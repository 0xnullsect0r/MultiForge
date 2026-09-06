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
package net.multiforge.runtime.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import net.multiforge.runtime.diagnostics.ViolationLogger;

/**
 * Bounded open-file pool for one world's MCA region files.
 *
 * <p>Byte-level contract lives in {@code docs/design/mca-format.md}. This class implements §3
 * (per-file {@code ReadWriteLock}: readers cross-region share, writers exclude) and §4
 * ({@code MAX_CACHE_SIZE = 256} default, LRU eviction, orderly flush + close).
 *
 * <h2>Concurrency contract</h2>
 *
 * <ul>
 *   <li>The cache's own map access is synchronized on {@code this} — every mutation of
 *       {@link #byPath} sits inside a {@code synchronized(this)} block.
 *   <li>Actual chunk I/O runs under a per-file {@link ReentrantReadWriteLock} held on the
 *       {@link Handle}. Readers take the shared read lock so concurrent cross-region reads
 *       proceed in parallel; writers and deletes take the exclusive write lock so they
 *       serialize against every reader in the same file. A thread holding a per-file lock
 *       must never re-enter {@code synchronized(this)} — that would risk a deadlock against
 *       an evictor that holds the monitor and waits on the same write lock. All I/O methods
 *       obey this by looking up the {@link Handle} under the monitor, releasing it, and only
 *       then acquiring the per-file lock.
 *   <li>LRU eviction fires when a fresh open would push the pool past {@link #maxOpen}. The
 *       eldest {@link Handle} (by access order in the backing {@link LinkedHashMap}) is
 *       removed from the map under the monitor, its write lock is then acquired (blocking on
 *       any in-flight reader/writer to drain), and its underlying {@link RegionFileReader}
 *       + {@link RegionFileWriter} are flushed and closed. This is idempotent — a second
 *       eviction of the same {@link Handle} would find it already removed from the map and
 *       fall through cleanly.
 *   <li>A miss opens both the {@link RegionFileReader} and {@link RegionFileWriter} under
 *       the monitor. Serializing opens keeps the two-writer race from ever materializing:
 *       two concurrent openers for the same path would otherwise install separate
 *       {@code usedSectors} bitmaps and clobber each other on the next allocate.
 * </ul>
 *
 * <h2>Reader freshness</h2>
 *
 * <p>{@link RegionFileReader} caches the 8192-byte header at construction and never mutates
 * it — that is what makes concurrent read lock sharing safe. After every write / delete we
 * therefore close and reopen the reader under the write lock so the next reader sees the
 * fresh header. Because the write lock is exclusive no reader can be mid-call during the
 * swap.
 *
 * <h2>Chunk → file mapping</h2>
 *
 * <p>{@code worldRegionDir/r.<regionX>.<regionZ>.mca} where {@code regionX = chunkX >> 5}
 * and {@code regionZ = chunkZ >> 5}, matching Vanilla
 * {@code RegionStorageInfo.getRegionFileName}.
 */
public final class RegionFileCache implements AutoCloseable {

    /** Default cap — matches Vanilla {@code RegionFileStorage.MAX_CACHE_SIZE}. */
    public static final int DEFAULT_MAX_OPEN = 256;

    private final Path worldRegionDir;
    private final int maxOpen;

    /**
     * Access-order map — iterating from the head yields eldest-first for LRU eviction, and
     * every {@link Map#get} on a hit bumps that entry to the tail. All access is under
     * {@code synchronized(this)}.
     */
    private final LinkedHashMap<Path, Handle> byPath;

    private volatile boolean closed;

    /**
     * Per-file I/O handles plus the read/write lock arbitrating them. The {@link #reader}
     * field is intentionally non-final: after each write we swap in a fresh reader (whose
     * cached header is up to date) under the write lock. The {@link #writer} keeps its
     * allocator state (used-sector {@code BitSet}) across calls and is only closed on
     * eviction.
     */
    private static final class Handle {
        final Path path;
        final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
        RegionFileReader reader;
        final RegionFileWriter writer;
        volatile long lastUsedNanos;

        Handle(Path path, RegionFileReader reader, RegionFileWriter writer) {
            this.path = path;
            this.reader = reader;
            this.writer = writer;
            this.lastUsedNanos = System.nanoTime();
        }
    }

    public RegionFileCache(Path worldRegionDir) {
        this(worldRegionDir, DEFAULT_MAX_OPEN);
    }

    public RegionFileCache(Path worldRegionDir, int maxOpen) {
        this.worldRegionDir = Objects.requireNonNull(worldRegionDir, "worldRegionDir");
        if (maxOpen <= 0) {
            throw new IllegalArgumentException("maxOpen must be > 0: " + maxOpen);
        }
        this.maxOpen = maxOpen;
        // Access-order LinkedHashMap: initial capacity 16, load factor 0.75, accessOrder=true.
        this.byPath = new LinkedHashMap<>(16, 0.75f, true);
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Read a chunk. Uses the shared read lock on the underlying file, so concurrent reads
     * across different chunk coordinates in the same region proceed in parallel.
     *
     * @return decompressed payload, or {@code null} if the slot is empty / corrupted (matches
     *     {@link RegionFileReader#readChunk} fail-soft semantics).
     */
    public byte[] readChunk(int chunkX, int chunkZ) throws IOException {
        Handle h = acquire(pathFor(chunkX, chunkZ));
        h.rwLock.readLock().lock();
        try {
            byte[] out = h.reader.readChunk(chunkX, chunkZ);
            h.lastUsedNanos = System.nanoTime();
            return out;
        } finally {
            h.rwLock.readLock().unlock();
        }
    }

    /**
     * Write a chunk. Uses the exclusive write lock on the underlying file. After the
     * writer's own {@link RegionFileWriter#writeChunk} completes (which allocates sectors,
     * flushes the payload, updates the header, and fsyncs), the cache swaps in a fresh
     * {@link RegionFileReader} so subsequent reads observe the new header.
     */
    public void writeChunk(int chunkX, int chunkZ, byte[] payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        Handle h = acquire(pathFor(chunkX, chunkZ));
        h.rwLock.writeLock().lock();
        try {
            h.writer.writeChunk(chunkX, chunkZ, payload);
            refreshReader(h);
            h.lastUsedNanos = System.nanoTime();
        } finally {
            h.rwLock.writeLock().unlock();
        }
    }

    /** Delete a chunk. Uses the exclusive write lock and refreshes the reader afterward. */
    public void deleteChunk(int chunkX, int chunkZ) throws IOException {
        Handle h = acquire(pathFor(chunkX, chunkZ));
        h.rwLock.writeLock().lock();
        try {
            h.writer.deleteChunk(chunkX, chunkZ);
            refreshReader(h);
            h.lastUsedNanos = System.nanoTime();
        } finally {
            h.rwLock.writeLock().unlock();
        }
    }

    /**
     * Flush + fsync every cached file. Takes each file's write lock in turn — waits on any
     * in-flight writer to drain, then blocks new readers/writers on that file for the
     * duration of the flush. Aggregates I/O errors via {@link Throwable#addSuppressed}.
     */
    public void flushAll() throws IOException {
        List<Handle> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(byPath.values());
        }
        IOException first = null;
        for (Handle h : snapshot) {
            h.rwLock.writeLock().lock();
            try {
                h.writer.flush();
            } catch (IOException e) {
                first = combine(first, e);
            } finally {
                h.rwLock.writeLock().unlock();
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /**
     * Flush and close every cached file. Further {@link #readChunk}/{@link #writeChunk}/{@link
     * #deleteChunk} calls throw. Idempotent — a second call is a no-op.
     */
    @Override
    public void close() throws IOException {
        List<Handle> snapshot;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            // Snapshot values now; clear the map below after each handle is drained so a
            // hypothetical in-flight caller that already resolved its Handle can still
            // finish its I/O without a NullPointerException.
            snapshot = new ArrayList<>(byPath.values());
        }
        IOException first = null;
        for (Handle h : snapshot) {
            h.rwLock.writeLock().lock();
            try {
                first = combine(first, closeHandleLocked(h));
            } finally {
                h.rwLock.writeLock().unlock();
            }
        }
        synchronized (this) {
            byPath.clear();
        }
        if (first != null) {
            throw first;
        }
    }

    // ---------------------------------------------------------------------
    // Test hooks (package-private)
    // ---------------------------------------------------------------------

    /** Test hook: number of files currently cached. */
    synchronized int cachedFileCount() {
        return byPath.size();
    }

    /** Test hook: true if {@code path} is currently in the pool. */
    synchronized boolean isCached(Path path) {
        return byPath.containsKey(path);
    }

    /**
     * Test hook: the read/write lock guarding the region file that owns {@code (chunkX,
     * chunkZ)}. Opens the file into the cache if not already present. Not part of the
     * public contract — production code must not reach past the {@code readChunk}/{@code
     * writeChunk}/{@code deleteChunk} API.
     */
    ReentrantReadWriteLock lockFor(int chunkX, int chunkZ) throws IOException {
        return acquire(pathFor(chunkX, chunkZ)).rwLock;
    }

    /** Test hook: the on-disk region file path for a chunk coordinate. */
    Path pathForChunk(int chunkX, int chunkZ) {
        return pathFor(chunkX, chunkZ);
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private Path pathFor(int chunkX, int chunkZ) {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        return worldRegionDir.resolve("r." + regionX + "." + regionZ + ".mca");
    }

    /**
     * Return the {@link Handle} for {@code path}, opening it (and evicting the LRU entry if
     * the pool is full) on a miss. All work happens under {@code synchronized(this)} so two
     * concurrent openers of the same path share the single instance produced by the winner.
     */
    private Handle acquire(Path path) throws IOException {
        synchronized (this) {
            if (closed) {
                throw new IOException("RegionFileCache is closed: " + worldRegionDir);
            }
            Handle existing = byPath.get(path); // access-order bump
            if (existing != null) {
                return existing;
            }
            while (byPath.size() >= maxOpen) {
                if (!evictOneEldestLocked()) {
                    // Can't happen (byPath.size() >= maxOpen implies at least one entry),
                    // but guard against infinite loop under a future refactor.
                    break;
                }
            }
            Files.createDirectories(worldRegionDir);
            RegionFileWriter writer = new RegionFileWriter(path);
            RegionFileReader reader;
            try {
                reader = new RegionFileReader(path);
            } catch (IOException | RuntimeException e) {
                try {
                    writer.close();
                } catch (IOException suppress) {
                    e.addSuppressed(suppress);
                }
                throw e;
            }
            Handle fresh = new Handle(path, reader, writer);
            byPath.put(path, fresh);
            return fresh;
        }
    }

    /**
     * Evict the eldest cache entry. Called with {@code synchronized(this)} held. Returns
     * {@code true} if an entry was found and evicted.
     *
     * <p>Deadlock argument: acquiring the write lock while holding the monitor is safe
     * because every thread that ever holds one of these write locks does so <em>outside</em>
     * the monitor. No thread ever holds a per-file lock and then re-enters
     * {@code synchronized(this)}, so there is no cycle. The worst case is a stall while an
     * in-flight write/read on the victim drains, which is acceptable.
     */
    private boolean evictOneEldestLocked() {
        Iterator<Map.Entry<Path, Handle>> it = byPath.entrySet().iterator();
        if (!it.hasNext()) {
            return false;
        }
        Map.Entry<Path, Handle> eldest = it.next();
        Handle victim = eldest.getValue();
        it.remove();
        // Acquire write lock to drain any in-flight caller before closing the channels
        // underneath them.
        victim.rwLock.writeLock().lock();
        try {
            IOException err = closeHandleLocked(victim);
            if (err != null) {
                ViolationLogger.warn(
                        "mca.cache.evict-failed",
                        "region file " + victim.path + " evicted with error: " + err.getMessage());
            }
        } finally {
            victim.rwLock.writeLock().unlock();
        }
        return true;
    }

    /**
     * Flush + close both channels of a handle. Caller holds the write lock. Returns the
     * first {@link IOException} encountered (with subsequent ones added as suppressed) or
     * {@code null} on clean close.
     */
    private static IOException closeHandleLocked(Handle h) {
        IOException first = null;
        try {
            h.writer.flush();
        } catch (IOException e) {
            first = combine(first, e);
        }
        try {
            h.reader.close();
        } catch (IOException e) {
            first = combine(first, e);
        }
        try {
            h.writer.close();
        } catch (IOException e) {
            first = combine(first, e);
        }
        return first;
    }

    /**
     * Swap in a fresh {@link RegionFileReader} whose cached header reflects the write we
     * just performed. Caller holds the write lock.
     */
    private static void refreshReader(Handle h) throws IOException {
        RegionFileReader old = h.reader;
        h.reader = new RegionFileReader(h.path);
        try {
            old.close();
        } catch (IOException e) {
            ViolationLogger.warn(
                    "mca.cache.reader-close-failed",
                    "closing stale reader for " + h.path + " after write failed: " + e.getMessage());
        }
    }

    private static IOException combine(IOException first, IOException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }
}
