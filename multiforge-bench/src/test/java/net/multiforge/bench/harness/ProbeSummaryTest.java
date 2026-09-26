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

import org.junit.jupiter.api.Test;

class ProbeSummaryTest {
    @Test
    void groupsTheRealProbeKeys() {
        ProbeSummary s = ProbeSummary.parse(String.join(
                "\n",
                "Level.setBlock:cross-region = 12",
                "Level.addFreshEntity:cross-region = 3",
                "reroute.Level.setBlock.mismatch = 1",
                "Level.setBlock:off-thread = 2",
                "entity-ai.wrong-owner = 4",
                "region-tick.block-fluid.off-thread = 1",
                "region-tick.overrun = 5",
                "region-tick.dispatch.overrun = 1",
                "event.dispatch.async.overflow = 9",
                "serial-lane.handoff = 40",
                "(no probes matching prefix 'x')"));
        assertThat(s.reroutes()).isEqualTo(15);
        assertThat(s.rerouteMismatches()).isEqualTo(1);
        assertThat(s.violations()).isEqualTo(7);
        assertThat(s.overruns()).isEqualTo(6);
        assertThat(s.violationCounters())
                .containsOnlyKeys(
                        "Level.setBlock:off-thread", "entity-ai.wrong-owner", "region-tick.block-fluid.off-thread");
    }
}
