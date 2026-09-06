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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.zip.InflaterInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Round-trip and allocator-behavior tests for {@link RegionFileWriter}.
 * The Phase 3 {@code RegionFileReader} is being built in parallel, so
 * these tests decode chunks with local helper methods that mirror the
 * on-disk contract from {@code docs/design/mca-format.md}.
 */
class RegionFileWriterTest {

    @Test
    void writesAndReadsBackSingleChunk(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        byte[] payload = "hello world — MultiForge chunk payload".getBytes();
        try (RegionFileWriter w = new RegionFileWriter(file)) {
            w.writeChunk(0, 0, payload);
        }
        assertThat(readInlineChunk(file, 0, 0)).isEqualTo(payload);
    }

    @Test
    void overwriteInPlaceWhenSameSectorCount(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        try (RegionFileWriter w = new RegionFileWriter(file)) {
            w.writeChunk(0, 0, "AAAAAAAA".getBytes());
            int locA = readLocationEntry(file, 0, 0);
            int sectorA = sectorOf(locA);
            int countA = countOf(locA);
            assertThat(sectorA).isGreaterThanOrEqualTo(2);
            assertThat(countA).isEqualTo(1);

            w.writeChunk(0, 0, "BBBBBBBB".getBytes());
            int locB = readLocationEntry(file, 0, 0);
            assertThat(countOf(locB)).isEqualTo(countA);
            assertThat(sectorOf(locB))
                    .as("same sector count -> in-place overwrite")
                    .isEqualTo(sectorA);
        }
    }

    @Test
    void overwriteReallocsWhenLargerPayload(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        try (RegionFileWriter w = new RegionFileWriter(file)) {
            w.writeChunk(0, 0, "tiny".getBytes());
            int locA = readLocationEntry(file, 0, 0);
            int sectorA = sectorOf(locA);
            int countA = countOf(locA);
            assertThat(countA).isEqualTo(1);

            // Random bytes resist deflate compression; 32 KiB produces
            // several sectors of compressed output.
            byte[] big = new byte[32 * 1024];
            new Random(0xC0FFEE).nextBytes(big);
            w.writeChunk(0, 0, big);
            int locB = readLocationEntry(file, 0, 0);
            assertThat(countOf(locB)).as("larger payload -> more sectors").isGreaterThan(countA);
            assertThat(sectorOf(locB)).as("larger payload -> relocated").isNotEqualTo(sectorA);

            // Round-trip verifies the actual bytes survived.
            assertThat(readInlineChunk(file, 0, 0)).isEqualTo(big);
        }
    }

    @Test
    void deleteChunkZerosLocation(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        try (RegionFileWriter w = new RegionFileWriter(file)) {
            w.writeChunk(3, 7, "foo".getBytes());
            assertThat(readLocationEntry(file, 3, 7)).isNotZero();

            w.deleteChunk(3, 7);
            assertThat(readLocationEntry(file, 3, 7)).isZero();
        }
    }

    @Test
    void sectorAllocatorReusesFreedSectors(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        try (RegionFileWriter w = new RegionFileWriter(file)) {
            w.writeChunk(0, 0, small(0));
            int sectorA = sectorOf(readLocationEntry(file, 0, 0));
            assertThat(sectorA).isEqualTo(2);

            w.writeChunk(1, 0, small(1));
            int sectorB = sectorOf(readLocationEntry(file, 1, 0));
            assertThat(sectorB).isEqualTo(sectorA + 1);

            w.deleteChunk(0, 0);

            w.writeChunk(2, 0, small(2));
            int sectorC = sectorOf(readLocationEntry(file, 2, 0));
            assertThat(sectorC).as("first-fit reuses the freed sector").isEqualTo(sectorA);
        }
    }

    @Test
    void externalMccWrittenWhenPayloadTooLarge(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        // COMPRESSION_NONE keeps the compressed size == input size, so we
        // reliably clear the 256-sector threshold without a 10 MB random
        // buffer racing against deflate ratios.
        int inputSize = RegionFileWriter.EXTERNAL_CHUNK_THRESHOLD * RegionFileWriter.SECTOR_BYTES;
        byte[] huge = new byte[inputSize];
        new Random(1).nextBytes(huge);

        try (RegionFileWriter w = new RegionFileWriter(file)) {
            w.writeChunk(0, 0, huge, RegionFileWriter.COMPRESSION_NONE);
        }

        Path mcc = dir.resolve("c.0.0.mcc");
        assertThat(Files.exists(mcc)).isTrue();
        assertThat(Files.size(mcc)).isEqualTo(inputSize);

        int loc = readLocationEntry(file, 0, 0);
        assertThat(countOf(loc)).as("external stub occupies exactly one sector").isEqualTo(1);
        ByteBuffer stub = readSector(file, sectorOf(loc));
        int length = stub.getInt();
        byte compType = stub.get();
        assertThat(length).as("stub length field = 1").isEqualTo(1);
        assertThat(compType & RegionFileWriter.EXTERNAL_STREAM_FLAG)
                .as("MSB of compression byte set on external chunk")
                .isEqualTo(RegionFileWriter.EXTERNAL_STREAM_FLAG);
        assertThat(compType & 0x7F)
                .as("low 7 bits carry the real compression type")
                .isEqualTo(RegionFileWriter.COMPRESSION_NONE);

        // .mcc sidecar carries the raw compressed payload with no header prefix.
        assertThat(Files.readAllBytes(mcc)).isEqualTo(huge);
    }

    // ---- helpers ----

    private static byte[] small(int seed) {
        byte[] b = new byte[64];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (seed * 31 + i);
        }
        return b;
    }

    private static int sectorOf(int loc) {
        return (loc >>> 8) & 0x00FF_FFFF;
    }

    private static int countOf(int loc) {
        return loc & 0xFF;
    }

    private static int readLocationEntry(Path file, int chunkX, int chunkZ) throws IOException {
        int byteOffset = ((chunkX & 31) + (chunkZ & 31) * 32) * 4;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer b = ByteBuffer.allocate(4);
            ch.read(b, byteOffset);
            b.flip();
            return b.getInt();
        }
    }

    private static ByteBuffer readSector(Path file, int sector) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer b = ByteBuffer.allocate(RegionFileWriter.SECTOR_BYTES);
            ch.read(b, (long) sector * RegionFileWriter.SECTOR_BYTES);
            b.flip();
            return b;
        }
    }

    /** Decode an inline (non-external) chunk from the on-disk MCA layout. */
    private static byte[] readInlineChunk(Path file, int chunkX, int chunkZ) throws IOException {
        int loc = readLocationEntry(file, chunkX, chunkZ);
        int sector = sectorOf(loc);
        int count = countOf(loc);
        int totalCapacity = count * RegionFileWriter.SECTOR_BYTES;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer b = ByteBuffer.allocate(totalCapacity);
            ch.read(b, (long) sector * RegionFileWriter.SECTOR_BYTES);
            b.flip();
            int length = b.getInt();
            byte compType = b.get();
            byte[] compressed = new byte[length - 1];
            b.get(compressed);
            switch (compType) {
                case RegionFileWriter.COMPRESSION_DEFLATE:
                    try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
                        return in.readAllBytes();
                    }
                case RegionFileWriter.COMPRESSION_NONE:
                    return compressed;
                default:
                    throw new IOException("unsupported compression type in test decode: " + compType);
            }
        }
    }
}
