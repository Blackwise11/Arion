/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Executes the System 2 planner's macro plan one step at a time while the System 1
 * engine (Laya) picks atomic actions.
 *
 * <p>This class is deliberately free of Minecraft types: it only knows about the
 * planner's JSON schema, inventory counts, and positions. That keeps the progression
 * logic unit-testable and lets the controller feed it a snapshot from either body
 * (the real player or the vessel shadow).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>parse DeepSeek's plan JSON (AGENTS.md §4.1), tolerantly</li>
 *   <li>track the current step, attempts, and progress</li>
 *   <li>auto-advance item steps when the target count is reached</li>
 *   <li>raise a re-plan reason on stalls, repeated failures, or hard timeouts</li>
 * </ul>
 *
 * <p>All calls happen on the Minecraft client thread (the controller's tick loop).
 */
public final class ProcessTracker {

    public enum Status { ACTIVE, DONE, ABANDONED }

    /** One planner step. {@code target} is an item/block id or a mob name; {@code amount} 0 = until done. */
    public record Step(int stepId, String objective, String target, int amount, String exitCondition) {

        /** Objectives whose success is measured by counting items in the body's inventory. */
        public boolean itemObjective() {
            String o = objective == null ? "" : objective.toLowerCase(Locale.ROOT);
            return o.equals("gather") || o.equals("mine") || o.equals("craft") || o.equals("smelt");
        }

        /** Normalized (namespace stripped) target id used for counting. */
        public String targetId() {
            return normalizeId(target);
        }

        public String oneLine() {
            StringBuilder sb = new StringBuilder(objective == null ? "step" : objective);
            if (target != null && !target.isBlank()) sb.append(' ').append(target);
            if (amount > 0) sb.append(" x").append(amount);
            if (exitCondition != null && !exitCondition.isBlank()) {
                sb.append(" [exit: ").append(exitCondition).append(']');
            }
            return sb.toString();
        }
    }

    /** A parsed macro plan. {@code raw} is kept for tracing, never shown in chat. */
    public record Plan(String goal, List<Step> steps, String raw) {
        public int totalSteps() {
            return steps.size();
        }
    }

    /** Per-tick snapshot fed by the controller (client body, or the vessel shadow while bound). */
    public record Progress(Map<String, Integer> items, double x, double y, double z,
                           float health, int food, long tick) {}

    /**
     * Mined block → the item it actually drops. The planner naturally says "gather 24
     * iron_ore" or "gather 20 stone"; counting the real inventory item is what makes the
     * step completable. Most blocks drop themselves, so this table only lists exceptions.
     */
    private static final Map<String, String> BLOCK_DROPS = Map.ofEntries(
            Map.entry("stone", "cobblestone"),
            Map.entry("deepslate", "cobbled_deepslate"),
            Map.entry("grass_block", "dirt"),
            Map.entry("podzol", "dirt"),
            Map.entry("mycelium", "dirt"),
            Map.entry("coal_ore", "coal"),
            Map.entry("deepslate_coal_ore", "coal"),
            Map.entry("iron_ore", "raw_iron"),
            Map.entry("deepslate_iron_ore", "raw_iron"),
            Map.entry("copper_ore", "raw_copper"),
            Map.entry("deepslate_copper_ore", "raw_copper"),
            Map.entry("gold_ore", "raw_gold"),
            Map.entry("deepslate_gold_ore", "raw_gold"),
            Map.entry("diamond_ore", "diamond"),
            Map.entry("deepslate_diamond_ore", "diamond"),
            Map.entry("emerald_ore", "emerald"),
            Map.entry("deepslate_emerald_ore", "emerald"),
            Map.entry("lapis_ore", "lapis_lazuli"),
            Map.entry("deepslate_lapis_ore", "lapis_lazuli"),
            Map.entry("redstone_ore", "redstone"),
            Map.entry("deepslate_redstone_ore", "redstone"),
            Map.entry("nether_quartz_ore", "quartz"),
            Map.entry("nether_gold_ore", "gold_nugget")
    );

    /**
     * Reverse of {@link #BLOCK_DROPS} for the mining action: an item target is obtained by breaking
     * the block that drops it. "gather 10 cobblestone" must mine <b>stone</b>, not search for
     * cobblestone blocks (which hardly exist naturally). Overworld variants are preferred over
     * deepslate; the player's pickaxe tier decides what is actually breakable.
     */
    private static final Map<String, String> ITEM_TO_BLOCK = Map.ofEntries(
            Map.entry("cobblestone", "stone"),
            Map.entry("cobbled_deepslate", "deepslate"),
            Map.entry("coal", "coal_ore"),
            Map.entry("raw_iron", "iron_ore"),
            Map.entry("raw_copper", "copper_ore"),
            Map.entry("raw_gold", "gold_ore"),
            Map.entry("diamond", "diamond_ore"),
            Map.entry("emerald", "emerald_ore"),
            Map.entry("lapis_lazuli", "lapis_ore"),
            Map.entry("redstone", "redstone_ore"),
            Map.entry("quartz", "nether_quartz_ore"),
            Map.entry("gold_nugget", "nether_gold_ore")
    );

    private static final int MAX_ATTEMPTS = 3;
    private static final int ITEM_STALL_TICKS = 20 * 120;   // 2 min without a target item
    private static final int MOVE_STALL_TICKS = 20 * 60;    // 1 min without moving
    private static final int STEP_TIMEOUT_TICKS = 20 * 60 * 10; // 10 min hard cap per step

    private Plan plan;
    private int index;
    private Status status = Status.DONE;

    private int attempts;
    private int bestCount = -1;
    private int actionStartCount = -1;
    /** True between beginAction() and noteActionFinished(); blocks mid-action step changes. */
    private boolean actionActive;

    private long stepStartTick = -1;
    private long lastProgressTick = -1;
    private boolean havePos;
    private double lastX, lastY, lastZ;

    private String replanReason;
    private String lastAdvanceReason;

    // ------------------------------------------------------------------ lifecycle

    /** Start (or replace) the macro plan. */
    public void begin(Plan plan, long nowTick) {
        if (plan == null || plan.steps().isEmpty()) {
            throw new IllegalArgumentException("plan has no steps");
        }
        this.plan = plan;
        this.index = 0;
        this.status = Status.ACTIVE;
        resetStepProgress(nowTick);
        this.replanReason = null;
        this.lastAdvanceReason = null;
    }

    public void abandon(String reason) {
        this.status = Status.ABANDONED;
        this.actionActive = false;
        this.replanReason = null;
        this.lastAdvanceReason = "abandoned: " + reason;
    }

    public boolean isActive() {
        return status == Status.ACTIVE && plan != null;
    }

    public Status status() {
        return status;
    }

    public Plan plan() {
        return plan;
    }

    public Step current() {
        if (plan == null || index < 0 || index >= plan.steps().size()) return null;
        return plan.steps().get(index);
    }

    public int stepIndex() {
        return index;
    }

    public int attempts() {
        return attempts;
    }

    /** Ticks spent on the current step (0 when no step is active). */
    public long stepAgeTicks(long nowTick) {
        return stepStartTick < 0 ? 0 : Math.max(0, nowTick - stepStartTick);
    }

    public int maxAttempts() {
        return MAX_ATTEMPTS;
    }

    public String lastAdvanceReason() {
        return lastAdvanceReason;
    }

    // --------------------------------------------------------------------- tick

    /**
     * Advance progress tracking one tick. Auto-advances a completed item step and raises a
     * re-plan reason on stall / timeout.
     *
     * @return the auto-advance reason when this tick completed the step, else null
     */
    public String tick(Progress p) {
        if (!isActive() || p == null) return null;
        Step s = current();
        if (s == null) return null;

        long now = p.tick();
        if (stepStartTick < 0) {
            stepStartTick = now;
            lastProgressTick = now;
        }
        if (lastProgressTick < 0) lastProgressTick = now;

        if (s.itemObjective() && s.amount() > 0) {
            int count = count(p, s);
            if (bestCount < 0 || count > bestCount) {
                bestCount = count;
                lastProgressTick = now;
            }
            if (count >= s.amount()) {
                // Do not switch steps in the middle of an action (e.g. the mine job is still
                // running when the count is already met); noteActionFinished() will advance.
                if (!actionActive) {
                    String reason = "target reached (" + count + "/" + s.amount() + ")";
                    advance(reason);
                    return reason;
                }
                return null;
            }
            if (now - lastProgressTick > ITEM_STALL_TICKS) {
                requestReplan("no target items gained for " + (ITEM_STALL_TICKS / 20) + "s");
                return null;
            }
        } else {
            if (!havePos) {
                havePos = true;
                lastX = p.x();
                lastY = p.y();
                lastZ = p.z();
                lastProgressTick = now;
            } else if (Math.hypot(p.x() - lastX, p.z() - lastZ) >= 4.0) {
                lastX = p.x();
                lastY = p.y();
                lastZ = p.z();
                lastProgressTick = now;
            }
            if (now - lastProgressTick > MOVE_STALL_TICKS) {
                requestReplan("no movement/exploration progress for " + (MOVE_STALL_TICKS / 20) + "s");
                return null;
            }
        }

        if (now - stepStartTick > STEP_TIMEOUT_TICKS) {
            requestReplan("step '" + s.oneLine() + "' exceeded "
                    + (STEP_TIMEOUT_TICKS / 20) + "s");
        }
        return null;
    }

    /**
     * Report the outcome of the atomic action Laya chose.
     *
     * <p>A job can finish "successfully" while producing no target items (wrong block,
     * unloaded chunk, unreachable vein); for item steps that still counts as a failed
     * attempt, otherwise the loop retries the same action forever.
     */
    public void noteActionFinished(boolean success, int currentCount, long nowTick) {
        if (!isActive()) return;
        Step s = current();
        if (s == null) return;

        boolean item = s.itemObjective() && s.amount() > 0;
        actionActive = false;
        actionStartCount = -1;
        // Reaching the target always completes the step, even if the action was cancelled
        // right after the last item was collected.
        if (item && currentCount >= s.amount()) {
            advance("target reached (" + currentCount + "/" + s.amount() + ")");
            return;
        }

        if (!success) {
            attempts++;
        } else if (item && actionStartCount >= 0 && currentCount <= actionStartCount) {
            attempts++;
        } else {
            attempts = 0;
        }

        if (attempts >= MAX_ATTEMPTS) {
            requestReplan("step '" + s.oneLine() + "' failed " + attempts + " times");
        }
        if (nowTick >= 0 && lastProgressTick < 0) lastProgressTick = nowTick;
    }

    /** Record the item count when an atomic action starts (for the no-progress check above). */
    public void beginAction(int currentCount) {
        actionActive = true;
        actionStartCount = currentCount;
    }

    /** Advance to the next step. Returns the new current step, or null when the plan is done. */
    public Step advance(String reason) {
        if (!isActive()) return null;
        lastAdvanceReason = reason;
        index++;
        if (index >= plan.steps().size()) {
            status = Status.DONE;
            index = plan.steps().size();
            return null;
        }
        resetStepProgress(-1);
        return current();
    }

    public void requestReplan(String reason) {
        if (replanReason == null) replanReason = reason;
    }

    public boolean needsReplan() {
        return replanReason != null;
    }

    public String takeReplanReason() {
        String r = replanReason;
        replanReason = null;
        return r;
    }

    private void resetStepProgress(long nowTick) {
        attempts = 0;
        bestCount = -1;
        actionStartCount = -1;
        actionActive = false;
        stepStartTick = nowTick;
        lastProgressTick = nowTick;
        havePos = false;
        lastX = lastY = lastZ = 0;
    }

    // ------------------------------------------------------------------ progress

    /** Items that satisfy the step's target (the target itself plus its real drop). */
    public static Set<String> countKeys(Step s) {
        Set<String> keys = new LinkedHashSet<>();
        if (s == null) return keys;
        String id = s.targetId();
        if (id.isEmpty()) return keys;
        keys.add(id);
        String drop = BLOCK_DROPS.get(id);
        if (drop != null) keys.add(drop);
        return keys;
    }

    /** Sum of the target's satisfying items currently held. */
    public int count(Progress p, Step s) {
        if (p == null || s == null) return 0;
        int total = 0;
        for (String key : countKeys(s)) {
            Integer n = p.items().get(key);
            if (n != null) total += n;
        }
        return total;
    }

    /**
     * The item id the block actually drops (for pickup filtering): {@code iron_ore → raw_iron},
     * {@code stone → cobblestone}, most blocks → themselves.
     */
    public static String dropItemName(Step s) {
        if (s == null) return "";
        String id = s.targetId();
        if (id.isEmpty()) return "";
        return BLOCK_DROPS.getOrDefault(id, id);
    }

    /**
     * The block Baritone should mine to obtain the step's target item. {@code cobblestone → stone},
     * {@code raw_iron → iron_ore}, most targets → themselves. This is what turns a planner step
     * "gather 10 cobblestone" into the easy, correct {@code #mine stone} job.
     */
    public static String mineBlockName(Step s) {
        if (s == null) return "";
        String id = s.targetId();
        if (id.isEmpty()) return "";
        return ITEM_TO_BLOCK.getOrDefault(id, id);
    }

    public int countCurrent(Progress p) {
        return count(p, current());
    }

    /** How many more of the target are needed (0 when met or not an item step). */
    public int remaining(Progress p) {
        Step s = current();
        if (s == null || !s.itemObjective() || s.amount() <= 0) return 0;
        return Math.max(0, s.amount() - count(p, s));
    }

    /** "step 2/6 gather minecraft:iron_ore have 8/24 (attempt 1)" — for the Laya state text. */
    public String progressLine(Progress p) {
        Step s = current();
        if (s == null) {
            return plan == null ? "no plan" : "plan complete";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("step ").append(index + 1).append('/').append(plan.totalSteps()).append(' ')
                .append(s.oneLine());
        if (s.itemObjective() && s.amount() > 0 && p != null) {
            sb.append(" have ").append(count(p, s)).append('/').append(s.amount());
        }
        sb.append(" (attempt ").append(attempts + 1).append('/').append(MAX_ATTEMPTS).append(')');
        return sb.toString();
    }

    @Override
    public String toString() {
        if (plan == null) return "no plan";
        Step s = current();
        return "[" + status + "] " + plan.goal() + " — step " + Math.min(index + 1, plan.totalSteps())
                + "/" + plan.totalSteps() + (s == null ? "" : " " + s.oneLine());
    }

    // ------------------------------------------------------------------- parsing

    /**
     * Parse the planner's plan JSON (AGENTS.md §4.1). Tolerant of markdown fences and
     * surrounding prose: the first {@code {} block} is extracted. Throws
     * {@link IllegalArgumentException} with a user-safe message when unusable.
     */
    public static Plan parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("empty planner response");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("no JSON object in the planner response");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(raw.substring(start, end + 1)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid JSON: " + e.getMessage());
        }

        if (root.has("error")) {
            throw new IllegalArgumentException("planner error: " + string(root, "error", "unknown"));
        }
        if (!root.has("steps") || !root.get("steps").isJsonArray()) {
            throw new IllegalArgumentException("missing \"steps\" array");
        }
        JsonArray arr = root.getAsJsonArray("steps");
        if (arr.isEmpty()) {
            throw new IllegalArgumentException("plan has no steps");
        }

        String goal = string(root, "goal", "unnamed goal");
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (el == null || !el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            int stepId = intVal(o, "step_id", i + 1);
            String objective = string(o, "objective", "gather");
            String target = string(o, "target", "");
            int amount = Math.max(0, intVal(o, "amount", 0));
            String exit = string(o, "exit_condition", "");
            steps.add(new Step(stepId, objective, target, amount, exit));
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("plan has no usable steps");
        }
        return new Plan(goal, List.copyOf(steps), raw);
    }

    private static String string(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        String s = e.getAsString();
        return s == null || s.isBlank() ? fallback : s.trim();
    }

    private static int intVal(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        try {
            return (int) Math.round(e.getAsDouble());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    /** Strip a namespace and lowercase: "minecraft:Iron_Ore" → "iron_ore". */
    public static String normalizeId(String id) {
        if (id == null) return "";
        String t = id.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("minecraft:")) t = t.substring("minecraft:".length());
        return t;
    }

    /** Normalized inventory map helper, shared by the controller and future tests. */
    public static Map<String, Integer> newItemMap() {
        return new LinkedHashMap<>();
    }
}
