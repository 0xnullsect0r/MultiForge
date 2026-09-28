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

import java.util.ArrayList;
import java.util.List;
import net.multiforge.api.world.BlockPos;
import org.junit.jupiter.api.Test;

/** {@link HolderManagerRegionData#tickTickers}: in-place compaction with the snapshot semantics it replaced. */
class HolderManagerRegionDataTickTickersTest {

    @Test
    void dropsRemovedInPlaceAndKeepsOrder() {
        HolderManagerRegionData data = new HolderManagerRegionData();
        List<Ticker> all = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Ticker t = new Ticker(i);
            all.add(t);
            data.addBlockEntityTicker(t);
        }
        all.get(0).removed = true;
        all.get(3).removed = true;
        all.get(4).removed = true;
        all.get(9).removed = true;
        List<Integer> ticked = new ArrayList<>();
        data.tickTickers(t -> ticked.add(((Ticker) t).id));
        assertThat(ticked).containsExactly(1, 2, 5, 6, 7, 8);
        assertThat(data.snapshotBlockEntityTickers())
                .containsExactly(all.get(1), all.get(2), all.get(5), all.get(6), all.get(7), all.get(8));
    }

    @Test
    void tickerRemovedByAnEarlierTickerIsDroppedWhenReached() {
        HolderManagerRegionData data = new HolderManagerRegionData();
        Ticker a = new Ticker(0);
        Ticker b = new Ticker(1);
        Ticker c = new Ticker(2);
        data.addBlockEntityTicker(a);
        data.addBlockEntityTicker(b);
        data.addBlockEntityTicker(c);
        List<Integer> ticked = new ArrayList<>();
        data.tickTickers(t -> {
            ticked.add(((Ticker) t).id);
            if (t == a) c.removed = true;
        });
        assertThat(ticked).containsExactly(0, 1);
        assertThat(data.snapshotBlockEntityTickers()).containsExactly(a, b);
    }

    @Test
    void tickersAddedDuringThePassWaitForTheNextTickInOrder() {
        HolderManagerRegionData data = new HolderManagerRegionData();
        Ticker a = new Ticker(0);
        Ticker b = new Ticker(1);
        Ticker c = new Ticker(2);
        data.addBlockEntityTicker(a);
        data.addBlockEntityTicker(b);
        data.addBlockEntityTicker(c);
        b.removed = true;
        List<Ticker> added = new ArrayList<>();
        List<Integer> ticked = new ArrayList<>();
        data.tickTickers(t -> {
            ticked.add(((Ticker) t).id);
            // Enough to grow the backing array mid-pass.
            for (int i = 0; i < 40; i++) {
                Ticker n = new Ticker(100 + added.size());
                added.add(n);
                data.addBlockEntityTicker(n);
            }
        });
        assertThat(ticked).containsExactly(0, 2);
        List<TickingBlockEntityRef> expected = new ArrayList<>(List.of(a, c));
        expected.addAll(added);
        assertThat(data.snapshotBlockEntityTickers()).containsExactlyElementsOf(expected);

        ticked.clear();
        data.tickTickers(t -> ticked.add(((Ticker) t).id));
        assertThat(ticked).hasSize(82).startsWith(0, 2, 100, 101);
    }

    @Test
    void aThrowingTickerLeavesTheListConsistent() {
        HolderManagerRegionData data = new HolderManagerRegionData();
        Ticker a = new Ticker(0);
        Ticker b = new Ticker(1);
        Ticker boom = new Ticker(2);
        Ticker d = new Ticker(3);
        Ticker e = new Ticker(4);
        for (Ticker t : List.of(a, b, boom, d, e)) data.addBlockEntityTicker(t);
        b.removed = true;
        e.removed = true;
        assertThatThrownBy(() -> data.tickTickers(t -> {
                    if (t == boom) throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);
        // b dropped (visited), boom kept, d and e not visited yet: kept as they were.
        assertThat(data.snapshotBlockEntityTickers()).containsExactly(a, boom, d, e);
        assertThat(data.blockEntityTickerCount()).isEqualTo(4);
    }

    @Test
    void splitAndMergeKeepOrder() {
        HolderManagerRegionData data = new HolderManagerRegionData();
        List<Ticker> all = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Ticker t = new Ticker(i);
            all.add(t);
            data.addBlockEntityTicker(t);
        }
        HolderManagerRegionData out = data.split(pos -> pos.x() % 2 == 1);
        assertThat(data.snapshotBlockEntityTickers()).containsExactly(all.get(0), all.get(2), all.get(4));
        assertThat(out.snapshotBlockEntityTickers()).containsExactly(all.get(1), all.get(3), all.get(5));
        data.merge(out);
        assertThat(out.blockEntityTickerCount()).isZero();
        assertThat(data.snapshotBlockEntityTickers())
                .containsExactly(all.get(0), all.get(2), all.get(4), all.get(1), all.get(3), all.get(5));
    }

    private static final class Ticker implements TickingBlockEntityRef {
        final int id;
        boolean removed;

        Ticker(int id) {
            this.id = id;
        }

        @Override
        public boolean shouldTick() {
            return true;
        }

        @Override
        public void tick() {}

        @Override
        public boolean isRemoved() {
            return removed;
        }

        @Override
        public BlockPos pos() {
            return new BlockPos(id * 16, 64, 0);
        }
    }
}
