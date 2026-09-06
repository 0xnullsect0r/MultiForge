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
package net.multiforge.runtime.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.RegionId;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link ChunkHolderManager#holdersOwnedBy(RegionId)} —
 * the B3.1 per-region ownership accessor (docs/design/
 * m13-b3-region-tick.md §4.1) that the BLOCK_FLUID_TICKS / ENTITY_AI /
 * BLOCK_ENTITIES phase bodies (B3.2/3/4) resolve "which chunks does
 * this region own?" through, via {@code Region.ownedChunkSnapshot()}.
 */
class ChunkHolderManagerOwnershipTest {

    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");

    @Test
    void holdersOwnedByReturnsExactlyThisRegionsChunks() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId[] regions = {RegionId.next(), RegionId.next(), RegionId.next(), RegionId.next()};
        Map<RegionId, Set<ChunkPos>> expected = new HashMap<>();
        for (RegionId r : regions) expected.put(r, new HashSet<>());

        // 100 chunks total, 25 per region, positions unique across all four.
        int idx = 0;
        for (RegionId r : regions) {
            for (int i = 0; i < 25; i++) {
                ChunkPos pos = new ChunkPos(idx, 0);
                m.createHolder(pos, r);
                expected.get(r).add(pos);
                idx++;
            }
        }
        assertThat(m.holderCount()).isEqualTo(100);

        Set<ChunkPos> seenAcrossAllRegions = new HashSet<>();
        for (RegionId r : regions) {
            List<NewChunkHolder> owned = m.holdersOwnedBy(r);
            assertThat(owned).hasSize(25);

            Set<ChunkPos> ownedPositions =
                    owned.stream().map(NewChunkHolder::position).collect(Collectors.toSet());
            assertThat(ownedPositions).isEqualTo(expected.get(r));

            // No chunk appears under two regions' results.
            for (ChunkPos p : ownedPositions) {
                assertThat(seenAcrossAllRegions.add(p))
                        .as("chunk %s claimed by more than one region", p)
                        .isTrue();
            }
        }
        // No chunk missing from all four.
        assertThat(seenAcrossAllRegions).hasSize(100);
    }

    @Test
    void splitPreservesTotalHolderCountAcrossBothRegions() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId parent = RegionId.next();
        RegionId child = RegionId.next();

        Set<ChunkPos> leaveSet = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            ChunkPos pos = new ChunkPos(i, 0);
            m.createHolder(pos, parent);
            if (i % 2 == 0) leaveSet.add(pos);
        }
        int preSplitTotal = m.holdersOwnedBy(parent).size();
        assertThat(preSplitTotal).isEqualTo(30);

        m.onRegionSplit(parent, child, leaveSet::contains);

        List<NewChunkHolder> parentOwned = m.holdersOwnedBy(parent);
        List<NewChunkHolder> childOwned = m.holdersOwnedBy(child);
        assertThat(parentOwned.size() + childOwned.size()).isEqualTo(preSplitTotal);
        assertThat(childOwned).allSatisfy(h -> assertThat(leaveSet).contains(h.position()));
        assertThat(parentOwned).allSatisfy(h -> assertThat(leaveSet).doesNotContain(h.position()));
    }

    @Test
    void mergePreservesTotalHolderCountUnderTargetRegion() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId target = RegionId.next();
        RegionId source = RegionId.next();
        for (int i = 0; i < 10; i++) m.createHolder(new ChunkPos(i, 0), target);
        for (int i = 0; i < 15; i++) m.createHolder(new ChunkPos(i, 100), source);

        m.onRegionMerged(target, source);

        assertThat(m.holdersOwnedBy(target)).hasSize(25);
        assertThat(m.holdersOwnedBy(source)).isEmpty();
    }

    @Test
    void emptyRegionReturnsEmptyListNotNull() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId empty = RegionId.next();

        List<NewChunkHolder> owned = m.holdersOwnedBy(empty);
        assertThat(owned).isNotNull().isEmpty();
    }

    @Test
    void holdersOwnedBySnapshotIsUnmodifiable() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId r = RegionId.next();
        m.createHolder(new ChunkPos(0, 0), r);

        List<NewChunkHolder> owned = m.holdersOwnedBy(r);
        assertThatThrownBy(() -> owned.add(null)).isInstanceOf(UnsupportedOperationException.class);
    }
}
