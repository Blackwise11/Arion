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
        // If the previous vessel died, it's still in the PlayerList (a fake player has no client to
        // request respawn, so it lingers dead). Re-spawning with the same name yields the same offline
        // UUID → collision. Drop the dead body first so a fresh spawn is clean.
        if (vessel != null && !vessel.isAlive()) {
            try { server.getPlayerList().remove(vessel); } catch (Throwable ignored) {}
            vessel = null;
        }
        if (hasVessel()) {
            return vessel.getGameProfile().name() + " is already here. ('ai despawn' first)";
        }
        ServerPlayer host = server.getPlayerList().getPlayer(hostPlayerUuid);
        if (host == null) return "Couldn't find you on the server — try again.";

        ServerLevel level = (ServerLevel) host.level();
        GameProfile profile = new GameProfile(
                net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(nameOrArion(name)),
                nameOrArion(name));
        FakeServerPlayer fp = new FakeServerPlayer(server, level, name);

        Vec3 at = host.position();
        fp.snapTo(at.x, at.y, at.z, host.getYRot(), host.getXRot());
        fp.setOldPosAndRot();
        fp.setHealth(fp.getMaxHealth());
        fp.getFoodData().setFoodLevel(20);

        // Register the vessel as a real player. The fake connection keeps it network-inert; the
        // server's player tick loop ticks it from here on.
        CommonListenerCookie cookie = new CommonListenerCookie(profile, 0, ClientInformation.createDefault(), false);
        try {
            server.getPlayerList().placeNewPlayer(new FakeConnection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), fp, cookie);
        } catch (Throwable t) {
            arion.Arion.LOGGER.warn("ServerVesselManager: placeNewPlayer failed: {}", t.toString());
            try { fp.discard(); } catch (Throwable ignored) {}
            return "Couldn't spawn the vessel: " + t;
        }

        // The vessel has no client to send the "finished loading" ack, so it would sit in the
        // server's client-load-wait limbo (which gates some behaviour and eventually tries to
        // disconnect — already neutralised in FakeConnection). Confirm load now so the server
        // treats it as a fully-joined player.
        try { fp.connection.markClientLoaded(); } catch (Throwable ignored) {}

        // Force SURVIVAL. placeNewPlayer inherits the world's default game type (ServerPlayer ctor →
        // calculateGameModeForNewPlayer), so on a creative world the vessel would spawn creative →
        // abilities.invulnerable=true → Player.hurtServer returns false for ALL melee damage. Survival
        // clears that flag so the vessel is genuinely damageable. A companion that can't be hurt can't
        // fight or be fought.
        try {
            fp.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            fp.getAbilities().invulnerable = false;   // belt-and-suspenders; survival already clears it
            fp.onUpdateAbilities();
        } catch (Throwable t) {
            arion.Arion.LOGGER.warn("[Arion] forcing vessel SURVIVAL failed: {}", t.toString());
        }

        this.vessel = fp;
        return "Summoned " + nameOrArion(name) + " (server-side, survival). Mobs can see it; it takes real damage.";
    }

    /** Remove the vessel from the server. */
    public String despawn() {
        if (vessel == null) return "No vessel is active.";
        String name = vessel.getGameProfile().name();
        try {
            server.getPlayerList().remove(vessel);
        } catch (Throwable ignored) {}
        try { vessel.discard(); } catch (Throwable ignored) {}
        vessel = null;
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
