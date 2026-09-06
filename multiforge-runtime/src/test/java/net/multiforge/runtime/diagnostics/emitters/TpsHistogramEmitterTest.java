/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.diagnostics.emitters;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import net.multiforge.api.scheduler.ServerDomains;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.config.MultiForgeConfig;
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
}
