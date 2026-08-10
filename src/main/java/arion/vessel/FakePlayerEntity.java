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

    // ---- Baritone-driven movement input (filled by FakePlayerManager each tick) ----
    private boolean moveForward, moveBack, moveLeft, moveRight;
    private boolean wantsJump, wantsSneak, wantsSprint;

    // ---- simple seek fallback (used when no Baritone is bound) ----
    private double seekX, seekY, seekZ;
    private boolean seeking = false;
    private static final double SEEK_SPEED = 0.215;

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
