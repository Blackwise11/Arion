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
        // log the user-side prompt only (messages carry no API key — the key lives on AiRequest)
        arion.ArionLog.trace("LLM request: " + playerMessage);

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

    /**
     * System 2 macro planner. Asks the configured planner for a strict JSON plan
     * (AGENTS.md §4.1) that {@code ProcessTracker} can execute one step at a time
     * while Laya picks the atomic actions.
     *
     * <p>Returns the raw reply; the caller parses it so a malformed plan is reported
     * as "planner returned unusable JSON" instead of silently becoming chat text.
     * Terminates exceptionally on provider errors — the caller must attach
     * {@code .exceptionally(...)} before dropping the future.
     */
    public CompletableFuture<String> planSteps(String goal, String worldSnapshot) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(planSystemPrompt()));
        messages.addAll(memory.recent());
        messages.add(Message.user("World state:\n" + worldSnapshot + "\n\nGoal: " + goal));
        arion.ArionLog.debug("planner(planSteps) → " + config.plannerProvider.get()
                + "/" + config.plannerModel.get());

        AiProvider planner = registry.byIdOrDefault(config.plannerProvider.get());
        AiRequest req = new AiRequest(
                config.plannerBaseUrl.get(),
                config.plannerModel.get(),
                config.plannerApiKey.get(),
                messages,
                config.plannerTimeoutSeconds.get()
        );
        return planner.complete(req);
    }

    /**
     * The strict plan schema. The planner is a chat model, not a JSON-mode endpoint, so the
     * prompt repeats the "only JSON" rule and gives one valid example.
     */
    private static String planSystemPrompt() {
        return """
                You are Arion, the System 2 macro planner for a Minecraft companion that must
                progress from spawn to defeating the Ender Dragon.

                Break the goal into a short sequence of concrete steps. Reply with ONLY a JSON
                object — no prose, no markdown fences.

                Schema:
                {"goal":"<string>","total_steps":<int>,"steps":[
                  {"step_id":<int>,
                   "objective":"<gather|craft|smelt|explore|hunt>",
                   "target":"<item_or_block_identifier>",
                   "amount":<int>,
                   "exit_condition":"<expression_evaluable_by_perception>"}
                ]}

                Rules:
                - 3 to 12 steps, ordered. Use minecraft ids for targets (e.g. "minecraft:iron_ore").
                - The LAST step MUST produce the goal item itself. Never end the plan at an
                  intermediate ingredient: for "get an iron pickaxe", the final step is
                  craft minecraft:iron_pickaxe, not smelt minecraft:iron_ingot.
                - Targets are the items the bot should END UP with (e.g. cobblestone, raw_iron);
                  Arion maps them to the correct block to mine. Do not repeat the same
                  target/amount in consecutive steps.
                - If the goal only asks to collect/gather items, use only gather/explore/hunt steps;
                  do not invent crafting or smelting steps the goal did not ask for.
                - objective=gather: mine or collect `amount` of target.
                - A tool needed to harvest a target must be crafted BEFORE that gather step:
                  stone_pickaxe before iron_ore/raw_iron, iron_pickaxe before diamond_ore/diamond,
                  wooden_pickaxe before stone/cobblestone. Never put the gather step before its tool.
                - Do not put an explore step before a gather/mine step: Baritone's mining action
                  searches for and paths to the target itself. Use explore only when the resource
                  is genuinely unknown or the bot is lost.
                - objective=craft/smelt: produce `amount` of target at a crafting table / furnace.
                - objective=explore: target "" and amount 0; move outward to find resources.
                - objective=hunt: target is a mob name (e.g. "zombie") and amount is how many.
                - exit_condition must be short and checkable from the body status, e.g.
                  "inventory:minecraft:raw_iron >= 24" or "at y <= 12 and has iron_pickaxe".
                - Only plan what is achievable with the current body, tools, and inventory shown
                  in the world state. Do not repeat steps that are already complete.
                """;
    }

    /** Parse the model's reply into a structured action. Tolerant of junk output. */
    static ArionAction parseAction(String reply) {
        arion.ArionLog.trace("LLM reply: " + reply);
        if (reply == null || reply.isBlank()) {
            arion.ArionLog.debug("LLM reply not JSON → chat fallback (blank)");
            return ArionAction.chat("(no response)");
        }
        // try to pull the first {...} JSON object out of the reply
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            // model didn't return JSON — just relay what it said
            arion.ArionLog.debug("LLM reply not JSON → chat fallback (no braces)");
            return ArionAction.chat(reply.trim());
        }
        try {
            JsonObject o = JsonParser.parseString(reply.substring(start, end + 1)).getAsJsonObject();
            String verb = o.has("action") ? o.get("action").getAsString().toLowerCase() : "chat";
            String arg  = o.has("arg") ? o.get("arg").getAsString() : "";
            String say  = o.has("say") ? o.get("say").getAsString() : "";
            arion.ArionLog.debug("parsed action: verb=" + verb + " arg=\"" + arg + "\" say=\"" + say + "\"");
            return new ArionAction(verb, arg, say);
        } catch (Exception e) {
            arion.ArionLog.debug("LLM reply not JSON → chat fallback (parse error: " + rootMessage(e) + ")");
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
