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
package net.multiforge.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import org.junit.jupiter.api.Test;

/** v1.9.0: the heatmap's per-chunk colour scale, and the HUD's "Here:" line. */
class HeatmapScaleAndHereLineTest {

    private static final String WORLD = "minecraft:overworld";

    @Test
    void aProtocolFourServerIsColouredPerChunkAndAnOlderOnePerRegion() {
        assertThat(HeatmapRenderer.scaleFor(4)).isSameAs(HeatmapRenderer.CHUNK_SCALE);
        assertThat(HeatmapRenderer.scaleFor(3)).isSameAs(HeatmapRenderer.REGION_SCALE);
        assertThat(HeatmapRenderer.scaleFor(1)).isSameAs(HeatmapRenderer.REGION_SCALE);
    }

    @Test
    void perChunkScale_emptyIsGreen_aMillisecondIsYellow_fiveIsRed() {
        float[] s = HeatmapRenderer.CHUNK_SCALE;
        assertThat(HeatmapRenderer.colorFor(0.02f, s)).startsWith(0, 255, 0);
        assertThat(HeatmapRenderer.colorFor(1.0f, s)).startsWith(255, 255, 0);
        assertThat(HeatmapRenderer.colorFor(5.0f, s)).startsWith(255, 0, 0);
        assertThat(HeatmapRenderer.colorFor(50.0f, s)).startsWith(255, 0, 0);
    }

    @Test
    void theSameNumberMeansLessOnTheRegionScale() {
        // 3 ms is orange-red for one chunk but green for a whole region.
        assertThat(HeatmapRenderer.colorFor(3.0f, HeatmapRenderer.CHUNK_SCALE)[1])
                .isLessThan(255);
        assertThat(HeatmapRenderer.colorFor(3.0f, HeatmapRenderer.REGION_SCALE)).startsWith(0, 255, 0);
    }

    @Test
    void hereNamesTheRegionItsTimeAndTheChunksOwnCost() {
        DebugHudState state = new DebugHudState();
        state.apply(new DebugPayload.Hello(4, 20, "1.9.0"));
        state.apply(new DebugPayload.RegionSnapshot(1L, List.of(new DebugPayload.RegionStat(7, 3, 4.1, 9.8, 12))));
        state.apply(new DebugPayload.OwnershipUpdate(WORLD, 4, List.of(new DebugPayload.SectionOwner(0, 0, 7))));
        state.apply(new DebugPayload.HeatmapUpdate(WORLD, List.of(new DebugPayload.ChunkHeat(3, 5, 0.02f))));

        String here = DebugHudRenderer.hereLine(state, WORLD, 3, 5);
        assertThat(here).isEqualTo("Here: region #7 (p50 4.1 / p95 9.8 ms), this chunk 0.02 ms/tick");

        List<String> lines = DebugHudRenderer.buildSummaryLines(state, WORLD, 3, 5);
        assertThat(lines).last().isEqualTo(here);
    }

    @Test
    void anOlderServersHeatIsNotPassedOffAsTheChunksOwn() {
        DebugHudState state = new DebugHudState();
        state.apply(new DebugPayload.Hello(3, 20, "1.8.1"));
        state.apply(new DebugPayload.OwnershipUpdate(WORLD, 4, List.of(new DebugPayload.SectionOwner(0, 0, 7))));
        state.apply(new DebugPayload.HeatmapUpdate(WORLD, List.of(new DebugPayload.ChunkHeat(3, 5, 45f))));

        assertThat(DebugHudRenderer.hereLine(state, WORLD, 3, 5)).isEqualTo("Here: region #7");
    }

    @Test
    void outsideAnyRegionSaysSoAndWithoutOwnershipSaysNothing() {
        DebugHudState state = new DebugHudState();
        state.apply(new DebugPayload.Hello(4, 20, "1.9.0"));
        assertThat(DebugHudRenderer.hereLine(state, WORLD, 0, 0)).isNull();

        state.apply(new DebugPayload.OwnershipUpdate(WORLD, 4, List.of(new DebugPayload.SectionOwner(0, 0, 7))));
        assertThat(DebugHudRenderer.hereLine(state, WORLD, 100, 100)).isEqualTo("Here: no region");
        assertThat(DebugHudRenderer.hereLine(state, "minecraft:the_nether", 0, 0))
                .isNull();
    }
}
