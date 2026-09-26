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

import java.util.ArrayList;
import java.util.List;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link MultiThreadedSchedulerHost#installRegionTickBody}: the registered
 * scheduled-tick and entity runners, and the block-entity phase, run in
 * their phases, after any caller-supplied body for the same phase, in the
 * documented phase order.
 */
class PhasedRegionTickBodyWiringTest {

    private static final WorldRef WORLD = WorldRef.of("test:region-tick-wiring");
    private static final ChunkPos POS = new ChunkPos(0, 0);

    private MultiThreadedSchedulerHost host;

    @BeforeEach
    void install() {
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(1).withThreadsPerCore(1);
        host = new MultiThreadedSchedulerHost(config);
        // Drive the body by hand; the eagerly started workers would race it.
        host.scheduler().close();
    }

    @AfterEach
    void shutdown() {
        host.close();
    }

    @Test
    void regionRunnersRunInTheirPhasesAfterTheCallersBody() {
        Region region = host.touchChunk(WORLD, POS.x(), POS.z());
        List<String> observed = new ArrayList<>();
        host.setBlockFluidRunner(r -> observed.add("runner:BLOCK_FLUID_TICKS " + (r == region)));
        host.setEntityTickRunner(r -> observed.add("runner:ENTITY_AI " + (r == region)));
        PhasedRegionTickBody.Builder userBuilder = PhasedRegionTickBody.builder()
                .inboundMailbox(r -> observed.add("user:INBOUND_MAILBOX"))
                .blockFluidTicks(r -> observed.add("user:BLOCK_FLUID_TICKS"))
                .entityAi(r -> observed.add("user:ENTITY_AI"))
                .blockEntities(r -> observed.add("user:BLOCK_ENTITIES"))
                .regionEvents(r -> observed.add("user:REGION_EVENTS"))
                .flushOutbound(r -> observed.add("user:FLUSH_OUTBOUND"));
        host.installRegionTickBody(userBuilder);

        // As a region worker would: the entity phase refuses a foreign token.
        net.multiforge.runtime.ownership.OwnerToken.runAs(
                net.multiforge.runtime.ownership.OwnerToken.forRegion(
                        region.id().value()),
                () -> host.scheduler().body().tickOnce(region));

        assertThat(observed)
                .containsExactly(
                        "user:INBOUND_MAILBOX",
                        "user:BLOCK_FLUID_TICKS",
                        "runner:BLOCK_FLUID_TICKS true",
                        "user:ENTITY_AI",
                        "runner:ENTITY_AI true",
                        "user:BLOCK_ENTITIES",
                        "user:REGION_EVENTS",
                        "user:FLUSH_OUTBOUND");
    }
}
