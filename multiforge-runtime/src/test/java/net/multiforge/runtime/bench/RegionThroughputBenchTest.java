/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.bench;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Sanity-only smoke test — confirms the bench runs without crashing
 * and produces positive throughput at both worker sizes. Does NOT
 * assert on a specific TPS ratio because CI runners have variable
 * CPU allocation. The full scaling curve is produced by running
 * {@link RegionThroughputBench#main(String[])} manually.
 */
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
