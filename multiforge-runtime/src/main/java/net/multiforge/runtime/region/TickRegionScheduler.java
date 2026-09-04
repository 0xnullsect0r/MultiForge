/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.region;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.runtime.ownership.OwnerToken;

/**
 * Fixed-size worker pool that ticks regions in parallel at ~20 TPS
 * each. Region-per-worker (Folia model): a worker claims a region via
 * {@link Region#tryMarkTicking()}, runs its tick body, releases with
 * {@link Region#markNotTicking()}, and returns to the pool.
 *
 * <p>Scheduling is earliest-deadline-first — the region with the
 * oldest {@code nextFireNanos} runs next. Slow regions accumulate a
 * deficit and get more back-to-back time until they catch up, but
 * cannot starve peers.
 */
public final class TickRegionScheduler implements AutoCloseable {

    private static final long TICK_NANOS = 50L * 1_000_000L; // 20 TPS

    private final int workerCount;
    private final ExecutorService pool;
    private final PriorityBlockingQueue<ScheduleEntry> queue = new PriorityBlockingQueue<>();
    private final ConcurrentMap<RegionId, RegionState_> perRegion = new ConcurrentHashMap<>();
    private final RegionTickBody body;
    private final RegionizedTaskQueue taskQueue;
    private final int mailboxDrainBatch;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public TickRegionScheduler(
            int workerCount, RegionTickBody body, RegionizedTaskQueue taskQueue, int mailboxDrainBatch) {
        if (workerCount < 1) throw new IllegalArgumentException("workerCount must be >= 1");
        this.workerCount = workerCount;
        this.body = Objects.requireNonNull(body, "body");
        this.taskQueue = Objects.requireNonNull(taskQueue, "taskQueue");
        this.mailboxDrainBatch = mailboxDrainBatch;
        AtomicInteger seq = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "multiforge-tick-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < workerCount; i++) pool.execute(this::runWorker);
    }

    public int workerCount() {
        return workerCount;
    }

    /** Register a region for scheduling. Idempotent. */
    public void register(Region region) {
        Objects.requireNonNull(region, "region");
        perRegion.computeIfAbsent(region.id(), id -> {
            RegionState_ s = new RegionState_(region);
            queue.add(new ScheduleEntry(System.nanoTime(), region.id(), s));
            return s;
        });
    }

    /** Deregister a region. Any pending schedule entries are ignored on pop. */
    public void unregister(Region region) {
        perRegion.remove(region.id());
    }

    public RegionMspt mspt(Region region) {
        RegionState_ s = perRegion.get(region.id());
        return s == null ? null : s.mspt;
    }

    @Override
    public void close() {
        running.set(false);
        pool.shutdownNow();
        try {
            pool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runWorker() {
        while (running.get()) {
            ScheduleEntry entry;
            try {
                entry = queue.poll(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (entry == null) continue;

            long now = System.nanoTime();
            if (entry.fireAtNanos > now) {
                // Not yet time; wait then reinsert.
                long delayNs = entry.fireAtNanos - now;
                LockSupportSleep.parkNanos(delayNs);
                queue.add(entry);
                continue;
            }

            RegionState_ s = entry.state;
            Region region = s.region;
            if (perRegion.get(region.id()) != s) continue; // deregistered

            if (!region.tryMarkTicking()) {
                // Someone else beat us; reschedule for later.
                queue.add(new ScheduleEntry(now + TICK_NANOS, region.id(), s));
                continue;
            }

            long start = System.nanoTime();
            try {
                OwnerToken.runAs(OwnerToken.forRegion(region.id().value()), () -> {
                    taskQueue.drain(region, mailboxDrainBatch);
                    body.tickOnce(region);
                    taskQueue.drain(region, mailboxDrainBatch);
                });
            } catch (Throwable t) {
                Thread.currentThread().getUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), t);
            } finally {
                long elapsed = System.nanoTime() - start;
                s.mspt.recordNanos(elapsed);
                region.markNotTicking();
                // Deadline = start-of-tick + one tick period, not
                // now + one period — so slow regions catch up rather
                // than drift.
                long nextFire = Math.max(start + TICK_NANOS, System.nanoTime());
                queue.add(new ScheduleEntry(nextFire, region.id(), s));
            }
        }
    }

    private static final class ScheduleEntry implements Comparable<ScheduleEntry> {
        final long fireAtNanos;
        final RegionId regionId;
        final RegionState_ state;

        ScheduleEntry(long fireAtNanos, RegionId regionId, RegionState_ state) {
            this.fireAtNanos = fireAtNanos;
            this.regionId = regionId;
            this.state = state;
        }

        @Override
        public int compareTo(ScheduleEntry o) {
            return Long.compare(fireAtNanos, o.fireAtNanos);
        }
    }

    private static final class RegionState_ {
        final Region region;
        final RegionMspt mspt = new RegionMspt(100); // ~5s at 20 TPS

        RegionState_(Region region) {
            this.region = region;
        }
    }

    /** Split out for testability. */
    private static final class LockSupportSleep {
        static void parkNanos(long nanos) {
            java.util.concurrent.locks.LockSupport.parkNanos(nanos);
        }
    }
}
