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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Pure byte-level parser/emitter for the 8192-byte MCA region file header.
 *
 * <p>Layout (see {@code docs/design/mca-format.md} §1 and Vanilla
 * {@code net.minecraft.world.level.chunk.storage.RegionFile}):
 *
 * <ul>
 *   <li>Bytes {@code 0..4095}: location table — 1024 × 4-byte big-endian entries packed as
 *       {@code (sectorOffset << 8) | sectorCount}, where {@code sectorOffset} is the 24-bit
 *       chunk payload start (in 4096-byte sectors from the file origin) and {@code sectorCount}
 *       is the 8-bit sector length. An all-zero entry means the slot is empty.
 *   <li>Bytes {@code 4096..8191}: timestamp table — 1024 × 4-byte big-endian epoch seconds of
 *       last modification.
 * </ul>
 *
 * <p>Slot index formula: {@code chunkLocalX + chunkLocalZ * 32} (row-major, X-fast) where
 * {@code chunkLocalX = chunkX & 31} and {@code chunkLocalZ = chunkZ & 31}.
 *
 * <p>This class performs no I/O. Callers pass in {@link ByteBuffer} snapshots — usually a slice
 * of the first {@link #HEADER_BYTES} of a memory-mapped MCA file. All multi-byte integers are
 * big-endian; buffers whose byte order has been flipped are transparently corrected on read/write.
 */
public final class RegionFileHeader {

    /** Sector size in bytes — Vanilla {@code RegionFile.SECTOR_BYTES}. */
    public static final int SECTOR_BYTES = 4096;

    /** Size of the fixed header (location table + timestamp table). */
    public static final int HEADER_BYTES = 8192;

    /** Number of chunk slots addressable by one region file (32 × 32). */
    public static final int SLOTS = 1024;

    /** Size of the location table in bytes. */
    public static final int LOCATION_TABLE_BYTES = 4096;

    /** Size of the timestamp table in bytes. */
    public static final int TIMESTAMP_TABLE_BYTES = 4096;

    /** Byte offset (from the start of the header) at which the timestamp table begins. */
    public static final int TIMESTAMP_TABLE_OFFSET = LOCATION_TABLE_BYTES;

    /** Sectors 0..1 hold the header and are pinned in the sector allocator. */
    public static final int RESERVED_SECTORS = 2;

    /** Maximum representable sector offset (24 bits). */
    public static final int MAX_SECTOR_OFFSET = 0xFF_FF_FF;

    /** Maximum representable sector count (8 bits). */
    public static final int MAX_SECTOR_COUNT = 0xFF;

    private RegionFileHeader() {
        // no instances
    }

    // ---------------------------------------------------------------------
    // Slot index math
    // ---------------------------------------------------------------------

    /**
     * Slot index in the location/timestamp table for a chunk-local (x, z). Both coordinates must
     * be in {@code [0, 31]}; callers with world chunk coordinates should mask via
     * {@link #localX(int)} / {@link #localZ(int)} first.
     */
    public static int slotIndex(int chunkLocalX, int chunkLocalZ) {
        if ((chunkLocalX | chunkLocalZ | (31 - chunkLocalX) | (31 - chunkLocalZ)) < 0) {
            throw new IllegalArgumentException(
                    "chunkLocal coords must be in [0,31]: x=" + chunkLocalX + " z=" + chunkLocalZ);
        }
        return chunkLocalX + chunkLocalZ * 32;
    }

    /** Chunk-local X for a world chunk-X coordinate. */
    public static int localX(int chunkX) {
        return chunkX & 31;
    }

    /** Chunk-local Z for a world chunk-Z coordinate. */
    public static int localZ(int chunkZ) {
        return chunkZ & 31;
    }

    // ---------------------------------------------------------------------
    // Location table
    // ---------------------------------------------------------------------

    /**
     * Read the raw 32-bit big-endian location entry for {@code slot}. The returned int packs
     * {@code (sectorOffset << 8) | sectorCount}. An empty slot reads as {@code 0}.
     */
    public static int locationEntry(ByteBuffer header, int slot) {
        checkSlot(slot);
        return readBigEndianInt(header, slot * 4);
    }

    /**
     * Sector offset (in 4096-byte sectors, not bytes) from a packed location entry. Header
     * sectors 0..1 are reserved, so a valid non-empty entry always has {@code sectorOffset >= 2}.
     */
    public static int sectorOffset(int locationEntry) {
        return locationEntry >>> 8;
    }

    /** Sector count (in 4096-byte sectors) from a packed location entry. */
    public static int sectorCount(int locationEntry) {
        return locationEntry & 0xFF;
    }

    /**
     * Pack {@code (sectorOffset, sectorCount)} into the 32-bit form used by the location table.
     *
     * @throws IllegalArgumentException if either argument is out of the 24/8-bit range.
     */
    public static int packLocationEntry(int sectorOffset, int sectorCount) {
        if (sectorOffset < 0 || sectorOffset > MAX_SECTOR_OFFSET) {
            throw new IllegalArgumentException("sectorOffset out of 24-bit range: " + sectorOffset);
        }
        if (sectorCount < 0 || sectorCount > MAX_SECTOR_COUNT) {
            throw new IllegalArgumentException("sectorCount out of 8-bit range: " + sectorCount);
        }
        return (sectorOffset << 8) | (sectorCount & 0xFF);
    }

    /** Write a packed location entry at {@code slot}. */
    public static void writeLocationEntry(ByteBuffer header, int slot, int packed) {
        checkSlot(slot);
        writeBigEndianInt(header, slot * 4, packed);
    }

    // ---------------------------------------------------------------------
    // Timestamp table
    // ---------------------------------------------------------------------

    /** Read the timestamp entry (epoch seconds) for {@code slot}. */
    public static int timestampEntry(ByteBuffer header, int slot) {
        checkSlot(slot);
        return readBigEndianInt(header, TIMESTAMP_TABLE_OFFSET + slot * 4);
    }

    /** Write the timestamp entry (epoch seconds) for {@code slot}. */
    public static void writeTimestampEntry(ByteBuffer header, int slot, int epochSeconds) {
        checkSlot(slot);
        writeBigEndianInt(header, TIMESTAMP_TABLE_OFFSET + slot * 4, epochSeconds);
    }

    // ---------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------

    /**
     * Sanity-check a decoded location entry against a file of {@code totalSectors} sectors. A
     * valid non-empty entry has {@code sectorOffset >= 2} (past the header), {@code sectorCount
     * >= 1}, and {@code sectorOffset + sectorCount <= totalSectors}. Arithmetic is 64-bit to
     * defeat the {@code sectorOffset=0xFFFFFF} overflow trap documented in
     * {@code WorldDiffTest.mcaCorruptSectorOffsetDoesNotCrash}.
     */
    public static boolean isValidLocation(int sectorOffset, int sectorCount, long totalSectors) {
        if (sectorOffset < RESERVED_SECTORS || sectorOffset > MAX_SECTOR_OFFSET) {
            return false;
        }
        if (sectorCount < 1 || sectorCount > MAX_SECTOR_COUNT) {
            return false;
        }
        if (totalSectors < 0) {
            return false;
        }
        long end = (long) sectorOffset + (long) sectorCount;
        return end <= totalSectors;
    }

    // ---------------------------------------------------------------------
    // Convenience: whole-header snapshot
    // ---------------------------------------------------------------------

    /** Immutable snapshot of one slot's parsed header data. */
    public record Slot(int slotIndex, int sectorOffset, int sectorCount, int timestampSeconds) {
        /** True when this slot's location entry is zero (chunk not present). */
        public boolean isEmpty() {
            return sectorOffset == 0 && sectorCount == 0;
        }
    }

    /**
     * Parse all 1024 slots into an array indexed by {@link #slotIndex(int, int)}. The returned
     * array is a snapshot — subsequent mutations to {@code header} do not affect it.
     */
    public static Slot[] readAllSlots(ByteBuffer header) {
        Slot[] out = new Slot[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            int loc = readBigEndianInt(header, i * 4);
            int ts = readBigEndianInt(header, TIMESTAMP_TABLE_OFFSET + i * 4);
            out[i] = new Slot(i, sectorOffset(loc), sectorCount(loc), ts);
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private static void checkSlot(int slot) {
        if (slot < 0 || slot >= SLOTS) {
            throw new IndexOutOfBoundsException("slot out of range [0," + SLOTS + "): " + slot);
        }
    }

    /**
     * Read a big-endian int without perturbing the buffer's byte-order setting. Callers may pass
     * a {@code ByteBuffer} in either byte order without corrupting our reads.
     */
    private static int readBigEndianInt(ByteBuffer buf, int index) {
        if (buf.order() == ByteOrder.BIG_ENDIAN) {
            return buf.getInt(index);
        }
        ByteOrder prev = buf.order();
        buf.order(ByteOrder.BIG_ENDIAN);
        try {
            return buf.getInt(index);
        } finally {
            buf.order(prev);
        }
    }

    private static void writeBigEndianInt(ByteBuffer buf, int index, int value) {
        if (buf.order() == ByteOrder.BIG_ENDIAN) {
            buf.putInt(index, value);
            return;
        }
        ByteOrder prev = buf.order();
        buf.order(ByteOrder.BIG_ENDIAN);
        try {
            buf.putInt(index, value);
        } finally {
            buf.order(prev);
        }
    }
}
