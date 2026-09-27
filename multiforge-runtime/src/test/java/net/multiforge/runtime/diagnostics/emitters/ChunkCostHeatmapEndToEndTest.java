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
package net.multiforge.runtime.diagnostics.emitters;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import net.multiforge.api.scheduler.ServerDomains;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.TickingBlockEntityRef;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.diagnostics.ChunkCost;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.multiforge.runtime.region.PhasedRegionTickBody;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.TickRegionScheduler;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The whole path behind the per-chunk heatmap: block-entity work on a real
 * barrier tick, charged to its chunk by the region phase, flushed to the
 * world's window and read by the emitter — for a region ticked on a worker
 * and one ticked on the server thread.
 */
class ChunkCostHeatmapEndToEndTest {

    private static final WorldRef WORLD = WorldRef.of("test:chunk-cost");

    private MultiThreadedSchedulerHost host;

    @AfterEach
    void tearDown() {
        ChunkCost.resetForTesting();
        if (host != null) host.close();
        ServerDomains.resetForTesting();
    }

    @Test
    void aBusyChunkIsHotAndItsIdleNeighbourInTheSameRegionIsNot_onWorkers() {
        run(true);
    }

    @Test
    void aBusyChunkIsHotAndItsIdleNeighbourInTheSameRegionIsNot_lonelyRegionOnTheServerThread() {
        run(false);
    }

    private void run(boolean twoRegions) {
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(2).withThreadsPerCore(1);
        host = new MultiThreadedSchedulerHost(config, region -> {}, TickRegionScheduler.Mode.BARRIER);
        // Chunks 0 and 1 share a 16-chunk section: one region.
        Region base = host.touchChunk(WORLD, 0, 0);
        assertThat(host.touchChunk(WORLD, 1, 0)).isSameAs(base);
        host.chunkManagerFor(WORLD).regionData(base.id()).addBlockEntityTicker(ticker(0, 0, 2_000_000L));
        host.chunkManagerFor(WORLD).regionData(base.id()).addBlockEntityTicker(ticker(16, 0, 0L));
        if (twoRegions) {
            Region far = host.touchChunk(WORLD, 1000, 1000);
            assertThat(far).isNotSameAs(base);
            host.chunkManagerFor(WORLD).regionData(far.id()).addBlockEntityTicker(ticker(16_000, 16_000, 2_000_000L));
        }
        host.installRegionTickBody(PhasedRegionTickBody.builder());
        ChunkCost.setEnabled(true);

        for (int i = 0; i < 10; i++) host.driveRegions(WORLD, 5_000_000_000L, () -> false);

        List<DebugPayload> received = new ArrayList<>();
        new TpsHistogramEmitter(host, WORLD, received::add, PermissionFilter.ALWAYS_ALLOW).emit();
        DebugPayload.HeatmapUpdate update = (DebugPayload.HeatmapUpdate) received.get(0);

        DebugPayload.ChunkHeat busy = heatAt(update, 0, 0);
        DebugPayload.ChunkHeat idle = heatAt(update, 1, 0);
        assertThat(busy.heatMspt()).isGreaterThan(1.5f);
        assertThat(idle.heatMspt()).isLessThan(0.5f);
        // The region's own average covers both chunks' work, as before.
        assertThat(busy.regionMspt()).isEqualTo(idle.regionMspt()).isGreaterThan(1.5f);
        if (twoRegions) assertThat(heatAt(update, 1000, 1000).heatMspt()).isGreaterThan(1.5f);
    }

    /** A block entity at block (x, 64, z) whose tick takes {@code spinNanos}. */
    private static TickingBlockEntityRef ticker(int blockX, int blockZ, long spinNanos) {
        BlockPos pos = new BlockPos(blockX, 64, blockZ);
        return new TickingBlockEntityRef() {
            @Override
            public boolean shouldTick() {
                return true;
            }

            @Override
            public void tick() {
                if (spinNanos <= 0) return;
                long end = System.nanoTime() + spinNanos;
                while (System.nanoTime() < end) LockSupport.parkNanos(50_000L);
            }

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

    private static DebugPayload.ChunkHeat heatAt(DebugPayload.HeatmapUpdate update, int x, int z) {
        return update.heats().stream()
                .filter(h -> h.chunkX() == x && h.chunkZ() == z)
                .findFirst()
                .orElseThrow();
    }
}
