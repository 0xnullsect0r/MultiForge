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
}
