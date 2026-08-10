/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.survival;

import arion.config.ArionConfig;
import baritone.api.IBaritone;
import baritone.api.utils.IPlayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import baritone.api.utils.input.Input;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;

/**
 * Keeps the bot alive without involving the LLM: reflex-level eating and
 * death/respawn bookkeeping. Death positions are recorded so the bot can walk
 * back and recover drops, which are time-sensitive (they despawn).
 */
public final class SurvivalManager {

    private final ArionConfig config;

    private BlockPos lastDeathPos;
    private boolean wasDead = false;
    private int eatCooldownTicks = 0;

    public SurvivalManager(ArionConfig config) {
        this.config = config;
    }

    /**
     * Advance survival reflexes one tick.
     *
     * @param onRespawn callback fired once when the player respawns after dying
     */
    public void tick(IBaritone baritone, IPlayerContext ctx, Consumer<IBaritone> onRespawn) {
        Player player = ctx.player();
        if (player == null) return;

        // --- death / respawn bookkeeping ---
        boolean dead = player.isDeadOrDying() || player.getHealth() <= 0;
        if (dead && !wasDead) {
            // just died: record where, so we can come back for drops
            lastDeathPos = player.blockPosition().immutable();
            wasDead = true;
        } else if (!dead && wasDead) {
            // just respawned
            wasDead = false;
            if (onRespawn != null) {
                onRespawn.accept(baritone);
            }
        }

        // --- eating reflex ---
        if (eatCooldownTicks > 0) {
            eatCooldownTicks--;
        } else if (player.getFoodData().getFoodLevel() < config.eatBelowFood.get().floatValue()) {
            tryEat(baritone, ctx);
        }
    }

    /** Swap to a food item and eat it, then swap back. A pure reflex, no LLM. */
    private void tryEat(IBaritone baritone, IPlayerContext ctx) {
        Player player = ctx.player();
        int foodSlot = findFoodSlot(player);
        if (foodSlot < 0) return; // nothing edible

        int prevSlot = player.getInventory().getSelectedSlot();
        player.getInventory().setSelectedSlot(foodSlot);
        // hold right-click to eat; Baritone's input handler does this cleanly
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);

        // eating takes ~32 ticks; set a cooldown so we don't spam slot swaps
        eatCooldownTicks = 40;

        // schedule the swap-back + release on the next ticks via a tiny delayed task
        scheduleRestore(baritone, ctx, prevSlot, 36);
    }

    private void scheduleRestore(IBaritone baritone, IPlayerContext ctx, int prevSlot, int ticks) {
        // minimal delayed restore: run on the client thread after `ticks`
        new Thread(() -> {
            try { Thread.sleep(ticks * 50L); } catch (InterruptedException ignored) {}
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
                Player p = ctx.player();
                if (p != null) p.getInventory().setSelectedSlot(prevSlot);
            });
        }, "arion-eat-restore").start();
    }

    /** First hotbar/inventory slot whose item is edible, or -1. */
    private int findFoodSlot(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            // In this MC version edibility is a data component, not an Item method.
            FoodProperties food = s.get(net.minecraft.core.component.DataComponents.FOOD);
            if (food != null && food.nutrition() > 0) {
                return i;
            }
        }
        return -1;
    }

    /** Where the player last died, for drop recovery. Null if never died. */
    public BlockPos lastDeathPos() {
        return lastDeathPos;
    }

    public void clearDeathPos() {
        lastDeathPos = null;
    }
}
