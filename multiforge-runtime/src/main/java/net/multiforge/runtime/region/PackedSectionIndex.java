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
package net.multiforge.runtime.region;

import java.util.concurrent.atomic.AtomicReferenceArray;
import org.jetbrains.annotations.ApiStatus;

/**
 * Section → region map keyed by a packed {@code long}, read lock-free and
 * allocation-free by any thread ({@link ThreadedRegionizer#regionAtChunk(int,
 * int)} runs for every guarded block write, entity and scheduled tick).
 *
 * <p>Single writer: every mutation runs under the regionizer's write lock.
 * Readers take no lock. Open addressing with linear probing over an {@link
 * AtomicReferenceArray} of immutable entries; a removal leaves a tombstone
 * (an entry with a {@code null} region) so probe chains stay intact, and a
 * put overwrites a slot in one write, so a reader never sees a section that
 * is mapped both before and after a put as unmapped. When live entries plus
 * tombstones pass half the capacity, a fresh table holding only the live
 * entries is built and published with one volatile write; a reader still
 * holding the old table sees the map as it was just before.
 */
@ApiStatus.Internal
final class PackedSectionIndex {

    private static final int MIN_CAPACITY = 16;

    private record Entry(long key, Region region) {}

    private static final class Table {
        final AtomicReferenceArray<Entry> slots;
        final int mask;
        /** Occupied slots, live or tombstone. Writer only. */
        int used;
        /** Slots with a non-null region. Writer only. */
        int live;

        Table(int capacity) {
            this.slots = new AtomicReferenceArray<>(capacity);
            this.mask = capacity - 1;
        }
    }

    private volatile Table table = new Table(MIN_CAPACITY);

    static long pack(int sectionX, int sectionZ) {
        return ((long) sectionX << 32) | (sectionZ & 0xFFFFFFFFL);
    }

    private static int slot(long key, int mask) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32)) & mask;
    }

    /** The region mapped to {@code key}, or {@code null}. Any thread. */
    Region get(long key) {
        Table t = table;
        AtomicReferenceArray<Entry> slots = t.slots;
        int mask = t.mask;
        for (int i = slot(key, mask); ; i = (i + 1) & mask) {
            Entry e = slots.get(i);
            if (e == null) return null;
            if (e.key == key) return e.region;
        }
    }

    /** Map {@code key} to {@code region} (non-null). Writer only. */
    void put(long key, Region region) {
        Table t = table;
        AtomicReferenceArray<Entry> slots = t.slots;
        int mask = t.mask;
        for (int i = slot(key, mask); ; i = (i + 1) & mask) {
            Entry e = slots.get(i);
            if (e == null) {
                slots.set(i, new Entry(key, region));
                t.used++;
                t.live++;
                if (t.used * 2 > slots.length()) rehash(t);
                return;
            }
            if (e.key == key) {
                if (e.region == null) t.live++;
                slots.set(i, new Entry(key, region));
                return;
            }
        }
    }

    /** Unmap {@code key}. Writer only. */
    void remove(long key) {
        Table t = table;
        AtomicReferenceArray<Entry> slots = t.slots;
        int mask = t.mask;
        for (int i = slot(key, mask); ; i = (i + 1) & mask) {
            Entry e = slots.get(i);
            if (e == null) return;
            if (e.key == key) {
                if (e.region != null) {
                    slots.set(i, new Entry(key, null));
                    t.live--;
                }
                return;
            }
        }
    }

    /** Unmap everything. Writer only. */
    void clear() {
        table = new Table(MIN_CAPACITY);
    }

    /** Live entries (tests). */
    int size() {
        return table.live;
    }

    private void rehash(Table old) {
        int capacity = MIN_CAPACITY;
        while (capacity < old.live * 4) capacity <<= 1;
        Table fresh = new Table(capacity);
        AtomicReferenceArray<Entry> from = old.slots;
        for (int i = 0; i < from.length(); i++) {
            Entry e = from.get(i);
            if (e == null || e.region == null) continue;
            for (int j = slot(e.key, fresh.mask); ; j = (j + 1) & fresh.mask) {
                if (fresh.slots.get(j) == null) {
                    fresh.slots.set(j, e);
                    break;
                }
            }
            fresh.used++;
            fresh.live++;
        }
        table = fresh;
    }
}
