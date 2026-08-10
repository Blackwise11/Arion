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
import arion.config.ArionConfig;
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
import net.minecraft.network.chat.Component;

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

    public ArionController(ArionConfig config, ArionBrain brain, ArionMemory memory) {
        this.config = config;
        this.brain = brain;
        this.memory = memory;
        this.survival = new SurvivalManager(config);
        this.combat = new CombatController(config);
        // surface hunt outcomes in chat + brain memory, like job results
        this.combat.setHuntListener(reason -> tell("Hunt ended (" + reason + ")."));
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
                // pathfind to the player's feet
                vm.baritone().getCustomGoalProcess().setGoalAndPath(
                        new baritone.api.pathing.goals.GoalNear(self.blockPosition(), 2));
            } else {
                vm.commandWalkTo(self.getX(), self.getY(), self.getZ());
            }
            tell(vm.vessel().getName().getString() + " is coming to you.");
            return;
        }
        if (lower.equals("vessel stay") || lower.equals("vessel stop")) {
            arion.Arion.vessels().commandStop();
            tell("Vessel staying put.");
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

        // ---- routing: default to the vessel's Baritone if one is bound, unless --self ----
        boolean forceSelf = lower.contains("--self");
        boolean explicitVessel = lower.contains("--vessel");
        String cleaned = lower.replace("--vessel", "").replace("--self", "").replaceAll("\\s+", " ").trim();
        String cleanedRaw = message.replace("--vessel", "").replace("--self", "").replaceAll("\\s+", " ").trim();
        var vm = arion.Arion.vessels();
        if (!forceSelf && vm.hasBaritone()) {
            // a vessel Baritone is borrowed: route work commands to it by default.
            // hunt must use the vessel's packet-safe (vesselMode) fighter, not this
            // controller's real-player fighter, which would fire gameMode.attack().
            if (cleaned.startsWith("hunt ")) {
                stopAll(vm.baritone(), JobResult.CANCELED);
                String result = vm.commandHunt(cleaned.substring(5).trim());
                tell(result != null ? result : "Vessel hunting — 'ai stop' to call it off.");
                return;
            }
            IBaritone vb = vm.baritone();
            debug("routing to vessel: \"" + cleaned + "\" (explicit=" + explicitVessel + ")");
            if (tryHardcoded(vb, cleaned)) return;
            askBrain(vb, cleanedRaw);
            return;
        }

        // hardcoded fast-path commands (no LLM round-trip) for responsiveness
        if (tryHardcoded(baritone, cleaned)) {
            return;
        }

        // otherwise hand it to the brain
        askBrain(baritone, cleanedRaw);
    }

    /** Hardcoded commands that don't need the LLM (milestones 1–2). */
    private boolean tryHardcoded(IBaritone baritone, String lower) {
        IPlayerContext ctx = baritone.getPlayerContext();
        if (lower.equals("follow") || lower.equals("follow me")) {
            String name = ctx.player() != null ? ctx.player().getName().getString() : "";
            startJob(baritone, new Jobs.Follow(name));
            return true;
        }
        if (lower.startsWith("mine ")) {
            String[] blocks = lower.substring(5).trim().split("[,\\s]+");
            startJob(baritone, new Jobs.Mine(0, blocks));
            return true;
        }
        if (lower.equals("explore")) {
            startJob(baritone, new Jobs.Explore());
            return true;
        }
        if (lower.startsWith("hunt ")) {
            String mob = lower.substring(5).trim();
            stopAll(baritone, JobResult.CANCELED); // combat and jobs are mutually exclusive
            debug("hardcoded: hunt " + mob);
            combat.hunt(baritone, mob);
            return true;
        }
        if (lower.equals("test") || lower.equals("test connection")) {
            testConnection();
            return true;
        }
        if (lower.startsWith("goto ")) {
            String[] parts = lower.substring(5).trim().split("\\s+");
            if (parts.length == 3) {
                try {
                    BlockPos pos = new BlockPos(
                            Integer.parseInt(parts[0]),
                            Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]));
                    startJob(baritone, new Jobs.Goto(pos));
                    return true;
                } catch (NumberFormatException ignored) {}
            }
            tell("goto needs 3 numbers: ai goto x y z");
            return true;
        }
        return false;
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
        String snapshot = ArionPerception.plannerSnapshot(baritone.getPlayerContext());
        Minecraft mc = Minecraft.getInstance();
        debug("brain request → " + config.reflexProvider.get() + "/" + config.reflexModel.get());

        java.util.concurrent.CompletableFuture<ArionAction> future = brain.decide(message, snapshot);
        pendingBrainFuture = future;
        // Always clear the pending flag on ANY completion (success / error / cancel) so
        // the gate can never wedge shut — this is what guarantees a slow/failed request
        // can't leave Arion refusing all further commands, and no retry ever fires.
        future.whenComplete((action, err) -> {
            pendingBrain = false;
            pendingBrainFuture = null;
            if (action == null) return; // cancelled — nothing to dispatch
            mc.execute(() -> {
                if (baritone.getPlayerContext().player() == null) return; // world unloaded
                dispatch(baritone, action);
            });
        });
    }

    /** Cancel any in-flight brain request and clear the pending flag. */
    private void cancelPendingBrain() {
        pendingBrain = false;
        if (pendingBrainFuture != null) {
            pendingBrainFuture.cancel(true);
            pendingBrainFuture = null;
        }
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
                if (vm != null && vm.hasBaritone() && baritone == vm.baritone()) {
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
        stopAll(baritone, JobResult.CANCELED); // interrupt whatever was running
        activeJob = job;
        tell("On it: " + job.label());
        debug("job started: " + job.label());
        jobStart(baritone, job);
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
        cancelPendingBrain();
        if (activeJob != null) {
            Job j = activeJob;
            activeJob = null;
            JobRunner.cancel(j, baritone);
            onJobFinished(j, result);
        }
        if (combat.isActive()) {
            combat.stop(baritone);
        }
        // the kill switch stops the vessel too (its Baritone pathing + any seek)
        var vm = arion.Arion.vessels();
        if (vm != null) {
            vm.commandStop();
        }
    }

    private void onJobFinished(Job job, JobResult result) {
        tell(job.label() + " → " + result);
        debug("job finished: " + job.label() + " → " + result);
        // feed the outcome back to the brain's memory so it knows what happened
        memory.assistantSaid("[job " + job.label() + " finished: " + result + "]");
    }

    // ------------------------------------------------------------ event pump

    @Override
    public void onTick(TickEvent event) {
        // IN = in-game (player + world non-null); OUT = main menu. Only act in-game.
        if (event.getType() != TickEvent.Type.IN) return;
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
                Job j = activeJob;
                activeJob = null;
                JobRunner.cancel(j, baritone);
                onJobFinished(j, JobResult.FAILED);
            }
            case CANCELED -> {
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

    /** Debug trace — chat + latest.log, only when debugMode is on. */
    private void debug(String message) {
        if (!config.debugMode.get()) return;
        arion.Arion.LOGGER.info("[Arion DEBUG] {}", message);
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                Helper.HELPER.logDirect(Component.literal("[Arion DEBUG] " + message));
            }
        });
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
