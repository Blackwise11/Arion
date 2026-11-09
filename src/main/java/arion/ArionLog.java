/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion;

import baritone.api.utils.Helper;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

/**
 * Shared debug/trace logging for the whole mod, gated on {@code config.debugMode}.
 *
 * <p>{@link ArionController} used to own the only debug sink (a private {@code debug()}
 * that dual-sank to log4j and the Baritone chat HUD). Classes without access to it
 * ({@link arion.brain.ArionBrain}, {@link arion.controller.JobRunner},
 * {@link arion.controller.Job}, {@link arion.vessel.FakePlayerManager},
 * {@link arion.chat.ArionChatHandler}) had no way to emit the same trace — leaving most
 * of the decision pipeline invisible. This utility fills that gap.
 *
 * <p>Three levels, all off when debug mode is off:
 * <ul>
 *   <li>{@link #debug} — log-only, {@code [Arion DEBUG]} prefix. The default trace.</li>
 *   <li>{@link #trace} — log-only, {@code [Arion TRACE]} prefix. Verbose (raw LLM
 *       replies, full brain snapshots); distinct prefix so it can be grepped apart.</li>
 *   <li>{@link #debugChat} — log <b>and</b> the in-game chat HUD (via Baritone's
 *       {@link Helper#HELPER}). For state transitions the user should see live.</li>
 * </ul>
 *
 * <p>The gate is a live {@link BooleanSupplier} (wired from {@code config.debugMode.get()}
 * in {@link Arion}'s constructor) so toggling the config at runtime takes effect
 * immediately. All chat-sink work is marshalled to the client thread via
 * {@link Minecraft#execute}, so the methods are safe to call from any thread (the server
 * thread included). log4j is thread-safe.
 *
 * <p><b>Security:</b> never pass API keys, base URLs containing credentials, or the
 * {@code AiRequest} object itself here. Message text the LLM sees is safe to log;
 * {@code AiRequest.apiKey} is not.
 */
public final class ArionLog {

    private static volatile BooleanSupplier enabled = () -> false;

    private ArionLog() {}

    /** Wire the debug gate. Call once during mod init. */
    public static void init(BooleanSupplier gate) {
        enabled = gate;
    }

    /** True when debug logging is on. */
    public static boolean on() {
        return enabled.getAsBoolean();
    }

    /** Log-only trace, {@code [Arion DEBUG]} prefix. */
    public static void debug(String msg) {
        if (enabled.getAsBoolean()) {
            Arion.LOGGER.info("[Arion DEBUG] {}", msg);
        }
    }

    /** Log-only verbose trace, {@code [Arion TRACE]} prefix (raw LLM, full snapshots). */
    public static void trace(String msg) {
        if (enabled.getAsBoolean()) {
            Arion.LOGGER.info("[Arion TRACE] {}", msg);
        }
    }

    /** Log <b>and</b> echo to the in-game chat HUD. For live state transitions. */
    public static void debugChat(String msg) {
        if (!enabled.getAsBoolean()) return;
        Arion.LOGGER.info("[Arion DEBUG] {}", msg);
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                Helper.HELPER.logDirect(Component.literal("[Arion DEBUG] " + msg));
            }
        });
    }

    /**
     * Always-visible chat + log line, <b>not</b> gated on {@code debugMode}. Reserved for
     * critical operational state the user must see to operate the mod — e.g. whether the
     * vessel's Baritone is bound (without it, {@code ai mine} silently runs on the host).
     * Do not use for trace; that's what {@link #debugChat} is for.
     */
    public static void chat(String msg) {
        Arion.LOGGER.info("[Arion] {}", msg);
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                Helper.HELPER.logDirect(Component.literal("[Arion] " + msg));
            }
        });
    }
}
