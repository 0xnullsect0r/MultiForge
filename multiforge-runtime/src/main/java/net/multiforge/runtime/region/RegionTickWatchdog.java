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

import java.util.Locale;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;

/**
 * Detects region ticks that exceed a per-tick deadline — the primary
 * safety net for M8-and-beyond work that starts moving real
 * mutation-heavy code onto region worker threads. A tick body that
 * runs long is almost always evidence of one of:
 *
 * <ul>
 *   <li>a blocking wait (Future.get, wait(), synchronized-on-contended-lock)
 *       reached from a region worker — the anti-pattern
 *       {@code docs/blueprint.md} §Cross-Region Comm calls out as a
 *       deadlock/jitter risk;</li>
 *   <li>a mod handler stuck in an infinite loop;</li>
 *   <li>legitimate work that's just too heavy for a single tick — a
 *       sign that one region carries too much (see docs/perf-tuning.md).</li>
 * </ul>
 *
 * <p>Default {@link Mode#WARN} rate-limits a violation warning per
 * region-id and bumps {@link ProbeRegistry} so CI can query
 * {@code region-tick.overrun} for hard regression prevention.
 * {@link Mode#STRICT} additionally throws {@link
 * RegionTickOverrunException} from {@link #exitTick} — used only by
 * the regression-run flag {@code -Dmultiforge.regiontick.strict=on}.
 *
 * <p><b>Designed waits do not count.</b> Two waits are part of the
 * design, bounded, and not the region's own work: a hand-off to the server
 * thread for a chunk that is not loaded yet (its generation can take
 * hundreds of milliseconds), and a hand-off to the serial event lane. The
 * listeners the lane runs for the region are its own work, though, and
 * count, as they do when the region ticks on the server thread and runs them
 * directly (see {@link #endWait(String, long)}). Their callers bracket them with {@link #beginWait()}/{@link #endWait()};
 * the overrun check measures the tick minus that time, and the waits are
 * totalled in the probes {@code region-tick.wait-ms.<kind>}. Anything else
 * that stalls a region tick — a {@code Future.get}, a contended lock, a
 * loop — still counts.
 *
 * <p>Deliberately checks only at {@link #exitTick} time, not via a
 * background sweeper thread. This misses a worker that's stuck
 * forever (endTick never called), but that case is already visible
 * via {@link RegionMspt} exposed on the scheduler, and the
 * extra-thread cost isn't worth the marginal coverage.
 */
public final class RegionTickWatchdog {

    /** Selected via {@code -Dmultiforge.regiontick.strict}: off (default) → WARN; on → STRICT. */
    public enum Mode {
        /** Rate-limited log warning + probe bump. Never throws. */
        WARN,
        /** Same as WARN plus throws {@link RegionTickOverrunException} at exitTick. */
        STRICT,
    }

    private static final String STRICT_PROP = "multiforge.regiontick.strict";
    private static final String WARN_MS_PROP = "multiforge.watchdog.warn-ms";
    private static final long DEFAULT_WARN_MS = 500L; // 10× the 50ms tick period

    private static volatile Mode mode = parseMode(System.getProperty(STRICT_PROP, "off"));
    private static volatile long warnMs = parseWarnMs(System.getProperty(WARN_MS_PROP));

    /**
     * Robust parse that never throws — an invalid sysprop value falls
     * back to {@link #DEFAULT_WARN_MS} instead of an
     * {@code ExceptionInInitializerError} that would kill every worker
     * thread on the pool's first tick (workers are not replaced by
     * {@link java.util.concurrent.Executors#newFixedThreadPool}).
     */
    public static long parseWarnMs(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_WARN_MS;
        try {
            long parsed = Long.parseLong(raw.trim());
            // Accept 0 as a documented "always warn" idiom; only negative
            // values (nonsensical) fall back to the default. Earlier revision
            // rejected 0 too, which silently coerced a legitimate operator
            // knob to the default — see /67 round-2 finding.
            return parsed < 0 ? DEFAULT_WARN_MS : parsed;
        } catch (NumberFormatException e) {
            return DEFAULT_WARN_MS;
        }
    }

    private static final ThreadLocal<Long> TICK_START_NANOS = new ThreadLocal<>();

    /** Per worker: {nanos waited this tick, start of the wait in progress or 0, nesting depth}. */
    private static final ThreadLocal<long[]> WAIT = ThreadLocal.withInitial(() -> new long[3]);

    /**
     * Per worker: each wait kind's {nanos, count} this tick plus a carry of
     * nanos not yet reported as a whole millisecond, and the serial-lane
     * posts of this tick and of the last one. Flushed to the probes once per
     * tick, so a hot wait costs no map update per wait, and sub-millisecond
     * waits add up instead of each rounding to zero.
     */
    private static final ThreadLocal<Tally> TALLY = ThreadLocal.withInitial(Tally::new);

    private static final class Tally {
        final java.util.HashMap<String, long[]> kinds = new java.util.HashMap<>();
        long serialThisTick;
        long serialLastTick;
        /** Serial-lane hand-off overhead (wait minus the jobs' own run time) this tick and last. */
        long serialOverheadThisTick;

        long serialOverheadLastTick;
    }

    /** {@code region-tick.wait-ms.<kind>}, {@code .wait-ns.<kind>}, {@code .waits.<kind>} per kind. */
    private static final java.util.concurrent.ConcurrentMap<String, String[]> WAIT_KEYS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * What one thread is ticking right now, read by {@code HangReporter} to tag a
     * stalled worker's stack with its region and phase. Written only by the
     * owning thread (plain volatile writes, no lock, no allocation per tick);
     * one per thread that ever ticked a region.
     */
    public static final class ActiveTick {
        private final java.lang.ref.WeakReference<Thread> thread;
        private volatile boolean ticking;
        private volatile long regionId = -1L;
        private volatile long startNanos;
        private volatile String phase;

        ActiveTick(Thread thread) {
            this.thread = new java.lang.ref.WeakReference<>(thread);
        }

        /** The thread, or {@code null} once it died. */
        public Thread thread() {
            return thread.get();
        }

        public boolean ticking() {
            return ticking;
        }

        public long regionId() {
            return regionId;
        }

        /** {@link System#nanoTime()} at the start of the region tick in progress. */
        public long startNanos() {
            return startNanos;
        }

        /** The phase last noted by {@link #notePhase}, or {@code null} (mailbox, before the first phase). */
        public String phase() {
            return phase;
        }
    }

    private static final java.util.concurrent.ConcurrentLinkedQueue<ActiveTick> ACTIVE_TICKS =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static final ThreadLocal<ActiveTick> ACTIVE = ThreadLocal.withInitial(RegionTickWatchdog::registerActive);

    private static ActiveTick registerActive() {
        ActiveTick a = new ActiveTick(Thread.currentThread());
        ACTIVE_TICKS.add(a);
        return a;
    }

    /** Threads ticking a region right now (a snapshot; dead threads are pruned). Any thread may call. */
    public static java.util.List<ActiveTick> activeTicks() {
        java.util.List<ActiveTick> out = new java.util.ArrayList<>();
        for (java.util.Iterator<ActiveTick> it = ACTIVE_TICKS.iterator(); it.hasNext(); ) {
            ActiveTick a = it.next();
            Thread t = a.thread();
            if (t == null || !t.isAlive()) {
                it.remove();
                continue;
            }
            if (a.ticking) out.add(a);
        }
        return out;
    }

    /** The calling thread's region tick entered {@code phase}; no-op outside a region tick. */
    public static void notePhase(String phase) {
        ActiveTick a = ACTIVE.get();
        if (a.ticking) a.phase = phase;
    }

    private RegionTickWatchdog() {}

    public static Mode mode() {
        return mode;
    }

    /**
     * Select the watchdog mode (the server applies its configured {@code
     * mode = "strict"} through this). {@code -Dmultiforge.regiontick.strict}
     * sets the initial value.
     */
    public static void setMode(Mode m) {
        mode = java.util.Objects.requireNonNull(m, "m");
    }

    /** Test-only: set the mode directly without going through a system property. */
    public static void setModeForTesting(Mode m) {
        mode = m;
    }

    /** Test-only: adjust the warn threshold in ms. */
    public static void setWarnMsForTesting(long ms) {
        warnMs = ms;
    }

    /** Test-only: reset the state so a subsequent test sees a clean watchdog. */
    public static void resetForTesting() {
        mode = Mode.WARN;
        warnMs = DEFAULT_WARN_MS;
        TICK_START_NANOS.remove();
    }

    static Mode parseMode(String raw) {
        String trimmed = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.equals("on") || trimmed.equals("strict") || trimmed.equals("true")) return Mode.STRICT;
        return Mode.WARN;
    }

    /** Called by {@link TickRegionScheduler} before it invokes the region tick body. */
    public static void enterTick(Region region) {
        long[] wait = WAIT.get();
        wait[0] = 0;
        wait[1] = 0;
        wait[2] = 0;
        Tally tally = TALLY.get();
        tally.serialThisTick = 0;
        tally.serialOverheadThisTick = 0;
        long now = System.nanoTime();
        TICK_START_NANOS.set(now);
        ActiveTick active = ACTIVE.get();
        active.regionId = region.id().value();
        active.phase = null;
        active.startNanos = now;
        active.ticking = true;
    }

    /**
     * The calling thread's region tick sent one job to the serial lane (or,
     * ticking on the server thread, ran one there directly). No-op outside a
     * region tick.
     */
    public static void countSerialPost() {
        if (TICK_START_NANOS.get() == null) return;
        TALLY.get().serialThisTick++;
    }

    /** Serial-lane posts made by the calling thread's most recent region tick. */
    public static long lastTickSerialPosts() {
        return TALLY.get().serialLastTick;
    }

    /**
     * Serial-lane hand-off overhead of the calling thread's most recent region
     * tick: time spent waiting for the lane minus the jobs' own run time. Zero
     * for a region ticked on the server thread, which runs its jobs directly.
     */
    public static long lastTickSerialOverheadNanos() {
        return TALLY.get().serialOverheadLastTick;
    }

    /** Nanoseconds of designed waits in the calling worker's most recent region tick. */
    public static long lastTickWaitNanos() {
        return WAIT.get()[0];
    }

    /** Whether the calling thread is ticking a region right now (a worker, or the server thread ticking one inline). */
    public static boolean inTick() {
        return TICK_START_NANOS.get() != null;
    }

    /**
     * The calling thread starts a designed wait (see the class doc). No-op
     * outside a region tick; nests.
     */
    public static void beginWait() {
        if (TICK_START_NANOS.get() == null) return;
        long[] wait = WAIT.get();
        if (wait[2]++ == 0) wait[1] = System.nanoTime();
    }

    /**
     * The designed wait begun by {@link #beginWait()} is over; {@code kind}
     * names it in the {@code region-tick.wait-*.<kind>} probes, reported when
     * the tick ends.
     */
    public static void endWait(String kind) {
        endWait(kind, 0L);
    }

    /**
     * Like {@link #endWait(String)}, for a wait during which {@code workNanos}
     * of the region's own work ran on another thread on its behalf: a
     * serial-lane job runs the region's listeners on the server thread while
     * the worker waits. That work counts as the region's time, as it does when
     * the region ticks on the server thread and runs the job itself; only the
     * rest of the wait, the hand-off, is left out.
     */
    public static void endWait(String kind, long workNanos) {
        if (TICK_START_NANOS.get() == null) return;
        long[] wait = WAIT.get();
        if (wait[2] == 0) return;
        if (--wait[2] == 0) {
            long waited = Math.max(0L, System.nanoTime() - wait[1] - Math.max(0L, workNanos));
            wait[0] += waited;
            Tally t = TALLY.get();
            long[] tally = t.kinds.computeIfAbsent(kind, k -> new long[3]);
            tally[0] += waited;
            tally[1]++;
            if (SERIAL_LANE.equals(kind)) t.serialOverheadThisTick += waited;
        }
    }

    /** The wait kind of a serial-lane hand-off. */
    public static final String SERIAL_LANE = "serial-lane";

    /** Report this tick's designed waits and serial posts to the probes, and start the next tick's tally. */
    private static void flushTally() {
        Tally tally = TALLY.get();
        tally.serialLastTick = tally.serialThisTick;
        tally.serialThisTick = 0;
        tally.serialOverheadLastTick = tally.serialOverheadThisTick;
        tally.serialOverheadThisTick = 0;
        for (java.util.Map.Entry<String, long[]> e : tally.kinds.entrySet()) {
            long[] k = e.getValue();
            if (k[1] == 0) continue;
            String[] keys = WAIT_KEYS.computeIfAbsent(e.getKey(), kind -> new String[] {
                "region-tick.wait-ms." + kind, "region-tick.wait-ns." + kind, "region-tick.waits." + kind
            });
            long total = k[0] + k[2];
            ProbeRegistry.add(keys[0], total / 1_000_000L);
            ProbeRegistry.add(keys[1], k[0]);
            ProbeRegistry.add(keys[2], k[1]);
            k[2] = total % 1_000_000L;
            k[0] = 0;
            k[1] = 0;
        }
    }

    /**
     * Called by {@link TickRegionScheduler} after the region tick body
     * returns (or throws). If the elapsed time exceeds the warn
     * threshold, records a violation.
     *
     * @throws RegionTickOverrunException in {@link Mode#STRICT} only.
     */
    public static void exitTick(Region region) {
        Long start = TICK_START_NANOS.get();
        TICK_START_NANOS.remove();
        ACTIVE.get().ticking = false;
        if (start == null) return; // enterTick wasn't called — defensive, should never happen
        flushTally();
        long waitedNs = WAIT.get()[0];
        long elapsedNs = System.nanoTime() - start - waitedNs;
        long elapsedMs = elapsedNs / 1_000_000L;
        if (elapsedMs < warnMs) return;

        ProbeRegistry.bump("region-tick.overrun");
        ViolationLogger.warn(
                "region-tick.overrun",
                "region " + region.id() + " tick body took " + elapsedMs + "ms (threshold " + warnMs + "ms, "
                        + waitedNs / 1_000_000L + "ms of designed waits not counted) — "
                        + "likely a blocking wait, or more work than one region should carry");

        if (mode == Mode.STRICT) {
            throw new RegionTickOverrunException(region, elapsedMs, warnMs);
        }
    }

    /**
     * Called by {@link TickRegionScheduler}'s catch block when the tick
     * body itself (or {@link #exitTick}'s STRICT-mode throw) already
     * propagated an exception. Clears the per-thread state so the next
     * tick body on this worker gets a clean {@link #enterTick} state
     * — without re-firing the violation warning that {@link #exitTick}
     * already fired (if the exception came from a strict-mode overrun)
     * or without silently absorbing an overrun the body itself masked.
     */
    public static void exitTickAfterThrow() {
        if (TICK_START_NANOS.get() != null) flushTally();
        TICK_START_NANOS.remove();
        ACTIVE.get().ticking = false;
    }

    /**
     * Thrown by {@link #exitTick} only in {@link Mode#STRICT}. Never thrown in
     * the default mode. Like any region-tick exception it is rethrown on the
     * server thread once the tick barrier completes, where Vanilla's "Exception
     * ticking world" handling stops the server: strict mode is for regression
     * runs that must fail loudly, not for production.
     */
    public static final class RegionTickOverrunException extends RuntimeException {
        private final RegionId regionId;
        private final long elapsedMs;
        private final long thresholdMs;

        public RegionTickOverrunException(Region region, long elapsedMs, long thresholdMs) {
            super("Region " + region.id() + " tick overran: " + elapsedMs + "ms > " + thresholdMs + "ms");
            this.regionId = region.id();
            this.elapsedMs = elapsedMs;
            this.thresholdMs = thresholdMs;
        }

        public RegionId regionId() {
            return regionId;
        }

        public long elapsedMs() {
            return elapsedMs;
        }

        public long thresholdMs() {
            return thresholdMs;
        }
    }
}
