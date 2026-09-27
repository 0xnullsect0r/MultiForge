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
package net.multiforge.runtime.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChunkCostTest {

    private static final String WORLD = "minecraft:overworld";

    @BeforeEach
    @AfterEach
    void reset() {
        ChunkCost.resetForTesting();
    }

    @Test
    void nothingIsMeasuredWhileDisabledOrOutsideALevelTick() {
        assertThat(ChunkCost.start()).isZero();
        ChunkCost.beginLevelTick(WORLD); // disabled: no-op
        assertThat(ChunkCost.start()).isZero();

        ChunkCost.setEnabled(true);
        assertThat(ChunkCost.start()).isZero(); // enabled, but no level is ticking
        ChunkCost.beginLevelTick(WORLD);
        assertThat(ChunkCost.start()).isNotZero();
        ChunkCost.endLevelTick();
        assertThat(ChunkCost.start()).isZero();
    }

    @Test
    void samplesSumPerChunkAndDrainPerTick() {
        ChunkCost.setEnabled(true);
        ChunkCost.beginLevelTick(WORLD);
        ChunkCost.add(1, 2, 300);
        ChunkCost.add(1, 2, 200);
        ChunkCost.add(-5, 7, 1000);
        ChunkCost.flush(WORLD);
        ChunkCost.beginLevelTick(WORLD);
        ChunkCost.add(-5, 7, 1000);
        ChunkCost.flush(WORLD);
        ChunkCost.endLevelTick();

        ChunkCost.Drained d = ChunkCost.drain(WORLD);
        assertThat(d.ticks()).isEqualTo(2);
        assertThat(asMap(d))
                .containsEntry(ChunkCost.pack(1, 2), 500L)
                .containsEntry(ChunkCost.pack(-5, 7), 2000L)
                .hasSize(2);

        ChunkCost.Drained again = ChunkCost.drain(WORLD);
        assertThat(again.ticks()).isZero();
        assertThat(again.size()).isZero();
    }

    @Test
    void worldsAreKeptApart() {
        ChunkCost.setEnabled(true);
        ChunkCost.beginLevelTick(WORLD);
        ChunkCost.add(0, 0, 10);
        ChunkCost.flush(WORLD);
        ChunkCost.beginLevelTick("minecraft:the_nether");
        ChunkCost.add(0, 0, 99);
        ChunkCost.flush("minecraft:the_nether");

        assertThat(asMap(ChunkCost.drain(WORLD))).containsExactly(Map.entry(ChunkCost.pack(0, 0), 10L));
        assertThat(asMap(ChunkCost.drain("minecraft:the_nether")))
                .containsExactly(Map.entry(ChunkCost.pack(0, 0), 99L));
    }

    @Test
    void theTableGrowsPastItsInitialSize() {
        ChunkCost.setEnabled(true);
        ChunkCost.beginLevelTick(WORLD);
        for (int x = 0; x < 500; x++) ChunkCost.add(x, -x, x + 1);
        ChunkCost.flush(WORLD);
        Map<Long, Long> got = asMap(ChunkCost.drain(WORLD));
        assertThat(got).hasSize(500);
        for (int x = 0; x < 500; x++) assertThat(got).containsEntry(ChunkCost.pack(x, -x), (long) x + 1);
    }

    @Test
    void packRoundTripsNegativeCoordinates() {
        long key = ChunkCost.pack(-30_000_000, 29_999_999);
        assertThat(ChunkCost.unpackX(key)).isEqualTo(-30_000_000);
        assertThat(ChunkCost.unpackZ(key)).isEqualTo(29_999_999);
    }

    @Test
    void disablingDropsWhatWasCollected() {
        ChunkCost.setEnabled(true);
        ChunkCost.beginLevelTick(WORLD);
        ChunkCost.add(0, 0, 10);
        ChunkCost.setEnabled(false);
        ChunkCost.flush(WORLD);
        assertThat(ChunkCost.drain(WORLD).size()).isZero();
    }

    /**
     * Not a benchmark, a guard: the enabled hot path (two nanoTime calls and
     * a table add) must stay far below the cost of the entity or block
     * entity tick it wraps. Prints the measured cost for the record.
     */
    @Test
    void theEnabledHotPathIsCheap() {
        ChunkCost.setEnabled(true);
        ChunkCost.beginLevelTick(WORLD);
        int n = 2_000_000;
        for (int warm = 0; warm < 3; warm++) {
            for (int i = 0; i < n; i++) ChunkCost.end(i & 255, i >> 8 & 255, ChunkCost.start());
            ChunkCost.flush(WORLD);
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) ChunkCost.end(i & 255, i >> 8 & 255, ChunkCost.start());
        long enabledNs = (System.nanoTime() - t0) / n;
        ChunkCost.flush(WORLD);
        ChunkCost.endLevelTick();

        long t1 = System.nanoTime();
        for (int i = 0; i < n; i++) ChunkCost.end(i & 255, i >> 8 & 255, ChunkCost.start());
        long idleNs = (System.nanoTime() - t1) / n;

        System.out.println("ChunkCost per unit: enabled=" + enabledNs + "ns idle=" + idleNs + "ns");
        assertThat(enabledNs).isLessThan(2_000);
        assertThat(idleNs).isLessThan(200);
    }

    private static Map<Long, Long> asMap(ChunkCost.Drained d) {
        Map<Long, Long> m = new HashMap<>();
        for (int i = 0; i < d.size(); i++) m.put(d.keys()[i], d.nanos()[i]);
        return m;
    }
}
