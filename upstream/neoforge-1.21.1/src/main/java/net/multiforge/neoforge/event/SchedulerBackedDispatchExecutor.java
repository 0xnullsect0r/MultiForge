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
package net.multiforge.neoforge.event;

import java.util.Objects;
import net.multiforge.runtime.event.AsyncEventPool;
import net.multiforge.runtime.event.DispatchExecutor;
import net.multiforge.runtime.event.SerialLane;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;

/**
 * Production {@link DispatchExecutor}: the serial lane is the server thread
 * ({@link SerialLane}, pumped at the tick barrier), the global region is the
 * host's global region inbox, and async listeners run on the shared {@link
 * AsyncEventPool}.
 */
public final class SchedulerBackedDispatchExecutor implements DispatchExecutor {
    private static final AsyncEventPool ASYNC_POOL = new AsyncEventPool();

    private final MultiThreadedSchedulerHost host;

    public SchedulerBackedDispatchExecutor(MultiThreadedSchedulerHost host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    @Override
    public void runSerial(Runnable task) {
        SerialLane.run(task);
    }

    @Override
    public void enqueueGlobal(Runnable task) {
        Objects.requireNonNull(task, "task");
        host.taskQueue().queueChunkTask(MultiThreadedSchedulerHost.GLOBAL_WORLD, 0, 0, task);
    }

    @Override
    public void enqueueAsync(Runnable task) {
        Objects.requireNonNull(task, "task");
        ASYNC_POOL.submit(task);
    }
}
