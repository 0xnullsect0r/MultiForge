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
package net.multiforge.runtime.region;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
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
 *
 * <p>Two execution modes (see {@link Mode}):
 *
 * <ul>
 * <li>{@link Mode#FREE_RUNNING} — the pure-runtime model above: each
 *     worker self-schedules regions on its own 20 TPS cadence. Only sound
 *     when nothing else mutates world state concurrently, so it is used by
 *     the MC-free runtime tests and headless harnesses.</li>
 * <li>{@link Mode#BARRIER} — the production model on a NeoForge server,
 *     where the server thread still owns the Vanilla main loop (network,
 *     chunk loading, commands). Workers never self-schedule; the server
 *     thread calls {@link #driveTick} once per level tick, which runs every
 *     region's tick body exactly once in parallel and returns only after
 *     all of them finished. Region work and server-thread work therefore
 *     never overlap, which is what makes running Vanilla entity/block code
 *     on region workers sound.</li>
 * </ul>
 */
public final class TickRegionScheduler implements AutoCloseable, RegionListener {

    /** Execution model — see the class javadoc. */
    public enum Mode {
        FREE_RUNNING,
        BARRIER
    }

    private static final long TICK_NANOS = 50L * 1_000_000L; // 20 TPS

    private volatile int workerCount;
    // FREE_RUNNING only: worker loops currently running; loops beyond
    // workerCount exit after a shrink.
    private final AtomicInteger liveLoops = new AtomicInteger();
    private final java.util.concurrent.ThreadPoolExecutor pool;
    private final PriorityBlockingQueue<ScheduleEntry> queue = new PriorityBlockingQueue<>();
    private final ConcurrentMap<RegionId, RegionState_> perRegion = new ConcurrentHashMap<>();
    // Volatile so a mid-run swap via setBody() publishes to every worker
    // without needing the executor's memory barrier. Workers re-read the
    // field once per tick (the read in runWorker: `body.tickOnce(region)`),
    // so a swap is picked up on the following tick — no in-flight tick is
    // preempted, no torn state exposed. Phase 5 wiring uses this to install
    // an M9-wired PhasedRegionTickBody after MultiThreadedSchedulerHost has
    // constructed its per-world managers.
    private volatile RegionTickBody body;
    private final RegionizedTaskQueue taskQueue;
    private final int mailboxDrainBatch;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Mode mode;

    public TickRegionScheduler(
            int workerCount, RegionTickBody body, RegionizedTaskQueue taskQueue, int mailboxDrainBatch) {
        this(workerCount, body, taskQueue, mailboxDrainBatch, Mode.FREE_RUNNING);
    }

    public TickRegionScheduler(
            int workerCount, RegionTickBody body, RegionizedTaskQueue taskQueue, int mailboxDrainBatch, Mode mode) {
        if (workerCount < 1) throw new IllegalArgumentException("workerCount must be >= 1");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.workerCount = workerCount;
        this.body = Objects.requireNonNull(body, "body");
        this.taskQueue = Objects.requireNonNull(taskQueue, "taskQueue");
        this.mailboxDrainBatch = mailboxDrainBatch;
        AtomicInteger seq = new AtomicInteger();
        this.pool = (java.util.concurrent.ThreadPoolExecutor) Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "multiforge-tick-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        if (mode == Mode.FREE_RUNNING) {
            for (int i = 0; i < workerCount; i++) startWorkerLoop();
        }
    }

    public int workerCount() {
        return workerCount;
    }

    /**
     * Change the number of worker threads. In {@link Mode#BARRIER} call it
     * between ticks (the server thread, outside {@link #driveTick}); the next
     * tick uses the new size. Idle surplus threads exit on their own.
     */
    public synchronized void resize(int newWorkerCount) {
        if (newWorkerCount < 1) throw new IllegalArgumentException("workerCount must be >= 1");
        int old = this.workerCount;
        if (newWorkerCount == old) return;
        if (newWorkerCount > old) {
            pool.setMaximumPoolSize(newWorkerCount);
            pool.setCorePoolSize(newWorkerCount);
        } else {
            pool.setCorePoolSize(newWorkerCount);
            pool.setMaximumPoolSize(newWorkerCount);
        }
        this.workerCount = newWorkerCount;
        if (mode == Mode.FREE_RUNNING) {
            for (int i = old; i < newWorkerCount; i++) startWorkerLoop();
        }
    }

    private void startWorkerLoop() {
        liveLoops.incrementAndGet();
        pool.execute(this::runWorker);
    }

    public Mode mode() {
        return mode;
    }

    /**
     * Swap in a new per-region tick body. Applied on the next tick each
     * worker starts — no in-flight tick is preempted. Callers must not
     * pass {@code null}. Used by {@code
     * MultiThreadedSchedulerHost.installRegionTickBody} once the server's
     * runners are bound.
     */
    public void setBody(RegionTickBody body) {
        this.body = Objects.requireNonNull(body, "body");
    }

    /** For observability + tests. */
    public RegionTickBody body() {
        return body;
    }

    /**
     * Register a region for scheduling. Idempotent.
     *
     * <p>We publish {@code s} into {@code perRegion} <em>before</em>
     * adding to the queue. If the queue add came first, a worker that
     * poll()'d the entry between add and put would see
     * {@code perRegion.get(id) == null}, hit the "deregistered"
     * guard, and discard the entry — losing it forever. Ordering as
     * put-first-then-add closes that race.
     */
    public void register(Region region) {
        register(region, null, 0.0);
    }

    private void register(Region region, RegionMspt seed, double share) {
        Objects.requireNonNull(region, "region");
        RegionState_ fresh = new RegionState_(region);
        if (seed != null) fresh.mspt.seedFrom(seed, share);
        RegionState_ existing = perRegion.putIfAbsent(region.id(), fresh);
        if (existing == null && mode == Mode.FREE_RUNNING) {
            queue.add(new ScheduleEntry(System.nanoTime(), region.id(), fresh));
        }
    }

    /** Deregister a region. Any pending schedule entries are ignored on pop. */
    public void unregister(Region region) {
        perRegion.remove(region.id());
    }

    /**
     * {@link RegionListener} hook: when a region dies (merged away or its
     * last section removed), automatically deregister it. Safe to call
     * concurrent with a worker actively ticking that region — the
     * scheduler's own worker checks {@code perRegion.get(id) != state}
     * on each pop (see {@link #runWorker} guard).
     */
    @Override
    public void onRegionDied(Region region) {
        unregister(region);
    }

    /**
     * {@link RegionListener} hook: register newly-created regions so
     * fresh split-children (peeled off by {@link
     * ThreadedRegionizer#splitIfDisconnected} when a chunk unload
     * disconnects a region) actually enter the tick pool. Without this,
     * tasks queued for the child region's chunks would accumulate in
     * the inbox forever with no worker to drain them.
     */
    @Override
    public void onRegionCreated(Region region) {
        register(region);
    }

    /**
     * {@link RegionListener} hook: a split child starts with the source's recent
     * tick times scaled by its share of the sections (see {@link
     * RegionMspt#seedFrom}), so its heat and {@code region list} figures do not
     * start from an empty window. Fired before {@link #onRegionCreated}, whose
     * {@link #register} is then a no-op.
     */
    @Override
    public void onRegionSplit(Region source, Region child) {
        RegionState_ src = perRegion.get(source.id());
        if (src == null) return;
        int total = source.sectionCount() + child.sectionCount();
        register(child, src.mspt, total == 0 ? 0.0 : child.sectionCount() / (double) total);
    }

    /**
     * {@link RegionListener} hook: the surviving region of a merge takes on the
     * dying region's work, so its recent tick times become the sum of both (see
     * {@link RegionMspt#absorb}).
     */
    @Override
    public void onRegionsMerging(Region surviving, Region dying) {
        RegionState_ keep = perRegion.get(surviving.id());
        RegionState_ gone = perRegion.get(dying.id());
        if (keep != null && gone != null) keep.mspt.absorb(gone.mspt);
    }

    public RegionMspt mspt(Region region) {
        RegionState_ s = perRegion.get(region.id());
        return s == null ? null : s.mspt;
    }

    /**
     * Deterministic barrier used by
     * {@code net.multiforge.neoforge.RegionizedTickCoordinator#dispatchLevelTick}
     * (M8 sub-step 6b — real per-region tick dispatch). Blocks the caller
     * until every region in {@code regions} that is currently mid-tick
     * ({@link RegionState#TICKING}) has returned to {@link RegionState#READY},
     * or the deadline expires — whichever comes first.
     *
     * <p>Semantics: the scheduler already ticks each registered region
     * autonomously on the worker pool at ~20 TPS. This method does not
     * <em>drive</em> those ticks — it <em>observes</em> them, giving the
     * caller a place to safely synchronise before running per-level
     * global work (weather, time, etc.) that must not race a region
     * worker mid-tick. Regions not currently ticking are skipped
     * without waiting.
     *
     * <p>The wait uses {@link Thread#onSpinWait()} rather than a
     * {@link java.util.concurrent.locks.LockSupport#parkNanos parkNanos}
     * sleep because expected wait times are microseconds — a full tick
     * body under the no-op body currently bound completes in well under
     * 1 ms, and even with a real body wired (Phase 5) the barrier is
     * only invoked once per server tick.
     *
     * @param regions the set of regions the caller wishes to synchronise
     *                against — typically a per-world snapshot of
     *                {@link ThreadedRegionizer#regions()}. May be empty
     *                (returns immediately).
     * @param deadlineNanos how long the caller is willing to wait for a
     *                      TICKING region to return to READY before
     *                      marking it as an overrun. Applies once across
     *                      the whole collection, not per region.
     * @return a {@link TickAllResult} carrying the total region count and
     *         the ids of regions that did not complete within the
     *         deadline. Never {@code null}.
     *         In {@link Mode#BARRIER} this delegates to {@link #driveTick}
     *         with no pump, i.e. it drives the ticks instead of observing
     *         them.
     */
    public TickAllResult tickAll(Collection<Region> regions, long deadlineNanos) {
        Objects.requireNonNull(regions, "regions");
        if (mode == Mode.BARRIER) return driveTick(regions, deadlineNanos, () -> false);
        if (regions.isEmpty()) return TickAllResult.EMPTY;
        long deadline = System.nanoTime() + Math.max(0L, deadlineNanos);
        List<RegionId> overrun = new ArrayList<>();
        int total = 0;
        for (Region region : regions) {
            total++;
            // Only wait for regions currently mid-tick. READY, TRANSIENT,
            // FOLDING, DEAD all pass through instantly — the caller is
            // synchronising with in-flight work, not driving new ticks.
            while (region.state() == RegionState.TICKING) {
                if (System.nanoTime() >= deadline) {
                    overrun.add(region.id());
                    break;
                }
                Thread.onSpinWait();
            }
        }
        return new TickAllResult(total, List.copyOf(overrun));
    }

    /**
     * Result of {@link #tickAll(Collection, long)}: how many regions the
     * caller synchronised against, and the ids of any that did not
     * complete their in-flight tick within the deadline. The coordinator
     * uses {@link #allCompleted()} to decide whether to route to the
     * warn (default) or STRICT-mode throw path.
     */
    /**
     * {@link Mode#BARRIER} driver: tick every region in {@code regions}
     * exactly once, in parallel on the worker pool, and return once all of
     * them finished. While waiting, the calling thread repeatedly invokes
     * {@code pump} (the server thread passes its main-thread task executor
     * here) so a region worker that needs a main-thread round trip — e.g.
     * Vanilla's off-thread {@code ServerChunkCache.getChunk} — can never
     * deadlock against the barrier.
     *
     * <p>The deadline never cuts a tick short: two server ticks must not
     * overlap, so this always waits for completion. Regions still running
     * when the deadline passes are reported as overruns in the result.
     * A region that cannot be claimed ({@link Region#tryMarkTicking()}
     * fails — it is mid-merge or already dead) is skipped this tick.
     *
     * <p>A throwable escaping a region's tick is rethrown here, on the
     * caller, once every region has finished — the first one, with any
     * others attached as suppressed. On a server that is the server thread,
     * whose Vanilla crash handling ("Exception ticking world") then applies
     * exactly as it would to an exception from an inline level tick.
     *
     * @throws IllegalStateException in {@link Mode#FREE_RUNNING}
     */
    public TickAllResult driveTick(Collection<Region> regions, long deadlineNanos, BooleanSupplier pump) {
        Objects.requireNonNull(regions, "regions");
        Objects.requireNonNull(pump, "pump");
        if (mode != Mode.BARRIER) throw new IllegalStateException("driveTick requires Mode.BARRIER");
        if (regions.isEmpty()) return TickAllResult.EMPTY;
        long deadline = System.nanoTime() + Math.max(0L, deadlineNanos);
        List<Region> batch = List.copyOf(regions);
        int n = batch.size();
        RegionState_[] states = new RegionState_[n];
        // Where each region ticks: on a worker, or here on the calling (server)
        // thread — see TickPlacement. A region alone in its batch gains nothing
        // from a worker and pays a hand-off for every serial-lane post and chunk
        // load; a hot one runs after the parallel ones, when nothing needs pumping.
        TickPlacement[] placement = new TickPlacement[n];
        int parallel = 0;
        for (int i = 0; i < n; i++) {
            Region region = batch.get(i);
            states[i] = perRegion.computeIfAbsent(region.id(), id -> new RegionState_(region));
            if (n == 1 && inlineSingleRegion) placement[i] = TickPlacement.SERVER_THREAD_SINGLE;
            else if (states[i].hot) placement[i] = TickPlacement.SERVER_THREAD_HOT;
            else {
                placement[i] = TickPlacement.WORKER;
                parallel++;
            }
        }
        AtomicInteger remaining = new AtomicInteger(parallel);
        long[] finishedAt = new long[n];
        // Designed waits (a main-thread chunk load, a serial-lane hand-off) are not
        // the region's own time: RegionTickWatchdog leaves them out per region, and
        // so does the deadline. The listeners the lane runs for a region do count.
        long[] waited = new long[n];
        Throwable[] failures = new Throwable[n];
        Thread waiter = Thread.currentThread();
        // While a worker runs, server-thread entity-visibility changes (a pumped
        // chunk promotion or demotion) are held back and replayed at the barrier.
        if (parallel > 0) RegionPhase.beginWorkers();
        try {
            submitAndAwaitWorkers(batch, states, placement, remaining, finishedAt, waited, failures, waiter, pump);
        } finally {
            if (parallel > 0) RegionPhase.endWorkers();
        }
        // Server-thread regions run now, one after another, with no worker
        // running: the caller is the serial lane and the chunk source's thread,
        // so their posts and chunk loads run directly instead of being handed off.
        long[] startedAt = new long[n];
        for (int i = 0; i < n; i++) {
            if (placement[i] == TickPlacement.WORKER) continue;
            Region region = batch.get(i);
            startedAt[i] = System.nanoTime();
            if (region.tryMarkTicking()) {
                states[i].placement = placement[i];
                ProbeRegistry.bump(
                        placement[i] == TickPlacement.SERVER_THREAD_SINGLE
                                ? "region-tick.inline.single"
                                : "region-tick.inline.hot");
                failures[i] = tickClaimed(states[i], region, false);
                waited[i] = RegionTickWatchdog.lastTickWaitNanos();
            }
            finishedAt[i] = System.nanoTime();
        }
        List<RegionId> overrun = new ArrayList<>();
        Throwable first = null;
        for (int i = 0; i < n; i++) {
            boolean late = placement[i] == TickPlacement.WORKER
                    ? finishedAt[i] - waited[i] > deadline
                    : finishedAt[i] - startedAt[i] - waited[i] > Math.max(0L, deadlineNanos);
            if (late) overrun.add(batch.get(i).id());
            updateHot(states[i], placement[i]);
            if (failures[i] != null) {
                if (first == null) first = failures[i];
                else if (first != failures[i]) first.addSuppressed(failures[i]);
            }
        }
        if (first instanceof RuntimeException re) throw re;
        if (first instanceof Error err) throw err;
        if (first != null) throw new IllegalStateException("region tick failed", first);
        return new TickAllResult(n, overrun);
    }

    /** Submit the worker-placed regions of a {@link #driveTick} batch and pump until all finished. */
    private void submitAndAwaitWorkers(
            List<Region> batch,
            RegionState_[] states,
            TickPlacement[] placement,
            AtomicInteger remaining,
            long[] finishedAt,
            long[] waited,
            Throwable[] failures,
            Thread waiter,
            BooleanSupplier pump) {
        int n = batch.size();
        for (int i = 0; i < n; i++) {
            if (placement[i] != TickPlacement.WORKER) continue;
            final int idx = i;
            Region region = batch.get(i);
            RegionState_ s = states[i];
            pool.execute(() -> {
                try {
                    if (region.tryMarkTicking()) {
                        s.placement = TickPlacement.WORKER;
                        failures[idx] = tickClaimed(s, region, false);
                        waited[idx] = RegionTickWatchdog.lastTickWaitNanos();
                    }
                } finally {
                    finishedAt[idx] = System.nanoTime();
                    if (remaining.decrementAndGet() == 0) LockSupport.unpark(waiter);
                }
            });
        }
        while (remaining.get() > 0) {
            boolean didWork;
            try {
                didWork = pump.getAsBoolean();
            } catch (RuntimeException e) {
                // A failing main-thread task must not wedge the barrier; the
                // pump's own owner reports it. Keep waiting on the regions.
                didWork = true;
            }
            if (!didWork && remaining.get() > 0) LockSupport.parkNanos(100_000L);
        }
    }

    /**
     * Where a region's tick ran last: on a worker, or on the server thread
     * because it was the only region of its batch or because a worker would
     * spend too much of its tick handing serial-lane events off.
     */
    public enum TickPlacement {
        WORKER,
        SERVER_THREAD_SINGLE,
        SERVER_THREAD_HOT
    }

    /** Consecutive ticks over the threshold before a region goes to the server thread. */
    static final int HOT_ENTER_TICKS = 20;

    /** Quiet ticks (under a quarter of the threshold) before a hot region goes back to a worker. */
    static final int HOT_RELEASE_TICKS = 200;

    /** Assumed cost of one serial-lane hand-off until a worker has measured it. */
    static final long DEFAULT_HANDOFF_NANOS = 20_000L;

    private volatile boolean inlineSingleRegion = true;
    private volatile long serialLaneHotWaitNanos = 5_000_000L;

    /**
     * Measured cost of one serial-lane hand-off (the wait minus the job's run
     * time), a moving average over worker ticks. Server thread only.
     */
    private long handoffNanos = DEFAULT_HANDOFF_NANOS;

    /**
     * @param single tick a batch of one region on the calling thread
     * @param hotWaitMs serial-lane hand-off time per tick, in milliseconds, above
     *     which a region ticks on the calling thread after the parallel ones;
     *     0 or less: never
     */
    public void setInlinePolicy(boolean single, long hotWaitMs) {
        this.inlineSingleRegion = single;
        this.serialLaneHotWaitNanos = Math.max(0L, hotWaitMs) * 1_000_000L;
        if (hotWaitMs <= 0) {
            for (RegionState_ s : perRegion.values()) s.hot = false;
        }
    }

    /**
     * Server thread, after the barrier: move a region to the server thread when
     * handing its serial-lane events off costs it more than the threshold for
     * {@link #HOT_ENTER_TICKS} ticks, and back to a worker after {@link
     * #HOT_RELEASE_TICKS} ticks under a quarter of it. The decision is on time
     * lost, not on the number of events: what one hand-off costs depends on the
     * machine and its load (17 µs on average in one modpack server's probes). On a worker the cost is measured; on the server
     * thread, where nothing is handed off, it is estimated from the region's
     * posts and the measured cost of one hand-off.
     */
    private void updateHot(RegionState_ s, TickPlacement where) {
        long threshold = serialLaneHotWaitNanos;
        if (threshold <= 0) return;
        long posts = s.lastSerialPosts;
        long overhead;
        if (where == TickPlacement.WORKER) {
            overhead = s.lastSerialOverheadNanos;
            if (posts > 0) handoffNanos += (overhead / posts - handoffNanos) / 16;
        } else {
            overhead = posts * handoffNanos;
        }
        if (!s.hot) {
            if (overhead > threshold) {
                if (++s.overTicks >= HOT_ENTER_TICKS) {
                    s.hot = true;
                    s.overTicks = 0;
                    s.quietTicks = 0;
                    ProbeRegistry.bump("region-tick.hot");
                }
            } else {
                s.overTicks = 0;
            }
        } else if (overhead < threshold / 4) {
            if (++s.quietTicks >= HOT_RELEASE_TICKS) {
                s.hot = false;
                s.quietTicks = 0;
                ProbeRegistry.bump("region-tick.hot-released");
            }
        } else {
            s.quietTicks = 0;
        }
    }

    /** Measured cost of one serial-lane hand-off, in nanoseconds (for diagnostics and tests). */
    public long serialHandoffNanos() {
        return handoffNanos;
    }

    /** Serial-lane hand-off time of {@code region}'s last tick, in nanoseconds (0 on the server thread). */
    public long lastTickSerialOverheadNanos(Region region) {
        RegionState_ s = perRegion.get(region.id());
        return s == null ? 0L : s.lastSerialOverheadNanos;
    }

    /**
     * {@code region}'s own time in its last tick (designed waits left out), in
     * nanoseconds; 0 if it has not ticked. One map lookup, for per-tick decisions
     * such as entity activation's load shedding.
     */
    public long lastTickNanos(Region region) {
        RegionState_ s = perRegion.get(region.id());
        return s == null ? 0L : s.lastTickNanos;
    }

    /** Name of the thread that last ticked {@code region}, or null if it has not ticked. */
    public String lastTickThread(Region region) {
        RegionState_ s = perRegion.get(region.id());
        return s == null ? null : s.lastThread;
    }

    /** Serial-lane posts of {@code region}'s last tick (hand-offs on a worker, direct runs on the server thread). */
    public long lastTickSerialPosts(Region region) {
        RegionState_ s = perRegion.get(region.id());
        return s == null ? 0L : s.lastSerialPosts;
    }

    /** Where {@code region} last ticked, or null if it has not ticked. */
    public TickPlacement lastTickPlacement(Region region) {
        RegionState_ s = perRegion.get(region.id());
        return s == null ? null : s.placement;
    }

    public static final class TickAllResult {
        static final TickAllResult EMPTY = new TickAllResult(0, List.of());

        private final int regionCount;
        private final List<RegionId> overrunRegions;

        public TickAllResult(int regionCount, List<RegionId> overrunRegions) {
            this.regionCount = regionCount;
            this.overrunRegions = List.copyOf(overrunRegions);
        }

        public int regionCount() {
            return regionCount;
        }

        public List<RegionId> overrunRegions() {
            return overrunRegions;
        }

        public boolean allCompleted() {
            return overrunRegions.isEmpty();
        }
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
            int live = liveLoops.get();
            if (live > workerCount && liveLoops.compareAndSet(live, live - 1)) return; // pool shrank
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
                tickClaimed(s, region, true);
            } finally {
                // Deadline = start-of-tick + one tick period, not
                // now + one period — so slow regions catch up rather
                // than drift.
                long nextFire = Math.max(start + TICK_NANOS, System.nanoTime());
                queue.add(new ScheduleEntry(nextFire, region.id(), s));
            }
        }
    }

    /**
     * Run one tick of an already-claimed ({@link Region#tryMarkTicking()}
     * succeeded) region on the current worker, then release it. Shared by
     * both modes. A throwable from the tick is handed to the worker's
     * uncaught-exception handler when {@code reportUncaught} (free-running:
     * nobody else will see it), otherwise returned to the caller.
     */
    private Throwable tickClaimed(RegionState_ s, Region region, boolean reportUncaught) {
        long start = System.nanoTime();
        s.lastThread = Thread.currentThread().getName();
        RegionTickWatchdog.enterTick(region);
        try {
            OwnerToken.runAs(OwnerToken.forRegion(region.id().value()), () -> {
                taskQueue.drain(region, mailboxDrainBatch);
                body.tickOnce(region);
                taskQueue.drain(region, mailboxDrainBatch);
            });
            RegionTickWatchdog.exitTick(region);
        } catch (Throwable t) {
            // exitTick did not run (either body threw or the watchdog itself threw in STRICT mode);
            // ensure the watchdog's per-thread state is cleared before routing the exception.
            RegionTickWatchdog.exitTickAfterThrow();
            if (!reportUncaught) return t;
            Thread.currentThread().getUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), t);
        } finally {
            // The region's own time: designed waits (a chunk load it handed to the
            // server thread, a serial-lane hand-off) are left out, as the watchdog
            // leaves them out. The listeners the lane ran for it count, wherever
            // the region ticked, so moving it between a worker and the server
            // thread does not change its time.
            long own = Math.max(1L, System.nanoTime() - start - RegionTickWatchdog.lastTickWaitNanos());
            s.mspt.recordNanos(own);
            s.lastTickNanos = own;
            s.lastSerialPosts = RegionTickWatchdog.lastTickSerialPosts();
            s.lastSerialOverheadNanos = RegionTickWatchdog.lastTickSerialOverheadNanos();
            region.markNotTicking();
        }
        return null;
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
        volatile String lastThread;
        volatile long lastTickNanos;
        volatile long lastSerialPosts;
        volatile long lastSerialOverheadNanos;
        volatile TickPlacement placement;
        // Server thread only (driveTick): serial-lane heat.
        boolean hot;
        int overTicks;
        int quietTicks;

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
