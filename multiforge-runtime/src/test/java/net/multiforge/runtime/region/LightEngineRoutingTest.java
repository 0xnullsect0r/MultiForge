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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import org.junit.jupiter.api.Test;

/**
 * Phase 4 task 4.5d — coverage for the routing invariant that {@link
 * net.multiforge.neoforge.chunk.MultiForgeLightEngine MultiForgeLightEngine}
 * relies on. Every mutating light call ({@code checkBlock},
 * {@code updateChunkStatus}, {@code updateSectionStatus}, and the batch
 * helpers) delegates to
 * {@link RegionizedTaskQueue#queueChunkTask(WorldRef, int, int, Runnable)}
 * — the facade itself extends {@code ThreadedLevelLightEngine} and cannot
 * be instantiated from the MC-free runtime module, so this suite exercises
 * the pure-Java queue semantics its correctness rests on:
 *
 * <ol>
 *   <li>owner resolution routes to the owning region's inbox,</li>
 *   <li>an unloaded chunk lands in the section-bucketed orphan queue,</li>
 *   <li>{@link RegionizedTaskQueue#reroute()} and {@link
 *       RegionizedTaskQueue#rerouteAtChunk(WorldRef, int, int)} drain the
 *       matching bucket without touching unrelated buckets,</li>
 *   <li>{@link RegionizedTaskQueue#drain(Region, int)} runs FIFO,
 *       honours the {@code max} bound, and does not abort on a task-thrown
 *       exception,</li>
 *   <li>concurrent producers targeting one region deliver every task
 *       exactly once,</li>
 *   <li>the production wiring via {@link
 *       RegionizedTaskQueue#of(ThreadedRegionizer)} pins ownership under
 *       the regionizer read lock so a concurrent write-lock hold blocks
 *       the enqueue.</li>
 * </ol>
 */
class LightEngineRoutingTest {

    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");

    /** Mutable owner map, thread-safe for the concurrent-producers case. */
    private static final class StubOwnerMap {
        final Map<Long, Region> owners = new ConcurrentHashMap<>();

        RegionizedTaskQueue.OwnerLookup lookup() {
            return (world, x, z) -> owners.get(key(x, z));
        }

        void put(int x, int z, Region r) {
            owners.put(key(x, z), r);
        }

        void clear(int x, int z) {
            owners.remove(key(x, z));
        }

        static long key(int x, int z) {
            return (((long) x) << 32) | (z & 0xFFFF_FFFFL);
        }
    }

    private static Region newRegion() {
        return new Region(RegionId.next(), 0);
    }

    // ----- Case 1 ---------------------------------------------------------

    @Test
    void taskLandsInOwnerRegionsInbox() {
        StubOwnerMap owners = new StubOwnerMap();
        Region regionA = newRegion();
        Region regionB = newRegion();
        owners.put(0, 0, regionA);
        owners.put(16, 16, regionB);

        RegionizedTaskQueue q = new RegionizedTaskQueue(owners.lookup());
        q.queueChunkTask(WORLD, 0, 0, () -> {});

        assertThat(q.inboxSize(regionA)).isEqualTo(1);
        assertThat(q.inboxSize(regionB)).isZero();
    }

    // ----- Case 2 ---------------------------------------------------------

    @Test
    void crossSectionTaskLandsInCorrectRegion() {
        StubOwnerMap owners = new StubOwnerMap();
        Region regionA = newRegion();
        Region regionB = newRegion();
        owners.put(0, 0, regionA);
        owners.put(16, 0, regionB);

        RegionizedTaskQueue q = new RegionizedTaskQueue(owners.lookup());
        q.queueChunkTask(WORLD, 16, 0, () -> {});

        assertThat(q.inboxSize(regionB)).isEqualTo(1);
        assertThat(q.inboxSize(regionA)).isZero();
    }

    // ----- Case 3 ---------------------------------------------------------

    @Test
    void unloadedChunkLandsInOrphanQueue() {
        StubOwnerMap owners = new StubOwnerMap();
        RegionizedTaskQueue q = new RegionizedTaskQueue(owners.lookup());

        q.queueChunkTask(WORLD, 999, 999, () -> {});

        assertThat(q.orphanedSize()).isEqualTo(1);
    }

    // ----- Case 4 ---------------------------------------------------------

    @Test
    void rerouteMovesOrphanedTaskAfterChunkLoad() {
        StubOwnerMap owners = new StubOwnerMap();
        RegionizedTaskQueue q = new RegionizedTaskQueue(owners.lookup());

        q.queueChunkTask(WORLD, 999, 999, () -> {});
        assertThat(q.orphanedSize()).isEqualTo(1);

        Region regionC = newRegion();
        owners.put(999, 999, regionC);
        q.reroute();

        assertThat(q.orphanedSize()).isZero();
        assertThat(q.inboxSize(regionC)).isEqualTo(1);
    }

    // ----- Case 5 ---------------------------------------------------------

    @Test
    void rerouteAtChunkOnlyDrainsMatchingBucket() {
        StubOwnerMap owners = new StubOwnerMap();
        RegionizedTaskQueue q = new RegionizedTaskQueue(owners.lookup());

        // Bucket A = section (0,0) — chunk (0,0) is in it (0 >> 4 == 0).
        for (int i = 0; i < 100; i++) {
            q.queueChunkTask(WORLD, 0, 0, () -> {});
        }
        // Bucket B = section (6,6) — chunk (100,100) is in it (100 >> 4 == 6).
        for (int i = 0; i < 100; i++) {
            q.queueChunkTask(WORLD, 100, 100, () -> {});
        }
        assertThat(q.orphanedSize()).isEqualTo(200);

        // Only bucket A's chunk gets an owner.
        Region regionA = newRegion();
        owners.put(0, 0, regionA);

        q.rerouteAtChunk(WORLD, 0, 0);

        assertThat(q.inboxSize(regionA)).isEqualTo(100);
        // Bucket B still has 100 orphans; bucket A is empty.
        assertThat(q.orphanedSize()).isEqualTo(100);
    }

    // ----- Case 6 ---------------------------------------------------------

    @Test
    void drainRunsTasksInFifoOrder() {
        StubOwnerMap owners = new StubOwnerMap();
        Region region = newRegion();
        owners.put(0, 0, region);
        RegionizedTaskQueue q = new RegionizedTaskQueue(owners.lookup());

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int captured = i;
            q.queueChunkTask(WORLD, 0, 0, () -> order.add(captured));
        }

        int drained = q.drain(region, 10);

        assertThat(drained).isEqualTo(5);
        assertThat(order).containsExactly(0, 1, 2, 3, 4);
    }

    // ----- Case 7 ---------------------------------------------------------

    @Test
    void drainRespectsMaxBound() {
        StubOwnerMap owners = new StubOwnerMap();
        Region region = newRegion();
        owners.put(0, 0, region);
        RegionizedTaskQueue q = new RegionizedTaskQueue(owners.lookup());

        AtomicInteger runs = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            q.queueChunkTask(WORLD, 0, 0, runs::incrementAndGet);
        }

        int drained = q.drain(region, 3);

        assertThat(drained).isEqualTo(3);
        assertThat(runs.get()).isEqualTo(3);
        assertThat(q.inboxSize(region)).isEqualTo(7);
    }

    // ----- Case 8 ---------------------------------------------------------

    @Test
    void drainIsolatesThrownExceptions() {
        StubOwnerMap owners = new StubOwnerMap();
        Region region = newRegion();
        owners.put(0, 0, region);
        RegionizedTaskQueue q = new RegionizedTaskQueue(owners.lookup());

        AtomicInteger post = new AtomicInteger();
        q.queueChunkTask(WORLD, 0, 0, () -> {
            throw new RuntimeException("simulated mod failure");
        });
        q.queueChunkTask(WORLD, 0, 0, post::incrementAndGet);

        // Swap the current thread's uncaught handler so the intentional
        // exception does not spam test output.
        Thread self = Thread.currentThread();
        var prev = self.getUncaughtExceptionHandler();
        self.setUncaughtExceptionHandler((t, e) -> {});
        try {
            int drained = q.drain(region, 10);
            assertThat(drained).isEqualTo(2);
        } finally {
            self.setUncaughtExceptionHandler(prev);
        }
        assertThat(post.get()).isEqualTo(1);
    }

    // ----- Case 9 ---------------------------------------------------------

    @Test
    void concurrentProducersSingleDrainerDeliversEveryTaskOnce() throws Exception {
        StubOwnerMap owners = new StubOwnerMap();
        Region region = newRegion();
        owners.put(0, 0, region);
        RegionizedTaskQueue q = new RegionizedTaskQueue(owners.lookup());

        final int producers = 8;
        final int perProducer = 100;
        final int total = producers * perProducer;

        AtomicInteger runs = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producers);
        List<Thread> threads = new ArrayList<>(producers);

        for (int p = 0; p < producers; p++) {
            Thread t = new Thread(
                    () -> {
                        try {
                            startGate.await();
                            for (int i = 0; i < perProducer; i++) {
                                q.queueChunkTask(WORLD, 0, 0, runs::incrementAndGet);
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    },
                    "lightengine-producer-" + p);
            t.start();
            threads.add(t);
        }
        startGate.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        for (Thread t : threads) t.join(5_000);

        assertThat(q.inboxSize(region)).isEqualTo(total);
        int drained = q.drain(region, total);
        assertThat(drained).isEqualTo(total);
        assertThat(runs.get()).isEqualTo(total);
        assertThat(q.inboxSize(region)).isZero();
    }

    // ----- Case 10 --------------------------------------------------------

    /**
     * Production wiring via {@link RegionizedTaskQueue#of(ThreadedRegionizer)}
     * pins section→region ownership under {@link
     * ThreadedRegionizer#readLock()} for the resolve-then-enqueue pair
     * (Phase 1 task 1.2). Regression proof: with the write lock held
     * (mimicking a merge in progress), a producer calling {@code
     * queueChunkTask} must block on the read-lock acquire; on release it
     * proceeds and the task lands in the surviving inbox exactly once.
     * A regression that dropped the read-lock acquire would let the
     * producer race past the block and enqueue immediately — the
     * "still blocked" assertion would fail.
     */
    @Test
    void queueChunkTaskBlocksWhileWriteLockHeld() throws Exception {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region region = regionizer.addChunk(new ChunkPos(0, 0));
        RegionizedTaskQueue q = RegionizedTaskQueue.of(regionizer);

        CountDownLatch writerHasLock = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        var writeLock = ((java.util.concurrent.locks.ReentrantReadWriteLock) rwLockOf(regionizer)).writeLock();

        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Thread blocker = new Thread(
                () -> {
                    writeLock.lock();
                    try {
                        writerHasLock.countDown();
                        try {
                            releaseWriter.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    } finally {
                        writeLock.unlock();
                    }
                },
                "lightengine-blocker");
        blocker.setUncaughtExceptionHandler((t, e) -> failures.add(e));
        blocker.start();
        assertThat(writerHasLock.await(5, TimeUnit.SECONDS)).isTrue();

        AtomicInteger runs = new AtomicInteger();
        Thread producer =
                new Thread(() -> q.queueChunkTask(WORLD, 0, 0, runs::incrementAndGet), "lightengine-producer");
        producer.setUncaughtExceptionHandler((t, e) -> failures.add(e));
        producer.start();

        // Producer must be blocked on the read-lock acquire — assert it
        // has not enqueued while we still hold the write lock.
        Thread.sleep(150);
        assertThat(q.inboxSize(region))
                .as("producer must not have enqueued while write lock is held")
                .isZero();
        assertThat(producer.isAlive()).isTrue();

        releaseWriter.countDown();
        producer.join(5_000);
        blocker.join(5_000);
        assertThat(producer.isAlive()).isFalse();
        assertThat(blocker.isAlive()).isFalse();
        assertThat(failures).isEmpty();

        assertThat(q.inboxSize(region)).isEqualTo(1);
        int drained = q.drain(region, 10);
        assertThat(drained).isEqualTo(1);
        assertThat(runs.get()).isEqualTo(1);
    }

    /**
     * Reflection reach into {@link ThreadedRegionizer#rwLock} — package
     * peers cannot see private state, and exposing the raw lock through
     * public API would leak an implementation detail. Scoped strictly to
     * this regression proof (mirrors the existing pattern in
     * {@code RegionizedTaskQueueTest}).
     */
    private static java.util.concurrent.locks.ReadWriteLock rwLockOf(ThreadedRegionizer regionizer) {
        try {
            java.lang.reflect.Field f = ThreadedRegionizer.class.getDeclaredField("rwLock");
            f.setAccessible(true);
            return (java.util.concurrent.locks.ReadWriteLock) f.get(regionizer);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("rwLock field missing on ThreadedRegionizer", e);
        }
    }
}
