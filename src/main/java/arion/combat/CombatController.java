/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.combat;

import arion.config.ArionConfig;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * The skilled-PvE fighter. Cheat-style under the hood (direct attack calls,
 * reach buff, instant aim) but presented smoothly so it looks human. Hunts any
 * mob. Runs a per-tick state machine on the client thread — never the LLM.
 *
 * States: IDLE → ACQUIRE → (SEARCH) → APPROACH → STRIKE → RECOVER → (RETREAT).
 * SEARCH keeps the hunt alive when no target is in range (wander + re-scan +
 * timeout) instead of giving up on the first empty scan.
 */
public final class CombatController {

    private enum State { IDLE, ACQUIRE, SEARCH, APPROACH, STRIKE, RECOVER, RETREAT }

    private static final double SCAN_RANGE = 32.0;
    private static final double PURSUE_RANGE = 64.0;

    // --- threat detection (client-side; see isHostile — NOT getTarget()-based) ---
    private static final double MELEE_THREAT = 2.5;    // a hostile this close is about to swing
    private static final double REACTION_RANGE = 8.0;  // notice closing threats within this
    private static final double SWAP_MARGIN = 0.5;     // hysteresis: don't jitter between near-equidistant mobs
    private static final int SWARM_KITE_THRESHOLD = 2; // ≥2 hostiles in melee → kite, don't stand and trade

    private final ArionConfig config;

    private State state = State.IDLE;
    private HuntFilter filter;
    private LivingEntity target;
    private MobTactic tactic;
    private int cooldown = 0;
    private int strikeJitter = 0;
    private int strafeDir = 1;
    private int strafeTicks = 0;
    private BlockPos lastGoalPos = null; // avoid re-issuing the same path every tick

    // search / hunt bookkeeping
    private long searchStartTick = -1;
    private long huntStartTick = -1;
    private long tickCount = 0;
    private BlockPos searchAnchor = null;
    private int searchRepathTicks = 0;

    // reach buff bookkeeping so we always restore
    private double originalReach = Double.NaN;

    /** Fired once when a hunt ends (completion, give-up, cancel). */
    private Consumer<String> huntListener;
    /** Fired on state transitions / target events when debug mode is on. */
    private Consumer<String> debugListener;

    /**
     * When true, this fighter drives the fake-player vessel and is <b>packet-safe</b>:
     * the direct attack call and the reach attribute buff are suppressed. The vessel
     * fights by pathing up to the mob and body-blocking it, which only registers in
     * single-player / on the integrated server (where the vessel's Baritone is allowed
     * anyway). See plan/FEATURE_FAKE_PLAYER.md §4.
     */
    private boolean vesselMode = false;

    public CombatController(ArionConfig config) {
        this.config = config;
    }

    /** Mark this fighter as vessel-bound (packet-safe: no attack packets / reach buff). */
    public void setVesselMode(boolean vessel) {
        this.vesselMode = vessel;
    }

    public boolean isVesselMode() { return vesselMode; }

    public boolean isActive() { return state != State.IDLE; }

    /** Register a listener notified (with a short reason) when the hunt ends. */
    public void setHuntListener(Consumer<String> listener) {
        this.huntListener = listener;
    }

    /** Register a listener for debug traces (state transitions, target found/lost). */
    public void setDebugListener(Consumer<String> listener) {
        this.debugListener = listener;
    }

    private void debug(String message) {
        Consumer<String> l = debugListener;
        if (l != null) {
            try { l.accept(message); } catch (Throwable ignored) {}
        }
    }

    private void setState(State next) {
        if (next != state) {
            debug("state " + state + " → " + next);
            state = next;
        }
    }

    /** Start hunting mobs matching the argument ("zombie", "nearest", "all"). */
    public void hunt(IBaritone baritone, String arg) {
        Optional<HuntFilter> f = HuntFilter.parse(arg);
        if (f.isEmpty()) {
            // parse failure — don't leave a stale hunt/reach running
            if (isActive()) {
                stop(baritone, "hunt cancelled");
            }
            tell("Unknown mob: " + arg + "  (try a name like zombie, or 'nearest' / 'all')");
            return;
        }
        this.filter = f.get();
        this.huntStartTick = tickCount;
        this.searchAnchor = null;
        this.setState(State.ACQUIRE);
        tell("Hunting " + filter.describe() + "."
                + (vesselMode ? " (vessel — body-blocking, no attack packets)" : ""));
    }

    /** Hard stop: cancel targeting, restore attributes, stop pathing. */
    public void stop(IBaritone baritone) {
        stop(baritone, null);
    }

    private void stop(IBaritone baritone, String reason) {
        boolean wasActive = isActive();
        setState(State.IDLE);
        target = null;
        lastGoalPos = null;
        searchAnchor = null;
        restoreReach(baritone.getPlayerContext());
        baritone.getPathingBehavior().cancelEverything();
        if (wasActive) {
            fireHuntEnded(reason != null ? reason : "stopped");
        }
    }

    /** Clear all state on world unload / disconnect. Always restores reach. */
    public void reset(IPlayerContext ctx) {
        boolean wasActive = isActive();
        setState(State.IDLE);
        target = null;
        filter = null;
        lastGoalPos = null;
        searchAnchor = null;
        restoreReach(ctx);
        // a reset mid-hunt used to end silently — fire the listener so the brain learns
        // the hunt stopped. "world unloaded" is NOT a re-ask trigger (see ArionController).
        if (wasActive) {
            fireHuntEnded("world unloaded");
        }
    }

    // ------------------------------------------------------------- main loop

    public void tick(IBaritone baritone, IPlayerContext ctx) {
        tickCount++;
        if (state == State.IDLE) return;
        Player player = ctx.player();
        if (player == null || ctx.world() == null) { reset(ctx); return; }

        // ---- safety guardrails (reflex, never LLM) ----
        if (player.getHealth() < config.combatRetreatHealth.get().floatValue()) {
            enterRetreat(baritone, ctx);
            return;
        }
        int attackers = countNearbyHostiles(ctx, REACTION_RANGE);
        debug("attackers=" + attackers);
        if (attackers > config.combatMaxAttackers.get()) {
            enterRetreat(baritone, ctx);
            return;
        }

        // ---- defensive reflex: re-target the nearest hostile threatening us ----
        // The main loop locks onto one target and then ignores everything else, so a
        // second mob walking up to swing would land free hits while we keep whaling on
        // the first. Every tick, re-evaluate the nearest HOSTILE within reaction range
        // (any hostile, not just hunt-filter prey — a skeleton shooting you while you
        // hunt zombies is a threat). Swap to it when it's in our face (about to swing)
        // while our locked target isn't, or when it's meaningfully closer (hysteresis
        // so we don't flicker between two near-equidistant mobs).
        //
        // NOTE: this is NOT getTarget()-based. Mob.target isn't synced to the client
        // (vanilla), so mob.getTarget() is null on the client — the old nearestThreat /
        // countAttackers / isAttackingMe were dead code. We detect threats by hostile
        // type + proximity instead. See isHostile().
        //
        // The reflex ONLY re-targets (sets target/tactic/lastGoalPos); it does not force
        // setState(STRIKE). The state handlers act on the new target naturally: doApproach
        // self-flips to STRIKE when the new target is in reach; doStrike/doRecover keep
        // swinging/kiting it. Forcing STRIKE here fought the swarm kite in RECOVER.
        LivingEntity threat = nearestHostile(ctx, REACTION_RANGE);
        if (threat != null && threat != target) {
            double threatDist = threat.distanceToSqr(player);
            boolean threatInMelee = threatDist <= MELEE_THREAT * MELEE_THREAT;
            boolean currentInMelee = target != null
                    && target.distanceToSqr(player) <= MELEE_THREAT * MELEE_THREAT;
            boolean threatCloser = target == null
                    || threatDist < target.distanceToSqr(player) - SWAP_MARGIN * SWAP_MARGIN;
            if ((threatInMelee && !currentInMelee) || threatCloser) {
                target = threat;
                tactic = MobTactic.forEntity(threat);
                lastGoalPos = null;
                equipBestWeapon(ctx);
                applyReachBuff(ctx);
                debug("threat! " + threat.getName().getString()
                        + " — re-targeted (melee=" + threatInMelee + ", state=" + state + ")");
                // break out of SEARCH/ACQUIRE so we engage the threat this tick; in
                // APPROACH/STRIKE/RECOVER the handler already acts on the new target.
                if (threatInMelee && (state == State.SEARCH || state == State.ACQUIRE)) {
                    setState(State.APPROACH);
                }
            }
        }

        // ---- hunt duration failsafe ----
        int maxDur = config.combatMaxDurationSeconds.get();
        if (maxDur > 0 && huntStartTick >= 0 && (tickCount - huntStartTick) > maxDur * 20L) {
            debug("hunt timed out");
            tell("Hunt timed out after " + maxDur + "s.");
            stop(baritone, "hunt timed out");
            return;
        }

        if (cooldown > 0) cooldown--;

        switch (state) {
            case ACQUIRE -> doAcquire(baritone, ctx);
            case SEARCH -> doSearch(baritone, ctx);
            case APPROACH -> doApproach(baritone, ctx);
            case STRIKE -> doStrike(baritone, ctx);
            case RECOVER -> doRecover(baritone, ctx);
            case RETREAT -> doRetreat(baritone, ctx);
            default -> {}
        }
    }

    // ---------------------------------------------------------------- states

    private void doAcquire(IBaritone baritone, IPlayerContext ctx) {
        LivingEntity found = pickTarget(ctx);
        if (found == null) {
            // keep hunting: wander + re-scan instead of giving up on the first empty scan
            enterSearch(baritone, ctx);
            return;
        }
        target = found;
        tactic = MobTactic.forEntity(found);
        lastGoalPos = null; // reset so the first approach always issues a goal
        searchAnchor = null;
        equipBestWeapon(ctx);
        applyReachBuff(ctx);
        debug("target acquired: " + found.getName().getString()
                + " hp=" + (int) Math.ceil(found.getHealth()));
        setState(State.APPROACH);
    }

    /** Wander + re-scan. Transitions to APPROACH on target found, gives up on timeout. */
    private void doSearch(IBaritone baritone, IPlayerContext ctx) {
        Player player = ctx.player();

        // re-scan every tick for a target entering range
        LivingEntity found = pickTarget(ctx);
        if (found != null) {
            target = found;
            tactic = MobTactic.forEntity(found);
            lastGoalPos = null;
            equipBestWeapon(ctx);
            applyReachBuff(ctx);
            tell("Spotted " + filter.describe() + " — moving in.");
            setState(State.APPROACH);
            return;
        }

        // give up after the search timeout
        int searchTimeout = config.combatSearchTimeoutSeconds.get();
        if (searchStartTick >= 0 && (tickCount - searchStartTick) > searchTimeout * 20L) {
            debug("no target found — ending hunt");
            tell("Couldn't find any " + filter.describe() + " nearby — giving up the hunt.");
            stop(baritone, "no target found");
            return;
        }

        // wander: pick a roam point near the anchor, re-path every few seconds
        if (searchAnchor == null) {
            searchAnchor = player.blockPosition().immutable();
        }
        if (--searchRepathTicks <= 0 || lastGoalPos == null) {
            searchRepathTicks = 60; // re-roam every ~3s
            double ang = jitter(0.0, Math.PI * 2);
            double dist = jitter(8.0, 20.0);
            BlockPos roam = searchAnchor.offset(
                    (int) (Math.cos(ang) * dist), 0, (int) (Math.sin(ang) * dist));
            lastGoalPos = roam;
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(roam, 2));
        }
    }

    private void enterSearch(IBaritone baritone, IPlayerContext ctx) {
        searchStartTick = tickCount;
        searchAnchor = null;
        searchRepathTicks = 0;
        setState(State.SEARCH);
    }

    private void doApproach(IBaritone baritone, IPlayerContext ctx) {
        if (!validTarget(ctx)) { onTargetLost(baritone, ctx); return; }
        Player player = ctx.player();
        double dist = player.distanceTo(target);

        // aim at it while closing
        aim(ctx, target);

        if (dist <= tactic.engageRange()) {
            setState(State.STRIKE);
            strikeJitter = jitter(0, 2);
            return;
        }

        // only re-issue the goal when the mob moved significantly — otherwise we
        // reset Baritone's path every tick and it never takes a step
        BlockPos mobPos = target.blockPosition();
        if (lastGoalPos == null || mobPos.distSqr(lastGoalPos) > 4) {
            lastGoalPos = mobPos;
            baritone.getCustomGoalProcess().setGoalAndPath(
                    new GoalNear(mobPos, (int) Math.max(1, tactic.engageRange() - 1)));
        }
    }

    private void doStrike(IBaritone baritone, IPlayerContext ctx) {
        if (!validTarget(ctx)) { onTargetLost(baritone, ctx); return; }
        Player player = ctx.player();
        double dist = player.distanceTo(target);

        if (dist > tactic.engageRange()) { setState(State.APPROACH); return; }

        aim(ctx, target);

        if (strikeJitter > 0) { strikeJitter--; return; } // human-looking timing jitter
        if (cooldown > 0) return;

        if (vesselMode) {
            // PACKET-SAFE: never attack. The vessel just presses against the mob
            // (body-block + swing animation) so it looks busy without the server ever
            // seeing a hit from a body it doesn't track. Stay in RECOVER to re-approach.
            player.swing(InteractionHand.MAIN_HAND);
            cooldown = 10;
            setState(State.RECOVER);
            return;
        }

        // respect attack cooldown when humanize is on (lower DPS, looks fair)
        if (config.combatHumanizeAim.get() && player.getAttackStrengthScale(0.5f) < 0.9f) {
            return;
        }

        // the cheat core: direct attack call + swing animation
        Minecraft.getInstance().gameMode.attack(player, target);
        player.swing(InteractionHand.MAIN_HAND);
        cooldown = (int) Math.max(4, player.getCurrentItemAttackStrengthDelay() / 2);
        setState(State.RECOVER);
    }

    private void doRecover(IBaritone baritone, IPlayerContext ctx) {
        if (!validTarget(ctx)) { onTargetLost(baritone, ctx); return; }

        // creeper: back off during swell
        if (tactic.retreatOnWindup() && target instanceof net.minecraft.world.entity.monster.Creeper c
                && c.getSwellDir() > 0) {
            backOff(baritone, ctx, tactic.dangerRange());
            return;
        }

        // ranged mobs: strafe between their shots so arrows whiff
        if (tactic.ranged() && config.combatHumanizeAim.get()) {
            strafe(baritone, ctx);
        }

        // SWARM KITE: ≥2 hostiles in melee → back off the group centroid to create
        // space, so we don't stand and take simultaneous hits from multiple mobs.
        // Lighter than RETREAT: we keep the target and resume striking when cooldown
        // resets (hit → back-off → re-approach → hit cycle). Full RETREAT stays for
        // low-HP / overwhelming numbers (the guardrails at the top of tick()).
        if (countNearbyHostiles(ctx, MELEE_THREAT) >= SWARM_KITE_THRESHOLD) {
            backOffFromCentroid(baritone, ctx, 4.0);
            return;   // let the back-off step play; re-strike when cooldown resets
        }

        if (cooldown <= 0) {
            setState(State.STRIKE);
            strikeJitter = jitter(0, 2);
        }
    }

    private void doRetreat(IBaritone baritone, IPlayerContext ctx) {
        // run away from the threat; resume hunting once healthy and clear
        Player player = ctx.player();
        if (player.getHealth() >= config.combatRetreatHealth.get().floatValue() + 6
                && countNearbyHostiles(ctx, REACTION_RANGE) <= config.combatMaxAttackers.get()) {
            tell("Healthy again — re-engaging.");
            setState(State.ACQUIRE);
            return;
        }
        if (target != null) {
            backOff(baritone, ctx, 12.0);
        }
    }

    /** Target died / despawned / walked out of range — re-acquire or search. */
    private void onTargetLost(IBaritone baritone, IPlayerContext ctx) {
        if (target != null && !target.isAlive()) {
            tell("Target down.");
        }
        target = null;
        lastGoalPos = null;
        setState(State.ACQUIRE); // re-scan; falls into SEARCH if nothing else is near
    }

    // ------------------------------------------------------------- behaviors

    private void aim(IPlayerContext ctx, LivingEntity e) {
        Player player = ctx.player();
        if (player == null) return;
        // aim at hitbox center with a small random offset (not pixel-locked)
        double ox = jitter(-0.1, 0.1);
        double oy = jitter(-0.1, 0.1);
        Vec3 eye = player.getEyePosition();
        Vec3 point = e.getEyePosition().add(ox, oy - 0.2, 0);
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));

        if (config.combatHumanizeAim.get()) {
            // eased: turn at most ~40°/tick so flicks look human; snap when point-blank
            double dist = player.distanceTo(e);
            float maxStep = dist < 3.0 ? 180f : 40f;
            player.setYRot(approachAngle(player.getYRot(), yaw, maxStep));
            player.setXRot(approachAngle(player.getXRot(), pitch, maxStep));
        } else {
            player.setYRot(yaw);
            player.setXRot(pitch);
        }
    }

    private void backOff(IBaritone baritone, IPlayerContext ctx, double distance) {
        if (target == null) return;
        Player player = ctx.player();
        Vec3 away = player.position().subtract(target.position()).normalize().scale(distance);
        BlockPos retreatPos = BlockPos.containing(target.position().add(away));
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(retreatPos, 1));
    }

    /**
     * Strafe around a ranged attacker: move perpendicular to the line to the target,
     * hold the direction for K ticks, then flip. Baritone paths each strafe step, so
     * the arrows whiff while we close. See combat_module.md §4.4.
     */
    private void strafe(IBaritone baritone, IPlayerContext ctx) {
        Player player = ctx.player();
        if (player == null || target == null) return;

        // flip direction every K ticks
        if (strafeTicks <= 0) {
            strafeDir = -strafeDir;
            strafeTicks = jitter(8, 16);
        }
        strafeTicks--;

        // perpendicular to the target→player line, scaled by the tactic's strafe reach
        Vec3 toPlayer = player.position().subtract(target.position());
        Vec3 flat = new Vec3(toPlayer.x, 0, toPlayer.z);
        if (flat.lengthSqr() < 1.0e-4) return;              // standing on the mob; nothing to strafe
        Vec3 perp = new Vec3(-flat.z, 0, flat.x).normalize().scale(strafeDir * tactic.strafeSpeed() * 4.0);
        BlockPos strafePos = BlockPos.containing(player.position().add(perp));

        // keep facing the mob while sidestepping, then let Baritone walk the step
        aim(ctx, target);
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(strafePos, 1));
    }

    // --------------------------------------------------------------- helpers

    private LivingEntity pickTarget(IPlayerContext ctx) {
        Player player = ctx.player();
        return ctx.entitiesStream()
                .filter(e -> e instanceof LivingEntity && e.isAlive() && e != player)
                // a vessel never chases a real player — it has no way to hurt them and
                // body-blocking the account owner would just be annoying
                .filter(e -> !(vesselMode && e instanceof Player))
                .filter(e -> filter.predicate().test(e))
                .filter(e -> e.distanceToSqr(player) <= SCAN_RANGE * SCAN_RANGE)
                .map(e -> (LivingEntity) e)
                // priority: hostile first, then lowest HP, then nearest. (The old
                // isAttackingMe key was dead — Mob.target isn't synced to the client.)
                .min(Comparator
                        .comparingInt((LivingEntity e) -> isHostile(e) ? 0 : 1)
                        .thenComparingDouble(LivingEntity::getHealth)
                        .thenComparingDouble(e -> e.distanceToSqr(player)))
                .orElse(null);
    }

    /**
     * Client-side hostile check. NOT getTarget()-based: {@code Mob.target} is set
     * server-side by mob AI and is never synced to the client, so
     * {@code mob.getTarget()} is {@code null} on the client. Detecting threats by
     * hostile type + proximity (done in {@link #nearestHostile} /
     * {@link #countNearbyHostiles}) is the reliable client-side signal.
     *
     * <p>{@code Monster} covers Zombie/Husk/Drowned, Skeleton variants, Creeper,
     * Spider, EnderMan, Witch, Blaze, etc. {@code Slime} and {@code Phantom} are
     * hostile but extend {@code Mob} directly (not {@code Monster}), so they're
     * listed explicitly. Known limitation: a <i>neutral</i> EnderMan within melee
     * range is treated as a threat (and will be hit, provoking it) — acceptable for
     * now; an enderman &lt;2.5 blocks away is usually already aggro'd.
     */
    private static boolean isHostile(Entity e) {
        return e instanceof net.minecraft.world.entity.monster.Monster
                || e instanceof net.minecraft.world.entity.monster.Slime
                || e instanceof net.minecraft.world.entity.monster.Phantom;
    }

    /** The nearest hostile within {@code range} of the player, or null. */
    private LivingEntity nearestHostile(IPlayerContext ctx, double range) {
        Player player = ctx.player();
        if (player == null) return null;
        double rs = range * range;
        return ctx.entitiesStream()
                .filter(e -> e instanceof LivingEntity && e.isAlive() && e != player)
                .filter(e -> !(vesselMode && e instanceof Player))
                .filter(CombatController::isHostile)
                .filter(e -> e.distanceToSqr(player) <= rs)
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(player)))
                .map(e -> (LivingEntity) e)
                .orElse(null);
    }

    /** Count hostiles within {@code range} of the player. Replaces the dead countAttackers. */
    private int countNearbyHostiles(IPlayerContext ctx, double range) {
        Player player = ctx.player();
        if (player == null) return 0;
        double rs = range * range;
        return (int) ctx.entitiesStream()
                .filter(e -> e instanceof LivingEntity && e.isAlive() && e != player)
                .filter(e -> !(vesselMode && e instanceof Player))
                .filter(CombatController::isHostile)
                .filter(e -> e.distanceToSqr(player) <= rs)
                .count();
    }

    /**
     * Back away from the centroid of all nearby hostiles (multi-mob spacing), not
     * just {@code target}. Used by the swarm kite in {@link #doRecover}: when ≥2
     * hostiles are in melee, retreating from the group centroid opens space from
     * the whole pack rather than one mob. Falls back to single-target
     * {@link #backOff} if only one hostile is near.
     */
    private void backOffFromCentroid(IBaritone baritone, IPlayerContext ctx, double distance) {
        Player player = ctx.player();
        if (player == null) return;
        double sumX = 0, sumY = 0, sumZ = 0, n = 0;
        double rs = REACTION_RANGE * REACTION_RANGE;
        for (Entity e : ctx.entitiesStream().toList()) {
            if (!(e instanceof LivingEntity) || !e.isAlive() || e == player) continue;
            if (vesselMode && e instanceof Player) continue;
            if (!isHostile(e)) continue;
            if (e.distanceToSqr(player) > rs) continue;
            sumX += e.getX(); sumY += e.getY(); sumZ += e.getZ(); n++;
        }
        if (n == 0) {
            if (target != null) backOff(baritone, ctx, distance);
            return;
        }
        Vec3 centroid = new Vec3(sumX / n, sumY / n, sumZ / n);
        Vec3 away = player.position().subtract(centroid);
        if (away.lengthSqr() < 1.0e-4) return; // standing on the centroid; no direction to pick
        BlockPos retreatPos = BlockPos.containing(player.position().add(away.normalize().scale(distance)));
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(retreatPos, 1));
    }

    /** Alive, not removed, still in the world, and within pursue range. */
    private boolean validTarget(IPlayerContext ctx) {
        if (target == null || !target.isAlive() || target.isRemoved()) return false;
        Player player = ctx.player();
        if (player == null) return false;
        // walked out of the pursue bubble or into an unloaded chunk → drop it
        return target.distanceToSqr(player) <= PURSUE_RANGE * PURSUE_RANGE;
    }

    private void equipBestWeapon(IPlayerContext ctx) {
        Player player = ctx.player();
        if (player == null) return;
        int best = -1;
        double bestDmg = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            // prefer swords/axes by attack damage attribute; fallback to any tool
            var attrs = s.get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS);
            double d = 0;
            if (attrs != null) {
                for (var mod : attrs.modifiers()) {
                    if (mod.attribute().is(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE.unwrapKey().get())) {
                        d += mod.modifier().amount();
                    }
                }
            }
            if (d > bestDmg) { bestDmg = d; best = i; }
        }
        if (best >= 0) {
            player.getInventory().setSelectedSlot(best);
        }
    }

    private void applyReachBuff(IPlayerContext ctx) {
        if (vesselMode) return; // no attack packets → reach is irrelevant; don't touch attributes
        Player player = ctx.player();
        if (player == null) return;
        // don't clobber the saved base if the buff is already applied
        if (!Double.isNaN(originalReach)) return;
        var attr = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ENTITY_INTERACTION_RANGE);
        if (attr != null) {
            originalReach = attr.getBaseValue();
            attr.setBaseValue(config.combatReach.get());
        }
    }

    private void restoreReach(IPlayerContext ctx) {
        Player player = ctx.player();
        if (player == null || Double.isNaN(originalReach)) return;
        var attr = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ENTITY_INTERACTION_RANGE);
        if (attr != null) {
            attr.setBaseValue(originalReach);
        }
        originalReach = Double.NaN;
    }

    private void enterRetreat(IBaritone baritone, IPlayerContext ctx) {
        if (state != State.RETREAT) {
            tell("Backing off — too dangerous.");
            target = null; // don't snap back to the mob that almost killed us
            setState(State.RETREAT);
        }
        backOff(baritone, ctx, 12.0);
    }

    private void fireHuntEnded(String reason) {
        Consumer<String> l = huntListener;
        if (l != null) {
            try { l.accept(reason); } catch (Throwable ignored) {}
        }
    }

    private static int jitter(int lo, int hi) {
        return ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }

    private static double jitter(double lo, double hi) {
        return ThreadLocalRandom.current().nextDouble(lo, hi);
    }

    /** Turn `from` toward `to` by at most `maxStep` degrees (shortest way around). */
    private static float approachAngle(float from, float to, float maxStep) {
        float diff = ((to - from + 540f) % 360f) - 180f;
        if (Math.abs(diff) <= maxStep) return to;
        return from + Math.copySign(maxStep, diff);
    }

    private void tell(String message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                Helper.HELPER.logDirect(Component.literal("[Arion] " + message));
            }
        });
    }
}
