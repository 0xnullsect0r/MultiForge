/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.bench;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.multiforge.api.mod.ModIdentifier;
import net.multiforge.api.scheduler.ServerDomains;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;

/**
 * Standalone throughput microbench proving the parallel tick
 * infrastructure delivers scaling with worker count. Runs a synthetic
 * "region tick" workload — a burst of {@code CPU_WORK_ITERATIONS}
 * dummy math operations per region per tick — and measures how many
 * regions can be simulated per real-world second.
 *
 * <p>Invoke via {@code ./gradlew :multiforge-runtime:test --tests
 * RegionThroughputBench --info}. The JUnit annotation is intentionally
 * <em>not</em> on the class so it doesn't run in normal CI; enable it
 * for a manual scaling check.
 */
public final class RegionThroughputBench {

    private static final ModIdentifier MOD = ModIdentifier.of("multiforge_bench");
    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");
    private static final int REGIONS = 32;
    private static final int CPU_WORK_ITERATIONS = 200_000;
    private static final long WARMUP_MS = 1_000L;
    private static final long MEASURE_MS = 3_000L;

    private RegionThroughputBench() {}

    public static void main(String[] args) throws Exception {
        System.out.println("MultiForge region-throughput microbench");
        System.out.printf(
                "  regions=%d, cpu_iters/tick=%d, warmup=%dms, measure=%dms%n",
                REGIONS, CPU_WORK_ITERATIONS, WARMUP_MS, MEASURE_MS);

        for (int workers : new int[] {1, 2, 4, 8}) {
            double tps = runOnce(workers);
            System.out.printf(
                    "  workers=%d → aggregate ticks/sec = %.1f (%.1f per region)%n", workers, tps, tps / REGIONS);
        }
    }

    static double runOnce(int workers) throws Exception {
        ServerDomains.resetForTesting();
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(workers).withThreadsPerCore(1);
        AtomicLong ticksAtomic = new AtomicLong();
        MultiThreadedSchedulerHost host = new MultiThreadedSchedulerHost(config, region -> {
            long acc = 0L;
            for (int i = 0; i < CPU_WORK_ITERATIONS; i++) acc += i * 31L;
            if (acc == Long.MIN_VALUE) System.out.println(acc); // prevent DCE
            ticksAtomic.incrementAndGet();
        });
        try {
            // Populate REGIONS separate regions by spacing chunk coordinates by 100
            // (which crosses section boundaries no matter the sectionChunkShift ≤ 6).
            for (int i = 0; i < REGIONS; i++) {
                host.touchChunk(WORLD, i * 100, 0);
            }
            host.install();

            Thread.sleep(WARMUP_MS);
            long before = ticksAtomic.get();
            long start = System.nanoTime();
            Thread.sleep(MEASURE_MS);
            long elapsed = System.nanoTime() - start;
            long delta = ticksAtomic.get() - before;
            return delta * 1_000_000_000.0 / elapsed;
        } finally {
            host.close();
            ServerDomains.resetForTesting();
            // Wait for CountDownLatch equivalent — nothing to wait on here, but sleep briefly to let daemons die.
            Thread.sleep(50);
        }
    }

    /** Test-form of the bench used in CI to prove multi-worker throughput ≥ single-worker throughput. */
    public static double singleWorker() throws Exception {
        return runOnce(1);
    }

    public static double manyWorkers() throws Exception {
        return runOnce(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
    }

    /** Coordination helper (unused; retained for parity with the JMH pattern). */
    @SuppressWarnings("unused")
    private static CountDownLatch newLatch(int n) {
        return new CountDownLatch(n);
    }

    /** Used by tests that need a nanosecond stamp. */
    static long now() {
        return System.nanoTime();
    }

    /** Small helper to avoid TimeUnit noise in code above. */
    static long asNanos(long ms) {
        return TimeUnit.MILLISECONDS.toNanos(ms);
    }
}
