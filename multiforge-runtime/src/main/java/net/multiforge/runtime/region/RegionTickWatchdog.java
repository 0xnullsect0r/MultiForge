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
 *       signal the region should split.</li>
 * </ul>
 *
 * <p>Default {@link Mode#WARN} rate-limits a violation warning per
 * region-id and bumps {@link ProbeRegistry} so CI can query
 * {@code region-tick.overrun} for hard regression prevention.
 * {@link Mode#STRICT} additionally throws {@link
 * RegionTickOverrunException} from {@link #exitTick} — used only by
 * the regression-run flag {@code -Dmultiforge.regiontick.strict=on}.
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

    private RegionTickWatchdog() {}

    public static Mode mode() {
        return mode;
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
        TICK_START_NANOS.set(System.nanoTime());
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
        if (start == null) return; // enterTick wasn't called — defensive, should never happen
        long elapsedNs = System.nanoTime() - start;
        long elapsedMs = elapsedNs / 1_000_000L;
        if (elapsedMs < warnMs) return;

        ProbeRegistry.bump("region-tick.overrun");
        ViolationLogger.warn(
                "region-tick.overrun",
                "region " + region.id() + " tick body took " + elapsedMs + "ms (threshold " + warnMs + "ms) — "
                        + "likely blocking wait or a region that needs to split");

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
        TICK_START_NANOS.remove();
    }

    /**
     * Thrown by {@link #exitTick} only in {@link Mode#STRICT}. Never
     * thrown in production mode. Caught by
     * {@link TickRegionScheduler#runWorker}'s outer catch, which routes
     * it to the thread's uncaught handler and continues.
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
