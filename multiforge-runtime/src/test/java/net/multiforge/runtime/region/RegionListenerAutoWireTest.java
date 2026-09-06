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

import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import org.junit.jupiter.api.Test;

/**
 * Verifies the M8 auto-wire: when a regionizer signals merge or death,
 * {@link RegionizedTaskQueue} and {@link TickRegionScheduler}
 * automatically clean up as {@link RegionListener}s registered on the
 * regionizer — no manual bookkeeping required at the call sites.
 *
 * <p>These tests exercise the queue and scheduler directly rather than
 * through {@link net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost}
 * so a failure attributes clearly to the listener contract, not to
 * higher-level wiring.
 */
class RegionListenerAutoWireTest {

    private static final WorldRef WORLD = WorldRef.of("test:world");

    @Test
    void mergeMovesQueuedTasksFromDyingRegionToSurvivor() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        regionizer.addListener(queue);

        // Two initially-separate regions
        Region left = regionizer.addChunk(new ChunkPos(0, 0));
        Region right = regionizer.addChunk(new ChunkPos(3, 0));
        assertThat(left).isNotSameAs(right);

        // Queue tasks against each
        AtomicInteger ran = new AtomicInteger();
        queue.queueChunkTask(WORLD, 0, 0, ran::incrementAndGet);
        queue.queueChunkTask(WORLD, 3, 0, ran::incrementAndGet);
        assertThat(queue.inboxSize(left)).isEqualTo(1);
        assertThat(queue.inboxSize(right)).isEqualTo(1);

        // Bridge (1,0) then (2,0) — (2,0) is adjacent to both, forces merge.
        regionizer.addChunk(new ChunkPos(1, 0));
        regionizer.addChunk(new ChunkPos(2, 0));

        Region survivor = regionizer.regionAtChunk(0, 0);
        assertThat(regionizer.regionAtChunk(3, 0)).isSameAs(survivor);
        // Both queued tasks now live in the survivor's inbox.
        assertThat(queue.inboxSize(survivor)).isEqualTo(2);

        // Drain and prove both actually run (i.e. the merged region's queue is a real deliverable).
        queue.drain(survivor, 100);
        assertThat(ran.get()).isEqualTo(2);
    }

    @Test
    void lastChunkRemovedDropsQueueEntirely() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        regionizer.addListener(queue);

        Region region = regionizer.addChunk(new ChunkPos(50, 50));
        queue.queueChunkTask(WORLD, 50, 50, () -> {});
        assertThat(queue.inboxSize(region)).isEqualTo(1);

        regionizer.removeChunk(new ChunkPos(50, 50));
        // Dead region: the queue lookup returns 0 (no inbox for that id anymore).
        assertThat(queue.inboxSize(region)).isEqualTo(0);
    }

    @Test
    void schedulerDeregistersDeadRegionAutomatically() throws Exception {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        try (TickRegionScheduler scheduler = new TickRegionScheduler(1, r -> {}, queue, 32)) {
            regionizer.addListener(scheduler);

            Region region = regionizer.addChunk(new ChunkPos(0, 0));
            scheduler.register(region);
            assertThat(scheduler.mspt(region)).isNotNull();

            regionizer.removeChunk(new ChunkPos(0, 0));
            // Dead region → perRegion entry removed → mspt() returns null.
            assertThat(scheduler.mspt(region)).isNull();
        }
    }
}
