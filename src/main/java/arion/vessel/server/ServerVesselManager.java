/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.vessel.server;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side owner of the vessel: spawns/despawns a real {@link FakeServerPlayer} registered with
 * the server's player list, and reports status. One vessel per server.
 *
 * <p>This runs on the server thread (the integrated server in single-player). The client reaches it
 * via {@code Minecraft.getInstance().getSingleplayerServer()} + {@code server.execute(...)} (Phase A);
 * a packet channel replaces those direct calls in Phase B for LAN support.
 *
 * <p>State is keyed by {@link MinecraftServer} in a {@link ConcurrentHashMap}. The integrated
 * server is recreated on each world load, but {@link #shutdown} (on {@code ServerStoppingEvent})
 * removes the entry, so a vessel never leaks across worlds. Using a concurrent map (rather than a
 * {@code WeakHashMap}) lets the client thread read vessel state via {@link #peek} without racing a
 * concurrent {@code computeIfAbsent} on the server thread.
 */
public final class ServerVesselManager {

    private static final ConcurrentHashMap<MinecraftServer, ServerVesselManager> BY_SERVER = new ConcurrentHashMap<>();

    private final MinecraftServer server;
    private volatile FakeServerPlayer vessel;
    private UUID hostUuid;          // the player who owns the vessel (respawn comes back to them)
    private UUID vesselUuid;        // last vessel's UUID — lets despawn scrub a lingering body even
                                    // after `this.vessel` has been cleared

    private ServerVesselManager(MinecraftServer server) {
        this.server = server;
    }

    /** Get (or lazily create) the manager for a server. Server thread. */
    public static ServerVesselManager get(MinecraftServer server) {
        return BY_SERVER.computeIfAbsent(server, ServerVesselManager::new);
    }

    /**
     * Non-creating lookup — safe to call from the client thread for a quick status read. Returns
     * null if no manager exists yet for this server (e.g. before {@code ServerStartingEvent}).
     */
    public static ServerVesselManager peek(MinecraftServer server) {
        return BY_SERVER.get(server);
    }

    /** Tear down on server stop — drop any vessel so it never survives into the next world. */
    public static void shutdown(MinecraftServer server) {
        ServerVesselManager m = BY_SERVER.remove(server);
        if (m != null) m.despawn();
    }

    public boolean hasVessel() {
        return vessel != null && vessel.isAlive();
    }

    /**
     * Spawn the vessel at the host player's position (so it appears next to whoever typed
     * {@code ai spawn}). Returns a status message for the host's chat.
     */
    public String spawn(String name, UUID hostPlayerUuid) {
        // If the previous vessel died and somehow lingered (auto-respawn normally handles death, but
        // a crash mid-respawn or a pre-respawn-build vessel could leave a dead body), drop it first.
        if (vessel != null && !vessel.isAlive()) {
            try { server.getPlayerList().remove(vessel); } catch (Throwable ignored) {}
            vessel = null;
        }
        if (hasVessel()) {
            return vessel.getGameProfile().name() + " is already here. ('ai despawn' first)";
        }
        ServerPlayer host = server.getPlayerList().getPlayer(hostPlayerUuid);
        if (host == null) return "Couldn't find you on the server — try again.";

        this.hostUuid = hostPlayerUuid;
        return createAndRegister(name, (ServerLevel) host.level(), host.position());
    }

    /**
     * Respawn the vessel after death. Called from {@link FakeServerPlayer#die} on the next server
     * tick (deferred, so we're outside the damage/tick chain). Tears the dead body down fully
     * (PlayerList + entity) and creates a fresh {@link FakeServerPlayer} at the host's current
     * position — the companion comes back to you. No-op if the vessel isn't actually dead (e.g. a
     * NeoForge death event cancelled it) or is already gone.
     */
    void respawnVessel() {
        if (vessel == null || vessel.isAlive()) return;
        String name = vessel.getGameProfile().name();
        Vec3 deathPos = vessel.position();
        UUID deadUuid = vessel.getUUID();
        arion.Arion.LOGGER.info("[Arion] vessel {} died — respawning at host", name);

        // Tear down the dead body fully. PlayerList.remove untracks the entity + drops the player
        // list / UUID-map entries; discard() guarantees the entity is gone even if tickDeath hadn't
        // reached remove(KILLED) yet.
        try { server.getPlayerList().remove(vessel); } catch (Throwable ignored) {}
        try { vessel.discard(); } catch (Throwable ignored) {}
        vessel = null;
        // Safety net: scrub any same-UUID stragglers before re-creating (same name → same offline
        // UUID, so a leftover entry would collide / stack a second body).
        purgeByUuid(deadUuid);

        ServerPlayer host = hostUuid != null ? server.getPlayerList().getPlayer(hostUuid) : null;
        ServerLevel level = host != null ? (ServerLevel) host.level() : server.overworld();
        Vec3 at = host != null ? host.position() : deathPos;
        createAndRegister(name, level, at);
        if (hostUuid != null) tellHost(server, hostUuid, name + " respawned at your side.");
    }

    /**
     * Create + register a fresh vessel named {@code name} at {@code pos} in {@code level}. Shared by
     * {@link #spawn} and {@link #respawnVessel}. Defensively purges any lingering same-UUID player
     * first, so the single-vessel invariant holds even if prior state was dirty.
     */
    private String createAndRegister(String name, ServerLevel level, Vec3 pos) {
        String clean = nameOrArion(name);
        GameProfile profile = new GameProfile(
                net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(clean), clean);
        purgeByUuid(profile.id());

        FakeServerPlayer fp = new FakeServerPlayer(server, level, clean);
        fp.snapTo(pos.x, pos.y, pos.z, 0.0f, 0.0f);
        fp.setOldPosAndRot();
        fp.setHealth(fp.getMaxHealth());
        fp.getFoodData().setFoodLevel(20);

        CommonListenerCookie cookie = new CommonListenerCookie(profile, 0, ClientInformation.createDefault(), false);
        try {
            server.getPlayerList().placeNewPlayer(
                    new FakeConnection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), fp, cookie);
        } catch (Throwable t) {
            arion.Arion.LOGGER.warn("ServerVesselManager: placeNewPlayer failed: {}", t.toString());
            try { fp.discard(); } catch (Throwable ignored) {}
            return "Couldn't spawn the vessel: " + t;
        }

        try { fp.connection.markClientLoaded(); } catch (Throwable ignored) {}

        // Force SURVIVAL. placeNewPlayer inherits the world's default game type (ServerPlayer ctor →
        // calculateGameModeForNewPlayer), so on a creative world the vessel would spawn creative →
        // abilities.invulnerable=true → Player.hurtServer returns false for ALL melee damage. Survival
        // clears that flag so the vessel is genuinely damageable.
        try {
            fp.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            fp.getAbilities().invulnerable = false;   // belt-and-suspenders; survival already clears it
            fp.onUpdateAbilities();
        } catch (Throwable t) {
            arion.Arion.LOGGER.warn("[Arion] forcing vessel SURVIVAL failed: {}", t.toString());
        }

        this.vessel = fp;
        this.vesselUuid = profile.id();
        return "Summoned " + clean + " (server-side, survival). Mobs can see it; it takes real damage.";
    }

    /**
     * Remove every player whose UUID matches {@code uuid} from the PlayerList + level. The host's
     * real UUID never equals the vessel's offline UUID, so the host is never touched. Idempotent.
     * This is the single-vessel safety net: even if a dead body lingered or a spawn raced, there's
     * never more than one Arion.
     */
    private void purgeByUuid(UUID uuid) {
        if (uuid == null) return;
        PlayerList pl = server.getPlayerList();
        for (ServerPlayer p : new java.util.ArrayList<>(pl.getPlayers())) {
            if (p.getUUID().equals(uuid)) {
                try { pl.remove(p); } catch (Throwable ignored) {}
                try { p.discard(); } catch (Throwable ignored) {}
            }
        }
    }

    /** Remove the vessel from the server. Also scrubs any lingering same-UUID body. */
    public String despawn() {
        UUID uuid = vessel != null ? vessel.getUUID() : vesselUuid;
        if (uuid == null) return "No vessel is active.";
        String name = vessel != null ? vessel.getGameProfile().name() : "Vessel";
        if (vessel != null) {
            try { server.getPlayerList().remove(vessel); } catch (Throwable ignored) {}
            try { vessel.discard(); } catch (Throwable ignored) {}
            vessel = null;
        }
        purgeByUuid(uuid);   // scrub any lingering body (e.g. a dead one this.vessel lost track of)
        return name + " despawned.";
    }

    /** One-line status for {@code ai vessel}. */
    public String status() {
        if (vessel == null || !vessel.isAlive()) return "No vessel. Summon one with 'ai spawn'.";
        return String.format("%s at [%d, %d, %d], hp=%d/%d (server-side)",
                vessel.getGameProfile().name(),
                (int) vessel.getX(), (int) vessel.getY(), (int) vessel.getZ(),
                (int) Math.ceil(vessel.getHealth()), (int) vessel.getMaxHealth());
    }

    /** Send a message to the host player (server-side chat). */
    public static void tellHost(MinecraftServer server, UUID hostPlayerUuid, String message) {
        ServerPlayer host = server.getPlayerList().getPlayer(hostPlayerUuid);
        if (host != null) {
            host.sendSystemMessage(Component.literal("[Arion] " + message));
        }
    }

    /** The vessel, or null — used by later phases (movement/combat) to drive the body. */
    public FakeServerPlayer vessel() {
        return vessel;
    }

    private static String nameOrArion(String name) {
        String clean = (name == null || name.isBlank()) ? "Arion" : name.trim();
        if (clean.length() > 16) clean = clean.substring(0, 16);
        return clean;
    }
}
