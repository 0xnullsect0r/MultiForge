/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.api.scheduler;

/**
 * Lifecycle state of a {@link ScheduledTask}. Mirrors Folia's states
 * so cross-porting Folia plugins is straightforward.
 */
public enum TaskState {
    /** Queued but not yet running. Can transition to EXECUTING or CANCELLED. */
    IDLE,

    /** Currently running. Cancellation while here transitions to CANCELLED_RUNNING. */
    EXECUTING,

    /** Ran to completion. Terminal. */
    FINISHED,

    /**
     * Cancellation observed after the task began executing. The current
     * iteration is allowed to finish; a repeating task fires no more.
     * Terminal.
     */
    CANCELLED_RUNNING,

    /**
     * Cancellation observed before the task ever ran, or between
     * iterations of a repeating task. Terminal.
     */
    CANCELLED,
}
