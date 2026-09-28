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

import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.RegionId;
import org.junit.jupiter.api.Test;

class ChunkHolderManagerTest {

    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");

    private static TickingBlockEntityRef ticker(int x, int z) {
        BlockPos pos = new BlockPos(x, 64, z);
        return new TickingBlockEntityRef() {
            @Override
            public BlockPos pos() {
                return pos;
            }

            @Override
            public boolean isRemoved() {
                return false;
            }

            @Override
            public boolean shouldTick() {
                return true;
            }

            @Override
            public void tick() {}
        };
    }

    @Test
    void createHolderRecordsOwnerAndReownsExistingHolder() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId a = RegionId.next();
        RegionId b = RegionId.next();
        ChunkPos pos = new ChunkPos(3, 4);
        NewChunkHolder h = m.createHolder(pos, a);
        assertThat(h.owningRegion()).isEqualTo(a);
        assertThat(m.holdersOwnedBy(a)).containsExactly(h);

        assertThat(m.createHolder(pos, b)).isSameAs(h);
        assertThat(h.owningRegion()).isEqualTo(b);
        assertThat(m.holdersOwnedBy(a)).isEmpty();
        assertThat(m.holdersOwnedBy(b)).containsExactly(h);
    }

    @Test
    void ownedSnapshotsAreReusedUntilTheRegionsChunksChange() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId a = RegionId.next();
        RegionId b = RegionId.next();
        NewChunkHolder h0 = m.createHolder(new ChunkPos(0, 0), a);
        NewChunkHolder h1 = m.createHolder(new ChunkPos(1, 0), a);
        java.util.List<NewChunkHolder> first = m.holdersOwnedBy(a);
        assertThat(first).containsExactlyInAnyOrder(h0, h1);
        assertThat(m.holdersOwnedBy(a)).isSameAs(first);
        java.util.List<ChunkPos> positions = m.ownedPositions(a);
        assertThat(positions).containsExactlyInAnyOrder(new ChunkPos(0, 0), new ChunkPos(1, 0));
        assertThat(m.ownedPositions(a)).isSameAs(positions);
        // Changes to another region leave a's snapshot alone.
        m.createHolder(new ChunkPos(9, 9), b);
        assertThat(m.holdersOwnedBy(a)).isSameAs(first);
        // Re-owning, adding, dropping, merging and splitting each invalidate it.
        m.createHolder(new ChunkPos(1, 0), b);
        assertThat(m.holdersOwnedBy(a)).containsExactly(h0);
        assertThat(m.holdersOwnedBy(b)).hasSize(2);
        NewChunkHolder h2 = m.createHolder(new ChunkPos(2, 0), a);
        assertThat(m.holdersOwnedBy(a)).containsExactlyInAnyOrder(h0, h2);
        m.dropHolder(new ChunkPos(2, 0));
        assertThat(m.holdersOwnedBy(a)).containsExactly(h0);
        assertThat(m.ownedPositions(a)).containsExactly(new ChunkPos(0, 0));
        m.onRegionMerged(a, b);
        assertThat(m.holdersOwnedBy(a)).hasSize(3);
        assertThat(m.holdersOwnedBy(b)).isEmpty();
        m.onRegionSplit(a, b, pos -> pos.x() >= 1);
        assertThat(m.ownedPositions(a)).containsExactly(new ChunkPos(0, 0));
        assertThat(m.ownedPositions(b)).containsExactlyInAnyOrder(new ChunkPos(1, 0), new ChunkPos(9, 9));
    }

    @Test
    void dropHolderForgetsTheChunk() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId r = RegionId.next();
        ChunkPos pos = new ChunkPos(0, 0);
        m.createHolder(pos, r);
        assertThat(m.dropHolder(pos)).isNotNull();
        assertThat(m.holderAt(pos)).isNull();
        assertThat(m.holdersOwnedBy(r)).isEmpty();
        assertThat(m.holderCount()).isZero();
    }

    @Test
    void mergeMovesChunksAndBlockEntityTickers() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId target = RegionId.next();
        RegionId source = RegionId.next();
        m.createHolder(new ChunkPos(0, 0), target);
        m.createHolder(new ChunkPos(1, 0), source);
        m.regionData(source).addBlockEntityTicker(ticker(20, 5));

        m.onRegionMerged(target, source);

        assertThat(m.holdersOwnedBy(target))
                .extracting(NewChunkHolder::position)
                .containsExactlyInAnyOrder(new ChunkPos(0, 0), new ChunkPos(1, 0));
        assertThat(m.holdersOwnedBy(source)).isEmpty();
        assertThat(m.regionData(target).blockEntityTickerCount()).isEqualTo(1);
    }

    @Test
    void splitMovesMatchingChunksAndTheirTickers() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId source = RegionId.next();
        RegionId child = RegionId.next();
        ChunkPos stay = new ChunkPos(0, 0);
        ChunkPos leave = new ChunkPos(10, 0);
        m.createHolder(stay, source);
        m.createHolder(leave, source);
        m.regionData(source).addBlockEntityTicker(ticker(5, 5)); // chunk (0,0)
        m.regionData(source).addBlockEntityTicker(ticker(165, 5)); // chunk (10,0)

        m.onRegionSplit(source, child, leave::equals);

        assertThat(m.holderAt(stay).owningRegion()).isEqualTo(source);
        assertThat(m.holderAt(leave).owningRegion()).isEqualTo(child);
        assertThat(m.regionData(source).snapshotBlockEntityTickers())
                .extracting(t -> t.pos().toChunkPos())
                .containsExactly(stay);
        assertThat(m.regionData(child).snapshotBlockEntityTickers())
                .extracting(t -> t.pos().toChunkPos())
                .containsExactly(leave);
    }
}
