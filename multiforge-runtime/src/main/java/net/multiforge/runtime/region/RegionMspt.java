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

    /**
     * Seed a new region's history from the region it split off, so its heat does not
     * start from nothing (an empty window read as a cheap region for its first ticks,
     * which showed up as the heatmap briefly dropping to green or yellow). Each of
     * {@code source}'s samples is copied oldest first and scaled by {@code share},
     * the child's share of the work. Nothing measures that share at split time, so
     * the caller passes an estimate (the child's share of the sections); the child's
     * own samples replace the estimate within one window.
     */
    public void seedFrom(RegionMspt source, double share) {
        if (share <= 0) return;
        long[] samples = source.samplesOldestFirst();
        for (long v : samples) recordNanos(Math.max(1L, Math.round(v * Math.min(1.0, share))));
    }

    /**
     * Fold a region that is merging into this one: the merged region does both
     * regions' work, so its expected tick time is the sum. Samples are added pairwise
     * by recency (newest with newest), and a slot only {@code other} had is taken
     * as is.
     */
    public void absorb(RegionMspt other) {
        long[] mine = samplesOldestFirst();
        long[] theirs = other.samplesOldestFirst();
        int n = Math.max(mine.length, theirs.length);
        long[] merged = new long[Math.min(n, capacity)];
        for (int k = 0; k < merged.length; k++) {
            // k counts back from the newest sample of each window.
            long a = k < mine.length ? mine[mine.length - 1 - k] : 0L;
            long b = k < theirs.length ? theirs[theirs.length - 1 - k] : 0L;
            merged[merged.length - 1 - k] = a + b;
        }
        for (int i = 0; i < capacity; i++) samplesNanos.set(i, 0L);
        cursor.set(0);
        for (long v : merged) recordNanos(v);
    }

    /** The recorded samples, oldest first; empty slots are left out. */
    long[] samplesOldestFirst() {
        int written = cursor.get();
        // A cursor past Integer.MAX_VALUE wraps negative; the window is full by then.
        int filled = written < 0 ? capacity : Math.min(written, capacity);
        long[] out = new long[filled];
        int n = 0;
        for (int i = written - filled; i < written; i++) {
            long v = samplesNanos.get(Math.floorMod(i, capacity));
            if (v > 0) out[n++] = v;
        }
        return n == filled ? out : java.util.Arrays.copyOf(out, n);
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
