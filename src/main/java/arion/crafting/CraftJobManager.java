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
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Executes a planned crafting/smelting job against the real vanilla machinery.
 *
 * <ul>
 *   <li><b>2×2 crafts</b> go through the player's {@link InventoryMenu} crafting grid (slots 1–4).</li>
 *   <li><b>3×3 crafts</b> open a real {@link CraftingMenu} on a placed/adjacent crafting table
 *       (slots 1–9) and take the result slot.</li>
 *   <li><b>Smelting</b> inserts the planned input/fuel into a real {@code FurnaceBlockEntity}
 *       (slots 0/1) and waits for vanilla cooking to fill slot 2, then extracts it.</li>
 * </ul>
 *
 * <p>One job per server at a time. The controller drives {@link #tick(MinecraftServer)} from the
 * client tick via {@code server.execute}; every method here runs on the <b>server thread</b>.
 */
public final class CraftJobManager {

    private static final Map<MinecraftServer, Job> JOBS = new ConcurrentHashMap<>();
    private static final int MAX_OPS_PER_TICK = 16;
    private static final long TIMEOUT_TICKS = 20L * 60 * 20; // 20 minutes

    private static final class Job {
        final ServerPlayer body;
        final ServerLevel level;
        final CraftingEngine.Plan plan;
        final Consumer<CraftingEngine.Result> callback;
        final long deadline;
        int index;
        boolean waiting;
        BlockPos furnacePos;
        ItemStack smeltExpected;
        int smeltCount;
        long lastTick = -1;
        String failure;

        Job(ServerPlayer body, ServerLevel level, CraftingEngine.Plan plan,
            Consumer<CraftingEngine.Result> callback, long deadline) {
            this.body = body;
            this.level = level;
            this.plan = plan;
            this.callback = callback;
            this.deadline = deadline;
        }
    }

    private CraftJobManager() {}

    /** True while a job is active. Safe to read from the client thread. */
    public static boolean isBusy(MinecraftServer server) {
        return server != null && JOBS.containsKey(server);
    }

    /**
     * Submit a job. <b>Server thread only.</b>
     *
     * @param callback invoked on the server thread exactly once (wrap with {@code mc.execute}
     *                 before touching client state)
     */
    public static void submit(MinecraftServer server, ServerPlayer body, String item, int amount,
                              boolean placeStations, Consumer<CraftingEngine.Result> callback) {
        if (server == null || body == null) {
            callback.accept(new CraftingEngine.Result(false, "no server/body available"));
            return;
        }
        if (JOBS.containsKey(server)) {
            callback.accept(new CraftingEngine.Result(false, "another craft is already running"));
            return;
        }
        CraftingEngine.Plan plan = CraftingEngine.plan(body, item, amount, placeStations);
        if (!plan.success()) {
            callback.accept(new CraftingEngine.Result(false, plan.message()));
            return;
        }
        if (plan.ops().isEmpty()) {
            callback.accept(new CraftingEngine.Result(true, plan.message()));
            return;
        }
        Job job = new Job(body, (ServerLevel) body.level(), plan, callback,
                server.getTickCount() + TIMEOUT_TICKS);
        JOBS.put(server, job);
    }

    /** Advance the active job. Server thread only; called from the client tick via server.execute. */
    public static void tick(MinecraftServer server) {
        Job job = JOBS.get(server);
        if (job == null) return;
        try {
            if (job.lastTick == server.getTickCount()) return;
            job.lastTick = server.getTickCount();

            if (server.getTickCount() > job.deadline) {
                finish(server, job, fail("craft timed out"));
                return;
            }
            if (!job.body.isAlive() || job.body.isRemoved() || job.body.level() != job.level) {
                finish(server, job, fail("body unavailable"));
                return;
            }

            if (job.waiting) {
                if (!pollFurnace(job)) {
                    if (job.failure != null) finish(server, job, fail(job.failure));
                    return;
                }
            }

            int guard = 0;
            while (job.index < job.plan.ops().size() && !job.waiting
                    && guard++ < MAX_OPS_PER_TICK) {
                CraftingEngine.Op op = job.plan.ops().get(job.index);
                if (!(op instanceof CraftingEngine.CraftOp)) {
                    closeMenu(job);
                }
                boolean ok;
                if (op instanceof CraftingEngine.PlaceOp place) {
                    ok = placeStation(job, place);
                } else if (op instanceof CraftingEngine.CraftOp craft) {
                    ok = craft(job, craft);
                } else if (op instanceof CraftingEngine.SmeltOp smelt) {
                    ok = startSmelt(job, smelt);
                } else {
                    job.failure = "unknown crafting op";
                    ok = false;
                }
                if (!ok) {
                    finish(server, job, fail(job.failure != null ? job.failure : "craft failed"));
                    return;
                }
                job.index++;
            }

            if (job.index >= job.plan.ops().size() && !job.waiting) {
                closeMenu(job);
                job.body.getInventory().setChanged();
                job.body.containerMenu.broadcastChanges();
                int have = CraftingEngine.count(job.body, job.plan.target());
                if (have >= job.plan.amount()) {
                    finish(server, job, new CraftingEngine.Result(true,
                            "produced " + job.plan.amount() + "x "
                                    + CraftingEngine.name(job.plan.target())
                                    + " with real crafting/furnace steps"));
                } else {
                    finish(server, job, fail("craft finished with " + have + "/" + job.plan.amount()));
                }
            }
        } catch (Throwable t) {
            finish(server, job, fail("craft error: " + t));
        }
    }

    /** Cancel the active job (player stop / plan cancel). Server thread only. */
    public static void cancel(MinecraftServer server) {
        Job job = JOBS.remove(server);
        if (job != null) closeMenu(job);
    }

    /** Drop all state when the server stops. */
    public static void shutdown(MinecraftServer server) {
        Job job = JOBS.remove(server);
        if (job != null) closeMenu(job);
    }

    // ------------------------------------------------------------------ ops

    private static boolean placeStation(Job job, CraftingEngine.PlaceOp op) {
        boolean placed = CraftingEngine.placeStation(job.level, job.body, op.block());
        if (!placed) placed = clearPocketAndPlace(job, op.block());
        if (!placed) {
            job.failure = "no space to place " + CraftingEngine.name(op.item());
            return false;
        }
        if (!CraftingEngine.consume(job.body, op.item(), 1)) {
            job.failure = "missing " + CraftingEngine.name(op.item()) + " to place";
            return false;
        }
        // A furnace placed with setBlock may not have its block entity until the chunk is
        // re-created; register it explicitly so the real furnace can tick.
        if (op.block() instanceof EntityBlock entityBlock) {
            BlockPos placedPos = findPlaced(job.level, job.body.blockPosition(), op.block());
            if (placedPos != null && job.level.getBlockEntity(placedPos) == null) {
                BlockEntity be = entityBlock.newBlockEntity(placedPos, job.level.getBlockState(placedPos));
                if (be != null) job.level.getChunkAt(placedPos).addAndRegisterBlockEntity(be);
            }
        }
        return true;
    }

    /**
     * The body can be in a 1x1 mining tunnel where no adjacent block is free (the live log hit
     * "no space to place crafting_table"). Dig a side pocket with the body's own game mode, then
     * retry placement. Station setup only; never used for normal mining.
     */
    private static boolean clearPocketAndPlace(Job job, net.minecraft.world.level.block.Block block) {
        BlockPos base = job.body.blockPosition();
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : offsets) {
            for (int dy : new int[]{0, 1}) {
                BlockPos pos = base.offset(o[0], dy, o[1]);
                if (pos.equals(base)) continue;
                var state = job.level.getBlockState(pos);
                if (state.isAir() || !state.getFluidState().isEmpty()) continue;
                if (job.level.getBlockEntity(pos) != null) continue; // don't destroy containers
                if (state.getDestroySpeed(job.level, pos) < 0) continue; // unbreakable
                try {
                    job.body.gameMode.destroyBlock(pos);
                } catch (Throwable ignored) {}
                if (CraftingEngine.placeStation(job.level, job.body, block)) return true;
            }
        }
        return false;
    }

    private static BlockPos findPlaced(ServerLevel level, BlockPos center, net.minecraft.world.level.block.Block block) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (level.getBlockState(pos).is(block)) return pos;
                }
            }
        }
        return null;
    }

    private static boolean craft(Job job, CraftingEngine.CraftOp op) {
        boolean table = op.needsTable();
        AbstractContainerMenu menu;
        int gridBase = 1;
        int gridSlots = op.grid().length;

        if (table) {
            BlockPos tablePos = CraftingEngine.findNearbyBlockPos(
                    job.level, job.body.blockPosition(), Blocks.CRAFTING_TABLE, 4);
            if (tablePos == null) {
                job.failure = "no crafting table nearby";
                return false;
            }
            if (!(job.body.containerMenu instanceof CraftingMenu)) {
                job.body.openMenu(new SimpleMenuProvider((id, inv, p) ->
                        new CraftingMenu(id, inv, ContainerLevelAccess.create(job.level, tablePos)),
                        Component.literal("Crafting")));
            }
            if (!(job.body.containerMenu instanceof CraftingMenu craftingMenu)) {
                job.failure = "could not open the crafting table menu";
                return false;
            }
            menu = craftingMenu;
        } else {
            if (!(job.body.containerMenu instanceof InventoryMenu)) {
                job.body.closeContainer();
            }
            menu = job.body.inventoryMenu;
        }

        // Clear anything left in the grid so the recipe is exact.
        for (int i = 0; i < gridSlots; i++) {
            var slot = menu.getSlot(gridBase + i);
            if (!slot.getItem().isEmpty()) {
                CraftingEngine.add(job.body, slot.getItem().copy());
                slot.set(ItemStack.EMPTY);
            }
        }
        for (int i = 0; i < gridSlots; i++) {
            menu.getSlot(gridBase + i).set(op.grid()[i].copy());
        }

        ItemStack result = menu.getSlot(0).getItem();
        if (result.isEmpty() || !result.is(op.expected().getItem())
                || result.getCount() != op.expected().getCount()) {
            job.failure = "recipe mismatch: expected " + op.expected().getCount() + "x "
                    + CraftingEngine.name(op.expected().getItem())
                    + (result.isEmpty() ? " (no result)"
                            : " got " + result.getCount() + "x " + CraftingEngine.name(result.getItem()));
            clearGrid(job, menu, gridBase, gridSlots);
            return false;
        }

        menu.clicked(0, 0, ContainerInput.QUICK_MOVE, job.body);
        if (!menu.getSlot(0).getItem().isEmpty()) {
            job.failure = "could not take the crafted result (inventory full?)";
            clearGrid(job, menu, gridBase, gridSlots);
            return false;
        }
        clearGrid(job, menu, gridBase, gridSlots);
        job.body.containerMenu.broadcastChanges();
        return true;
    }

    private static void clearGrid(Job job, AbstractContainerMenu menu, int base, int count) {
        for (int i = 0; i < count; i++) {
            var slot = menu.getSlot(base + i);
            if (!slot.getItem().isEmpty()) {
                CraftingEngine.add(job.body, slot.getItem().copy());
                slot.set(ItemStack.EMPTY);
            }
        }
    }

    private static boolean startSmelt(Job job, CraftingEngine.SmeltOp op) {
        BlockPos pos = CraftingEngine.findNearbyBlockPos(
                job.level, job.body.blockPosition(), Blocks.FURNACE, 4);
        if (pos == null) {
            job.failure = "no furnace nearby";
            return false;
        }
        BlockEntity be = job.level.getBlockEntity(pos);
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) {
            job.failure = "furnace block entity missing";
            return false;
        }
        if (!furnace.getItem(2).isEmpty()) {
            job.failure = "furnace output slot is occupied";
            return false;
        }
        furnace.setItem(0, op.input().copy());
        furnace.setItem(1, new ItemStack(op.fuel().getItem(), op.fuelCount()));
        job.waiting = true;
        job.furnacePos = pos;
        job.smeltExpected = op.expected().copy();
        job.smeltCount = op.expectedCount();
        return true;
    }

    /** @return true when the smelt completed and the job may continue */
    private static boolean pollFurnace(Job job) {
        BlockEntity be = job.level.getBlockEntity(job.furnacePos);
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) {
            job.failure = "furnace disappeared";
            return false;
        }
        ItemStack out = furnace.getItem(2);
        if (!out.isEmpty() && out.is(job.smeltExpected.getItem())
                && out.getCount() >= job.smeltCount) {
            ItemStack take = out.copy();
            take.setCount(job.smeltCount);
            out.shrink(job.smeltCount);
            furnace.setItem(2, out.isEmpty() ? ItemStack.EMPTY : out);
            if (!CraftingEngine.add(job.body, take)) {
                job.failure = "inventory full while collecting smelted output";
                return false;
            }
            job.waiting = false;
            job.furnacePos = null;
            return true;
        }
        boolean lit = job.level.getBlockState(job.furnacePos).getValue(AbstractFurnaceBlock.LIT);
        ItemStack input = furnace.getItem(0);
        if (!lit && input.isEmpty() && out.getCount() < job.smeltCount) {
            job.failure = "furnace stopped with " + out.getCount() + "/" + job.smeltCount;
            return false;
        }
        return false;
    }

    // ---------------------------------------------------------------- helpers

    private static void closeMenu(Job job) {
        try {
            if (job.body.containerMenu instanceof CraftingMenu) {
                job.body.closeContainer();
            }
        } catch (Throwable ignored) {}
    }

    private static void finish(MinecraftServer server, Job job, CraftingEngine.Result result) {
        JOBS.remove(server, job);
        closeMenu(job);
        try {
            job.callback.accept(result);
        } catch (Throwable ignored) {}
    }

    private static CraftingEngine.Result fail(String message) {
        return new CraftingEngine.Result(false, message);
    }
}
