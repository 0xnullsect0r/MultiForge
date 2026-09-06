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
package net.multiforge.runtime.bench;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Manual bench smoke wrappers — {@link Disabled @Disabled} for CI
 * because throughput is meaningless on shared runners with unknown
 * CPU allocation. Run locally to sanity-check that the harness
 * compiles and produces non-zero output:
 *
 * <pre>
 *   ./gradlew :multiforge-runtime:test --tests RegionThroughputBenchTest
 *         -DrunBench=true
 * </pre>
 *
 * The full scaling curve comes from
 * {@link RegionThroughputBench#main(String[])}.
 */
@Disabled("Manual — enable via -DrunBench=true or run RegionThroughputBench.main() directly")
class RegionThroughputBenchTest {

    @Test
    void singleWorkerProducesPositiveThroughput() throws Exception {
        double tps = RegionThroughputBench.singleWorker();
        assertThat(tps).isGreaterThan(0.0);
    }

    @Test
    void manyWorkersProducesPositiveThroughput() throws Exception {
        double tps = RegionThroughputBench.manyWorkers();
        assertThat(tps).isGreaterThan(0.0);
    }
}
