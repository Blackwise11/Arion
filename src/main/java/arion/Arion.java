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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
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

        // wire the brain, memory, and controller
        ProviderRegistry registry = new ProviderRegistry();
        ArionMemory memory = new ArionMemory(config.maxContextTurns);
        ArionBrain brain = new ArionBrain(config, registry, memory);
        controller = new ArionController(config, brain, memory);
        vesselManager = new arion.vessel.FakePlayerManager(config);

        // you → bot: chat with the configured prefix (NeoForge GAME bus)
        NeoForge.EVENT_BUS.register(new ArionChatHandler(config, controller));

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
        arion.vessel.server.ServerVesselManager.shutdown(event.getServer());
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
            } catch (Throwable t) {
                LOGGER.warn("Arion couldn't attach to Baritone yet: {}", t.toString());
            }
        }
        // vessel upkeep (survival reflexes) runs on the client tick
        if (vesselManager != null) {
            vesselManager.tick();
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
