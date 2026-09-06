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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class RegionFileHeaderTest {

    @Test
    void slotIndexIsRowMajor() {
        // (0,0) → 0, (1,0) → 1, (0,1) → 32, (31,31) → 1023.
        assertThat(RegionFileHeader.slotIndex(0, 0)).isZero();
        assertThat(RegionFileHeader.slotIndex(1, 0)).isEqualTo(1);
        assertThat(RegionFileHeader.slotIndex(31, 0)).isEqualTo(31);
        assertThat(RegionFileHeader.slotIndex(0, 1)).isEqualTo(32);
        assertThat(RegionFileHeader.slotIndex(31, 31)).isEqualTo(1023);

        // Full 32×32 sweep must be a bijection onto [0, 1024).
        boolean[] seen = new boolean[RegionFileHeader.SLOTS];
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                int idx = RegionFileHeader.slotIndex(x, z);
                assertThat(seen[idx]).as("duplicate slot at (%d,%d)", x, z).isFalse();
                seen[idx] = true;
            }
        }
        for (int i = 0; i < RegionFileHeader.SLOTS; i++) {
            assertThat(seen[i]).as("missing slot %d", i).isTrue();
        }

        // Out-of-range coords fail loudly rather than silently wrapping.
        assertThatThrownBy(() -> RegionFileHeader.slotIndex(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RegionFileHeader.slotIndex(32, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RegionFileHeader.slotIndex(0, 32)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void localXZMasksAgainst31() {
        // Positive coords: identity within [0,31].
        for (int i = 0; i < 32; i++) {
            assertThat(RegionFileHeader.localX(i)).isEqualTo(i);
            assertThat(RegionFileHeader.localZ(i)).isEqualTo(i);
        }
        // Positive coords >= 32 wrap by mask.
        assertThat(RegionFileHeader.localX(32)).isZero();
        assertThat(RegionFileHeader.localX(63)).isEqualTo(31);
        assertThat(RegionFileHeader.localX(1024)).isZero();
        // Negative coords wrap into [0,31] via two's-complement AND.
        assertThat(RegionFileHeader.localX(-1)).isEqualTo(31);
        assertThat(RegionFileHeader.localX(-32)).isZero();
        assertThat(RegionFileHeader.localZ(-33)).isEqualTo(31);
        // Spot-check world chunk (100, -50) → (100 & 31, -50 & 31) = (4, 14).
        assertThat(RegionFileHeader.localX(100)).isEqualTo(4);
        assertThat(RegionFileHeader.localZ(-50)).isEqualTo(14);
    }

    @Test
    void packAndReadLocationEntryRoundTrips() {
        // Small values.
        int packed = RegionFileHeader.packLocationEntry(2, 1);
        assertThat(RegionFileHeader.sectorOffset(packed)).isEqualTo(2);
        assertThat(RegionFileHeader.sectorCount(packed)).isEqualTo(1);
        assertThat(packed).isEqualTo((2 << 8) | 1);

        // Max 24-bit offset + max 8-bit count.
        int maxPacked = RegionFileHeader.packLocationEntry(0xFFFFFF, 0xFF);
        assertThat(RegionFileHeader.sectorOffset(maxPacked)).isEqualTo(0xFFFFFF);
        assertThat(RegionFileHeader.sectorCount(maxPacked)).isEqualTo(0xFF);
        // Bit-exact: 0xFFFFFFFF as an int is -1.
        assertThat(maxPacked).isEqualTo(-1);

        // Buffer round-trip: write, then read back the same packed int.
        ByteBuffer buf = ByteBuffer.allocate(RegionFileHeader.HEADER_BYTES);
        RegionFileHeader.writeLocationEntry(buf, 42, packed);
        assertThat(RegionFileHeader.locationEntry(buf, 42)).isEqualTo(packed);

        // Byte-level layout must be big-endian (sectorOffset high 3 bytes, count low byte).
        int expectedOffset = 0x123456;
        int expectedCount = 0x78;
        int entry = RegionFileHeader.packLocationEntry(expectedOffset, expectedCount);
        RegionFileHeader.writeLocationEntry(buf, 0, entry);
        assertThat(buf.get(0)).isEqualTo((byte) 0x12);
        assertThat(buf.get(1)).isEqualTo((byte) 0x34);
        assertThat(buf.get(2)).isEqualTo((byte) 0x56);
        assertThat(buf.get(3)).isEqualTo((byte) 0x78);

        // pack() rejects out-of-range inputs (guards against silent overflow into the count byte).
        assertThatThrownBy(() -> RegionFileHeader.packLocationEntry(0x1000000, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RegionFileHeader.packLocationEntry(-1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RegionFileHeader.packLocationEntry(2, 0x100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RegionFileHeader.packLocationEntry(2, -1))
                .isInstanceOf(IllegalArgumentException.class);

        // Robust against buffers a caller left in little-endian order.
        ByteBuffer le = ByteBuffer.allocate(RegionFileHeader.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        RegionFileHeader.writeLocationEntry(le, 7, entry);
        assertThat(RegionFileHeader.locationEntry(le, 7)).isEqualTo(entry);
        assertThat(le.order()).isEqualTo(ByteOrder.LITTLE_ENDIAN); // order restored
        assertThat(le.get(7 * 4)).isEqualTo((byte) 0x12);
    }

    @Test
    void packAndReadTimestampRoundTrips() {
        ByteBuffer buf = ByteBuffer.allocate(RegionFileHeader.HEADER_BYTES);
        int ts = 0x66_DE_AD_BE; // arbitrary epoch-seconds sentinel
        RegionFileHeader.writeTimestampEntry(buf, 100, ts);
        assertThat(RegionFileHeader.timestampEntry(buf, 100)).isEqualTo(ts);

        // Timestamps live in the second 4 KiB, not the first.
        int base = RegionFileHeader.TIMESTAMP_TABLE_OFFSET + 100 * 4;
        assertThat(buf.get(base)).isEqualTo((byte) 0x66);
        assertThat(buf.get(base + 1)).isEqualTo((byte) 0xDE);
        assertThat(buf.get(base + 2)).isEqualTo((byte) 0xAD);
        assertThat(buf.get(base + 3)).isEqualTo((byte) 0xBE);

        // Writing a timestamp must NOT clobber the corresponding location entry.
        int loc = RegionFileHeader.packLocationEntry(5, 2);
        RegionFileHeader.writeLocationEntry(buf, 100, loc);
        RegionFileHeader.writeTimestampEntry(buf, 100, ts);
        assertThat(RegionFileHeader.locationEntry(buf, 100)).isEqualTo(loc);
        assertThat(RegionFileHeader.timestampEntry(buf, 100)).isEqualTo(ts);

        // Bounds checks.
        assertThatThrownBy(() -> RegionFileHeader.writeTimestampEntry(buf, -1, 0))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> RegionFileHeader.writeTimestampEntry(buf, RegionFileHeader.SLOTS, 0))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> RegionFileHeader.timestampEntry(buf, RegionFileHeader.SLOTS))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void isValidLocationRejectsOutOfBounds() {
        // Normal shape: sector 2, 1 sector long, file has 3 sectors → valid.
        assertThat(RegionFileHeader.isValidLocation(2, 1, 3)).isTrue();
        // Exactly abutting the file end is valid.
        assertThat(RegionFileHeader.isValidLocation(2, 4, 6)).isTrue();
        // One past end is not.
        assertThat(RegionFileHeader.isValidLocation(2, 5, 6)).isFalse();

        // sectorOffset must be past the 2-sector header.
        assertThat(RegionFileHeader.isValidLocation(0, 1, 100)).isFalse();
        assertThat(RegionFileHeader.isValidLocation(1, 1, 100)).isFalse();
        assertThat(RegionFileHeader.isValidLocation(2, 1, 100)).isTrue();

        // sectorCount must be >= 1 (empty-slot sentinel is zero packed, not 0-count with offset).
        assertThat(RegionFileHeader.isValidLocation(2, 0, 100)).isFalse();

        // The corruption trap from WorldDiffTest.mcaCorruptSectorOffsetDoesNotCrash:
        // location entry `FF FF FF 01` decodes to sectorOffset=0xFFFFFF, sectorCount=1. The
        // signed multiplication `0xFFFFFF * 4096` wraps to -4096; long arithmetic here must
        // still reject cleanly against any real-world file size.
        int packed = 0xFF_FF_FF_01;
        int off = RegionFileHeader.sectorOffset(packed);
        int cnt = RegionFileHeader.sectorCount(packed);
        assertThat(off).isEqualTo(0xFFFFFF);
        assertThat(cnt).isEqualTo(1);
        // Empty 8192-byte file = 2 sectors: reject.
        assertThat(RegionFileHeader.isValidLocation(off, cnt, 2)).isFalse();
        // Even a preposterously large file below 0xFFFFFF sectors rejects.
        assertThat(RegionFileHeader.isValidLocation(off, cnt, (long) 0xFFFFFE)).isFalse();
        // A file that actually holds the tail sector accepts (proves no signed-int wrap).
        assertThat(RegionFileHeader.isValidLocation(off, cnt, (long) 0x1_000_000))
                .isTrue();

        // The MAX_SECTOR_OFFSET + MAX_SECTOR_COUNT ceiling: total = 0xFFFFFF + 0xFF = 16_777_470.
        // As a long that's fine; as a signed int the addition still fits (both operands < 2^24),
        // so the validator's `end` must be a long only where sectorCount pushes past 2^31 — we
        // add the extreme case here to nail the contract.
        assertThat(RegionFileHeader.isValidLocation(
                        RegionFileHeader.MAX_SECTOR_OFFSET, RegionFileHeader.MAX_SECTOR_COUNT, 0x1_000_100L))
                .isTrue();
        assertThat(RegionFileHeader.isValidLocation(
                        RegionFileHeader.MAX_SECTOR_OFFSET, RegionFileHeader.MAX_SECTOR_COUNT, 0x1_000_000L))
                .isFalse();

        // A negative totalSectors (caller bug) rejects rather than silently accepting.
        assertThat(RegionFileHeader.isValidLocation(2, 1, -1)).isFalse();
    }

    @Test
    void emptyHeaderReadsAllZeros() {
        ByteBuffer empty = ByteBuffer.allocate(RegionFileHeader.HEADER_BYTES);
        for (int i = 0; i < RegionFileHeader.SLOTS; i++) {
            assertThat(RegionFileHeader.locationEntry(empty, i))
                    .as("loc slot %d", i)
                    .isZero();
            assertThat(RegionFileHeader.timestampEntry(empty, i))
                    .as("ts slot %d", i)
                    .isZero();
            assertThat(RegionFileHeader.sectorOffset(RegionFileHeader.locationEntry(empty, i)))
                    .isZero();
            assertThat(RegionFileHeader.sectorCount(RegionFileHeader.locationEntry(empty, i)))
                    .isZero();
        }

        RegionFileHeader.Slot[] slots = RegionFileHeader.readAllSlots(empty);
        assertThat(slots).hasSize(RegionFileHeader.SLOTS);
        for (RegionFileHeader.Slot s : slots) {
            assertThat(s.isEmpty()).isTrue();
            assertThat(s.sectorOffset()).isZero();
            assertThat(s.sectorCount()).isZero();
            assertThat(s.timestampSeconds()).isZero();
        }
        // slotIndex field aligns with array position.
        assertThat(slots[0].slotIndex()).isZero();
        assertThat(slots[1023].slotIndex()).isEqualTo(1023);

        // Sanity constants.
        assertThat(RegionFileHeader.HEADER_BYTES)
                .isEqualTo(RegionFileHeader.LOCATION_TABLE_BYTES + RegionFileHeader.TIMESTAMP_TABLE_BYTES);
        assertThat(RegionFileHeader.SECTOR_BYTES).isEqualTo(4096);
        assertThat(RegionFileHeader.SLOTS).isEqualTo(1024);
    }

    @Test
    void readAllSlotsCapturesMixedContent() {
        ByteBuffer buf = ByteBuffer.allocate(RegionFileHeader.HEADER_BYTES);
        // Populate three slots and verify readAllSlots snapshots them accurately.
        int locA = RegionFileHeader.packLocationEntry(2, 1);
        int locB = RegionFileHeader.packLocationEntry(0x123, 4);
        int locC = RegionFileHeader.packLocationEntry(0xFFFFFF, 0xFF);
        RegionFileHeader.writeLocationEntry(buf, 0, locA);
        RegionFileHeader.writeTimestampEntry(buf, 0, 111);
        RegionFileHeader.writeLocationEntry(buf, 100, locB);
        RegionFileHeader.writeTimestampEntry(buf, 100, 222);
        RegionFileHeader.writeLocationEntry(buf, 1023, locC);
        RegionFileHeader.writeTimestampEntry(buf, 1023, 333);

        RegionFileHeader.Slot[] slots = RegionFileHeader.readAllSlots(buf);
        assertThat(slots[0].sectorOffset()).isEqualTo(2);
        assertThat(slots[0].sectorCount()).isEqualTo(1);
        assertThat(slots[0].timestampSeconds()).isEqualTo(111);
        assertThat(slots[0].isEmpty()).isFalse();
        assertThat(slots[100].sectorOffset()).isEqualTo(0x123);
        assertThat(slots[100].sectorCount()).isEqualTo(4);
        assertThat(slots[100].timestampSeconds()).isEqualTo(222);
        assertThat(slots[1023].sectorOffset()).isEqualTo(0xFFFFFF);
        assertThat(slots[1023].sectorCount()).isEqualTo(0xFF);
        assertThat(slots[1023].timestampSeconds()).isEqualTo(333);
        assertThat(slots[500].isEmpty()).isTrue();
    }
}
