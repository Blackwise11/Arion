/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion;

import arion.brain.ArionBrain;
import arion.chat.ArionChatHandler;
import arion.config.ArionConfig;
import arion.controller.ArionController;
import arion.memory.ArionMemory;
import arion.provider.ProviderRegistry;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Arion — an AI companion mod.
 *
 * Arion is a standalone mod that REQUIRES Baritone (mod id {@code baritoe}). It does
 * not modify or improve Baritone; it drives Baritone's public API as its body while an
 * LLM acts as the brain. See step_to_dev.md.
 */
@Mod(value = "arion", dist = Dist.CLIENT)
public final class Arion {

    public static final String MOD_ID = "arion";
    public static final Logger LOGGER = LogManager.getLogger("Arion");

    private static ArionController controller;
    private static ArionConfig config;
    private static arion.vessel.FakePlayerManager vesselManager;

    public Arion(IEventBus modBus) {
        LOGGER.info("Arion initializing (requires Baritone).");

        // config first — everything else reads from it
        config = new ArionConfig();
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.CLIENT, config.spec, "arion-client.toml");

        // shared debug/trace gate — live supplier so toggling debugMode in the config
        // takes effect immediately (ArionLog is used across the brain, jobs, combat, etc.)
        ArionLog.init(() -> config.debugMode.get());

        // wire the brain, memory, and controller
        ProviderRegistry registry = new ProviderRegistry();
        ArionMemory memory = new ArionMemory(config.maxContextTurns);
        ArionBrain brain = new ArionBrain(config, registry, memory);
        controller = new ArionController(config, brain, memory);
        vesselManager = new arion.vessel.FakePlayerManager(config);
        // route vessel hunt-ends through the controller so the brain re-decides the next
        // action (dormant until the vessel can hunt — Phase C/D)
        vesselManager.setHuntEndHandler(controller::onVesselHuntEnded);

        // you → bot: chat with the configured prefix (NeoForge GAME bus)
        NeoForge.EVENT_BUS.register(new ArionChatHandler(config, controller));

        // vessel inventory menu type + its chest-style screen (equipment icons in row 1)
        arion.vessel.server.ArionMenus.MENUS.register(modBus);
        modBus.addListener(Arion::onRegisterMenuScreens);

        // right-click the vessel entity to open its live inventory UI
        NeoForge.EVENT_BUS.addListener(this::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(this::onInteractionKey);

        // voice front-end status (Simple Voice Chat integration is optional)
        new arion.voice.ArionVoice(config).logStatus();

        // in-game settings GUI: NeoForge's built-in ConfigurationScreen auto-generates
        // an editor for our ModConfigSpec (provider/model/key/URL fields — BYOK), opened
        // from the mod list. Test-connection is the `ai test` chat command.
        ModLoadingContext.get().getActiveContainer().registerExtensionPoint(
                net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                net.neoforged.neoforge.client.gui.ConfigurationScreen::new);

        // job supervision: ticks + path events come from Baritone's own event bus,
        // which we join lazily once a world (and thus a Baritone instance) exists.
        NeoForge.EVENT_BUS.addListener(this::onClientStartedHook);

        // on logout / world unload, clear combat state so a stale target or a latched
        // reach buff never survives into the next world
        NeoForge.EVENT_BUS.addListener(this::onLogout);

        // Server lifecycle: on an integrated (single-player) server the physical dist IS
        // client, so these game-bus events fire even though @Mod is Dist.CLIENT. The vessel
        // body is a server-side ServerPlayer, so its state must be torn down when the server
        // stops — otherwise it would leak across world reloads.
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
    }

    private void onServerStarting(net.neoforged.neoforge.event.server.ServerStartingEvent event) {
        arion.vessel.server.ServerVesselManager.get(event.getServer());
    }

    private void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        arion.crafting.CraftJobManager.shutdown(event.getServer());
        arion.vessel.server.ServerVesselManager.shutdown(event.getServer());
    }

    /**
     * Register the chest-style screen for {@link arion.vessel.server.VesselChestMenu}. Client-only
     * event (mod bus); the screen is the vanilla {@code ContainerScreen}, so the equipment row's
     * armor/shield icons are drawn by the standard slot renderer via {@code Slot.getNoItemIcon()}.
     */
    private static void onRegisterMenuScreens(
            net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) {
        event.<net.minecraft.world.inventory.ChestMenu,
                        net.minecraft.client.gui.screens.inventory.ContainerScreen>register(
                arion.vessel.server.ArionMenus.VESSEL_INVENTORY.get(),
                (menu, inv, title) ->
                        new net.minecraft.client.gui.screens.inventory.ContainerScreen(menu, inv, title));
    }

    /**
     * Right-clicking the vessel opens its live inventory as a 4-row chest UI on the host.
     *
     * <p>Handled on both logical sides: the client event may be the only one fired for an entity
     * interaction, while the server event is the authority that can actually open a menu for the
     * host. The duplicate-open guard avoids two menus when both fire.
     */
    private void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Player target)) return;
        if (vesselManager == null) return;
        java.util.UUID vesselId = vesselManager.vesselUuid();
        if (vesselId == null || !vesselId.equals(target.getUUID())) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        if (event.getLevel().isClientSide()) {
            openVesselInventoryThrottled();
            return;
        }
        if (event.getEntity() instanceof ServerPlayer host) {
            var server = host.level().getServer();
            if (server != null && host.containerMenu == host.inventoryMenu) {
                String error = arion.vessel.server.ServerVesselManager.get(server).openInventory(host);
                if (error != null) {
                    arion.vessel.server.ServerVesselManager.tellHost(server, host.getUUID(), error);
                }
            }
        }
    }

    private long lastVesselInventoryOpenMs = 0;

    /**
     * Client input path: right-click while the crosshair is on the vessel. Complements the entity
     * interact event (some interaction paths only fire one of the two); the 500 ms throttle stops
     * a double-open when both fire.
     */
    private void onInteractionKey(net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) return;
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || !(mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit)) return;
        if (!(hit.getEntity() instanceof Player target)) return;
        if (vesselManager == null) return;
        java.util.UUID vesselId = vesselManager.vesselUuid();
        if (vesselId == null || !vesselId.equals(target.getUUID())) return;
        if (mc.player.distanceToSqr(target) > 16.0) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        openVesselInventoryThrottled();
    }

    private void openVesselInventoryThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastVesselInventoryOpenMs < 500) return;
        lastVesselInventoryOpenMs = now;
        String error = vesselManager != null ? vesselManager.openInventory() : "No vessel.";
        if (error != null) ArionLog.chat(error);
    }

    /**
     * The local player's Baritone instance, or null if unavailable (e.g. no world yet,
     * or Baritone not present). Callers must null-check.
     */
    public static IBaritone baritone() {
        try {
            return BaritoneAPI.getProvider().getPrimaryBaritone();
        } catch (Throwable t) {
            // Baritone not initialized yet or not present.
            return null;
        }
    }

    public static ArionController controller() {
        return controller;
    }

    public static ArionConfig config() {
        return config;
    }

    public static arion.vessel.FakePlayerManager vessels() {
        return vesselManager;
    }

    // The controller must be registered on Baritone's IEventBus to receive onTick /
    // onPathEvent. Baritone's event bus is per-instance and is recreated across world
    // loads / reconnects, so we track WHICH instance we attached to and re-register
    // whenever a different one appears — otherwise combat silently stops ticking.
    private IBaritone attachedBaritone = null;

    private void onClientStartedHook(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        IBaritone b = baritone();
        if (b == null) {
            attachedBaritone = null; // world gone; force a re-attach next time
            return;
        }
        if (b != attachedBaritone) {
            try {
                b.getGameEventHandler().registerEventListener(controller);
                attachedBaritone = b;
                LOGGER.info("Arion attached to Baritone's event bus.");
                applyMiningTuning();
            } catch (Throwable t) {
                LOGGER.warn("Arion couldn't attach to Baritone yet: {}", t.toString());
            }
        }
        // vessel upkeep (survival reflexes) runs on the client tick
        if (vesselManager != null) {
            vesselManager.tick();
        }

        // M3 craft jobs: one server-thread step per tick while a job is active. Never run the
        // job on the client thread (it mutates inventories/menus/furnaces).
        try {
            var server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
            if (server != null && arion.crafting.CraftJobManager.isBusy(server)) {
                server.execute(() -> arion.crafting.CraftJobManager.tick(server));
            }
        } catch (Throwable t) {
            LOGGER.warn("Arion craft job tick failed: {}", t.toString());
        }
    }

    /**
     * Apply the drop-collection tuning to Baritone's global settings.
     *
     * <p>Baritone's default {@code cancelOnGoalInvalidation=true} cancels the path the instant a
     * mined block becomes air, so the body walks off and never picks up the drop. Turning it off
     * makes the body walk onto each mined block (slower, but collects everything); the loiter
     * duration gives the drop time to spawn before the next target is chosen. Arion additionally
     * sweeps nearby drops after every mining action (see {@code startDropPickup}).
     */
    private void applyMiningTuning() {
        try {
            var s = BaritoneAPI.getSettings();
            // Auto-tool: Baritone's InventoryBehavior only selects tools from the hotbar and its
            // allowInventory default is false, so a pickaxe crafted into the main inventory (e.g.
            // slot 35) is never moved to the hotbar. The bot then mines diamond ore with a stone
            // pickaxe and gets no drops. Enable inventory moves so Baritone swaps the best tool in.
            s.allowInventory.value = true;
            if (config.mineCollectDrops.get()) {
                s.mineScanDroppedItems.value = true;
                s.cancelOnGoalInvalidation.value = false;
                s.mineDropLoiterDurationMSThanksLouca.value = (long) config.mineDropLoiterMs.get();
            }
            LOGGER.info("Arion mining tuning: allowInventory=true (auto-tool), collectDrops={}, loiter={}ms",
                    config.mineCollectDrops.get(), config.mineDropLoiterMs.get());
        } catch (Throwable t) {
            LOGGER.warn("Arion couldn't tune Baritone mining settings: {}", t.toString());
        }
    }

    private void onLogout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        attachedBaritone = null; // force re-attach on next join
        try {
            if (controller != null) {
                IBaritone b = baritone();
                if (b != null) {
                    controller.combat().reset(b.getPlayerContext());
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Arion combat reset on logout failed: {}", t.toString());
        }
        // the vessel doesn't survive a world change — drop it
        if (vesselManager != null) {
            vesselManager.reset();
        }
    }
}
