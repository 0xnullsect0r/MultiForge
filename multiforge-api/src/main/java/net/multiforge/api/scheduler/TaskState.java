/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
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
