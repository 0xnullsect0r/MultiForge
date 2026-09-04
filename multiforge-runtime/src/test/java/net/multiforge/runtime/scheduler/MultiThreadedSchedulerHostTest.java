/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.mod.ModIdentifier;
import net.multiforge.api.scheduler.ScheduledTask;
import net.multiforge.api.scheduler.ServerDomains;
import net.multiforge.api.scheduler.TaskState;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.config.MultiForgeConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MultiThreadedSchedulerHostTest {

    private static final ModIdentifier MOD = ModIdentifier.of("multiforge_test");
    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");
    private static final ChunkPos POS = new ChunkPos(0, 0);

    private MultiThreadedSchedulerHost host;

    @BeforeEach
    void install() {
        ServerDomains.resetForTesting();
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(2).withThreadsPerCore(1);
        host = new MultiThreadedSchedulerHost(config);
        host.touchChunk(WORLD, POS.x(), POS.z());
        host.install();
    }

    @AfterEach
    void shutdown() {
        host.close();
        ServerDomains.resetForTesting();
    }

    @Test
    void regionTaskEventuallyRuns() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        ScheduledTask t = ServerDomains.region(WORLD, POS).execute(MOD, latch::countDown);
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> t.state() == TaskState.FINISHED);
    }

    @Test
    void globalTaskEventuallyRuns() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        ScheduledTask t = ServerDomains.global().execute(MOD, latch::countDown);
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> t.state() == TaskState.FINISHED);
    }

    @Test
    void asyncTaskRunsOffTickPool() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger threadIdCaptured = new AtomicInteger(-1);
        ServerDomains.async().runNow(MOD, ignored -> {
            threadIdCaptured.set((int) Thread.currentThread().threadId());
            latch.countDown();
        });
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(Thread.currentThread().getName()).doesNotStartWith("multiforge-async-");
    }

    @Test
    void regionRunAtFixedRateFiresMultipleTimes() {
        AtomicInteger n = new AtomicInteger();
        ServerDomains.region(WORLD, POS)
                .runAtFixedRate(
                        MOD,
                        h -> {
                            if (n.incrementAndGet() >= 3) h.cancel();
                        },
                        0L,
                        1L);
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> n.get() >= 3);
        assertThat(n.get()).isGreaterThanOrEqualTo(3);
    }
}
