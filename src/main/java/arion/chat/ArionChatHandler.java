/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.chat;

import arion.config.ArionConfig;
import arion.controller.ArionController;
import baritone.api.IBaritone;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientChatEvent;

/**
 * Captures the player's outgoing chat and routes lines that start with the
 * configured prefix (default "ai ") to the controller. The message is cancelled
 * so it never reaches the server — Arion commands stay local.
 *
 * Registered on the NeoForge GAME event bus (ClientChatEvent is outbound-only,
 * fired when the player sends a chat line).
 */
public final class ArionChatHandler {

    private final ArionConfig config;
    private final ArionController controller;

    public ArionChatHandler(ArionConfig config, ArionController controller) {
        this.config = config;
        this.controller = controller;
    }

    @SubscribeEvent
    public void onClientChat(ClientChatEvent event) {
        if (!config.enabled.get()) return;
        String prefix = config.chatPrefix.get();
        String raw = event.getMessage();
        if (raw == null || !raw.toLowerCase().startsWith(prefix.toLowerCase())) {
            return;
        }
        // it's ours — don't send it to the server
        event.setCanceled(true);
        String command = raw.substring(prefix.length()).trim();
        if (command.isEmpty()) return;
        arion.ArionLog.debug("chat → \"" + command + "\"");

        IBaritone baritone = arion.Arion.baritone();
        if (baritone == null) {
            // previously a silent drop — tell the player + log it
            arion.ArionLog.debug("chat: no Baritone available — dropped");
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                var p = net.minecraft.client.Minecraft.getInstance().player;
                if (p != null) {
                    p.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "[Arion] Baritone isn't ready yet — try again in a moment."));
                }
            });
            return;
        }
        controller.onPlayerCommand(baritone, command);
    }
}
