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
import baritone.api.utils.IPlayerContext;

/**
 * A single unit of work Arion has asked Baritone to do (go somewhere, mine
 * something, follow someone). A Job owns its own failsafes: stall detection and
 * a timeout. Completion is reported once via {@link Listener#onJobFinished}.
 */
public abstract class Job {

    public interface Listener {
        void onJobFinished(Job job, JobResult result);
    }

    private final String label;
    private final long timeoutTicks;
    private final int stallTicks;

    private long ageTicks = 0;
    private long ticksSinceProgress = 0;
    private double lastProgressDistance = Double.NaN;
    private boolean finished = false;

    protected Job(String label, long timeoutTicks, int stallTicks) {
        this.label = label;
        this.timeoutTicks = timeoutTicks;
        this.stallTicks = stallTicks;
    }

    /** Start the underlying Baritone process. Called once on the client thread. */
    protected abstract void start(IBaritone baritone);

    /** Stop the underlying Baritone process. */
    protected abstract void cancel(IBaritone baritone);

    /**
     * @return distance-to-goal for stall detection, or NaN if not applicable.
     *         Smaller = closer. Returning a decreasing value counts as progress.
     */
    protected abstract double progressDistance(IPlayerContext ctx);

    /**
     * Whether a Baritone {@code CALC_FAILED} path event should fail this job immediately.
     *
     * <p>Mining / exploring / pickup processes handle calc failures internally: they blacklist
     * the closest unreachable target and retry. Killing the job on the first CALC_FAILED makes an
     * otherwise workable mine look broken after one bad path (the exact "mine stone → FAILED after
     * 2 s, three times" loop in the live log).
     */
    boolean calcFailureIsFatal() {
        return true;
    }

    /** @return true if the underlying Baritone process is still running. */
    protected abstract boolean baritoneActive(IBaritone baritone);

    public final String label() { return label; }

    /**
     * Advance the job one tick. Drives timeout + stall failsafes and detects
     * natural completion.
     *
     * @return the terminal result if the job just finished, else null
     */
    final JobResult tick(IBaritone baritone, IPlayerContext ctx) {
        if (finished) return null;
        ageTicks++;

        // natural completion: baritone gave up control without an error
        if (!baritoneActive(baritone)) {
            arion.ArionLog.debug("job " + label + " → DONE");
            return finish(JobResult.DONE);
        }

        // timeout failsafe
        if (timeoutTicks > 0 && ageTicks > timeoutTicks) {
            arion.ArionLog.debug("job " + label + " → TIMEOUT");
            cancel(baritone);
            return finish(JobResult.TIMEOUT);
        }

        // stall failsafe
        double d = progressDistance(ctx);
        if (!Double.isNaN(d)) {
            if (Double.isNaN(lastProgressDistance) || d < lastProgressDistance - 0.5) {
                lastProgressDistance = d;
                ticksSinceProgress = 0;
            } else {
                ticksSinceProgress++;
                if (ticksSinceProgress > stallTicks) {
                    arion.ArionLog.debug("job " + label + " → STUCK");
                    cancel(baritone);
                    return finish(JobResult.STUCK);
                }
            }
        }
        return null;
    }

    /** Force-finish with an explicit result (cancel, path-calc failure, etc.). */
    final JobResult finish(JobResult result) {
        if (finished) return null;
        finished = true;
        return result;
    }

    final boolean isFinished() { return finished; }
}
