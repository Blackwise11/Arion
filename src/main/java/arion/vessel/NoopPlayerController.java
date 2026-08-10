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
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A no-op {@link IPlayerController} for the vessel's Baritone.
 *
 * Baritone's pathing/mining/building processes reach the network through
 * {@code ctx.playerController()} (break block, place block, right-click, window
 * click). The real implementation is backed by {@code Minecraft.gameMode}, which
 * sends packets under the player's account. For a client-only vessel that must never
 * emit such packets, so every interaction here is stubbed to a safe no-op:
 * block-break reports "not broken", right-clicks return PASS, and movement reach is
 * left at a sane value so pathfinding math still works.
 *
 * This is the core of the vessel's packet-safety: even if a Baritone process decides
 * to break or place a block, nothing reaches the server.
 */
public final class NoopPlayerController implements IPlayerController {

    public static final NoopPlayerController INSTANCE = new NoopPlayerController();

    private NoopPlayerController() {}

    @Override
    public void syncHeldItem() { /* no-op */ }

    @Override
    public boolean hasBrokenBlock() { return false; }

    @Override
    public boolean onPlayerDamageBlock(BlockPos pos, Direction side) { return false; }

    @Override
    public void resetBlockRemoving() { /* no-op */ }

    @Override
    public void windowClick(int windowId, int slotId, int mouseButton, ContainerInput type, Player player) { /* no-op */ }

    @Override
    public GameType getGameType() { return GameType.SURVIVAL; }

    @Override
    public InteractionResult processRightClickBlock(LocalPlayer player, Level level, InteractionHand hand, BlockHitResult hit) {
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult processRightClick(LocalPlayer player, Level level, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    @Override
    public boolean clickBlock(BlockPos pos, Direction side) { return false; }

    @Override
    public void setHittingBlock(boolean hitting) { /* no-op */ }

    @Override
    public double getBlockReachDistance() { return 4.5; }
}
