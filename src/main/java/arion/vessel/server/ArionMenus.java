/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.vessel.server;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Menu types owned by Arion.
 *
 * <p>The vessel inventory needs its own type (not {@code MenuType.GENERIC_9x5}) so the client
 * builds a {@link VesselChestMenu} — with the equipment-row icons and the shift-click routing —
 * and so the server menu is backed by the body's live inventory instead of a plain container.
 */
public final class ArionMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, "arion");

    public static final DeferredHolder<MenuType<?>, MenuType<VesselChestMenu>> VESSEL_INVENTORY =
            MENUS.register("vessel_inventory", () -> IMenuTypeExtension.create(
                    (id, inventory, data) -> new VesselChestMenu(type(), id, inventory)));

    /** Resolved lazily: a direct field read inside the initializer above is a self-reference. */
    static MenuType<VesselChestMenu> type() {
        return VESSEL_INVENTORY.get();
    }

    private ArionMenus() {}
}
