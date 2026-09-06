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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Full-pipeline integration tests for MultiForge's MCA I/O layer — Phase 3 task 3.6.
 *
 * <p>Individual class tests ({@code RegionFileHeaderTest}, {@code RegionFileReaderTest},
 * {@code RegionFileWriterTest}) cover per-class behavior. This suite exercises
 * writer→reader parity, adversarial-input tolerance, and concurrent-read safety
 * across the composed pipeline. Byte-parity vs. Vanilla is asserted separately by
 * task 3.7's {@code RegionFileParityTest} under {@code multiforge-bench}.
 */
class RegionFileIntegrationTest {

    // -----------------------------------------------------------------
    // 1. Round-trip: 1000 procedurally generated chunks
    // -----------------------------------------------------------------

    /**
     * Writer produces 1000 chunks with varying sizes (10 bytes to 200 KB); reader
     * reads each back and asserts byte-equality. A region file has 32×32 = 1024
     * slots, so we generate 1000 unique (localX, localZ) pairs to avoid slot
     * collisions rewriting each other.
     */
    @Test
    void roundTrip1000ProceduralChunks(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        Random rand = new Random(0xC0FFEE1L);

        // Generate 1000 unique in-region slots by taking rows 0..30 (992) plus
        // 8 slots from row 31 — 1000 total, all distinct.
        int chunkCount = 1000;
        Map<Long, byte[]> expected = new HashMap<>(chunkCount);
        int[][] coords = new int[chunkCount][2];
        int idx = 0;
        outer:
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                if (idx == chunkCount) break outer;
                coords[idx][0] = x;
                coords[idx][1] = z;
                idx++;
            }
        }

        try (RegionFileWriter w = new RegionFileWriter(file)) {
            for (int i = 0; i < chunkCount; i++) {
                int size = sampleChunkSize(rand);
                byte[] payload = new byte[size];
                rand.nextBytes(payload);
                int cx = coords[i][0];
                int cz = coords[i][1];
                w.writeChunk(cx, cz, payload);
                expected.put(pack(cx, cz), payload);
            }
        }

        try (RegionFileReader r = new RegionFileReader(file)) {
            for (int i = 0; i < chunkCount; i++) {
                int cx = coords[i][0];
                int cz = coords[i][1];
                byte[] want = expected.get(pack(cx, cz));
                byte[] got = r.readChunk(cx, cz);
                assertThat(got)
                        .as("round-trip mismatch at (" + cx + "," + cz + ") size=" + want.length)
                        .isEqualTo(want);
            }
        }
    }

    /**
     * Log-biased size distribution: mostly small chunks with occasional large
     * ones. Keeps the region file under ~30 MB while still exercising the
     * full 10 bytes .. 200 KB range demanded by the task.
     */
    private static int sampleChunkSize(Random rand) {
        double r = rand.nextDouble();
        if (r < 0.60) {
            // 60%: 10 bytes .. 1 KB
            return 10 + rand.nextInt(1_024);
        } else if (r < 0.90) {
            // 30%: 1 KB .. 16 KB
            return 1_024 + rand.nextInt(15 * 1_024);
        } else if (r < 0.99) {
            // 9%: 16 KB .. 64 KB
            return 16 * 1_024 + rand.nextInt(48 * 1_024);
        } else {
            // 1%: 64 KB .. 200 KB
            return 64 * 1_024 + rand.nextInt(136 * 1_024);
        }
    }

    // -----------------------------------------------------------------
    // 2. Corruption tolerance: adversarial location table
    // -----------------------------------------------------------------

    /**
     * Write valid chunks then hex-corrupt the location table with hostile values
     * (sectorOffset=0xFFFFFF, sectorCount=255 dangling past EOF, sector pointing
     * into the reserved header, huge sectorCount that overflows file bounds).
     * The reader must return null / warn without crashing on every corrupted
     * slot, and must still read good slots correctly.
     */
    @Test
    void corruptionToleranceAdversarialLocationTable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        // Write a bank of valid chunks first so there are readable payloads
        // interleaved with the hostile slots.
        byte[][] good = new byte[16][];
        Random rand = new Random(0xBADF00D);
        try (RegionFileWriter w = new RegionFileWriter(file)) {
            for (int i = 0; i < good.length; i++) {
                good[i] = new byte[256 + rand.nextInt(2_048)];
                rand.nextBytes(good[i]);
                // Place good chunks in row z=0.
                w.writeChunk(i, 0, good[i]);
            }
        }

        // Now open the raw file and stomp on selected location-table entries in
        // rows that don't overlap the good chunks.
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            // Hostile entries — each triples the arithmetic overflow risk in
            // RegionFileHeader.isValidLocation, which uses 64-bit math to defeat
            // the 24-bit×4096 wrap the reader would otherwise hit.
            writeLocation(ch, /*x=*/ 0, /*z=*/ 1, /*sectorOff=*/ 0xFFFFFF, /*sectorCount=*/ 1);
            writeLocation(ch, /*x=*/ 1, /*z=*/ 1, /*sectorOff=*/ 0xFFFFFF, /*sectorCount=*/ 0xFF);
            writeLocation(ch, /*x=*/ 2, /*z=*/ 1, /*sectorOff=*/ 2, /*sectorCount=*/ 0xFF);
            writeLocation(ch, /*x=*/ 3, /*z=*/ 1, /*sectorOff=*/ 0, /*sectorCount=*/ 1);
            writeLocation(ch, /*x=*/ 4, /*z=*/ 1, /*sectorOff=*/ 1, /*sectorCount=*/ 1);
            writeLocation(ch, /*x=*/ 5, /*z=*/ 1, /*sectorOff=*/ 100_000, /*sectorCount=*/ 1);
            // sectorOffset that fits but sectorCount overflows EOF.
            writeLocation(ch, /*x=*/ 6, /*z=*/ 1, /*sectorOff=*/ 2, /*sectorCount=*/ 200);
            ch.force(true);
        }

        try (RegionFileReader r = new RegionFileReader(file)) {
            // Every hostile slot: hasChunk false, readChunk null, readChunkRaw
            // null, compressionType -1 — and none of these calls throw.
            for (int x : new int[] {0, 1, 2, 3, 4, 5, 6}) {
                assertThat(r.hasChunk(x, 1))
                        .as("hostile slot (" + x + ",1) hasChunk")
                        .isFalse();
                assertThat(r.readChunk(x, 1))
                        .as("hostile slot (" + x + ",1) readChunk")
                        .isNull();
                assertThat(r.readChunkRaw(x, 1))
                        .as("hostile slot (" + x + ",1) readChunkRaw")
                        .isNull();
                assertThat(r.compressionType(x, 1))
                        .as("hostile slot (" + x + ",1) compressionType")
                        .isEqualTo(-1);
            }
            // Good chunks in row 0 still decode intact — corruption of one slot
            // must not poison other slots.
            for (int i = 0; i < good.length; i++) {
                assertThat(r.readChunk(i, 0))
                        .as("good chunk (" + i + ",0) survived neighbouring corruption")
                        .isEqualTo(good[i]);
            }
        }
    }

    // -----------------------------------------------------------------
    // 3. Truncated file: reader returns null for cut-off chunks
    // -----------------------------------------------------------------

    @Test
    void truncatedFileHandledGracefully(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        // Write enough chunks that the file grows well past the header. Use a
        // deterministic seed so slot-vs-truncation cutoff is reproducible.
        int chunkCount = 100;
        byte[][] payloads = new byte[chunkCount][];
        Random rand = new Random(0x7B0CCE7L);
        try (RegionFileWriter w = new RegionFileWriter(file)) {
            for (int i = 0; i < chunkCount; i++) {
                int size = 512 + rand.nextInt(4_096);
                payloads[i] = new byte[size];
                rand.nextBytes(payloads[i]);
                int cx = i & 31;
                int cz = (i >>> 5) & 31;
                w.writeChunk(cx, cz, payloads[i]);
            }
        }

        long fullSize = Files.size(file);
        long truncSize = fullSize / 2;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.truncate(truncSize);
        }
        assertThat(Files.size(file)).isEqualTo(truncSize);

        // The reader must handle every slot without throwing. Chunks that
        // survived truncation still decode; chunks whose sector range now
        // extends past EOF must return null.
        int survived = 0;
        int lost = 0;
        try (RegionFileReader r = new RegionFileReader(file)) {
            for (int i = 0; i < chunkCount; i++) {
                int cx = i & 31;
                int cz = (i >>> 5) & 31;
                byte[] got = r.readChunk(cx, cz);
                if (got == null) {
                    lost++;
                } else {
                    assertThat(got)
                            .as("chunk (" + cx + "," + cz + ") that survived truncation must be exact")
                            .isEqualTo(payloads[i]);
                    survived++;
                }
            }
        }
        // At least some chunks were truncated (payload sectors past midpoint)
        // and at least some survived (payload sectors before midpoint) — the
        // fact that both are non-zero proves the reader tolerates the mixed
        // state rather than blanket-rejecting the whole file.
        assertThat(lost)
                .as("some chunks past the truncation point should be lost")
                .isPositive();
        assertThat(survived)
                .as("some chunks before the truncation point should survive")
                .isPositive();
        assertThat(survived + lost).isEqualTo(chunkCount);
    }

    // -----------------------------------------------------------------
    // 4. Concurrent-read stress: 16 threads, 5 seconds
    // -----------------------------------------------------------------

    /**
     * Writer produces 100 chunks; 16 reader threads then read random slots for
     * 5 seconds. RegionFileReader documents concurrent-reader safety (positional
     * FileChannel.read + immutable cached header). This test asserts that
     * property empirically: every read returns either the exact expected bytes
     * for a written slot, or null for an unwritten slot — never mangled bytes.
     */
    @Test
    void concurrentReadStress(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("r.0.0.mca");
        int chunkCount = 100;
        Map<Integer, byte[]> written = new HashMap<>(chunkCount);
        Random rand = new Random(0xC07E57DL);
        try (RegionFileWriter w = new RegionFileWriter(file)) {
            for (int i = 0; i < chunkCount; i++) {
                int size = 128 + rand.nextInt(8_192);
                byte[] payload = new byte[size];
                rand.nextBytes(payload);
                int cx = i & 31;
                int cz = (i >>> 5) & 31;
                w.writeChunk(cx, cz, payload);
                written.put(slotKey(cx, cz), payload);
            }
        }

        int threadCount = 16;
        long durationMillis = 5_000L;
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger corruptions = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try (RegionFileReader reader = new RegionFileReader(file)) {
            for (int t = 0; t < threadCount; t++) {
                final int seed = t;
                pool.submit(() -> {
                    Random tr = new Random(seed * 0xDEADBEEFL);
                    ready.countDown();
                    try {
                        go.await();
                        while (!stop.get()) {
                            int cx = tr.nextInt(32);
                            int cz = tr.nextInt(32);
                            byte[] got = reader.readChunk(cx, cz);
                            byte[] want = written.get(slotKey(cx, cz));
                            if (want == null) {
                                if (got != null) {
                                    // Read something from an unwritten slot — corruption.
                                    corruptions.incrementAndGet();
                                }
                            } else {
                                if (got == null || got.length != want.length) {
                                    corruptions.incrementAndGet();
                                } else {
                                    // Full byte-compare — cheap enough at these sizes.
                                    for (int i = 0; i < got.length; i++) {
                                        if (got[i] != want[i]) {
                                            corruptions.incrementAndGet();
                                            break;
                                        }
                                    }
                                }
                            }
                            reads.incrementAndGet();
                        }
                    } catch (Throwable ex) {
                        failure.compareAndSet(null, ex);
                    }
                });
            }
            ready.await();
            go.countDown();
            Thread.sleep(durationMillis);
            stop.set(true);
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                    .as("reader threads should finish within 30s of stop-signal")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        if (failure.get() != null) {
            throw new AssertionError("reader thread threw", failure.get());
        }
        assertThat(reads.get()).as("expected non-trivial read count over 5s").isGreaterThan(1_000);
        assertThat(corruptions.get())
                .as("no read returned mangled or bogus bytes")
                .isZero();
    }

    // -----------------------------------------------------------------
    // 5. Mixed compression types: gzip, deflate, none
    // -----------------------------------------------------------------

    /**
     * Write the same payload with each of the three supported compression types
     * (at distinct slots — the writer only writes one compression byte per slot),
     * then read each back and verify the decompressed bytes match the original.
     */
    @Test
    void mixedCompressionTypesRoundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        byte[] payload = new byte[4_096];
        new Random(0x1A2B3C4DL).nextBytes(payload);

        int gzipSlotX = 0;
        int deflateSlotX = 1;
        int noneSlotX = 2;

        try (RegionFileWriter w = new RegionFileWriter(file)) {
            w.writeChunk(gzipSlotX, 0, payload, RegionFileWriter.COMPRESSION_GZIP);
            w.writeChunk(deflateSlotX, 0, payload, RegionFileWriter.COMPRESSION_DEFLATE);
            w.writeChunk(noneSlotX, 0, payload, RegionFileWriter.COMPRESSION_NONE);
        }

        try (RegionFileReader r = new RegionFileReader(file)) {
            assertThat(r.compressionType(gzipSlotX, 0) & 0x7F).isEqualTo(RegionFileReader.COMPRESSION_GZIP);
            assertThat(r.compressionType(deflateSlotX, 0) & 0x7F).isEqualTo(RegionFileReader.COMPRESSION_DEFLATE);
            assertThat(r.compressionType(noneSlotX, 0) & 0x7F).isEqualTo(RegionFileReader.COMPRESSION_NONE);

            assertThat(r.readChunk(gzipSlotX, 0)).as("gzip round-trip").isEqualTo(payload);
            assertThat(r.readChunk(deflateSlotX, 0)).as("deflate round-trip").isEqualTo(payload);
            assertThat(r.readChunk(noneSlotX, 0))
                    .as("no-compression round-trip")
                    .isEqualTo(payload);
        }
    }

    // -----------------------------------------------------------------
    // 6. External .mcc spillover: >256-sector payload
    // -----------------------------------------------------------------

    /**
     * Write a chunk whose compressed size crosses the 256-sector (~1 MB)
     * external-file threshold. Verify the writer creates a {@code c.X.Z.mcc}
     * sidecar, the region file holds a 1-sector stub with the external flag,
     * and the reader reconstructs the original payload from the sidecar.
     */
    @Test
    void externalMccSpilloverRoundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        // Random bytes resist deflate, so a 2 MB random payload compresses to
        // roughly 2 MB — comfortably past the 256×4096 = 1 MB threshold.
        int payloadSize = 2 * 1_024 * 1_024;
        byte[] payload = new byte[payloadSize];
        new Random(0x59110F0CL).nextBytes(payload);

        int chunkX = 5;
        int chunkZ = 9;
        try (RegionFileWriter w = new RegionFileWriter(file)) {
            w.writeChunk(chunkX, chunkZ, payload, RegionFileWriter.COMPRESSION_DEFLATE);
        }

        // Sidecar exists — external file naming uses full world chunk coords.
        Path sidecar = dir.resolve("c." + chunkX + "." + chunkZ + ".mcc");
        assertThat(Files.exists(sidecar))
                .as(".mcc sidecar must exist for oversized chunk")
                .isTrue();

        try (RegionFileReader r = new RegionFileReader(file)) {
            assertThat(r.hasChunk(chunkX, chunkZ)).isTrue();
            int cb = r.compressionType(chunkX, chunkZ);
            assertThat(cb & RegionFileReader.EXTERNAL_STREAM_FLAG)
                    .as("stub's compression byte carries the external-stream flag")
                    .isNotZero();
            assertThat(cb & 0x7F).isEqualTo(RegionFileReader.COMPRESSION_DEFLATE);

            byte[] got = r.readChunk(chunkX, chunkZ);
            assertThat(got)
                    .as("external-spillover chunk must round-trip to original payload")
                    .isEqualTo(payload);
        }
    }

    // -----------------------------------------------------------------
    // 7. Cache eviction does not corrupt
    // -----------------------------------------------------------------

    /**
     * With {@code maxOpen=4}, write one chunk to each of 8 different region
     * files. The 5th, 6th, 7th, 8th open each force LRU eviction of the
     * previously opened file, which under the cache's contract flushes +
     * closes the evicted handle without losing writes. Close the cache to
     * flush the final 4 open files, then reopen and verify every chunk still
     * decodes to its exact original payload.
     */
    @Test
    void cacheEvictionDoesNotCorrupt(@TempDir Path dir) throws IOException {
        int regionCount = 8;
        int maxOpen = 4;
        Map<Integer, byte[]> expected = new HashMap<>(regionCount);
        Random rand = new Random(0xC0FFEE1L);

        // Chunks at (i*32, 0) each land in a distinct region file r.i.0.mca
        // (regionX = chunkX >> 5 = i).
        try (RegionFileCache cache = new RegionFileCache(dir, maxOpen)) {
            for (int i = 0; i < regionCount; i++) {
                byte[] payload = new byte[512 + rand.nextInt(4_096)];
                rand.nextBytes(payload);
                int chunkX = i * 32;
                int chunkZ = 0;
                cache.writeChunk(chunkX, chunkZ, payload);
                expected.put(i, payload);
                // The cache must never hold more than maxOpen files at once.
                assertThat(cache.cachedFileCount())
                        .as("cached file count must never exceed maxOpen")
                        .isLessThanOrEqualTo(maxOpen);
            }
        }

        // All 8 region files must exist on disk (evicted writes were flushed).
        for (int i = 0; i < regionCount; i++) {
            Path mca = dir.resolve("r." + i + ".0.mca");
            assertThat(Files.exists(mca))
                    .as("region file for chunk (" + (i * 32) + ",0) must survive cache eviction")
                    .isTrue();
        }

        // Reopen a fresh cache and verify every chunk decodes to the exact
        // original payload — no mid-eviction data corruption.
        try (RegionFileCache cache = new RegionFileCache(dir, maxOpen)) {
            for (int i = 0; i < regionCount; i++) {
                int chunkX = i * 32;
                int chunkZ = 0;
                byte[] got = cache.readChunk(chunkX, chunkZ);
                assertThat(got)
                        .as("chunk (" + chunkX + "," + chunkZ + ") survived cache eviction cycle")
                        .isEqualTo(expected.get(i));
            }
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static int slotKey(int chunkX, int chunkZ) {
        return RegionFileHeader.slotIndex(RegionFileHeader.localX(chunkX), RegionFileHeader.localZ(chunkZ));
    }

    /** Overwrite one 4-byte location entry in the header (bytes 0..4095). */
    private static void writeLocation(FileChannel ch, int chunkX, int chunkZ, int sectorOffset, int sectorCount)
            throws IOException {
        int slot = RegionFileHeader.slotIndex(RegionFileHeader.localX(chunkX), RegionFileHeader.localZ(chunkZ));
        int packed = ((sectorOffset & 0x00FF_FFFF) << 8) | (sectorCount & 0xFF);
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putInt(packed);
        buf.flip();
        long fileOffset = slot * 4L;
        while (buf.hasRemaining()) {
            int wrote = ch.write(buf, fileOffset);
            if (wrote <= 0) throw new IOException("short write");
            fileOffset += wrote;
        }
    }
}
