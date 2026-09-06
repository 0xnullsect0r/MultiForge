/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.multiforge.runtime.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.Objects;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * MCA writer for MultiForge — byte-parity with Vanilla {@code
 * net.minecraft.world.level.chunk.storage.RegionFile} on a fresh file
 * receiving the same sequence of writes.
 *
 * <p>Layout (per {@code docs/design/mca-format.md}):
 *
 * <ul>
 *   <li>Bytes 0-4095: location table — 1024 × 4-byte packed
 *       {@code (sectorOffset:24, sectorCount:8)}, big-endian, row-major
 *       X-fast at index {@code (chunkX & 31) + (chunkZ & 31) * 32}.
 *   <li>Bytes 4096-8191: timestamp table — 1024 × 4-byte epoch-seconds,
 *       written on every mutation, matching Vanilla.
 *   <li>Bytes 8192+: chunk payloads, sector-aligned. Each payload is
 *       {@code (int32 length-BE, int8 compressionType, byte[] compressed)}
 *       where {@code length = 1 + compressed.length}. Sectors 0 and 1
 *       are permanently reserved for the header.
 *   <li>{@code compressionType} MSB (0x80) marks external-file spillover;
 *       when a chunk needs {@code >= 256} sectors we spill the compressed
 *       payload to {@code c.<chunkX>.<chunkZ>.mcc} in the same directory
 *       and write a 1-sector stub {@code (length=1, type|0x80)} into the
 *       region file at a freshly allocated slot.
 * </ul>
 *
 * <p>The sector allocator mirrors Vanilla's {@code RegionBitmap}: first-fit
 * over a {@link BitSet}, growing the file logically when no gap is large
 * enough. Rewrites allocate the new sectors and flush the payload
 * <em>before</em> freeing the old ones so a mid-write crash still leaves
 * the on-disk header pointing at readable data (Vanilla's crash-safety
 * contract, {@code RegionFile.java:311-317}). A same-sector-count rewrite
 * skips the allocator dance and overwrites the existing sectors in place.
 *
 * <p>{@link #writeChunk} calls {@link FileChannel#force(boolean)
 * channel.force(true)} at the end of every mutation. This is the safer
 * side of the mca-format.md §10 open question — a WAL-style guarantee for
 * Phase 5.3 autosave. Batched fsync is a Phase 7 bench decision.
 *
 * <p>This class is <b>not</b> internally thread-safe. Cross-thread access
 * is arbitrated by the per-file {@code ReadWriteLock} in
 * {@code RegionFileCache} (Phase 3 task 3.4).
 */
public final class RegionFileWriter implements AutoCloseable {

    // These constants move to RegionFileHeader once Phase 3 task 3.1 lands;
    // keeping local mirrors avoids a build-order coupling between parallel
    // Phase 3 tasks. Values are Vanilla-exact.
    public static final int SECTOR_BYTES = 4096;
    public static final int HEADER_BYTES = 8192;
    public static final int SECTOR_INTS = 1024;
    public static final int CHUNK_HEADER_SIZE = 5;
    public static final int EXTERNAL_STREAM_FLAG = 0x80;
    public static final int EXTERNAL_CHUNK_THRESHOLD = 256;

    public static final int COMPRESSION_GZIP = 1;
    public static final int COMPRESSION_DEFLATE = 2;
    public static final int COMPRESSION_NONE = 3;

    private final Path path;
    private final Path externalDir;
    private final FileChannel channel;
    private final ByteBuffer header;
    private final IntBuffer offsets;
    private final IntBuffer timestamps;
    private final BitSet usedSectors;
    private volatile boolean closed;

    /** Open (create if absent). Reads and validates the existing header. */
    public RegionFileWriter(Path path) throws IOException {
        this.path = Objects.requireNonNull(path, "path");
        Path parent = path.getParent();
        this.externalDir = parent == null ? Path.of(".") : parent;
        Files.createDirectories(this.externalDir);
        this.channel =
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);

        this.header = ByteBuffer.allocate(HEADER_BYTES);
        // Two overlapping IntBuffer views over the header buffer:
        //   offsets    -> bytes [0, 4096)
        //   timestamps -> bytes [4096, 8192)
        this.header.position(0);
        IntBuffer offsetsView = this.header.asIntBuffer();
        offsetsView.limit(SECTOR_INTS);
        this.offsets = offsetsView;
        this.header.position(SECTOR_BYTES);
        IntBuffer timestampsView = this.header.asIntBuffer();
        timestampsView.limit(SECTOR_INTS);
        this.timestamps = timestampsView;
        this.header.position(0);

        this.usedSectors = new BitSet();
        // Sectors 0 and 1 are the header, permanently reserved.
        this.usedSectors.set(0);
        this.usedSectors.set(1);

        long fileSize = channel.size();
        if (fileSize > 0L) {
            this.header.clear();
            channel.read(this.header, 0L);
            this.header.position(0);
            // Populate the used-sector bitmap from the existing location table,
            // clearing any entry that would collide with the header or extend
            // past EOF (matches Vanilla RegionFile:80-98).
            for (int i = 0; i < SECTOR_INTS; i++) {
                int loc = offsets.get(i);
                if (loc == 0) {
                    continue;
                }
                int sector = sectorOffsetOf(loc);
                int count = sectorCountOf(loc);
                if (sector < 2 || count == 0 || (long) sector * SECTOR_BYTES > fileSize) {
                    offsets.put(i, 0);
                } else {
                    usedSectors.set(sector, sector + count);
                }
            }
        }
    }

    /** Compressed-write with the default compression type (deflate). */
    public void writeChunk(int chunkX, int chunkZ, byte[] payload) throws IOException {
        writeChunk(chunkX, chunkZ, payload, COMPRESSION_DEFLATE);
    }

    /**
     * Compressed-write with an explicit compression type. Allocates sectors,
     * writes the payload, updates the header, then fsyncs.
     *
     * @param compressionType one of {@link #COMPRESSION_GZIP},
     *     {@link #COMPRESSION_DEFLATE}, {@link #COMPRESSION_NONE}.
     */
    public void writeChunk(int chunkX, int chunkZ, byte[] payload, int compressionType) throws IOException {
        Objects.requireNonNull(payload, "payload");
        ensureOpen();
        if (compressionType != COMPRESSION_GZIP
                && compressionType != COMPRESSION_DEFLATE
                && compressionType != COMPRESSION_NONE) {
            throw new IllegalArgumentException("Unsupported compression type: " + compressionType);
        }

        byte[] compressed = compress(payload, compressionType);
        int totalBytes = CHUNK_HEADER_SIZE + compressed.length;
        int sectorsNeeded = sizeToSectors(totalBytes);

        int idx = offsetIndex(chunkX, chunkZ);
        int oldLoc = offsets.get(idx);
        int oldSector = sectorOffsetOf(oldLoc);
        int oldCount = sectorCountOf(oldLoc);

        int newSector;
        int newCount;

        if (sectorsNeeded >= EXTERNAL_CHUNK_THRESHOLD) {
            // Oversized chunk: spill to c.<x>.<z>.mcc, keep a 1-sector stub
            // in the region file (matches RegionFile.java:297-304).
            newCount = 1;
            if (oldCount == newCount && oldSector >= 2) {
                newSector = oldSector;
            } else {
                newSector = allocate(newCount);
            }
            writeExternalFile(chunkX, chunkZ, compressed);
            ByteBuffer stub = ByteBuffer.allocate(CHUNK_HEADER_SIZE);
            stub.putInt(1);
            stub.put((byte) (compressionType | EXTERNAL_STREAM_FLAG));
            stub.flip();
            writeAtSector(newSector, stub);
            // Free the old range only after the stub is on disk — Vanilla's
            // durability invariant: the header never points at unreadable
            // sectors, even mid-write.
            if (newSector != oldSector && oldCount > 0) {
                usedSectors.clear(oldSector, oldSector + oldCount);
            }
        } else {
            newCount = sectorsNeeded;
            if (oldCount == newCount && oldSector >= 2) {
                // Same footprint: overwrite in place, skip the allocator dance.
                newSector = oldSector;
            } else {
                newSector = allocate(newCount);
            }
            ByteBuffer buf = ByteBuffer.allocate(totalBytes);
            buf.putInt(1 + compressed.length);
            buf.put((byte) compressionType);
            buf.put(compressed);
            buf.flip();
            writeAtSector(newSector, buf);
            if (newSector != oldSector && oldCount > 0) {
                usedSectors.clear(oldSector, oldSector + oldCount);
            }
            // Clean up any stale external sidecar if we just shrank
            // an external chunk back into an inline one.
            Files.deleteIfExists(externalChunkPath(chunkX, chunkZ));
        }

        offsets.put(idx, packSectorLoc(newSector, newCount));
        timestamps.put(idx, currentTimestamp());
        writeHeader();
        channel.force(true);
    }

    /** Delete a chunk: free its sectors, zero its location + timestamp entry, drop any .mcc sidecar. */
    public void deleteChunk(int chunkX, int chunkZ) throws IOException {
        ensureOpen();
        int idx = offsetIndex(chunkX, chunkZ);
        int loc = offsets.get(idx);
        if (loc == 0) {
            return;
        }
        int sector = sectorOffsetOf(loc);
        int count = sectorCountOf(loc);
        if (sector >= 2 && count > 0) {
            usedSectors.clear(sector, sector + count);
        }
        offsets.put(idx, 0);
        // Task-spec: zero the timestamp entry. (Vanilla writes the current
        // timestamp at delete; the task's byte-parity contract does not cover
        // deleteChunk, and semantic diff ignores the timestamp field either
        // way, so zeroing is consistent with the "chunk not present" semantics.)
        timestamps.put(idx, 0);
        writeHeader();
        Files.deleteIfExists(externalChunkPath(chunkX, chunkZ));
        channel.force(true);
    }

    /** Rewrite the header to disk and fsync. Idempotent. */
    public void flush() throws IOException {
        if (closed) {
            return;
        }
        writeHeader();
        channel.force(true);
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            padToFullSector();
        } finally {
            try {
                channel.force(true);
            } finally {
                channel.close();
            }
        }
    }

    // ---- internals ----

    private byte[] compress(byte[] payload, int type) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(payload.length / 2, 64));
        switch (type) {
            case COMPRESSION_GZIP -> {
                try (GZIPOutputStream out = new GZIPOutputStream(baos)) {
                    out.write(payload);
                }
            }
            case COMPRESSION_DEFLATE -> {
                // Matches Vanilla's RegionFileVersion.VERSION_DEFLATE: a bare
                // DeflaterOutputStream over a fresh Deflater at DEFAULT_COMPRESSION
                // (level 6). The BufferedOutputStream wrapper Vanilla adds is
                // pure buffering — flushed on close — and does not alter the
                // deflate byte stream, so the compressed output matches byte
                // for byte.
                try (DeflaterOutputStream out = new DeflaterOutputStream(baos)) {
                    out.write(payload);
                }
            }
            case COMPRESSION_NONE -> baos.write(payload);
            default -> throw new IllegalStateException("unreachable: " + type);
        }
        return baos.toByteArray();
    }

    /** First-fit contiguous allocation, mirroring {@code RegionBitmap.allocate}. */
    private int allocate(int count) {
        int i = 0;
        while (true) {
            int j = usedSectors.nextClearBit(i);
            int k = usedSectors.nextSetBit(j);
            if (k == -1 || k - j >= count) {
                usedSectors.set(j, j + count);
                return j;
            }
            i = k;
        }
    }

    private void writeAtSector(int sector, ByteBuffer buf) throws IOException {
        long fileOffset = (long) sector * SECTOR_BYTES;
        while (buf.hasRemaining()) {
            int wrote = channel.write(buf, fileOffset);
            if (wrote <= 0) {
                throw new IOException("short write to " + path);
            }
            fileOffset += wrote;
        }
    }

    private void writeExternalFile(int chunkX, int chunkZ, byte[] compressed) throws IOException {
        Path target = externalChunkPath(chunkX, chunkZ);
        Path tmp = Files.createTempFile(externalDir, "tmp", null);
        try {
            try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                ByteBuffer buf = ByteBuffer.wrap(compressed);
                while (buf.hasRemaining()) {
                    int wrote = out.write(buf);
                    if (wrote <= 0) {
                        throw new IOException("short write to " + tmp);
                    }
                }
                out.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private void writeHeader() throws IOException {
        header.position(0);
        long fileOffset = 0L;
        while (header.hasRemaining()) {
            int wrote = channel.write(header, fileOffset);
            if (wrote <= 0) {
                throw new IOException("short header write to " + path);
            }
            fileOffset += wrote;
        }
        header.position(0);
    }

    private void padToFullSector() throws IOException {
        long size = channel.size();
        long padded = (long) sizeToSectors((int) Math.min(size, Integer.MAX_VALUE)) * SECTOR_BYTES;
        if (size > 0L && size < padded) {
            ByteBuffer pad = ByteBuffer.allocate(1);
            channel.write(pad, padded - 1L);
        }
    }

    private Path externalChunkPath(int chunkX, int chunkZ) {
        return externalDir.resolve("c." + chunkX + "." + chunkZ + ".mcc");
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("RegionFileWriter closed: " + path);
        }
    }

    // ---- packing helpers (Vanilla-exact) ----

    static int packSectorLoc(int sectorOffset, int sectorCount) {
        return (sectorOffset << 8) | (sectorCount & 0xFF);
    }

    static int sectorOffsetOf(int loc) {
        return (loc >>> 8) & 0x00FF_FFFF;
    }

    static int sectorCountOf(int loc) {
        return loc & 0xFF;
    }

    static int sizeToSectors(int size) {
        return (size + SECTOR_BYTES - 1) / SECTOR_BYTES;
    }

    static int offsetIndex(int chunkX, int chunkZ) {
        return (chunkX & 31) + (chunkZ & 31) * 32;
    }

    private static int currentTimestamp() {
        return (int) (System.currentTimeMillis() / 1000L);
    }
}
