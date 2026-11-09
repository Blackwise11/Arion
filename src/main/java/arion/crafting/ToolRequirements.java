/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.crafting;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

/**
 * Harvest-tier prerequisite checks for gather/mine steps.
 *
 * <p>The LLM planner sometimes orders `gather iron_ore` before `craft stone_pickaxe` (or omits
 * the tool entirely). A wooden pickaxe on iron ore drops nothing, so the body silently wastes
 * time. This helper answers the deterministic question: "which pickaxe, if any, must be crafted
 * before this step can succeed?"
 */
public final class ToolRequirements {

    private ToolRequirements() {}

    /**
     * @param blockId the block to mine (use {@code ProcessTracker.mineBlockName(step)})
     * @return the minimum pickaxe item that must be crafted, or null when the body can already
     *         harvest the block (or no tool is required, or the block id is unknown)
     */
    public static Item missingTool(Player player, String blockId) {
        BlockState state = blockState(blockId);
        if (player == null || state == null) return null;
        Item required = requiredTool(state);
        if (required == null) return null;
        return canHarvest(player, state) ? null : required;
    }

    /** Resolve a normalized block id to its default state, or null. */
    public static BlockState blockState(String blockId) {
        if (blockId == null || blockId.isBlank()) return null;
        String s = blockId.trim().toLowerCase(Locale.ROOT);
        if (!s.contains(":")) s = "minecraft:" + s;
        Identifier id = Identifier.tryParse(s);
        if (id == null) return null;
        Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        return block == null ? null : block.defaultBlockState();
    }

    /** The minimum pickaxe tier for the block, or null if it needs no pickaxe. */
    public static Item requiredTool(BlockState state) {
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) return Items.DIAMOND_PICKAXE;
        if (state.is(BlockTags.NEEDS_IRON_TOOL)) return Items.IRON_PICKAXE;
        if (state.is(BlockTags.NEEDS_STONE_TOOL)) return Items.STONE_PICKAXE;
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return Items.WOODEN_PICKAXE;
        return null;
    }

    /** True when any main-inventory tool can harvest the block with drops. */
    public static boolean canHarvest(Player player, BlockState state) {
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty() && stack.isCorrectToolForDrops(state)) return true;
        }
        return false;
    }
}
