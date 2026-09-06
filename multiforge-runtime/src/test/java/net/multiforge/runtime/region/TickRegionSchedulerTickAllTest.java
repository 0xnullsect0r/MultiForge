/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.region;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link TickRegionScheduler#tickAll(java.util.Collection, long)}
 * barrier landed by M8 sub-step 6b (Phase 1 task 1.5) — the fan-out /
 * synchronisation primitive that {@code RegionizedTickCoordinator
 * .dispatchLevelTick} calls from the vanilla per-level tick to wait for
 * any in-flight region work before running the global portion.
 *
 * <p>These tests exercise {@link TickRegionScheduler} directly rather
 * than through the fork façade so a failure attributes clearly to the
 * barrier semantics, not to any of the vanilla-adjacent glue.
 */
class TickRegionSchedulerTickAllTest {

    private static final WorldRef WORLD = WorldRef.of("test:world");

    @Test
    void tickAllOnEmptyCollectionReturnsInstantly() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        try (TickRegionScheduler scheduler = new TickRegionScheduler(1, r -> {}, queue, 32)) {
            long start = System.nanoTime();
            TickRegionScheduler.TickAllResult result = scheduler.tickAll(List.of(), 1_000_000_000L);
            long elapsedNs = System.nanoTime() - start;

            assertThat(result.regionCount()).isZero();
            assertThat(result.overrunRegions()).isEmpty();
            assertThat(result.allCompleted()).isTrue();
            // Empty collection should not spin — deadline should be irrelevant.
            assertThat(elapsedNs).isLessThan(50_000_000L); // 50ms
        }
    }

    @Test
    void tickAllOnAllReadyRegionsReturnsWithoutWaiting() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        try (TickRegionScheduler scheduler = new TickRegionScheduler(2, r -> {}, queue, 32)) {
            regionizer.addListener(scheduler);

            Region r1 = regionizer.addChunk(new ChunkPos(0, 0));
            Region r2 = regionizer.addChunk(new ChunkPos(50, 50));

            // Force both regions to READY explicitly so we're synchronising
            // against a known state (fresh regions start TRANSIENT).
            r1.markReady();
            r2.markReady();

            TickRegionScheduler.TickAllResult result = scheduler.tickAll(List.of(r1, r2), 1_000_000_000L);

            assertThat(result.regionCount()).isEqualTo(2);
            assertThat(result.overrunRegions()).isEmpty();
            assertThat(result.allCompleted()).isTrue();
        }
    }

    @Test
    void tickAllWaitsForRegionCurrentlyTicking() throws Exception {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);

        // A body that hangs the first tick until we release it, then no-ops on
        // any subsequent tick — gives us a deterministic window where the
        // barrier must wait, and prevents the tight-loop retick from
        // reintroducing a TICKING state before the assertion below can check.
        CountDownLatch firstBodyEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstBody = new CountDownLatch(1);
        AtomicInteger bodyExits = new AtomicInteger();
        RegionTickBody body = r -> {
            if (firstBodyEntered.getCount() > 0) {
                firstBodyEntered.countDown();
                try {
                    releaseFirstBody.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                bodyExits.incrementAndGet();
            }
            // Subsequent ticks are instant no-ops.
        };

        try (TickRegionScheduler scheduler = new TickRegionScheduler(1, body, queue, 32)) {
            regionizer.addListener(scheduler);
            Region region = regionizer.addChunk(new ChunkPos(0, 0));

            // Wait until the worker has entered the body (so region is TICKING).
            assertThat(firstBodyEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(region.state()).isEqualTo(RegionState.TICKING);

            // Kick off the barrier on a background thread so we can release
            // the body from the main thread and observe the barrier return.
            Thread barrierThread = new Thread(
                    () -> {
                        // A long deadline: we release the body promptly, so the barrier
                        // returns well before this.
                        scheduler.tickAll(List.of(region), 5_000_000_000L);
                    },
                    "tickAll-under-test");
            barrierThread.setDaemon(true);
            barrierThread.start();

            // Give the barrier a moment to start spinning against TICKING.
            Thread.sleep(20);
            assertThat(barrierThread.isAlive()).isTrue(); // still spinning

            // Release the body → region → READY → barrier returns.
            releaseFirstBody.countDown();

            barrierThread.join(5000);
            assertThat(barrierThread.isAlive())
                    .as("barrier must return promptly after the body exits TICKING")
                    .isFalse();
            assertThat(bodyExits.get()).isEqualTo(1);
        }
    }

    @Test
    void tickAllRecordsOverrunWhenDeadlineExpiresWithRegionStillTicking() throws Exception {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);

        // A body that hangs until we release it — guarantees the region stays TICKING
        // for the entire duration of tickAll.
        CountDownLatch bodyEntered = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        RegionTickBody hangingBody = r -> {
            bodyEntered.countDown();
            try {
                // Bound with a large timeout so a broken test doesn't hang CI forever.
                releaseBody.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        };

        try (TickRegionScheduler scheduler = new TickRegionScheduler(1, hangingBody, queue, 32)) {
            regionizer.addListener(scheduler);
            Region region = regionizer.addChunk(new ChunkPos(0, 0));

            assertThat(bodyEntered.await(5, TimeUnit.SECONDS)).isTrue();

            // Deadline 20ms — the body will still be waiting on the latch, so the
            // barrier records this region as an overrun rather than blocking forever.
            TickRegionScheduler.TickAllResult result = scheduler.tickAll(List.of(region), 20_000_000L);

            assertThat(result.regionCount()).isEqualTo(1);
            assertThat(result.allCompleted()).isFalse();
            assertThat(result.overrunRegions()).containsExactly(region.id());

            // Release the body so the worker can clean up before scheduler.close().
            releaseBody.countDown();
        }
    }

    @Test
    void tickAllRejectsNullCollection() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        try (TickRegionScheduler scheduler = new TickRegionScheduler(1, r -> {}, queue, 32)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduler.tickAll(null, 1_000L))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("regions");
        }
    }

    @Test
    void tickAllResultIsImmutable() {
        java.util.ArrayList<RegionId> mutable = new java.util.ArrayList<>();
        mutable.add(new RegionId(1L));
        TickRegionScheduler.TickAllResult result = new TickRegionScheduler.TickAllResult(2, mutable);
        // Mutating the original list must not affect the result's snapshot.
        mutable.clear();
        assertThat(result.overrunRegions()).hasSize(1);
        // And the returned list is itself unmodifiable.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> result.overrunRegions().add(new RegionId(2L)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
