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
import net.multiforge.api.scheduler.ServerDomains;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.diagnostics.ChunkCost;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TpsHistogramEmitterTest {

    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");
    private static final WorldRef UNTOUCHED_WORLD = WorldRef.of("minecraft:the_nether");

    private MultiThreadedSchedulerHost host;

    @BeforeEach
    void setUp() {
        // regionSize = 0 -> sectionChunkShift 0 -> 1 chunk per section, so a
        // touched chunk maps 1:1 onto the ChunkHeat sample's coordinates.
        MultiForgeConfig config =
                MultiForgeConfig.defaults().withCores(1).withThreadsPerCore(1).withRegionSize(0);
        host = new MultiThreadedSchedulerHost(config);
    }

    @AfterEach
    void tearDown() {
        ChunkCost.resetForTesting();
        host.close();
        ServerDomains.resetForTesting();
    }

    @Test
    void emitProducesOneHeatSamplePerOwnedSection() {
        host.touchChunk(WORLD, 3, -4);
        List<DebugPayload> received = new ArrayList<>();
        TpsHistogramEmitter emitter =
                new TpsHistogramEmitter(host, WORLD, received::add, PermissionFilter.ALWAYS_ALLOW);

        emitter.emit();

        assertThat(received).hasSize(1);
        DebugPayload.HeatmapUpdate update = (DebugPayload.HeatmapUpdate) received.get(0);
        assertThat(update.worldId()).isEqualTo("minecraft:overworld");
        assertThat(update.heats()).hasSize(1);
        assertThat(update.heats().get(0).chunkX()).isEqualTo(3);
        assertThat(update.heats().get(0).chunkZ()).isEqualTo(-4);
    }

    @Test
    void emitOnUntouchedWorldProducesEmptyHeatmapWithoutThrowing() {
        List<DebugPayload> received = new ArrayList<>();
        TpsHistogramEmitter emitter =
                new TpsHistogramEmitter(host, UNTOUCHED_WORLD, received::add, PermissionFilter.ALWAYS_ALLOW);

        emitter.emit();

        DebugPayload.HeatmapUpdate update = (DebugPayload.HeatmapUpdate) received.get(0);
        assertThat(update.worldId()).isEqualTo("minecraft:the_nether");
        assertThat(update.heats()).isEmpty();
    }

    @Test
    void goldenThreeEmitTicksProduceThreePayloads() {
        host.touchChunk(WORLD, 0, 0);
        List<DebugPayload> received = new ArrayList<>();
        TpsHistogramEmitter emitter =
                new TpsHistogramEmitter(host, WORLD, received::add, PermissionFilter.ALWAYS_ALLOW);

        emitter.emit();
        emitter.emit();
        emitter.emit();

        assertThat(received).hasSize(3);
        assertThat(received).allSatisfy(p -> assertThat(p).isInstanceOf(DebugPayload.HeatmapUpdate.class));
    }

    @Test
    void eachChunkCarriesItsOwnCostAndTheRegionAverageBesideIt() {
        // Adjacent one-chunk sections merge: both chunks are one region.
        host.touchChunk(WORLD, 0, 0);
        host.touchChunk(WORLD, 1, 0);
        ChunkCost.setEnabled(true);
        for (int tick = 0; tick < 4; tick++) {
            ChunkCost.beginLevelTick(WORLD.dimensionId());
            ChunkCost.add(0, 0, 2_000_000); // 2 ms a tick: the base
            ChunkCost.add(1, 0, 10_000); // 0.01 ms a tick: wilderness
            ChunkCost.flush(WORLD.dimensionId());
            ChunkCost.endLevelTick();
        }
        List<DebugPayload> received = new ArrayList<>();
        new TpsHistogramEmitter(host, WORLD, received::add, PermissionFilter.ALWAYS_ALLOW).emit();

        DebugPayload.HeatmapUpdate update = (DebugPayload.HeatmapUpdate) received.get(0);
        DebugPayload.ChunkHeat base = heatAt(update, 0, 0);
        DebugPayload.ChunkHeat wild = heatAt(update, 1, 0);
        assertThat(base.heatMspt()).isCloseTo(2.0f, org.assertj.core.data.Offset.offset(1e-4f));
        assertThat(wild.heatMspt()).isCloseTo(0.01f, org.assertj.core.data.Offset.offset(1e-5f));
        assertThat(base.regionMspt()).isEqualTo(wild.regionMspt());
    }

    @Test
    void aChunkThatStopsCostingDecaysAndAnUnmeasuredDrainChangesNothing() {
        host.touchChunk(WORLD, 0, 0);
        ChunkCost.setEnabled(true);
        ChunkCost.beginLevelTick(WORLD.dimensionId());
        ChunkCost.add(0, 0, 1_000_000);
        ChunkCost.flush(WORLD.dimensionId());
        ChunkCost.endLevelTick();
        List<DebugPayload> received = new ArrayList<>();
        TpsHistogramEmitter emitter =
                new TpsHistogramEmitter(host, WORLD, received::add, PermissionFilter.ALWAYS_ALLOW);
        emitter.emit();
        assertThat(heatAt(last(received), 0, 0).heatMspt()).isEqualTo(1.0f);

        emitter.emit(); // no tick measured since: unchanged
        assertThat(heatAt(last(received), 0, 0).heatMspt()).isEqualTo(1.0f);

        ChunkCost.beginLevelTick(WORLD.dimensionId()); // a tick in which the chunk cost nothing
        ChunkCost.endLevelTick();
        emitter.emit();
        assertThat(heatAt(last(received), 0, 0).heatMspt())
                .isCloseTo((float) (1.0 - TpsHistogramEmitter.SMOOTHING), org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void aChunkNeverMeasuredIsZero() {
        host.touchChunk(WORLD, 5, 5);
        List<DebugPayload> received = new ArrayList<>();
        new TpsHistogramEmitter(host, WORLD, received::add, PermissionFilter.ALWAYS_ALLOW).emit();
        assertThat(heatAt(last(received), 5, 5).heatMspt()).isZero();
    }

    private static DebugPayload.HeatmapUpdate last(List<DebugPayload> received) {
        return (DebugPayload.HeatmapUpdate) received.get(received.size() - 1);
    }

    private static DebugPayload.ChunkHeat heatAt(DebugPayload.HeatmapUpdate update, int x, int z) {
        return update.heats().stream()
                .filter(h -> h.chunkX() == x && h.chunkZ() == z)
                .findFirst()
                .orElseThrow();
    }
}
