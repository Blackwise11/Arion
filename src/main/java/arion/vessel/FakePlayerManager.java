/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.vessel;

import arion.config.ArionConfig;
import arion.combat.CombatController;
import arion.survival.SurvivalManager;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * Owns Arion's vessel (the AI's body): spawning, despawning, and status.
 *
 * <h3>Server-side vessel (Phase A)</h3>
 * The vessel is a real, server-side {@code ServerPlayer} (see
 * {@link arion.vessel.server.ServerVesselManager} and {@link arion.vessel.server.FakeServerPlayer}),
 * spawned on the integrated server Carpet-style via {@code PlayerList.placeNewPlayer} with a
 * network-inert {@link arion.vessel.server.FakeConnection}. Because the body is a genuine
 * server-tracked player, it renders to all clients, is targeted by mob AI, resolves a skin/name
 * from its {@code GameProfile}, and takes real damage — none of which the old client-only ghost
 * could do. The brain, LLM, API keys, and Baritone all stay client-side (BYOK unchanged).
 *
 * <p>This client-side manager is a thin front-end: {@link #spawn}, {@link #despawn}, and
 * {@link #status} hop to the integrated server ({@code Minecraft#getSingleplayerServer} +
 * {@code server.execute}) and report back. No client-side entity is created. One vessel at a time.
 *
 * <h3>Legacy client-side path (dormant)</h3>
 * The {@code FakePlayerEntity} / {@link VesselBaritoneBinder} / {@link VesselPlayerContext} /
 * {@code NoopPlayerController} machinery below is the PRE-server-side design (a client-only
 * {@code LocalPlayer} that borrowed the primary Baritone). It is retained for the Phase C
 * Baritone-pathfinding bridge but is dormant in Phase A — {@code vessel} is never set, so
 * {@link #tick}, {@link #hasBaritone}, {@link #commandHunt} etc. are no-ops until movement lands.
 */
public final class FakePlayerManager {

    private final ArionConfig config;
    private final SurvivalManager survival;
    private final CombatController combat;

    private FakePlayerEntity vessel;
    private IBaritone vesselBaritone;                  // the PRIMARY baritone while borrowed (null when not bound)
    private IPlayerContext originalContext;            // saved primary context, restored on despawn

    public FakePlayerManager(ArionConfig config) {
        this.config = config;
        this.survival = new SurvivalManager(config);
        this.combat = new CombatController(config);
        this.combat.setVesselMode(true); // packet-safe: no attack packets / reach buff
        this.combat.setHuntListener(reason ->
                arion.Arion.LOGGER.info("[Arion] vessel hunt ended ({})", reason));
    }

    /**
     * True if a vessel exists — client-side (legacy) OR server-side (Phase A). In Phase A the vessel
     * lives on the integrated server, so this peeks at {@link arion.vessel.server.ServerVesselManager}.
     */
    public boolean hasVessel() {
        if (vessel != null) return true;
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) return false;
        var svm = arion.vessel.server.ServerVesselManager.peek(server);
        return svm != null && svm.hasVessel();
    }
    public FakePlayerEntity vessel() { return vessel; }

    /** The vessel's own Baritone, or null if not bound (see class doc / spawn message). */
    public IBaritone baritone() { return vesselBaritone; }

    /** True when the vessel has a working Baritone driving it. */
    public boolean hasBaritone() { return vessel != null && vesselBaritone != null; }

    /** The vessel's own (packet-safe) combat controller. */
    public CombatController combat() { return combat; }

    /** Whether the vessel is currently hunting. */
    public boolean isVesselHunting() { return combat.isActive(); }

    /**
     * Order the vessel to hunt mobs (Phase 4). Only when a Baritone is bound AND
     * vesselCombat is enabled. Returns a status message, or null if it started.
     */
    public String commandHunt(String mobArg) {
        if (!hasBaritone()) return "The vessel needs its Baritone to hunt (single-player only).";
        if (!config.vesselCombat.get()) return "Vessel combat is disabled (vesselCombat=false in config).";
        combat.hunt(vesselBaritone, mobArg);
        return null;
    }

    /**
     * Summon the vessel. Phase A: spawn a real server-side {@code ServerPlayer} on the integrated
     * server (Carpet-style). The body appears at the host player's position, renders to all clients,
     * is targeted by mob AI, and takes real damage. No movement yet (Phase B).
     *
     * <p>Because the vessel now lives server-side, this hops to the integrated server via
     * {@code server.execute(...)}; the real result is reported back to the host as a chat message.
     *
     * @param name display name; blank/null defaults to "Arion"
     * @return an immediate ack message for chat
     */
    public String spawn(String name) {
        if (!config.vesselEnabled.get()) return "The vessel is disabled (vesselEnabled=false in config).";
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = mc.player;
        if (self == null) return "No world loaded.";
        var server = mc.getSingleplayerServer();
        if (server == null) {
            return "The server-side vessel needs a single-player / integrated server. (LAN + dedicated come later.)";
        }
        final String clean = cleanName(name);
        final UUID hostUuid = self.getUUID();
        server.execute(() -> {
            String result = arion.vessel.server.ServerVesselManager.get(server).spawn(clean, hostUuid);
            arion.vessel.server.ServerVesselManager.tellHost(server, hostUuid, result);
        });
        return "Summoning " + clean + " (server-side)…";
    }

    /**
     * Remove the vessel. Phase A: despawn the server-side body on the integrated server. Any
     * leftover client-side vessel (none in Phase A) is also cleaned up defensively.
     */
    public String despawn() {
        // defensive cleanup of any stale client-side vessel (not created in Phase A)
        if (vessel != null) {
            destroyVesselBaritone();
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                mc.level.removeEntity(vessel.getId(), Entity.RemovalReason.DISCARDED);
            }
            try { vessel.discard(); } catch (Throwable ignored) {}
            vessel = null;
        }
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) return "No vessel is active.";
        if (mc.player == null) return "No vessel is active.";
        final UUID hostUuid = mc.player.getUUID();
        server.execute(() -> {
            String result = arion.vessel.server.ServerVesselManager.get(server).despawn();
            arion.vessel.server.ServerVesselManager.tellHost(server, hostUuid, result);
        });
        return "Despawning the vessel…";
    }

    /** Drop the vessel without chat (world unload / logout). */
    public void reset() {
        try { combat.reset(vesselBaritone != null ? vesselBaritone.getPlayerContext() : null); }
        catch (Throwable ignored) {}
        destroyVesselBaritone();
        if (vessel != null) {
            try { vessel.discard(); } catch (Throwable ignored) {}
            vessel = null;
        }
        originalContext = null;
    }

    /** Order the vessel to walk to a position (used when no Baritone is bound). */
    public void commandWalkTo(double x, double y, double z) {
        if (vessel != null) {
            // a simple seek — set a movement goal via the entity's own driver
            vessel.seekTo(x, y, z);
        }
    }

    /** Order the vessel to stop where it is (pathing + any hunt). */
    public void commandStop() {
        if (vessel != null) vessel.stopSeeking();
        if (vesselBaritone != null) {
            try {
                if (combat.isActive()) combat.stop(vesselBaritone);
                vesselBaritone.getPathingBehavior().cancelEverything();
            } catch (Throwable ignored) {}
        }
    }

    /**
     * One-line status for {@code ai vessel}. Phase A: read the server-side vessel's state. This is a
     * read-only peek via {@link arion.vessel.server.ServerVesselManager#peek} (non-creating, safe from
     * the client thread). A stale client-side vessel (none in Phase A) is reported if present.
     */
    public String status() {
        if (vessel != null) {
            return String.format("%s at [%d, %d, %d], hp=%d/%d, baritone=%s%s (client-side)",
                    vessel.getName().getString(),
                    (int) vessel.getX(), (int) vessel.getY(), (int) vessel.getZ(),
                    (int) Math.ceil(vessel.getHealth()), (int) vessel.getMaxHealth(),
                    vesselBaritone != null ? "bound" : "none",
                    combat.isActive() ? ", hunting" : "");
        }
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) return "No vessel (server-side vessel needs a single-player world).";
        var svm = arion.vessel.server.ServerVesselManager.peek(server);
        return svm == null ? "No vessel. Summon one with 'ai spawn'." : svm.status();
    }

    /**
     * Per-tick upkeep. When a Baritone is bound, it self-ticks (Baritone's
     * MixinMinecraft fires onTick for every registered Baritone) and we translate its
     * forced-inputs into vessel movement. When not bound, the vessel just seeks.
     */
    public void tick() {
        if (vessel == null) return;
        if (!vessel.isAlive() || vessel.level() == null) { reset(); return; }

        if (vesselBaritone != null) {
            try {
                IInputOverrideHandler io = vesselBaritone.getInputOverrideHandler();
                vessel.setBaritoneInput(
                        io.isInputForcedDown(Input.MOVE_FORWARD),
                        io.isInputForcedDown(Input.MOVE_BACK),
                        io.isInputForcedDown(Input.MOVE_LEFT),
                        io.isInputForcedDown(Input.MOVE_RIGHT),
                        io.isInputForcedDown(Input.JUMP),
                        io.isInputForcedDown(Input.SNEAK),
                        io.isInputForcedDown(Input.SPRINT));
            } catch (Throwable ignored) {}
        }

        try { vessel.tick(); } catch (Throwable ignored) {}

        if (vesselBaritone != null) {
            try {
                survival.tick(vesselBaritone, vesselBaritone.getPlayerContext(), b -> {});
                // Phase 4: the vessel fights on its own (packet-safe — see CombatController vesselMode)
                if (config.vesselCombat.get()) {
                    combat.tick(vesselBaritone, vesselBaritone.getPlayerContext());
                }
            } catch (Throwable ignored) {}
        }
    }

    // --------------------------------------------------------------- naming

    /** Clamp/blank-default a vessel name (max 16 chars, matching Minecraft name limits). */
    private static String cleanName(String name) {
        String clean = (name == null || name.isBlank()) ? "Arion" : name.trim();
        if (clean.length() > 16) clean = clean.substring(0, 16);
        return clean;
    }

    // ------------------------------------------------------------- baritone

    /**
     * Borrow the PRIMARY baritone for the vessel, honouring the safety gate.
     *
     * Baritone's {@code createBaritone(Minecraft)} returns the already-registered
     * primary (there is only one {@code Minecraft} instance), so we cannot create a
     * separate baritone for the vessel. Instead we reflectively swap the primary's
     * {@code playerContext} for a vessel-bound wrapper and restore it on despawn.
     * While borrowed, the real player's own Baritone is unavailable.
     */
    private boolean tryBindBaritone(Minecraft mc, FakePlayerEntity fp) {
        boolean allowedHere = mc.isSingleplayer() || config.vesselBaritoneOnServers.get();
        if (!allowedHere) return false;
        try {
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primary == null) return false;
            IPlayerContext saved = VesselBaritoneBinder.bind(primary, fp);
            if (saved == null) return false; // reflection failed; primary left untouched
            this.originalContext = saved;
            this.vesselBaritone = primary;
            return true;
        } catch (Throwable t) {
            arion.Arion.LOGGER.warn("Couldn't borrow the primary Baritone for the vessel: {}", t.toString());
            return false;
        }
    }

    /** Return the borrowed primary baritone to the real player. */
    private void destroyVesselBaritone() {
        if (vesselBaritone != null) {
            try { VesselBaritoneBinder.unbind(vesselBaritone, originalContext); } catch (Throwable ignored) {}
        }
        vesselBaritone = null;
        originalContext = null;
    }
}
