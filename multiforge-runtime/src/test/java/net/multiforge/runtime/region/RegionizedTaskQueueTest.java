/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.region;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import org.junit.jupiter.api.Test;

class RegionizedTaskQueueTest {

    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");

    @Test
    void enqueuedTaskRunsAgainstOwnerRegion() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region region = regionizer.addChunk(new ChunkPos(0, 0));
        RegionizedTaskQueue q = RegionizedTaskQueue.of(regionizer);

        AtomicInteger runs = new AtomicInteger();
        q.queueChunkTask(WORLD, 0, 0, runs::incrementAndGet);
        assertThat(q.inboxSize(region)).isEqualTo(1);

        int drained = q.drain(region, Integer.MAX_VALUE);
        assertThat(drained).isEqualTo(1);
        assertThat(runs.get()).isEqualTo(1);
        assertThat(q.inboxSize(region)).isZero();
    }

    @Test
    void unresolvedChunkGoesToOrphanQueueAndReroutesAfterAdd() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue q = RegionizedTaskQueue.of(regionizer);

        AtomicInteger runs = new AtomicInteger();
        q.queueChunkTask(WORLD, 10, 10, runs::incrementAndGet);
        assertThat(q.orphanedSize()).isEqualTo(1);

        // No owner yet — reroute drops it back in the orphan queue.
        q.reroute();
        assertThat(q.orphanedSize()).isEqualTo(1);

        // Occupy the chunk and re-route.
        Region owner = regionizer.addChunk(new ChunkPos(10, 10));
        q.reroute();
        assertThat(q.orphanedSize()).isZero();
        assertThat(q.inboxSize(owner)).isEqualTo(1);
    }

    @Test
    void ordersFifoWithinOneRegion() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region region = regionizer.addChunk(new ChunkPos(0, 0));
        RegionizedTaskQueue q = RegionizedTaskQueue.of(regionizer);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            int captured = i;
            q.queueChunkTask(WORLD, 0, 0, () -> sb.append(captured));
        }
        q.drain(region, 5);
        assertThat(sb.toString()).isEqualTo("01234");
    }

    @Test
    void exceptionInOneTaskDoesNotHaltTheDrain() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region region = regionizer.addChunk(new ChunkPos(0, 0));
        RegionizedTaskQueue q = RegionizedTaskQueue.of(regionizer);

        AtomicInteger post = new AtomicInteger();
        q.queueChunkTask(WORLD, 0, 0, () -> {
            throw new RuntimeException("mod threw");
        });
        q.queueChunkTask(WORLD, 0, 0, post::incrementAndGet);

        int drained = q.drain(region, 10);
        assertThat(drained).isEqualTo(2);
        assertThat(post.get()).isEqualTo(1);
    }

    /**
     * Phase 1 task 1.2 regression: {@link
     * RegionizedTaskQueue#queueChunkTask} must serialise against a
     * concurrent regionizer merge, otherwise a merge landing between the
     * lookup and the add can put the task into a dying region's inbox
     * (which the merge/death listeners then drop). The fix wraps the
     * resolve-then-enqueue pair in the regionizer's read lock.
     *
     * <p>This test drives the race deterministically:
     * <ol>
     *   <li>A "blocker" thread grabs the write lock (mimicking a merge in
     *       progress) and holds it until we tell it to release.</li>
     *   <li>A "producer" thread calls {@code queueChunkTask}. Because
     *       {@link ThreadedRegionizer#readLock()} is now held around the
     *       enqueue, the producer must block on the read-lock acquire
     *       until the write lock is released.</li>
     *   <li>We assert the producer is blocked (it did not enqueue), release
     *       the write lock, and confirm the enqueue then commits.</li>
     * </ol>
     * A regression that removed the read-lock acquire would let the
     * producer race past the block and enqueue immediately — the "still
     * blocked" assertion would fail.
     */
    @Test
    void queueChunkTaskUnderConcurrentMergeDoesNotLoseTask() throws Exception {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region region = regionizer.addChunk(new ChunkPos(0, 0));
        RegionizedTaskQueue q = RegionizedTaskQueue.of(regionizer);
        // Match production shape: queue folds/drops inboxes on merge/death.
        regionizer.addListener(q);

        AtomicInteger runs = new AtomicInteger();
        CountDownLatch writerHasLock = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        var writeLock =
                ((java.util.concurrent.locks.ReentrantReadWriteLock) pickRwLockFromRegionizer(regionizer)).writeLock();

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
                "test-blocker");
        blocker.start();
        assertThat(writerHasLock.await(5, TimeUnit.SECONDS)).isTrue();

        Thread producer = new Thread(() -> q.queueChunkTask(WORLD, 0, 0, runs::incrementAndGet), "test-producer");
        producer.start();
        // Producer should be blocked on read-lock acquire — give it a
        // generous moment to try, then assert it hasn't proceeded.
        Thread.sleep(150);
        assertThat(q.inboxSize(region))
                .as("producer must not have enqueued while write lock is held (Phase 1 task 1.2)")
                .isZero();
        assertThat(producer.isAlive()).isTrue();

        // Release: producer should complete promptly, enqueue, and exit.
        releaseWriter.countDown();
        producer.join(5_000);
        blocker.join(5_000);
        assertThat(producer.isAlive()).isFalse();
        assertThat(blocker.isAlive()).isFalse();

        assertThat(q.inboxSize(region)).isEqualTo(1);
        int drained = q.drain(region, 10);
        assertThat(drained).isEqualTo(1);
        assertThat(runs.get()).isEqualTo(1);
    }

    /**
     * Complementary regression: when {@link #queueChunkTask} runs at the
     * exact moment a bridge chunk lands, the read lock guarantees the
     * enqueue either resolves the surviving region (post-merge) or
     * lands in the dying region <em>before</em> the merge and is then
     * moved by {@link RegionizedTaskQueue#onRegionsMerging}. Either way
     * the task is delivered.
     */
    @Test
    void queueChunkTaskAfterMergeResolvesSurvivor() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region left = regionizer.addChunk(new ChunkPos(0, 0));
        Region right = regionizer.addChunk(new ChunkPos(2, 0));
        assertThat(left).isNotSameAs(right);

        RegionizedTaskQueue q = RegionizedTaskQueue.of(regionizer);
        regionizer.addListener(q);
        // Bridge → merge left + right.
        regionizer.addChunk(new ChunkPos(1, 0));
        Region survivor = regionizer.regionAtChunk(0, 0);
        assertThat(regionizer.regionAtChunk(2, 0)).isSameAs(survivor);

        AtomicInteger runs = new AtomicInteger();
        q.queueChunkTask(WORLD, 2, 0, runs::incrementAndGet);
        assertThat(q.inboxSize(survivor)).isEqualTo(1);
        int drained = q.drain(survivor, 10);
        assertThat(drained).isEqualTo(1);
        assertThat(runs.get()).isEqualTo(1);
    }

    /**
     * Cross-thread visibility helper: reach the regionizer's rwLock via
     * reflection so we can drive both sides of the read/write lock from
     * the test without changing production API surface. Package-private
     * accessors on {@link ThreadedRegionizer} would leak an internal
     * implementation detail; a reflection reach here is scoped to the
     * regression proof for Phase 1 task 1.2.
     */
    private static java.util.concurrent.locks.ReadWriteLock pickRwLockFromRegionizer(ThreadedRegionizer regionizer) {
        try {
            java.lang.reflect.Field f = ThreadedRegionizer.class.getDeclaredField("rwLock");
            f.setAccessible(true);
            return (java.util.concurrent.locks.ReadWriteLock) f.get(regionizer);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("rwLock field missing on ThreadedRegionizer (Phase 1 task 1.2)", e);
        }
    }
}
