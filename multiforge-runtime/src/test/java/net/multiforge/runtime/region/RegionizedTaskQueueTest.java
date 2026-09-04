/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.region;

import static org.assertj.core.api.Assertions.assertThat;

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
}
