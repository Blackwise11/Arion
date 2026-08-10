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
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.spider.Spider;

/**
 * How to fight one kind of mob. The "skill" of the combat module lives in these
 * per-mob numbers and flags.
 *
 * @param engageRange     how close we must be to hit (≈ our reach)
 * @param dangerRange     inside this we take countermeasures (retreat/strafe)
 * @param retreatOnWindup back off when the mob starts a fuse/wind-up (creeper)
 * @param strafeSpeed     how fast to strafe vs ranged attackers
 * @param ranged          true for mobs with a ranged attack (skeleton/blaze/witch)
 */
public record MobTactic(double engageRange, double dangerRange, boolean retreatOnWindup,
                        double strafeSpeed, boolean ranged) {

    /** Generic melee fallback for any mob without a hand-tuned entry. */
    public static final MobTactic GENERIC = new MobTactic(4.5, 2.0, false, 0.6, false);

    public static MobTactic forEntity(Entity e) {
        if (e instanceof Creeper)        return new MobTactic(4.5, 6.0, true, 0.6, false);
        if (e instanceof AbstractSkeleton) return new MobTactic(4.5, 8.0, false, 1.0, true);
        if (e instanceof Spider)         return new MobTactic(4.5, 2.5, false, 0.7, false);
        if (e instanceof EnderMan)       return new MobTactic(4.0, 2.0, false, 0.7, false);
        if (e instanceof Witch)          return new MobTactic(4.5, 8.0, false, 1.0, true);
        if (e instanceof Blaze)          return new MobTactic(4.5, 8.0, false, 1.0, true);
        if (e instanceof Slime)          return new MobTactic(4.5, 3.0, false, 0.6, false);
        // zombies, husks, drowned, piglins, and everything else → generic melee
        return GENERIC;
    }
}
