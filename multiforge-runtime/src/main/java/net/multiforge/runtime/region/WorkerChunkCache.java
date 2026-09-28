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

import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.ApiStatus;

/**
 * A region worker's last four full-chunk reads, the worker-side counterpart of
 * the four-entry cache Vanilla's {@code ServerChunkCache} keeps for the server
 * thread ({@code lastChunkPos}/{@code lastChunk}). A worker reads chunks from
 * the chunk map's visible holders ({@code multiforge-patches/04-chunk-system/});
 * this skips the holder lookup when the same few chunks are read again.
 *
 * <p><b>Invalidation.</b> Every entry is tagged with the {@link #generation()}
 * read before the chunk was looked up, and served only while the generation is
 * unchanged. The server thread bumps it ({@link #invalidateAll}) wherever
 * Vanilla clears its own cache — {@code ServerChunkCache.clearCache}, after
 * any distance-manager update that changed a holder's status or the visible
 * holder map (a chunk unloading, a status downgrade) and at the end of every
 * chunk-source tick — and when a level's regions start ticking. Those are the
 * only places a loaded chunk's holder or full status changes, so a hit returns
 * exactly what the holder lookup would. A lookup racing with such a change may
 * return the chunk as it was just before, as the uncached read could.
 *
 * <p>Only a positive answer is cached, and only for {@code FULL} reads; values
 * are opaque ({@code LevelChunk} in the fork). Entries are keyed by the chunk
 * source instance and the packed chunk position, so levels and server restarts
 * never share an entry. Used only by the {@link RegionWorkerThread} owning it.
 *
 * <p>Kill switch: {@code [perf] workerChunkCache} ({@link #setEnabled}).
 */
@ApiStatus.Internal
public final class WorkerChunkCache {

    private static final int SIZE = 4;
    private static final AtomicLong GENERATION = new AtomicLong();
    private static volatile boolean enabled = true;

    private final Object[] owners = new Object[SIZE];
    private final long[] keys = new long[SIZE];
    private final Object[] values = new Object[SIZE];
    private long generation = -1L;

    /** The current worker's cache, or {@code null} off a region worker or while disabled. */
    public static WorkerChunkCache current() {
        if (!enabled) return null;
        return Thread.currentThread() instanceof RegionWorkerThread w ? w.chunkCache() : null;
    }

    /** Drop every worker's entries. Server thread, after the chunk state changed. */
    public static void invalidateAll() {
        GENERATION.incrementAndGet();
    }

    public static long generation() {
        return GENERATION.get();
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        GENERATION.incrementAndGet();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * The value cached for ({@code owner}, {@code key}), or {@code null}. On a
     * miss the caller looks the chunk up and hands a non-null result to {@link
     * #put}; the entry is tagged with the generation read here, before that
     * lookup.
     */
    public Object get(Object owner, long key) {
        long gen = GENERATION.get();
        if (gen != generation) {
            java.util.Arrays.fill(owners, null);
            java.util.Arrays.fill(values, null);
            generation = gen;
            return null;
        }
        for (int i = 0; i < SIZE; i++) {
            if (keys[i] == key && owners[i] == owner) return values[i];
        }
        return null;
    }

    /** Cache {@code value} (non-null) for ({@code owner}, {@code key}), most recent first. */
    public void put(Object owner, long key, Object value) {
        for (int i = SIZE - 1; i > 0; i--) {
            owners[i] = owners[i - 1];
            keys[i] = keys[i - 1];
            values[i] = values[i - 1];
        }
        owners[0] = owner;
        keys[0] = key;
        values[0] = value;
    }
}
