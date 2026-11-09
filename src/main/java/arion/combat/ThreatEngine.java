/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.combat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Deterministic projectile threat scan (M4), modelled on Baritone's battle-tested
 * {@code ProjectileThreat} (which only runs while Baritone's CombatProcess is active).
 *
 * <p>It consumes the same client entity stream Baritone uses ({@code ctx.entitiesStream()}), does
 * a tick-by-tick step simulation (drag 0.99, gravity 0.05 — matching arrows), and ignores:
 * <ul>
 *   <li>our own projectiles (owner UUID),</li>
 *   <li>resting/landed projectiles (velocity &lt; 0.2 b/t),</li>
 *   <li>projectiles already inside the box but receding (post-impact),</li>
 *   <li>harmless throwables (fishing hooks, snowballs, eggs, ender pearls).</li>
 * </ul>
 *
 * <p>No LLM, no blocking network calls — a Layer 0 reflex on the client thread.
 */
public final class ThreatEngine {

    /** {@code impactTicks} is the first tick the predicted path enters the body box. */
    public record Threat(Entity projectile, int impactTicks, double distance) {
        public String label() {
            return projectile.getName().getString() + " in " + impactTicks + "t";
        }
    }

    private static final int LOOKAHEAD = 30;
    private static final double MIN_SPEED_SQR = 0.04; // ~0.2 b/tick: landed projectiles are not threats

    /**
     * @param entities the client entity stream (Baritone's {@code ctx.entitiesStream()})
     * @param range    scan radius in blocks
     * @return the most urgent projectile threat, or null
     */
    public Threat scan(Player player, Iterable<Entity> entities, double range) {
        if (player == null || entities == null) return null;
        UUID selfId = player.getUUID();
        AABB box = player.getBoundingBox().inflate(0.4);
        double rangeSqr = Math.max(1.0, range) * Math.max(1.0, range);
        double centerX = (box.minX + box.maxX) * 0.5;
        double centerY = (box.minY + box.maxY) * 0.5;
        double centerZ = (box.minZ + box.maxZ) * 0.5;

        Threat best = null;
        for (Entity entity : entities) {
            if (!(entity instanceof Projectile projectile)) continue;
            if (!projectile.isAlive()) continue;
            if (projectile.distanceToSqr(player) > rangeSqr) continue;
            Entity owner;
            try {
                owner = projectile.getOwner();
            } catch (Throwable ignored) {
                owner = null;
            }
            if (owner != null && selfId.equals(owner.getUUID())) continue; // our own shots
            if (harmless(projectile)) continue;

            Vec3 velocity = projectile.getDeltaMovement();
            // A landed/stuck arrow or a dropped thrown item is still a Projectile entity. Its
            // near-zero velocity would otherwise look like a permanent tick-1 impact.
            if (velocity.lengthSqr() < MIN_SPEED_SQR) continue;

            Vec3 position = projectile.position();
            // If it is already right next to us and no longer closing, it has already arrived /
            // been blocked and is tumbling down — do not re-arm the shield forever.
            if (distanceToBox(position, box) < 3.0) {
                Vec3 toCenter = new Vec3(centerX - position.x, centerY - position.y, centerZ - position.z);
                if (velocity.dot(toCenter) <= 0.0) continue;
            }

            for (int tick = 1; tick <= LOOKAHEAD; tick++) {
                Vec3 previous = position;
                position = position.add(velocity);
                velocity = new Vec3(velocity.x * 0.99, velocity.y * 0.99 - 0.05, velocity.z * 0.99);
                if (box.contains(position) || box.clip(previous, position).isPresent()) {
                    Threat threat = new Threat(projectile, tick, projectile.distanceTo(player));
                    if (best == null || threat.impactTicks() < best.impactTicks()) best = threat;
                    break;
                }
            }
        }
        return best;
    }

    private static boolean harmless(Projectile projectile) {
        return projectile instanceof FishingHook
                || projectile instanceof Snowball
                || projectile instanceof ThrownEgg
                || projectile instanceof ThrownEnderpearl;
    }

    private static double distanceToBox(Vec3 point, AABB box) {
        double dx = Math.max(box.minX - point.x, Math.max(0, point.x - box.maxX));
        double dy = Math.max(box.minY - point.y, Math.max(0, point.y - box.maxY));
        double dz = Math.max(box.minZ - point.z, Math.max(0, point.z - box.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
