/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.brain;

import arion.config.ArionConfig;
import arion.memory.ArionMemory;
import arion.provider.AiProvider;
import arion.provider.AiProvider.AiRequest;
import arion.provider.AiProvider.Message;
import arion.provider.ProviderRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The LLM "brain". Turns a player request plus a world snapshot into a
 * structured {@link ArionAction} the controller can dispatch to Baritone.
 *
 * Runs entirely off the client thread: {@link #decide} returns a future and the
 * caller marshals the result back onto the client thread.
 */
public final class ArionBrain {

    private final ArionConfig config;
    private final ProviderRegistry registry;
    private final ArionMemory memory;

    public ArionBrain(ArionConfig config, ProviderRegistry registry, ArionMemory memory) {
        this.config = config;
        this.registry = registry;
        this.memory = memory;
    }

    /** Exposed for the settings "test connection" command. */
    public ProviderRegistry registry() {
        return registry;
    }

    /** Exposed for debug logging of the exact system prompt in use. */
    public String systemPromptForDebug() {
        return systemPrompt();
    }

    /**
     * Ask the reflex (fast) model to classify a player request into an action.
     * The reflex model is small and quick — combat never goes through here.
     */
    public CompletableFuture<ArionAction> decide(String playerMessage, String worldSnapshot) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(systemPrompt()));
        messages.add(Message.user("World state:\n" + worldSnapshot + "\n\nPlayer says: " + playerMessage));

        AiProvider reflex = registry.byIdOrDefault(config.reflexProvider.get());
        AiRequest req = new AiRequest(
                config.reflexBaseUrl.get(),
                config.reflexModel.get(),
                config.reflexApiKey.get(),
                messages,
                config.plannerTimeoutSeconds.get()
        );

        return reflex.complete(req)
                .thenApply(ArionBrain::parseAction)
                .exceptionally(t -> ArionAction.chat("❌ " + rootMessage(t)));
    }

    /**
     * Ask the planner (bigger) model for multi-step reasoning. Used for open-ended
     * goals rather than one-shot commands.
     */
    public CompletableFuture<String> plan(String goal, String worldSnapshot) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(
                "You are Arion, a Minecraft companion. The player controls the game through you. " +
                "Break the goal into a short numbered list of concrete steps using these verbs: " +
                verbList() + ". Be brief."));
        // include conversational memory so the planner has context
        messages.addAll(memory.recent());
        messages.add(Message.user("World state:\n" + worldSnapshot + "\n\nGoal: " + goal));

        AiProvider planner = registry.byIdOrDefault(config.plannerProvider.get());
        AiRequest req = new AiRequest(
                config.plannerBaseUrl.get(),
                config.plannerModel.get(),
                config.plannerApiKey.get(),
                messages,
                config.plannerTimeoutSeconds.get()
        );

        return planner.complete(req)
                .exceptionally(t -> "❌ " + rootMessage(t));
    }

    /** Parse the model's reply into a structured action. Tolerant of junk output. */
    static ArionAction parseAction(String reply) {
        if (reply == null || reply.isBlank()) {
            return ArionAction.chat("(no response)");
        }
        // try to pull the first {...} JSON object out of the reply
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            // model didn't return JSON — just relay what it said
            return ArionAction.chat(reply.trim());
        }
        try {
            JsonObject o = JsonParser.parseString(reply.substring(start, end + 1)).getAsJsonObject();
            String verb = o.has("action") ? o.get("action").getAsString().toLowerCase() : "chat";
            String arg  = o.has("arg") ? o.get("arg").getAsString() : "";
            String say  = o.has("say") ? o.get("say").getAsString() : "";
            return new ArionAction(verb, arg, say);
        } catch (Exception e) {
            return ArionAction.chat(reply.trim());
        }
    }

    /** Comma-separated verb list for the planner prompt (respects the extendedVerbs gate). */
    private String verbList() {
        String base = "mine, follow, goto, hunt, explore, farm, get, pickup";
        if (config.extendedVerbs.get()) {
            base += ", build, clear";
        }
        return base + ", stop";
    }

    private String systemPrompt() {
        StringBuilder sb = new StringBuilder("""
                You are Arion, a Minecraft companion. The player controls the game through you.
                Classify the player's request into EXACTLY ONE action and reply with ONLY a JSON object, no prose.

                Schema:
                {"action":"<verb>","arg":"<argument>","say":"<short reply to player>"}

                Verbs:
                - mine   : arg = block name (e.g. "oak_log", "iron_ore") or "N block" for a count
                - follow : arg = "" (follow the player) or a player name
                - goto   : arg = "x y z", or relative "~dx ~dy ~dz", or a compass word ("north") or "surface"
                - hunt   : arg = mob name (e.g. "zombie", "skeleton"), "nearest", or "all"
                - explore: arg = "" (wander and map the area)
                - farm   : arg = "" or a radius in blocks (tend/harvest/replant crops nearby)
                - get    : arg = block name — walk to a block to USE it (crafting_table, chest, furnace, bed)
                - pickup : arg = "" or an item name (collect dropped items)
                """);
        if (config.extendedVerbs.get()) {
            sb.append("""
                - build  : arg = schematic name (build a structure from a file in the schematics folder)
                - clear  : arg = "x1 y1 z1 x2 y2 z2" (level/remove all blocks in the region — destructive)
                """);
        }
        sb.append("""
                - stop   : arg = "" (stop everything)
                - chat   : arg = "" (just talk; put your reply in "say")

                Prefer an action verb whenever the request implies doing something in the world.
                Distinguish get (reach and use a block) from mine (break and collect blocks).
                Use chat only for pure questions or conversation. Keep "say" under 20 words.
                """);
        return sb.toString();
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }
}
