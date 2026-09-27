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
import static org.assertj.core.api.Assertions.within;

import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import org.junit.jupiter.api.Test;

/**
 * A region made by a split or a merge carries tick-time history over instead of
 * starting from an empty window (which read as a cheap region and made the
 * heatmap flicker to yellow for a few seconds after every split).
 */
class RegionMsptHistoryTest {

    private static final WorldRef WORLD = WorldRef.of("test:world");
    private static final long MS = 1_000_000L;

    @Test
    void seedScalesTheSourceHistoryByTheShare() {
        RegionMspt source = new RegionMspt(10);
        for (int i = 0; i < 10; i++) source.recordNanos(40 * MS);
        RegionMspt child = new RegionMspt(10);
        child.seedFrom(source, 0.25);
        assertThat(child.averageMillis()).isCloseTo(10.0, within(1e-9));
        // The source is not touched.
        assertThat(source.averageMillis()).isCloseTo(40.0, within(1e-9));
    }

    @Test
    void seedOfAnEmptySourceLeavesTheChildEmpty() {
        RegionMspt child = new RegionMspt(10);
        child.seedFrom(new RegionMspt(10), 0.5);
        assertThat(child.averageMillis()).isZero();
    }

    @Test
    void absorbAddsSamplesByRecency() {
        RegionMspt keep = new RegionMspt(4);
        for (long v : new long[] {1, 2, 3, 4, 5, 6}) keep.recordNanos(v * MS); // window: 3,4,5,6
        RegionMspt gone = new RegionMspt(4);
        for (long v : new long[] {10, 20}) gone.recordNanos(v * MS); // only two samples
        keep.absorb(gone);
        // Newest pairs with newest: 6+20, 5+10, 4, 3.
        assertThat(keep.samplesOldestFirst()).containsExactly(3 * MS, 4 * MS, 15 * MS, 26 * MS);
        // New samples keep rolling the window from there.
        keep.recordNanos(7 * MS);
        assertThat(keep.samplesOldestFirst()).containsExactly(4 * MS, 15 * MS, 26 * MS, 7 * MS);
    }

    @Test
    void splitChildStartsWithItsShareOfTheSourceHistory() throws Exception {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        try (TickRegionScheduler scheduler =
                new TickRegionScheduler(1, r -> {}, queue, 32, TickRegionScheduler.Mode.BARRIER)) {
            regionizer.addListener(scheduler);
            regionizer.addChunk(new ChunkPos(0, 0));
            regionizer.addChunk(new ChunkPos(1, 0));
            Region whole = regionizer.addChunk(new ChunkPos(2, 0));
            scheduler.register(whole);
            for (int i = 0; i < 100; i++) scheduler.mspt(whole).recordNanos(30 * MS);

            regionizer.removeChunk(new ChunkPos(1, 0)); // (0,0) and (2,0) no longer touch

            Region left = regionizer.regionAtChunk(0, 0);
            Region right = regionizer.regionAtChunk(2, 0);
            assertThat(left).isNotSameAs(right);
            Region child = left == whole ? right : left;
            // One section each: the child gets half of the source's recent cost.
            assertThat(scheduler.mspt(child).averageMillis()).isCloseTo(15.0, within(1e-6));
            assertThat(scheduler.mspt(whole).averageMillis()).isCloseTo(30.0, within(1e-6));
        }
    }

    @Test
    void mergeSurvivorCarriesBothRegionsHistory() throws Exception {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        try (TickRegionScheduler scheduler =
                new TickRegionScheduler(1, r -> {}, queue, 32, TickRegionScheduler.Mode.BARRIER)) {
            regionizer.addListener(scheduler);
            Region a = regionizer.addChunk(new ChunkPos(0, 0));
            Region b = regionizer.addChunk(new ChunkPos(2, 0));
            scheduler.register(a);
            scheduler.register(b);
            for (int i = 0; i < 100; i++) {
                scheduler.mspt(a).recordNanos(10 * MS);
                scheduler.mspt(b).recordNanos(5 * MS);
            }

            regionizer.addChunk(new ChunkPos(1, 0)); // bridges them

            Region merged = regionizer.regionAtChunk(0, 0);
            assertThat(regionizer.regionAtChunk(2, 0)).isSameAs(merged);
            assertThat(scheduler.mspt(merged).averageMillis()).isCloseTo(15.0, within(1e-6));
        }
    }
}
