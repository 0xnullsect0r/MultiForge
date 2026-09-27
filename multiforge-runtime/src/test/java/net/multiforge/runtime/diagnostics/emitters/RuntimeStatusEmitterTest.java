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
import java.util.concurrent.atomic.AtomicLong;
import net.multiforge.api.scheduler.ServerDomains;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.TickRegionScheduler;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuntimeStatusEmitterTest {

    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");

    private MultiThreadedSchedulerHost host;

    @BeforeEach
    void setUp() {
        ProbeRegistry.resetForTesting();
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(1).withThreadsPerCore(1);
        host = new MultiThreadedSchedulerHost(config);
    }

    @AfterEach
    void tearDown() {
        host.close();
        ServerDomains.resetForTesting();
        ProbeRegistry.resetForTesting();
    }

    private static DebugPayload.RuntimeStatus only(List<DebugPayload> received) {
        assertThat(received).hasSize(1);
        return (DebugPayload.RuntimeStatus) received.get(0);
    }

    @Test
    void everyLiveRegionIsListedWithItsWorldAndThread() {
        Region region = host.touchChunk(WORLD, 0, 0);
        List<DebugPayload> received = new ArrayList<>();
        new RuntimeStatusEmitter(host, received::add, PermissionFilter.ALWAYS_ALLOW).emit();

        DebugPayload.RuntimeStatus st = only(received);
        assertThat(st.worlds())
                .filteredOn(w -> w.worldId().equals(WORLD.dimensionId()))
                .singleElement()
                .satisfies(w -> assertThat(w.regionCount()).isEqualTo(1));
        // The default host runs free, so the region may or may not have
        // ticked by now: either no thread yet, or the worker that ticked it.
        assertThat(st.regions())
                .filteredOn(r -> r.regionId() == region.id().value())
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.worldId()).isEqualTo(WORLD.dimensionId());
                    if (r.thread().isEmpty()) {
                        assertThat(r.placement()).isEqualTo(DebugPayload.Placement.UNKNOWN);
                    } else {
                        assertThat(r.thread()).startsWith("multiforge-tick-");
                    }
                });
    }

    @Test
    void laneRatesAreDifferencedBetweenEmits() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        List<DebugPayload> received = new ArrayList<>();
        RuntimeStatusEmitter emitter =
                new RuntimeStatusEmitter(host, received::add, PermissionFilter.ALWAYS_ALLOW, clock::get);

        ProbeRegistry.add(RuntimeStatusEmitter.HANDOFF_PROBE, 500);
        emitter.emit();
        assertThat(((DebugPayload.RuntimeStatus) received.get(0)).laneHandoffsPerSecond())
                .isZero();

        ProbeRegistry.add(RuntimeStatusEmitter.HANDOFF_PROBE, 1000);
        ProbeRegistry.add(RuntimeStatusEmitter.INLINE_PROBE, 250);
        clock.addAndGet(500_000_000L); // half a second later
        emitter.emit();
        DebugPayload.RuntimeStatus st = (DebugPayload.RuntimeStatus) received.get(1);
        assertThat(st.laneHandoffsPerSecond()).isEqualTo(2000.0);
        assertThat(st.laneInlinePerSecond()).isEqualTo(500.0);
    }

    @Test
    void schedulerEnumsMapOntoWireEnums() {
        for (MultiThreadedSchedulerHost.WorldTickMode mode : MultiThreadedSchedulerHost.WorldTickMode.values()) {
            assertThat(RuntimeStatusEmitter.worldMode(mode).name()).isEqualTo(mode.name());
        }
        for (TickRegionScheduler.TickPlacement p : TickRegionScheduler.TickPlacement.values()) {
            assertThat(RuntimeStatusEmitter.placement(p).name()).isEqualTo(p.name());
        }
        assertThat(RuntimeStatusEmitter.placement(null)).isEqualTo(DebugPayload.Placement.UNKNOWN);
    }
}
