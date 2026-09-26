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
package net.multiforge.runtime.event;

/**
 * Where {@link DomainDispatcher} hands off invocations that do not run
 * inline. MC-free so the dispatcher is unit-testable; the fork's {@code
 * SchedulerBackedDispatchExecutor} is the production implementation.
 */
public interface DispatchExecutor {

    /**
     * Run {@code task} on the serial lane and return once it finished (see
     * {@link SerialLane}). Exceptions propagate to the caller.
     */
    void runSerial(Runnable task);

    /** Hand {@code task} to the global region; runs at its next tick. Never blocks. */
    void enqueueGlobal(Runnable task);

    /** Hand {@code task} to the shared async pool. Never blocks. */
    void enqueueAsync(Runnable task);
}
