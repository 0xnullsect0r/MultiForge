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

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.globals.GlobalSystem;
import net.multiforge.runtime.globals.GlobalTickContext;
import net.multiforge.runtime.region.PhasedRegionTickBody;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionId;
import net.multiforge.runtime.region.RegionTickBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies B1.1's wiring: {@link
 * MultiThreadedSchedulerHost#installM9WiredTickBody} routes the {@code
 * BLOCK_ENTITIES} phase to {@link
 * net.multiforge.runtime.globals.GlobalSystems#tickAll(GlobalTickContext)}
 * only when the ticked region is {@link
 * MultiThreadedSchedulerHost#globalRegion()}, and that a throwing {@link
 * GlobalSystem} does not break isolation between registered systems (see
 * {@code docs/design/global-region.md} §2.3/§7.1).
 */
class MultiThreadedSchedulerHostGlobalTickTest {

    private static final WorldRef WORLD = WorldRef.of("test:global-tick-wiring");
    private static final ChunkPos POS = new ChunkPos(0, 0);

    private MultiThreadedSchedulerHost host;

    @BeforeEach
    void install() {
        ProbeRegistry.resetForTesting();
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(1).withThreadsPerCore(1);
        host = new MultiThreadedSchedulerHost(config);
        // Stop the autonomous worker pool so tests can drive body.tickOnce
        // by hand without racing it — same pattern as
        // PhasedRegionTickBodyWiringTest.
        host.scheduler().close();
    }

    @AfterEach
    void shutdown() {
        host.close();
        ProbeRegistry.resetForTesting();
    }

    private static GlobalSystem recording(String name, AtomicInteger counter, AtomicInteger lastTick) {
        return new GlobalSystem() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void tick(GlobalTickContext ctx) {
                counter.incrementAndGet();
                lastTick.set((int) ctx.globalTick());
            }

            @Override
            public Set<WorldRef> readSet() {
                return Set.of();
            }

            @Override
            public Set<WorldRef> writeSet() {
                return Set.of();
            }

            @Override
            public void crossRegionEffect(RegionId dest, Runnable task) {
                // unused by this fixture
            }
        };
    }

    @Test
    void globalSystemTicksOnceWhenGlobalRegionTicksAndNotOnOtherRegions(@TempDir Path journalDir) {
        AtomicInteger tickCount = new AtomicInteger();
        AtomicInteger lastTick = new AtomicInteger(-1);
        GlobalSystem fake = recording("test-fake", tickCount, lastTick);
        host.globalSystems().register(fake);

        host.installM9WiredTickBody(PhasedRegionTickBody.builder(), null, journalDir);
        RegionTickBody body = host.scheduler().body();

        // Ticking the global region invokes the fake system exactly once,
        // with a GlobalTickContext carrying the global region's id.
        body.tickOnce(host.globalRegion());
        assertThat(tickCount.get()).isEqualTo(1);
        assertThat(lastTick.get()).isEqualTo((int) host.globalSystems().currentTick());

        // Ticking a normal, non-global region does not touch the fake
        // system at all — the BLOCK_ENTITIES slot no-ops there.
        Region normal = host.touchChunk(WORLD, POS.x(), POS.z());
        body.tickOnce(normal);
        assertThat(tickCount.get()).isEqualTo(1);

        // A second global-region tick fires it again.
        body.tickOnce(host.globalRegion());
        assertThat(tickCount.get()).isEqualTo(2);
    }

    @Test
    void throwingGlobalSystemDoesNotBreakOthers(@TempDir Path journalDir) {
        GlobalSystem throwing = new GlobalSystem() {
            @Override
            public String name() {
                return "throwing-fixture";
            }

            @Override
            public void tick(GlobalTickContext ctx) {
                throw new RuntimeException("boom");
            }

            @Override
            public Set<WorldRef> readSet() {
                return Set.of();
            }

            @Override
            public Set<WorldRef> writeSet() {
                return Set.of();
            }

            @Override
            public void crossRegionEffect(RegionId dest, Runnable task) {
                // unused
            }
        };
        AtomicInteger okCount = new AtomicInteger();
        GlobalSystem recording = recording("recording-fixture", okCount, new AtomicInteger());

        host.globalSystems().register(throwing);
        host.globalSystems().register(recording);

        host.installM9WiredTickBody(PhasedRegionTickBody.builder(), null, journalDir);
        RegionTickBody body = host.scheduler().body();

        body.tickOnce(host.globalRegion());

        // Isolation held: the later-registered system still ran.
        assertThat(okCount.get()).isEqualTo(1);
        // Probe bump + no propagation past tickAll (tickOnce itself didn't throw).
        assertThat(ProbeRegistry.get("global.system.failure.throwing-fixture")).isEqualTo(1L);
    }
}
