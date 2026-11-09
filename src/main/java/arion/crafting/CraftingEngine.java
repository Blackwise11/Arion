/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.crafting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Recipe-graph planner for M3. It reads the server's real {@link RecipeManager}, resolves a target
 * recursively from the body's inventory, and emits a concrete {@link Op} sequence:
 *
 * <ul>
 *   <li>{@link CraftOp} — a real grid layout for a {@code CraftingMenu}/{@code InventoryMenu} craft</li>
 *   <li>{@link SmeltOp} — real input/fuel stacks for a {@link net.minecraft.world.level.block.entity.FurnaceBlockEntity}</li>
 *   <li>{@link PlaceOp} — place a crafting table/furnace station</li>
 * </ul>
 *
 * <p>{@link CraftJobManager} executes the ops on the server thread using the actual menus and
 * furnace block entity. Planning is side-effect free: candidates work on a simulated inventory and
 * only the successful plan is returned.
 */
public final class CraftingEngine {

    public record Result(boolean success, String message) {}

    public sealed interface Op permits CraftOp, SmeltOp, PlaceOp {}

    /** A real grid craft. Grid is 4 slots (2×2 inventory) or 9 slots (3×3 table). */
    public record CraftOp(ItemStack[] grid, ItemStack expected, boolean needsTable) implements Op {}

    /** Insert these stacks into a real furnace and wait for the expected output. */
    public record SmeltOp(ItemStack input, ItemStack fuel, int fuelCount,
                          int expectedCount, ItemStack expected, int cookingTime) implements Op {}

    /** Place a station block from the inventory. */
    public record PlaceOp(Block block, Item item) implements Op {}

    /** A planned job. {@code ops} is empty when the target is already in the inventory. */
    public record Plan(boolean success, String message, Item target, int amount, List<Op> ops) {}

    private static final int MAX_DEPTH = 8;
    private static final int MAX_OPS = 512;
    private static final int MAX_SMELT_BATCH = 32;

    // ---------------------------------------------------------------- recipe index

    private record CraftCandidate(ItemStack result, List<Ingredient> ingredients, boolean needsTable,
                                  int width, int height, List<Optional<Ingredient>> pattern,
                                  boolean shapeless) {}

    private record SmeltCandidate(ItemStack result, Ingredient input, int cookingTime) {}

    private record Index(Map<Item, List<CraftCandidate>> crafts,
                         Map<Item, List<SmeltCandidate>> smelts) {}

    private static final Map<RecipeManager, Index> CACHE = new WeakHashMap<>();

    private static final ThreadLocal<ServerLevel> LEVEL = new ThreadLocal<>();
    private static final ThreadLocal<ServerPlayer> BODY = new ThreadLocal<>();
    private static final ThreadLocal<Index> INDEX = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PLACE_STATIONS = ThreadLocal.withInitial(() -> Boolean.TRUE);

    private static Index index(ServerLevel level) {
        RecipeManager manager = level.recipeAccess();
        synchronized (CACHE) {
            Index cached = CACHE.get(manager);
            if (cached != null) return cached;
            Index built = buildIndex(level, manager);
            CACHE.put(manager, built);
            return built;
        }
    }

    private static Index buildIndex(ServerLevel level, RecipeManager manager) {
        Map<Item, List<CraftCandidate>> crafts = new HashMap<>();
        Map<Item, List<SmeltCandidate>> smelts = new HashMap<>();
        var context = SlotDisplayContext.fromLevel(level);

        for (RecipeHolder<?> holder : manager.getRecipes()) {
            Recipe<?> recipe = holder.value();
            try {
                if (recipe.getType() == RecipeType.CRAFTING) {
                    List<RecipeDisplay> displays = recipe.display();
                    if (displays.isEmpty()) continue;
                    RecipeDisplay display = displays.getFirst();
                    ItemStack result = display.result().resolveForFirstStack(context);
                    if (result.isEmpty()) continue;
                    if (recipe.placementInfo().isImpossibleToPlace()) continue;
                    List<Ingredient> ingredients = recipe.placementInfo().ingredients();
                    if (ingredients.isEmpty()) continue;

                    if (recipe instanceof ShapedRecipe shaped) {
                        int width = shaped.getWidth();
                        int height = shaped.getHeight();
                        List<Optional<Ingredient>> pattern = shaped.getIngredients();
                        boolean needsTable = width > 2 || height > 2;
                        crafts.computeIfAbsent(result.getItem(), k -> new ArrayList<>())
                                .add(new CraftCandidate(result.copy(), List.copyOf(ingredients),
                                        needsTable, width, height, pattern, false));
                    } else {
                        boolean needsTable = ingredients.size() > 4;
                        crafts.computeIfAbsent(result.getItem(), k -> new ArrayList<>())
                                .add(new CraftCandidate(result.copy(), List.copyOf(ingredients),
                                        needsTable, 0, 0, null, true));
                    }
                } else if (recipe.getType() == RecipeType.SMELTING
                        && recipe instanceof AbstractCookingRecipe cooking) {
                    Ingredient input = cooking.input();
                    Item inputItem = firstPossibleItem(input);
                    if (inputItem == null) continue;
                    ItemStack result = cooking.assemble(new SingleRecipeInput(new ItemStack(inputItem)));
                    if (result.isEmpty()) continue;
                    smelts.computeIfAbsent(result.getItem(), k -> new ArrayList<>())
                            .add(new SmeltCandidate(result.copy(), input, cooking.cookingTime()));
                }
            } catch (Throwable ignored) {
                // one malformed/datapack recipe must not break the whole index
            }
        }
        for (List<CraftCandidate> list : crafts.values()) {
            list.sort((a, b) -> {
                if (a.needsTable != b.needsTable) return a.needsTable ? 1 : -1;
                return Integer.compare(a.ingredients.size(), b.ingredients.size());
            });
        }
        return new Index(crafts, smelts);
    }

    // ------------------------------------------------------------------- planning

    private static final class Ctx {
        final Map<Item, Integer> sim;
        final List<Op> ops;
        final Set<Block> placed;
        String missing;

        Ctx(Map<Item, Integer> sim) {
            this.sim = sim;
            this.ops = new ArrayList<>();
            this.placed = new HashSet<>();
        }

        Ctx copy() {
            Ctx c = new Ctx(new HashMap<>(sim));
            c.ops.addAll(ops);
            c.placed.addAll(placed);
            c.missing = missing;
            return c;
        }

        void commit(Ctx other) {
            sim.clear();
            sim.putAll(other.sim);
            ops.clear();
            ops.addAll(other.ops);
            placed.clear();
            placed.addAll(other.placed);
            missing = other.missing;
        }
    }

    /**
     * Plan a target using the body's current inventory. Server thread only.
     * Never mutates the inventory or the level.
     */
    public static Plan plan(ServerPlayer player, String itemId, int amount, boolean placeStations) {
        Item target = resolveItem(itemId);
        if (target == null) return new Plan(false, "unknown item: " + itemId, null, amount, List.of());
        if (amount <= 0) return new Plan(false, "amount must be > 0", target, amount, List.of());

        ServerLevel level = (ServerLevel) player.level();
        LEVEL.set(level);
        BODY.set(player);
        PLACE_STATIONS.set(placeStations);
        try {
            Index index = index(level);
            INDEX.set(index);
            int have = count(player, target);
            if (have >= amount) {
                return new Plan(true, "already have " + have + "x " + name(target), target, amount, List.of());
            }

            Ctx ctx = new Ctx(inventoryCounts(player));
            if (!planProduce(target, amount, ctx, 0, new HashSet<>())) {
                return new Plan(false, "missing: " + (ctx.missing != null ? ctx.missing : name(target)),
                        target, amount, List.of());
            }
            return new Plan(true, "planned " + ctx.ops.size() + " step(s)", target, amount,
                    List.copyOf(ctx.ops));
        } catch (Throwable t) {
            return new Plan(false, "planning error: " + t, target, amount, List.of());
        } finally {
            LEVEL.remove();
            BODY.remove();
            INDEX.remove();
            PLACE_STATIONS.remove();
        }
    }

    private static boolean planProduce(Item target, int amount, Ctx ctx, int depth, Set<Item> visiting) {
        int have = ctx.sim.getOrDefault(target, 0);
        if (have >= amount) return true;
        if (depth > MAX_DEPTH || ctx.ops.size() > MAX_OPS || visiting.contains(target)) {
            ctx.missing = name(target);
            return false;
        }

        Index index = INDEX.get();
        if (index == null) {
            ctx.missing = "recipe index unavailable";
            return false;
        }

        Set<Item> nextVisiting = new HashSet<>(visiting);
        nextVisiting.add(target);

        String deepMissing = null;
        for (CraftCandidate candidate : index.crafts.getOrDefault(target, List.of())) {
            Ctx trial = ctx.copy();
            if (applyCraft(target, amount, candidate, trial, depth, nextVisiting)) {
                ctx.commit(trial);
                return true;
            }
            if (trial.missing != null) deepMissing = trial.missing;
        }
        for (SmeltCandidate candidate : index.smelts.getOrDefault(target, List.of())) {
            Ctx trial = ctx.copy();
            if (applySmelt(target, amount, candidate, trial, depth, nextVisiting)) {
                ctx.commit(trial);
                return true;
            }
            if (trial.missing != null) deepMissing = trial.missing;
        }

        ctx.missing = deepMissing != null ? deepMissing : name(target);
        return false;
    }

    private static boolean applyCraft(Item target, int amount, CraftCandidate candidate,
                                      Ctx ctx, int depth, Set<Item> visiting) {
        int output = candidate.result.getCount();
        if (output <= 0) return false;
        if (candidate.needsTable && !ensureStation(ctx, Blocks.CRAFTING_TABLE)) return false;

        int need = amount - ctx.sim.getOrDefault(target, 0);
        int crafts = (need + output - 1) / output;
        for (int craft = 0; craft < crafts; craft++) {
            List<Item> chosen = new ArrayList<>(candidate.ingredients.size());
            for (Ingredient ingredient : candidate.ingredients) {
                Item item = chooseIngredient(ingredient, ctx, depth, visiting);
                if (item == null) {
                    ctx.missing = "an ingredient of " + name(target);
                    return false;
                }
                ctx.sim.merge(item, -1, Integer::sum);
                chosen.add(item);
            }
            ItemStack[] grid = buildGrid(candidate, chosen);
            ctx.ops.add(new CraftOp(grid, candidate.result.copy(), candidate.needsTable));
            ctx.sim.merge(target, output, Integer::sum);
        }
        return true;
    }

    private static ItemStack[] buildGrid(CraftCandidate candidate, List<Item> chosen) {
        ItemStack[] grid = new ItemStack[candidate.needsTable ? 9 : 4];
        Arrays.fill(grid, ItemStack.EMPTY);
        if (candidate.shapeless) {
            for (int i = 0; i < chosen.size() && i < grid.length; i++) {
                grid[i] = new ItemStack(chosen.get(i));
            }
            return grid;
        }
        int k = 0;
        for (int i = 0; i < candidate.pattern.size(); i++) {
            if (candidate.pattern.get(i).isEmpty()) continue;
            int x = i % candidate.width;
            int y = i / candidate.width;
            int gi = x + y * (candidate.needsTable ? 3 : 2);
            if (gi >= 0 && gi < grid.length && k < chosen.size()) {
                grid[gi] = new ItemStack(chosen.get(k));
            }
            k++;
        }
        return grid;
    }

    private static boolean applySmelt(Item target, int amount, SmeltCandidate candidate,
                                      Ctx ctx, int depth, Set<Item> visiting) {
        int output = candidate.result.getCount();
        if (output <= 0) return false;
        if (!ensureStation(ctx, Blocks.FURNACE)) return false;

        int need = amount - ctx.sim.getOrDefault(target, 0);
        int remaining = (need + output - 1) / output;
        while (remaining > 0) {
            int batch = Math.min(remaining, MAX_SMELT_BATCH);
            Item input = chooseIngredient(candidate.input, ctx, depth, visiting);
            if (input == null) {
                ctx.missing = "an input for " + name(target);
                return false;
            }
            if (!planProduce(input, batch, ctx, depth + 1, visiting)) {
                ctx.missing = name(input);
                return false;
            }
            ctx.sim.merge(input, -batch, Integer::sum);
            FuelChoice fuel = chooseFuel(ctx, batch * Math.max(1, candidate.cookingTime), depth, visiting);
            if (fuel == null) {
                ctx.missing = "fuel";
                return false;
            }
            ctx.sim.merge(fuel.item, -fuel.count, Integer::sum);
            int out = batch * output;
            ctx.sim.merge(target, out, Integer::sum);
            ctx.ops.add(new SmeltOp(new ItemStack(input, batch),
                    new ItemStack(fuel.item), fuel.count, out, candidate.result.copy(),
                    candidate.cookingTime));
            remaining -= batch;
        }
        return true;
    }

    private record FuelChoice(Item item, int count) {}

    private static FuelChoice chooseFuel(Ctx ctx, int totalTicks, int depth, Set<Item> visiting) {
        List<Item> candidates = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : ctx.sim.entrySet()) {
            if (e.getValue() > 0) candidates.add(e.getKey());
        }
        for (Item common : List.of(net.minecraft.world.item.Items.COAL,
                net.minecraft.world.item.Items.CHARCOAL,
                net.minecraft.world.item.Items.OAK_PLANKS,
                net.minecraft.world.item.Items.OAK_LOG,
                net.minecraft.world.item.Items.STICK)) {
            if (!candidates.contains(common)) candidates.add(common);
        }

        ServerLevel level = LEVEL.get();
        if (level == null) return null;
        var fuelValues = level.fuelValues();
        FuelChoice best = null;
        Ctx bestPlan = null;
        for (Item item : candidates) {
            int burn = fuelValues.burnDuration(new ItemStack(item));
            if (burn <= 0) continue;
            int count = (totalTicks + burn - 1) / burn;
            if (count <= 0) continue;
            if (best != null && count >= best.count) continue;
            Ctx trial = ctx.copy();
            if (!planProduce(item, count, trial, depth + 1, visiting)) continue;
            best = new FuelChoice(item, count);
            bestPlan = trial;
        }
        if (best == null || bestPlan == null) return null;
        ctx.commit(bestPlan);
        return best;
    }

    private static boolean ensureStation(Ctx ctx, Block station) {
        if (ctx.placed.contains(station)) return true;
        ServerLevel level = LEVEL.get();
        ServerPlayer body = BODY.get();
        if (level == null || body == null) return false;
        if (findNearbyBlockPos(level, body.blockPosition(), station, 4) != null) return true;
        if (!PLACE_STATIONS.get()) {
            ctx.missing = "no nearby " + name(station.asItem()) + " (station placement disabled)";
            return false;
        }

        Item stationItem = station.asItem();
        if (ctx.sim.getOrDefault(stationItem, 0) < 1) {
            if (!planProduce(stationItem, 1, ctx, 1, new HashSet<>())) return false;
        }
        if (ctx.sim.getOrDefault(stationItem, 0) < 1) return false;
        ctx.sim.merge(stationItem, -1, Integer::sum);
        ctx.ops.add(new PlaceOp(station, stationItem));
        ctx.placed.add(station);
        return true;
    }

    private static Item chooseIngredient(Ingredient ingredient, Ctx ctx, int depth, Set<Item> visiting) {
        Item best = null;
        int bestCount = 0;
        for (Map.Entry<Item, Integer> e : ctx.sim.entrySet()) {
            if (e.getValue() <= 0) continue;
            if (!ingredient.test(new ItemStack(e.getKey()))) continue;
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        if (best != null) return best;

        String deepMissing = null;
        for (Item possible : possibleItems(ingredient)) {
            Ctx trial = ctx.copy();
            if (planProduce(possible, 1, trial, depth + 1, visiting)) {
                ctx.commit(trial);
                return possible;
            }
            if (trial.missing != null) deepMissing = trial.missing;
        }
        if (deepMissing != null) ctx.missing = deepMissing;
        return null;
    }

    private static List<Item> possibleItems(Ingredient ingredient) {
        Set<Item> items = new LinkedHashSet<>();
        ingredient.items().map(Holder::value).limit(32).forEach(items::add);
        return new ArrayList<>(items);
    }

    private static Item firstPossibleItem(Ingredient ingredient) {
        List<Item> items = possibleItems(ingredient);
        return items.isEmpty() ? null : items.getFirst();
    }

    // --------------------------------------------------------- shared helpers

    public static Item resolveItem(String id) {
        if (id == null || id.isBlank()) return null;
        String s = id.trim().toLowerCase(Locale.ROOT);
        if (!s.contains(":")) s = "minecraft:" + s;
        Identifier key = Identifier.tryParse(s);
        if (key == null) return null;
        return BuiltInRegistries.ITEM.getOptional(key).orElse(null);
    }

    static String name(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "unknown" : id.toString();
    }

    static int count(ServerPlayer player, Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty() && stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    static Map<Item, Integer> inventoryCounts(ServerPlayer player) {
        Map<Item, Integer> counts = new HashMap<>();
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) continue;
            counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    static boolean consume(ServerPlayer player, Item item, int count) {
        int remaining = count;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty() || !stack.is(item)) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
            if (remaining <= 0) break;
        }
        return remaining <= 0;
    }

    static boolean add(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return true;
        if (player.getInventory().add(stack)) return true;
        player.drop(stack, false);
        return false;
    }

    static ServerPlayer body() {
        return BODY.get();
    }

    static ServerLevel level() {
        return LEVEL.get();
    }

    static BlockPos findNearbyBlockPos(ServerLevel level, BlockPos center, Block block, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (level.getBlockState(pos).is(block)) return pos;
                }
            }
        }
        return null;
    }

    static boolean placeStation(ServerLevel level, ServerPlayer player, Block block) {
        BlockPos base = player.blockPosition();
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {0, 0}};
        for (int[] o : offsets) {
            for (int dy : new int[]{0, 1, -1}) {
                BlockPos pos = base.offset(o[0], dy, o[1]);
                if (pos.equals(base)) continue;
                BlockState state = level.getBlockState(pos);
                if (!state.isAir() && !state.canBeReplaced()) continue;
                if (level.getBlockState(pos.below()).isAir()) continue;
                level.setBlock(pos, block.defaultBlockState(), 3);
                return true;
            }
        }
        return false;
    }

    public static String describe(ServerPlayer player, String itemId) {
        Item target = resolveItem(itemId);
        if (target == null) return "unknown item: " + itemId;
        ServerLevel level = (ServerLevel) player.level();
        LEVEL.set(level);
        try {
            Index index = index(level);
            List<CraftCandidate> crafts = index.crafts.getOrDefault(target, List.of());
            List<SmeltCandidate> smelts = index.smelts.getOrDefault(target, List.of());
            if (crafts.isEmpty() && smelts.isEmpty()) {
                return name(target) + ": no crafting/smelting recipe (gather it)";
            }
            StringBuilder sb = new StringBuilder(name(target));
            if (!crafts.isEmpty()) {
                CraftCandidate c = crafts.getFirst();
                sb.append(" | craft: ");
                List<String> parts = new ArrayList<>();
                for (Ingredient ing : c.ingredients) parts.add(ingredientLabel(ing));
                sb.append(String.join(" + ", parts));
                sb.append(c.needsTable ? " (3x3 table)" : " (2x2 inventory)");
            }
            if (!smelts.isEmpty()) {
                SmeltCandidate s = smelts.getFirst();
                sb.append(" | smelt: ").append(ingredientLabel(s.input))
                        .append(" (").append(s.cookingTime).append("t)");
            }
            return sb.toString();
        } finally {
            LEVEL.remove();
        }
    }

    private static String ingredientLabel(Ingredient ingredient) {
        List<Item> items = possibleItems(ingredient);
        if (items.isEmpty()) return "?";
        if (items.size() == 1) return name(items.getFirst());
        return name(items.getFirst()) + " (+" + (items.size() - 1) + " variants)";
    }
}
