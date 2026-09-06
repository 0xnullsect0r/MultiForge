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

import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import org.junit.jupiter.api.Test;

class ThreadedRegionizerTest {

    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");

    @Test
    void addingAdjacentChunksCoalescesToOneRegion() {
        // sectionChunkShift=0 → each chunk is its own section so we can
        // control adjacency chunk-by-chunk.
        ThreadedRegionizer r = new ThreadedRegionizer(WORLD, 0);
        Region a = r.addChunk(new ChunkPos(0, 0));
        Region b = r.addChunk(new ChunkPos(1, 0));
        assertThat(a).isSameAs(b);
        assertThat(r.regions()).hasSize(1);
        assertThat(a.sectionCount()).isEqualTo(2);
    }

    @Test
    void nonAdjacentChunksProduceSeparateRegions() {
        ThreadedRegionizer r = new ThreadedRegionizer(WORLD, 0);
        Region a = r.addChunk(new ChunkPos(0, 0));
        Region b = r.addChunk(new ChunkPos(100, 100));
        assertThat(a).isNotSameAs(b);
        assertThat(r.regions()).hasSize(2);
    }

    @Test
    void bridgingSectionMergesAdjacentRegions() {
        ThreadedRegionizer r = new ThreadedRegionizer(WORLD, 0);
        Region a = r.addChunk(new ChunkPos(0, 0));
        Region b = r.addChunk(new ChunkPos(2, 0));
        assertThat(a).isNotSameAs(b);
        assertThat(r.regions()).hasSize(2);

        // The bridging chunk sits between a and b; both merge into one.
        Region c = r.addChunk(new ChunkPos(1, 0));
        assertThat(r.regions()).hasSize(1);
        assertThat(c.sectionCount()).isEqualTo(3);
    }

    @Test
    void removingBridgeSplitsRegion() {
        ThreadedRegionizer r = new ThreadedRegionizer(WORLD, 0);
        r.addChunk(new ChunkPos(0, 0));
        r.addChunk(new ChunkPos(1, 0));
        r.addChunk(new ChunkPos(2, 0));
        assertThat(r.regions()).hasSize(1);

        r.removeChunk(new ChunkPos(1, 0));
        assertThat(r.regions()).hasSize(2);
    }

    @Test
    void removingLastChunkDropsRegion() {
        ThreadedRegionizer r = new ThreadedRegionizer(WORLD, 0);
        Region region = r.addChunk(new ChunkPos(5, 5));
        assertThat(r.regions()).hasSize(1);
        r.removeChunk(new ChunkPos(5, 5));
        assertThat(r.regions()).isEmpty();
        assertThat(region.state()).isEqualTo(RegionState.DEAD);
    }

    @Test
    void sectionSizeCoalescesChunksInsideOneSection() {
        // sectionChunkShift=4 → 16-chunk sides. Chunks (0,0) and (15,15) fall in the
        // same section, so they share the same region without any merge work.
        ThreadedRegionizer r = new ThreadedRegionizer(WORLD, 4);
        Region a = r.addChunk(new ChunkPos(0, 0));
        Region b = r.addChunk(new ChunkPos(15, 15));
        assertThat(a).isSameAs(b);
        assertThat(a.sectionCount()).isEqualTo(1);
    }
}
