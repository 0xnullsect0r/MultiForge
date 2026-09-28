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

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import org.junit.jupiter.api.Test;

/**
 * WS7 region lookups: the packed section index behind {@link
 * ThreadedRegionizer#regionAtChunk(int, int)}, the versioned {@link
 * ThreadedRegionizer#regions()} snapshot and longest-first submission.
 */
class RegionLookupCachesTest {

    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");

    @Test
    void packedIndexMatchesAHashMapUnderRandomPutsAndRemoves() {
        PackedSectionIndex index = new PackedSectionIndex();
        Map<Long, Region> model = new HashMap<>();
        Random random = new Random(42);
        Region[] regions = new Region[8];
        for (int i = 0; i < regions.length; i++) regions[i] = new Region(RegionId.next(), 4);
        for (int step = 0; step < 50_000; step++) {
            int x = random.nextInt(64) - 32;
            int z = random.nextInt(64) - 32;
            long key = PackedSectionIndex.pack(x, z);
            if (random.nextInt(3) == 0) {
                index.remove(key);
                model.remove(key);
            } else {
                Region r = regions[random.nextInt(regions.length)];
                index.put(key, r);
                model.put(key, r);
            }
            if (step % 997 == 0) {
                for (int qx = -33; qx <= 32; qx++) {
                    for (int qz = -33; qz <= 32; qz++) {
                        long k = PackedSectionIndex.pack(qx, qz);
                        assertThat(index.get(k)).isSameAs(model.get(k));
                    }
                }
            }
        }
        assertThat(index.size()).isEqualTo(model.size());
        index.clear();
        assertThat(index.size()).isZero();
        assertThat(index.get(PackedSectionIndex.pack(0, 0))).isNull();
    }

    @Test
    void packKeepsNegativeCoordinatesApart() {
        assertThat(PackedSectionIndex.pack(-1, 0)).isNotEqualTo(PackedSectionIndex.pack(0, -1));
        assertThat(PackedSectionIndex.pack(-1, -1)).isNotEqualTo(PackedSectionIndex.pack(0, 0));
    }

    @Test
    void regionAtChunkFollowsMergeSplitAndClear() {
        ThreadedRegionizer r = new ThreadedRegionizer(WORLD, 2);
        Region a = r.addChunk(new ChunkPos(0, 0));
        Region b = r.addChunk(new ChunkPos(16, 0));
        assertThat(r.regionAtChunk(1, 1)).isSameAs(a);
        assertThat(r.regionAtChunk(17, 3)).isSameAs(b);
        assertThat(r.regionAtChunk(-1, 0)).isNull();
        // Bridge the gap: sections 1..3 join a and b into one region.
        for (int x = 4; x < 16; x += 4) r.addChunk(new ChunkPos(x, 0));
        Region merged = r.regionAtChunk(0, 0);
        assertThat(r.regionAtChunk(16, 0)).isSameAs(merged);
        // Cut it again: the far side splits off.
        r.removeChunk(new ChunkPos(8, 0));
        assertThat(r.regionAtChunk(16, 0)).isNotSameAs(r.regionAtChunk(0, 0));
        assertThat(r.regionAtChunk(8, 0)).isNull();
        r.clear();
        assertThat(r.regionAtChunk(0, 0)).isNull();
        assertThat(r.regionAtChunk(16, 0)).isNull();
    }

    @Test
    void regionAtChunkDoesNotAllocate() {
        ThreadedRegionizer r = new ThreadedRegionizer(WORLD, 4);
        for (int x = -40; x < 40; x += 3) {
            for (int z = -40; z < 40; z += 3) r.addChunk(new ChunkPos(x * 7, z * 7));
        }
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long sink = 0;
        // Warm up so the JIT has compiled the lookup.
        for (int i = 0; i < 200_000; i++) sink += lookup(r, i);
        long before = mx.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 1_000_000; i++) sink += lookup(r, i);
        long allocated = mx.getCurrentThreadAllocatedBytes() - before;
        assertThat(sink).isNotZero();
        // A SectionPos per lookup would be ~24 MB; allow a little measurement noise.
        assertThat(allocated).isLessThan(64 * 1024);
    }

    private static long lookup(ThreadedRegionizer r, int i) {
        Region region = r.regionAtChunk((i % 560) - 280, ((i / 560) % 560) - 280);
        return region == null ? 1 : region.id().value();
    }

    @Test
    void regionsSnapshotIsReusedUntilTheTopologyChanges() {
        ThreadedRegionizer r = new ThreadedRegionizer(WORLD, 2);
        r.addChunk(new ChunkPos(0, 0));
        r.addChunk(new ChunkPos(40, 0));
        Collection<Region> first = r.regions();
        assertThat(first).hasSize(2);
        assertThat(r.regions()).isSameAs(first);
        // Another chunk of an occupied section: no topology change.
        r.addChunk(new ChunkPos(1, 0));
        assertThat(r.regions()).isSameAs(first);
        r.addChunk(new ChunkPos(80, 0));
        Collection<Region> second = r.regions();
        assertThat(second).isNotSameAs(first).hasSize(3);
        r.removeChunk(new ChunkPos(80, 0));
        assertThat(r.regions()).hasSize(2).isNotSameAs(second);
        r.clear();
        assertThat(r.regions()).isEmpty();
    }

    @Test
    void longestFirstIsAStableDescendingSort() {
        for (int n : new int[] {0, 1, 5, 64, 65, 300}) {
            Random random = new Random(n);
            int[] idx = new int[n];
            long[] cost = new long[n];
            for (int i = 0; i < n; i++) {
                idx[i] = i;
                cost[i] = random.nextInt(10);
            }
            long[] original = cost.clone();
            TickRegionScheduler.sortDescending(idx, cost);
            List<Integer> order = new ArrayList<>();
            for (int i : idx) order.add(i);
            for (int i = 1; i < n; i++) {
                assertThat(original[idx[i - 1]]).isGreaterThanOrEqualTo(original[idx[i]]);
                if (original[idx[i - 1]] == original[idx[i]])
                    assertThat(idx[i - 1]).isLessThan(idx[i]);
                assertThat(cost[i]).isEqualTo(original[idx[i]]);
            }
            assertThat(order).containsExactlyInAnyOrderElementsOf(range(n));
        }
    }

    private static List<Integer> range(int n) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(i);
        return out;
    }

    @Test
    void workerChunkCacheHitsUntilTheGenerationMoves() {
        WorkerChunkCache cache = new WorkerChunkCache();
        Object level = new Object();
        Object other = new Object();
        Object chunk = new Object();
        assertThat(cache.get(level, 7L)).isNull();
        cache.put(level, 7L, chunk);
        assertThat(cache.get(level, 7L)).isSameAs(chunk);
        assertThat(cache.get(other, 7L)).isNull();
        assertThat(cache.get(level, 8L)).isNull();
        WorkerChunkCache.invalidateAll();
        assertThat(cache.get(level, 7L)).isNull();
        // Four entries, most recent first; the fifth evicts the oldest.
        for (long k = 0; k < 5; k++) {
            assertThat(cache.get(level, k)).isNull();
            cache.put(level, k, "chunk" + k);
        }
        assertThat(cache.get(level, 0L)).isNull();
        for (long k = 1; k < 5; k++) assertThat(cache.get(level, k)).isEqualTo("chunk" + k);
    }

    @Test
    void workerChunkCacheIsOnlyOnWorkersAndHonoursTheKillSwitch() throws Exception {
        assertThat(WorkerChunkCache.current()).isNull();
        WorkerChunkCache[] seen = new WorkerChunkCache[2];
        RegionWorkerThread worker = new RegionWorkerThread(
                () -> {
                    seen[0] = WorkerChunkCache.current();
                    WorkerChunkCache.setEnabled(false);
                    seen[1] = WorkerChunkCache.current();
                    WorkerChunkCache.setEnabled(true);
                },
                "test-worker");
        worker.start();
        worker.join();
        assertThat(seen[0]).isSameAs(worker.chunkCache());
        assertThat(seen[1]).isNull();
    }
}
