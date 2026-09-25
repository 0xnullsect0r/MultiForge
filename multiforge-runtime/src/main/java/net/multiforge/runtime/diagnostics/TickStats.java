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
package net.multiforge.runtime.diagnostics;

import java.util.Arrays;
import java.util.Locale;
import org.jetbrains.annotations.ApiStatus;

/**
 * Whole-server tick timing, recorded once per {@code MinecraftServer.tickServer}.
 *
 * <p>Vanilla's {@code /tick query} only reports percentiles over its last
 * 100 ticks and has no maximum. This keeps every tick since the last
 * {@link #reset()}: the true maximum, and a ring of the last {@value
 * #WINDOW} ticks' durations and end times, so a sustained TPS figure over a
 * real ten-minute window is a count, not a projection from the mean.
 *
 * <p>Only the server thread records; readers (the {@code /multiforge
 * tickstats} command, also on the server thread) take a consistent snapshot
 * under the monitor. Recording is a few array writes per tick.
 */
@ApiStatus.Internal
public final class TickStats {
    /** Ring size: ten minutes at 20 TPS, plus slack. */
    public static final int WINDOW = 12_800;

    private static final long TEN_MINUTES_NANOS = 600_000_000_000L;

    private static final long[] DURATIONS = new long[WINDOW];
    private static final long[] END_TIMES = new long[WINDOW];
    private static long count;
    private static long maxNanos;
    private static long totalNanos;

    private TickStats() {}

    /** Record one tick that took {@code durationNanos} and ended at {@code endNanos} ({@link System#nanoTime()}). */
    public static synchronized void record(long durationNanos, long endNanos) {
        int slot = (int) (count % WINDOW);
        DURATIONS[slot] = durationNanos;
        END_TIMES[slot] = endNanos;
        count++;
        totalNanos += durationNanos;
        if (durationNanos > maxNanos) maxNanos = durationNanos;
    }

    public static synchronized void reset() {
        count = 0;
        maxNanos = 0;
        totalNanos = 0;
    }

    /**
     * Consistent view of the recorded ticks.
     *
     * @param ticks ticks recorded since the last reset
     * @param meanMs mean tick duration over all of them
     * @param maxMs longest single tick over all of them
     * @param p50Ms median over the retained window (the last {@value #WINDOW} ticks)
     * @param p95Ms 95th percentile over the retained window
     * @param p99Ms 99th percentile over the retained window
     * @param tps ticks per second over the last ten minutes of wall time, or since
     *     the oldest retained tick when that is more recent
     * @param tpsWindowSeconds the wall-time span {@code tps} was measured over
     */
    public record Snapshot(
            long ticks,
            double meanMs,
            double maxMs,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            double tps,
            double tpsWindowSeconds) {

        /** One line, {@code key=value} pairs, parsed by the bench harness. */
        public String render() {
            return String.format(
                    Locale.ROOT,
                    "ticks=%d mean=%.3fms p50=%.3fms p95=%.3fms p99=%.3fms max=%.3fms tps=%.2f window=%.1fs",
                    ticks,
                    meanMs,
                    p50Ms,
                    p95Ms,
                    p99Ms,
                    maxMs,
                    tps,
                    tpsWindowSeconds);
        }
    }

    public static synchronized Snapshot snapshot() {
        return snapshot(System.nanoTime());
    }

    static synchronized Snapshot snapshot(long nowNanos) {
        if (count == 0) return new Snapshot(0, 0, 0, 0, 0, 0, 0, 0);
        int retained = (int) Math.min(count, WINDOW);
        long[] sorted = new long[retained];
        System.arraycopy(DURATIONS, 0, sorted, 0, retained);
        Arrays.sort(sorted);

        // Ticks that ended within the last ten minutes, walking back from the newest.
        long windowStart = nowNanos - TEN_MINUTES_NANOS;
        long inWindow = 0;
        for (long i = count - 1; i >= count - retained; i--) {
            if (END_TIMES[(int) (i % WINDOW)] < windowStart) break;
            inWindow++;
        }
        // When every retained tick is inside the window, the ring (or the run) is
        // younger than ten minutes: measure from the oldest retained tick's end
        // and count only the ticks after it.
        long ticksCounted = inWindow;
        long start = windowStart;
        if (inWindow == retained) {
            start = END_TIMES[(int) ((count - retained) % WINDOW)];
            ticksCounted = inWindow - 1;
        }
        double spanNanos = Math.max(1L, nowNanos - start);
        double tps = ticksCounted / (spanNanos / 1e9);
        return new Snapshot(
                count,
                totalNanos / (double) count / 1e6,
                maxNanos / 1e6,
                percentile(sorted, 0.50),
                percentile(sorted, 0.95),
                percentile(sorted, 0.99),
                tps,
                spanNanos / 1e9);
    }

    private static double percentile(long[] sorted, double q) {
        int idx = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))] / 1e6;
    }
}
