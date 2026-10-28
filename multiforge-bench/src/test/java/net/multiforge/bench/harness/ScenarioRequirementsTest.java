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

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link ScenarioRun#requirements}: a scenario must show what it is about, not just match stock. */
class ScenarioRequirementsTest {

    private static Map<String, String> healthyX4() {
        Map<String, String> mf = new HashMap<>();
        mf.put("errors", "0");
        mf.put("info.violations", "0");
        mf.put("info.reroute_mismatches", "0");
        mf.put("info.regions", "minecraft:overworld: 2 region(s)");
        mf.put("info.writes", "20");
        mf.put("info.reroutes", "20");
        return mf;
    }

    @Test
    void aHealthyRunPasses() {
        assertThat(ScenarioRun.requirements("x4", Map.of(), healthyX4())).isEmpty();
    }

    @Test
    void aScenarioThatFailedTheSameWayOnBothServersFails() {
        Map<String, String> stock = Map.of("scenario_error", "no village found");
        Map<String, String> mf = healthyX4();
        mf.put("scenario_error", "no village found");
        assertThat(ScenarioRun.requirements("x4", stock, mf)).hasSize(2);
    }

    @Test
    void oneRegionOrNoReroutesFailsX4() {
        Map<String, String> mf = healthyX4();
        mf.put("info.regions", "minecraft:overworld: 1 region(s)");
        mf.put("info.reroutes", "0");
        assertThat(ScenarioRun.requirements("x4", Map.of(), mf)).hasSize(2);
    }

    @Test
    void unreadableProbesFail() {
        Map<String, String> mf = healthyX4();
        mf.remove("info.violations");
        mf.put("errors", "3");
        assertThat(ScenarioRun.requirements("x2", Map.of(), mf)).hasSize(2);
    }

    @Test
    void parsesTheRegionCount() {
        assertThat(ScenarioRun.regionCount("minecraft:overworld: 4 region(s)")).isEqualTo(4);
        assertThat(ScenarioRun.regionCount("?")).isEqualTo(-1);
    }
}
