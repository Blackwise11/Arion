/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.controller;

/**
 * The terminal outcome of a job. Emitted exactly once when a job finishes,
 * so the brain (and the player) always get a callback — never left hanging.
 */
public enum JobResult {
    /** The goal was reached / the task completed. */
    DONE,
    /** The pathfinder couldn't do it (calc failed, unreachable, etc.). */
    FAILED,
    /** A human or the kill switch cancelled it. */
    CANCELED,
    /** Stall detection: the bot stopped making progress. */
    STUCK,
    /** The job exceeded its time budget. */
    TIMEOUT
}
