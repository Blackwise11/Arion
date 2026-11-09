/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.vessel;

import baritone.api.utils.IPlayerController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.UUID;

/**
 * A Baritone {@link IPlayerController} that bridges block interactions to the vessel's
 * <b>server-side</b> body — the real {@link arion.vessel.server.FakeServerPlayer}.
 *
 * <p>This replaces {@link NoopPlayerController} for the server-side vessel. The Noop controller
 * was correct for the old client-only ghost (which must never emit a packet), but the server-side
 * vessel is a genuine {@code ServerPlayer} the server accepts authoritative actions from. So when
 * Baritone's {@code MineProcess} / {@code BuilderProcess} decides to break or place a block, we
 * hop to the server thread and call {@code vessel.gameMode.destroyBlock(pos)} /
 * {@code useItemOn(...)} — server-validated, dropping loot and firing events, with <b>no packet
 * sent under the host player's account</b>. This is what finally lets {@code ai mine iron_ore}
 * actually mine: Baritone pathfinds to the ore (via the borrowed primary baritone + the client
 * shadow) and breaks it server-side through this controller.
 *
 * <p>Each interaction hops via {@code server.execute}. The boolean returns ({@code hasBrokenBlock},
 * {@code clickBlock}) reflect the outcome of the <i>last</i> server hop; Baritone polls
 * {@code hasBrokenBlock()} after {@code onPlayerDamageBlock} to know when to move on. Because the
 * hop is async, the result lags by a tick or two — Baritone simply re-issues the break next tick
 * until it reads true, which is the same loop it runs against a real player.
 *
 * <p><b>Break semantics (v1):</b> {@code ServerPlayerGameMode.destroyBlock} removes the block
 * outright (it fires {@code BlockEvent.BreakEvent}, cancellable, and drops loot as the vessel).
 * It does NOT model survival break-speed/time, so mining is effectively instant regardless of
 * tool. That's overpowered but correct enough for the "AI beats the game" goal; real
 * break-timing via {@code handleBlockBreakAction} is a later refinement.
 *
 * <p>Tool selection is not bridged: Baritone reads the shadow {@code LocalPlayer}'s inventory
 * (empty), not the vessel's server inventory, so it can't auto-equip a pickaxe. With instant
 * breaks the tool is irrelevant anyway. Inventory syncing (so Baritone can manage the vessel's
 * hotbar) is a later phase.
 */
public final class VesselServerPlayerController implements IPlayerController {

    private final MinecraftServer server;
    private final UUID vesselUuid;

    // outcome of the last server hop, read by hasBrokenBlock() on the next client tick
    private volatile boolean lastBreakOk = false;

    // Mirrored SWAP requests. Baritone reads the shadow inventory, which is only synced from the
    // body every 2 ticks. Without mirroring the swap on the shadow immediately, Baritone keeps
    // seeing the tool in the main inventory and re-requests the SWAP; each request swaps the body
    // back and forth (observed live: rapid tool oscillation). The timestamp throttles duplicates
    // while the body hop / shadow sync is in flight.
    private int lastSwapSlot = Integer.MIN_VALUE;
    private int lastSwapButton = Integer.MIN_VALUE;
    private long lastSwapMs;

    // Survival break state. ALL fields below are touched only inside server hops (server thread);
    // the client thread only submits start/continue/abort requests. Nothing is destroyed
    // instantly any more: progress comes from the body's real tool/attributes via
    // ServerPlayerGameMode.handleBlockBreakAction + gameMode.tick() (ServerPlayer.tick calls it).
    private BlockPos breakPos;
    private Direction breakFace = Direction.DOWN;
    private int breakTicks;
    private int breakSeq;
    private boolean breakActive;

    public VesselServerPlayerController(MinecraftServer server, UUID vesselUuid) {
        this.server = server;
        this.vesselUuid = vesselUuid;
    }

    private arion.vessel.server.FakeServerPlayer findVessel() {
        if (server == null) return null;
        arion.vessel.server.ServerVesselManager svm = arion.vessel.server.ServerVesselManager.peek(server);
        arion.vessel.server.FakeServerPlayer v = svm != null ? svm.vessel() : null;
        if (v != null && v.isAlive() && (vesselUuid == null || v.getUUID().equals(vesselUuid))) return v;
        return null;
    }

    /**
     * Resolve a <b>client</b>-side target UUID to the matching server-side entity. Never pass a
     * client {@code Entity} across the thread boundary — the two objects live in different worlds
     * (and touching a client entity from the server thread is a data race).
     *
     * <p>Fast path: the vessel's own level. Fallback: the other levels, in case the mob took a
     * portal between the client tick and this hop. Returns null if it no longer exists.
     */
    private static Entity resolveEntity(arion.vessel.server.FakeServerPlayer vessel, UUID uuid) {
        if (uuid == null || vessel == null) return null;
        Entity sameLevel = vessel.level().getEntity(uuid);
        if (sameLevel != null) return sameLevel;
        MinecraftServer s = vessel.level().getServer();
        if (s == null) return null;
        for (ServerLevel level : s.getAllLevels()) {
            if (level == vessel.level()) continue;
            Entity e = level.getEntity(uuid);
            if (e != null) return e;
        }
        return null;
    }

    @Override public void syncHeldItem() { /* vessel inventory is server-side; Baritone's view is read-only */ }

    @Override
    public boolean clickBlock(BlockPos pos, Direction side) {
        // Start a survival break: tell the body's game mode, which tracks progress over ticks
        // using the real tool/enchantments/attributes. Nothing is destroyed here.
        lastBreakOk = false;
        if (server == null) return false;
        server.execute(() -> {
            arion.vessel.server.FakeServerPlayer v = findVessel();
            if (v != null) {
                try { beginBreak(v, pos, side); }
                catch (Throwable t) { arion.Arion.LOGGER.warn("[Arion] vessel break start failed: {}", t.toString()); }
            }
        });
        return false;
    }

    @Override
    public boolean onPlayerDamageBlock(BlockPos pos, Direction side) {
        // Continue breaking. This is called every tick while Baritone holds the button; when the
        // accumulated vanilla progress reaches 1.0 we send STOP_DESTROY_BLOCK, which lets the
        // server destroy the block (loot, events, tool durability) at the correct time.
        if (server == null) return false;
        server.execute(() -> {
            arion.vessel.server.FakeServerPlayer v = findVessel();
            if (v != null) {
                try { continueBreak(v, pos, side); }
                catch (Throwable t) { arion.Arion.LOGGER.warn("[Arion] vessel break failed: {}", t.toString()); }
            }
        });
        return false;
    }

    /** Server thread: begin (or restart) a break at {@code pos}. */
    private void beginBreak(arion.vessel.server.FakeServerPlayer v, BlockPos pos, Direction face) {
        BlockState state = v.level().getBlockState(pos);
        if (state.isAir()) {
            breakActive = false;
            return;
        }
        // The game mode rejects out-of-range starts; don't spin on a block Baritone can't reach
        // (it will repath and call again).
        if (!v.isWithinBlockInteractionRange(pos, 1.0)) {
            breakActive = false;
            return;
        }
        breakPos = pos.immutable();
        breakFace = face;
        breakTicks = 0;
        breakActive = true;
        v.gameMode.handleBlockBreakAction(breakPos,
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                face, v.level().getMaxY(), ++breakSeq);
        v.swing(InteractionHand.MAIN_HAND);
    }

    /** Server thread: advance progress, and finish the break when vanilla's formula says so. */
    private void continueBreak(arion.vessel.server.FakeServerPlayer v, BlockPos pos, Direction face) {
        BlockState state = v.level().getBlockState(pos);
        if (state.isAir()) {
            if (pos.equals(breakPos)) { lastBreakOk = true; breakActive = false; }
            return;
        }
        if (!breakActive || !pos.equals(breakPos)) {
            beginBreak(v, pos, face);
            return;
        }
        breakTicks++;
        float perTick = state.getDestroyProgress(v, v.level(), pos);
        if (perTick * (breakTicks + 1) >= 1.0F) {
            v.gameMode.handleBlockBreakAction(pos,
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    breakFace, v.level().getMaxY(), ++breakSeq);
            // destroyAndAck is synchronous when the server agrees the block is finished; if it
            // opted for the delayed-destroy path, a later call observes air and reports it.
            if (v.level().getBlockState(pos).isAir()) {
                lastBreakOk = true;
                breakActive = false;
            }
        } else if (breakTicks % 4 == 0) {
            v.swing(InteractionHand.MAIN_HAND);   // arm animation for tracking clients
        }
    }

    @Override
    public void resetBlockRemoving() {
        if (server == null) return;
        server.execute(() -> {
            arion.vessel.server.FakeServerPlayer v = findVessel();
            if (v == null) return;
            try {
                if (breakActive && breakPos != null) {
                    v.gameMode.handleBlockBreakAction(breakPos,
                            ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                            breakFace, v.level().getMaxY(), ++breakSeq);
                }
            } catch (Throwable t) {
                arion.Arion.LOGGER.warn("[Arion] vessel break abort failed: {}", t.toString());
            } finally {
                breakActive = false;
                breakTicks = 0;
                breakPos = null;
            }
        });
    }

    @Override
    public boolean hasBrokenBlock() {
        boolean r = lastBreakOk;
        lastBreakOk = false;
        return r;
    }

    @Override
    public void windowClick(int windowId, int slotId, int mouseButton, ContainerInput type, Player player) {
        // Baritone's InventoryBehavior moves tools to the hotbar with ContainerInput.SWAP and
        // selects slots. Mirror the swap on the SHADOW immediately (Baritone's view), then apply
        // it to the body's own inventory menu server-side. Without the shadow mirror, Baritone
        // sees the pre-swap inventory for up to 2 ticks and re-issues the swap, which swaps the
        // body back (rapid tool oscillation).
        if (type == ContainerInput.SWAP) {
            long now = System.currentTimeMillis();
            if (slotId == lastSwapSlot && mouseButton == lastSwapButton && now - lastSwapMs < 500) {
                return; // duplicate while the body hop / shadow sync is still in flight
            }
            lastSwapSlot = slotId;
            lastSwapButton = mouseButton;
            lastSwapMs = now;
            try {
                if (player instanceof arion.vessel.FakePlayerEntity && player.inventoryMenu != null) {
                    player.inventoryMenu.clicked(slotId, mouseButton, type, player);
                }
            } catch (Throwable t) {
                arion.Arion.LOGGER.warn("[Arion] vessel shadow inventory swap failed: {}", t.toString());
            }
        }
        if (server == null) return;
        server.execute(() -> {
            arion.vessel.server.FakeServerPlayer v = findVessel();
            if (v == null) return;
            try {
                v.inventoryMenu.clicked(slotId, mouseButton, type, v);
                v.inventoryMenu.broadcastChanges();
            } catch (Throwable t) {
                arion.Arion.LOGGER.warn("[Arion] vessel inventory click failed: {}", t.toString());
            }
        });
    }

    @Override public GameType getGameType() { return GameType.SURVIVAL; }

    @Override
    public InteractionResult processRightClickBlock(LocalPlayer player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (server == null) return InteractionResult.PASS;
        server.execute(() -> {
            arion.vessel.server.FakeServerPlayer v = findVessel();
            if (v != null) {
                try {
                    v.gameMode.useItemOn(v, v.level(), v.getItemInHand(hand), hand, hit);
                } catch (Throwable t) { arion.Arion.LOGGER.warn("[Arion] vessel useItemOn failed: {}", t.toString()); }
            }
        });
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult processRightClick(LocalPlayer player, Level level, InteractionHand hand) {
        if (server == null) return InteractionResult.PASS;
        server.execute(() -> {
            arion.vessel.server.FakeServerPlayer v = findVessel();
            if (v != null) {
                try { v.gameMode.useItem(v, v.level(), v.getItemInHand(hand), hand); }
                catch (Throwable t) { arion.Arion.LOGGER.warn("[Arion] vessel useItem failed: {}", t.toString()); }
            }
        });
        return InteractionResult.PASS;
    }

    /**
     * Attack {@code target} through the vessel's own server-side body.
     *
     * <p><b>Why not a client call:</b> Baritone's {@code CombatProcess} reaches entities via
     * {@code ctx.playerController().attack(...)} on the <i>client</i> thread. The vanilla path
     * ({@code MultiPlayerGameMode.attack}) would send a {@code ServerboundAttackPacket} under the
     * host's account and hit whatever the host is looking at. Here we instead perform the exact
     * server-side work {@code ServerGamePacketListenerImpl.handleAttack} performs for a real
     * client: resolve the entity, {@code ServerPlayer.attack(...)} (damage, knockback, crits,
     * sweep, stats), then {@code swing(...)} (broadcasts the arm animation and resets the
     * attack-strength ticker). No packet ever leaves the host's connection.
     *
     * <p><b>Threading:</b> {@code player} is Baritone's shadow {@code LocalPlayer} — the one
     * {@code LookBehavior} just aimed. We read its yaw/pitch <i>now</i>, on the client thread,
     * then hop; reading it later inside the hop would race the next client tick. The target is
     * likewise reduced to its UUID before the hop and resolved back into a server entity there.
     *
     * <p><b>Swing order matters:</b> {@code swing()} resets {@code attackStrengthTicker}, which
     * {@code attack()} reads to scale damage — so attack first, swing second (the same effective
     * order as client attack + swing packet).
     */
    @Override
    public void attack(Player player, Entity target) {
        if (server == null || target == null) return;
        final UUID targetUuid = target.getUUID();
        final float aimYaw = player != null ? player.getYRot() : 0.0f;
        final float aimPitch = player != null ? player.getXRot() : 0.0f;
        server.execute(() -> {
            arion.vessel.server.FakeServerPlayer v = findVessel();
            if (v == null) return;
            if (v.getUUID().equals(targetUuid)) return;          // never punch ourselves
            Entity serverTarget = resolveEntity(v, targetUuid);
            if (serverTarget == null || !serverTarget.isAlive()) return;
            if (serverTarget.level() != v.level()) return;       // cross-dimension hits are never legitimate
            try {
                // Face where Baritone aimed the shadow, so the body's swing telegraphs the real
                // hit direction (and look-vector-derived knockback/particles look right).
                v.setYRot(aimYaw);
                v.setYHeadRot(aimYaw);
                v.setXRot(aimPitch);
                v.resetLastActionTime();
                v.attack(serverTarget);
                v.swing(InteractionHand.MAIN_HAND);
                // Vanilla resets the attack-charge ticker in handlePunch (the client's swing
                // packet), NOT inside Player.attack. Our bridge sends no packet, so without this
                // the body's ticker never resets: the mirrored shadow always reports a full
                // charge, Baritone's >= 0.9 gate always passes, and the vessel swings on every
                // cooldown tick ("spams attacks while holding a sword"). Reset AFTER attack() so
                // the strike itself is still scaled by the charge that was built up.
                v.resetAttackStrengthTicker();
            } catch (Throwable t) {
                arion.Arion.LOGGER.warn("[Arion] vessel attack failed: {}", t.toString());
            }
        });
    }

    /**
     * Release the vessel's in-use item (fires a drawn bow, drops a shield, finishes an eat).
     *
     * <p>Vanilla's server does this on {@code ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM}
     * by calling {@code ServerPlayer.releaseUsingItem()}; there is no client packet to receive here,
     * so we call it directly on the server thread. {@code stopUsingItem()} alone would <b>not</b>
     * complete the use — a bow would never loose its arrow — hence {@code releaseUsingItem}, the
     * same call Baritone's {@code CombatProcess.releaseUse()} expects.
     */
    @Override
    public void releaseUsingItem(Player player) {
        if (server == null) return;
        server.execute(() -> {
            arion.vessel.server.FakeServerPlayer v = findVessel();
            if (v == null) return;
            try { v.releaseUsingItem(); }
            catch (Throwable t) { arion.Arion.LOGGER.warn("[Arion] vessel releaseUsingItem failed: {}", t.toString()); }
        });
    }

    @Override public void setHittingBlock(boolean hitting) { /* no client-side break animation state to manage */ }

    @Override
    public double getBlockReachDistance() {
        // match the reach NoopPlayerController used so Baritone's "can I break this?" math is
        // unchanged. The server validates the actual reach.
        return 4.5;
    }

    /** Suppress the unused-import warning for Minecraft (kept for future client-side reads). */
    @SuppressWarnings("unused")
    private Minecraft mc() { return Minecraft.getInstance(); }
}
