/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.perception;

import baritone.api.IBaritone;
import baritone.api.utils.IPlayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns the live client world into a compact text snapshot the LLM can reason
 * about. Kept deliberately short — local models choke on giant prompts.
 *
 * Two directions of perception:
 *  - bot → you: messages back into chat (handled by the caller via Helper.logDirect)
 *  - you → bot: chat input (handled by ArionChatHandler)
 *
 * This class only READS the world and formats it.
 */
public final class ArionPerception {

    /** How far out to list entities/blocks, in blocks. */
    private static final double SCAN_RADIUS = 24.0;

    private ArionPerception() {}

    /** A compact one-line-per-fact snapshot of the world around the player. */
    public static String snapshot(IPlayerContext ctx) {
        StringBuilder sb = new StringBuilder(512);
        Level world = ctx.world();
        Player player = ctx.player();
        if (world == null || player == null) {
            return "world unavailable";
        }

        Vec3 pos = player.position();
        sb.append("pos=").append(fmt(pos)).append('\n');
        sb.append("health=").append((int) Math.ceil(player.getHealth())).append("/20")
                .append(" food=").append(player.getFoodData().getFoodLevel()).append("/20").append('\n');
        sb.append("dimension=").append(world.dimension().identifier()).append('\n');
        long dayTime = world.getOverworldClockTime() % 24000L;
        sb.append("time=").append(dayTime < 13000 ? "day" : "night").append('\n');
        if (world.isRaining()) sb.append("weather=rain\n");

        // held item
        ItemStack held = player.getMainHandItem();
        if (!held.isEmpty()) {
            sb.append("holding=").append(held.getHoverName().getString())
                    .append(" x").append(held.getCount()).append('\n');
        }

        // nearby entities, nearest first
        List<Entity> near = ctx.entitiesStream()
                .filter(e -> e != player && e instanceof LivingEntity)
                // When the vessel's Baritone is bound, ctx.player() is the vessel shadow and the
                // vessel's own RemotePlayer is a second entity (same body) near it. The shadow has
                // no usable GameProfile name, so filter by the vessel's UUID/name, not selfName.
                .filter(e -> !isSelfOrVessel(e, player))
                .filter(e -> e.distanceToSqr(player) <= SCAN_RADIUS * SCAN_RADIUS)
                .sorted(Comparator.comparingDouble(e -> e.distanceToSqr(player)))
                .limit(12)
                .toList();
        if (!near.isEmpty()) {
            sb.append("nearby:\n");
            for (Entity e : near) {
                String name = e.getName().getString();
                double d = Math.sqrt(e.distanceToSqr(player));
                sb.append("  - ").append(name);
                if (e instanceof LivingEntity le) {
                    sb.append(" hp=").append((int) Math.ceil(le.getHealth()));
                }
                if (e instanceof Player) sb.append(" (player)");
                sb.append(" dist=").append((int) d).append('\n');
            }
        }
        return sb.toString();
    }

    /** A slightly larger snapshot used for planning (adds inventory summary). */
    public static String plannerSnapshot(IPlayerContext ctx) {
        StringBuilder sb = new StringBuilder(snapshot(ctx));
        Player player = ctx.player();
        if (player != null) {
            List<String> inv = new ArrayList<>();
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack s = player.getInventory().getItem(i);
                if (!s.isEmpty()) {
                    inv.add(s.getHoverName().getString() + " x" + s.getCount());
                }
                if (inv.size() >= 20) break;
            }
            sb.append("inventory=").append(inv.isEmpty() ? "empty" : String.join(", ", inv)).append('\n');
        }
        return sb.toString();
    }

    private static String fmt(Vec3 v) {
        return String.format("%d %d %d", (int) v.x, (int) v.y, (int) v.z);
    }

    /**
     * True for the context player itself or the vessel's own client entity. The vessel shadow is a
     * {@code LocalPlayer} with no usable profile name, so name comparison alone misses its remote
     * counterpart; matching the vessel UUID is reliable.
     */
    public static boolean isSelfOrVessel(Entity e, Player self) {
        if (e == null || self == null) return false;
        if (e == self) return true;
        if (!(e instanceof Player other)) return false;
        if (other.getName().getString().equals(self.getName().getString())) return true;
        var vm = arion.Arion.vessels();
        if (vm != null) {
            if (vm.vesselUuid() != null && other.getUUID().equals(vm.vesselUuid())) return true;
            String name = vm.vesselName();
            if (name != null && other.getName().getString().equals(name)) return true;
        }
        return false;
    }

    /** Distance in whole blocks from the player to a position, or -1 if unavailable. */
    public static int distanceTo(IPlayerContext ctx, BlockPos pos) {
        Player p = ctx.player();
        if (p == null) return -1;
        return (int) Math.sqrt(p.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()));
    }
}
