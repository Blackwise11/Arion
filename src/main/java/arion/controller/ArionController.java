/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.controller;

import arion.brain.ArionAction;
import arion.brain.ArionBrain;
import arion.combat.CombatController;
import arion.combat.ThreatEngine;
import arion.config.ArionConfig;
import arion.crafting.CraftJobManager;
import arion.crafting.CraftingEngine;
import arion.crafting.ToolRequirements;
import arion.laya.LayaClient;
import arion.laya.LayaProtocol;
import arion.memory.ArionMemory;
import arion.perception.ArionPerception;
import arion.survival.SurvivalManager;
import baritone.api.IBaritone;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The central nervous system. Routes player input to the brain, dispatches the
 * brain's decisions to Baritone, and supervises running jobs with failsafes.
 *
 * Owns the single active job, listens to Baritone's event bus for ticks and
 * path events, and guarantees every job ends with a {@link JobResult} callback.
 */
public final class ArionController implements AbstractGameEventListener {

    private final ArionConfig config;
    private final ArionBrain brain;
    private final ArionMemory memory;
    private final SurvivalManager survival;
    private final CombatController combat;

    private Job activeJob;
    private boolean pendingBrain = false;
    private java.util.concurrent.CompletableFuture<?> pendingBrainFuture;

    // ---- M2: System 2 macro plan (DeepSeek) + System 1 micro loop (Laya) ----
    /** The active macro plan, or null. Steps execute one at a time; Laya picks each action. */
    private ProcessTracker tracker;
    /** The job a Laya decision started (so finished jobs feed the tracker, not player commands). */
    private Job layaJob;
    /** The step that job was started for (drop sweep + stale-step cancellation). */
    private ProcessTracker.Step layaJobStep;
    /** Post-mining drop sweep. Kept separate so its result is not counted as a step action. */
    private Job pickupJob;
    private ProcessTracker.Step pendingPickupStep;
    private long pickupDeadlineTick;
    /** Lazy HTTP client: config must never be read in a constructor. Rebuilt if the URL changes. */
    private LayaClient laya;
    private String layaUrl;
    /** One Laya request in flight at a time; plus a tick stamp for the wedge guard. */
    private boolean layaPending;
    private long layaPendingSince;
    /** Bumped on every plan start/cancel; stale async results are dropped by epoch. */
    private int layaEpoch;
    private boolean plannerPending;
    private long nextLayaTick;
    private int layaReplans;
    /** A server-side craft/smelt plan is being applied; the tick loop waits for its callback. */
    private boolean craftPending;
    private long craftStartTick;
    private double lastLayaLatencyMs = -1;
    private String lastLayaSummary = "";
    private String layaError;
    private long lastLayaErrorTellTick = -10_000;
    /** Runtime on/off override for 'ai laya on|off' (null = use the config value). */
    private Boolean layaRuntimeOverride;
    /** Throttle repeated eat actions (the safety rule fires every decision while food is low). */
    private long lastEatAttemptTick = -10_000;

    // ---- M4: projectile threat reflex (offhand shield) ----
    private final ThreatEngine threat = new ThreatEngine();
    private boolean shielding;
    private int shieldHoldTicks;
    private String lastThreatLabel = "";
    private Boolean shieldRuntimeOverride;
    private long lastShieldWarnTick = -10_000;
    /** Cached target-block scan (once per second, per step) for explore exits and Laya's Nearby. */
    private int scanStepIndex = -1;
    private long lastScanTick = -10_000;
    private int scanCount;
    private int scanDist = -1;
    private String scanTargetId = "";

    // Baritone fires PathEvent.CANCELED asynchronously (queued by cancelEverything() and
    // delivered on its next tick). When we cancel a job in order to start another one, that
    // stale event arrives after the new job is already active and must not kill it — the
    // "mine oak_log → CANCELED one tick after start" bug. Everything within this grace window
    // is treated as the previous job's cancellation.
    private long tickCounter = 0;
    private long jobStartTick = -10_000;
    private static final long CANCEL_GRACE_TICKS = 5;

    public ArionController(ArionConfig config, ArionBrain brain, ArionMemory memory) {
        this.config = config;
        this.brain = brain;
        this.memory = memory;
        this.survival = new SurvivalManager(config);
        this.combat = new CombatController(config);
        // surface hunt outcomes in chat + brain memory, like job results; on a
        // self-concluded hunt (no target / timed out) re-ask the brain for the next action
        this.combat.setHuntListener(this::onHuntEnded);
        this.combat.setDebugListener(this::debug);
    }

    // ---------------------------------------------------------------- input

    /** A player chat line routed in from ArionChatHandler. Runs on the client thread. */
    public void onPlayerCommand(IBaritone baritone, String message) {
        if (!config.enabled.get()) {
            tell("(Arion is disabled — re-enable in config/arion-client.toml)");
            return;
        }
        String lower = message.trim().toLowerCase();
        debug("command received: \"" + message + "\"");

        // hardcoded kill switch — never trust the LLM for this
        if (lower.equals("stop") || lower.equals("ai stop")) {
            stopAll(baritone, JobResult.CANCELED);
            tell("Stopped.");
            return;
        }

        // force-clear a stuck brain flag (LLM hung, never came back)
        if (lower.equals("reset")) {
            cancelPendingBrain();
            tell("Reset. Brain cleared.");
            return;
        }

        // ---- vessel (fake player) commands ----
        if (lower.startsWith("spawn")) {
            String name = lower.length() > 5 ? lower.substring(5).trim() : "";
            tell(arion.Arion.vessels().spawn(name));
            return;
        }
        if (lower.equals("despawn")) {
            tell(arion.Arion.vessels().despawn());
            return;
        }
        if (lower.equals("vessel")) {
            tell(arion.Arion.vessels().status());
            return;
        }
        // call the vessel over to you (works with or without a bound Baritone)
        if (lower.equals("vessel come") || (lower.equals("come") && arion.Arion.vessels().hasVessel())) {
            var vm = arion.Arion.vessels();
            if (!vm.hasVessel()) { tell("No vessel. Summon one with 'ai spawn'."); return; }
            var self = Minecraft.getInstance().player;
            if (self == null) { tell("No world loaded."); return; }
            if (vm.hasBaritone()) {
                // Stage 2: pathfind to the player's feet via the vessel's own Baritone
                vm.baritone().getCustomGoalProcess().setGoalAndPath(
                        new baritone.api.pathing.goals.GoalNear(self.blockPosition(), 2));
            } else {
                // Stage 1: straight-line seek toward the host (no Baritone yet)
                vm.commandWalkTo(self.getX(), self.getY(), self.getZ());
            }
            tell("Vessel is coming to you.");
            return;
        }
        // send the vessel to absolute coordinates: ai vessel goto <x y z>
        if (lower.startsWith("vessel goto ")) {
            var vm = arion.Arion.vessels();
            if (!vm.hasVessel()) { tell("No vessel. Summon one with 'ai spawn'."); return; }
            String[] parts = lower.substring("vessel goto ".length()).trim().split("\\s+");
            if (parts.length != 3) { tell("vessel goto needs 3 numbers: ai vessel goto x y z"); return; }
            try {
                double x = Double.parseDouble(parts[0]);
                double y = Double.parseDouble(parts[1]);
                double z = Double.parseDouble(parts[2]);
                if (vm.hasBaritone()) {
                    // pathfind (not straight-line): with the context redirect, Baritone plans from
                    // the vessel's own position and its forced inputs drive the server body.
                    vm.baritone().getCustomGoalProcess().setGoalAndPath(
                            new baritone.api.pathing.goals.GoalNear(
                                    new net.minecraft.core.BlockPos(
                                            (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)), 2));
                } else {
                    vm.commandWalkTo(x, y, z);   // fallback: server-side straight-line seek
                }
                tell("Vessel heading to " + (int) x + " " + (int) y + " " + (int) z + ".");
            } catch (NumberFormatException e) {
                tell("vessel goto: couldn't parse coordinates");
            }
            return;
        }
        if (lower.equals("vessel stay") || lower.equals("vessel stop")) {
            arion.Arion.vessels().commandStop();
            tell("Vessel staying put.");
            return;
        }
        // force a Baritone borrow attempt and report the exact outcome in chat — the
        // decisive diagnostic when `ai mine` runs on the host instead of the vessel
        if (lower.equals("vessel bind")) {
            tell(arion.Arion.vessels().rebind());
            return;
        }
        // hand Baritone back to the real player, keeping the vessel in the world
        if (lower.equals("vessel unbind") || lower.equals("unbind")) {
            tell(arion.Arion.vessels().unbind());
            return;
        }
        // full teardown — despawns the body AND returns Baritone to the player
        if (lower.equals("vessel despawn") || lower.equals("vessel remove")) {
            tell(arion.Arion.vessels().despawn());
            return;
        }
        // Phase 4: the vessel hunts on its own (packet-safe — it never sends an attack packet)
        if (lower.startsWith("vessel hunt ")) {
            var vm = arion.Arion.vessels();
            if (!vm.hasVessel()) { tell("No vessel. Summon one with 'ai spawn'."); return; }
            String result = vm.commandHunt(lower.substring("vessel hunt ".length()).trim());
            tell(result != null ? result : "Vessel hunting — 'ai vessel stop' to call it off.");
            return;
        }
        // Open the vessel's live inventory UI (right-clicking the vessel does the same)
        if (lower.equals("inventory") || lower.equals("inv") || lower.equals("vessel inventory")) {
            var vm = arion.Arion.vessels();
            if (vm == null || !vm.hasVessel()) {
                tell("No vessel. Summon one with 'ai spawn'.");
                return;
            }
            String error = vm.openInventory();
            tell(error != null ? error
                    : "Opening the vessel's inventory — row 1: helmet, chest, legs, boots, offhand.");
            return;
        }
        // M4 projectile shield reflex status/toggle
        if (lower.equals("shield") || lower.startsWith("shield ")) {
            String arg = lower.length() > 6 ? lower.substring(6).trim() : "";
            switch (arg) {
                case "", "status" -> {
                    var vessels = arion.Arion.vessels();
                    boolean vesselRaised = vessels != null && vessels.hasVessel()
                            && vessels.isVesselShielding();
                    tell("Auto-shield: " + (shieldEnabled() ? "ON" : "OFF")
                            + (vesselRaised ? " (raised on the vessel)"
                                    : shielding ? " (raised)" : "")
                            + (lastThreatLabel.isBlank() ? "" : " | last: " + lastThreatLabel));
                }
                case "on" -> {
                    shieldRuntimeOverride = Boolean.TRUE;
                    tell("Auto-shield ON.");
                }
                case "off" -> {
                    shieldRuntimeOverride = Boolean.FALSE;
                    IBaritone b = arion.Arion.baritone();
                    if (b != null) {
                        var vm = arion.Arion.vessels();
                        boolean bound = vm != null && vm.hasBaritone();
                        setShield(false, null, bound, b.getPlayerContext().player());
                    }
                    tell("Auto-shield OFF.");
                }
                case "test" -> tell(arion.Arion.vessels().testVesselShield());
                default -> tell("shield: status | on | off | test");
            }
            return;
        }

        // ---- M3: direct crafting / smelting / recipe diagnostics ----
        String raw = message.trim();
        if (lower.equals("craft") || lower.equals("smelt") || lower.equals("recipe")) {
            tell("usage: ai craft <item> [amount] | ai smelt <item> [amount] | ai recipe <item>");
            return;
        }
        if (lower.startsWith("craft ")) {
            craftCommand(raw.substring(6), false);
            return;
        }
        if (lower.startsWith("smelt ")) {
            craftCommand(raw.substring(6), true);
            return;
        }
        if (lower.startsWith("recipe ")) {
            recipeCommand(raw.substring(7));
            return;
        }

        // ---- System 2 macro plan (DeepSeek) + System 1 loop (Laya) — M2 ----
        String trimmed = message.trim();
        if (trimmed.equalsIgnoreCase("plan") || lower.startsWith("plan ")) {
            String arg = trimmed.length() > 4 ? trimmed.substring(4).trim() : "";
            if (arg.isEmpty() || arg.equalsIgnoreCase("status")) {
                tell(planStatus());
            } else if (arg.equalsIgnoreCase("stop") || arg.equalsIgnoreCase("cancel")) {
                cancelPlan("player cancelled");
                tell("Macro plan cancelled.");
            } else if (arg.equalsIgnoreCase("skip") || arg.equalsIgnoreCase("next")) {
                if (tracker == null) {
                    tell("No macro plan to skip.");
                } else {
                    advanceStep("skipped by player");
                }
            } else {
                startPlan(baritone, arg);
            }
            return;
        }
        if (lower.equals("laya") || lower.startsWith("laya ")) {
            String arg = lower.length() > 4 ? lower.substring(4).trim() : "";
            switch (arg) {
                case "", "status" -> tell(layaStatus());
                case "on" -> {
                    layaRuntimeOverride = Boolean.TRUE;
                    tell("Laya loop enabled.");
                }
                case "off" -> {
                    layaRuntimeOverride = Boolean.FALSE;
                    tell("Laya loop disabled (the current body action keeps running; 'ai plan stop' to abort).");
                }
                case "test" -> testLaya();
                case "decide" -> forceLayaDecision(baritone);
                default -> tell("laya: status | on | off | test | decide");
            }
            return;
        }
        if (lower.equals("decide")) {
            forceLayaDecision(baritone);
            return;
        }

        // ---- routing: default to the vessel's Baritone if one is bound, unless --self ----
        boolean forceSelf = lower.contains("--self");
        boolean explicitVessel = lower.contains("--vessel");
        String cleaned = lower.replace("--vessel", "").replace("--self", "").replaceAll("\\s+", " ").trim();
        String cleanedRaw = message.replace("--vessel", "").replace("--self", "").replaceAll("\\s+", " ").trim();
        var vm = arion.Arion.vessels();

        // `--self` cannot work while the vessel has borrowed the primary Baritone: there is only
        // one service, and its context points at the vessel. Refuse loudly instead of silently
        // driving the vessel (the exact failure mode `--self` is meant to prevent).
        if (forceSelf && vm != null && vm.hasBaritone()) {
            tell("Baritone is borrowed by the vessel right now — there is no separate service for YOU. "
                    + "Run 'ai vessel unbind' first (the vessel stays put), then '" + cleaned + "' drives you.");
            return;
        }

        // diagnostic: pings the planner API on purpose — NOT a control command, so it
        // stays out of the direct-dispatch path below.
        if (cleaned.equals("test") || cleaned.equals("test connection")) {
            testConnection();
            return;
        }

        // Direct control commands: a recognized verb dispatches immediately to a Baritone
        // job / combat hunt with NO LLM round-trip — these are how the player drives the
        // body, not something the AI needs to decide. Only free-form input the verb parser
        // doesn't recognize is handed to the brain as conversational chat.
        ArionAction direct = directAction(cleanedRaw);

        if (!forceSelf && vm.hasBaritone()) {
            // a vessel Baritone is borrowed: route work commands to it by default.
            // dispatch() routes hunt to the vessel's packet-safe (vesselMode) fighter
            // automatically when the baritone it's passed is the vessel's.
            IBaritone vb = vm.baritone();
            if (direct != null) {
                debug("direct command (vessel): " + direct.verb() + " \"" + direct.arg() + "\"");
                dispatch(vb, direct);
                return;
            }
            debug("routing to vessel brain: \"" + cleaned + "\" (explicit=" + explicitVessel + ")");
            askBrain(vb, cleanedRaw);
            return;
        }

        if (direct != null) {
            // No vessel Baritone is bound. If a vessel IS spawned, warn loudly: the command is
            // about to run on the HOST (you), not the vessel — the exact silent failure the user
            // hit before. Otherwise this is the normal "drive yourself" path.
            if (vm.hasVessel() && !vm.hasBaritone()) {
                tell("⚠ Vessel Baritone is unbound — running '" + direct.verb()
                        + "' on YOU, not the vessel. Check 'ai vessel' for the bind reason.");
            }
            debug("direct command: " + direct.verb() + " \"" + direct.arg() + "\"");
            dispatch(baritone, direct);
            return;
        }

        // otherwise hand it to the brain (free-form chat / questions)
        askBrain(baritone, cleanedRaw);
    }

    /**
     * Verbs that are direct control commands — dispatched immediately with no LLM round-trip.
     * Mirrors the {@code dispatch} switch: every verb the brain can return is also reachable
     * as a direct command, so the AI is only consulted for input that isn't one of these.
     */
    private static final java.util.Set<String> DIRECT_VERBS = java.util.Set.of(
            "mine", "follow", "goto", "hunt", "explore", "farm", "get", "pickup", "build", "clear");

    /**
     * Parse a player command into a direct {@link ArionAction} if it starts with a known
     * control verb, else null (→ the brain handles it as free-form chat). Verb matching is
     * case-insensitive; the argument preserves its original case (schematic filenames and
     * player names are case-sensitive). "follow me" is normalized to follow-self.
     */
    private static ArionAction directAction(String cleanedRaw) {
        String trimmed = cleanedRaw.trim();
        if (trimmed.isEmpty()) return null;
        int sp = trimmed.indexOf(' ');
        String verbPart = (sp < 0) ? trimmed : trimmed.substring(0, sp);
        String arg = (sp < 0) ? "" : trimmed.substring(sp + 1).trim();
        String verb = verbPart.toLowerCase(java.util.Locale.ROOT);
        if (!DIRECT_VERBS.contains(verb)) return null;
        if (verb.equals("follow") && arg.equalsIgnoreCase("me")) arg = "";
        return new ArionAction(verb, arg, "");
    }

    /** Settings "test connection": ping the planner provider and report ✅/❌ in chat. */
    private void testConnection() {
        tell("Testing connection to " + config.plannerProvider.get() + "…");
        var provider = brain.registry().byIdOrDefault(config.plannerProvider.get());
        var req = new arion.provider.AiProvider.AiRequest(
                config.plannerBaseUrl.get(),
                config.plannerModel.get(),
                config.plannerApiKey.get(),
                java.util.List.of(),
                10
        );
        Minecraft mc = Minecraft.getInstance();
        provider.testConnection(req)
                .thenAccept(ok ->
                        mc.execute(() -> tell(ok
                                ? "✅ Connected to " + provider.displayName() + " (" + config.plannerModel.get() + ")"
                                : "❌ Couldn't reach " + provider.displayName() + ". Check the base URL / API key in the Arion config. (reason in latest.log)")))
                .exceptionally(t -> {
                    mc.execute(() -> tell("❌ Connection error: " + rootMessage(t)));
                    return null;
                });
    }

    /** Send the request + world snapshot to the brain off-thread. */
    private void askBrain(IBaritone baritone, String message) {
        if (pendingBrain) {
            tell("(still thinking about the last one…  'ai reset' to clear, 'ai stop' to cancel)");
            return;
        }
        pendingBrain = true;
        memory.userSaid(message);
        String clientSnapshot = ArionPerception.plannerSnapshot(baritone.getPlayerContext());
        debug("brain request → " + config.reflexProvider.get() + "/" + config.reflexModel.get());
        gatherVesselStatusAndDecide(baritone, message, clientSnapshot);
    }

    /**
     * Wire a brain-decide future to the gate + dispatch path. Always clears the pending flag on ANY
     * completion (success / error / cancel) so the gate can never wedge shut — a slow or failed
     * request can't leave Arion refusing all further commands.
     */
    private void wireBrainFuture(IBaritone baritone, java.util.concurrent.CompletableFuture<ArionAction> future) {
        pendingBrainFuture = future;
        Minecraft mc = Minecraft.getInstance();
        future.whenComplete((action, err) -> {
            pendingBrain = false;
            pendingBrainFuture = null;
            if (err != null) {
                arion.ArionLog.debug("brain error: " + rootMessage(err));
                return;
            }
            if (action == null) {
                arion.ArionLog.debug("brain returned null (cancelled)");
                return; // cancelled — nothing to dispatch
            }
            arion.ArionLog.debug("brain returned: " + action.verb() + " arg=\"" + action.arg() + "\"");
            mc.execute(() -> {
                if (baritone.getPlayerContext().player() == null) return; // world unloaded
                dispatch(baritone, action);
            });
        });
    }

    /**
     * Gather the vessel's body status on the server thread (the vessel is a server-side entity),
     * then run the brain with the combined snapshot = client world view + vessel status. If there's
     * no integrated server (or no vessel), decides with the client snapshot alone. The brain call
     * always fires (off-thread), so {@link #wireBrainFuture} always clears the gate — the gate can
     * never wedge even if the client unloads mid-hop.
     */
    private void gatherVesselStatusAndDecide(IBaritone baritone, String message, String clientSnapshot) {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) {
            wireBrainFuture(baritone, brain.decide(message, clientSnapshot));
            return;
        }
        try {
            server.execute(() -> {
                var svm = arion.vessel.server.ServerVesselManager.peek(server);
                // Structured (JSON) body state: HP/food/armor/effects/XP/selected slot/held tool/
                // inventory/position/velocity. The planner is asked to reason from this, so it can
                // pick "eat", "retreat", "craft a pickaxe", etc. from the vessel's actual state.
                final String vs = (svm != null) ? svm.vesselStatusJson() : null;
                mc.execute(() -> {
                    String full = clientSnapshot + (vs != null ? "\nVESSEL_STATUS_JSON:\n" + vs : "");
                    arion.ArionLog.trace("brain snapshot:\n" + full);
                    wireBrainFuture(baritone, brain.decide(message, full));
                });
            });
        } catch (Throwable t) {
            // never wedge the gate: decide with whatever we already have
            arion.ArionLog.debug("vessel-status hop failed: " + t + " — deciding with client snapshot");
            wireBrainFuture(baritone, brain.decide(message, clientSnapshot));
        }
    }

    /**
     * Called when a hunt ends (completion, give-up, cancel, world-unload). Surfaces it in
     * chat + brain memory — the existing behavior — and, for a self-concluded hunt
     * ("no target found" / "hunt timed out"), re-asks the brain so the AI picks the next
     * action (idle, wander, hunt elsewhere, …) rather than just standing still. This is
     * the "request idle or wandering" tail of the decision chain. User-initiated stops
     * and world-unload do NOT re-ask.
     */
    private void onHuntEnded(String reason) {
        tell("Hunt ended (" + reason + ").");
        memory.assistantSaid("[hunt ended: " + reason + "]");
        arion.ArionLog.debugChat("hunt ended (" + reason + ") → deciding next action");
        // A macro plan owns the decision loop. Re-asking the brain here would race the Laya
        // loop for the same body, so just report the failed action to the tracker and let it
        // decide (it may re-plan after repeated failures).
        if (tracker != null && tracker.isActive()) {
            boolean failed = "no target found".equals(reason) || "hunt timed out".equals(reason);
            if (failed) {
                IBaritone b0 = arion.Arion.baritone();
                ProcessTracker.Progress pr = b0 != null ? progressSnapshot(b0.getPlayerContext()) : null;
                tracker.noteActionFinished(false,
                        pr != null ? tracker.countCurrent(pr) : -1, tickCounter);
            }
            nextLayaTick = tickCounter + 5;
            arion.ArionLog.debug("hunt ended with an active plan → Laya continues");
            return;
        }
        if ("no target found".equals(reason) || "hunt timed out".equals(reason)) {
            IBaritone b = arion.Arion.baritone();
            if (b != null && b.getPlayerContext().player() != null && !pendingBrain) {
                askBrainNextAction(b, reason);
            } else {
                arion.ArionLog.debug("hunt-end re-ask skipped (brain busy / no player)");
            }
        }
    }

    /** Vessel-side entry point (wired from {@link arion.Arion}). Routes to the same handler. */
    public void onVesselHuntEnded(String reason) {
        onHuntEnded(reason);
    }

    /**
     * Re-ask the brain for what to do after a self-concluded hunt. Does NOT record
     * {@code memory.userSaid} (it's a system follow-up, not a player utterance); records an
     * assistant-side note instead. Reuses the normal snapshot + vessel-status path so the
     * brain sees the current body + surroundings.
     */
    private void askBrainNextAction(IBaritone baritone, String reason) {
        if (pendingBrain) return;
        String prompt = "My hunt just ended (" + reason + "). Given my current status and "
                + "surroundings, what should I do next? (e.g. idle/chat, explore/wander, "
                + "hunt elsewhere, follow)";
        pendingBrain = true;
        memory.assistantSaid("[asking what to do next after hunt ended: " + reason + "]");
        String clientSnapshot = ArionPerception.plannerSnapshot(baritone.getPlayerContext());
        arion.ArionLog.debug("brain request → " + config.reflexProvider.get() + "/"
                + config.reflexModel.get() + " (hunt-end:" + reason + ")");
        gatherVesselStatusAndDecide(baritone, prompt, clientSnapshot);
    }

    /** Cancel any in-flight brain request and clear the pending flag. */
    private void cancelPendingBrain() {
        pendingBrain = false;
        if (pendingBrainFuture != null) {
            pendingBrainFuture.cancel(true);
            pendingBrainFuture = null;
        }
    }

    // ============================================================ M2: plan + Laya

    /** True if the System 1 loop may act (config value, overridable at runtime by 'ai laya on|off'). */
    private boolean layaEnabled() {
        Boolean override = layaRuntimeOverride;
        return override != null ? override : config.layaEnabled.get();
    }

    /** Lazy Laya client — config values are only populated after ModConfigEvent.Loading. */
    private LayaClient layaClient() {
        String url = config.layaBaseUrl.get();
        if (laya == null || !url.equals(layaUrl)) {
            if (laya != null) laya.close();
            laya = new LayaClient(url, config.layaTimeoutSeconds.get());
            layaUrl = url;
        }
        return laya;
    }

    /** 'ai plan <goal>' — cancel anything previous, then ask the System 2 planner. */
    private void startPlan(IBaritone baritone, String goal) {
        cancelActiveJob(baritone, JobResult.CANCELED);
        cancelPlan("replaced by a new goal");
        layaReplans = 0;
        memory.userSaid("Goal: " + goal);
        tell("Planning: " + goal);
        requestPlan(baritone, goal, "initial plan", false);
    }

    /**
     * Gather the body status on the server thread, then ask DeepSeek for a plan off-thread.
     * The response is marshalled back to the client thread and handed to the tracker.
     */
    private void requestPlan(IBaritone baritone, String goal, String reason, boolean replan) {
        if (plannerPending) {
            tell("(the planner is still thinking about the previous plan…)");
            return;
        }
        IBaritone b = baritone != null ? baritone : arion.Arion.baritone();
        if (b == null || b.getPlayerContext().player() == null) {
            tell("No world loaded.");
            return;
        }

        plannerPending = true;
        layaPending = false;
        // invalidate any in-flight micro decision: the plan it was deciding for is changing
        final int epoch = ++layaEpoch;
        final String clientSnapshot = ArionPerception.plannerSnapshot(b.getPlayerContext());
        final Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) {
            firePlanRequest(goal, clientSnapshot, reason, replan, epoch);
            return;
        }
        try {
            server.execute(() -> {
                String vs = null;
                try {
                    var svm = arion.vessel.server.ServerVesselManager.peek(server);
                    if (svm != null) vs = svm.vesselStatusJson();
                } catch (Throwable ignored) {}
                final String full = clientSnapshot + (vs != null ? "\nVESSEL_STATUS_JSON:\n" + vs : "");
                mc.execute(() -> firePlanRequest(goal, full, reason, replan, epoch));
            });
        } catch (Throwable t) {
            arion.ArionLog.debug("plan vessel-status hop failed: " + t + " — planning with the client snapshot");
            firePlanRequest(goal, clientSnapshot, reason, replan, epoch);
        }
    }

    private void firePlanRequest(String goal, String snapshot, String reason, boolean replan, int epoch) {
        if (epoch != layaEpoch) {
            arion.ArionLog.debug("stale plan request ignored (epoch changed during the status hop)");
            return;
        }
        String context = snapshot;
        if (replan) {
            context += "\n\nSystem note: the previous plan stalled (" + reason + "). Produce a revised "
                    + "plan and do not repeat steps that already failed.";
        }
        arion.ArionLog.trace("planner snapshot:\n" + context);
        brain.planSteps(goal, context)
                .whenComplete((raw, err) -> Minecraft.getInstance().execute(() -> {
                    if (epoch != layaEpoch) {
                        arion.ArionLog.debug("stale planner response ignored");
                        return;
                    }
                    plannerPending = false;
                    if (err != null || raw == null) {
                        tell("❌ Planner failed: "
                                + (err != null ? rootMessage(err) : "empty response"));
                        tell("Keeping the current plan (if any).");
                        return;
                    }
                    ProcessTracker.Plan parsed;
                    try {
                        parsed = ProcessTracker.parse(raw);
                    } catch (IllegalArgumentException e) {
                        arion.ArionLog.trace("planner raw: " + raw);
                        tell("❌ Planner returned an unusable plan: " + e.getMessage());
                        tell("Keeping the current plan (if any).");
                        return;
                    }
                    tracker = new ProcessTracker();
                    tracker.begin(parsed, tickCounter);
                    layaJob = null;
                    nextLayaTick = tickCounter + 5;
                    arion.ArionLog.debug("plan accepted: " + parsed.totalSteps()
                            + " step(s), goal=" + parsed.goal());
                    tell("Plan ready: " + parsed.goal() + " (" + parsed.totalSteps() + " steps)");
                    int shown = Math.min(3, parsed.totalSteps());
                    for (int i = 0; i < shown; i++) {
                        tell("  " + (i + 1) + ". " + parsed.steps().get(i).oneLine());
                    }
                    if (parsed.totalSteps() > shown) {
                        tell("  … " + (parsed.totalSteps() - shown) + " more");
                    }
                    memory.assistantSaid("[plan started: " + parsed.goal() + "]");
                }))
                .exceptionally(t -> null);
    }

    /** Abandon the macro plan and invalidate every in-flight planner/Laya result. */
    private void cancelPlan(String reason) {
        layaEpoch++;
        plannerPending = false;
        layaPending = false;
        craftPending = false;
        try {
            Minecraft mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            if (server != null) server.execute(() -> CraftJobManager.cancel(server));
        } catch (Throwable ignored) {}
        layaJob = null;
        layaJobStep = null;
        pickupJob = null;
        pendingPickupStep = null;
        lastLayaSummary = "";
        if (tracker != null) {
            arion.ArionLog.debug("plan cancelled (" + reason + "): " + tracker);
            tracker.abandon(reason);
            tracker = null;
        }
    }

    /**
     * Ask the planner for a revised plan. Called on stalls, repeated action failures, or when
     * Laya's {@code need_replan} answer fires. Capped so a pathological goal can't loop forever.
     */
    private void requestReplan(IBaritone baritone, String reason) {
        if (tracker == null) return;
        if (plannerPending) return;
        if (layaReplans >= 3) {
            ProcessTracker.Plan p = tracker.plan();
            cancelActiveJob(baritone, JobResult.STUCK);
            cancelPlan("re-plan limit reached: " + reason);
            tell("✖ Plan abandoned after 3 re-plans: " + reason);
            memory.assistantSaid("[plan abandoned: " + (p != null ? p.goal() : "?") + "]");
            return;
        }
        layaReplans++;
        arion.ArionLog.debug("re-plan #" + layaReplans + ": " + reason);
        tell("Re-planning (" + reason + ")…");
        // stop the stuck action; do NOT use stopAll() — that cancels the plan we're revising
        cancelActiveJob(baritone, JobResult.STUCK);
        layaJob = null;
        if (combat.isActive()) combat.stop(baritone);
        var vm = arion.Arion.vessels();
        if (vm != null) vm.commandStop();
        String goal = tracker.plan() != null ? tracker.plan().goal() : "";
        requestPlan(baritone, goal, reason, true);
    }

    /**
     * Supervise the macro plan: feed progress to the tracker, honour re-plan requests, and run
     * one Laya decision whenever the body is idle. Called once per client tick.
     */
    private void tickPlan(IBaritone baritone, IPlayerContext ctx) {
        if (tracker == null) return;

        // The M3 job owns the body while it runs (a real furnace can take minutes); pausing the
        // tracker here prevents the item-stall/step-timeout from cancelling a valid smelt.
        if (craftPending) {
            if (tickCounter - craftStartTick > 20 * 60 * 25) {
                craftPending = false;
                arion.ArionLog.debug("craftPending guard fired (25 min)");
            } else {
                return;
            }
        }

        ProcessTracker.Progress pr = progressSnapshot(ctx);

        // Inventory is the truth. When the current item step's target is already in the body's
        // inventory but the mining job is still running (Baritone's own quantity filter can
        // disagree, e.g. with the vessel shadow), stop the job now instead of chopping forever.
        ProcessTracker.Step activeStep = tracker.current();
        if (activeStep != null && layaJob != null && layaJobStep != null
                && layaJobStep.equals(activeStep)
                && activeStep.itemObjective() && activeStep.amount() > 0
                && tracker.count(pr, activeStep) >= activeStep.amount()) {
            int count = tracker.count(pr, activeStep);
            ProcessTracker.Step finished = layaJobStep;
            arion.ArionLog.debug("inventory target met (" + count + "/" + activeStep.amount()
                    + ") → stopping " + layaJob.label());
            layaJob = null;
            layaJobStep = null;
            cancelActiveJob(baritone, JobResult.DONE);
            pendingPickupStep = finished;
            ProcessTracker.Step next = tracker.advance(
                    "target reached (" + count + "/" + activeStep.amount() + ")");
            if (!tracker.isActive()) {
                ProcessTracker.Plan done = tracker.plan();
                tracker = null;
                tell("✔ Plan complete: " + (done != null ? done.goal() : "goal"));
                memory.assistantSaid("[plan complete: " + (done != null ? done.goal() : "?") + "]");
            } else {
                tell("Step done (inventory " + count + "/" + activeStep.amount()
                        + ") → " + (next != null ? next.oneLine() : ""));
            }
            return;
        }

        String autoAdvance = tracker.tick(pr);
        if (!tracker.isActive()) {
            // Plan complete via the count check. If its mine job is still running, stop it (the
            // inventory already has enough) and sweep the drops it left.
            if (layaJobStep != null) pendingPickupStep = layaJobStep;
            if (layaJob != null) {
                arion.ArionLog.debug("plan complete while " + layaJob.label()
                        + " is active → cancelling the job");
                layaJob = null;
                layaJobStep = null;
                cancelActiveJob(baritone, JobResult.DONE);
            }
            ProcessTracker.Plan done = tracker.plan();
            tracker = null;
            tell("✔ Plan complete: " + (done != null ? done.goal() : "goal"));
            memory.assistantSaid("[plan complete: " + (done != null ? done.goal() : "?") + "]");
            arion.ArionLog.debug("plan complete: " + (done != null ? done.goal() : "?"));
            return;
        }

        if (autoAdvance != null) {
            tell("Step done (" + autoAdvance + ") → " + tracker.current().oneLine());
            arion.ArionLog.debug("step auto-advance: " + autoAdvance);
            if (layaJob != null) {
                // The tracker's inventory count says the target is met, but Baritone's own
                // quantity filter can disagree and keep chopping. The inventory is the truth.
                arion.ArionLog.debug("stopping " + layaJob.label()
                        + " because the step target was reached");
                if (layaJobStep != null) pendingPickupStep = layaJobStep;
                layaJob = null;
                layaJobStep = null;
                cancelActiveJob(baritone, JobResult.DONE);
            }
        }

        ProcessTracker.Step step = tracker.current();
        if (step != null) {
            String objective = step.objective() == null ? "" : step.objective().toLowerCase(Locale.ROOT);
            // An explore step is redundant when any later step gathers/mines a known target:
            // Baritone's MineProcess searches for the target itself (the live log burned a 60 s
            // explore budget before `mine stone` even started). Skip straight to that step.
            if ("explore".equals(objective)) {
                ProcessTracker.Plan plan = tracker.plan();
                if (plan != null) {
                    for (int i = tracker.stepIndex() + 1; i < plan.totalSteps(); i++) {
                        ProcessTracker.Step later = plan.steps().get(i);
                        if (later.itemObjective() && !later.targetId().isBlank()) {
                            advanceStep("skipping explore: a later step searches for its target");
                            return;
                        }
                    }
                }
            }
            if (!isCraftObjective(step)) {
                refreshTargetScan(ctx, step);
            }
            if ("explore".equals(objective)) {
                // Deterministic explore exit: the planner's "within N blocks" condition, or a
                // time budget so an explore step can never stall for the full step timeout.
                if (scanCount > 0) {
                    advanceStep("target found nearby (" + scanDist + "m)");
                    return;
                }
                if (tracker.stepAgeTicks(tickCounter) > 20 * 60) {
                    advanceStep("explore time budget reached (60s)");
                    return;
                }
            }
        }

        String replan = tracker.takeReplanReason();
        if (replan != null) {
            requestReplan(baritone, replan);
            return;
        }

        // --- post-mining drop sweep (runs even when the Laya loop is disabled) ---
        if (pickupJob != null && tickCounter > pickupDeadlineTick) {
            arion.ArionLog.debug("drop sweep timed out → cancelling");
            pickupJob = null;
            cancelActiveJob(baritone, JobResult.CANCELED);
        }
        if (pendingPickupStep != null && activeJob == null && pickupJob == null
                && !combat.isActive()) {
            ProcessTracker.Step sweep = pendingPickupStep;
            pendingPickupStep = null;
            startDropPickup(baritone, ctx, sweep);
            if (pickupJob != null) return;
        }

        // --- M3: crafting / smelting steps (deterministic; the engine owns the recipe graph) ---
        if (step != null && isCraftObjective(step)) {
            if (craftPending) return;
            if (layaPending && tickCounter - layaPendingSince > 400) {
                layaPending = false;
                layaError = "decision guard timeout";
                arion.ArionLog.debug("laya pending guard fired (20s)");
            }
            var vm0 = arion.Arion.vessels();
            boolean craftBusy = activeJob != null || combat.isActive()
                    || (vm0 != null && vm0.isVesselHunting())
                    || layaPending || plannerPending;
            if (craftBusy) return;
            if (tickCounter < nextLayaTick) return;
            if (!config.craftEnabled.get()) {
                requestReplan(baritone, "crafting is disabled (craftEnabled=false)");
                return;
            }
            startCraftStep(baritone, ctx);
            return;
        }

        // --- Harvest tool tier. The planner can order a gather before its tool (or omit it);
        // a wooden pickaxe on iron ore drops nothing. Craft the minimum pickaxe first.
        if (step != null && isGatherObjective(step) && !craftPending
                && tickCounter >= nextLayaTick) {
            Item missing = ToolRequirements.missingTool(
                    ctx.player(), ProcessTracker.mineBlockName(step));
            if (missing != null) {
                startToolCraft(baritone, ctx, missing, step);
                return;
            }
        }

        if (!layaEnabled() || plannerPending) return;

        // wedge guard: a lost HTTP callback must never stop the loop forever
        if (layaPending && tickCounter - layaPendingSince > 400) {
            layaPending = false;
            layaError = "decision guard timeout";
            arion.ArionLog.debug("laya pending guard fired (20s)");
        }
        if (layaPending) return;

        var vm = arion.Arion.vessels();
        boolean bodyBusy = activeJob != null
                || combat.isActive()
                || (vm != null && vm.isVesselHunting());
        if (bodyBusy) return;

        if (tickCounter < nextLayaTick) return;
        nextLayaTick = tickCounter + Math.max(2, config.layaIntervalTicks.get());
        requestMicroDecision(baritone, ctx, pr);
    }

    /** Build the Laya state on the client thread, enrich it with the vessel body, then decide. */
    private void requestMicroDecision(IBaritone baritone, IPlayerContext ctx,
                                      ProcessTracker.Progress pr) {
        ProcessTracker.Step step = tracker != null ? tracker.current() : null;
        if (step == null) return;

        final int epoch = layaEpoch;
        layaPending = true;
        layaPendingSince = tickCounter;
        final Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server != null) {
            try {
                server.execute(() -> {
                    arion.vessel.server.ServerVesselManager.Vitals vv = null;
                    try {
                        var svm = arion.vessel.server.ServerVesselManager.peek(server);
                        if (svm != null) vv = svm.vitals();
                    } catch (Throwable ignored) {}
                    final arion.vessel.server.ServerVesselManager.Vitals vesselVitals = vv;
                    mc.execute(() -> {
                        if (epoch != layaEpoch) return;
                        // The shadow does not mirror health/food — use the vessel's real vitals.
                        String state = buildLayaState(ctx, step, pr, vesselVitals);
                        fireMicroDecision(state, LayaProtocol.microQuestions(), baritone, epoch,
                                vesselVitals != null ? vesselVitals : clientVitals(ctx));
                    });
                });
                return;
            } catch (Throwable t) {
                arion.ArionLog.debug("laya vessel-vitals hop failed: " + t);
            }
        }
        String state = buildLayaState(ctx, step, pr, null);
        fireMicroDecision(state, LayaProtocol.microQuestions(), baritone, epoch, clientVitals(ctx));
    }

    private void fireMicroDecision(String state, Map<String, LayaClient.Question> questions,
                                   IBaritone baritone, int epoch,
                                   arion.vessel.server.ServerVesselManager.Vitals vitals) {
        if (epoch != layaEpoch) return;
        arion.ArionLog.trace("laya state:\n" + cap(state, 6000));
        final long started = System.currentTimeMillis();
        layaClient().decide(cap(state, 6000), questions)
                .whenComplete((res, err) -> Minecraft.getInstance().execute(() -> {
                    if (epoch != layaEpoch) {
                        arion.ArionLog.debug("stale laya decision ignored");
                        return;
                    }
                    layaPending = false;
                    if (err != null || res == null) {
                        layaError = err != null ? rootMessage(err) : "empty response";
                        lastLayaLatencyMs = -1;
                        if (tickCounter - lastLayaErrorTellTick > 200) {
                            lastLayaErrorTellTick = tickCounter;
                            tell("⚠ Laya unavailable (" + layaError + "). Start laya-project\\laya_http_server.py"
                                    + " (expected at " + layaClient().baseUrl() + ").");
                        }
                        arion.ArionLog.debug("laya error: " + layaError);
                        nextLayaTick = tickCounter + Math.max(20, config.layaIntervalTicks.get());
                        return;
                    }
                    layaError = null;
                    lastLayaLatencyMs = res.latencyMs() >= 0
                            ? res.latencyMs() : (System.currentTimeMillis() - started);
                    lastLayaSummary = res.summarize();
                    arion.ArionLog.debug("laya decision: " + lastLayaSummary
                            + " rtt=" + (System.currentTimeMillis() - started) + "ms");
                    try {
                        applyMicroDecision(res, baritone, vitals);
                    } catch (Throwable t) {
                        arion.ArionLog.debug("laya action failed: " + t);
                    }
                }))
                .exceptionally(t -> null);
    }

    /** Confidence required to let Laya declare a non-item step finished. */
    private static final double ADVANCE_MIN_CONFIDENCE = 0.60;

    /** Execute (or advance past) one atomic System 1 choice. */
    private void applyMicroDecision(LayaClient.Result res, IBaritone baritone,
                                    arion.vessel.server.ServerVesselManager.Vitals vitals) {
        if (tracker == null || !tracker.isActive()) return;
        ProcessTracker.Step step = tracker.current();
        if (step == null) return;

        IPlayerContext ctx = baritone.getPlayerContext();
        ProcessTracker.Progress pr = progressSnapshot(ctx);

        // Deterministic safety beats a noisy classifier: measured against the live model, Laya
        // keeps choosing "mine" at 4 HP with zombies adjacent.
        String safety = safetyAction(ctx, vitals);
        if (safety != null) {
            arion.ArionLog.debug("laya safety override: " + safety);
            if (safety.equals("flee")) {
                actionFlee(baritone);
                return;
            }
            if (safety.equals("eat")) {
                actionEat(baritone);
                return;
            }
        }

        LayaClient.Answer replan = res.get("need_replan");
        if (replan != null && replan.noul() != null) {
            // Advisory only: on the live model this reads ~0.2 both for fresh steps and while
            // genuinely stuck, so the deterministic tracker owns re-planning.
            arion.ArionLog.debug("laya need_replan p="
                    + String.format(Locale.ROOT, "%.2f", replan.noul()));
        }

        LayaClient.Answer action = res.get("action");
        if (action == null || action.choice() == null) {
            arion.ArionLog.debug("laya returned no action answer → step default");
            runStepDefault(baritone, pr);
            return;
        }
        LayaClient.Answer threat = res.get("threat_level");
        String choice = action.choice().trim().toLowerCase(Locale.ROOT);
        double confidence = action.confidence();
        arion.ArionLog.debug("laya chose '" + choice + "' conf="
                + String.format(Locale.ROOT, "%.2f", confidence)
                + " threat=" + LayaProtocol.threatName(threat != null ? threat.score() : null));

        boolean itemStep = step.itemObjective() && step.amount() > 0;
        int count = itemStep ? tracker.count(pr, step) : 0;

        if (choice.equals(LayaProtocol.ACTION_ADVANCE)) {
            // The classifier must never skip a gather step early; the inventory count is truth.
            if (itemStep) {
                if (count >= step.amount()) {
                    advanceStep("target count reached");
                } else {
                    arion.ArionLog.debug("laya advance rejected (" + count + "/" + step.amount()
                            + ") → step default");
                    runStepDefault(baritone, pr);
                }
            } else if (confidence >= ADVANCE_MIN_CONFIDENCE) {
                advanceStep("Laya: exit condition met");
            } else {
                arion.ArionLog.debug("laya advance below confidence floor → step default");
                runStepDefault(baritone, pr);
            }
            return;
        }

        if (confidence < config.layaMinConfidence.get()) {
            arion.ArionLog.debug("laya confidence below floor → step default");
            runStepDefault(baritone, pr);
            return;
        }

        switch (choice) {
            case LayaProtocol.ACTION_MINE -> actionMine(baritone, pr);
            case LayaProtocol.ACTION_EXPLORE -> actionExplore(baritone);
            case LayaProtocol.ACTION_COMBAT -> actionHunt(baritone, pr);
            case LayaProtocol.ACTION_FLEE -> actionFlee(baritone);
            case LayaProtocol.ACTION_EAT -> actionEat(baritone);
            default -> {
                arion.ArionLog.debug("unknown laya action: " + choice + " → step default");
                runStepDefault(baritone, pr);
            }
        }
    }

    /**
     * Deterministic safety rules that do not depend on the classifier's opinion.
     *
     * @return "flee", "eat", or null
     */
    private String safetyAction(IPlayerContext ctx,
                                arion.vessel.server.ServerVesselManager.Vitals vitals) {
        Player p = ctx.player();
        if (p == null || vitals == null) return null;
        Entity hostile = nearestHostile(ctx, p, 8.0);
        if (hostile != null && vitals.health() <= config.combatRetreatHealth.get()) return "flee";
        if (hostile == null && vitals.food() < 6) return "eat";
        return null;
    }

    private static Entity nearestHostile(IPlayerContext ctx, Player around, double radius) {
        return ctx.entitiesStream()
                .filter(e -> e != around && isHostile(e))
                .filter(e -> e.distanceToSqr(around) <= radius * radius)
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(around)))
                .orElse(null);
    }

    /** The step's natural action. Used when Laya is below the confidence floor or answers junk. */
    private void runStepDefault(IBaritone baritone, ProcessTracker.Progress pr) {
        ProcessTracker.Step step = tracker != null ? tracker.current() : null;
        if (step == null) return;
        String objective = step.objective() == null ? "" : step.objective().toLowerCase(Locale.ROOT);
        arion.ArionLog.debug("deterministic step action: " + objective + " " + step.target());
        switch (objective) {
            case "gather", "mine" -> actionMine(baritone, pr);
            case "explore" -> actionExplore(baritone);
            case "hunt" -> actionHunt(baritone, pr);
            case "craft", "smelt" -> nextLayaTick = tickCounter + 5; // tickPlan's craft branch owns it
            default -> nextLayaTick = tickCounter + 20;
        }
    }

    private void actionExplore(IBaritone baritone) {
        if (tracker == null) return;
        tracker.beginAction(-1);
        startJob(baritone, new Jobs.Explore(), false);
        layaJob = activeJob;
        layaJobStep = tracker.current();
        nextLayaTick = tickCounter + 40;
    }

    /**
     * Walk over the drops a mining action left behind before the next decision. Uses Baritone's
     * follow-process pickup predicate, capped at 10 s; if no matching item entity is nearby this
     * is a no-op. Kept separate from {@code layaJob} so its result is not counted as a step action.
     */
    private void startDropPickup(IBaritone baritone, IPlayerContext ctx, ProcessTracker.Step step) {
        if (step == null) return;
        String item = ProcessTracker.dropItemName(step);
        if (item.isBlank()) return;
        Player p = ctx.player();
        if (p == null) return;
        boolean found = false;
        for (Entity e : ctx.entitiesStream().toList()) {
            if (!(e instanceof net.minecraft.world.entity.item.ItemEntity ie)) continue;
            if (e.distanceToSqr(p) > 12 * 12) continue;
            String id = ProcessTracker.normalizeId(
                    BuiltInRegistries.ITEM.getKey(ie.getItem().getItem()).toString());
            if (id.equals(item)) {
                found = true;
                break;
            }
        }
        if (!found) {
            arion.ArionLog.debug("no " + item + " drops within 12m to collect");
            return;
        }
        arion.ArionLog.debug("drop sweep: " + item);
        startJob(baritone, new Jobs.Pickup(item, 20 * 10), false);
        pickupJob = activeJob;
        pickupDeadlineTick = tickCounter + 20 * 12;
        nextLayaTick = tickCounter + 20;
    }

    private void actionMine(IBaritone baritone, ProcessTracker.Progress pr) {
        if (tracker == null) return;
        ProcessTracker.Step step = tracker.current();
        if (step == null) return;

        String objective = step.objective() == null ? "" : step.objective().toLowerCase(Locale.ROOT);
        if (!objective.equals("gather") && !objective.equals("mine")) {
            // tickPlan's M3 crafting branch owns craft/smelt steps; nothing to mine here.
            nextLayaTick = tickCounter + 5;
            return;
        }
        String target = ProcessTracker.mineBlockName(step);
        if (target.isBlank()) {
            advanceStep("step has no target");
            return;
        }
        int count = tracker.count(pr, step);
        if (step.amount() > 0 && count >= step.amount()) {
            advanceStep("target count already reached");
            return;
        }
        int qty = step.amount() > 0 ? Math.max(1, step.amount() - count) : 0;
        arion.ArionLog.debug("mine action: item=" + step.targetId() + " → block=" + target
                + " qty=" + qty + " (have " + count + ")");
        tracker.beginAction(count);
        startJob(baritone, new Jobs.Mine(qty, target), false);
        layaJob = activeJob;
        layaJobStep = step;
        nextLayaTick = tickCounter + 20;
    }

    private void actionHunt(IBaritone baritone, ProcessTracker.Progress pr) {
        if (tracker == null) return;
        ProcessTracker.Step step = tracker.current();
        // Only a hunt step supplies a mob name; on any other step "combat" means the nearest threat.
        String target = "nearest";
        if (step != null && "hunt".equalsIgnoreCase(step.objective())) {
            String t = step.targetId();
            if (!t.isBlank() && !t.equalsIgnoreCase("nearest")) target = t;
        }
        var vm = arion.Arion.vessels();
        boolean toVessel = vm != null && vm.hasBaritone() && baritone == vm.baritone();
        if (toVessel) {
            // M4.1: native ICombatProcess — real attacks through VesselServerPlayerController.attack
            // plus Baritone's own melee/projectile shield (combatUseShield). A manual hunt is a
            // non-temporary process, so it takes pathing control while the tracker waits; the
            // per-tick hunt-end poll in FakePlayerManager reports back via onHuntEnded.
            tracker.beginAction(-1);
            String refused = vm.commandHunt(target);
            if (refused != null) {
                arion.ArionLog.debug("vessel hunt refused: " + refused);
                requestReplan(baritone, "vessel hunt refused: " + refused);
                return;
            }
            arion.ArionLog.debug("laya combat: " + target + " (vessel native ICombatProcess)");
            nextLayaTick = tickCounter + 40;
            return;
        }
        tracker.beginAction(-1);
        arion.ArionLog.debug("laya combat: " + target + " (self)");
        combat.hunt(baritone, target);
        nextLayaTick = tickCounter + 40;
    }

    private void actionFlee(IBaritone baritone) {
        IPlayerContext ctx = baritone.getPlayerContext();
        Player p = ctx.player();
        if (p == null || tracker == null) return;
        Entity threat = ctx.entitiesStream()
                .filter(e -> e != p && isHostile(e))
                .filter(e -> e.distanceToSqr(p) <= 24 * 24)
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(p)))
                .orElse(null);
        Vec3 away = threat != null
                ? p.position().subtract(threat.position())
                : p.getLookAngle().scale(-1.0);
        double horiz = Math.hypot(away.x, away.z);
        if (horiz < 1.0E-4) {
            away = new Vec3(1, 0, 0);
            horiz = 1.0;
        }
        BlockPos target = p.blockPosition().offset(
                (int) Math.round(away.x / horiz * 24), 0,
                (int) Math.round(away.z / horiz * 24));
        tracker.beginAction(-1);
        tell("Retreating!");
        startJob(baritone, new Jobs.Goto(target), false);
        layaJob = activeJob;
        layaJobStep = null;
        nextLayaTick = tickCounter + 20;
    }

    private void actionEat(IBaritone baritone) {
        if (tracker == null) return;
        if (tickCounter - lastEatAttemptTick < 200) {
            // already tried recently; don't spam the hotbar/chat
            nextLayaTick = tickCounter + 20;
            return;
        }
        lastEatAttemptTick = tickCounter;
        var vm = arion.Arion.vessels();
        boolean toVessel = vm != null && vm.hasBaritone() && baritone == vm.baritone();
        tracker.beginAction(-1);
        if (toVessel) {
            vm.commandEat();
        } else if (!survival.forceEat(baritone, baritone.getPlayerContext())) {
            tell("No food in the inventory to eat.");
        }
        // eating takes ~32 ticks; don't interrupt it with another action
        nextLayaTick = tickCounter + 50;
    }

    /** True for plan steps the M3 crafting engine owns. */
    private static boolean isCraftObjective(ProcessTracker.Step step) {
        if (step == null || step.objective() == null) return false;
        String objective = step.objective().toLowerCase(Locale.ROOT);
        return objective.equals("craft") || objective.equals("smelt");
    }

    /** Gather/mine steps that need a harvest tool tier check. */
    private static boolean isGatherObjective(ProcessTracker.Step step) {
        if (step == null || step.objective() == null) return false;
        String objective = step.objective().toLowerCase(Locale.ROOT);
        return objective.equals("gather") || objective.equals("mine");
    }

    /**
     * Craft the minimum harvest tool before the gather/mine step. Uses the M3 job manager; the
     * callback resumes the normal loop, which re-checks the tier and then starts the mine.
     */
    private void startToolCraft(IBaritone baritone, IPlayerContext ctx, Item tool,
                                ProcessTracker.Step step) {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) return;
        final int epoch = layaEpoch;
        final String toolId = BuiltInRegistries.ITEM.getKey(tool).toString();
        final String target = step.targetId();
        craftPending = true;
        craftStartTick = tickCounter;
        nextLayaTick = tickCounter + 10;
        arion.ArionLog.debug("tool prerequisite: " + toolId + " before mining " + target);
        tell("I need a " + toolId + " to harvest " + target + " — crafting it first.");
        final java.util.UUID hostUuid = mc.player != null ? mc.player.getUUID() : null;
        final boolean placeStations = config.craftPlaceStations.get();
        try {
            server.execute(() -> {
                ServerPlayer body = serverBody(server, hostUuid);
                if (body == null) {
                    mc.execute(() -> onCraftFinished(
                            new CraftingEngine.Result(false, "no server-side body available"),
                            toolId, 1, epoch));
                    return;
                }
                CraftJobManager.submit(server, body, toolId, 1, placeStations,
                        result -> mc.execute(() -> onCraftFinished(result, toolId, 1, epoch)));
            });
        } catch (Throwable t) {
            craftPending = false;
            tell("❌ Tool prerequisite failed: " + rootMessage(t));
        }
    }

    /**
     * Run the server-side crafting/smelting engine for the current step. The engine consumes the
     * exact recipe ingredients and adds the real output; the tracker advances on the inventory
     * count once the body/shadow inventory reflects it.
     */
    private void startCraftStep(IBaritone baritone, IPlayerContext ctx) {
        if (tracker == null) return;
        ProcessTracker.Step step = tracker.current();
        if (step == null) return;
        String target = step.targetId();
        if (target.isBlank()) {
            advanceStep("craft step has no target");
            return;
        }
        int amount = step.amount() > 0 ? step.amount() : 1;
        ProcessTracker.Progress pr = progressSnapshot(ctx);
        int have = tracker.count(pr, step);
        if (have >= amount) {
            advanceStep("already have " + have + "/" + amount);
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) {
            tell("Crafting needs a single-player / integrated server.");
            return;
        }
        final int epoch = layaEpoch;
        final String item = target;
        final int count = amount;
        final boolean placeStations = config.craftPlaceStations.get();
        craftPending = true;
        craftStartTick = tickCounter;
        nextLayaTick = tickCounter + 10;
        arion.ArionLog.debug("craft step: " + item + " x" + count + " (have " + have + ")");
        final java.util.UUID hostUuid = mc.player != null ? mc.player.getUUID() : null;
        try {
            server.execute(() -> {
                ServerPlayer body = serverBody(server, hostUuid);
                if (body == null) {
                    mc.execute(() -> onCraftFinished(
                            new CraftingEngine.Result(false, "no server-side body available to craft with"),
                            item, count, epoch));
                    return;
                }
                CraftJobManager.submit(server, body, item, count, placeStations,
                        result -> mc.execute(() -> onCraftFinished(result, item, count, epoch)));
            });
        } catch (Throwable t) {
            craftPending = false;
            tell("❌ Crafting failed to start: " + rootMessage(t));
        }
    }

    private void onCraftFinished(CraftingEngine.Result result, String item, int amount, int epoch) {
        if (epoch != layaEpoch) {
            arion.ArionLog.debug("stale craft result ignored (" + item + ")");
            return;
        }
        craftPending = false;
        if (result == null) {
            tell("❌ Crafting returned no result.");
            return;
        }
        if (result.success()) {
            arion.ArionLog.debug("craft ok: " + result.message());
            tell("⚒ " + result.message());
            nextLayaTick = tickCounter + 5;
        } else {
            arion.ArionLog.debug("craft failed: " + result.message());
            tell("❌ " + result.message());
            IBaritone b = arion.Arion.baritone();
            if (b != null) requestReplan(b, "craft failed: " + result.message());
        }
    }

    /** The vessel body if one exists and is bound, else the host's server-side player. */
    private static ServerPlayer serverBody(net.minecraft.server.MinecraftServer server, java.util.UUID hostUuid) {
        var vm = arion.Arion.vessels();
        if (vm != null && vm.hasVessel() && vm.hasBaritone()) {
            var svm = arion.vessel.server.ServerVesselManager.peek(server);
            if (svm != null) {
                var v = svm.vessel();
                if (v != null && v.isAlive()) return v;
            }
            // The bound vessel is dead/respawning: do NOT silently craft on the host.
            return null;
        }
        if (hostUuid != null) {
            return server.getPlayerList().getPlayer(hostUuid);
        }
        return null;
    }

    /** 'ai craft <item> [amount]' / 'ai smelt <item> [amount]'. */
    private void craftCommand(String arg, boolean smelt) {
        String[] parts = arg.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) {
            tell("usage: ai " + (smelt ? "smelt" : "craft") + " <item> [amount]");
            return;
        }
        String item = parts[0];
        int amount = parts.length > 1 ? Math.max(1, parseIntOr(parts[1], 1)) : 1;
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) {
            tell("Crafting needs a single-player / integrated server.");
            return;
        }
        final boolean placeStations = config.craftPlaceStations.get();
        tell((smelt ? "Smelting " : "Crafting ") + amount + "x " + item + "…");
        final java.util.UUID hostUuid = mc.player != null ? mc.player.getUUID() : null;
        try {
            server.execute(() -> {
                ServerPlayer body = serverBody(server, hostUuid);
                if (body == null) {
                    mc.execute(() -> tell("No server-side body available to craft with."));
                    return;
                }
                CraftJobManager.submit(server, body, item, amount, placeStations,
                        result -> mc.execute(() ->
                                tell((result.success() ? "⚒ " : "❌ ") + result.message())));
            });
        } catch (Throwable t) {
            tell("❌ Crafting failed to start: " + rootMessage(t));
        }
    }

    /** 'ai recipe <item>' — print the resolved recipe (ingredients + station). */
    private void recipeCommand(String arg) {
        String[] parts = arg.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) {
            tell("usage: ai recipe <item>");
            return;
        }
        String item = parts[0];
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) {
            tell("Recipes need a single-player / integrated server.");
            return;
        }
        final java.util.UUID hostUuid = mc.player != null ? mc.player.getUUID() : null;
        try {
            server.execute(() -> {
                ServerPlayer body = serverBody(server, hostUuid);
                if (body == null) {
                    mc.execute(() -> tell("No server-side body available."));
                    return;
                }
                String description = CraftingEngine.describe(body, item);
                mc.execute(() -> tell(description));
            });
        } catch (Throwable t) {
            tell("❌ Recipe lookup failed: " + rootMessage(t));
        }
    }

    /** Advance the macro step (Laya says the exit condition is met, or the player skips). */
    private void advanceStep(String reason) {
        if (tracker == null) return;
        // If the action for the current step is still running (e.g. an explore job hitting its
        // 60 s budget), stop it before moving on; otherwise the next step waits for a job whose
        // step no longer exists.
        ProcessTracker.Step current = tracker.current();
        if (layaJob != null && layaJobStep != null && layaJobStep.equals(current)) {
            Job finished = layaJob;
            layaJob = null;
            layaJobStep = null;
            arion.ArionLog.debug("advancing step → cancelling " + finished.label());
            IBaritone b = arion.Arion.baritone();
            if (b != null) cancelActiveJob(b, JobResult.DONE);
        }
        ProcessTracker.Step next = tracker.advance(reason);
        if (!tracker.isActive()) {
            ProcessTracker.Plan done = tracker.plan();
            tracker = null;
            layaJob = null;
            tell("✔ Plan complete: " + done.goal());
            memory.assistantSaid("[plan complete: " + done.goal() + "]");
            arion.ArionLog.debug("plan complete: " + done.goal());
        } else {
            tell("Step done (" + reason + ") → " + (next != null ? next.oneLine() : ""));
            nextLayaTick = tickCounter + 5;
        }
    }

    /** One-line plan status for 'ai plan'. */
    private String planStatus() {
        if (tracker == null) {
            return "No macro plan. 'ai plan <goal>' starts one "
                    + "(e.g. 'ai plan get 20 iron ingots and an iron pickaxe').";
        }
        IBaritone b = arion.Arion.baritone();
        ProcessTracker.Progress pr = b != null ? progressSnapshot(b.getPlayerContext()) : null;
        StringBuilder sb = new StringBuilder("Plan: ").append(tracker.plan().goal())
                .append(" — ").append(tracker.progressLine(pr));
        if (plannerPending) sb.append(" | planner thinking…");
        sb.append(" | Laya ").append(layaEnabled() ? "on" : "off");
        if (lastLayaLatencyMs >= 0) {
            sb.append(String.format(Locale.ROOT, " | last %.0fms", lastLayaLatencyMs));
        }
        if (layaError != null) sb.append(" | last error: ").append(layaError);
        return sb.toString();
    }

    /** One-line transport status for 'ai laya'. */
    private String layaStatus() {
        StringBuilder sb = new StringBuilder("Laya ").append(layaEnabled() ? "ON" : "OFF")
                .append(" @ ").append(config.layaBaseUrl.get())
                .append(" (every ").append(config.layaIntervalTicks.get()).append(" ticks)");
        if (!lastLayaSummary.isEmpty()) sb.append(" | last: ").append(lastLayaSummary);
        if (lastLayaLatencyMs >= 0) {
            sb.append(String.format(Locale.ROOT, " | %.0fms", lastLayaLatencyMs));
        }
        if (layaError != null) sb.append(" | error: ").append(layaError);
        sb.append(" | plan: ").append(tracker == null ? "none" : tracker.toString());
        return sb.toString();
    }

    /** 'ai laya test' — ping /health, then run one real decision and report it in chat. */
    private void testLaya() {
        Minecraft mc = Minecraft.getInstance();
        // capture the client: the composed callback runs on a Laya worker thread, and calling
        // layaClient() there could rebuild/close it if the configured URL changed meanwhile
        final LayaClient client = layaClient();
        tell("Pinging Laya at " + client.baseUrl() + "…");
        client.health()
                .thenCompose(h -> {
                    mc.execute(() -> tell("Laya: " + h.describe()));
                    if (!h.ok()) {
                        return java.util.concurrent.CompletableFuture.<LayaClient.Result>completedFuture(null);
                    }
                    String state = "Sub-goal: gather 8 oak_log (have 3). HP: 18/20. Hunger: 16/20. "
                            + "Nearby: 1 zombie (dist 9m), 1 oak_log (dist 3m).";
                    return client.decide(state, LayaProtocol.microQuestions());
                })
                .thenAccept(res -> {
                    if (res == null) return;
                    mc.execute(() -> tell("Laya test: " + res.summarize()
                            + String.format(Locale.ROOT, " (%.0f ms)", res.latencyMs())));
                })
                .exceptionally(t -> {
                    mc.execute(() -> tell("❌ Laya test failed: " + rootMessage(t)
                            + " — start laya-project\\laya_http_server.py"));
                    return null;
                });
    }

    /** 'ai decide' — one diagnostic decision with no dispatch (the live loop acts on its own). */
    private void forceLayaDecision(IBaritone baritone) {
        IBaritone b = baritone != null ? baritone : arion.Arion.baritone();
        if (b == null || b.getPlayerContext().player() == null) {
            tell("No world loaded.");
            return;
        }
        ProcessTracker.Step step = tracker != null ? tracker.current() : null;
        ProcessTracker.Progress pr = progressSnapshot(b.getPlayerContext());
        String state;
        if (step != null) {
            state = buildLayaState(b.getPlayerContext(), step, pr, null);
        } else {
            arion.vessel.server.ServerVesselManager.Vitals v = clientVitals(b.getPlayerContext());
            state = "Sub-goal: idle (no macro plan). HP: "
                    + (v != null ? (int) Math.ceil(v.health()) : 20) + "/20. Hunger: "
                    + (v != null ? v.food() : 20) + "/20. Nearby: "
                    + nearbyForLaya(b.getPlayerContext()) + ".";
        }
        final String full = cap(state, 6000);
        Minecraft mc = Minecraft.getInstance();
        tell("Asking Laya for a decision…");
        layaClient().decide(full, LayaProtocol.microQuestions())
                .thenAccept(res -> mc.execute(() -> {
                    lastLayaLatencyMs = res.latencyMs();
                    lastLayaSummary = res.summarize();
                    layaError = null;
                    tell("Laya: " + res.summarize()
                            + String.format(Locale.ROOT, " (%.0f ms)", res.latencyMs())
                            + (tracker == null ? " [dry run — no plan]" : ""));
                }))
                .exceptionally(t -> {
                    mc.execute(() -> {
                        layaError = rootMessage(t);
                        tell("❌ Laya: " + layaError);
                    });
                    return null;
                });
    }

    /**
     * The System 1 state string. Deliberately the canonical single-line format
     * ("Sub-goal: mine 24 iron_ore (current: 8). HP: 16/20. Hunger: 14/20. Nearby: ..."),
     * which the classifier answers with 0.75–0.97 confidence. Multi-line states with plan
     * text/JSON collapse to ~0.1 confidence and produce random choices (measured live).
     *
     * @param authoritative the vessel's real vitals (the shadow does not mirror them), or null
     *                      to use the client body's own values
     */
    private String buildLayaState(IPlayerContext ctx, ProcessTracker.Step step,
                                  ProcessTracker.Progress pr,
                                  arion.vessel.server.ServerVesselManager.Vitals authoritative) {
        arion.vessel.server.ServerVesselManager.Vitals v =
                authoritative != null ? authoritative : clientVitals(ctx);
        int hp = v != null ? (int) Math.ceil(v.health()) : 20;
        int food = v != null ? v.food() : 20;
        String nearby = nearbyForLaya(ctx);
        if (scanCount > 0 && !scanTargetId.isBlank()) {
            String block = scanCount + " " + scanTargetId + " (dist " + scanDist + "m)";
            nearby = "nothing".equals(nearby) ? block : block + ", " + nearby;
        }
        return "Sub-goal: " + layaGoalPhrase(step, pr)
                + ". HP: " + hp + "/20. Hunger: " + food + "/20. Nearby: " + nearby + ".";
    }

    /**
     * Cheap target-block scan (12-block box, once per second per step). Feeds two things:
     * a deterministic exit for "explore until X is within N blocks" steps, and the canonical
     * "1 oak_log (dist 3m)" hint the System 1 classifier uses to pick mine.
     */
    private void refreshTargetScan(IPlayerContext ctx, ProcessTracker.Step step) {
        if (tracker == null) return;
        if (scanStepIndex == tracker.stepIndex() && tickCounter - lastScanTick < 20) return;
        scanStepIndex = tracker.stepIndex();
        lastScanTick = tickCounter;
        scanCount = 0;
        scanDist = -1;
        scanTargetId = stepFocusTarget(step);
        if (scanTargetId.isBlank()) return;
        net.minecraft.world.level.Level world = ctx.world();
        Player p = ctx.player();
        if (world == null || p == null) return;
        BlockPos feet = p.blockPosition();
        int bestSq = Integer.MAX_VALUE;
        for (int dx = -12; dx <= 12; dx++) {
            for (int dy = -6; dy <= 6; dy++) {
                for (int dz = -12; dz <= 12; dz++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    var state = world.getBlockState(pos);
                    if (state.isAir()) continue;
                    String id = ProcessTracker.normalizeId(
                            BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                    if (!id.equals(scanTargetId)) continue;
                    scanCount++;
                    int dsq = dx * dx + dy * dy + dz * dz;
                    if (dsq < bestSq) bestSq = dsq;
                }
            }
        }
        if (bestSq != Integer.MAX_VALUE) scanDist = (int) Math.ceil(Math.sqrt(bestSq));
    }

    /** "mine 20 oak_log (current: 0)", "find oak_log (current: none found)", … */
    private String layaGoalPhrase(ProcessTracker.Step step, ProcessTracker.Progress pr) {
        String objective = step.objective() == null ? "" : step.objective().toLowerCase(Locale.ROOT);
        String target = stepFocusTarget(step);
        int count = step.itemObjective() && pr != null && tracker != null
                ? tracker.count(pr, step) : 0;
        return switch (objective) {
            case "gather", "mine" -> "mine " + Math.max(1, step.amount()) + " "
                    + (target.isBlank() ? "the target block" : target)
                    + " (current: " + count + ")";
            case "explore" -> "find " + (target.isBlank() ? "resources" : target)
                    + " (current: none found)";
            case "hunt" -> "hunt " + Math.max(1, step.amount()) + " "
                    + (target.isBlank() ? "hostile mobs" : target) + " (current: 0)";
            case "craft", "smelt" -> "craft " + (target.isBlank() ? "the item" : target)
                    + " (current: " + count + ")";
            default -> step.oneLine();
        };
    }

    /**
     * The step's target id, or — when the planner leaves it blank on an explore step — the
     * minecraft id named in the exit condition ("nearby_block:minecraft:oak_log within 16").
     * "find oak_log" is answered with 0.66 confidence; "find resources" falls below the floor.
     */
    private static String stepFocusTarget(ProcessTracker.Step step) {
        String target = step.targetId();
        if (!target.isBlank()) return target;
        String exit = step.exitCondition();
        if (exit == null || exit.isBlank()) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("minecraft:([a-z0-9_]+)").matcher(exit.toLowerCase(Locale.ROOT));
        return m.find() ? m.group(1) : "";
    }

    /** Up to six nearby living entities, self/duplicate filtered; "nothing" when empty. */
    private static String nearbyForLaya(IPlayerContext ctx) {
        Player p = ctx.player();
        if (p == null) return "nothing";
        List<Entity> near = ctx.entitiesStream()
                .filter(e -> e != p && e instanceof LivingEntity)
                .filter(e -> !ArionPerception.isSelfOrVessel(e, p))
                .filter(e -> e.distanceToSqr(p) <= 16 * 16)
                .sorted(Comparator.comparingDouble(e -> e.distanceToSqr(p)))
                .limit(6)
                .toList();
        if (near.isEmpty()) return "nothing";
        StringBuilder sb = new StringBuilder(96);
        for (Entity e : near) {
            if (!sb.isEmpty()) sb.append(", ");
            int dist = (int) Math.round(Math.sqrt(e.distanceToSqr(p)));
            sb.append(e.getName().getString()).append(" (dist ").append(dist).append("m)");
        }
        return sb.toString();
    }

    private static arion.vessel.server.ServerVesselManager.Vitals clientVitals(IPlayerContext ctx) {
        Player p = ctx.player();
        if (p == null) return null;
        return new arion.vessel.server.ServerVesselManager.Vitals(
                (float) p.getHealth(), p.getFoodData().getFoodLevel());
    }

    /** Inventory + position + vitals snapshot for the tracker. Client thread; reads the shadow when bound. */
    private ProcessTracker.Progress progressSnapshot(IPlayerContext ctx) {
        Map<String, Integer> items = new LinkedHashMap<>();
        Player p = ctx.player();
        if (p != null) {
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack s = p.getInventory().getItem(i);
                if (s.isEmpty()) continue;
                String id = ProcessTracker.normalizeId(
                        BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
                items.merge(id, s.getCount(), Integer::sum);
            }
        }
        return new ProcessTracker.Progress(
                items,
                p != null ? p.getX() : 0,
                p != null ? p.getY() : 0,
                p != null ? p.getZ() : 0,
                p != null ? p.getHealth() : 0,
                p != null ? p.getFoodData().getFoodLevel() : 0,
                tickCounter);
    }

    /** Same hostility rule as the combat layer: type + proximity, never Mob.getTarget(). */
    private static boolean isHostile(Entity e) {
        return e instanceof Monster || e instanceof Slime || e instanceof Phantom;
    }

    // ------------------------------------------------- M4: projectile auto-shield

    /** Runtime override wins over the config value. */
    private boolean shieldEnabled() {
        Boolean override = shieldRuntimeOverride;
        return override != null ? override : config.autoShield.get();
    }

    /**
     * Effective auto-shield state (runtime 'ai shield on|off' wins over the config). Read by
     * {@code FakePlayerManager} to mirror the setting onto the vessel body, which owns its own
     * server-side projectile reflex.
     */
    public boolean autoShieldEnabled() {
        return shieldEnabled();
    }

    /**
     * Layer 0 reflex: scan for incoming projectiles every tick and raise the offhand shield when
     * one is predicted to hit. Runs for both the real player and the vessel body. No LLM.
     */
    private void tickThreat(IPlayerContext ctx, boolean vesselBound) {
        if (vesselBound) {
            // The vessel body owns its own reflex on the server thread (FakeServerPlayer.tick):
            // it sees a projectile the tick it spawns on the server and holds the real offhand.
            // Scanning here would only add a server.execute() hop and depend on the client's
            // entity tracker having broadcast the arrow.
            shielding = false;
            shieldHoldTicks = 0;
            lastThreatLabel = "";
            return;
        }
        Player p = ctx.player();
        if (p == null || p.isDeadOrDying() || !config.enabled.get()) {
            if (shielding) setShield(false, null, vesselBound, p);
            return;
        }
        if (!shieldEnabled()) {
            if (shielding) setShield(false, null, vesselBound, p);
            return;
        }

        ThreatEngine.Threat threat = this.threat.scan(
                p, ctx.entitiesStream().toList(), config.shieldRange.get());
        if (arion.ArionLog.on() && tickCounter % 20 == 0) {
            long nearby = ctx.entitiesStream()
                    .filter(e -> e instanceof net.minecraft.world.entity.projectile.Projectile)
                    .filter(e -> e.distanceToSqr(p) <= config.shieldRange.get() * config.shieldRange.get())
                    .count();
            if (nearby > 0) {
                arion.ArionLog.debug("threat scan: " + nearby + " projectile(s) within "
                        + config.shieldRange.get() + "m");
            }
        }
        if (threat != null && threat.impactTicks() <= 20) {
            lastThreatLabel = threat.label();
            shieldHoldTicks = Math.max(1, config.shieldHoldTicks.get());
            boolean hasShield = vesselBound || p.getOffhandItem().is(Items.SHIELD);
            if (!hasShield) {
                if (tickCounter - lastShieldWarnTick > 200) {
                    lastShieldWarnTick = tickCounter;
                    tell("⚠ Incoming " + threat.label() + " — no shield in the offhand!");
                }
                return;
            }
            if (!shielding) {
                setShield(true, threat.projectile(), vesselBound, p);
                arion.ArionLog.debug("auto-shield raised: " + threat.label());
            } else {
                aimShield(threat.projectile(), vesselBound, p);
            }
        } else if (shielding && --shieldHoldTicks <= 0) {
            setShield(false, null, vesselBound, p);
            arion.ArionLog.debug("auto-shield lowered");
        }
    }

    /** Raise/lower the shield on the active body. */
    private void setShield(boolean raise, Entity target, boolean vesselBound, Player p) {
        if (raise) {
            double x = target != null ? target.getX() : 0;
            double y = target != null ? target.getY() : 0;
            double z = target != null ? target.getZ() : 0;
            if (vesselBound) {
                var vm = arion.Arion.vessels();
                if (vm != null) vm.commandShield(true, x, y, z);
            } else if (p != null && p.getOffhandItem().is(Items.SHIELD)) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.gameMode != null) {
                    if (p.isUsingItem() && !p.getUseItem().is(Items.SHIELD)) {
                        p.stopUsingItem();
                        mc.gameMode.releaseUsingItem(p);
                    }
                    mc.gameMode.useItem(p, InteractionHand.OFF_HAND);
                }
            }
            shielding = true;
        } else {
            if (vesselBound) {
                var vm = arion.Arion.vessels();
                if (vm != null) vm.commandShield(false, 0, 0, 0);
            } else if (p != null) {
                Minecraft mc = Minecraft.getInstance();
                if (p.isUsingItem() && p.getUseItem().is(Items.SHIELD)) {
                    p.stopUsingItem();
                    if (mc.gameMode != null) mc.gameMode.releaseUsingItem(p);
                }
            }
            shielding = false;
            shieldHoldTicks = 0;
            lastThreatLabel = "";
        }
    }

    /** Face the threat while blocking (vessel: server-side look override; self: client rotation). */
    private void aimShield(Entity target, boolean vesselBound, Player p) {
        if (target == null) return;
        if (vesselBound) {
            var vm = arion.Arion.vessels();
            if (vm != null) vm.commandShield(true, target.getX(), target.getY(), target.getZ());
            return;
        }
        if (p == null) return;
        Vec3 eye = p.getEyePosition();
        Vec3 dir = target.position().subtract(eye);
        if (dir.lengthSqr() > 1.0E-6) {
            float yaw = (float) (Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90.0);
            float pitch = (float) (-Math.toDegrees(Math.atan2(dir.y, Math.hypot(dir.x, dir.z))));
            p.setYRot(yaw);
            p.setYHeadRot(yaw);
            p.setXRot(pitch);
        }
    }

    private static String cap(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Turn a brain decision into a Baritone job. */
    private void dispatch(IBaritone baritone, ArionAction action) {
        if (!action.say().isBlank()) {
            tell(action.say());
            memory.assistantSaid(action.say());
        }
        debug("dispatch: " + action.verb() + " arg=\"" + action.arg() + "\"");
        IPlayerContext ctx = baritone.getPlayerContext();
        switch (action.verb()) {
            case "mine" -> {
                // accept "N block" (count) or bare "block[,more]"
                String arg = action.arg().trim();
                int qty = 0;
                String rest = arg;
                String[] toks = arg.split("\\s+", 2);
                if (toks.length == 2 && toks[0].matches("\\d+")) {
                    qty = Integer.parseInt(toks[0]);
                    rest = toks[1];
                }
                String[] blocks = rest.isBlank() ? new String[]{"oak_log"} : rest.split("[,\\s]+");
                startJob(baritone, new Jobs.Mine(qty, blocks));
            }
            case "follow" -> {
                String name = action.arg().isBlank()
                        ? (ctx.player() != null ? ctx.player().getName().getString() : "")
                        : action.arg().trim();
                startJob(baritone, new Jobs.Follow(name));
            }
            case "goto" -> dispatchGoto(baritone, action.arg());
            case "hunt" -> {
                stopAll(baritone, JobResult.CANCELED); // combat and jobs are mutually exclusive
                // when driving the borrowed vessel, use its packet-safe fighter — this
                // controller's real-player fighter would fire gameMode.attack()
                var vm = arion.Arion.vessels();
                boolean toVessel = vm != null && vm.hasBaritone() && baritone == vm.baritone();
                arion.ArionLog.debug("hunt via " + (toVessel ? "vessel fighter" : "real-player fighter"));
                if (toVessel) {
                    String result = vm.commandHunt(action.arg().trim());
                    if (result != null) tell(result);
                } else {
                    combat.hunt(baritone, action.arg());
                }
            }
            case "explore" -> startJob(baritone, new Jobs.Explore());
            case "farm" -> {
                int range = action.arg().isBlank()
                        ? config.farmDefaultRange.get()
                        : parseIntOr(action.arg().trim(), config.farmDefaultRange.get());
                startJob(baritone, new Jobs.Farm(range));
            }
            case "get" -> {
                if (action.arg().isBlank()) {
                    tell("get what? name a block (e.g. crafting_table, chest, furnace).");
                } else {
                    startJob(baritone, new Jobs.GetTo(action.arg().trim().split("[,\\s]+")[0]));
                }
            }
            case "pickup" -> startJob(baritone, new Jobs.Pickup(action.arg().trim()));
            case "build" -> {
                if (action.arg().isBlank()) {
                    tell("build what? name a schematic file from the schematics folder.");
                } else {
                    startJob(baritone, new Jobs.Build(action.arg().trim()));
                }
            }
            case "clear" -> dispatchClear(baritone, action.arg());
            case "stop" -> stopAll(baritone, JobResult.CANCELED);
            case "chat" -> { /* already said above */ }
            default -> tell("(don't know how to: " + action.verb() + ")");
        }
    }

    private void dispatchGoto(IBaritone baritone, String arg) {
        String[] parts = arg.trim().split("\\s+");
        IPlayerContext ctx = baritone.getPlayerContext();
        // compass / surface shortcuts
        if (parts.length == 1) {
            BlockPos feet = ctx.playerFeet();
            switch (parts[0].toLowerCase()) {
                case "north" -> { startJob(baritone, new Jobs.GotoXZ(feet.getX(), feet.getZ() - 1000)); return; }
                case "south" -> { startJob(baritone, new Jobs.GotoXZ(feet.getX(), feet.getZ() + 1000)); return; }
                case "east"  -> { startJob(baritone, new Jobs.GotoXZ(feet.getX() + 1000, feet.getZ())); return; }
                case "west"  -> { startJob(baritone, new Jobs.GotoXZ(feet.getX() - 1000, feet.getZ())); return; }
                case "surface" -> { startJob(baritone, new Jobs.GotoY(ctx.world().getSeaLevel())); return; }
                default -> { /* fall through to coordinate parsing */ }
            }
        }
        // relative ~dx ~dy ~dz
        if (parts.length == 3 && parts[0].startsWith("~")) {
            try {
                BlockPos feet = ctx.playerFeet();
                int dx = Integer.parseInt(parts[0].substring(1));
                int dy = Integer.parseInt(parts[1].substring(1));
                int dz = Integer.parseInt(parts[2].substring(1));
                startJob(baritone, new Jobs.Goto(feet.offset(dx, dy, dz)));
                return;
            } catch (NumberFormatException e) {
                tell("goto: couldn't parse relative coordinates");
                return;
            }
        }
        // absolute x y z
        if (parts.length == 3) {
            try {
                startJob(baritone, new Jobs.Goto(new BlockPos(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]))));
            } catch (NumberFormatException e) {
                tell("goto: couldn't parse coordinates");
            }
        } else {
            tell("goto needs x y z, ~dx ~dy ~dz, or a direction (north/south/east/west/surface)");
        }
    }

    private void dispatchClear(IBaritone baritone, String arg) {
        String[] p = arg.trim().split("\\s+");
        if (p.length != 6) {
            tell("clear needs two corners: x1 y1 z1 x2 y2 z2");
            return;
        }
        try {
            BlockPos a = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            BlockPos bpos = new BlockPos(Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]));
            long vol = (long) (Math.abs(bpos.getX() - a.getX()) + 1)
                    * (Math.abs(bpos.getY() - a.getY()) + 1)
                    * (Math.abs(bpos.getZ() - a.getZ()) + 1);
            if (vol > config.clearMaxVolume.get()) {
                tell("That's " + vol + " blocks — over the clear limit of " + config.clearMaxVolume.get()
                        + ". Raise clearMaxVolume in the config or clear a smaller region.");
                return;
            }
            startJob(baritone, new Jobs.Clear(a, bpos));
        } catch (NumberFormatException e) {
            tell("clear: couldn't parse coordinates");
        }
    }

    private static int parseIntOr(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    // ------------------------------------------------------------- lifecycle

    private void startJob(IBaritone baritone, Job job) {
        startJob(baritone, job, true);
    }

    /**
     * @param announce true for player-issued commands ("On it: …"); false for autonomous
     *                 Laya actions, which would otherwise spam chat every decision
     */
    private void startJob(IBaritone baritone, Job job, boolean announce) {
        // Cancel the previous Arion job WITHOUT the vessel-wide path cancel. stopAll() calls
        // vm.commandStop() → PathingBehavior.cancelEverything(), which queues PathEvent.CANCELED
        // for the next Baritone tick — by then this new job is active, and onPathEvent killed it
        // immediately ("mine oak_log → CANCELED"). JobRunner.cancel is enough here.
        cancelActiveJob(baritone, JobResult.CANCELED);
        cancelPendingBrain();
        activeJob = job;
        jobStartTick = tickCounter;
        if (announce) tell("On it: " + job.label());
        debug("job started: " + job.label());
        jobStart(baritone, job);
    }

    /** Drop the current job (if any) and report it. Does not touch the vessel's pathing. */
    private void cancelActiveJob(IBaritone baritone, JobResult result) {
        if (activeJob == null) return;
        Job j = activeJob;
        activeJob = null;
        JobRunner.cancel(j, baritone);
        onJobFinished(j, result);
    }

    private void jobStart(IBaritone baritone, Job job) {
        // Jobs start their Baritone process via a protected method; call it here
        // through a small package bridge to keep the client-thread requirement explicit.
        try {
            JobRunner.start(job, baritone);
        } catch (Throwable t) {
            // a job that fails to start (e.g. build with a missing schematic) must not
            // crash the tick loop — report it and clear the active job
            if (activeJob == job) activeJob = null;
            tell(job.label() + " → FAILED (" + rootMessage(t) + ")");
            debug("job failed to start: " + job.label() + ": " + t);
        }
    }

    /** Stop everything: the active job, any combat, and any in-flight brain request. */
    public void stopAll(IBaritone baritone, JobResult result) {
        arion.ArionLog.debug("stopAll (job=" + (activeJob != null ? activeJob.label() : "none")
                + ", combat=" + combat.isActive() + ")");
        cancelPendingBrain();
        cancelActiveJob(baritone, result);
        if (combat.isActive()) {
            combat.stop(baritone);
        }
        // the kill switch stops the vessel too (its Baritone pathing + any seek). NOTE: this
        // queues PathEvent.CANCELED; startJob deliberately does not go through here for that
        // reason (see startJob).
        var vm = arion.Arion.vessels();
        if (vm != null) {
            vm.commandStop();
        }
        // the player's kill switch also abandons the macro plan (Laya actions alone never call
        // stopAll, so autonomous combat does not cancel the plan)
        cancelPlan("stopped by player");
        // lower the auto-shield immediately
        if (shielding) {
            boolean bound = vm != null && vm.hasBaritone();
            Player p = baritone != null ? baritone.getPlayerContext().player() : null;
            setShield(false, null, bound, p);
        }
    }

    private void onJobFinished(Job job, JobResult result) {
        // An autonomous drop sweep is bookkeeping, not a step action: no chat, no tracker note.
        if (job == pickupJob) {
            pickupJob = null;
            arion.ArionLog.debug("drop sweep finished → " + result);
            nextLayaTick = tickCounter + 5;
            return;
        }
        tell(job.label() + " → " + result);
        debug("job finished: " + job.label() + " → " + result);
        // feed the outcome back to the brain's memory so it knows what happened
        memory.assistantSaid("[job " + job.label() + " finished: " + result + "]");
        // and to the macro tracker, when this job was a System 1 action
        if (tracker != null && job == layaJob) {
            ProcessTracker.Step step = layaJobStep;
            layaJob = null;
            layaJobStep = null;
            IBaritone b = arion.Arion.baritone();
            ProcessTracker.Progress pr = b != null ? progressSnapshot(b.getPlayerContext()) : null;
            tracker.noteActionFinished(result == JobResult.DONE,
                    pr != null ? tracker.countCurrent(pr) : -1, tickCounter);
            // mining jobs leave drops on the ground; sweep them before the next decision
            if (step != null && step.itemObjective()) pendingPickupStep = step;
            nextLayaTick = tickCounter + 5;
        }
    }

    // ------------------------------------------------------------ event pump

    @Override
    public void onTick(TickEvent event) {
        // IN = in-game (player + world non-null); OUT = main menu. Only act in-game.
        if (event.getType() != TickEvent.Type.IN) return;
        tickCounter++;
        IBaritone baritone = arion.Arion.baritone();
        if (baritone == null) return;
        IPlayerContext ctx = baritone.getPlayerContext();
        if (ctx.player() == null || ctx.world() == null) return;

        // When the vessel has borrowed the primary baritone, ctx.player() is the vessel.
        // The vessel's own (packet-safe, vesselMode) survival + combat are ticked by
        // FakePlayerManager.tick() — running THIS fighter here too would double-tick and,
        // worse, fire the non-vesselMode gameMode.attack() under the real account. So
        // while the vessel is bound we skip our reflexes and only supervise jobs (which
        // are packet-safe: movement only; NoopPlayerController blocks break/place).
        var vm = arion.Arion.vessels();
        boolean vesselBound = vm != null && vm.hasBaritone();

        if (!vesselBound) {
            // survival first (eating, death/respawn) — reflexes, never the LLM
            survival.tick(baritone, ctx, this::onDeathResume);
            // combat is a reflex loop, ticks independently of the LLM
            combat.tick(baritone, ctx);
        }

        // supervise the active job (stall/timeout/completion failsafes) for whichever
        // body the baritone currently drives — the real player, or the vessel when bound
        if (activeJob != null) {
            JobResult result = activeJob.tick(baritone, ctx);
            if (result != null) {
                Job j = activeJob;
                activeJob = null;
                onJobFinished(j, result);
            }
        }

        // System 2 plan supervision + System 1 (Laya) micro loop. Runs after job supervision so
        // a job that ended this tick is reported to the tracker before the next decision. A bug
        // here must never take down Baritone's event dispatch.
        // M4 projectile reflex runs for both bodies before the planning loop so an incoming arrow
        // gets the shield even while a job/craft is active.
        try {
            tickThreat(ctx, vesselBound);
        } catch (Throwable t) {
            arion.ArionLog.debug("threat tick failed: " + t);
        }
        try {
            tickPlan(baritone, ctx);
        } catch (Throwable t) {
            arion.ArionLog.debug("plan tick failed: " + t);
        }
    }

    @Override
    public void onPathEvent(PathEvent event) {
        IBaritone baritone = arion.Arion.baritone();
        if (baritone == null || activeJob == null) return;
        // Combat uses the CustomGoalProcess too — when combat is running, a path event
        // is about combat's goal, not the active job's, so don't let it clobber the job.
        if (combat.isActive()) return;
        // a path-calc failure surfaces here rather than via isActive()
        switch (event) {
            case CALC_FAILED, NEXT_CALC_FAILED -> {
                if (!activeJob.calcFailureIsFatal()) {
                    debug("ignoring CALC_FAILED for " + activeJob.label()
                            + " (Baritone process retries)");
                    return;
                }
                Job j = activeJob;
                activeJob = null;
                JobRunner.cancel(j, baritone);
                onJobFinished(j, JobResult.FAILED);
            }
            case CANCELED -> {
                // cancelEverything() queues this event for the next path tick. If it arrives
                // right after a job took control, it belongs to the job we just replaced -
                // not to the new one.
                if (tickCounter - jobStartTick <= CANCEL_GRACE_TICKS) {
                    debug("ignoring stale CANCELED " + (tickCounter - jobStartTick)
                            + " tick(s) after job start (" + activeJob.label() + ")");
                    return;
                }
                // A path cancellation is not a process cancellation: if the job's Baritone
                // process is still active (e.g. MineProcess re-pathing), the event is stale.
                boolean stillActive = false;
                try {
                    stillActive = activeJob.baritoneActive(baritone);
                } catch (Throwable ignored) {}
                if (stillActive) {
                    debug("ignoring CANCELED: " + activeJob.label() + " process still active");
                    return;
                }
                Job j = activeJob;
                activeJob = null;
                onJobFinished(j, JobResult.CANCELED);
            }
            default -> { /* AT_GOAL etc. handled by isActive() going false in tick */ }
        }
    }

    /** Called by SurvivalManager after respawn so we can resume an interrupted job. */
    private void onDeathResume(IBaritone baritone) {
        if (config.autoResumeAfterDeath.get() && activeJob != null) {
            tell("Respawned — resuming " + activeJob.label());
            JobRunner.start(activeJob, baritone);
        }
    }

    // ---------------------------------------------------------------- output

    /** Bot → you, straight into the client's chat HUD (and out loud via voice, if set up). */
    private void tell(String message) {
        tell(message, true);
    }

    /** Debug trace — chat + latest.log, only when debugMode is on. Delegates to the shared sink. */
    private void debug(String message) {
        arion.ArionLog.debugChat(message);
    }

    private void tell(String message, boolean speak) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                Helper.HELPER.logDirect(Component.literal("[Arion] " + message));
            }
        });
        if (speak) {
            arion.voice.ArionVoice.speak(message); // no-op unless SVC + TTS configured
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    public CombatController combat() { return combat; }
    public SurvivalManager survival() { return survival; }
}
