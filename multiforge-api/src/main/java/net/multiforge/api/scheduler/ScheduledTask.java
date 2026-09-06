/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
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
