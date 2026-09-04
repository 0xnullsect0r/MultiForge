/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionizedTaskQueue;
import net.multiforge.runtime.region.ThreadedRegionizer;
import org.junit.jupiter.api.Test;

class ChunkTaskSchedulerTest {

    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");

    @Test
    void drainsInStrictPriorityOrder() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region region = regionizer.addChunk(new ChunkPos(0, 0));
        RegionizedTaskQueue tq = RegionizedTaskQueue.of(regionizer);
        ChunkTaskScheduler cts = new ChunkTaskScheduler(tq, (w, x, z) -> regionizer.regionAtChunk(x, z));

        List<String> order = new ArrayList<>();
        cts.scheduleChunkTask(WORLD, 0, 0, () -> order.add("low"), ChunkTaskPriority.LOW);
        cts.scheduleChunkTask(WORLD, 0, 0, () -> order.add("blocking"), ChunkTaskPriority.BLOCKING);
        cts.scheduleChunkTask(WORLD, 0, 0, () -> order.add("normal"), ChunkTaskPriority.NORMAL);
        cts.scheduleChunkTask(WORLD, 0, 0, () -> order.add("highest"), ChunkTaskPriority.HIGHEST);

        // Trampolines land in the region's inbox; draining them runs drainInto which
        // executes tasks in strict priority order.
        tq.drain(region, Integer.MAX_VALUE);

        assertThat(order).containsExactly("blocking", "highest", "normal", "low");
    }

    @Test
    void pendingCountsPerPriority() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region region = regionizer.addChunk(new ChunkPos(0, 0));
        RegionizedTaskQueue tq = RegionizedTaskQueue.of(regionizer);
        ChunkTaskScheduler cts = new ChunkTaskScheduler(tq, (w, x, z) -> regionizer.regionAtChunk(x, z));

        cts.scheduleChunkTask(WORLD, 0, 0, () -> {}, ChunkTaskPriority.HIGH);
        cts.scheduleChunkTask(WORLD, 0, 0, () -> {}, ChunkTaskPriority.HIGH);
        cts.scheduleChunkTask(WORLD, 0, 0, () -> {}, ChunkTaskPriority.LOW);

        assertThat(cts.pending(region.id(), ChunkTaskPriority.HIGH)).isEqualTo(2);
        assertThat(cts.pending(region.id(), ChunkTaskPriority.LOW)).isEqualTo(1);
        assertThat(cts.pending(region.id(), ChunkTaskPriority.NORMAL)).isEqualTo(0);
    }
}
