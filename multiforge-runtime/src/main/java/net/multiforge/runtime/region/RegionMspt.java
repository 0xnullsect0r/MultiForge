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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Fixed-window MSPT (milliseconds per tick) tracker for a region. Uses
 * a lock-free ring buffer so the tick worker can record samples in the
 * hot path and diagnostics reads happen off-thread.
 *
 * <p>The window size is a construction-time constant; 100 samples at
 * 20 TPS = 5 seconds of history, which matches Folia's
 * {@code getTickReport5s}.
 */
public final class RegionMspt {

    private final AtomicLongArray samplesNanos;
    private final AtomicInteger cursor = new AtomicInteger();
    private final int capacity;

    public RegionMspt(int windowTicks) {
        if (windowTicks <= 0) throw new IllegalArgumentException("windowTicks must be > 0");
        this.capacity = windowTicks;
        this.samplesNanos = new AtomicLongArray(windowTicks);
    }

    public void recordNanos(long nanos) {
        int idx = Math.floorMod(cursor.getAndIncrement(), capacity);
        samplesNanos.set(idx, nanos);
    }

    /** Rolling average, in milliseconds; 0 if no samples yet. */
    public double averageMillis() {
        long total = 0L;
        int filled = 0;
        for (int i = 0; i < capacity; i++) {
            long v = samplesNanos.get(i);
            if (v > 0) {
                total += v;
                filled++;
            }
        }
        if (filled == 0) return 0.0;
        return (total / (double) filled) / 1_000_000.0;
    }

    /** Percentile in milliseconds (approximate — sorts a snapshot). */
    public double percentileMillis(double p) {
        if (p <= 0 || p >= 1) throw new IllegalArgumentException("p must be in (0,1)");
        long[] copy = new long[capacity];
        int filled = 0;
        for (int i = 0; i < capacity; i++) {
            long v = samplesNanos.get(i);
            if (v > 0) copy[filled++] = v;
        }
        if (filled == 0) return 0.0;
        java.util.Arrays.sort(copy, 0, filled);
        int idx = (int) Math.floor(p * filled);
        if (idx >= filled) idx = filled - 1;
        return copy[idx] / 1_000_000.0;
    }
}
