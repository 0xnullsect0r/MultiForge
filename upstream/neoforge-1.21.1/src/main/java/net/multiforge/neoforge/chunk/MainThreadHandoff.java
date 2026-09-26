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
package net.multiforge.neoforge.chunk;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.multiforge.runtime.diagnostics.ProbeRegistry;

/**
 * Lets the server thread service Vanilla main-thread chunk requests, and the
 * serial event lane ({@link net.multiforge.runtime.event.SerialLane}), while
 * it waits at the region tick barrier.
 *
 * <p>Vanilla's {@code ServerChunkCache.getChunk} called off the server
 * thread for a chunk that is not already loaded hands the load to the
 * server thread's executor and joins on it. In the barrier tick model the
 * server thread is itself waiting for the regions, so without help that is
 * a deadlock. The patched {@code getChunk} brackets the join with {@link
 * #enter()}/{@link #exit()}, and every task a region worker hands the
 * executor is counted ({@link #track}); the barrier's pump ({@link #pumpFor})
 * runs main-thread chunk tasks only while a worker is waiting or such a task
 * is pending, so
 * in the common case (no region touching an unloaded chunk) the server
 * thread does nothing but wait and no chunk-system work overlaps region
 * work.
 *
 * <p>Every hand-off bumps the probe {@code region.main-thread-chunk-load};
 * a region that keeps loading chunks synchronously is a performance
 * problem worth seeing in {@code /multiforge probes}. The wait is a
 * designed wait: the region tick watchdog does not count it.
 */
public final class MainThreadHandoff {
    private static final AtomicInteger WAITING = new AtomicInteger();
    private static final AtomicInteger REGION_TASKS = new AtomicInteger();

    private MainThreadHandoff() {}

    /** A worker is about to block on a main-thread chunk task. */
    public static void enter() {
        WAITING.incrementAndGet();
        ProbeRegistry.bump("region.main-thread-chunk-load");
        // A designed wait: the region tick watchdog does not count it as the region's time.
        net.multiforge.runtime.region.RegionTickWatchdog.beginWait();
    }

    /** The worker's main-thread chunk task completed. */
    public static void exit() {
        net.multiforge.runtime.region.RegionTickWatchdog.endWait("main-thread-chunk-load");
        WAITING.decrementAndGet();
    }

    /**
     * Wrap a task handed to a level's main-thread chunk executor. A task from a
     * region worker is counted until it has run, and the barrier's pump runs
     * the executor's tasks while any is pending: the worker may be waiting for
     * it through code that never calls {@link #enter()} (a mod that replaces
     * {@code ServerChunkCache.getChunk}, as Lithium does, or joins a future of
     * its own), and without the pump that wait would never end.
     */
    public static Runnable track(Runnable task) {
        if (net.multiforge.runtime.ownership.OwnerToken.current().domain() != net.multiforge.runtime.ownership.Domain.REGION) {
            return task;
        }
        REGION_TASKS.incrementAndGet();
        return new RegionTask(task);
    }

    private static final class RegionTask implements Runnable {
        private final Runnable task;
        private boolean done;

        RegionTask(Runnable task) {
            this.task = task;
        }

        @Override
        public void run() {
            try {
                task.run();
            } finally {
                if (!done) {
                    done = true;
                    REGION_TASKS.decrementAndGet();
                }
            }
        }
    }

    /** @return how many region workers are currently blocked on the server thread. */
    public static int waiting() {
        return WAITING.get();
    }

    /**
     * The pump the barrier runs on the server thread: while any worker is
     * waiting, run one pending main-thread task of each level's chunk
     * source.
     *
     * @return whether any task ran
     */
    public static BooleanSupplier pumpFor(MinecraftServer server) {
        return () -> {
            // Event listeners region workers handed to the serial lane.
            boolean ran = net.multiforge.runtime.event.SerialLane.drain();
            if (WAITING.get() == 0 && REGION_TASKS.get() == 0) return ran;
            for (ServerLevel level : server.getAllLevels()) {
                ran |= level.getChunkSource().pollTask();
            }
            return ran;
        };
    }
}
