/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.region;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import org.junit.jupiter.api.Test;

class PhasedRegionTickBodyTest {

    private static final WorldRef WORLD = WorldRef.of("test:world");

    @Test
    void phasesRunInDocumentedOrder() {
        List<PhasedRegionTickBody.Phase> observed = new ArrayList<>();
        PhasedRegionTickBody body = PhasedRegionTickBody.builder()
                .inboundMailbox(r -> observed.add(PhasedRegionTickBody.Phase.INBOUND_MAILBOX))
                .blockFluidTicks(r -> observed.add(PhasedRegionTickBody.Phase.BLOCK_FLUID_TICKS))
                .entityAi(r -> observed.add(PhasedRegionTickBody.Phase.ENTITY_AI))
                .blockEntities(r -> observed.add(PhasedRegionTickBody.Phase.BLOCK_ENTITIES))
                .regionEvents(r -> observed.add(PhasedRegionTickBody.Phase.REGION_EVENTS))
                .flushOutbound(r -> observed.add(PhasedRegionTickBody.Phase.FLUSH_OUTBOUND))
                .build();

        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region region = regionizer.addChunk(new ChunkPos(0, 0));
        body.tickOnce(region);

        assertThat(observed)
                .containsExactly(
                        PhasedRegionTickBody.Phase.INBOUND_MAILBOX,
                        PhasedRegionTickBody.Phase.BLOCK_FLUID_TICKS,
                        PhasedRegionTickBody.Phase.ENTITY_AI,
                        PhasedRegionTickBody.Phase.BLOCK_ENTITIES,
                        PhasedRegionTickBody.Phase.REGION_EVENTS,
                        PhasedRegionTickBody.Phase.FLUSH_OUTBOUND);
    }

    @Test
    void unwiredPhasesAreSilentNoOps() {
        List<PhasedRegionTickBody.Phase> observed = new ArrayList<>();
        PhasedRegionTickBody body = PhasedRegionTickBody.builder()
                .entityAi(r -> observed.add(PhasedRegionTickBody.Phase.ENTITY_AI))
                .build();

        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region region = regionizer.addChunk(new ChunkPos(0, 0));
        body.tickOnce(region);

        assertThat(observed).containsExactly(PhasedRegionTickBody.Phase.ENTITY_AI);
    }

    @Test
    void setNullClearsAPreviouslyWiredPhase() {
        List<PhasedRegionTickBody.Phase> observed = new ArrayList<>();
        PhasedRegionTickBody body = PhasedRegionTickBody.builder()
                .entityAi(r -> observed.add(PhasedRegionTickBody.Phase.ENTITY_AI))
                .set(PhasedRegionTickBody.Phase.ENTITY_AI, null)
                .build();

        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region region = regionizer.addChunk(new ChunkPos(0, 0));
        body.tickOnce(region);

        assertThat(observed).isEmpty();
    }
}
