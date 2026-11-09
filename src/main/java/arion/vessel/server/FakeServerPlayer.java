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
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Arion's vessel body — a real, server-side {@link ServerPlayer} the server fully tracks.
 *
 * Because it is a genuine {@code ServerPlayer} registered via {@code PlayerList.placeNewPlayer},
 * everything the client-only vessel could never do "just works": it renders to all clients, mob AI
 * targets it, skin/name resolve from the {@link GameProfile}, and blocks/damage are server-validated.
 * This is the Carpet-mod approach ({@code EntityPlayerMPFake}) ported to NeoForge.
 *
 * The vessel has no real network connection — {@link ServerVesselManager} pairs it with a
 * {@link FakeConnection}, so it never sends or receives a packet. It ticks as part of the server's
 * normal player tick loop.
 */
public final class FakeServerPlayer extends ServerPlayer {

    // --- movement intent (Phase B / M1: straight-line seek) ---
    // Set in tick() BEFORE doTick() (the seek computation), consumed by applyInput() which
    // doTick()→aiStep() calls just before travel(). Volatile because commandWalkTo/stopWalking
    // arrive on the server thread via ServerVesselManager (same thread as tick(), but marked for
    // clarity / future channel hops).
    private volatile double walkX, walkY, walkZ;
    private volatile boolean walking = false;
    private boolean wantsJump;

    /** Order the vessel to walk straight toward a world position. Server thread. */
    public void walkTo(double x, double y, double z) { this.walkX = x; this.walkY = y; this.walkZ = z; this.walking = true; }
    /** Stop any in-progress walk. Server thread. */
    public void stopWalking() { this.walking = false; this.wantsJump = false; }
    /** True while the vessel has an active walk target. */
    public boolean isWalking() { return walking || baritoneDriving; }

    /**
     * Start eating the first edible hotbar item, the way a player would: select the slot and
     * begin a real server-side item use. Vanilla's use tick (inside {@link #doTick()} →
     * {@code aiStep}) finishes the eat, so hunger/saturation change through the normal food
     * pipeline rather than a direct {@code FoodData} write.
     *
     * <p>Server thread only.
     *
     * @return true when a use was actually started
     */
    public boolean tryEatFromHotbar() {
        if (!this.isAlive() || this.isUsingItem()) return false;
        if (this.getFoodData().getFoodLevel() >= 20) return false;
        for (int i = 0; i < 9; i++) {
            net.minecraft.world.item.ItemStack stack = this.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            net.minecraft.world.food.FoodProperties food =
                    stack.get(net.minecraft.core.component.DataComponents.FOOD);
            if (food == null || food.nutrition() <= 0) continue;
            this.getInventory().setSelectedSlot(i);
            this.gameMode.useItem(this, this.level(), stack, net.minecraft.world.InteractionHand.MAIN_HAND);
            return this.isUsingItem();
        }
        return false;
    }

    /** True while the vessel is actively consuming food. */
    public boolean isEating() {
        return this.isUsingItem() && !this.getUseItem().isEmpty()
                && this.getUseItem().get(net.minecraft.core.component.DataComponents.FOOD) != null;
    }

    // --- M4 auto-shield (offhand) ---
    //
    // Server-authoritative: the vessel body owns its own projectile reflex. It runs on the SERVER
    // thread at the top of tick(), one tick before doTick() ticks the use — the same "re-assert
    // every tick" shape Baritone's CombatProcess uses (raiseShield -> processRightClick + hold the
    // use key). A client-side scan cannot be the only trigger: the server sees a projectile the
    // instant it is fired, while the client only sees it once the entity tracker broadcasts it,
    // and server.execute() adds another tick of latency before the body could react.

    /** Shared predictor — stateless apart from locals, so one instance per body is fine. */
    private final arion.combat.ThreatEngine threatEngine = new arion.combat.ThreatEngine();

    private volatile boolean shieldEnabled = true;   // mirrored from config by FakePlayerManager
    private volatile double shieldRange = 48.0;
    private volatile int shieldHoldLimit = 10;

    private int shieldHoldLeft;              // ticks to keep the block up after the last threat
    private int shieldForceTicks;            // 'ai shield test' manual hold window
    private boolean shieldWanted;            // the reflex wants the shield raised
    private volatile boolean shieldBlocked;  // published snapshot for the client status readout
    private boolean shieldNoItemWarned;
    /**
     * Aim override: while non-null the body faces this point after the Baritone frame/walk
     * rotation, which is what makes the vanilla damage-source angle check block a projectile
     * coming from the side/back. Server thread.
     */
    private Vec3 shieldLook;

    /** Mirror the client config (autoShield / shieldRange / shieldHoldTicks) onto the body. */
    public void configureShield(boolean enabled, double range, int holdTicks) {
        this.shieldEnabled = enabled;
        this.shieldRange = range;
        this.shieldHoldLimit = holdTicks;
    }

    /**
     * Hold the offhand shield for {@code ticks} regardless of projectiles — the 'ai shield test'
     * probe. Logs the authoritative use/block state when the window closes. Server thread.
     */
    public void forceShield(int ticks) {
        this.shieldForceTicks = Math.max(1, ticks);
        Vec3 look = this.shieldLook != null
                ? this.shieldLook
                : this.getEyePosition().add(this.getLookAngle().scale(8.0));
        this.startShield(look);
    }

    /** True while the body was actually blocking on its last tick (client-readable snapshot). */
    public boolean isShielding() {
        return this.shieldBlocked;
    }

    /**
     * Authoritative attack-charge ticker. The client shadow mirrors this every tick so Baritone's
     * {@code getAttackStrengthScale()} gate sees the body's real cooldown. A swing resets the
     * <b>body's</b> ticker ({@code VesselServerPlayerController.attack} → {@code attack()} +
     * {@code swing()}), while the shadow is never ticked — so a hand-incremented shadow counter
     * stays pinned at full charge and every subsequent strike lands at partial damage (and the
     * hit-and-run spacing branch never triggers).
     */
    public int attackStrengthTickerValue() {
        return this.attackStrengthTicker;
    }

    /**
     * One reflex step. Called at the top of {@link #tick()} (server thread) so the rotation it
     * installs is applied before {@code doTick()} ticks the use this tick.
     */
    private void tickShieldReflex() {
        if (!this.shieldEnabled) {
            if (this.shieldWanted) this.stopShield();
            return;
        }
        if (this.shieldForceTicks > 0) {
            if (--this.shieldForceTicks == 0) {
                arion.ArionLog.debug("vessel shield test: using=" + this.isUsingItem()
                        + " blocking=" + this.isBlocking() + " offhand=" + this.getOffhandItem());
            }
            this.startShield(this.shieldLook);
            return;
        }
        java.util.List<net.minecraft.world.entity.Entity> nearby = this.level().getEntitiesOfClass(
                net.minecraft.world.entity.Entity.class,
                this.getBoundingBox().inflate(this.shieldRange),
                e -> e instanceof net.minecraft.world.entity.projectile.Projectile);
        arion.combat.ThreatEngine.Threat threat =
                this.threatEngine.scan(this, nearby, this.shieldRange);

        if (threat != null && threat.impactTicks() <= 20) {
            if (!this.shieldWanted) {
                arion.ArionLog.debug("vessel shield raised: " + threat.label());
            }
            this.shieldHoldLeft = Math.max(1, this.shieldHoldLimit);
            this.startShield(threat.projectile().position());
        } else if (this.shieldWanted) {
            if (--this.shieldHoldLeft <= 0) {
                arion.ArionLog.debug("vessel shield lowered");
                this.stopShield();
            } else {
                // Re-assert while the threat is gone but the hold window is open: vanilla drops
                // the use if anything (a container, an attack, a release packet) stops it.
                this.startShield(this.shieldLook);
            }
        }
    }

    /**
     * Start blocking with the offhand shield and face {@code look}. If the body is eating/using a
     * non-shield item, that use is cancelled so the shield gets priority. Server thread.
     */
    public void startShield(Vec3 look) {
        this.shieldLook = look;
        if (this.isUsingItem() && !this.getUseItem().is(Items.SHIELD)) {
            this.stopUsingItem();
        }
        ItemStack off = this.getOffhandItem();
        if (!off.is(Items.SHIELD)) {
            this.shieldWanted = false;
            if (!this.shieldNoItemWarned) {
                this.shieldNoItemWarned = true;
                arion.ArionLog.debug("vessel shield: no shield in the offhand (" + off + ")");
            }
            return;
        }
        this.shieldWanted = true;
        if (!this.isUsingItem()) {
            // Vanilla server path: Item.use -> startUsingItem + the living-entity use flags.
            this.gameMode.useItem(this, this.level(), off, InteractionHand.OFF_HAND);
            if (!this.isUsingItem()) {
                // The fake connection's game mode declined (spectator/cooldown/result shape).
                // Start the use directly — this is exactly what the client does on right-click.
                this.startUsingItem(InteractionHand.OFF_HAND);
            }
            this.shieldNoItemWarned = false;
            arion.ArionLog.debug("vessel shield: use started (using=" + this.isUsingItem()
                    + " offhand=" + off.getItem() + ")");
        }
    }

    /** Lower the shield and clear the aim override. Server thread. */
    public void stopShield() {
        this.shieldLook = null;
        this.shieldWanted = false;
        this.shieldHoldLeft = 0;
        this.shieldBlocked = false;
        this.shieldNoItemWarned = false;
        if (this.isUsingItem() && this.getUseItem().is(Items.SHIELD)) {
            this.stopUsingItem();
        }
    }

    // --- Baritone input frame (Phase C / M2: the vessel borrows the primary Baritone) ---
    // Baritone is client-side and drives a client LocalPlayer via InputOverrideHandler forced
    // inputs. We forward those inputs here as a frame; applyInput() turns them into the same
    // xxa/zza/jumping fields the seek path uses, so the server vessel's physics (travel()) move
    // it the way Baritone expects. Volatile: written by FakePlayerManager on the CLIENT thread,
    // read here on the SERVER thread (the vessel ticks via PlayerList.tickAll). The yaw Baritone
    // aimed the shadow at is forwarded too — the vessel's forward impulse points along it.
    private volatile boolean baritoneDriving = false;
    private volatile boolean hasBaritoneFrame = false;
    private volatile boolean bForward, bBack, bLeft, bRight, bJump, bSneak, bSprint;
    private volatile float bYaw, bPitch;
    private volatile int bSelectedSlot;

    /**
     * Push a Baritone input frame from the client thread. Called every client tick while the
     * vessel's Baritone is borrowed. Any movement input (forward/back/left/right/jump) marks the
     * frame "driving", which takes precedence over the straight-line seek in {@link #applyInput}.
     *
     * <p>{@code hasBaritoneFrame} is set even when every input is false: Baritone still aims the
     * shadow while it is merely turning or paused, and the body must face that aim. Without it,
     * the body only turned on ticks that also had a movement key (which made it walk in a fixed
     * direction). {@code pitch} is applied to the body as well so mining/aiming looks right.
     */
    public void setBaritoneInputFrame(float yaw, float pitch, int selectedSlot,
                                      boolean forward, boolean back, boolean left, boolean right,
                                      boolean jump, boolean sneak, boolean sprint) {
        this.bYaw = yaw;
        this.bPitch = pitch;
        this.bSelectedSlot = selectedSlot;
        this.bForward = forward; this.bBack = back; this.bLeft = left; this.bRight = right;
        this.bJump = jump; this.bSneak = sneak; this.bSprint = sprint;
        this.hasBaritoneFrame = true;
        this.baritoneDriving = forward || back || left || right || jump;
        if (this.baritoneDriving) {
            // Baritone is actively pathing — cancel any leftover straight-line seek so the two
            // don't fight for the same movement fields.
            this.walking = false;
        }
    }

    /** Release the Baritone frame (vessel idles / falls back to seek). Client thread. */
    public void clearBaritoneInput() {
        this.baritoneDriving = false;
        this.hasBaritoneFrame = false;
        this.bForward = this.bBack = this.bLeft = this.bRight = this.bJump = false;
    }

    /**
     * Create the vessel named {@code name} in {@code level}. The UUID is an offline-style UUID
     * derived from the name (stable across restarts, never collides with a real account). The vessel
     * renders with the default (Steve/Alex) skin for now — real skin resolution via the server's
     * {@code ProfileResolver} is a later polish step (the modern async API differs across versions).
     */
    public FakeServerPlayer(MinecraftServer server, ServerLevel level, String name) {
        super(server, level, profileFor(name), ClientInformation.createDefault());
    }

    /**
     * Respawn on death instead of lying dead forever. A fake player has no client to click the
     * "Respawn" button, so without this the dead body would linger: {@code LivingEntity.tickDeath}
     * removes the <i>entity</i> after 20 ticks but never drops it from the {@code PlayerList}, and a
     * later {@code ai spawn} (same name → same offline UUID) would collide / stack a second body.
     *
     * <p>{@code super.die} plays the death message + drops loot as normal. We then defer a clean
     * respawn to the next server tick via {@link ServerVesselManager#respawnVessel()}. Deferring is
     * required: {@code die()} runs inside the damage/tick chain, and respawning mutates the player
     * list ({@code PlayerList.remove} + {@code placeNewPlayer}) — doing that inline risks a
     * {@code ConcurrentModificationException}. {@code server.execute} runs the respawn on the server
     * thread before the next entity-tick pass, safely outside the iteration.
     *
     * <p>{@code respawnVessel} guards on {@code !isAlive()}, so if a NeoForge death event cancelled
     * the death (restoring health), the deferred call is a no-op.
     */
    /**
     * Survival damage + knockback for the vessel.
     *
     * <p>The body is a real {@code ServerPlayer}, so {@code super.hurtServer} already applies
     * vanilla damage and {@code LivingEntity.knockback}. Two vessel-specific details:
     * <ul>
     *   <li>Physics is driven manually ({@link #tick()} calls {@code doTick()}), so the knockback
     *       delta is normally consumed by {@code travel()}. If something clears it before the
     *       movement tick (measured as an unchanged delta), the body would "take damage but not
     *       recoil" — apply the vanilla impulse explicitly as a fallback.</li>
     *   <li>A debug trace of the resulting delta makes that visible in {@code latest.log}.</li>
     * </ul>
     */
    /**
     * The vessel is a survival body — never immune to damage.
     *
     * <p>26.1.2's {@code isInvulnerableToBase} reads the private {@code Entity.invulnerable} field
     * directly and {@code LivingEntity.isInvulnerableTo} additionally ORs in
     * {@code EnchantmentHelper.isImmuneToDamage} (equipment enchantments). Either can survive a
     * death/respawn cycle — clearing them on revive and every tick was not enough in practice, and
     * a single true here silently drops every player hit (no damage, no knockback). Overriding the
     * whole predicate is the body contract: a survival vessel is damageable, period. Death/removal
     * are still enforced by {@code isDeadOrDying()} in {@code hurtServer}.
     */
    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
        return false;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        Vec3 before = this.getDeltaMovement();
        boolean hurt = super.hurtServer(level, source, damage);
        if (!hurt && this.tickCount - lastRejectLogTick > 40) {
            lastRejectLogTick = this.tickCount;
            var atk = source.getEntity();
            arion.Arion.LOGGER.info("[Arion] vessel damage rejected: invulnTime={} invulnerable={} "
                            + "abilities={} dead={} removed={} spectator={} invulnerableTo={} "
                            + "health={}/{} lastHurt={} held={} armor={} attacker={} dist={}",
                    this.invulnerableTime, this.isInvulnerable(), this.getAbilities().invulnerable,
                    this.dead, this.isRemoved(), this.isSpectator(),
                    this.isInvulnerableTo(level, source),
                    this.getHealth(), this.getMaxHealth(), this.lastHurt,
                    this.getMainHandItem().isEmpty() ? "empty" : this.getMainHandItem().getHoverName().getString(),
                    this.getArmorValue(),
                    atk == null ? "none" : atk.getName().getString(),
                    atk == null ? -1.0 : Math.sqrt(atk.distanceToSqr(this)));
        }
        if (hurt) {
            Vec3 after = this.getDeltaMovement();
            boolean impulseApplied = after.distanceToSqr(before) > 1.0E-6;
            var attacker = source.getEntity();
            if (!impulseApplied && attacker != null && !source.is(DamageTypeTags.NO_KNOCKBACK)) {
                this.knockback(0.4,
                        attacker.getX() - this.getX(),
                        attacker.getZ() - this.getZ());
            }
            // Make sure the tracker broadcasts the velocity impulse even if it already ran its
            // send pass this tick (ServerEntity clears the flag when it sends).
            this.needsSync = true;
            // Short position burst so the short knockback window reaches tracking clients even
            // if the tracker's delta throttle would otherwise skip it.
            forceClientSync(5);
            if (attacker instanceof net.minecraft.world.entity.player.Player) {
                // Measure what the physics actually did next tick: moved 0 with hColl=true is a
                // wall/ceiling (a zombie would be blocked identically); moved 0 with hColl=false
                // means the body's travel() is not running at all.
                this.probeX = this.getX();
                this.probeY = this.getY();
                this.probeZ = this.getZ();
                this.knockbackProbeTicks = 2;
            }
            arion.ArionLog.debug(String.format(java.util.Locale.ROOT,
                    "vessel hurt: dmg=%.1f by=%s delta=[%.3f, %.3f, %.3f]",
                    damage, attacker == null ? "environment" : attacker.getName().getString(),
                    getDeltaMovement().x, getDeltaMovement().y, getDeltaMovement().z));
        }
        return hurt;
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        MinecraftServer s = this.level().getServer();
        if (s != null) {
            s.execute(() -> ServerVesselManager.get(s).respawnVessel());
        }
    }

    /**
     * Revive this vessel in place after death — a real respawn, not a remove+re-register. Called
     * from {@link ServerVesselManager#respawnVessel} on the next server tick (before
     * {@code tickDeath} reaches 20, so the entity is still client-tracked).
     *
     * <p><b>Why every invulnerability channel is cleared:</b> a lethal blow leaves
     * {@code invulnerableTime}, {@code hurtTime}/{@code hurtDuration} and possibly
     * {@code abilities.invulnerable} set; flipping {@code dead} back to false and restoring health
     * is not enough. A revived player that keeps any of those is untouchable — the "vessel goes
     * invincible after respawn" bug (and, because a hit that does nothing applies no vanilla
     * knockback, it also looked like "no knockback from the player").
     */
    public void reviveInPlace(ServerLevel level, Vec3 pos) {
        this.dead = false;                 // protected in LivingEntity — via inheritance
        this.deathTime = 0;                // public; stops the client death-tilt + the 20-tick remove
        this.hurtTime = 0;
        this.hurtDuration = 0;
        this.invulnerableTime = 0;         // public on Entity — clears temporary invulnerability
        this.setInvulnerable(false);
        this.setHealth(this.getMaxHealth());
        this.getFoodData().setFoodLevel(20);
        this.getFoodData().setSaturation(5.0F);
        this.removeAllEffects();
        this.clearFire();
        this.setRemainingFireTicks(0);
        this.setDeltaMovement(Vec3.ZERO);
        this.resetFallDistance();
        this.stopUsingItem();
        this.setShiftKeyDown(false);
        // Death can leave the body in a non-survival mode (or creative abilities). Re-assert the
        // body contract: survival, damageable, not flying.
        try {
            this.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        } catch (Throwable ignored) {}
        this.getAbilities().invulnerable = false;
        this.getAbilities().flying = false;
        this.onUpdateAbilities();
        // teleport to the respawn point. snapTo sets position; setOldPosAndRot avoids a "moved
        // too quickly" flagged move from the death position. Then force replication so tracking
        // clients see the vessel at the new spot, alive, this tick.
        this.snapTo(pos.x, pos.y, pos.z, 0.0f, 0.0f);
        this.setOldPosAndRot();
        try {
            this.connection.resetPosition();
            level.getChunkSource().move(this);
            // A respawn is a large jump; the entity tracker may throttle it as a delta (or keep
            // sending deltas from its old base), leaving the host's client rendering the body at
            // the death spot while the server body is at the respawn point — attacks at that ghost
            // are rejected by the server's range check, which looks exactly like invincibility.
            // Send the authoritative pose now and keep re-sending it for a second so the client
            // cannot be dragged back.
            level.getChunkSource().sendToTrackingPlayersAndSelf(this,
                    ClientboundEntityPositionSyncPacket.of(this));
            forceClientSync(20);
        } catch (Throwable t) {
            arion.Arion.LOGGER.warn("[Arion] vessel revive replication failed: {}", t.toString());
        }
    }

    /**
     * Drive the vessel's physics + replication ourselves (Phase A). This is the Carpet-mod approach.
     *
     * <p><b>Why we must tick physics manually:</b> the server ticks each player's
     * {@code ServerGamePacketListenerImpl} via {@code ServerConnectionListener.tick()}, which iterates
     * the server's <i>connection list</i>. The vessel's {@link FakeConnection} was never added to
     * that list (it bypassed the real channel handshake), so its listener's {@code tick()} never
     * runs — meaning {@code tickPlayer() → doTick()} (the physics driver: gravity, knockback,
     * movement) never runs. The vessel would float with {@code dy=0} forever, taking damage but
     * never falling.
     *
     * <p>Carpet's {@code EntityPlayerMPFake.tick()} solves this by calling {@code doTick()} itself.
     * {@code super.tick()} ({@code ServerPlayer.tick}: game mode, load-timeout, container) runs for
     * bookkeeping; {@code doTick()} ({@code ServerPlayer.doTick → Player.tick → LivingEntity.tick →
     * aiStep → travel}) applies the physics the listener would have. Because the listener isn't
     * ticking the vessel, there's no double-tick.
     *
     * <p><b>Replication:</b> a {@code ServerPlayer}'s position changes aren't broadcast to tracking
     * clients the way mob movement is (the server normally trusts the player's own client). Every 10
     * ticks we force {@code resetPosition()} + {@code getChunkSource().move(this)} so the host (and
     * any other clients) see the vessel fall / get knocked back. We also replicate EVERY tick while
     * the vessel has meaningful velocity — knockback is a brief impulse that decays within a few
     * ticks, so the 10-tick cadence would miss it and the vessel would look like it never flinched.
     */
    /**
     * Drive the vessel's forward impulse ourselves (Phase B / M1).
     *
     * <p><b>Why an override is required:</b> the only {@code applyInput()} that runs for a
     * {@code ServerPlayer} is {@code LivingEntity.applyInput()}, which merely decays {@code xxa}/
     * {@code zza} by 0.98 and never writes them. So without this override no forward impulse ever
     * reaches {@code travel()} and the vessel can't walk — exactly the Phase A symptom. We supply
     * a fixed forward impulse ({@code zza=1}) each tick while walking; the yaw aimed by the seek
     * computation in {@link #tick()} turns that into the right direction.
     *
     * <p><b>Do NOT call {@code super.applyInput()}</b> — it only decays. {@code Player.aiStep()}
     * already calls {@code setSpeed(MOVEMENT_SPEED)} each tick, so we supply direction only.
     * Runs inside {@code doTick()→aiStep()}, immediately before {@code travel()}.
     */
    @Override
    protected void applyInput() {
        // Baritone frame takes precedence — it's the AI actually driving (pathfinding, mining,
        // bridging). Translate the forwarded inputs into the same impulse fields travel() reads.
        if (baritoneDriving) {
            float strafe = (bLeft ? 1f : 0f) - (bRight ? 1f : 0f);
            float forward = (bForward ? 1f : 0f) - (bBack ? 1f : 0f);
            float scale = bSneak ? 0.3f : 1.0f;
            this.xxa = strafe * scale;
            this.zza = forward * scale;
            this.yya = 0.0f;
            this.jumping = bJump;
            this.setSprinting(bSprint && forward > 0f);
            this.setShiftKeyDown(bSneak);
            return;
        }
        if (!walking) {
            this.xxa = 0;
            this.zza = 0;
            this.jumping = false;
            return;
        }
        this.xxa = 0;          // no strafe; straight-line seek
        this.zza = 1f;         // forward — yaw (set in tick()) aims it
        this.jumping = wantsJump;
    }

    @Override
    public void tick() {
        // Vessel body contract: always a damageable survival entity.
        // `Entity.isInvulnerableToBase` reads the private `invulnerable` field DIRECTLY (not
        // isInvulnerable()), so clearing it once in reviveInPlace is not enough if the death /
        // respawn pipeline (vanilla or a NeoForge patch) sets it again afterwards. Enforce it
        // every tick; the vessel is never legitimately invulnerable. NOTE: `invulnerableTime`
        // is the hurt cooldown in this version — do NOT clear it per tick, only on revive.
        this.setInvulnerable(false);
        // M4: server-side projectile reflex. Runs before the frame/walk aim and before doTick(),
        // so the rotation it installs sticks and the use it starts ticks this same tick.
        this.tickShieldReflex();
        if (baritoneDriving || hasBaritoneFrame) {
            // Baritone aimed the shadow at this yaw; the vessel's forward impulse (zza) points
            // along it. Apply it even when no movement key is held (hasBaritoneFrame): Baritone
            // still turns the shadow while paused or between segments, and the body must follow
            // that aim or it ends up walking in one fixed direction.
            setYRot(bYaw);
            setYHeadRot(bYaw);
            setXRot(bPitch);
            // Baritone selects the tool/hotbar slot on the shadow; mirror it onto the body so the
            // break/place path uses the same item Baritone reasoned about.
            int slot = bSelectedSlot;
            if (slot >= 0 && slot < 9) this.getInventory().setSelectedSlot(slot);
        } else if (walking) {
            // Seek computation: aim at the walk target BEFORE doTick() (which runs aiStep()→
            // applyInput()→travel()). Arrive → clear intent. Straight-line only; walls it can't
            // auto-jump will stall it — that's expected and is the motivation for the Baritone
            // pathfinding bridge (Stage 2 / M2).
            double dx = walkX - getX();
            double dz = walkZ - getZ();
            if (dx * dx + dz * dz < 0.04) {        // arrival radius ~0.2 blocks (horizontal)
                stopWalking();
            } else {
                setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
                // crude auto-jump: bumped something and the target is above us → hop
                wantsJump = horizontalCollision && walkY > getY() + 0.5;
            }
        }

        // M4 auto-shield: face the projectile while blocking. Applied after the Baritone frame /
        // walk aim so the shield's damage-source angle check sees this rotation.
        if (this.shieldLook != null) {
            Vec3 eye = this.getEyePosition();
            Vec3 dir = this.shieldLook.subtract(eye);
            if (dir.lengthSqr() > 1.0E-6) {
                float yaw = (float) (Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90.0);
                float pitch = (float) (-Math.toDegrees(Math.atan2(dir.y, Math.hypot(dir.x, dir.z))));
                this.setYRot(yaw);
                this.setXRot(pitch);
                this.yHeadRot = yaw;
                this.yBodyRot = yaw;
            }
        }

        super.tick();
        // Physics: drive doTick() ourselves since the listener (which normally calls it) is never
        // ticked for our network-inert vessel. Wrap defensively — doTick touches a lot of state.
        try {
            this.doTick();
        } catch (Throwable t) {
            arion.Arion.LOGGER.warn("[Arion] vessel doTick failed: {}", t.toString());
        }
        // Publish the authoritative block state for the client-side status readout ('ai shield').
        this.shieldBlocked = this.isBlocking();
        boolean moving = this.getDeltaMovement().horizontalDistanceSqr() > 1.0E-6
                || Math.abs(this.getDeltaMovement().y) > 0.001;
        if (this.tickCount % 10 == 0 || moving) {
            try {
                this.connection.resetPosition();
                this.level().getChunkSource().move(this);
            } catch (Throwable t) {
                arion.Arion.LOGGER.warn("[Arion] vessel move-sync failed: {}", t.toString());
            }
        }
        // Authoritative pose for the client shadow — published AFTER physics so the shadow paths
        // from where the body actually is this tick (see Pose).
        Vec3 tail = this.getDeltaMovement();
        this.pose = new Pose(this.getX(), this.getY(), this.getZ(),
                tail.x, tail.y, tail.z, this.getYRot(), this.getXRot(),
                this.onGround(), this.tickCount);
        // Post-teleport burst: keep clients pinned to the authoritative pose for a short window.
        if (this.strongSyncTicks > 0) {
            this.strongSyncTicks--;
            try {
                this.level().getChunkSource().sendToTrackingPlayersAndSelf(this,
                        ClientboundEntityPositionSyncPacket.of(this));
            } catch (Throwable ignored) {}
        }
        // Knockback probe (2 ticks after a player hit): what did move() actually do?
        if (this.knockbackProbeTicks > 0 && --this.knockbackProbeTicks == 0) {
            arion.ArionLog.debug(String.format(java.util.Locale.ROOT,
                    "vessel knockback move: d=[%.3f, %.3f, %.3f] hColl=%s vColl=%s onGround=%s "
                            + "delta=[%.3f, %.3f, %.3f]",
                    this.getX() - probeX, this.getY() - probeY, this.getZ() - probeZ,
                    this.horizontalCollision, this.verticalCollision, this.onGround(),
                    this.getDeltaMovement().x, this.getDeltaMovement().y, this.getDeltaMovement().z));
        }
    }

    /**
     * Authoritative pose snapshot for the client-side shadow.
     *
     * <p>The shadow must path from the body's <b>real</b> position. The client-visible
     * {@code RemotePlayer} is a poor source: the server broadcasts player positions on a
     * throttle and the client interpolates, so at walking speed the shadow trails the body by
     * roughly a block. Baritone then computes its aim/next node from a phantom position,
     * overshoots the node, turns back, and the body visibly circles before settling on the path.
     *
     * <p>Written once per server tick at the end of {@link #tick()} (server thread) and read from
     * the client thread. One immutable record so position/velocity/rotation always belong to the
     * same tick (no torn reads).
     */
    public record Pose(double x, double y, double z,
                       double vx, double vy, double vz,
                       float yaw, float pitch, boolean onGround, int tick) {}

    private volatile Pose pose = new Pose(0, 0, 0, 0, 0, 0, 0, 0, true, -1);

    /** Latest authoritative pose. Server thread writes, any thread reads. */
    public Pose pose() { return pose; }

    /**
     * Ticks remaining of a forced client-side position sync burst. A respawn/teleport is a large
     * jump; the entity tracker may keep sending deltas based on its old position base, which can
     * drag the client back toward where the body died. Re-sending the authoritative pose for a
     * short window makes the client converge immediately (and keeps it there).
     */
    private int strongSyncTicks = 0;

    // throttled log for rejected damage (diagnoses immunity vs. out-of-range attacks)
    private int lastRejectLogTick = -100;

    // knockback probe: after a player hit, measure how far the body's move() actually got and
    // whether a collision blocked it (a zombie in the same spot would be blocked identically).
    private int knockbackProbeTicks = 0;
    private double probeX, probeY, probeZ;
    private boolean probeCollided;

    /** Ask {@link #tick()} to broadcast the full pose for the next {@code ticks} ticks. */
    public void forceClientSync(int ticks) {
        this.strongSyncTicks = Math.max(this.strongSyncTicks, ticks);
    }

    private static GameProfile profileFor(String name) {
        String clean = (name == null || name.isBlank()) ? "Arion" : name.trim();
        if (clean.length() > 16) clean = clean.substring(0, 16);
        return new GameProfile(UUIDUtil.createOfflinePlayerUUID(clean), clean);
    }
}
