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
package net.multiforge.runtime.event;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.event.DispatchDomainKind;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;

/**
 * Fixed-size pool backing {@link DispatchDomainKind#ASYNC ASYNC}-domain
 * listeners. Default 4 threads, configurable via {@code
 * -Dmultiforge.event-async-pool.size=N}.
 *
 * <p>Submission ({@link #submit(Runnable)}) never blocks the calling
 * thread (CLAUDE.md rule 4): the backing queue is bounded, and once full,
 * a saturated pool drops the single oldest queued task (with a
 * rate-limited warn + a {@code event.dispatch.async.overflow} probe bump)
 * and retries — never rejects by throwing back at the caller and never
 * degrades to a blocking offer.
 *
 * <p>This is provided as the default {@code ASYNC} backing for the fork
 * bridge's {@link DispatchExecutor} implementation (M12.2). {@code
 * docs/design/m12-event-routing.md} §5 notes that M12 could instead reuse
 * the existing executor backing {@code ServerDomains.async()} rather than
 * standing up a second pool — that wiring choice belongs to the fork
 * bridge, not this module; this class exists so either choice is
 * available and independently testable.
 */
public final class AsyncEventPool implements AutoCloseable {

    private static final String SIZE_PROPERTY = "multiforge.event-async-pool.size";
    private static final int DEFAULT_POOL_SIZE = 4;
    private static final int QUEUE_CAPACITY = 4096;

    private final ThreadPoolExecutor executor;

    public AsyncEventPool() {
        this(poolSizeFromProperty());
    }

    public AsyncEventPool(int poolSize) {
        if (poolSize <= 0) {
            throw new IllegalArgumentException("poolSize must be positive: " + poolSize);
        }
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        this.executor = new ThreadPoolExecutor(
                poolSize,
                poolSize,
                60L,
                TimeUnit.SECONDS,
                queue,
                new EventPoolThreadFactory(),
                new DropOldestAndWarn());
    }

    /** Non-blocking submission — see class javadoc for the overflow contract. */
    public void submit(Runnable task) {
        Objects.requireNonNull(task, "task");
        executor.execute(task);
    }

    public int poolSize() {
        return executor.getCorePoolSize();
    }

    public int queueDepth() {
        return executor.getQueue().size();
    }

    public boolean isShutdown() {
        return executor.isShutdown();
    }

    /** Orderly shutdown: stop accepting new work, wait briefly for in-flight tasks, then force-stop. */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static int poolSizeFromProperty() {
        String raw = System.getProperty(SIZE_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_POOL_SIZE;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : DEFAULT_POOL_SIZE;
        } catch (NumberFormatException e) {
            return DEFAULT_POOL_SIZE;
        }
    }

    private static final class EventPoolThreadFactory implements ThreadFactory {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "multiforge-event-async-" + count.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    /**
     * Bounded queue overflow policy: drop the single oldest pending task,
     * warn (rate-limited), and retry the submission. Never throws back at
     * the submitter (CLAUDE.md rule 5) — if the retry itself is rejected
     * (pool shut down concurrently, or still saturated under extreme
     * contention), the task is dropped silently; the warn above already
     * recorded the overflow.
     */
    private static final class DropOldestAndWarn implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor exec) {
            if (exec.isShutdown()) {
                return;
            }
            exec.getQueue().poll();
            ProbeRegistry.bump("event.dispatch.async.overflow");
            ViolationLogger.warn(
                    "AsyncEventPool.overflow",
                    "async event-dispatch queue saturated (capacity " + QUEUE_CAPACITY
                            + "); dropped oldest pending task");
            try {
                exec.execute(task);
            } catch (RejectedExecutionException ignored) {
                // Pool shut down concurrently, or still saturated under heavy
                // contention — drop the task; the overflow warn above already fired.
            }
        }
    }
}
