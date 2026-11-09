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
import baritone.api.utils.IPlayerController;
import net.minecraft.client.player.LocalPlayer;

/**
 * Binds the primary Baritone to the vessel's client-side shadow, and unbinds it on despawn.
 *
 * <p><b>Why we redirect the context instead of swapping {@code Baritone.playerContext}:</b>
 * Baritone constructs every behavior and process with the single {@link IPlayerContext}
 * instance created in its constructor ({@code Behavior.ctx}, {@code BaritoneProcessHelper.ctx},
 * the block break/place helpers, …). Reflecting a new context into the {@code playerContext}
 * field changes what {@code getPlayerContext()} returns — which is enough for the mixin gate
 * that stops the host from being driven — but all of those cached references still resolve
 * {@code player()} to the host. The engine therefore plans from the host's position while the
 * forced inputs are forwarded to the vessel: {@code ai vessel come} sets a goal at the host's
 * own feet (already satisfied), {@code follow} follows the host (already there), and nothing
 * moves.
 *
 * <p>The fork exposes {@link IPlayerContext#setPlayerOverride(LocalPlayer)} and
 * {@link IPlayerContext#setPlayerControllerOverride(IPlayerController)}. Both redirect the one
 * shared context instance, so everything that already holds it — pathing, look, inventory,
 * every {@code IBaritoneProcess} — follows automatically. Clearing the overrides restores the
 * real player without touching the host's identity.
 *
 * <p>If any step fails, the overrides are cleared and {@link #bind} returns {@code null}; the
 * primary Baritone is left exactly as it was (the vessel then falls back to straight-line seek).
 */
final class VesselBaritoneBinder {

    private VesselBaritoneBinder() {}

    /**
     * Redirect {@code primary}'s player context to {@code vessel} + {@code controller}.
     *
     * @return the redirected context (pass it back to {@link #unbind} on despawn), or
     *         {@code null} on failure — in which case the primary is untouched.
     */
    static IPlayerContext bind(IBaritone primary, FakePlayerEntity vessel, IPlayerController controller) {
        if (primary == null || vessel == null || controller == null) return null;
        try {
            IPlayerContext ctx = primary.getPlayerContext();
            if (ctx == null) {
                arion.Arion.LOGGER.warn("VesselBaritoneBinder: primary has no player context yet.");
                return null;
            }

            ctx.setPlayerControllerOverride(controller);
            ctx.setPlayerOverride(vessel);

            // Self-verify: this is the instance every behavior/process cached at construction,
            // so if it reports the vessel here, pathing/look/combat all operate on the vessel.
            // If not, fail loudly and revert rather than silently driving the host.
            if (ctx.player() != vessel) {
                LocalPlayer actual = ctx.player();
                arion.Arion.LOGGER.error("[Arion] VesselBaritoneBinder: player override did not take "
                                + "(context.player() is {}, expected the vessel shadow). Reverting; host untouched.",
                        actual == null ? "null" : actual.getName().getString());
                unbind(primary, ctx);
                return null;
            }
            if (ctx.playerController() != controller) {
                arion.Arion.LOGGER.error("[Arion] VesselBaritoneBinder: controller override did not take. "
                        + "Reverting; host untouched.");
                unbind(primary, ctx);
                return null;
            }

            arion.Arion.LOGGER.info("[Arion] VesselBaritoneBinder: primary playerContext redirected to the vessel shadow.");
            return ctx;
        } catch (Throwable t) {
            arion.Arion.LOGGER.warn("VesselBaritoneBinder: couldn't bind the primary Baritone: {}", t.toString());
            return null;
        }
    }

    /** Clear the redirect so the host's real player and controller become visible again. */
    static void unbind(IBaritone primary, IPlayerContext boundContext) {
        if (boundContext == null) return;
        try {
            boundContext.setPlayerOverride(null);
            boundContext.setPlayerControllerOverride(null);
        } catch (Throwable t) {
            arion.Arion.LOGGER.warn("VesselBaritoneBinder: couldn't restore the primary Baritone: {}", t.toString());
        }
    }
}
