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

/**
 * Covers the HUD's pure line builders. The renderer itself needs a
 * Minecraft {@code GuiGraphics}, but everything that decides
 * <em>what</em> to draw is static and testable.
 */
class DebugHudRendererTest {

    private static DebugHudState connected() {
        DebugHudState state = new DebugHudState();
        state.apply(new DebugPayload.Hello(2, 20, "1.4.0"));
        return state;
    }

    @Test
    void summaryIsEmptyBeforeAnyHello() {
        assertThat(DebugHudRenderer.buildSummaryLines(new DebugHudState())).isEmpty();
    }

    @Test
    void summaryHasOnlyTheBuildLineUntilASnapshotArrives() {
        List<String> lines = DebugHudRenderer.buildSummaryLines(connected());
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("build=1.4.0").contains("proto=2").contains("tickHz=20");
    }

    @Test
    void summaryReportsWorstP95AcrossRegions() {
        DebugHudState state = connected();
        state.apply(new DebugPayload.RegionSnapshot(
                1L,
                List.of(
                        new DebugPayload.RegionStat(1, 1, 1.0, 8.0, 10),
                        new DebugPayload.RegionStat(2, 1, 1.0, 25.0, 10))));

        List<String> lines = DebugHudRenderer.buildSummaryLines(state);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(1)).contains("regions=2");
        assertThat(lines.get(2)).contains("worstP95=25.0ms");
        // tps ~= min(tickHz, 1000/worstP95) = min(20, 40) = 20
        assertThat(lines.get(1)).contains("tps~=20.0");
    }

    @Test
    void summaryTpsFallsBelowTickRateWhenARegionIsSlow() {
        DebugHudState state = connected();
        state.apply(new DebugPayload.RegionSnapshot(1L, List.of(new DebugPayload.RegionStat(1, 1, 1.0, 200.0, 0))));

        // 1000/200 = 5.0, which is below the 20 Hz tick rate.
        assertThat(DebugHudRenderer.buildSummaryLines(state).get(1)).contains("tps~=5.0");
    }

    @Test
    void unsupportedProtocolReplacesTheWholeSummary() {
        DebugHudState state = connected();
        state.markProtocolUnsupported(99);

        List<String> lines = DebugHudRenderer.buildSummaryLines(state);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("protocol 99").contains("unsupported");
    }

    @Test
    void regionListIsEmptyWithoutASnapshot() {
        assertThat(DebugHudRenderer.buildRegionLines(connected(), 8)).isEmpty();
        assertThat(DebugHudRenderer.buildRegionLines(new DebugHudState(), 8)).isEmpty();
    }

    @Test
    void regionListRendersOneRowPerRegion() {
        DebugHudState state = connected();
        state.apply(new DebugPayload.RegionSnapshot(
                1L,
                List.of(
                        new DebugPayload.RegionStat(1, 3, 10.0, 15.0, 42),
                        new DebugPayload.RegionStat(2, 4, 20.0, 25.0, 7))));

        List<String> rows = DebugHudRenderer.buildRegionLines(state, 8);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).contains("region-1").contains("mspt=10.0/15.0").contains("owned=42");
        assertThat(rows.get(1)).contains("region-2").contains("sections=4");
    }

    @Test
    void regionListTruncatesAndSaysSo() {
        DebugHudState state = connected();
        List<DebugPayload.RegionStat> many = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(new DebugPayload.RegionStat(i, 1, 1.0, 1.0, 0));
        }
        state.apply(new DebugPayload.RegionSnapshot(1L, many));

        List<String> rows = DebugHudRenderer.buildRegionLines(state, 5);
        assertThat(rows).hasSize(6); // 5 regions + the truncation notice
        assertThat(rows.get(5)).contains("7 more region");
    }

    @Test
    void violationPanelIsEmptyWithNoEvents() {
        assertThat(DebugHudRenderer.buildViolationLines(new DebugHudState(), 8)).isEmpty();
    }

    @Test
    void violationPanelIsNewestFirstAndCapped() {
        DebugHudState state = new DebugHudState();
        for (int i = 0; i < 20; i++) {
            state.apply(new DebugPayload.ViolationEvent(1_700_000_000_000L + i, "mod" + i, "site" + i, "detail" + i));
        }

        List<String> rows = DebugHudRenderer.buildViolationLines(state, 3);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).contains("[mod19]").contains("site19");
        assertThat(rows.get(2)).contains("[mod17]");
    }

    @Test
    void violationRowCarriesTimeModAndSite() {
        DebugHudState state = new DebugHudState();
        state.apply(new DebugPayload.ViolationEvent(0L, "somemod", "entity.move", "off-thread"));

        String row = DebugHudRenderer.buildViolationLines(state, 8).get(0);
        assertThat(row).matches("\\d\\d:\\d\\d:\\d\\d \\[somemod] entity\\.move — off-thread");
    }

    @Test
    void violationDetailIsEllipsisedSoOneEventCannotOverflowThePanel() {
        DebugHudState state = new DebugHudState();
        state.apply(new DebugPayload.ViolationEvent(0L, "m", "s", "x".repeat(400)));

        String row = DebugHudRenderer.buildViolationLines(state, 8).get(0);
        assertThat(row).endsWith("…");
        assertThat(row.length()).isLessThan(100);
    }
}
