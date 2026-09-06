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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionFileReaderTest {

    // -----------------------------------------------------------------
    // Empty region
    // -----------------------------------------------------------------

    @Test
    void readsEmptyRegionAsNulls(@TempDir Path dir) throws IOException {
        Path mca = dir.resolve("r.0.0.mca");
        Files.write(mca, new byte[RegionFileHeader.HEADER_BYTES]);
        try (RegionFileReader r = new RegionFileReader(mca)) {
            assertThat(r.hasChunk(0, 0)).isFalse();
            assertThat(r.hasChunk(31, 31)).isFalse();
            assertThat(r.compressionType(0, 0)).isEqualTo(-1);
            assertThat(r.readChunk(0, 0)).isNull();
            assertThat(r.readChunkRaw(0, 0)).isNull();
        }
    }

    // -----------------------------------------------------------------
    // Valid single-chunk reads (gzip, deflate)
    // -----------------------------------------------------------------

    @Test
    void readsSingleValidGzipChunk(@TempDir Path dir) throws IOException {
        byte[] payload = "gzip-hello-multiforge".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = gzip(payload);
        Path mca = dir.resolve("r.0.0.mca");
        writeSingleChunkRegion(mca, 0, 0, RegionFileReader.COMPRESSION_GZIP, compressed, /* external= */ false);

        try (RegionFileReader r = new RegionFileReader(mca)) {
            assertThat(r.hasChunk(0, 0)).isTrue();
            assertThat(r.compressionType(0, 0)).isEqualTo(RegionFileReader.COMPRESSION_GZIP);
            assertThat(r.readChunk(0, 0)).isEqualTo(payload);
            assertThat(r.readChunkRaw(0, 0)).isEqualTo(compressed);
        }
    }

    @Test
    void readsSingleValidDeflateChunk(@TempDir Path dir) throws IOException {
        byte[] payload = "deflate-hello-multiforge".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = deflate(payload);
        Path mca = dir.resolve("r.0.0.mca");
        // Put it at a nontrivial region-local slot (5, 7) → slot index 5 + 7*32 = 229.
        writeSingleChunkRegion(mca, 5, 7, RegionFileReader.COMPRESSION_DEFLATE, compressed, /* external= */ false);

        try (RegionFileReader r = new RegionFileReader(mca)) {
            assertThat(r.hasChunk(5, 7)).isTrue();
            assertThat(r.compressionType(5, 7)).isEqualTo(RegionFileReader.COMPRESSION_DEFLATE);
            assertThat(r.readChunk(5, 7)).isEqualTo(payload);
            assertThat(r.readChunkRaw(5, 7)).isEqualTo(compressed);

            // Adjacent slots remain empty.
            assertThat(r.hasChunk(4, 7)).isFalse();
            assertThat(r.readChunk(4, 7)).isNull();
        }
    }

    // -----------------------------------------------------------------
    // External .mcc sidecar
    // -----------------------------------------------------------------

    @Test
    void handlesExternalMccFile(@TempDir Path dir) throws IOException {
        byte[] payload = "big-external-chunk-payload".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = deflate(payload);

        // Region file: 8192-byte header + 1 sector stub. Slot (0, 0) points at sector 2.
        // Stub layout matches Vanilla RegionFile.createExternalStub: length=1, type=(deflate | 0x80).
        int chunkX = 12;
        int chunkZ = 34; // full world coords → external file "c.12.34.mcc"
        Path mca = dir.resolve("r.0.0.mca");
        int stubType = RegionFileReader.COMPRESSION_DEFLATE | RegionFileReader.EXTERNAL_STREAM_FLAG;
        byte[] stubPayload = new byte[0]; // no in-region payload; external file carries everything
        writeSingleChunkRegion(mca, chunkX, chunkZ, stubType, stubPayload, /* external= */ true);
        Files.write(dir.resolve("c." + chunkX + "." + chunkZ + ".mcc"), compressed);

        try (RegionFileReader r = new RegionFileReader(mca)) {
            assertThat(r.hasChunk(chunkX, chunkZ)).isTrue();
            assertThat(r.compressionType(chunkX, chunkZ) & 0x7F).isEqualTo(RegionFileReader.COMPRESSION_DEFLATE);
            assertThat(r.compressionType(chunkX, chunkZ) & RegionFileReader.EXTERNAL_STREAM_FLAG)
                    .isNotZero();
            assertThat(r.readChunk(chunkX, chunkZ)).isEqualTo(payload);
            assertThat(r.readChunkRaw(chunkX, chunkZ)).isEqualTo(compressed);
        }
    }

    // -----------------------------------------------------------------
    // Corruption tolerance (regressions from WorldDiffTest)
    // -----------------------------------------------------------------

    /**
     * /67 round-3 finding D1: {@code sectorOffset=0xFFFFFF} × 4096 wraps a signed int negative and
     * used to crash the whole diff. The reader must decline the slot without throwing.
     */
    @Test
    void corruptSectorOffsetReturnsNullWithoutCrash(@TempDir Path dir) throws IOException {
        byte[] corrupt = new byte[RegionFileHeader.HEADER_BYTES];
        // Slot 0 location: sectorOffset=0xFFFFFF, sectorCount=1 → raw bytes FF FF FF 01.
        corrupt[0] = (byte) 0xFF;
        corrupt[1] = (byte) 0xFF;
        corrupt[2] = (byte) 0xFF;
        corrupt[3] = (byte) 0x01;
        Path mca = dir.resolve("r.0.0.mca");
        Files.write(mca, corrupt);

        try (RegionFileReader r = new RegionFileReader(mca)) {
            // hasChunk must reject the malformed slot — sectorOffset way past file end.
            assertThat(r.hasChunk(0, 0)).isFalse();
            assertThat(r.readChunk(0, 0)).isNull();
            assertThat(r.readChunkRaw(0, 0)).isNull();
            assertThat(r.compressionType(0, 0)).isEqualTo(-1);
        }
    }

    /**
     * /67 round-3 finding D2: chunk-length field larger than remaining file. The reader must reject
     * cleanly rather than allocate 2GB or overflow the bounds check.
     */
    @Test
    void corruptChunkLengthReturnsNullWithoutCrash(@TempDir Path dir) throws IOException {
        byte[] corrupt = new byte[3 * RegionFileHeader.SECTOR_BYTES];
        // Slot 0 location: sectorOffset=2, sectorCount=1 → in-range.
        corrupt[0] = 0;
        corrupt[1] = 0;
        corrupt[2] = 2;
        corrupt[3] = 1;
        // Chunk header at byte 8192 (sector 2): declaredLength=0x7FFFFFFF, compression=deflate(2).
        int base = 2 * RegionFileHeader.SECTOR_BYTES;
        corrupt[base] = 0x7F;
        corrupt[base + 1] = (byte) 0xFF;
        corrupt[base + 2] = (byte) 0xFF;
        corrupt[base + 3] = (byte) 0xFF;
        corrupt[base + 4] = (byte) RegionFileReader.COMPRESSION_DEFLATE;
        Path mca = dir.resolve("r.0.0.mca");
        Files.write(mca, corrupt);

        try (RegionFileReader r = new RegionFileReader(mca)) {
            // hasChunk sees a valid-looking location entry; it's the payload that lies.
            assertThat(r.hasChunk(0, 0)).isTrue();
            assertThat(r.readChunk(0, 0)).isNull();
            assertThat(r.readChunkRaw(0, 0)).isNull();
            // Compression byte itself is still readable.
            assertThat(r.compressionType(0, 0)).isEqualTo(RegionFileReader.COMPRESSION_DEFLATE);
        }
    }

    // -----------------------------------------------------------------
    // Fixture builders
    // -----------------------------------------------------------------

    /**
     * Build a minimal region file with exactly one chunk slot occupied. Layout matches Vanilla:
     * 8192-byte header, then chunk payload sector-aligned starting at sector 2, padded to a full
     * sector.
     */
    private static void writeSingleChunkRegion(
            Path mca, int chunkX, int chunkZ, int compressionByte, byte[] compressedPayload, boolean external)
            throws IOException {
        // In-region payload is (4-byte length + 1-byte type + compressed bytes). External stub is
        // (length=1 + type|0x80) with zero-byte payload.
        int payloadSize = external
                ? RegionFileReader.CHUNK_HEADER_SIZE
                : (RegionFileReader.CHUNK_HEADER_SIZE + compressedPayload.length);
        int sectorCount = (payloadSize + RegionFileHeader.SECTOR_BYTES - 1) / RegionFileHeader.SECTOR_BYTES;
        if (sectorCount == 0) {
            sectorCount = 1;
        }
        int totalBytes = RegionFileHeader.HEADER_BYTES + sectorCount * RegionFileHeader.SECTOR_BYTES;

        byte[] file = new byte[totalBytes];
        // Location entry at slot for (chunkX, chunkZ). sectorOffset=2, sectorCount=<computed>.
        int slot = RegionFileHeader.slotIndex(RegionFileHeader.localX(chunkX), RegionFileHeader.localZ(chunkZ));
        int packed = RegionFileHeader.packLocationEntry(2, sectorCount);
        ByteBuffer wrap = ByteBuffer.wrap(file);
        wrap.putInt(slot * 4, packed);
        // Timestamp (not asserted, just non-zero for realism).
        wrap.putInt(RegionFileHeader.TIMESTAMP_TABLE_OFFSET + slot * 4, 1_700_000_000);

        // Chunk header: length = payloadCompressedBytes + 1 (the compression byte). External stub
        // uses length=1 (Vanilla's createExternalStub, RegionFile.java:320-326).
        int declaredLength = external ? 1 : (compressedPayload.length + 1);
        int base = 2 * RegionFileHeader.SECTOR_BYTES;
        wrap.putInt(base, declaredLength);
        file[base + 4] = (byte) compressionByte;
        if (!external && compressedPayload.length > 0) {
            System.arraycopy(compressedPayload, 0, file, base + 5, compressedPayload.length);
        }

        Files.write(mca, file);
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(out)) {
            g.write(data);
        }
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream d = new DeflaterOutputStream(out, new Deflater(Deflater.DEFAULT_COMPRESSION))) {
            d.write(data);
        }
        return out.toByteArray();
    }
}
