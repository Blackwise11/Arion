/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.vessel;

import baritone.api.IBaritone;
import baritone.api.utils.IPlayerContext;

import java.lang.reflect.Field;

/**
 * Borrows the <b>primary</b> Baritone for the vessel, then returns it on despawn.
 *
 * Why borrow the primary (not create a fresh instance): Baritone's
 * {@code IBaritoneProvider.createBaritone(Minecraft)} is keyed by the single
 * {@code Minecraft} instance — it returns the already-registered primary baritone,
 * never a new one. There is no API to create a second Baritone for a different
 * {@code LocalPlayer}. So the vessel reuses the primary baritone for the duration it
 * is spawned: we reflectively swap the primary's private-final {@code playerContext}
 * for a {@link VesselPlayerContext} (whose {@code player()} is the vessel and whose
 * {@code playerController()} is a {@link NoopPlayerController}), and on despawn we
 * swap the original context back.
 *
 * While borrowed, the real player cannot use their own Baritone — that is the
 * documented trade-off of this approach. {@code getBaritoneForPlayer(mc.player)}
 * returns null while the primary's context points at the vessel, so Baritone's
 * input-forcing mixins never fire against {@code mc.player} (no packets from your
 * account). The vessel body is driven purely by {@link FakePlayerManager} copying the
 * primary's forced inputs into the vessel's no-network tick.
 *
 * The wrapper's {@code playerController()} is a no-op, so Baritone can pathfind and
 * move the vessel but <b>cannot emit a break/place/attack packet</b>. We do NOT touch
 * the original context's {@code playerController} field — doing so would destroy the
 * real player's controller and make clean restoration impossible. The wrapper override
 * is sufficient.
 *
 * If any reflection step fails, {@link #bind} returns {@code null} and leaves the
 * primary baritone unmodified (the vessel falls back to seek-only).
 */
final class VesselBaritoneBinder {

    private VesselBaritoneBinder() {}

    /**
     * Borrow {@code primary} for {@code vessel}. Returns the original
     * {@link IPlayerContext} (to be passed back to {@link #unbind} on despawn), or
     * {@code null} on any reflection failure — in which case the baritone is untouched.
     */
    static IPlayerContext bind(IBaritone primary, FakePlayerEntity vessel) {
        try {
            Field pcField = findField(primary.getClass(), "playerContext");
            pcField.setAccessible(true);
            Object originalCtx = pcField.get(primary);
            if (!(originalCtx instanceof IPlayerContext)) {
                arion.Arion.LOGGER.warn("VesselBaritoneBinder: playerContext is not an IPlayerContext.");
                return null;
            }
            IPlayerContext original = (IPlayerContext) originalCtx;

            // swap in the vessel-bound wrapper as the primary's playerContext
            VesselPlayerContext wrapper = new VesselPlayerContext(vessel, original);
            pcField.set(primary, wrapper);
            return original;
        } catch (Throwable t) {
            arion.Arion.LOGGER.warn("VesselBaritoneBinder: couldn't borrow the primary Baritone: {}", t.toString());
            return null;
        }
    }

    /** Return {@code primary} to the real player by restoring the saved context. */
    static void unbind(IBaritone primary, IPlayerContext original) {
        if (primary == null || original == null) return;
        try {
            Field pcField = findField(primary.getClass(), "playerContext");
            pcField.setAccessible(true);
            pcField.set(primary, original);
        } catch (Throwable t) {
            arion.Arion.LOGGER.warn("VesselBaritoneBinder: couldn't restore the primary Baritone: {}", t.toString());
        }
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        Class<?> c = cls;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
