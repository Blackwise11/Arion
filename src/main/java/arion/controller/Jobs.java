/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.controller;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.IPlayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

/**
 * Concrete {@link Job} implementations, one per Baritone process Arion drives.
 * All start on the client thread; all report progress as distance-to-goal.
 */
public final class Jobs {

    private Jobs() {}

    /** Walk to within a few blocks of a target position. */
    public static final class Goto extends Job {
        private final BlockPos target;

        public Goto(BlockPos target) {
            super("goto " + target.toShortString(), /*timeout*/ 20 * 60 * 5, /*stall*/ 100);
            this.target = target;
        }

        @Override protected void start(IBaritone b) {
            b.getCustomGoalProcess().setGoalAndPath(new GoalNear(target, 3));
        }

        @Override protected void cancel(IBaritone b) {
            b.getCustomGoalProcess().onLostControl();
            b.getPathingBehavior().cancelEverything();
        }

        @Override protected double progressDistance(IPlayerContext ctx) {
            // BetterBlockPos has no distSqr; compute distance manually
            var feet = ctx.playerFeet();
            double dx = feet.getX() - target.getX();
            double dy = feet.getY() - target.getY();
            double dz = feet.getZ() - target.getZ();
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        @Override protected boolean baritoneActive(IBaritone b) {
            return b.getCustomGoalProcess().isActive();
        }
    }

    /** Follow a specific player. */
    public static final class Follow extends Job {
        private final String playerName;

        public Follow(String playerName) {
            super("follow " + playerName, /*timeout*/ 0 /*no timeout*/, /*stall*/ 200);
            this.playerName = playerName;
        }

        @Override protected void start(IBaritone b) {
            b.getFollowProcess().follow(e -> e instanceof Player p
                    && p.getName().getString().equalsIgnoreCase(playerName));
        }

        @Override protected void cancel(IBaritone b) {
            b.getFollowProcess().cancel();
        }

        @Override protected double progressDistance(IPlayerContext ctx) {
            Player target = ctx.world().players().stream()
                    .filter(p -> p.getName().getString().equalsIgnoreCase(playerName))
                    .findFirst().orElse(null);
            if (target == null) return Double.NaN;
            return Math.sqrt(ctx.player().distanceToSqr(target));
        }

        @Override protected boolean baritoneActive(IBaritone b) {
            return b.getFollowProcess().isActive();
        }
    }

    /** Mine blocks by name. */
    public static final class Mine extends Job {
        private final int quantity;
        private final String[] blocks;

        public Mine(int quantity, String... blocks) {
            super("mine " + String.join(",", blocks), /*timeout*/ 20 * 60 * 10, /*stall*/ 100);
            this.quantity = quantity;
            this.blocks = blocks;
        }

        @Override protected void start(IBaritone b) {
            b.getMineProcess().mineByName(quantity, blocks);
        }

        @Override protected void cancel(IBaritone b) {
            b.getMineProcess().cancel();
        }

        @Override protected double progressDistance(IPlayerContext ctx) {
            // mining progress isn't a single distance; stall on the pathing state instead
            return Double.NaN;
        }

        @Override protected boolean baritoneActive(IBaritone b) {
            return b.getMineProcess().isActive();
        }

        /** MineProcess blacklists unreachable targets and retries; one calc failure is not fatal. */
        @Override boolean calcFailureIsFatal() {
            return false;
        }
    }
    /** Explore outward from the current position. */
    public static final class Explore extends Job {
        public Explore() {
            super("explore", /*timeout*/ 20 * 60 * 5, /*stall*/ 200);
        }

        @Override protected void start(IBaritone b) {
            IPlayerContext ctx = b.getPlayerContext();
            int x = ctx.playerFeet().getX();
            int z = ctx.playerFeet().getZ();
            b.getExploreProcess().explore(x, z);
        }

        @Override protected void cancel(IBaritone b) {
            b.getExploreProcess().onLostControl();
        }

        @Override protected double progressDistance(IPlayerContext ctx) {
            return Double.NaN; // exploration has no fixed goal distance
        }

        @Override protected boolean baritoneActive(IBaritone b) {
            return b.getExploreProcess().isActive();
        }

        /** ExploreProcess handles calc failures by re-planning; not fatal. */
        @Override boolean calcFailureIsFatal() {
            return false;
        }
    }

    /** Walk to an X/Z column (compass directions, distant waypoints). */
    public static final class GotoXZ extends Job {
        private final int x, z;

        public GotoXZ(int x, int z) {
            super("goto xz " + x + " " + z, /*timeout*/ 20 * 60 * 5, /*stall*/ 100);
            this.x = x;
            this.z = z;
        }

        @Override protected void start(IBaritone b) {
            b.getCustomGoalProcess().setGoalAndPath(new GoalXZ(x, z));
        }

        @Override protected void cancel(IBaritone b) {
            b.getCustomGoalProcess().onLostControl();
            b.getPathingBehavior().cancelEverything();
        }

        @Override protected double progressDistance(IPlayerContext ctx) {
            var feet = ctx.playerFeet();
            double dx = feet.getX() - x;
            double dz = feet.getZ() - z;
            return Math.sqrt(dx * dx + dz * dz);
        }

        @Override protected boolean baritoneActive(IBaritone b) {
            return b.getCustomGoalProcess().isActive();
        }
    }

    /** Walk to a Y level (e.g. "go to the surface"). */
    public static final class GotoY extends Job {
        private final int y;

        public GotoY(int y) {
            super("goto y " + y, /*timeout*/ 20 * 60 * 3, /*stall*/ 100);
            this.y = y;
        }

        @Override protected void start(IBaritone b) {
            b.getCustomGoalProcess().setGoalAndPath(new GoalYLevel(y));
        }

        @Override protected void cancel(IBaritone b) {
            b.getCustomGoalProcess().onLostControl();
            b.getPathingBehavior().cancelEverything();
        }

        @Override protected double progressDistance(IPlayerContext ctx) {
            return Math.abs(ctx.playerFeet().getY() - y);
        }

        @Override protected boolean baritoneActive(IBaritone b) {
            return b.getCustomGoalProcess().isActive();
        }
    }

    /** Tend/harvest/replant crops in a radius (Baritone FarmProcess). */
    public static final class Farm extends Job {
        private final int range;

        public Farm(int range) {
            super("farm range " + range, /*timeout*/ 20 * 60 * 10, /*stall*/ 100);
            this.range = range;
        }

        @Override protected void start(IBaritone b) {
            b.getFarmProcess().farm(range);
        }

        @Override protected void cancel(IBaritone b) {
            b.getFarmProcess().onLostControl();
            b.getPathingBehavior().cancelEverything();
        }

        @Override protected double progressDistance(IPlayerContext ctx) {
            return Double.NaN; // farming has no single goal distance
        }

        @Override protected boolean baritoneActive(IBaritone b) {
            return b.getFarmProcess().isActive();
        }
    }

    /** Walk to and reach a block so the player can use it (crafting table, chest, bed). */
    public static final class GetTo extends Job {
        private final String blockName;

        public GetTo(String blockName) {
            super("get " + blockName, /*timeout*/ 20 * 60 * 5, /*stall*/ 100);
            this.blockName = blockName;
        }

        @Override protected void start(IBaritone b) {
            b.getGetToBlockProcess().getToBlock(new BlockOptionalMeta(blockName));
        }

        @Override protected void cancel(IBaritone b) {
            b.getGetToBlockProcess().onLostControl();
            b.getPathingBehavior().cancelEverything();
        }

        @Override protected double progressDistance(IPlayerContext ctx) {
            return Double.NaN; // target block location is process-internal
        }

        @Override protected boolean baritoneActive(IBaritone b) {
            return b.getGetToBlockProcess().isActive();
        }
    }

    /** Sweep up dropped items (rides Baritone's follow process with an item predicate). */
    public static final class Pickup extends Job {
        private final String itemName; // empty = any dropped item

        public Pickup(String itemName) {
            this(itemName, 20 * 60 * 5);
        }

        /** Short-timeout variant for the post-mining drop sweep. */
        public Pickup(String itemName, int timeoutTicks) {
            super("pickup" + (itemName.isBlank() ? "" : " " + itemName),
                    /*timeout*/ timeoutTicks, /*stall*/ 200);
            this.itemName = itemName;
        }

        @Override protected void start(IBaritone b) {
            // the pickup predicate is over dropped ItemStacks (empty name = any item)
            b.getFollowProcess().pickup(stack ->
                    itemName.isBlank() || matches(stack));
        }

        private boolean matches(net.minecraft.world.item.ItemStack stack) {
            String want = itemName.toLowerCase().replace(' ', '_');
            String got = stack.getHoverName().getString().toLowerCase().replace(' ', '_');
            return got.contains(want);
        }

        @Override protected void cancel(IBaritone b) {
            b.getFollowProcess().cancel();
        }

        @Override protected double progressDistance(IPlayerContext ctx) {
            return Double.NaN; // items move/despawn; no fixed goal distance
        }

        @Override protected boolean baritoneActive(IBaritone b) {
            return b.getFollowProcess().isActive();
        }

        /** FollowProcess re-paths toward moving items; a calc failure is not fatal. */
        @Override boolean calcFailureIsFatal() {
            return false;
        }
    }

    /** Build a structure from a schematic file in the schematics folder. */
    public static final class Build extends Job {
        private final String schematicName;

        public Build(String schematicName) {
            super("build " + schematicName, /*timeout*/ 0 /*long-running*/, /*stall*/ 400);
            this.schematicName = schematicName;
        }

        @Override protected void start(IBaritone b) {
            java.io.File dir = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("schematics").toFile();
            java.io.File file = new java.io.File(dir, schematicName);
            if (!file.exists()) {
                // try common schematic extensions
                java.io.File alt = null;
                for (String ext : new String[]{".schematic", ".litematic", ".schem", ".nbt"}) {
                    java.io.File cand = new java.io.File(dir, schematicName + ext);
                    if (cand.exists()) { alt = cand; break; }
                }
                if (alt != null) file = alt;
            }
            if (!file.exists()) {
                throw new IllegalStateException("no schematic named '" + schematicName
                        + "' in the schematics folder");
            }
            BlockPos origin = b.getPlayerContext().playerFeet();
            b.getBuilderProcess().build(schematicName, file, origin);
        }

        @Override protected void cancel(IBaritone b) {
            b.getBuilderProcess().onLostControl();
            b.getPathingBehavior().cancelEverything();
        }

        @Override protected double progressDistance(IPlayerContext ctx) {
            return Double.NaN; // building has no single goal distance
        }

        @Override protected boolean baritoneActive(IBaritone b) {
            return b.getBuilderProcess().isActive();
        }
    }

    /** Level (remove all blocks in) a rectangular region. Destructive. */
    public static final class Clear extends Job {
        private final BlockPos a, bpos;

        public Clear(BlockPos a, BlockPos bpos) {
            super("clear region", /*timeout*/ 20 * 60 * 20, /*stall*/ 200);
            this.a = a;
            this.bpos = bpos;
        }

        @Override protected void start(IBaritone b) {
            b.getBuilderProcess().clearArea(a, bpos);
        }

        @Override protected void cancel(IBaritone b) {
            b.getBuilderProcess().onLostControl();
            b.getPathingBehavior().cancelEverything();
        }

        @Override protected double progressDistance(IPlayerContext ctx) {
            return Double.NaN;
        }

        @Override protected boolean baritoneActive(IBaritone b) {
            return b.getBuilderProcess().isActive();
        }
    }
}
