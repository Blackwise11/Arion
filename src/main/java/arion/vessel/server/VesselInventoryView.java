/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.vessel.server;

import net.minecraft.world.Container;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Read/write view of the vessel body's inventory for the container inspector
 * ({@code ai inventory} / right-clicking the vessel).
 *
 * <p>Layout (5-row chest):
 * <pre>
 *   slot   0..3   armor icons, left-to-right: helmet, chestplate, leggings, boots
 *   slot   4      offhand (shield icon)
 *   slots  5..40  main inventory + hotbar (36 slots)
 *   slots 41..44  empty filler (the chest has 45 slots)
 * </pre>
 *
 * <p>Every access goes through {@link Inventory#getItem}/{@link Inventory#setItem}, which is the
 * exact path the vanilla {@code InventoryMenu} armor slots use ({@code Slot.setByPlayer} ->
 * {@code Container.setItem}). Equipping armor here therefore applies its attributes, durability and
 * equip hooks (and unequipping clears them) with no extra bookkeeping.
 *
 * <p>{@link Inventory} stores the armor region boots-first (36 = feet, 39 = head); the display order
 * is remapped so the row reads like the player's inventory screen. The offhand is
 * {@code SLOT_OFFHAND} (40), which also makes the shield reachable from the UI instead of
 * {@code /item replace entity ... weapon.offhand}.
 */
final class VesselInventoryView implements Container {

    /** 36 main/hotbar + 4 armor + 1 offhand. */
    static final int REAL_SLOTS = Inventory.SLOT_OFFHAND + 1; // 41
    /** 5-row chest = 45 slots; the last four are filler so ChestMenu has a full grid. */
    static final int VIEW_SIZE = 45;

    private final Inventory inventory;

    VesselInventoryView(Inventory inventory) {
        this.inventory = inventory;
    }

    /** View slot -> body inventory slot (equipment first, then the 36 main slots). */
    private static int toInventorySlot(int slot) {
        return switch (slot) {
            case 0 -> 39; // helmet
            case 1 -> 38; // chestplate
            case 2 -> 37; // leggings
            case 3 -> 36; // boots
            case 4 -> 40; // offhand
            default -> (slot >= 5 && slot < 41) ? slot - 5 : -1;
        };
    }

    @Override
    public int getContainerSize() {
        return VIEW_SIZE;
    }

    @Override
    public boolean isEmpty() {
        return this.inventory.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        int inv = toInventorySlot(slot);
        return inv >= 0 && inv < REAL_SLOTS ? this.inventory.getItem(inv) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        int inv = toInventorySlot(slot);
        return inv >= 0 && inv < REAL_SLOTS ? this.inventory.removeItem(inv, count) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        int inv = toInventorySlot(slot);
        return inv >= 0 && inv < REAL_SLOTS ? this.inventory.removeItemNoUpdate(inv) : ItemStack.EMPTY;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        int inv = toInventorySlot(slot);
        if (inv >= 0 && inv < REAL_SLOTS) {
            this.inventory.setItem(inv, stack);
        }
    }

    @Override
    public void setChanged() {
        this.inventory.setChanged();
    }

    @Override
    public void clearContent() {
        this.inventory.clearContent();
    }

    @Override
    public boolean stillValid(Player player) {
        return true; // the body can be anywhere; the viewer is the host that owns it
    }

    /** Not used by {@code ChestMenu}; the container is menu-only, not a command target. */
    @Override
    public SlotAccess getSlot(int slot) {
        return null;
    }
}
