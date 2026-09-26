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
package net.multiforge.bench.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricsCollectorTest {

    private static final String TICKSTATS =
            "ticks=2003 mean=0.201ms p50=0.140ms p95=0.550ms p99=1.110ms max=4.800ms tps=19.98 window=100.2s";

    @Test
    void tickStatsIsAuthoritative() {
        MetricsCollector m = new MetricsCollector();
        m.recordTickQueryReply("Average time per tick: 9.0ms (Target: 50.0ms)\n"
                + "Percentiles: P50: 8.0ms P95: 9.0ms P99: 10.0ms, sample: 100");
        m.recordTickStats(TICKSTATS);
        assertThat(m.timingSource()).isEqualTo("tickstats");
        assertThat(m.avgMspt()).isEqualTo(0.201);
        assertThat(m.p99Mspt()).isEqualTo(1.11);
        assertThat(m.maxMspt()).isEqualTo(4.8);
        assertThat(m.sustainedTps()).isEqualTo(19.98);
        assertThat(m.tickStats())
                .get()
                .extracting(MetricsCollector.TickStatsReply::ticks)
                .isEqualTo(2003L);
    }

    @Test
    void withoutTickStatsTpsIsMeasuredFromGameTimeOrAbsent() {
        MetricsCollector m = new MetricsCollector();
        assertThat(m.sustainedTps()).isNaN();
        m.recordGameTimeWindow(1200, 60.0);
        assertThat(m.sustainedTps()).isEqualTo(20.0);
        assertThat(m.timingSource()).isEqualTo("tick-query");
    }

    @Test
    void sprintRunsDoNotReportSustainedTps() {
        MetricsCollector m = new MetricsCollector();
        m.recordTickStats(TICKSTATS.replace("tps=19.98", "tps=3530.60"));
        BenchResult sprint = BenchResult.from("vanilla", 1, 2000, 1, m, 1, true, true, Map.of("pacing", "sprint"));
        assertThat(sprint.toJson())
                .contains("\"tps_sustained_last_10min\": null")
                .contains("\"sprint_tps\": 3531");
        BenchResult paced = BenchResult.from("swarm", 1, 2000, 1, m, 1, true, true, Map.of("pacing", "real-time"));
        assertThat(paced.toJson()).contains("\"tps_sustained_last_10min\": 3530.6");
    }
}
