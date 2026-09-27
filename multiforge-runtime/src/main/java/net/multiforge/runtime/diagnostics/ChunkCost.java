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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.ApiStatus;

/**
 * Tick time per chunk, for the debug client's heatmap.
 *
 * <p>A region's tick time says what its thread spent, not where. This
 * attributes the work that has a position to the chunk it happened in:
 * each entity to the chunk it was in when its tick began, each block
 * entity, scheduled block or fluid tick and chunk tick (random ticks,
 * natural spawning) to its own chunk. A call site brackets one such unit:
 *
 * <pre>{@code
 * long t = ChunkCost.start();
 * ... tick one entity ...
 * ChunkCost.end(chunkX, chunkZ, t);
 * }</pre>
 *
 * <p>Samples collect in a per-thread table and move to the world's window
 * when the region's phase ends ({@link #flush}); the heatmap emitter drains
 * the window ({@link #drain}) four times a second. Nothing is measured
 * unless {@link #setEnabled} is on (the debug channel turns it on while a
 * client subscribes to the heatmap) and a level's regions are ticking
 * ({@link #beginLevelTick}); otherwise {@link #start} is one volatile read.
 */
@ApiStatus.Internal
public final class ChunkCost {

    private static volatile boolean enabled;
    /** Set while a level's regions tick and {@link #enabled} is on. */
    private static volatile boolean sampling;

    private static final ThreadLocal<Accumulator> LOCAL = ThreadLocal.withInitial(Accumulator::new);
    private static final Map<String, Window> WINDOWS = new ConcurrentHashMap<>();

    private ChunkCost() {}

    /** Turn measurement on or off (the debug channel: on while anyone watches the heatmap). */
    public static void setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            sampling = false;
            WINDOWS.clear();
        }
    }

    public static boolean enabled() {
        return enabled;
    }

    /**
     * Server thread, before a level's regions tick: sample this tick if
     * measurement is on, and count it toward {@code worldId}'s window.
     */
    public static void beginLevelTick(String worldId) {
        if (!enabled) return;
        WINDOWS.computeIfAbsent(worldId, id -> new Window()).countTick();
        sampling = true;
    }

    /** Server thread, after a level's regions finished. */
    public static void endLevelTick() {
        sampling = false;
    }

    /** Start timing one unit of positioned work; 0 when nothing is measured. */
    public static long start() {
        return sampling ? System.nanoTime() : 0L;
    }

    /** Charge the time since {@code startNanos} to chunk ({@code chunkX}, {@code chunkZ}). */
    public static void end(int chunkX, int chunkZ, long startNanos) {
        if (startNanos == 0L) return;
        add(chunkX, chunkZ, System.nanoTime() - startNanos);
    }

    /** Charge {@code nanos} already measured to chunk ({@code chunkX}, {@code chunkZ}) on this thread. */
    public static void add(int chunkX, int chunkZ, long nanos) {
        if (nanos > 0) LOCAL.get().add(pack(chunkX, chunkZ), nanos);
    }

    /**
     * Move this thread's samples into {@code worldId}'s window. Called when a
     * region's phase ends, on the thread that ran it.
     */
    public static void flush(String worldId) {
        Accumulator local = LOCAL.get();
        if (local.size == 0) return;
        Window window = enabled ? WINDOWS.get(worldId) : null;
        if (window == null) {
            local.clear();
            return;
        }
        window.addAll(local);
        local.clear();
    }

    /**
     * Take {@code worldId}'s samples since the last drain: nanoseconds per
     * chunk and the number of level ticks they cover. Empty when nothing was
     * measured.
     */
    public static Drained drain(String worldId) {
        Window window = WINDOWS.get(worldId);
        return window == null ? Drained.EMPTY : window.drain();
    }

    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }

    public static int unpackX(long key) {
        return (int) key;
    }

    public static int unpackZ(long key) {
        return (int) (key >>> 32);
    }

    /** Test hook: forget every window and this thread's samples. */
    public static void resetForTesting() {
        enabled = false;
        sampling = false;
        WINDOWS.clear();
        LOCAL.get().clear();
    }

    /** One drain: parallel arrays of chunk keys and nanoseconds, and the ticks they cover. */
    public record Drained(long[] keys, long[] nanos, int size, long ticks) {
        static final Drained EMPTY = new Drained(new long[0], new long[0], 0, 0);
    }

    /** A world's samples between two drains. Guarded by its monitor. */
    private static final class Window {
        private final Accumulator totals = new Accumulator();
        private long ticks;

        synchronized void countTick() {
            ticks++;
        }

        synchronized void addAll(Accumulator from) {
            long[] keys = from.keys;
            long[] vals = from.vals;
            for (int i = 0; i < keys.length; i++) {
                if (vals[i] != 0L) totals.add(keys[i], vals[i]);
            }
        }

        synchronized Drained drain() {
            long[] keys = new long[totals.size];
            long[] nanos = new long[totals.size];
            int n = 0;
            for (int i = 0; i < totals.keys.length; i++) {
                if (totals.vals[i] != 0L) {
                    keys[n] = totals.keys[i];
                    nanos[n] = totals.vals[i];
                    n++;
                }
            }
            Drained out = new Drained(keys, nanos, n, ticks);
            totals.clear();
            ticks = 0;
            return out;
        }
    }

    /**
     * Open-addressed long→long sum table. A slot is live when its value is
     * non-zero (every sample is positive), so no separate occupancy array.
     */
    static final class Accumulator {
        long[] keys = new long[64];
        long[] vals = new long[64];
        int size;

        void add(long key, long nanos) {
            int mask = keys.length - 1;
            int i = mix(key) & mask;
            while (vals[i] != 0L) {
                if (keys[i] == key) {
                    vals[i] += nanos;
                    return;
                }
                i = (i + 1) & mask;
            }
            keys[i] = key;
            vals[i] = nanos;
            if (++size * 2 > keys.length) grow();
        }

        void clear() {
            if (size == 0) return;
            Arrays.fill(vals, 0L);
            size = 0;
        }

        private void grow() {
            long[] oldKeys = keys;
            long[] oldVals = vals;
            keys = new long[oldKeys.length * 2];
            vals = new long[oldVals.length * 2];
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldVals[i] != 0L) add(oldKeys[i], oldVals[i]);
            }
        }

        private static int mix(long key) {
            long h = key * 0x9E3779B97F4A7C15L;
            return (int) (h ^ (h >>> 32));
        }
    }
}
