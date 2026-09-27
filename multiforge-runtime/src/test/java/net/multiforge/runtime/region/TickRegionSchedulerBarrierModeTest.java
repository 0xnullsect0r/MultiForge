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
package net.multiforge.runtime.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import org.junit.jupiter.api.Test;

/** {@link TickRegionScheduler.Mode#BARRIER}: the server thread drives every region tick. */
class TickRegionSchedulerBarrierModeTest {

    private static final WorldRef WORLD = WorldRef.of("test:barrier");

    @Test
    void regionsNeverTickWithoutADrive() throws Exception {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        AtomicInteger ticks = new AtomicInteger();
        try (TickRegionScheduler scheduler =
                new TickRegionScheduler(2, r -> ticks.incrementAndGet(), queue, 32, TickRegionScheduler.Mode.BARRIER)) {
            regionizer.addListener(scheduler);
            regionizer.addChunk(new ChunkPos(0, 0));
            Thread.sleep(200);
            assertThat(ticks.get()).isZero();
        }
    }

    @Test
    void driveTickRunsEveryRegionExactlyOnceAndWaits() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        Map<RegionId, AtomicInteger> perRegion = new ConcurrentHashMap<>();
        RegionTickBody body = r -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            perRegion.computeIfAbsent(r.id(), k -> new AtomicInteger()).incrementAndGet();
        };
        try (TickRegionScheduler scheduler =
                new TickRegionScheduler(4, body, queue, 32, TickRegionScheduler.Mode.BARRIER)) {
            regionizer.addListener(scheduler);
            Region a = regionizer.addChunk(new ChunkPos(0, 0));
            Region b = regionizer.addChunk(new ChunkPos(500, 500));
            Region c = regionizer.addChunk(new ChunkPos(-500, 500));
            for (int tick = 1; tick <= 5; tick++) {
                TickRegionScheduler.TickAllResult result =
                        scheduler.driveTick(List.of(a, b, c), 5_000_000_000L, () -> false);
                assertThat(result.regionCount()).isEqualTo(3);
                assertThat(result.allCompleted()).isTrue();
                // Barrier: every region's body has returned when driveTick returns.
                for (Region r : List.of(a, b, c)) {
                    assertThat(perRegion.get(r.id()).get()).isEqualTo(tick);
                    assertThat(r.state()).isEqualTo(RegionState.READY);
                }
            }
        }
    }

    @Test
    void regionsTickInParallel() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        CountDownLatch bothInside = new CountDownLatch(2);
        AtomicBoolean overlapped = new AtomicBoolean();
        RegionTickBody body = r -> {
            bothInside.countDown();
            try {
                overlapped.compareAndSet(false, bothInside.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try (TickRegionScheduler scheduler =
                new TickRegionScheduler(2, body, queue, 32, TickRegionScheduler.Mode.BARRIER)) {
            regionizer.addListener(scheduler);
            Region a = regionizer.addChunk(new ChunkPos(0, 0));
            Region b = regionizer.addChunk(new ChunkPos(500, 500));
            scheduler.driveTick(List.of(a, b), 5_000_000_000L, () -> false);
            assertThat(overlapped.get()).isTrue();
        }
    }

    @Test
    void pumpRunsOnTheCallerWhileWaiting() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        // The region body needs the caller ("main thread") to do something
        // before it can finish — exactly Vanilla's off-thread getChunk hop.
        CountDownLatch mainThreadWork = new CountDownLatch(1);
        RegionTickBody body = r -> {
            try {
                assertThat(mainThreadWork.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Thread caller = Thread.currentThread();
        AtomicBoolean pumpedOnCaller = new AtomicBoolean();
        try (TickRegionScheduler scheduler =
                new TickRegionScheduler(1, body, queue, 32, TickRegionScheduler.Mode.BARRIER)) {
            scheduler.setInlinePolicy(false, 0); // a worker, so the caller pumps
            regionizer.addListener(scheduler);
            Region a = regionizer.addChunk(new ChunkPos(0, 0));
            TickRegionScheduler.TickAllResult result = scheduler.driveTick(List.of(a), 5_000_000_000L, () -> {
                if (Thread.currentThread() == caller) pumpedOnCaller.set(true);
                mainThreadWork.countDown();
                return true;
            });
            assertThat(result.allCompleted()).isTrue();
            assertThat(pumpedOnCaller.get()).isTrue();
        }
    }

    @Test
    void designedWaitsDoNotCountAgainstTheDeadline() {
        // The region waits 300ms for main-thread work (a chunk generation), bracketed
        // as a designed wait the way MainThreadHandoff does; it does nothing slow itself.
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        CountDownLatch mainThreadWork = new CountDownLatch(1);
        RegionTickBody body = r -> {
            RegionTickWatchdog.beginWait();
            try {
                assertThat(mainThreadWork.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                RegionTickWatchdog.endWait("test");
            }
        };
        try (TickRegionScheduler scheduler =
                new TickRegionScheduler(1, body, queue, 32, TickRegionScheduler.Mode.BARRIER)) {
            scheduler.setInlinePolicy(false, 0); // a worker, so the caller pumps
            regionizer.addListener(scheduler);
            Region a = regionizer.addChunk(new ChunkPos(0, 0));
            TickRegionScheduler.TickAllResult result = scheduler.driveTick(List.of(a), 100_000_000L, () -> {
                if (mainThreadWork.getCount() == 0) return false;
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                mainThreadWork.countDown();
                return true;
            });
            assertThat(result.allCompleted()).isTrue();
        }
    }

    @Test
    void overrunIsReportedButTheTickIsNeverCutShort() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        AtomicBoolean bodyFinished = new AtomicBoolean();
        RegionTickBody body = r -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            bodyFinished.set(true);
        };
        try (TickRegionScheduler scheduler =
                new TickRegionScheduler(1, body, queue, 32, TickRegionScheduler.Mode.BARRIER)) {
            regionizer.addListener(scheduler);
            Region a = regionizer.addChunk(new ChunkPos(0, 0));
            TickRegionScheduler.TickAllResult result = scheduler.driveTick(List.of(a), 1_000_000L, () -> false);
            assertThat(bodyFinished.get()).isTrue();
            assertThat(result.overrunRegions()).containsExactly(a.id());
        }
    }

    @Test
    void queuedChunkTasksDrainDuringADrivenTick() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        AtomicInteger ran = new AtomicInteger();
        try (TickRegionScheduler scheduler =
                new TickRegionScheduler(1, r -> {}, queue, 32, TickRegionScheduler.Mode.BARRIER)) {
            regionizer.addListener(scheduler);
            regionizer.addListener(queue);
            Region a = regionizer.addChunk(new ChunkPos(0, 0));
            queue.queueChunkTask(WORLD, 0, 0, ran::incrementAndGet);
            assertThat(ran.get()).isZero();
            scheduler.driveTick(List.of(a), 5_000_000_000L, () -> false);
            assertThat(ran.get()).isEqualTo(1);
        }
    }

    @Test
    void driveTickIsRejectedInFreeRunningMode() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        try (TickRegionScheduler scheduler = new TickRegionScheduler(1, r -> {}, queue, 32)) {
            assertThatThrownBy(() -> scheduler.driveTick(List.of(), 0L, () -> false))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void aRegionTickedOnTheCallerLeavesItsDesignedWaitsOutOfItsTime() {
        // Ticked inline, the region's thread is the server thread, which loads a
        // missing chunk itself (ServerChunkCache's managedBlock, bracketed by
        // MainThreadHandoff.enterInline). That wait is not the region's own work:
        // exploring terrain alone must not make the region look hot.
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        AtomicBoolean inTickDuringBody = new AtomicBoolean();
        AtomicBoolean bracketed = new AtomicBoolean(true);
        RegionTickBody body = r -> {
            inTickDuringBody.set(RegionTickWatchdog.inTick());
            boolean wait = bracketed.get();
            if (wait) RegionTickWatchdog.beginWait();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (wait) RegionTickWatchdog.endWait("main-thread-chunk-load");
            }
        };
        try (TickRegionScheduler scheduler =
                new TickRegionScheduler(2, body, queue, 32, TickRegionScheduler.Mode.BARRIER)) {
            regionizer.addListener(scheduler);
            Region a = regionizer.addChunk(new ChunkPos(0, 0));

            scheduler.driveTick(List.of(a), 5_000_000_000L, () -> false);
            assertThat(scheduler.lastTickPlacement(a))
                    .isEqualTo(TickRegionScheduler.TickPlacement.SERVER_THREAD_SINGLE);
            assertThat(inTickDuringBody).isTrue();
            assertThat(RegionTickWatchdog.inTick()).isFalse();
            assertThat(scheduler.mspt(a).averageMillis()).isLessThan(100.0);

            // The same 200ms of unbracketed work is the region's own time.
            bracketed.set(false);
            scheduler.driveTick(List.of(a), 5_000_000_000L, () -> false);
            assertThat(scheduler.mspt(a).averageMillis()).isGreaterThan(90.0);
        }
    }

    @Test
    void aRegionAloneInItsBatchTicksOnTheCaller() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        Map<RegionId, Thread> tickedOn = new ConcurrentHashMap<>();
        RegionTickBody body = r -> tickedOn.put(r.id(), Thread.currentThread());
        try (TickRegionScheduler scheduler =
                new TickRegionScheduler(2, body, queue, 32, TickRegionScheduler.Mode.BARRIER)) {
            regionizer.addListener(scheduler);
            Region a = regionizer.addChunk(new ChunkPos(0, 0));
            Region b = regionizer.addChunk(new ChunkPos(500, 500));

            scheduler.driveTick(List.of(a), 5_000_000_000L, () -> false);
            assertThat(tickedOn.get(a.id())).isSameAs(Thread.currentThread());
            assertThat(scheduler.lastTickPlacement(a))
                    .isEqualTo(TickRegionScheduler.TickPlacement.SERVER_THREAD_SINGLE);
            assertThat(scheduler.lastTickThread(a))
                    .isEqualTo(Thread.currentThread().getName());

            scheduler.driveTick(List.of(a, b), 5_000_000_000L, () -> false);
            assertThat(tickedOn.get(a.id())).isNotSameAs(Thread.currentThread());
            assertThat(tickedOn.get(b.id())).isNotSameAs(Thread.currentThread());
            assertThat(scheduler.lastTickPlacement(a)).isEqualTo(TickRegionScheduler.TickPlacement.WORKER);
            assertThat(scheduler.lastTickThread(b)).startsWith("multiforge-tick-");

            scheduler.setInlinePolicy(false, 0);
            scheduler.driveTick(List.of(a), 5_000_000_000L, () -> false);
            assertThat(tickedOn.get(a.id())).isNotSameAs(Thread.currentThread());
        }
    }

    @Test
    void aRegionWhoseHandOffsCostTooMuchTicksOnTheCallerUntilItQuietsDown() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        AtomicInteger posts = new AtomicInteger(50);
        Map<RegionId, Thread> tickedOn = new ConcurrentHashMap<>();
        Region[] hot = new Region[1];
        // 50 posts whose hand-offs cost the worker 8 ms in all (a designed wait with
        // no listener work in it), over the 5 ms threshold.
        RegionTickBody body = r -> {
            tickedOn.put(r.id(), Thread.currentThread());
            if (r != hot[0]) return;
            int n = posts.get();
            for (int i = n; i > 0; i--) RegionTickWatchdog.countSerialPost();
            if (n > 0 && Thread.currentThread().getName().startsWith("multiforge-tick-")) {
                RegionTickWatchdog.beginWait();
                spin(8_000_000L);
                RegionTickWatchdog.endWait(RegionTickWatchdog.SERIAL_LANE, 0L);
            }
        };
        try (TickRegionScheduler scheduler =
                new TickRegionScheduler(2, body, queue, 32, TickRegionScheduler.Mode.BARRIER)) {
            scheduler.setInlinePolicy(true, 5);
            regionizer.addListener(scheduler);
            Region a = regionizer.addChunk(new ChunkPos(0, 0));
            Region b = regionizer.addChunk(new ChunkPos(500, 500));
            hot[0] = a;

            for (int i = 1; i < TickRegionScheduler.HOT_ENTER_TICKS; i++) {
                scheduler.driveTick(List.of(a, b), 5_000_000_000L, () -> false);
                assertThat(scheduler.lastTickPlacement(a)).isEqualTo(TickRegionScheduler.TickPlacement.WORKER);
            }
            assertThat(scheduler.lastTickSerialPosts(a)).isEqualTo(50);
            assertThat(scheduler.lastTickSerialOverheadNanos(a)).isGreaterThanOrEqualTo(8_000_000L);
            // The hand-off time is left out of the region's time.
            assertThat(scheduler.mspt(a).averageMillis()).isLessThan(4.0);
            scheduler.driveTick(List.of(a, b), 5_000_000_000L, () -> false); // 20th tick over: now hot

            scheduler.driveTick(List.of(a, b), 5_000_000_000L, () -> false);
            assertThat(tickedOn.get(a.id())).isSameAs(Thread.currentThread());
            assertThat(scheduler.lastTickPlacement(a)).isEqualTo(TickRegionScheduler.TickPlacement.SERVER_THREAD_HOT);
            assertThat(tickedOn.get(b.id())).isNotSameAs(Thread.currentThread());
            assertThat(scheduler.serialHandoffNanos()).isGreaterThan(TickRegionScheduler.DEFAULT_HANDOFF_NANOS);

            // Still posting: the estimate (posts × measured hand-off) keeps it hot.
            for (int i = 0; i < TickRegionScheduler.HOT_RELEASE_TICKS + 5; i++) {
                scheduler.driveTick(List.of(a, b), 5_000_000_000L, () -> false);
            }
            assertThat(scheduler.lastTickPlacement(a)).isEqualTo(TickRegionScheduler.TickPlacement.SERVER_THREAD_HOT);

            posts.set(0);
            for (int i = 0; i < TickRegionScheduler.HOT_RELEASE_TICKS; i++) {
                scheduler.driveTick(List.of(a, b), 5_000_000_000L, () -> false);
            }
            scheduler.driveTick(List.of(a, b), 5_000_000_000L, () -> false);
            assertThat(scheduler.lastTickPlacement(a)).isEqualTo(TickRegionScheduler.TickPlacement.WORKER);
        }
    }

    @Test
    void aFewCheapHandOffsNeverMoveARegion() {
        // Many posts are not by themselves a reason to move: only the time they cost.
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        RegionTickBody body = r -> {
            for (int i = 0; i < 5000; i++) RegionTickWatchdog.countSerialPost();
        };
        try (TickRegionScheduler scheduler =
                new TickRegionScheduler(2, body, queue, 32, TickRegionScheduler.Mode.BARRIER)) {
            scheduler.setInlinePolicy(true, 5);
            regionizer.addListener(scheduler);
            Region a = regionizer.addChunk(new ChunkPos(0, 0));
            Region b = regionizer.addChunk(new ChunkPos(500, 500));
            for (int i = 0; i < TickRegionScheduler.HOT_ENTER_TICKS + 5; i++) {
                scheduler.driveTick(List.of(a, b), 5_000_000_000L, () -> false);
            }
            assertThat(scheduler.lastTickPlacement(a)).isEqualTo(TickRegionScheduler.TickPlacement.WORKER);
        }
    }

    @Test
    void serialLaneListenersCountTheSameOnAWorkerAndOnTheCaller() {
        // Five lane jobs of 2 ms of listener work each: 10 ms of the region's own
        // work per tick, whether a worker hands them to the lane or the region
        // ticks on the lane thread and runs them itself. Only the hand-off is left out.
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        Region[] measured = new Region[1];
        RegionTickBody body = r -> {
            if (r != measured[0]) return;
            for (int i = 0; i < 5; i++) net.multiforge.runtime.event.SerialLane.run(() -> spin(2_000_000L));
        };
        net.multiforge.runtime.event.SerialLane.bind(Thread.currentThread());
        try (TickRegionScheduler scheduler =
                new TickRegionScheduler(2, body, queue, 32, TickRegionScheduler.Mode.BARRIER)) {
            scheduler.setInlinePolicy(true, 0);
            regionizer.addListener(scheduler);
            Region a = regionizer.addChunk(new ChunkPos(0, 0));
            Region b = regionizer.addChunk(new ChunkPos(500, 500));
            measured[0] = a;

            for (int i = 0; i < 20; i++) {
                scheduler.driveTick(List.of(a, b), 5_000_000_000L, net.multiforge.runtime.event.SerialLane::drain);
            }
            assertThat(scheduler.lastTickPlacement(a)).isEqualTo(TickRegionScheduler.TickPlacement.WORKER);
            double onWorker = scheduler.mspt(a).averageMillis();

            Region c = regionizer.addChunk(new ChunkPos(-500, -500));
            measured[0] = c;
            for (int i = 0; i < 20; i++) {
                scheduler.driveTick(List.of(c), 5_000_000_000L, net.multiforge.runtime.event.SerialLane::drain);
            }
            assertThat(scheduler.lastTickPlacement(c))
                    .isEqualTo(TickRegionScheduler.TickPlacement.SERVER_THREAD_SINGLE);
            double onCaller = scheduler.mspt(c).averageMillis();

            assertThat(onWorker).isGreaterThan(9.0);
            assertThat(onCaller).isGreaterThan(9.0);
            assertThat(onWorker).isCloseTo(onCaller, org.assertj.core.data.Percentage.withPercentage(25));
        } finally {
            net.multiforge.runtime.event.SerialLane.unbind();
        }
    }

    private static void spin(long nanos) {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) Thread.onSpinWait();
    }
}
