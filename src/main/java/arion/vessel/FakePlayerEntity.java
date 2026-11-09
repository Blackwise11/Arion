/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.vessel;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.chat.ChatAbilities;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Arion's vessel — a fake {@link LocalPlayer} with a visible body that Baritone can
 * drive <b>without sending a single network packet</b>.
 *
 * Why {@code LocalPlayer} and not {@code RemotePlayer}: Baritone's
 * {@code IPlayerContext.player()} returns the concrete {@code LocalPlayer}, so only a
 * LocalPlayer vessel can be bound to its own Baritone instance.
 *
 * A LocalPlayer's tick path normally reads the keyboard and fires
 * {@code connection.send(...)} (position / input / sprint / sneak / elytra packets).
 * A fake player must never do that. This subclass therefore:
 * <ul>
 *   <li>overrides {@link #tick()} and {@link #aiStep()} to a <b>no-network physics
 *       step</b> built on the public {@code LivingEntity.travel(Vec3)} primitive
 *       (gravity + collision + fluid + jump), with no packets;</li>
 *   <li>takes its movement from a small input state that {@code FakePlayerManager}
 *       fills each tick from the vessel Baritone's {@code InputOverrideHandler}
 *       forced-inputs (move/jump/sneak/sprint).</li>
 * </ul>
 *
 * Baritone-binding safety is handled in {@code FakePlayerManager}: the vessel's
 * Baritone gets a vessel-bound {@code IPlayerContext} whose {@code playerController()}
 * is a <b>no-op</b>, so Baritone cannot emit break/place/attack packets through it —
 * and the binding is only enabled in single-player / on the integrated server unless
 * the user explicitly opts in. See {@code plan/FEATURE_FAKE_PLAYER.md}.
 */
public class FakePlayerEntity extends LocalPlayer {

    /** Negative ids, counting down, so we never collide with server-assigned ids. */
    private static final AtomicInteger NEXT_ID = new AtomicInteger(-1_000_000);

    /** Body-mirrored attack charge (see {@link #syncAttackStrength(int, float)}). */
    private float syncedAttackScale = 1.0f;
    private boolean hasSyncedAttackStrength;

    // ---- Baritone-driven movement input (filled by FakePlayerManager each tick) ----
    private boolean moveForward, moveBack, moveLeft, moveRight;
    private boolean wantsJump, wantsSneak, wantsSprint;

    // ---- simple seek fallback (used when no Baritone is bound) ----
    private double seekX, seekY, seekZ;
    private boolean seeking = false;
    private static final double SEEK_SPEED = 0.215;

    // ---- shadow mode (Phase C / M2): a position-feeder for the primary Baritone ----
    // When shadowMode is true this entity is NOT ticked physically. It exists only so Baritone's
    // IPlayerContext.player() returns a LocalPlayer whose position/rotation mirror the server-side
    // vessel — Baritone pathfinds from there. FakePlayerManager sets the position each tick via
    // syncFrom(); tick()/aiStep() are no-ops. The shadow is invisible and never added to the level.
    private boolean shadowMode = false;

    public FakePlayerEntity(Minecraft mc, ClientLevel level, ClientPacketListener connection,
                            GameProfile profile) {
        super(mc, level, connection,
                new StatsCounter(),
                new ClientRecipeBook(),
                new Input(false, false, false, false, false, false, false),
                false,
                ChatAbilities.NO_RESTRICTIONS);
        setId(NEXT_ID.getAndDecrement());
        // a private input holder so nothing reads the real keyboard for this player
        this.input = new net.minecraft.client.player.ClientInput();
    }

    /**
     * Switch this entity into a Baritone shadow: invisible, non-physical, position set externally.
     * The server-side vessel is the real body; this LocalPlayer only exists so Baritone (which
     * requires a LocalPlayer in its context) can read a position + world to pathfind against.
     */
    public void enableShadowMode() {
        this.shadowMode = true;
        this.seeking = false;
        this.setInvisible(true);
        this.noPhysics = true;
        // a unique, non-colliding UUID — the shadow is never added to the level, but set one so
        // nothing can mistake it for the real player or the vessel.
        try { this.setUUID(java.util.UUID.randomUUID()); } catch (Throwable ignored) {}
    }

    public boolean isShadow() { return shadowMode; }

    /**
     * Advance the vanilla attack-strength ticker that a shadow never gets.
     *
     * <p>A shadow is a pure position feeder: {@link #tick()} returns immediately in shadow mode, so
     * {@code Player.tick()} — which normally does {@code attackStrengthTicker++} every tick — never
     * runs and {@link #getAttackStrengthScale(float)} stays pinned near 0. Baritone's
     * {@code CombatProcess} refuses to melee while {@code getAttackStrengthScale(0.5f) < 0.9f}
     * (the {@code combatHumanizeAim} gate, on by default), so without this the borrowed Baritone
     * would walk the vessel up to a mob and never swing: the vessel-bound controller's
     * {@code attack(...)} would never be called.
     *
     * <p>Called once per client tick by {@code FakePlayerManager} (mirrors vanilla's increment).
     */
    public void tickAttackStrength() {
        this.attackStrengthTicker++;
    }

    /**
     * Mirror the server body's real attack charge. Called on the client thread every tick by
     * {@link arion.vessel.FakePlayerManager}.
     *
     * <p>Both values are authoritative for the body: {@code ticker} feeds anything that reads the
     * raw counter, and {@code scale} is what Baritone's {@code getAttackStrengthScale() >= 0.9}
     * gate actually consumes. Mirroring the scale matters because the shadow never ticks: its own
     * ATTACK_SPEED attribute modifiers (from the held sword) are never refreshed, so a locally
     * computed scale would reach 0.9 far too early and the vessel would spam swings.
     */
    public void syncAttackStrength(int ticker, float scale) {
        this.attackStrengthTicker = Math.max(0, ticker);
        this.syncedAttackScale = Math.max(0.0f, Math.min(1.0f, scale));
        this.hasSyncedAttackStrength = true;
    }

    /** Body-authoritative while the bridge is live; vanilla math as a fallback. */
    @Override
    public float getAttackStrengthScale(float adjustTicks) {
        return this.hasSyncedAttackStrength
                ? this.syncedAttackScale
                : super.getAttackStrengthScale(adjustTicks);
    }

    /**
     * Initial placement: position + facing. Used once at spawn, before Baritone drives the
     * shadow. Do <b>not</b> use this per tick — Baritone owns the shadow's rotation while it
     * drives (see the per-tick overload).
     */
    public void syncFrom(double x, double y, double z, float yRot) {
        // setPosRaw + setYRot to avoid triggering movement/collision logic on a non-physical entity
        this.setPosRaw(x, y, z);
        this.setYRot(yRot);
        this.setYHeadRot(yRot);
        this.xOld = x; this.yOld = y; this.zOld = z;   // suppress interp jitter
        this.setOnGround(true);
    }

    /**
     * Per-tick mirror from the server vessel's client-visible {@code RemotePlayer}.
     *
     * <p><b>Deliberately does not copy yaw/pitch.</b> Baritone aims the shadow through
     * {@code LookBehavior}/{@code MovementHelper} and Arion forwards {@code shadow.getYRot()} to
     * the server body. Copying the body's yaw back into the shadow every tick creates a feedback
     * loop — the body's old yaw overwrites Baritone's new aim before it is forwarded — so the
     * body only ever walks in its initial direction ("moves forward but never turns").
     *
     * <p>{@code onGround} and {@code deltaMovement} are mirrored because pathing reads them when
     * reconciling a start position; they do not make the shadow physical.
     */
    public void syncFrom(double x, double y, double z, boolean onGround, Vec3 deltaMovement) {
        this.setPosRaw(x, y, z);
        this.xOld = x; this.yOld = y; this.zOld = z;   // suppress interp jitter
        this.setOnGround(onGround);
        if (deltaMovement != null) this.setDeltaMovement(deltaMovement);
    }

    /** Update the movement inputs Baritone wants this tick. Called by FakePlayerManager. */
    public void setBaritoneInput(boolean forward, boolean back, boolean left, boolean right,
                                 boolean jump, boolean sneak, boolean sprint) {
        this.moveForward = forward;
        this.moveBack = back;
        this.moveLeft = left;
        this.moveRight = right;
        this.wantsJump = jump;
        this.wantsSneak = sneak;
        this.wantsSprint = sprint;
        // Only let Baritone override the seek fallback when it's actually driving — an
        // idle Baritone (path finished, all inputs released) must not cancel a seek.
        if (forward || back || left || right || jump) {
            this.seeking = false;
        }
    }

    /** Walk toward a point using the seek fallback (no Baritone required). */
    public void seekTo(double x, double y, double z) {
        this.seekX = x; this.seekY = y; this.seekZ = z;
        this.seeking = true;
    }

    /** Stop the seek fallback. */
    public void stopSeeking() {
        this.seeking = false;
        this.moveForward = this.moveBack = this.moveLeft = this.moveRight = false;
        this.wantsJump = this.wantsSneak = this.wantsSprint = false;
    }

    /**
     * No-network physics step. {@code LocalPlayer.tick()} would send a
     * {@code ServerboundPlayerInputPacket} and call {@code sendPosition()}; we skip all
     * of that and just advance our own physics.
     */
    @Override
    public void tick() {
        if (shadowMode) return; // shadow is a pure position-feeder; no physics, no network
        // baseTick() = Entity-level tick (riding, portal, fire, position history) — no
        // network. We deliberately do NOT call LocalPlayer.tick()/AbstractClientPlayer.tick().
        this.baseTick();
        this.aiStep();
    }

    /**
     * No-network movement step. {@code LocalPlayer.aiStep()} would send sprint/sneak/
     * elytra packets and poll the keyboard. Instead we translate the Baritone-driven
     * input into the standard movement-impulse fields ({@code xxa}/{@code zza}) and the
     * {@code jumping} flag, then run the public {@code LivingEntity.travel(Vec3)}
     * physics — exactly the shared movement core, minus the network.
     */
    @Override
    public void aiStep() {
        // --- if seeking (no Baritone), derive movement input from the seek target ---
        if (seeking) {
            double dx = seekX - getX();
            double dz = seekZ - getZ();
            double horizSq = dx * dx + dz * dz;
            if (horizSq < 0.04) {
                stopSeeking();
            } else {
                float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
                setYRot(yaw);
                moveForward = true;
                wantsJump = horizontalCollision && seekY > getY() + 0.5;
            }
        }

        // --- translate input into movement impulses ----------------------------
        float strafe = (moveLeft ? 1f : 0f) - (moveRight ? 1f : 0f);
        float forward = (moveForward ? 1f : 0f) - (moveBack ? 1f : 0f);
        float scale = wantsSneak ? 0.3f : 1.0f; // sneak slows ground movement
        this.xxa = strafe * scale;
        this.zza = forward * scale;
        this.yya = 0.0f;
        this.jumping = wantsJump;
        this.setSprinting(wantsSprint && forward > 0f);
        this.setShiftKeyDown(wantsSneak);

        // --- physics (public, self-contained, no packets) ----------------------
        this.travel(new Vec3(this.xxa, this.yya, this.zza));

        // keep the body/head rotation sane so the model doesn't spin
        this.yHeadRot = this.getYRot();
        this.yBodyRot = this.getYRot();
    }

    /** Vessels are never the camera entity; no first-person/hand rendering for them. */
    @Override
    public boolean isControlledCamera() {
        return false;
    }
}
