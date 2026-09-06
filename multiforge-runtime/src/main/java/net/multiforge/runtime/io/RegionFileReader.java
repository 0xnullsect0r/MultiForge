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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.multiforge.runtime.diagnostics.ViolationLogger;

/**
 * Read-only view of one MCA region file — per-slot payload read + decompress.
 *
 * <p>Byte-level contract in {@code docs/design/mca-format.md}. Slot layout, sector allocation and
 * compression-type semantics all match Vanilla {@code net.minecraft.world.level.chunk.storage.RegionFile}
 * so a MultiForge reader can consume any file Vanilla wrote and vice versa (Phase 3 task 3.7's
 * byte-identical parity regression relies on this).
 *
 * <h2>Concurrency</h2>
 * This class is safe for concurrent reads by multiple threads. The 8192-byte header buffer is
 * populated once at construction and never mutated, and {@link FileChannel#read(ByteBuffer, long)}
 * is a positional read that does not touch the channel's position field, so concurrent readChunk /
 * readChunkRaw / hasChunk / compressionType calls are all safe. Cross-region coordination against
 * writers is the responsibility of a higher layer ({@code RegionFileCache}, Phase 3 task 3.4).
 *
 * <h2>Fail-soft</h2>
 * Every corruption mode observed by {@code WorldDiffTest} — overflowing sector offsets, chunk-length
 * fields larger than the file, missing external {@code .mcc} sidecars, unknown compression IDs, and
 * decompressor errors — returns {@code null} with a rate-limited warn rather than throwing. This
 * matches Vanilla's {@code LOGGER.error} + return-null shape and honors CLAUDE.md rule 5 ("never
 * throw from a mod's code path").
 */
public final class RegionFileReader implements AutoCloseable {

    /**
     * Compression IDs from Vanilla {@code RegionFileVersion}. Kept as local constants so this class
     * doesn't take a dependency on the Minecraft classpath (multiforge-runtime is MC-free).
     */
    public static final int COMPRESSION_GZIP = 1;

    public static final int COMPRESSION_DEFLATE = 2;
    public static final int COMPRESSION_NONE = 3;
    public static final int COMPRESSION_CUSTOM = 127;

    /** Vanilla {@code RegionFile.EXTERNAL_STREAM_FLAG} — MSB of the compression byte. */
    public static final int EXTERNAL_STREAM_FLAG = 0x80;

    /** Vanilla {@code RegionFile.CHUNK_HEADER_SIZE}: 4-byte length + 1-byte compression type. */
    public static final int CHUNK_HEADER_SIZE = 5;

    private final Path path;
    private final Path externalFileDir;
    private final FileChannel channel;
    private final long fileSize;

    /**
     * Cached 8192-byte header, read once at construction. Not mutated after construction, so
     * concurrent reads are safe without synchronization.
     */
    private final ByteBuffer header;

    public RegionFileReader(Path path) throws IOException {
        this.path = path;
        this.externalFileDir = path.getParent() != null ? path.getParent() : Path.of(".");
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        this.fileSize = channel.size();
        this.header = ByteBuffer.allocate(RegionFileHeader.HEADER_BYTES);
        readHeader();
    }

    private void readHeader() throws IOException {
        // A newly-created MCA (0 bytes) is a valid empty region — every slot reads as "no chunk".
        // Vanilla treats a truncated header the same way (RegionFile.java:73-76 warns and continues
        // with the partial data); allocate() zero-fills, so unread trailing bytes are already 0.
        if (fileSize == 0L) {
            return;
        }
        int read = channel.read(header, 0L);
        if (read < RegionFileHeader.HEADER_BYTES) {
            ViolationLogger.warn(
                    "mca.reader.truncated-header",
                    "region file " + path + " header is " + Math.max(read, 0) + " bytes (expected "
                            + RegionFileHeader.HEADER_BYTES + "); missing slots read as empty");
        }
        header.position(0);
    }

    /**
     * True if the slot for {@code (chunkX, chunkZ)} has a location entry that decodes into a valid,
     * in-range sector range. Returns false for empty slots, sector-offset overlaps with the header,
     * zero-sector counts, and out-of-file entries — matching Vanilla's own header-scan checks
     * (RegionFile.java:80-98).
     */
    public boolean hasChunk(int chunkX, int chunkZ) throws IOException {
        int loc = locationEntry(chunkX, chunkZ);
        if (loc == 0) {
            return false;
        }
        int sectorOffset = RegionFileHeader.sectorOffset(loc);
        int sectorCount = RegionFileHeader.sectorCount(loc);
        long totalSectors = (fileSize + RegionFileHeader.SECTOR_BYTES - 1L) / RegionFileHeader.SECTOR_BYTES;
        return RegionFileHeader.isValidLocation(sectorOffset, sectorCount, totalSectors);
    }

    /**
     * Compression byte for the slot, including the MSB external-stream flag. Returns {@code -1} if
     * the slot is empty, unreadable, or truncated. Callers usually only care about
     * {@code result & 0x7F} for the compression family and {@code (result & 0x80) != 0} for the
     * external-file bit.
     */
    public int compressionType(int chunkX, int chunkZ) throws IOException {
        SlotRead read = readSlotHeader(chunkX, chunkZ);
        return read == null ? -1 : read.compressionByte & 0xFF;
    }

    /**
     * Raw compressed payload for one chunk slot — the 5-byte {@code (length, type)} prefix is
     * stripped. Useful for hash-diff and byte-identical parity tests that want to compare
     * pre-decompression bytes.
     *
     * <p>Returns {@code null} for empty slots and every corruption mode enumerated in the class
     * javadoc.
     */
    public byte[] readChunkRaw(int chunkX, int chunkZ) throws IOException {
        SlotRead read = readSlotHeader(chunkX, chunkZ);
        if (read == null) {
            return null;
        }
        if ((read.compressionByte & EXTERNAL_STREAM_FLAG) != 0) {
            // External spill: entire .mcc file is the raw payload (already header-stripped).
            return readExternalPayload(chunkX, chunkZ);
        }
        // In-region payload: length includes the compression byte; the actual compressed
        // bytes are length-1 in size, starting immediately after the 5-byte header.
        int payloadLength = read.declaredLength - 1;
        if (payloadLength <= 0) {
            return new byte[0];
        }
        long payloadStart = (long) read.sectorOffset * RegionFileHeader.SECTOR_BYTES + CHUNK_HEADER_SIZE;
        // Long arithmetic throughout — /67 round-3 finding D1 hardened this against
        // (sectorOffset=0xFFFFFF) * 4096 wrapping into a negative int.
        long payloadEnd = payloadStart + payloadLength;
        long sectorEnd = (long) (read.sectorOffset + read.sectorCount) * RegionFileHeader.SECTOR_BYTES;
        if (payloadEnd > fileSize || payloadEnd > sectorEnd) {
            ViolationLogger.warn(
                    "mca.reader.truncated-payload",
                    "chunk (" + chunkX + "," + chunkZ + ") in " + path + " declared length " + read.declaredLength
                            + " overruns its sector range");
            return null;
        }
        byte[] out = new byte[payloadLength];
        int n = channel.read(ByteBuffer.wrap(out), payloadStart);
        if (n != payloadLength) {
            ViolationLogger.warn(
                    "mca.reader.short-read",
                    "chunk (" + chunkX + "," + chunkZ + ") in " + path + " read " + n + " bytes, expected "
                            + payloadLength);
            return null;
        }
        return out;
    }

    /**
     * Read and decompress the payload for one chunk slot. Returns {@code null} if the slot is
     * empty, corrupted, or fails to decompress; never throws.
     */
    public byte[] readChunk(int chunkX, int chunkZ) throws IOException {
        SlotRead read = readSlotHeader(chunkX, chunkZ);
        if (read == null) {
            return null;
        }
        byte[] compressed;
        if ((read.compressionByte & EXTERNAL_STREAM_FLAG) != 0) {
            compressed = readExternalPayload(chunkX, chunkZ);
        } else {
            int payloadLength = read.declaredLength - 1;
            if (payloadLength < 0) {
                return null;
            }
            if (payloadLength == 0) {
                return new byte[0];
            }
            long payloadStart = (long) read.sectorOffset * RegionFileHeader.SECTOR_BYTES + CHUNK_HEADER_SIZE;
            long payloadEnd = payloadStart + payloadLength;
            long sectorEnd = (long) (read.sectorOffset + read.sectorCount) * RegionFileHeader.SECTOR_BYTES;
            if (payloadEnd > fileSize || payloadEnd > sectorEnd) {
                ViolationLogger.warn(
                        "mca.reader.truncated-payload",
                        "chunk (" + chunkX + "," + chunkZ + ") in " + path + " declared length " + read.declaredLength
                                + " overruns its sector range");
                return null;
            }
            compressed = new byte[payloadLength];
            int n = channel.read(ByteBuffer.wrap(compressed), payloadStart);
            if (n != payloadLength) {
                ViolationLogger.warn(
                        "mca.reader.short-read",
                        "chunk (" + chunkX + "," + chunkZ + ") in " + path + " read " + n + " bytes, expected "
                                + payloadLength);
                return null;
            }
        }
        if (compressed == null) {
            return null;
        }
        int compression = read.compressionByte & ~EXTERNAL_STREAM_FLAG & 0xFF;
        try {
            return decompress(chunkX, chunkZ, compression, compressed);
        } catch (IOException | RuntimeException ex) {
            ViolationLogger.warn(
                    "mca.reader.decompress-failed",
                    "chunk (" + chunkX + "," + chunkZ + ") in " + path + " failed to decompress (type=" + compression
                            + "): " + ex.getMessage());
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /** Immutable per-slot header snapshot. */
    private record SlotRead(int sectorOffset, int sectorCount, int declaredLength, byte compressionByte) {}

    private int locationEntry(int chunkX, int chunkZ) {
        int slot = RegionFileHeader.slotIndex(RegionFileHeader.localX(chunkX), RegionFileHeader.localZ(chunkZ));
        return RegionFileHeader.locationEntry(header, slot);
    }

    /**
     * Read and validate the 5-byte chunk header at the slot. Returns {@code null} for empty slots
     * and every corruption mode. Never throws.
     */
    private SlotRead readSlotHeader(int chunkX, int chunkZ) throws IOException {
        int loc = locationEntry(chunkX, chunkZ);
        if (loc == 0) {
            return null;
        }
        int sectorOffset = RegionFileHeader.sectorOffset(loc);
        int sectorCount = RegionFileHeader.sectorCount(loc);
        long totalSectors = (fileSize + RegionFileHeader.SECTOR_BYTES - 1L) / RegionFileHeader.SECTOR_BYTES;
        if (!RegionFileHeader.isValidLocation(sectorOffset, sectorCount, totalSectors)) {
            ViolationLogger.warn(
                    "mca.reader.corrupt-location",
                    "region " + path + " slot for (" + chunkX + "," + chunkZ + ") has invalid location entry "
                            + "sectorOffset=" + sectorOffset + " sectorCount=" + sectorCount
                            + " (fileSize=" + fileSize + ")");
            return null;
        }
        long sectorStart = (long) sectorOffset * RegionFileHeader.SECTOR_BYTES;
        if (sectorStart + CHUNK_HEADER_SIZE > fileSize) {
            ViolationLogger.warn(
                    "mca.reader.header-eof",
                    "region " + path + " chunk (" + chunkX + "," + chunkZ + ") header extends past EOF");
            return null;
        }
        ByteBuffer buf = ByteBuffer.allocate(CHUNK_HEADER_SIZE);
        int read = channel.read(buf, sectorStart);
        if (read != CHUNK_HEADER_SIZE) {
            ViolationLogger.warn(
                    "mca.reader.header-short",
                    "region " + path + " chunk (" + chunkX + "," + chunkZ + ") read " + read + " header bytes");
            return null;
        }
        buf.flip();
        int declaredLength = buf.getInt();
        byte compressionByte = buf.get();
        if (declaredLength == 0) {
            // Vanilla warn: "Chunk {} is allocated, but stream is missing" (RegionFile.java:131).
            ViolationLogger.warn(
                    "mca.reader.zero-length",
                    "region " + path + " chunk (" + chunkX + "," + chunkZ + ") declared length is 0");
            return null;
        }
        if (declaredLength < 0) {
            ViolationLogger.warn(
                    "mca.reader.negative-length",
                    "region " + path + " chunk (" + chunkX + "," + chunkZ + ") declared length " + declaredLength
                            + " is negative");
            return null;
        }
        return new SlotRead(sectorOffset, sectorCount, declaredLength, compressionByte);
    }

    /** Read the entire {@code c.<x>.<z>.mcc} sidecar. Returns null with warn on missing/short read. */
    private byte[] readExternalPayload(int chunkX, int chunkZ) throws IOException {
        Path external = externalPath(chunkX, chunkZ);
        try {
            if (!Files.isRegularFile(external)) {
                ViolationLogger.warn(
                        "mca.reader.external-missing",
                        "external chunk file " + external + " is missing for chunk (" + chunkX + "," + chunkZ + ")");
                return null;
            }
            return Files.readAllBytes(external);
        } catch (NoSuchFileException ex) {
            // Race with a concurrent writer/deleter — matches design doc §10 open question 2.
            ViolationLogger.warn(
                    "mca.reader.external-race",
                    "external chunk file " + external + " vanished during read: " + ex.getMessage());
            return null;
        }
    }

    /**
     * External-file naming matches Vanilla {@code RegionFile.getExternalChunkPath}: {@code c.X.Z.mcc}
     * where {@code X} and {@code Z} are the full world chunk coordinates (not region-local). See
     * Vanilla {@code RegionFile.java:107-110}. The design doc §1 wording ("regionLocalX / Z") is
     * imprecise on this point but its citation resolves to the code that uses full coords, and
     * matching Vanilla exactly is a hard requirement for the Phase 3 task 3.7 parity regression.
     */
    private Path externalPath(int chunkX, int chunkZ) {
        return externalFileDir.resolve("c." + chunkX + "." + chunkZ + ".mcc");
    }

    private static byte[] decompress(int chunkX, int chunkZ, int compression, byte[] compressed) throws IOException {
        return switch (compression) {
            case COMPRESSION_GZIP -> inflateAll(new GZIPInputStream(new ByteArrayInputStream(compressed)));
            case COMPRESSION_DEFLATE -> inflateAll(new InflaterInputStream(new ByteArrayInputStream(compressed)));
            case COMPRESSION_NONE -> compressed.clone();
            case COMPRESSION_CUSTOM -> throw new IOException(
                    "chunk (" + chunkX + "," + chunkZ + ") uses custom compression 127 which requires a"
                            + " Vanilla-side registry lookup; RegionFileReader stays MC-free.");
            default -> throw new IOException(
                    "chunk (" + chunkX + "," + chunkZ + ") has unknown compression type " + compression);
        };
    }

    private static byte[] inflateAll(InputStream in) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
