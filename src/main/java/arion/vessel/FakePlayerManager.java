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
import baritone.api.utils.IPlayerController;
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
    private IPlayerContext boundContext;               // the primary's redirected context; overrides cleared on despawn
    private Boolean savedFreeLook;                     // Baritone settings restored on unbind
    private Boolean savedSmoothLook;
    private java.util.function.Consumer<String> huntEndHandler;  // routes vessel hunt-ends to the controller

    // Last bind outcome: null = bound (or never attempted); otherwise the human-readable reason
    // the borrow failed. Surfaced in `ai vessel` status and logged once per distinct reason so a
    // silently-failing bind (the previous bug — `ai mine` then ran on the HOST) is now visible.
    private String bindReason = null;

    // Server-side vessel identity (Phase A onwards). Set in spawn() so the client can compute the
    // vessel's offline UUID + look up its client body for the Stage 2 Baritone shadow. Volatile:
    // written on the client thread in spawn(), read in the tick() / control paths.
    private volatile String vesselName;
    private volatile UUID vesselUuid;

    // throttled debug trace of the forwarded Baritone frame (debugMode only)
    private int frameLogTicks = 0;
    // inventory snapshot cadence (server -> shadow)
    private int inventorySyncTicks = 0;
    // M4.1: native vessel hunt tracking (Baritone ICombatProcess) + the auto-defend handover
    private boolean vesselHuntActive;
    private int vesselHuntTicks;
    private baritone.api.process.AutoDefendMode savedAutoDefendMode;

    public FakePlayerManager(ArionConfig config) {
        this.config = config;
        this.survival = new SurvivalManager(config);
        this.combat = new CombatController(config);
        this.combat.setVesselMode(true); // packet-safe: no attack packets / reach buff
        this.combat.setHuntListener(reason -> {
            arion.Arion.LOGGER.info("[Arion] vessel hunt ended ({})", reason);
            // also route to the controller so the brain re-decides the next action, like the
            // real-player path. Dormant in Phase A (vessel can't hunt until its Baritone binds).
            java.util.function.Consumer<String> h = huntEndHandler;
            if (h != null) { try { h.accept(reason); } catch (Throwable ignored) {} }
        });
        // surface vessel combat state transitions in the debug log + chat HUD, like the
        // real-player fighter (ArionController wires its own debug listener). Dormant in
        // Phase A (the vessel can't hunt until its Baritone is bound) but zero-cost + ready.
        this.combat.setDebugListener(arion.ArionLog::debugChat);
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

    /** The vessel's profile name (null when no vessel). Used to filter its own remote entity. */
    public String vesselName() { return vesselName; }

    /** The vessel's offline UUID (null when no vessel). */
    public java.util.UUID vesselUuid() { return vesselUuid; }

    /** The vessel's own Baritone, or null if not bound (see class doc / spawn message). */
    public IBaritone baritone() { return vesselBaritone; }

    /** True when the vessel has a working Baritone driving it. */
    public boolean hasBaritone() { return vesselBaritone != null; }

    /**
     * Manually (re)attempt the Baritone borrow and return a one-line, always-visible status.
     * Wired to {@code ai vessel bind} so the user can force a bind attempt and read the exact
     * outcome in chat — the decisive diagnostic when {@code ai mine} runs on the host.
     */
    public String rebind() {
        Minecraft mc = Minecraft.getInstance();
        if (vessel == null) {
            return "No vessel shadow — summon one first with 'ai spawn'.";
        }
        if (vesselBaritone != null) {
            // already bound — but still run the host-match proof so the user can see the
            // exact reason `ai mine` would (or wouldn't) drive the host.
            return "Vessel Baritone already bound. " + hostMatchProof(mc);
        }
        // reset the dedup key so tryBindBaritone re-announces even if the reason is unchanged
        bindReason = null;
        boolean ok = tryBindBaritone(mc, vessel);
        if (ok) return "Vessel Baritone bound — ai mine/goto/etc. will drive the vessel. " + hostMatchProof(mc);
        return "Vessel Baritone NOT bound: " + (bindReason != null ? bindReason : "unknown");
    }

    /**
     * Hand Baritone back to the real player <b>without</b> removing the vessel.
     *
     * <p>Cancels the vessel's pathing/combat first (so no borrowed goal keeps running once the
     * context points at the host again), clears the redirect, restores the Baritone settings the
     * bridge forced, and releases the server body's last input frame so it stops where it stands.
     * The vessel keeps existing: {@code ai <verb>} now runs on YOU, and {@code ai vessel come|goto}
     * still works through the server-side straight-line seek until you {@code ai vessel bind} again.
     *
     * @return a one-line status for chat
     */
    public String unbind() {
        if (!hasVessel()) return "No vessel. Summon one with 'ai spawn'.";
        if (vesselBaritone == null) return "Baritone is already on you (vessel unbound).";
        destroyVesselBaritone();
        // stop the body (also releases the Baritone input frame) so it doesn't walk off on a
        // stale frame after the bridge is gone
        commandStop();
        arion.ArionLog.debug("vessel baritone unbound — control returned to the host");
        return "Baritone returned to YOU. The vessel stays put; 'ai vessel bind' hands it back, "
                + "'ai vessel come|goto' still works via the server-side seek.";
    }

    /**
     * The decisive runtime proof: after binding, {@code getBaritoneForPlayer(mc.player)} MUST
     * return null — that's the exact check every Baritone input mixin makes before driving a
     * player. If it returns the primary, the HOST will be driven (the original bug) even though
     * {@code hasBaritone()} is true. Returns a short human-readable verdict.
     */
    private String hostMatchProof(Minecraft mc) {
        try {
            if (mc.player == null) return "(no host player to verify)";
            IBaritone match = baritone.api.BaritoneAPI.getProvider().getBaritoneForPlayer(mc.player);
            if (match == null) {
                return "Verified: getBaritoneForPlayer(YOU)=null → Baritone will NOT drive you. ✅";
            }
            return "WARNING: getBaritoneForPlayer(YOU) still returns the baritone → the HOST will be "
                    + "driven, not the vessel. (swap did not redirect; this is a bug)";
        } catch (Throwable t) {
            return "(host-match proof threw: " + t.getClass().getSimpleName() + ")";
        }
    }

    /** The vessel's own (packet-safe) combat controller. */
    public CombatController combat() { return combat; }

    /**
     * Register a handler fired when the vessel's hunt ends, so the controller can re-ask the
     * brain for the next action (mirrors the real-player path). Wired from {@link arion.Arion}.
     */
    public void setHuntEndHandler(java.util.function.Consumer<String> handler) {
        this.huntEndHandler = handler;
    }

    /** Whether the vessel is currently hunting (native Baritone CombatProcess). */
    public boolean isVesselHunting() {
        if (vesselBaritone == null) return false;
        try {
            return vesselBaritone.getCombatProcess().isActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /** True while the vessel body is actually blocking with a shield (server-published snapshot). */
    public boolean isVesselShielding() {
        arion.vessel.server.FakeServerPlayer sv = serverVessel();
        return sv != null && sv.isShielding();
    }

    /**
     * Order the vessel to hunt mobs (Phase 4). Only when a Baritone is bound AND
     * vesselCombat is enabled. Returns a status message, or null if it started.
     */
    public String commandHunt(String mobArg) {
        arion.ArionLog.debug("vessel commandHunt: " + mobArg);
        if (!hasBaritone()) {
            arion.ArionLog.debug("vessel commandHunt refused: no Baritone bound");
            return "The vessel needs its Baritone to hunt (single-player only).";
        }
        if (!config.vesselCombat.get()) {
            arion.ArionLog.debug("vessel commandHunt refused: vesselCombat=false");
            return "Vessel combat is disabled (vesselCombat=false in config).";
        }
        // Native ICombatProcess: real attacks through VesselServerPlayerController.attack, plus
        // Baritone's own melee + projectile shield (combatUseShield). The legacy Arion fighter in
        // vesselMode only body-blocked, so it is no longer used for vessel hunts.
        try {
            vesselBaritone.getCombatProcess().hunt(mobArg);
            vesselHuntActive = true;
            vesselHuntTicks = 0;
            arion.ArionLog.debug("vessel hunt via native ICombatProcess: " + mobArg);
            return null;
        } catch (Throwable t) {
            arion.ArionLog.debug("vessel commandHunt failed: " + t);
            return "Vessel hunt failed: " + t;
        }
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
        // record the vessel identity for the client (Stage 2 shadow lookup). The entity UUID ==
        // profile id == offline UUID derived from the name — deterministic, so the client can
        // compute it now without waiting for a server round-trip.
        this.vesselName = clean;
        this.vesselUuid = net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(clean);
        // Create the client-side shadow + borrow the primary Baritone onto it BEFORE the server
        // spawn completes, so the moment the vessel's RemotePlayer appears in the client level the
        // shadow begins mirroring it and Baritone can drive. Falls back gracefully if binding fails
        // (the vessel still walks via straight-line seek).
        bindShadow(mc, self);
        server.execute(() -> {
            String result = arion.vessel.server.ServerVesselManager.get(server).spawn(clean, hostUuid);
            arion.vessel.server.ServerVesselManager.tellHost(server, hostUuid, result);
        });
        return "Summoning " + clean + " (server-side)…";
    }

    /**
     * Create the invisible {@link FakePlayerEntity} shadow at the host's position and borrow the
     * primary Baritone onto it. Idempotent — if a shadow is already bound, does nothing. On any
     * failure the vessel simply runs without Baritone (straight-line seek only).
     */
    private void bindShadow(Minecraft mc, LocalPlayer host) {
        if (vesselBaritone != null) return; // already bound
        try {
            com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(vesselUuid, vesselName);
            FakePlayerEntity shadow = new FakePlayerEntity(
                    mc, (net.minecraft.client.multiplayer.ClientLevel) mc.level, mc.getConnection(), profile);
            shadow.enableShadowMode();
            shadow.syncFrom(host.getX(), host.getY(), host.getZ(), host.getYRot());
            this.vessel = shadow; // the legacy `vessel` field now holds the shadow
            if (tryBindBaritone(mc, shadow)) {
                arion.ArionLog.chat("Vessel Baritone bound — ai mine/goto/etc. will drive the vessel.");
            } else {
                arion.ArionLog.chat("Vessel shadow up, but Baritone NOT bound — will retry each tick. Check 'ai vessel'.");
            }
        } catch (Throwable t) {
            arion.Arion.LOGGER.warn("Couldn't create the vessel shadow: {}", t.toString());
        }
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
        // stop the server body's movement frame before tearing it down
        arion.vessel.server.FakeServerPlayer sv = serverVessel();
        if (sv != null) { try { sv.clearBaritoneInput(); } catch (Throwable ignored) {} }
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
        arion.vessel.server.FakeServerPlayer sv = serverVessel();
        if (sv != null) { try { sv.clearBaritoneInput(); } catch (Throwable ignored) {} }
        destroyVesselBaritone();
        if (vessel != null) {
            try { vessel.discard(); } catch (Throwable ignored) {}
            vessel = null;
        }
        vesselName = null;
        vesselUuid = null;
        boundContext = null;
        bindReason = null;
    }

    /**
     * Order the vessel to walk to a position. Phase B / M1: hop to the integrated server and drive
     * the real {@code FakeServerPlayer} body (straight-line seek — the Baritone path is used when
     * the caller has one, see {@code ai vessel goto}).
     *
     * <p><b>Never</b> route this to the client {@code vessel} field: that is the shadow, which is
     * a pure position feeder and is never ticked ({@code FakePlayerEntity.tick()} returns
     * immediately in shadow mode), so a seek stored there is never stepped. That branch is why
     * {@code ai vessel goto} used to do nothing at all.
     */
    public void commandWalkTo(double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) {
            arion.Arion.LOGGER.warn("[Arion] vessel walk requested without an integrated server — "
                    + "the server-side body needs single-player (LAN/dedicated is a later phase)");
            return;
        }
        server.execute(() -> arion.vessel.server.ServerVesselManager.get(server).walkTo(x, y, z));
    }

    /** Order the vessel to stop where it is (pathing + any seek + any hunt). */
    public void commandStop() {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server != null) {
            server.execute(() -> arion.vessel.server.ServerVesselManager.get(server).stopWalk());
        }
        if (vesselBaritone != null) {
            try {
                if (combat.isActive()) combat.stop(vesselBaritone);
                vesselBaritone.getCombatProcess().stop();   // native hunt / auto-defend
            } catch (Throwable ignored) {}
            try {
                vesselBaritone.getPathingBehavior().cancelEverything();
            } catch (Throwable ignored) {}
        }
        vesselHuntActive = false;
        // release the server vessel's movement frame so it stops walking immediately
        arion.vessel.server.FakeServerPlayer sv = serverVessel();
        if (sv != null) { try { sv.clearBaritoneInput(); } catch (Throwable ignored) {} }
    }

    /**
     * Order the vessel to eat from its hotbar. The body is server-side, so this hops to the
     * integrated server and reports a refusal (not hungry / no food) back to the host. The
     * actual food use is a real timed use — hunger and saturation change through vanilla.
     */
    public void commandEat() {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) {
            arion.ArionLog.chat("Vessel eat needs an integrated server.");
            return;
        }
        final java.util.UUID host = mc.player != null ? mc.player.getUUID() : null;
        server.execute(() -> {
            String r = arion.vessel.server.ServerVesselManager.get(server).eat();
            if (r != null && host != null) {
                arion.vessel.server.ServerVesselManager.tellHost(server, host, "Vessel: " + r);
            }
        });
    }

    /** Raise/lower the vessel's offhand shield; hops to the server thread. */
    public void commandShield(boolean raise, double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> arion.vessel.server.ServerVesselManager.get(server)
                .shield(raise, x, y, z));
    }

    /**
     * 'ai shield test': hold the vessel's offhand shield for ~2 s and log the authoritative state
     * (using/blocking/offhand) when the window closes. Returns a chat-ready ack.
     */
    public String testVesselShield() {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) return "Shield test needs an integrated server.";
        if (!hasVessel()) return "No vessel. Summon one with 'ai spawn'.";
        server.execute(() -> arion.vessel.server.ServerVesselManager.get(server).testShield(40));
        return "Vessel shield test: holding the offhand shield for 2 s — watch the body, then "
                + "check 'ai shield status' (raised on the vessel) or latest.log for "
                + "'vessel shield test: using=true blocking=true'.";
    }

    /**
     * Open the vessel's live inventory on the host as a 4-row chest UI. The host can move items
     * in/out; the vessel body's inventory is the real container, so Baritone sees the changes
     * (and the M1.5 shadow sync mirrors them) after the normal sync delay.
     *
     * @return an immediate status message on failure, or null when the open request was sent
     */
    public String openInventory() {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) return "Vessel inventory needs a single-player / integrated server.";
        if (!hasVessel()) return "No vessel. Summon one with 'ai spawn'.";
        LocalPlayer self = mc.player;
        if (self == null) return "No world loaded.";
        final java.util.UUID hostUuid = self.getUUID();
        server.execute(() -> {
            String result = arion.vessel.server.ServerVesselManager.get(server)
                    .openInventory(server.getPlayerList().getPlayer(hostUuid));
            if (result != null) {
                arion.vessel.server.ServerVesselManager.tellHost(server, hostUuid, result);
            }
        });
        return null;
    }

    /**
     * One-line status for {@code ai vessel}. Phase A: read the server-side vessel's state. This is a
     * read-only peek via {@link arion.vessel.server.ServerVesselManager#peek} (non-creating, safe from
     * the client thread). A stale client-side vessel (none in Phase A) is reported if present.
     */
    public String status() {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) return "No vessel (server-side vessel needs a single-player world).";
        var svm = arion.vessel.server.ServerVesselManager.peek(server);
        if (svm == null) return "No vessel. Summon one with 'ai spawn'.";
        String base = svm.status();
        // annotate with the Baritone bridge state (the server body doesn't know about it)
        if (vesselBaritone != null) {
            base += vesselBaritone.getPathingBehavior().isPathing() ? ", baritone=pathing" : ", baritone=bound";
        } else {
            base += ", baritone=UNBOUND" + (bindReason != null ? " (" + bindReason + ")" : "")
                    + " — ai <verb> runs on YOU, not the vessel";
        }
        // shadow-vs-body position: the single most useful diagnostic when movement does nothing.
        // Baritone plans from the shadow, so a stale shadow (host position) means paths are wrong.
        if (vessel != null) {
            base += String.format(java.util.Locale.ROOT, ", shadow=[%d, %d, %d]",
                    (int) Math.floor(vessel.getX()), (int) Math.floor(vessel.getY()), (int) Math.floor(vessel.getZ()));
            var sv = serverVessel();
            if (sv != null) {
                double d2 = vessel.distanceToSqr(sv);
                if (d2 > 4.0) base += " (STALE vs body)";
            }
        }
        return base;
    }

    /**
     * Per-tick upkeep. With the Baritone bridge bound (Phase C / M2):
     * <ol>
     *   <li>mirror the server vessel's client-visible {@code RemotePlayer} transform into the
     *       shadow, so Baritone pathfinds from the vessel's real position;</li>
     *   <li>forward Baritone's forced inputs (forward/back/left/right/jump/sneak/sprint) to the
     *       server vessel's input frame, so its physics move it the way Baritone expects.</li>
     * </ol>
     * The shadow itself is non-physical (shadow mode), so we never tick it. Survival/combat reflexes
     * for the vessel tick against the borrowed baritone's context, as before.
     */
    public void tick() {
        if (vessel == null) return;
        // The shadow is never ticked (it is a position feeder only), so nothing advances the
        // vanilla attack-strength ticker that Player.tick() normally increments. Baritone's
        // CombatProcess gates every melee strike on getAttackStrengthScale() >= 0.9, so without
        // this the vessel would approach a mob and never swing (attack() never reached).
        vessel.tickAttackStrength();
        // Mirror the shield config onto the server body (volatile writes, cheap). The body owns
        // its own projectile reflex on the server thread, so it needs autoShield/range/hold —
        // including the runtime 'ai shield on|off' override that lives in the controller.
        arion.vessel.server.FakeServerPlayer shieldBody = serverVessel();
        if (shieldBody != null) {
            var controller = arion.Arion.controller();
            boolean autoShield = controller != null
                    ? controller.autoShieldEnabled() : config.autoShield.get();
            shieldBody.configureShield(autoShield, config.shieldRange.get(), config.shieldHoldTicks.get());
            // Baritone gates every swing on the SHADOW's getAttackStrengthScale, but the swing resets
            // the BODY's ticker (attack + swing are server-side). Mirror the body so the gate waits
            // for a real full charge instead of firing at partial damage every cooldown tick.
            vessel.syncAttackStrength(shieldBody.attackStrengthTickerValue(),
                    shieldBody.getAttackStrengthScale(0.5f));
        }
        if (vesselBaritone == null) {
            // Shadow exists but the spawn-time borrow failed (e.g. the primary Baritone wasn't
            // ready yet, or a gate rejected it). Retry every tick until it sticks — without this,
            // hasBaritone() stays false forever and every `ai <verb>` silently runs on the HOST.
            // The borrow needs only the shadow + the primary baritone, NOT the server vessel, so
            // we do NOT gate on serverVessel() (that race would otherwise stall the retry forever).
            // Throttled logging: tryBindBaritone only logs a WARN when the reason changes.
            tryBindBaritone(Minecraft.getInstance(), vessel);
            return;
        }

        // M4.1: a native hunt (ICombatProcess) runs until the process goes inactive. There is no
        // completion callback, so poll here and synthesize the reason the controller understands
        // ("no target found" / "hunt timed out" drive a re-plan, "hunt ended" does not).
        if (vesselHuntActive) {
            vesselHuntTicks++;
            boolean active;
            try { active = vesselBaritone.getCombatProcess().isActive(); }
            catch (Throwable t) { active = false; }
            if (!active) {
                vesselHuntActive = false;
                String reason = vesselHuntEndReason();
                arion.ArionLog.debug("vessel native hunt ended (" + reason + ")");
                java.util.function.Consumer<String> h = huntEndHandler;
                if (h != null) { try { h.accept(reason); } catch (Throwable ignored) {} }
            }
        }

        // 1. Sync the shadow from the body's AUTHORITATIVE pose (a server-thread snapshot published
        //    each server tick), NOT from the client-visible RemotePlayer. The latter is broadcast on
        //    a throttle and interpolated client-side, so it trails the body by ~a block at walking
        //    speed; Baritone would then aim at the next node from a phantom position, overshoot,
        //    turn back, and the body visibly circles before settling ("spins, then moves").
        arion.vessel.server.FakeServerPlayer sv = serverVessel();
        if (sv != null) {
            arion.vessel.server.FakeServerPlayer.Pose p = sv.pose();
            if (p.tick() >= 0) {
                vessel.syncFrom(p.x(), p.y(), p.z(), p.onGround(),
                        new net.minecraft.world.phys.Vec3(p.vx(), p.vy(), p.vz()));
            }
        }

        // 2. Apply Baritone's aim to the shadow, then forward the frame to the server body.
        //
        // Baritone normally sets the context player's rotation from LookBehavior.onPlayerUpdate,
        // fired by its MixinClientPlayerEntity (PRE, inside LocalPlayer.tick) and MixinMinecraft
        // (POST, after tickEntities) — but ONLY when getBaritoneForPlayer(mc.player) resolves.
        // While the vessel borrows the primary, the host no longer resolves to it, so those mixin
        // hooks are dead and LookBehavior never runs: the shadow's yaw stays frozen and the body
        // only ever walks in its spawn direction ("moves forward, never turns").
        //
        // Firing the same events ourselves reproduces the vanilla loop for the shadow: PRE makes
        // LookBehavior apply the pending movement target's rotation (and PathingBehavior's
        // autoJump bookkeeping), POST restores/clears it. Read the rotation BETWEEN the two so we
        // forward the aimed values, not the ones POST reverts.
        if (sv != null) {
            try {
                vesselBaritone.getGameEventHandler()
                        .onPlayerUpdate(new baritone.api.event.events.PlayerUpdateEvent(
                                baritone.api.event.events.type.EventState.PRE));
            } catch (Throwable ignored) {}
            try {
                IInputOverrideHandler io = vesselBaritone.getInputOverrideHandler();
                sv.setBaritoneInputFrame(
                        vessel.getYRot(),
                        vessel.getXRot(),
                        vessel.getInventory().getSelectedSlot(),
                        io.isInputForcedDown(Input.MOVE_FORWARD),
                        io.isInputForcedDown(Input.MOVE_BACK),
                        io.isInputForcedDown(Input.MOVE_LEFT),
                        io.isInputForcedDown(Input.MOVE_RIGHT),
                        io.isInputForcedDown(Input.JUMP),
                        io.isInputForcedDown(Input.SNEAK),
                        io.isInputForcedDown(Input.SPRINT));
                if (++frameLogTicks % 20 == 0) {
                    var host = Minecraft.getInstance().player;
                    arion.ArionLog.debug(String.format(java.util.Locale.ROOT,
                            "vessel frame yaw=%.1f pitch=%.1f fwd=%s jump=%s "
                                    + "shadow=[%.2f,%.2f,%.2f] body=[%.2f,%.2f,%.2f] d=%.2f "
                                    + "host=[%.1f,%.1f,%.1f] dhost=%.1f",
                            vessel.getYRot(), vessel.getXRot(),
                            io.isInputForcedDown(Input.MOVE_FORWARD),
                            io.isInputForcedDown(Input.JUMP),
                            vessel.getX(), vessel.getY(), vessel.getZ(),
                            sv.getX(), sv.getY(), sv.getZ(),
                            Math.sqrt(vessel.distanceToSqr(sv)),
                            host == null ? 0 : host.getX(),
                            host == null ? 0 : host.getY(),
                            host == null ? 0 : host.getZ(),
                            host == null ? -1 : Math.sqrt(host.distanceToSqr(sv))));
                }
            } catch (Throwable ignored) {}
            try {
                vesselBaritone.getGameEventHandler()
                        .onPlayerUpdate(new baritone.api.event.events.PlayerUpdateEvent(
                                baritone.api.event.events.type.EventState.POST));
            } catch (Throwable ignored) {}
        }

        // 3. Mirror the body's real inventory into the shadow every 2 ticks. Baritone reads the
        //    SHADOW's inventory (tool choice, placeability, throwaway), so the shadow must see what
        //    the body actually carries. Copies are made on the server thread, applied on the client
        //    thread. The reverse direction — hotbar selection and slot swaps — travels in the input
        //    frame and through VesselServerPlayerController.windowClick.
        if (sv != null && ++inventorySyncTicks % 2 == 0) {
            var srv = Minecraft.getInstance().getSingleplayerServer();
            if (srv != null) {
                srv.execute(() -> {
                    arion.vessel.server.FakeServerPlayer body = serverVessel();
                    if (body == null) return;
                    int size = body.getInventory().getContainerSize();
                    net.minecraft.world.item.ItemStack[] snapshot =
                            new net.minecraft.world.item.ItemStack[size];
                    for (int i = 0; i < size; i++) snapshot[i] = body.getInventory().getItem(i).copy();
                    Minecraft.getInstance().execute(() -> {
                        if (vessel == null) return;
                        int n = Math.min(size, vessel.getInventory().getContainerSize());
                        for (int i = 0; i < n; i++) vessel.getInventory().setItem(i, snapshot[i]);
                    });
                });
            }
        }

        // 4. vessel survival/combat reflexes (gated by config) tick against the borrowed context
        try {
            survival.tick(vesselBaritone, vesselBaritone.getPlayerContext(), b -> {});
            if (config.vesselCombat.get()) {
                combat.tick(vesselBaritone, vesselBaritone.getPlayerContext());
            }
        } catch (Throwable ignored) {}
    }

    /** Look up the live server vessel (off-thread-safe peek). */
    private arion.vessel.server.FakeServerPlayer serverVessel() {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) return null;
        var svm = arion.vessel.server.ServerVesselManager.peek(server);
        return svm != null ? svm.vessel() : null;
    }

    /**
     * Classify a finished native hunt using the vocabulary the controller expects. There is no
     * completion callback on {@code ICombatProcess}, so this infers the outcome: a timeout when
     * the process ran to {@code combatMaxDurationSeconds}, "no target found" when nothing hostile
     * is left nearby, otherwise a plain end (user stop, retreat, death).
     */
    private String vesselHuntEndReason() {
        int maxSec = 0;
        try { maxSec = config.combatMaxDurationSeconds.get(); } catch (Throwable ignored) {}
        if (maxSec > 0 && vesselHuntTicks >= maxSec * 20) return "hunt timed out";
        try {
            var ctx = vesselBaritone != null ? vesselBaritone.getPlayerContext() : null;
            var p = ctx != null ? ctx.player() : null;
            if (p != null) {
                boolean any = ctx.entitiesStream().anyMatch(e -> e != p
                        && e.distanceToSqr(p) <= 12 * 12
                        && isHostile(e));
                if (!any) return "no target found";
            }
        } catch (Throwable ignored) {}
        return "hunt ended";
    }

    /** Same hostility rule as the combat layer: type + proximity, never Mob.getTarget(). */
    private static boolean isHostile(net.minecraft.world.entity.Entity e) {
        return e instanceof net.minecraft.world.entity.monster.Monster
                || e instanceof net.minecraft.world.entity.monster.Slime
                || e instanceof net.minecraft.world.entity.monster.Phantom;
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
     * Borrow the PRIMARY baritone for the vessel shadow, honouring the safety gate.
     *
     * Baritone's {@code createBaritone(Minecraft)} returns the already-registered
     * primary (there is only one {@code Minecraft} instance), so we cannot create a
     * separate baritone for the vessel. Instead we reflectively swap the primary's
     * {@code playerContext} for a vessel-bound wrapper and restore it on despawn.
     * While borrowed, the real player's own Baritone is unavailable.
     *
     * <p>The wrapper's controller is a {@link VesselServerPlayerController} that bridges
     * break/place to the server vessel's {@code gameMode} — so Baritone's mine/build
     * processes act on the real body, server-authoritatively, with no packets from the
     * host account.
     */
    private boolean tryBindBaritone(Minecraft mc, FakePlayerEntity shadow) {
        String reason = attemptBind(mc, shadow);
        if (reason == null) {
            if (bindReason != null) {
                // we failed before and have now recovered — say so in chat + log (always visible)
                arion.ArionLog.chat("Vessel Baritone bound (recovered).");
            }
            bindReason = null;
            return true;
        }
        if (!reason.equals(bindReason)) {
            // log each distinct reason once so the chat/log isn't spammed every tick.
            // Always-visible (not debug-gated): without this the user can't tell why
            // `ai mine` ran on them instead of the vessel.
            bindReason = reason;
            arion.Arion.LOGGER.warn("[Arion] vessel Baritone NOT bound: {} (vessel commands will run on you until this clears)", reason);
            arion.ArionLog.chat("Vessel Baritone NOT bound: " + reason
                    + " — ai <verb> will run on YOU, not the vessel. (enable debugMode for more)");
        }
        return false;
    }

    /**
     * Attempt the borrow. Returns {@code null} on success, or a short human-readable reason on
     * failure. Never throws. Each failure point is distinct so the chat/log pinpoints the cause.
     */
    private String attemptBind(Minecraft mc, FakePlayerEntity shadow) {
        if (vesselBaritone != null) return null; // already bound
        boolean allowedHere = mc.isSingleplayer() || config.vesselBaritoneOnServers.get();
        if (!allowedHere) return "not single-player (set vesselBaritoneOnServers=true to allow)";
        var server = mc.getSingleplayerServer();
        if (server == null) return "no integrated server";
        try {
            IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (primary == null) return "primary Baritone not ready yet";
            IPlayerController controller = new VesselServerPlayerController(server, vesselUuid);
            IPlayerContext bound = VesselBaritoneBinder.bind(primary, shadow, controller);
            if (bound == null) return "context redirect failed (primary left untouched)";
            this.boundContext = bound;

            // freeLook=true (Baritone's default) makes LookBehavior expose/keep the context's
            // rotation in `serverRotation` — the last rotation packet the HOST sent — and normal
            // pathing targets run in SERVER mode, which reverts the context rotation after each
            // tick. For a real player that's the silent-aim feature. For the vessel it means the
            // shadow never accumulates its aim: the body only ever turns a fixed step, spins, and
            // objectMouseOver() raytraces from the host's stale look vector (breaking aim).
            // CLIENT mode + the shadow's own rotation is what the bridge needs. Restored on unbind.
            if (savedFreeLook == null) {
                savedFreeLook = BaritoneAPI.getSettings().freeLook.value;
                savedSmoothLook = BaritoneAPI.getSettings().smoothLook.value;
            }
            BaritoneAPI.getSettings().freeLook.value = false;
            BaritoneAPI.getSettings().smoothLook.value = false;
            this.vesselBaritone = primary;
            // M4.1: hand melee defence to Baritone's CombatProcess. ALWAYS self-activates the moment
            // a hostile enters combatDefendRange (even while idle or mid-plan); it is a temporary
            // process, so it freezes the task, fights (real attacks via VesselServerPlayerController
            // + its melee/projectile shield), then yields control back. Restored on unbind so the
            // host's own auto-defend mode is left untouched.
            if (config.vesselAutoDefend.get()) {
                try {
                    var cp = primary.getCombatProcess();
                    if (savedAutoDefendMode == null) savedAutoDefendMode = cp.getAutoDefendMode();
                    cp.autoDefendMode(baritone.api.process.AutoDefendMode.ALWAYS);
                    arion.ArionLog.debug("vessel auto-defend: " + cp.getAutoDefendMode());
                } catch (Throwable t) {
                    arion.ArionLog.debug("vessel auto-defend setup failed: " + t);
                }
            }
            arion.ArionLog.debug("vessel baritone bound to shadow (server bridge active)");
            return null;
        } catch (Throwable t) {
            arion.Arion.LOGGER.warn("Couldn't borrow the primary Baritone for the vessel: {}", t.toString());
            return "exception: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** Return the borrowed primary baritone to the real player. */
    private void destroyVesselBaritone() {
        if (vesselBaritone != null) {
            // Stop the borrowed work BEFORE clearing the redirect. Baritone's processes keep
            // their goal across the context swap: unbinding mid-mine would hand the still-active
            // MineProcess/goal to the HOST and immediately start driving YOU. Cancel first, then
            // restore the context.
            try {
                if (combat.isActive()) combat.stop(vesselBaritone);
            } catch (Throwable ignored) {}
            try {
                vesselBaritone.getPathingBehavior().cancelEverything();
            } catch (Throwable ignored) {}
            // Restore the auto-defend mode we overrode on bind, before dropping the reference.
            if (savedAutoDefendMode != null) {
                try { vesselBaritone.getCombatProcess().autoDefendMode(savedAutoDefendMode); }
                catch (Throwable ignored) {}
                savedAutoDefendMode = null;
            }
            try { VesselBaritoneBinder.unbind(vesselBaritone, boundContext); } catch (Throwable ignored) {}
        }
        // restore the settings the bridge forced (freeLook = CLIENT rotation + correct raytrace)
        if (savedFreeLook != null) {
            BaritoneAPI.getSettings().freeLook.value = savedFreeLook;
            savedFreeLook = null;
        }
        if (savedSmoothLook != null) {
            BaritoneAPI.getSettings().smoothLook.value = savedSmoothLook;
            savedSmoothLook = null;
        }
        vesselBaritone = null;
        boundContext = null;
        bindReason = null;
    }
}
