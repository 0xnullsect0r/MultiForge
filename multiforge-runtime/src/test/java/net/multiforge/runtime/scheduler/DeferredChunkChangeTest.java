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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.region.TickRegionScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A chunk that loads while a level's regions tick — a region worker handed the
 * load to the server thread and waits for it — must not change the region
 * structure mid-tick: merging a ticking region waits for its tick to end, and
 * that tick is waiting for the load.
 */
class DeferredChunkChangeTest {
    private static final WorldRef WORLD = WorldRef.of("test:deferred");
    private MultiThreadedSchedulerHost host;

    @AfterEach
    void close() {
        if (host != null) host.close();
    }

    @Test
    void aChunkLoadedDuringTheBarrierThatBridgesTwoTickingRegionsIsAppliedAfterIt() {
        CountDownLatch loaded = new CountDownLatch(1);
        AtomicBoolean regionSawBridge = new AtomicBoolean();
        // Each region "needs" the bridging chunk: it waits until the server thread loaded it.
        host = new MultiThreadedSchedulerHost(
                MultiForgeConfig.defaults().withCores(2).withThreadsPerCore(1).withRegionSize(0),
                region -> {
                    try {
                        loaded.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                TickRegionScheduler.Mode.BARRIER);
        host.chunkLoaded(WORLD, 0, 0);
        host.chunkLoaded(WORLD, 2, 0);
        ThreadedRegionizer regionizer = host.regionizerFor(WORLD);
        assertThat(regionizer.regions()).hasSize(2);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            boolean[] once = {false};
            host.driveRegions(WORLD, 5_000_000_000L, () -> {
                if (once[0]) return false;
                once[0] = true;
                // The server thread loads the chunk between the two regions.
                host.chunkLoaded(WORLD, 1, 0);
                regionSawBridge.set(regionizer.regionAtChunk(1, 0) != null);
                loaded.countDown();
                return true;
            });
        });

        assertThat(regionSawBridge.get())
                .as("no structural change during the barrier")
                .isFalse();
        Region merged = regionizer.regionAtChunk(1, 0);
        assertThat(merged).isNotNull();
        assertThat(regionizer.regions()).containsExactly(merged);
        assertThat(host.chunkManagerFor(WORLD).holderAt(new net.multiforge.api.world.ChunkPos(1, 0)))
                .isNotNull();
    }
}
