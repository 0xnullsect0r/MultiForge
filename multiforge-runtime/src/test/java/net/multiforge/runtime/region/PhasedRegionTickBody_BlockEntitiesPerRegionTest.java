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

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.TickingBlockEntityRef;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage for B3.4's per-region {@code BLOCK_ENTITIES} wiring
 * (docs/design/m13-b3-region-tick.md §5.3):
 * {@code MultiThreadedSchedulerHost#phaseBlockEntitiesTickPerRegion},
 * layered <em>after</em> the already-landed {@code
 * phaseGlobalSystemsTick} on the same phase slot via {@link
 * MultiThreadedSchedulerHost#installM9WiredTickBody}.
 *
 * <p>End-to-end through the real production path: a region is created
 * via {@link MultiThreadedSchedulerHost#touchChunk}, a {@link
 * TickingBlockEntityRef} test double is registered directly against
 * that region's {@code HolderManagerRegionData} (the fork bridge's job
 * of routing a real Vanilla ticker there is exercised by the fork
 * module's own tests, out of reach of this MC-free module — see {@code
 * net.multiforge.neoforge.tick.BlockEntityTickerBridge}), and the
 * installed {@link PhasedRegionTickBody} is driven by hand via {@code
 * tickOnce}, matching {@code PhasedRegionTickBodyWiringTest}'s and
 * {@code MultiThreadedSchedulerHostGlobalTickTest}'s established
 * pattern for this test module.
 */
class PhasedRegionTickBody_BlockEntitiesPerRegionTest {

    private static final WorldRef WORLD = WorldRef.of("test:block-entities-per-region");

    private MultiThreadedSchedulerHost host;

    @BeforeEach
    void install() {
        ProbeRegistry.resetForTesting();
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(1).withThreadsPerCore(1);
        host = new MultiThreadedSchedulerHost(config);
        // Stop the autonomous worker pool so tests can drive body.tickOnce
        // by hand without racing it — same pattern as
        // PhasedRegionTickBodyWiringTest / MultiThreadedSchedulerHostGlobalTickTest.
        host.scheduler().close();
    }

    @AfterEach
    void shutdown() {
        host.close();
        ProbeRegistry.resetForTesting();
    }

    @Test
    void threeTickersInOneRegionAllTickExactlyOnce(@TempDir Path journalDir) {
        Region region = host.touchChunk(WORLD, 0, 0);
        ChunkHolderManager manager = host.chunkManagerFor(WORLD);

        RecordingTicker a = new RecordingTicker(new BlockPos(0, 64, 0));
        RecordingTicker b = new RecordingTicker(new BlockPos(1, 64, 0));
        RecordingTicker c = new RecordingTicker(new BlockPos(2, 64, 0));
        manager.regionData(region.id()).addBlockEntityTicker(a);
        manager.regionData(region.id()).addBlockEntityTicker(b);
        manager.regionData(region.id()).addBlockEntityTicker(c);

        host.installM9WiredTickBody(PhasedRegionTickBody.builder(), null, journalDir);
        RegionTickBody body = host.scheduler().body();

        body.tickOnce(region);

        assertThat(a.tickCount.get()).isEqualTo(1);
        assertThat(b.tickCount.get()).isEqualTo(1);
        assertThat(c.tickCount.get()).isEqualTo(1);
    }

    @Test
    void crossRegionIsolationOnlyTickingRegionsTickersFire(@TempDir Path journalDir) {
        // Far-apart chunk coordinates so touchChunk materialises two
        // distinct regions, matching the offset convention already used
        // by ChunkHolderManagerOwnershipTest's multi-region cases.
        Region regionA = host.touchChunk(WORLD, 0, 0);
        Region regionB = host.touchChunk(WORLD, 1000, 1000);
        ChunkHolderManager manager = host.chunkManagerFor(WORLD);
        assertThat(regionA.id()).isNotEqualTo(regionB.id());

        RecordingTicker inA = new RecordingTicker(new BlockPos(0, 64, 0));
        RecordingTicker inB = new RecordingTicker(new BlockPos(1000 * 16, 64, 1000 * 16));
        manager.regionData(regionA.id()).addBlockEntityTicker(inA);
        manager.regionData(regionB.id()).addBlockEntityTicker(inB);

        host.installM9WiredTickBody(PhasedRegionTickBody.builder(), null, journalDir);
        RegionTickBody body = host.scheduler().body();

        body.tickOnce(regionA);

        assertThat(inA.tickCount.get()).isEqualTo(1);
        assertThat(inB.tickCount.get()).isZero();

        body.tickOnce(regionB);

        assertThat(inA.tickCount.get()).isEqualTo(1);
        assertThat(inB.tickCount.get()).isEqualTo(1);
    }

    @Test
    void removedTickersAreSkippedAndPrunedFromTheRegionSlice(@TempDir Path journalDir) {
        Region region = host.touchChunk(WORLD, 0, 0);
        ChunkHolderManager manager = host.chunkManagerFor(WORLD);

        RecordingTicker live = new RecordingTicker(new BlockPos(0, 64, 0));
        RecordingTicker removed = new RecordingTicker(new BlockPos(1, 64, 0));
        removed.removed = true;
        manager.regionData(region.id()).addBlockEntityTicker(live);
        manager.regionData(region.id()).addBlockEntityTicker(removed);
        assertThat(manager.regionData(region.id()).blockEntityTickerCount()).isEqualTo(2);

        host.installM9WiredTickBody(PhasedRegionTickBody.builder(), null, journalDir);
        RegionTickBody body = host.scheduler().body();

        body.tickOnce(region);

        assertThat(live.tickCount.get()).isEqualTo(1);
        assertThat(removed.tickCount.get()).isZero();
        // The removed ticker is pruned from the live slice, not merely skipped.
        assertThat(manager.regionData(region.id()).blockEntityTickerCount()).isEqualTo(1);
        assertThat(manager.regionData(region.id()).snapshotBlockEntityTickers()).containsExactly(live);
    }

    @Test
    void globalSystemsAndPerRegionBodiesBothFireLayeredOnBlockEntitiesPhase(@TempDir Path journalDir) {
        AtomicInteger globalTicks = new AtomicInteger();
        host.globalSystems().register(new net.multiforge.runtime.globals.GlobalSystem() {
            @Override
            public String name() {
                return "test-fixture";
            }

            @Override
            public void tick(net.multiforge.runtime.globals.GlobalTickContext ctx) {
                globalTicks.incrementAndGet();
            }

            @Override
            public java.util.Set<WorldRef> readSet() {
                return java.util.Set.of();
            }

            @Override
            public java.util.Set<WorldRef> writeSet() {
                return java.util.Set.of();
            }

            @Override
            public void crossRegionEffect(net.multiforge.runtime.region.RegionId dest, Runnable task) {
                // unused
            }
        });

        Region normal = host.touchChunk(WORLD, 0, 0);
        ChunkHolderManager manager = host.chunkManagerFor(WORLD);
        RecordingTicker ticker = new RecordingTicker(new BlockPos(0, 64, 0));
        manager.regionData(normal.id()).addBlockEntityTicker(ticker);

        host.installM9WiredTickBody(PhasedRegionTickBody.builder(), null, journalDir);
        RegionTickBody body = host.scheduler().body();

        // Ticking the global region: phaseGlobalSystemsTick does real work
        // (the fixture fires), phaseBlockEntitiesTickPerRegion is a no-op
        // (globalRegion never owns ordinary block-entity tickers).
        body.tickOnce(host.globalRegion());
        assertThat(globalTicks.get()).isEqualTo(1);
        assertThat(ticker.tickCount.get()).isZero();

        // Ticking the normal region: phaseGlobalSystemsTick early-returns
        // (not globalRegion), phaseBlockEntitiesTickPerRegion does real
        // work — both bodies ran (no exception, no interference) but only
        // one did anything observable for each region.
        body.tickOnce(normal);
        assertThat(globalTicks.get()).isEqualTo(1); // unchanged — global body no-opped here
        assertThat(ticker.tickCount.get()).isEqualTo(1);
    }

    @Test
    void throwingTickerDoesNotBreakOtherTickersInTheSameRegion(@TempDir Path journalDir) {
        Region region = host.touchChunk(WORLD, 0, 0);
        ChunkHolderManager manager = host.chunkManagerFor(WORLD);

        RecordingTicker before = new RecordingTicker(new BlockPos(0, 64, 0));
        ThrowingTicker throwing = new ThrowingTicker(new BlockPos(1, 64, 0));
        RecordingTicker after = new RecordingTicker(new BlockPos(2, 64, 0));
        manager.regionData(region.id()).addBlockEntityTicker(before);
        manager.regionData(region.id()).addBlockEntityTicker(throwing);
        manager.regionData(region.id()).addBlockEntityTicker(after);

        host.installM9WiredTickBody(PhasedRegionTickBody.builder(), null, journalDir);
        RegionTickBody body = host.scheduler().body();

        // The whole tickOnce call must not throw — the defensive
        // try/catch inside BlockEntityTickRunner.standard isolates the
        // throwing ticker.
        body.tickOnce(region);

        assertThat(before.tickCount.get()).isEqualTo(1);
        assertThat(after.tickCount.get()).isEqualTo(1);
        assertThat(throwing.tickAttempts.get()).isEqualTo(1);
        assertThat(ProbeRegistry.get("block-entities.ticker.failure")).isEqualTo(1L);
    }

    private static class RecordingTicker implements TickingBlockEntityRef {
        final BlockPos pos;
        final AtomicInteger tickCount = new AtomicInteger();
        volatile boolean removed;

        RecordingTicker(BlockPos pos) {
            this.pos = pos;
        }

        @Override
        public boolean shouldTick() {
            return !removed;
        }

        @Override
        public void tick() {
            tickCount.incrementAndGet();
        }

        @Override
        public boolean isRemoved() {
            return removed;
        }

        @Override
        public BlockPos pos() {
            return pos;
        }
    }

    private static final class ThrowingTicker implements TickingBlockEntityRef {
        final BlockPos pos;
        final AtomicInteger tickAttempts = new AtomicInteger();

        ThrowingTicker(BlockPos pos) {
            this.pos = pos;
        }

        @Override
        public boolean shouldTick() {
            return true;
        }

        @Override
        public void tick() {
            tickAttempts.incrementAndGet();
            throw new RuntimeException("boom — simulated mod bug in a custom BlockEntity#tick");
        }

        @Override
        public boolean isRemoved() {
            return false;
        }

        @Override
        public BlockPos pos() {
            return pos;
        }
    }
}
