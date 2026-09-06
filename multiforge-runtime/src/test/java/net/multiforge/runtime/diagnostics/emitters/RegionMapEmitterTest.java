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
package net.multiforge.runtime.diagnostics.emitters;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import net.multiforge.api.scheduler.ServerDomains;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RegionMapEmitterTest {

    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");

    private MultiThreadedSchedulerHost host;

    @BeforeEach
    void setUp() {
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(1).withThreadsPerCore(1);
        host = new MultiThreadedSchedulerHost(config);
    }

    @AfterEach
    void tearDown() {
        host.close();
        ServerDomains.resetForTesting();
    }

    @Test
    void emitIncludesEveryLiveRegionAcrossWorlds() {
        Region region = host.touchChunk(WORLD, 0, 0);
        List<DebugPayload> received = new ArrayList<>();
        RegionMapEmitter emitter = new RegionMapEmitter(host, id -> 0, received::add, PermissionFilter.ALWAYS_ALLOW);

        emitter.emit();

        assertThat(received).hasSize(1);
        DebugPayload.RegionSnapshot snapshot = (DebugPayload.RegionSnapshot) received.get(0);
        // At least the touched-chunk region + the always-live global region.
        assertThat(snapshot.regions()).isNotEmpty();
        assertThat(snapshot.regions()).anySatisfy(stat -> assertThat(stat.regionId())
                .isEqualTo(region.id().value()));
        assertThat(snapshot.regions())
                .allSatisfy(stat -> assertThat(stat.sectionCount()).isGreaterThanOrEqualTo(1));
    }

    @Test
    void ownedEntityCounterIsConsulted() {
        Region region = host.touchChunk(WORLD, 0, 0);
        List<DebugPayload> received = new ArrayList<>();
        RegionMapEmitter emitter = new RegionMapEmitter(
                host, id -> id.equals(region.id()) ? 7 : 0, received::add, PermissionFilter.ALWAYS_ALLOW);

        emitter.emit();

        DebugPayload.RegionSnapshot snapshot = (DebugPayload.RegionSnapshot) received.get(0);
        assertThat(snapshot.regions())
                .filteredOn(stat -> stat.regionId() == region.id().value())
                .first()
                .satisfies(stat -> assertThat(stat.ownedEntities()).isEqualTo(7));
    }

    @Test
    void goldenThreeEmitTicksProduceThreePayloads() {
        host.touchChunk(WORLD, 0, 0);
        List<DebugPayload> received = new ArrayList<>();
        RegionMapEmitter emitter = new RegionMapEmitter(host, id -> 0, received::add, PermissionFilter.ALWAYS_ALLOW);

        emitter.emit();
        emitter.emit();
        emitter.emit();

        assertThat(received).hasSize(3);
        assertThat(received).allSatisfy(p -> assertThat(p).isInstanceOf(DebugPayload.RegionSnapshot.class));
    }

    @Test
    void installWiresIntoScheduleGlobal() throws Exception {
        host.touchChunk(WORLD, 0, 0);
        List<DebugPayload> received = new ArrayList<>();
        AutoCloseable handle = RegionMapEmitter.install(host, received::add, PermissionFilter.ALWAYS_ALLOW);
        try {
            org.awaitility.Awaitility.await()
                    .atMost(java.time.Duration.ofSeconds(2))
                    .until(() -> received.size() >= 2);
        } finally {
            handle.close();
        }
        assertThat(received).isNotEmpty();
    }
}
