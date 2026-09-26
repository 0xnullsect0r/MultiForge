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
package net.multiforge.runtime.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.TickingBlockEntityRef;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.TickRegionScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@link MultiThreadedSchedulerHost#applyConfig}: worker-pool resize and live re-partitioning. */
class LiveConfigTest {

    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");
    private MultiThreadedSchedulerHost host;

    @AfterEach
    void close() {
        if (host != null) host.close();
    }

    private static TickingBlockEntityRef ticker(int blockX, int blockZ) {
        BlockPos pos = new BlockPos(blockX, 64, blockZ);
        return new TickingBlockEntityRef() {
            @Override
            public boolean shouldTick() {
                return true;
            }

            @Override
            public void tick() {}

            @Override
            public boolean isRemoved() {
                return false;
            }

            @Override
            public BlockPos pos() {
                return pos;
            }
        };
    }

    @Test
    void resizingThePoolChangesHowManyThreadsTickRegions() {
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(1).withThreadsPerCore(1);
        host = new MultiThreadedSchedulerHost(config, region -> {}, TickRegionScheduler.Mode.BARRIER);
        assertThat(host.scheduler().workerCount()).isEqualTo(1);

        host.applyConfig(config.withCores(4));
        assertThat(host.scheduler().workerCount()).isEqualTo(4);

        // Regions far apart tick concurrently on up to four distinct threads.
        Set<String> threads = ConcurrentHashMap.newKeySet();
        MultiThreadedSchedulerHost h = host;
        for (int i = 0; i < 8; i++) h.touchChunk(WORLD, i * 1000, 0);
        host.installRegionTickBody(
                net.multiforge.runtime.region.PhasedRegionTickBody.builder().blockFluidTicks(r -> {
                    threads.add(Thread.currentThread().getName());
                    java.util.concurrent.locks.LockSupport.parkNanos(20_000_000L);
                }));
        host.driveRegions(WORLD, 5_000_000_000L, () -> false);
        assertThat(threads).hasSizeBetween(2, 4);

        host.applyConfig(host.config().withCores(1));
        assertThat(host.scheduler().workerCount()).isEqualTo(1);
        host.driveRegions(WORLD, 5_000_000_000L, () -> false); // still ticks after shrinking
    }

    @Test
    void changingTheRegionSizeRepartitionsAndMovesTickers() {
        // shift 4: chunks 0 and 20 fall in adjacent 16-chunk sections -> one region.
        MultiForgeConfig config =
                MultiForgeConfig.defaults().withCores(1).withThreadsPerCore(1).withRegionSize(4);
        host = new MultiThreadedSchedulerHost(config, region -> {}, TickRegionScheduler.Mode.BARRIER);
        Region a = host.touchChunk(WORLD, 0, 0);
        Region b = host.touchChunk(WORLD, 20, 0);
        assertThat(a).isSameAs(b);
        ChunkHolderManager manager = host.chunkManagerFor(WORLD);
        manager.regionData(a.id()).addBlockEntityTicker(ticker(5, 5)); // chunk (0,0)
        manager.regionData(a.id()).addBlockEntityTicker(ticker(20 * 16 + 5, 5)); // chunk (20,0)

        // shift 0: one chunk per section, so the two chunks are far apart -> two regions.
        host.applyConfig(config.withRegionSize(0));

        Region near = host.regionizerFor(WORLD).regionAtChunk(0, 0);
        Region far = host.regionizerFor(WORLD).regionAtChunk(20, 0);
        assertThat(near).isNotNull().isNotSameAs(far);
        assertThat(far).isNotNull();
        assertThat(host.regionizerFor(WORLD).sectionChunkShift()).isZero();
        assertThat(manager.holderAt(new ChunkPos(0, 0)).owningRegion()).isEqualTo(near.id());
        assertThat(manager.holderAt(new ChunkPos(20, 0)).owningRegion()).isEqualTo(far.id());
        assertThat(manager.regionData(near.id()).snapshotBlockEntityTickers())
                .extracting(t -> t.pos().toChunkPos())
                .containsExactly(new ChunkPos(0, 0));
        assertThat(manager.regionData(far.id()).snapshotBlockEntityTickers())
                .extracting(t -> t.pos().toChunkPos())
                .containsExactly(new ChunkPos(20, 0));
        assertThat(List.copyOf(host.regionizerFor(WORLD).regions())).hasSize(2);
    }
}
