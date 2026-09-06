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
package net.multiforge.runtime.region;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link Region#withChunkSource}/{@link
 * Region#ownedChunkSnapshot()}/{@link Region#ownedChunkCount()} — the
 * B3.1 accessor (docs/design/m13-b3-region-tick.md §4.3) the three B3
 * phase bodies use to resolve "which chunks does this region own,
 * right now?". Exercised here purely at the {@code region} package
 * level, via a stub {@link RegionChunkSource} — the real production
 * source (backed by {@code ChunkHolderManager.holdersOwnedBy}) is
 * covered by {@code ChunkHolderManagerOwnershipTest} in the {@code
 * chunk} package, and the {@code scheduler} package's own wiring is
 * exercised indirectly by every existing {@code
 * MultiThreadedSchedulerHost} chunk test.
 */
class RegionOwnedChunkSourceTest {

    private static final WorldRef WORLD = WorldRef.of("test:world");

    @Test
    void unwiredRegionReturnsEmptySnapshotNotNull() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region region = regionizer.addChunk(new ChunkPos(0, 0));

        assertThat(region.ownedChunkSnapshot()).isNotNull().isEmpty();
        assertThat(region.ownedChunkCount()).isZero();
    }

    @Test
    void wiredRegionDelegatesToItsChunkSource() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region region = regionizer.addChunk(new ChunkPos(0, 0));

        List<ChunkPos> owned = List.of(new ChunkPos(1, 1), new ChunkPos(2, 2));
        region.withChunkSource(rid -> rid.equals(region.id()) ? owned : List.of());

        assertThat(region.ownedChunkSnapshot()).containsExactlyInAnyOrderElementsOf(owned);
        assertThat(region.ownedChunkCount()).isEqualTo(2);
    }

    @Test
    void chunkSourceIsQueriedWithThisRegionsId() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region region = regionizer.addChunk(new ChunkPos(0, 0));

        region.withChunkSource(rid -> List.of(new ChunkPos(rid.equals(region.id()) ? 1 : -1, 0)));

        assertThat(region.ownedChunkSnapshot()).containsExactly(new ChunkPos(1, 0));
    }
}
