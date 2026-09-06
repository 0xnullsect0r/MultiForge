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
package net.multiforge.runtime.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.RegionId;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link HolderManagerRegionData}'s {@link
 * TickingBlockEntityRef} slice — the B3.1 per-region equivalent of
 * Vanilla's level-wide {@code Level.blockEntityTickers} (docs/design/
 * m13-b3-region-tick.md §4.2). The load-bearing property under test is
 * the one §4.2 calls out explicitly: a ticker's block position resolves
 * to the region holding that ticker, both in steady state and across a
 * split or merge.
 */
class HolderManagerRegionDataBlockEntityTickerTest {

    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");

    @Test
    void addRemoveSnapshotRoundTrip() {
        HolderManagerRegionData data = new HolderManagerRegionData();
        StubTicker a = new StubTicker(new BlockPos(0, 64, 0));
        StubTicker b = new StubTicker(new BlockPos(16, 64, 0));

        data.addBlockEntityTicker(a);
        data.addBlockEntityTicker(b);
        assertThat(data.blockEntityTickerCount()).isEqualTo(2);
        assertThat(data.snapshotBlockEntityTickers()).containsExactlyInAnyOrder(a, b);

        assertThat(data.removeBlockEntityTicker(a)).isTrue();
        assertThat(data.blockEntityTickerCount()).isEqualTo(1);
        assertThat(data.snapshotBlockEntityTickers()).containsExactly(b);

        // Already removed — second call is a no-op reporting false, not a throw.
        assertThat(data.removeBlockEntityTicker(a)).isFalse();
    }

    /**
     * {@link HolderManagerRegionData#snapshotBlockEntityTickers()} hands
     * back an immutable copy specifically so a caller iterating it (the
     * B3.4 BLOCK_ENTITIES phase body, ticking each ref in turn) never
     * observes a {@link java.util.ConcurrentModificationException} when
     * a block entity removes itself — or another ticker — from the live
     * list mid-iteration.
     */
    @Test
    void snapshotIterationToleratesConcurrentRemovalFromLiveList() {
        HolderManagerRegionData data = new HolderManagerRegionData();
        List<StubTicker> tickers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            StubTicker t = new StubTicker(new BlockPos(i, 64, 0));
            tickers.add(t);
            data.addBlockEntityTicker(t);
        }

        List<TickingBlockEntityRef> snapshot = data.snapshotBlockEntityTickers();
        assertThat(snapshot).hasSize(10);

        assertThatCode(() -> {
                    for (TickingBlockEntityRef ticker : snapshot) {
                        // Simulate a ticker removing itself (and every other
                        // ticker) from the live store while the caller is
                        // still iterating the snapshot copy taken above.
                        data.removeBlockEntityTicker(ticker);
                    }
                })
                .doesNotThrowAnyException();

        assertThat(data.blockEntityTickerCount()).isZero();
        // The snapshot itself is untouched by the live-list drain.
        assertThat(snapshot).hasSize(10);
    }

    @Test
    void splitRedistributesTickersByBlockPosition() {
        HolderManagerRegionData data = new HolderManagerRegionData();
        ChunkPos leaveChunk = new ChunkPos(5, 5);

        StubTicker stayTicker = new StubTicker(new BlockPos(0, 64, 0)); // chunk (0,0)
        StubTicker leaveTicker = new StubTicker(new BlockPos(5 * 16 + 3, 64, 5 * 16 + 2)); // chunk (5,5)
        assertThat(leaveTicker.pos().toChunkPos()).isEqualTo(leaveChunk);

        data.addBlockEntityTicker(stayTicker);
        data.addBlockEntityTicker(leaveTicker);

        // shouldLeave (over NewChunkHolder) is irrelevant here — no holders
        // are staged — only the ChunkPos-keyed ticker predicate matters.
        HolderManagerRegionData child = data.split(h -> false, cp -> cp.equals(leaveChunk));

        assertThat(data.snapshotBlockEntityTickers()).containsExactly(stayTicker);
        assertThat(child.snapshotBlockEntityTickers()).containsExactly(leaveTicker);
    }

    @Test
    void mergeConcatenatesTickersWithoutDuplicates() {
        HolderManagerRegionData a = new HolderManagerRegionData();
        HolderManagerRegionData b = new HolderManagerRegionData();
        StubTicker t1 = new StubTicker(new BlockPos(0, 64, 0));
        StubTicker t2 = new StubTicker(new BlockPos(1, 64, 0));
        StubTicker t3 = new StubTicker(new BlockPos(2, 64, 0));

        a.addBlockEntityTicker(t1);
        b.addBlockEntityTicker(t2);
        b.addBlockEntityTicker(t3);

        a.merge(b);

        assertThat(a.snapshotBlockEntityTickers()).containsExactlyInAnyOrder(t1, t2, t3);
        // other's list is drained by the fold, matching pendingFullLoadUpdate/autoSaveQueue.
        assertThat(b.blockEntityTickerCount()).isZero();
    }

    /**
     * End-to-end through {@link ChunkHolderManager#onRegionSplit}, the
     * real production call site — proves the {@code ChunkPos} predicate
     * that already drives holder/ticket re-partitioning also drives
     * ticker re-partitioning, using the exact same predicate instance.
     */
    @Test
    void chunkHolderManagerSplitRedistributesTickersThroughRegionData() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId parent = RegionId.next();
        RegionId child = RegionId.next();

        ChunkPos stayChunk = new ChunkPos(0, 0);
        ChunkPos leaveChunk = new ChunkPos(9, 9);
        m.createHolder(stayChunk, parent);
        m.createHolder(leaveChunk, parent);

        StubTicker stayTicker = new StubTicker(new BlockPos(1, 64, 1));
        StubTicker leaveTicker = new StubTicker(new BlockPos(9 * 16, 64, 9 * 16));
        m.regionData(parent).addBlockEntityTicker(stayTicker);
        m.regionData(parent).addBlockEntityTicker(leaveTicker);

        m.onRegionSplit(parent, child, leaveChunk::equals);

        assertThat(m.regionData(parent).snapshotBlockEntityTickers()).containsExactly(stayTicker);
        assertThat(m.regionData(child).snapshotBlockEntityTickers()).containsExactly(leaveTicker);
    }

    private static final class StubTicker implements TickingBlockEntityRef {
        private final BlockPos pos;
        private volatile boolean removed;

        StubTicker(BlockPos pos) {
            this.pos = pos;
        }

        @Override
        public boolean shouldTick() {
            return !removed;
        }

        @Override
        public void tick() {}

        @Override
        public boolean isRemoved() {
            return removed;
        }

        @Override
        public BlockPos pos() {
            return pos;
        }
    }
}
