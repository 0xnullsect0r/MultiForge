/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Correctness + concurrency tests for {@link RegionFileCache}. Exercises the four contract
 * lines from {@code docs/design/mca-format.md} §3–§4: cross-region reads share the read
 * lock, writes take the write lock exclusively, LRU eviction closes the eldest file on
 * cap-overflow, and round-trips survive across many chunks + regions.
 */
class RegionFileCacheTest {

    // ---------------------------------------------------------------------
    // 1. Round-trip across many chunks + many region files.
    // ---------------------------------------------------------------------

    @Test
    void readsAndWritesAcrossManyChunks(@TempDir Path dir) throws IOException {
        try (RegionFileCache cache = new RegionFileCache(dir)) {
            int n = 200;
            Map<Long, byte[]> expected = new HashMap<>();
            Random rng = new Random(0xC0FFEE);
            for (int i = 0; i < n; i++) {
                // Spread chunks over many region files: chunkX in [0..199], chunkZ in
                // [0..79] → region coords span (0..6) × (0..2), 21 distinct region
                // files, all comfortably under the 256-file default cap.
                int cx = i;
                int cz = (i * 3) % 80;
                byte[] payload = new byte[64 + rng.nextInt(256)];
                rng.nextBytes(payload);
                cache.writeChunk(cx, cz, payload);
                expected.put(key(cx, cz), payload);
            }
            for (Map.Entry<Long, byte[]> e : expected.entrySet()) {
                int cx = keyX(e.getKey());
                int cz = keyZ(e.getKey());
                assertThat(cache.readChunk(cx, cz))
                        .as("round-trip for chunk (" + cx + "," + cz + ")")
                        .isEqualTo(e.getValue());
            }
            // Delete a subset and verify they read back null; survivors unchanged.
            List<Long> keys = new ArrayList<>(expected.keySet());
            for (int i = 0; i < keys.size(); i += 5) {
                long k = keys.get(i);
                cache.deleteChunk(keyX(k), keyZ(k));
            }
            for (int i = 0; i < keys.size(); i++) {
                long k = keys.get(i);
                int cx = keyX(k);
                int cz = keyZ(k);
                if (i % 5 == 0) {
                    assertThat(cache.readChunk(cx, cz))
                            .as("deleted chunk (" + cx + "," + cz + ") is gone")
                            .isNull();
                } else {
                    assertThat(cache.readChunk(cx, cz))
                            .as("surviving chunk (" + cx + "," + cz + ") unchanged")
                            .isEqualTo(expected.get(k));
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // 2. LRU eviction closes the oldest file when the pool overflows.
    // ---------------------------------------------------------------------

    @Test
    void evictsOldestFileWhenCapReached(@TempDir Path dir) throws IOException {
        try (RegionFileCache cache = new RegionFileCache(dir, 4)) {
            // Write to 5 different region files by choosing chunks in distinct 32-chunk
            // bands along X (chunk (i*32, 0) sits in region (i, 0)).
            byte[][] payloads = new byte[5][];
            Path[] regionPaths = new Path[5];
            for (int i = 0; i < 5; i++) {
                payloads[i] = ("payload-region-" + i).getBytes(StandardCharsets.UTF_8);
                cache.writeChunk(i * 32, 0, payloads[i]);
                regionPaths[i] = cache.pathForChunk(i * 32, 0);
            }

            // Cap = 4, five distinct regions touched — the first region (touched
            // longest ago) must have been evicted, the other four remain cached.
            assertThat(cache.cachedFileCount()).isEqualTo(4);
            assertThat(cache.isCached(regionPaths[0]))
                    .as("oldest region file was evicted")
                    .isFalse();
            for (int i = 1; i < 5; i++) {
                assertThat(cache.isCached(regionPaths[i]))
                        .as("region file " + i + " should still be cached")
                        .isTrue();
            }

            // The evicted file is still readable on demand (writer flushed + closed
            // orderly), and re-opening it evicts the next-oldest (region 1).
            assertThat(cache.readChunk(0, 0)).isEqualTo(payloads[0]);
            assertThat(cache.isCached(regionPaths[0])).isTrue();
            assertThat(cache.isCached(regionPaths[1]))
                    .as("re-opening region 0 evicts the next-eldest (region 1)")
                    .isFalse();
        }
    }

    // ---------------------------------------------------------------------
    // 3. Concurrent readers share the read lock (cross-region parallelism).
    // ---------------------------------------------------------------------

    @Test
    void concurrentReadsShareReadLock(@TempDir Path dir) throws Exception {
        try (RegionFileCache cache = new RegionFileCache(dir)) {
            // Pre-populate 16 chunks inside a single region file so all 16 reader
            // threads target the same file's Handle.
            int n = 16;
            byte[][] payloads = new byte[n][];
            for (int i = 0; i < n; i++) {
                payloads[i] = ("chunk-" + i).getBytes(StandardCharsets.UTF_8);
                cache.writeChunk(i, 0, payloads[i]);
            }

            // Direct-lock proof of sharing: grab the file's RW lock and take its read
            // lock from N threads simultaneously. A shared read lock permits every
            // reader concurrently; an exclusive mutex would serialize them.
            ReentrantReadWriteLock lock = cache.lockFor(0, 0);
            CountDownLatch acquired = new CountDownLatch(n);
            CountDownLatch release = new CountDownLatch(1);
            Thread[] threads = new Thread[n];
            for (int i = 0; i < n; i++) {
                threads[i] = new Thread(
                        () -> {
                            lock.readLock().lock();
                            try {
                                acquired.countDown();
                                release.await();
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            } finally {
                                lock.readLock().unlock();
                            }
                        },
                        "reader-share-" + i);
                threads[i].start();
            }
            assertThat(acquired.await(5, TimeUnit.SECONDS))
                    .as("all " + n + " readers reached the read-lock critical section")
                    .isTrue();
            assertThat(lock.getReadLockCount())
                    .as("shared read lock is held by all " + n + " readers simultaneously")
                    .isEqualTo(n);
            release.countDown();
            for (Thread t : threads) {
                t.join(2000);
                assertThat(t.isAlive()).isFalse();
            }

            // End-to-end proof: 16 threads call readChunk on distinct chunks in the
            // same file concurrently. No deadlock, every read returns the right bytes.
            ExecutorService es = Executors.newFixedThreadPool(n);
            try {
                CountDownLatch startGate = new CountDownLatch(1);
                List<Future<byte[]>> futures = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    final int idx = i;
                    futures.add(es.submit(() -> {
                        startGate.await();
                        return cache.readChunk(idx, 0);
                    }));
                }
                startGate.countDown();
                for (int i = 0; i < n; i++) {
                    assertThat(futures.get(i).get(5, TimeUnit.SECONDS))
                            .as("concurrent readChunk(" + i + ", 0)")
                            .isEqualTo(payloads[i]);
                }
            } finally {
                es.shutdownNow();
            }
        }
    }

    // ---------------------------------------------------------------------
    // 4. Writer excludes readers on the same file.
    // ---------------------------------------------------------------------

    @Test
    void writerExcludesReaders(@TempDir Path dir) throws Exception {
        try (RegionFileCache cache = new RegionFileCache(dir)) {
            cache.writeChunk(0, 0, "initial".getBytes(StandardCharsets.UTF_8));

            ReentrantReadWriteLock lock = cache.lockFor(0, 0);

            CountDownLatch writerHolding = new CountDownLatch(1);
            CountDownLatch releaseWriter = new CountDownLatch(1);
            Thread writerThread = new Thread(
                    () -> {
                        lock.writeLock().lock();
                        try {
                            writerHolding.countDown();
                            releaseWriter.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            lock.writeLock().unlock();
                        }
                    },
                    "writer-exclusion");
            writerThread.start();
            assertThat(writerHolding.await(2, TimeUnit.SECONDS))
                    .as("writer thread reached the exclusive critical section")
                    .isTrue();

            // Reader can't get past the write lock. Prove it observably: the reader
            // has not completed within a comfortable slack window while the writer
            // still holds the exclusive lock.
            AtomicBoolean readerDone = new AtomicBoolean(false);
            AtomicReference<byte[]> readerResult = new AtomicReference<>();
            AtomicReference<Throwable> readerError = new AtomicReference<>();
            Thread readerThread = new Thread(
                    () -> {
                        try {
                            byte[] r = cache.readChunk(0, 0);
                            readerResult.set(r);
                            readerDone.set(true);
                        } catch (Throwable ex) {
                            readerError.set(ex);
                        }
                    },
                    "reader-exclusion");
            readerThread.start();

            // Wait long enough that a running reader would obviously have finished
            // (readChunk on a 7-byte deflate payload is measured in microseconds), yet
            // still confirm exclusion.
            Thread.sleep(300);
            assertThat(readerDone.get())
                    .as("reader must not complete while writer holds exclusive lock")
                    .isFalse();
            assertThat(readerError.get())
                    .as("reader must not have errored — it should be blocked, not failed")
                    .isNull();

            // Release the writer, then the reader must unblock and return the payload.
            releaseWriter.countDown();
            writerThread.join(2000);
            readerThread.join(2000);
            assertThat(writerThread.isAlive()).isFalse();
            assertThat(readerThread.isAlive()).isFalse();
            assertThat(readerError.get()).isNull();
            assertThat(readerDone.get()).isTrue();
            assertThat(readerResult.get()).isEqualTo("initial".getBytes(StandardCharsets.UTF_8));
        }
    }

    // ---------------------------------------------------------------------
    // Bonus: close() rejects further I/O — verifies idempotency + latching.
    // ---------------------------------------------------------------------

    @Test
    void closedCacheRejectsFurtherIo(@TempDir Path dir) throws IOException {
        RegionFileCache cache = new RegionFileCache(dir);
        cache.writeChunk(0, 0, "ok".getBytes(StandardCharsets.UTF_8));
        cache.close();
        // Idempotent close.
        cache.close();
        assertThatThrownBy(() -> cache.readChunk(0, 0)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> cache.writeChunk(1, 0, new byte[] {1})).isInstanceOf(IOException.class);
    }

    // ---------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------

    private static long key(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    private static int keyX(long k) {
        return (int) (k >> 32);
    }

    private static int keyZ(long k) {
        return (int) k;
    }
}
