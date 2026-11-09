/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.vessel.server;

import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Container UI for the vessel body: <b>row 1 is equipment</b>, the four rows below are the body's
 * 36 main/hotbar slots, and the viewer's own inventory stays at the bottom (standard {@link ChestMenu}).
 *
 * <pre>
 *   row 1: [helmet] [chest] [legs] [boots] [offhand] [ ] [ ] [ ] [ ]
 *   rows 2-5: 36 main inventory / hotbar slots
 *   + the viewer's inventory, rendered by the vanilla chest screen
 * </pre>
 *
 * <p>The four armor cells and the offhand cell carry the vanilla empty-slot sprites via
 * {@link Slot#getNoItemIcon()} (helmet/chestplate/leggings/boots/shield), which is exactly how
 * the player inventory screen draws them — so the row reads at a glance without a custom screen.
 *
 * <p>Why not open the vessel's real {@code InventoryMenu}? Its container id is hard-coded to 0,
 * and the client maps container 0 to the <i>viewer's</i> inventory menu; server slot packets for
 * the vessel would then be applied to the viewer's own inventory. A dedicated menu type with a
 * dummy client container is the safe equivalent.
 */
public final class VesselChestMenu extends ChestMenu {

    /** 5 rows: equipment + 36 main/hotbar, then the viewer's 36 slots. */
    public static final int ROWS = 5;
    /** Container slots (row 1 + four inventory rows); the last four are empty filler. */
    public static final int VESSEL_SLOTS = ROWS * 9;      // 45
    /** First viewer-inventory slot in {@link #slots}. */
    public static final int HOST_START = VESSEL_SLOTS;    // 45
    public static final int HOST_END = HOST_START + 36;   // 81

    private static final int MAIN_START = 5;
    private static final int MAIN_END = 41;
    private static final int OFFHAND_CELL = 4;

    /**
     * Client side. {@link ChestMenu} creates a fresh 45-slot dummy container; the real contents
     * arrive in the container packets (never written into the viewer's own inventory).
     */
    public VesselChestMenu(MenuType<?> menuType, int containerId, Inventory viewerInventory) {
        this(menuType, containerId, viewerInventory, new SimpleContainer(ROWS * 9));
    }

    /** Server side: {@code vesselView} is the body's live inventory view. */
    public VesselChestMenu(MenuType<?> menuType, int containerId, Inventory viewerInventory,
                           Container vesselView) {
        super(menuType, containerId, viewerInventory, vesselView, ROWS);
        decorateEquipmentSlots();
    }

    /** Replace the five equipment cells with slots that draw the vanilla empty-slot icons. */
    private void decorateEquipmentSlots() {
        Identifier[] icons = {
                InventoryMenu.EMPTY_ARMOR_SLOT_HELMET,
                InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
                InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS,
                InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS,
                InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD
        };
        for (int i = 0; i < icons.length; i++) {
            final Identifier icon = icons[i];
            Slot old = this.slots.get(i);
            this.slots.set(i, new Slot(old.container, i, old.x, old.y) {
                @Override
                public Identifier getNoItemIcon() {
                    return icon;
                }
            });
        }
    }

    /**
     * Shift-click routing:
     * <ul>
     *   <li>viewer inventory → the matching equipment cell when it is free (armor/offhand),
     *       otherwise the body's main inventory;</li>
     *   <li>body inventory / equipment → the viewer's inventory.</li>
     * </ul>
     * This is what stops a shift-clicked stack from being dumped into the armor row.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        if (index >= HOST_START) {
            EquipmentSlot eq = player.getEquipmentSlotForItem(stack);
            int target = switch (eq) {
                case HEAD -> 0;
                case CHEST -> 1;
                case LEGS -> 2;
                case FEET -> 3;
                case OFFHAND -> OFFHAND_CELL;
                default -> -1;
            };
            boolean moved = target >= 0
                    && !this.slots.get(target).hasItem()
                    && this.moveItemStackTo(stack, target, target + 1, false);
            if (!moved && !this.moveItemStackTo(stack, MAIN_START, MAIN_END, false)) {
                return ItemStack.EMPTY;
            }
        } else if (index < VESSEL_SLOTS) {
            if (!this.moveItemStackTo(stack, HOST_START, HOST_END, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }
}
