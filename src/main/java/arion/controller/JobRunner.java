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

/**
 * Package-private bridge that lets the controller invoke a {@link Job}'s
 * protected start/cancel hooks while keeping those hooks off-limits to the rest
 * of the codebase (so jobs are only ever started on the client thread, through
 * the controller).
 */
final class JobRunner {

    private JobRunner() {}

    static void start(Job job, IBaritone baritone) {
        job.start(baritone);
    }

    static void cancel(Job job, IBaritone baritone) {
        job.cancel(baritone);
    }
}
