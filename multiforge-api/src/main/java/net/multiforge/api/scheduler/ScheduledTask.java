/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.api.scheduler;

import net.multiforge.api.mod.ModIdentifier;

/**
 * Handle to a scheduled unit of work. Callers may observe the {@link
 * #state()} or {@link #cancel()} at any time; both are thread-safe.
 */
public interface ScheduledTask {

    /** Current lifecycle state. */
    TaskState state();

    /** Which mod owns this task. */
    ModIdentifier owner();

    /** True for tasks queued via {@code scheduleAtFixedRate} / {@code runAtFixedRate}. */
    boolean isRepeating();

    /**
     * Request cancellation.
     *
     * @return {@code true} iff this call transitioned the state to
     *         {@link TaskState#CANCELLED} or {@link
     *         TaskState#CANCELLED_RUNNING}. A task that has already
     *         reached a terminal state returns {@code false}.
     */
    boolean cancel();
}
