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
package net.multiforge.bench.io;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;
import net.multiforge.runtime.io.RegionFileHeader;
import net.multiforge.runtime.io.RegionFileReader;
import net.multiforge.runtime.io.RegionFileWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 3 task 3.7 — byte-identical MCA parity regression.
 *
 * <p>The acceptance criterion in {@code docs/design/mca-format.md} §1 is that MultiForge's {@link
 * RegionFileWriter} produces output byte-identical to Vanilla {@code
 * net.minecraft.world.level.chunk.storage.RegionFile} for the same input, so a MultiForge server
 * can be dropped in and out of place without invalidating a save.
 *
 * <p>Two evidence axes carry that claim in a runtime-only test module:
 *
 * <ol>
 *   <li><b>Format-spec parity</b> — every byte of the header, chunk-header, and compressed payload
 *       matches the on-disk contract from mca-format.md §1 and Vanilla {@code RegionFile.java}.
 *       Encoded here as: header layout (big-endian, X-fast slot index), chunk-header shape
 *       ({@code int32 length-BE, int8 compressionType}), and compression byte parity against a
 *       fresh {@link DeflaterOutputStream} (which is exactly what Vanilla's {@code
 *       RegionFileVersion.VERSION_DEFLATE} uses).
 *   <li><b>Reader/writer internal consistency</b> — round-tripping every chunk through
 *       Reader→Writer→Reader preserves the payload byte-for-byte, and re-reading a written file
 *       preserves the chunk count and per-slot payload set. This is weaker than "identical file
 *       bytes" (sector layout after a rewrite may legitimately differ, since Vanilla first-fits
 *       just like MultiForge but from a different starting state), but it is the strongest guarantee
 *       available without a Minecraft-produced fixture on the classpath.
 * </ol>
 *
 * <p><b>Limitation.</b> A Vanilla-produced {@code r.0.0.mca} was not vendored — generating one
 * requires running a full Minecraft server, and {@code multiforge-bench} has no MC dependency. The
 * task spec's fallback path ("synthetic parity test against Vanilla's format spec") is what this
 * class implements. Once a determinism harness run (M8 sub-step 8b) produces two paired
 * baseline/patched world saves, an additional test in this file can compare their MCA files byte
 * for byte — see the flagged TODO on {@link #TODO_vendoredVanillaMcaFileIsAvailable()}.
 */
class RegionFileParityTest {

    // ---------------------------------------------------------------------
    // Axis 1 — format-spec parity
    // ---------------------------------------------------------------------

    /**
     * Header layout matches the mca-format.md §1 contract byte-for-byte: 1024 big-endian location
     * entries packed as {@code (sectorOffset << 8) | sectorCount}, timestamps at offset 4096,
     * X-fast slot indexing.
     */
    @Test
    void headerLayoutMatchesVanillaFormatSpec(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        byte[] payload = "chunk (3,7) payload for header-parity check".getBytes();
        try (RegionFileWriter w = new RegionFileWriter(file)) {
            w.writeChunk(3, 7, payload);
        }

        // Read the raw 8192-byte header off disk and parse it with the same helpers Vanilla uses
        // (big-endian ints, X-fast slot index).
        byte[] raw = Files.readAllBytes(file);
        assertThat(raw.length).isGreaterThanOrEqualTo(RegionFileHeader.HEADER_BYTES);
        ByteBuffer header = ByteBuffer.wrap(raw, 0, RegionFileHeader.HEADER_BYTES);

        int slot = RegionFileHeader.slotIndex(3, 7);
        assertThat(slot).as("X-fast row-major indexing").isEqualTo(3 + 7 * 32);

        int loc = RegionFileHeader.locationEntry(header, slot);
        int sectorOffset = RegionFileHeader.sectorOffset(loc);
        int sectorCount = RegionFileHeader.sectorCount(loc);
        assertThat(sectorOffset)
                .as("first payload lands past the 2-sector header")
                .isEqualTo(2);
        assertThat(sectorCount).as("small payload fits in 1 sector").isEqualTo(1);

        // Every other slot in the location table must be 0 (chunk not present).
        for (int i = 0; i < RegionFileHeader.SLOTS; i++) {
            if (i == slot) {
                continue;
            }
            assertThat(RegionFileHeader.locationEntry(header, i))
                    .as("slot %d should be empty", i)
                    .isEqualTo(0);
        }

        // Timestamp for the written slot is non-zero (last-modified seconds).
        int ts = RegionFileHeader.timestampEntry(header, slot);
        assertThat(ts).as("write records last-modified timestamp").isNotZero();
    }

    /**
     * The 5-byte chunk header at the payload sector matches Vanilla's shape: 4-byte big-endian
     * {@code length = 1 + compressed.length}, then 1-byte compression type (2 = deflate).
     */
    @Test
    void chunkHeaderMatchesVanillaShape(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        byte[] payload = repeat("payload-bytes-for-header-shape-", 40);
        try (RegionFileWriter w = new RegionFileWriter(file)) {
            w.writeChunk(0, 0, payload);
        }

        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            // Slot (0,0) is the first chunk written, so it lives in sector 2.
            long sectorStart = 2L * RegionFileHeader.SECTOR_BYTES;
            ByteBuffer hdr = ByteBuffer.allocate(5);
            int read = ch.read(hdr, sectorStart);
            assertThat(read).isEqualTo(5);
            hdr.flip();

            int declaredLength = hdr.getInt();
            byte compressionType = hdr.get();
            assertThat(compressionType)
                    .as("Writer defaults to VERSION_DEFLATE (2)")
                    .isEqualTo((byte) RegionFileReader.COMPRESSION_DEFLATE);
            assertThat(declaredLength)
                    .as("declared length = 1 (compression byte) + compressed.length, matching "
                            + "Vanilla RegionFile.java write path")
                    .isEqualTo(1 + vanillaEquivalentDeflate(payload).length);
        }
    }

    /**
     * The compressed bytes on disk are byte-identical to what a fresh {@link DeflaterOutputStream}
     * writes. That is exactly what Vanilla's {@code RegionFileVersion.VERSION_DEFLATE} produces —
     * a plain deflater at {@code Deflater.DEFAULT_COMPRESSION} (level 6) with no custom
     * dictionary, wrapped only by a {@code BufferedOutputStream} that does not alter the byte
     * stream. This is the strongest byte-parity check available without invoking Vanilla directly.
     */
    @Test
    void compressedPayloadBytesMatchVanillaDeflate(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        byte[] payload = repeat("compression-parity-payload-", 60);
        try (RegionFileWriter w = new RegionFileWriter(file)) {
            w.writeChunk(1, 1, payload);
        }

        // Read the on-disk compressed bytes (skip the 5-byte chunk header).
        try (RegionFileReader r = new RegionFileReader(file)) {
            byte[] onDisk = r.readChunkRaw(1, 1);
            byte[] vanilla = vanillaEquivalentDeflate(payload);
            assertThat(onDisk)
                    .as("Writer's compressed bytes must byte-match a fresh DeflaterOutputStream")
                    .isEqualTo(vanilla);
        }
    }

    // ---------------------------------------------------------------------
    // Axis 2 — reader/writer internal consistency
    // ---------------------------------------------------------------------

    /**
     * The Reader must successfully parse every chunk the Writer produces — no throws, no nulls,
     * decompressed bytes equal the original input. This is the shape of "Reader can parse any
     * Vanilla-written file" once vendored MCAs exist.
     */
    @Test
    void readerParsesEveryWriterChunkWithoutError(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        List<int[]> coords = new ArrayList<>();
        List<byte[]> payloads = new ArrayList<>();

        try (RegionFileWriter w = new RegionFileWriter(file)) {
            for (int i = 0; i < 16; i++) {
                int cx = (i * 3) & 31;
                int cz = (i * 5 + 1) & 31;
                byte[] p = pseudoPayload(cx, cz, 128 + i * 37);
                w.writeChunk(cx, cz, p);
                coords.add(new int[] {cx, cz});
                payloads.add(p);
            }
        }

        try (RegionFileReader r = new RegionFileReader(file)) {
            for (int i = 0; i < coords.size(); i++) {
                int cx = coords.get(i)[0];
                int cz = coords.get(i)[1];
                assertThat(r.hasChunk(cx, cz))
                        .as("chunk (%d,%d) present", cx, cz)
                        .isTrue();
                byte[] decoded = r.readChunk(cx, cz);
                assertThat(decoded)
                        .as("Reader must decode chunk (%d,%d) losslessly", cx, cz)
                        .isEqualTo(payloads.get(i));
            }
        }
    }

    /**
     * Read → Write → Read preserves every chunk payload byte-for-byte. Sector layout of the two
     * files may legitimately differ (both allocators first-fit from an empty bitmap, but the second
     * file may see different write order effects), so the check is per-payload byte-equality, not
     * whole-file byte-equality.
     */
    @Test
    void roundTripPreservesEveryChunkPayload(@TempDir Path dir) throws IOException {
        Path original = dir.resolve("r.0.0.mca");
        Path roundTripped = dir.resolve("r.0.0.rt.mca");

        // 1. Build the original file with a variety of chunk sizes (small, medium, multi-sector).
        List<int[]> coords = List.of(
                new int[] {0, 0},
                new int[] {1, 2},
                new int[] {5, 5},
                new int[] {10, 15},
                new int[] {31, 31},
                new int[] {17, 4},
                new int[] {8, 23});
        List<byte[]> payloads = new ArrayList<>();
        try (RegionFileWriter w = new RegionFileWriter(original)) {
            for (int[] c : coords) {
                // A mix that spans single-sector, multi-sector, and up-to-tens-of-KiB payloads
                // so the parity claim covers all inline sizes.
                int sz = 200 + ((c[0] * 3 + c[1] * 7) & 31) * 900;
                byte[] p = pseudoPayload(c[0], c[1], sz);
                w.writeChunk(c[0], c[1], p);
                payloads.add(p);
            }
        }

        // 2. Read every chunk out via Reader.
        List<byte[]> decoded = new ArrayList<>();
        try (RegionFileReader r = new RegionFileReader(original)) {
            for (int[] c : coords) {
                byte[] d = r.readChunk(c[0], c[1]);
                assertThat(d).as("chunk (%d,%d) reads back", c[0], c[1]).isNotNull();
                decoded.add(d);
            }
        }
        assertThat(decoded).containsExactlyElementsOf(payloads);

        // 3. Rewrite everything to a fresh file via Writer.
        try (RegionFileWriter w = new RegionFileWriter(roundTripped)) {
            for (int i = 0; i < coords.size(); i++) {
                w.writeChunk(coords.get(i)[0], coords.get(i)[1], decoded.get(i));
            }
        }

        // 4. Read the round-tripped file — every chunk payload must byte-match the original.
        try (RegionFileReader r = new RegionFileReader(roundTripped)) {
            for (int i = 0; i < coords.size(); i++) {
                int cx = coords.get(i)[0];
                int cz = coords.get(i)[1];
                byte[] again = r.readChunk(cx, cz);
                assertThat(again)
                        .as("round-trip preserves chunk (%d,%d) payload", cx, cz)
                        .isEqualTo(payloads.get(i));
            }
        }
    }

    /**
     * After a round-trip through Reader → Writer, the sector layout of the two files can differ
     * (this is the point Vanilla and MultiForge both concede in {@code RegionFile}'s first-fit
     * allocator — a rewrite of a fragmented file will pack differently). What must NOT differ is
     * the set of chunks present and the compressed payload for each occupied slot.
     */
    @Test
    void sectorLayoutMayDifferButChunkSetAndPayloadsMatch(@TempDir Path dir) throws IOException {
        Path a = dir.resolve("a.mca");
        Path b = dir.resolve("b.mca");
        // Write, overwrite, delete-simulate (skip a slot), rewrite — exercises the allocator.
        try (RegionFileWriter w = new RegionFileWriter(a)) {
            w.writeChunk(0, 0, pseudoPayload(0, 0, 4000));
            w.writeChunk(1, 0, pseudoPayload(1, 0, 8000));
            w.writeChunk(0, 1, pseudoPayload(0, 1, 300));
            // Overwrite (1,0) with something smaller to leave a hole.
            w.writeChunk(1, 0, pseudoPayload(1, 0, 500));
        }

        // Now read (a) and write (b) with all chunks — layout in (b) may differ, but the set of
        // (slot, decoded-payload) pairs must be identical.
        List<int[]> present = new ArrayList<>();
        try (RegionFileReader r = new RegionFileReader(a)) {
            for (int cz = 0; cz < 32; cz++) {
                for (int cx = 0; cx < 32; cx++) {
                    if (r.hasChunk(cx, cz)) {
                        present.add(new int[] {cx, cz});
                    }
                }
            }
        }
        assertThat(present).as("three distinct slots occupied in a.mca").hasSize(3);

        try (RegionFileReader ra = new RegionFileReader(a);
                RegionFileWriter wb = new RegionFileWriter(b)) {
            for (int[] c : present) {
                byte[] p = ra.readChunk(c[0], c[1]);
                assertThat(p).isNotNull();
                wb.writeChunk(c[0], c[1], p);
            }
        }

        // Chunk count parity + per-slot payload parity.
        int matched = 0;
        try (RegionFileReader ra = new RegionFileReader(a);
                RegionFileReader rb = new RegionFileReader(b)) {
            for (int cz = 0; cz < 32; cz++) {
                for (int cx = 0; cx < 32; cx++) {
                    boolean inA = ra.hasChunk(cx, cz);
                    boolean inB = rb.hasChunk(cx, cz);
                    assertThat(inA)
                            .as("chunk-count parity at slot (%d,%d)", cx, cz)
                            .isEqualTo(inB);
                    if (inA) {
                        assertThat(rb.readChunk(cx, cz))
                                .as("payload parity at slot (%d,%d)", cx, cz)
                                .isEqualTo(ra.readChunk(cx, cz));
                        matched++;
                    }
                }
            }
        }
        assertThat(matched).as("every occupied slot compared").isEqualTo(present.size());
    }

    // ---------------------------------------------------------------------
    // Reminder of the outstanding vendored-fixture axis (see class javadoc).
    // ---------------------------------------------------------------------

    /**
     * Placeholder for the byte-identical-against-Vanilla test that unlocks once a paired
     * baseline/patched world save exists on disk (M8 sub-step 8b output). Until then this test
     * asserts the fixture is absent, so the missing evidence stays visible in test output rather
     * than silently no-op-ing.
     */
    @Test
    @SuppressWarnings("checkstyle:MethodName")
    void TODO_vendoredVanillaMcaFileIsAvailable() {
        Path vendored = Path.of("multiforge-bench", "src", "test", "resources", "mca-vendored", "r.0.0.mca");
        assertThat(Files.exists(vendored))
                .as("No Vanilla-produced MCA fixture is vendored yet. This is expected — Phase 3 "
                        + "task 3.7 falls back to the format-spec + round-trip parity harness in the other "
                        + "tests in this class. When M8 sub-step 8b lands a determinism world save, drop the "
                        + "smallest MCA from it into src/test/resources/mca-vendored/ and expand this test "
                        + "into a byte-identical diff against RegionFileWriter's re-output.")
                .isFalse();
    }

    // ---------------------------------------------------------------------
    // Test helpers
    // ---------------------------------------------------------------------

    /**
     * Deflate {@code payload} with the same code path Vanilla uses: a plain {@link
     * DeflaterOutputStream} at {@code Deflater.DEFAULT_COMPRESSION} (level 6). Vanilla's actual
     * pipeline wraps this in a {@code BufferedOutputStream} for I/O batching, but the buffering
     * layer does not alter the deflate byte stream, so this produces the same bytes on disk.
     */
    private static byte[] vanillaEquivalentDeflate(byte[] payload) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(payload.length / 2, 64));
        try (DeflaterOutputStream out = new DeflaterOutputStream(baos)) {
            out.write(payload);
        }
        return baos.toByteArray();
    }

    /** Deterministic payload keyed by (cx,cz,size) so failure messages are reproducible. */
    private static byte[] pseudoPayload(int cx, int cz, int size) {
        byte[] out = new byte[size];
        Random rng = new Random(((long) cx << 32) ^ (long) cz ^ (long) size);
        rng.nextBytes(out);
        return out;
    }

    private static byte[] repeat(String s, int times) {
        StringBuilder sb = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(s);
        }
        return sb.toString().getBytes();
    }
}
