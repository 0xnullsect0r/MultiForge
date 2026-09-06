/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.multiforge.runtime.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage for the B3.2 {@code BLOCK_FLUID_TICKS} wiring (docs/design/
 * m13-b3-region-tick.md §5.1): {@link
 * MultiThreadedSchedulerHost#setBlockFluidRunner} plumbs a {@link
 * ScheduledTickRunner} into the {@code BLOCK_FLUID_TICKS} phase slot that
 * {@link MultiThreadedSchedulerHost#installM9WiredTickBody} wires. Extends
 * the {@code PhasedRegionTickBodyWiringTest} fixture pattern (worker pool
 * shut down up-front, tests drive {@code body.tickOnce(region)} by hand).
 */
class PhasedRegionTickBody_BlockFluidTicksTest {

    private static final WorldRef WORLD = WorldRef.of("test:block-fluid-ticks");

    private MultiThreadedSchedulerHost host;

    @BeforeEach
    void install() {
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(1).withThreadsPerCore(1);
        host = new MultiThreadedSchedulerHost(config);
        // Same rationale as PhasedRegionTickBodyWiringTest#install: the
        // scheduler's worker pool starts eagerly and would race a
        // hand-driven body.tickOnce below.
        host.scheduler().close();
    }

    @AfterEach
    void shutdown() {
        host.close();
    }

    /** Records one (regionId, ownedChunkCount) pair per runBlockFluidTicks invocation. */
    private static final class RecordingRunner implements ScheduledTickRunner {
        final List<RegionId> regionsSeen = new ArrayList<>();
        final List<Integer> chunkCountsSeen = new ArrayList<>();

        @Override
        public void runBlockFluidTicks(Region region) {
            regionsSeen.add(region.id());
            chunkCountsSeen.add(region.ownedChunkSnapshot().size());
        }
    }

    @Test
    void runnerInvokedOncePerTickWithRegionsOwnedChunks(@TempDir Path journalDir) {
        // Three chunks close enough together to land in the same region
        // under the default (small) test region size.
        Region region = host.touchChunk(WORLD, 0, 0);
        Region same1 = host.touchChunk(WORLD, 0, 1);
        Region same2 = host.touchChunk(WORLD, 1, 0);
        assertThat(same1.id()).isEqualTo(region.id());
        assertThat(same2.id()).isEqualTo(region.id());
        assertThat(region.ownedChunkSnapshot()).hasSize(3);

        RecordingRunner runner = new RecordingRunner();
        host.setBlockFluidRunner(runner);
        host.installM9WiredTickBody(PhasedRegionTickBody.builder(), null, journalDir);

        RegionTickBody body = host.scheduler().body();
        body.tickOnce(region);

        assertThat(runner.regionsSeen).containsExactly(region.id());
        assertThat(runner.chunkCountsSeen).containsExactly(3);
    }

    @Test
    void phaseNeverInvokesRunnerForAForeignRegion(@TempDir Path journalDir) {
        Region regionA = host.touchChunk(WORLD, 0, 0);
        // Far enough away to land in a distinct region from regionA.
        Region regionB = host.touchChunk(WORLD, 10_000, 10_000);
        assertThat(regionB.id()).isNotEqualTo(regionA.id());

        RecordingRunner runner = new RecordingRunner();
        host.setBlockFluidRunner(runner);
        host.installM9WiredTickBody(PhasedRegionTickBody.builder(), null, journalDir);

        RegionTickBody body = host.scheduler().body();
        body.tickOnce(regionA);

        // Only regionA's tick ran — the runner must never see regionB.
        assertThat(runner.regionsSeen).containsExactly(regionA.id());
        assertThat(runner.regionsSeen).doesNotContain(regionB.id());
    }

    @Test
    void throwingRunnerIsCaughtAndTickContinues(@TempDir Path journalDir) {
        Region region = host.touchChunk(WORLD, 0, 0);
        long before = ProbeRegistry.get("region-tick.block-fluid.failure");

        List<PhasedRegionTickBody.Phase> observed = new ArrayList<>();
        PhasedRegionTickBody.Builder userBuilder = PhasedRegionTickBody.builder()
                .regionEvents(r -> observed.add(PhasedRegionTickBody.Phase.REGION_EVENTS));
        host.setBlockFluidRunner(r -> {
            throw new RuntimeException("boom — simulated fork-bridge failure");
        });
        host.installM9WiredTickBody(userBuilder, null, journalDir);

        RegionTickBody body = host.scheduler().body();
        // The throwing runner must not propagate out of tickOnce, and later
        // phases (here, the user-supplied REGION_EVENTS body) still run.
        assertThatCode(() -> body.tickOnce(region)).doesNotThrowAnyException();

        assertThat(observed).containsExactly(PhasedRegionTickBody.Phase.REGION_EVENTS);
        assertThat(ProbeRegistry.get("region-tick.block-fluid.failure")).isEqualTo(before + 1);
    }
}
