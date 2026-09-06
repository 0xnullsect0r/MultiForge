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
package net.multiforge.runtime.chunk.regression;

import static org.assertj.core.api.Assertions.assertThat;

import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.ChunkLoadLevel;
import net.multiforge.runtime.chunk.NewChunkHolder;
import net.multiforge.runtime.chunk.Ticket;
import net.multiforge.runtime.chunk.TicketType;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import org.junit.jupiter.api.Test;

/**
 * Phase 6 (wave A, cohort 6.2) regression pin.
 *
 * <p>Vanilla {@code net.minecraft.server.level.ServerLevel#setDefaultSpawnPos}
 * at lines 1380 and 1385 rewrites the spawn-radius START ticket:
 * <pre>
 *   getChunkSource().removeRegionTicket(TicketType.START, new ChunkPos(oldSpawn), lastRadius, Unit.INSTANCE);
 *   ...
 *   getChunkSource().addRegionTicket(TicketType.START, new ChunkPos(newSpawn), radius, Unit.INSTANCE);
 * </pre>
 * The START ticket keeps the spawn chunks resident permanently (Vanilla
 * per-type timeout {@code 0}). Under M9 the same START ticket-type is
 * additionally the one fired by
 * {@code net.multiforge.neoforge.RegionizedChunkLifecycle.onChunkLoaded}
 * (Phase 5 task 5.6 — no more shadow-only writes) so this test also
 * pins the promotion invariant the region lifecycle relies on.
 *
 * <p>Vanilla's spawn-radius default is 2. The runtime side of a
 * {@code addRegionTicket(START, pos, 2, Unit)} lands at Vanilla ticket
 * level {@code MAX_VIEW_DISTANCE + 1 - 2 = 32}, which the runtime
 * {@link ChunkLoadLevel#forDistance(int)} ladder collapses to
 * {@link ChunkLoadLevel#TICKING} — matching Vanilla's intent that the
 * spawn chunks run block ticks.
 */
class ServerLevel_startTicketRoutingTest {

    private static final WorldRef WORLD = WorldRef.of("test:spawn_radius");

    private static final String START_NAME = "start";

    /** START's Vanilla per-type timeout — permanent. */
    private static final int START_TIMEOUT_TICKS = 0;

    /** Runtime distance for {@code addRegionTicket(START, pos, 2, Unit)} — TICKING. */
    private static final int START_TICKET_LEVEL_RADIUS_2 = 32;

    /** Runtime distance for {@code addRegionTicket(START, pos, 3, Unit)} — ENTITY_TICKING (larger radius). */
    private static final int START_TICKET_LEVEL_RADIUS_3 = 31;

    private static final ChunkPos SPAWN = new ChunkPos(0, 0);

    @Test
    void startTicketRewriteOnSpawnRadiusChange() {
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(1).withThreadsPerCore(1);
        try (MultiThreadedSchedulerHost host = new MultiThreadedSchedulerHost(config, region -> {})) {
            host.registerChunk(WORLD, SPAWN.x(), SPAWN.z());
            ChunkHolderManager manager = host.chunkManagerFor(WORLD);
            ThreadedRegionizer regionizer = host.regionizerFor(WORLD);
            Region region = regionizer.regionAtChunk(SPAWN.x(), SPAWN.z());
            assertThat(region).isNotNull();

            TicketType startType = TicketType.of(START_NAME, START_TICKET_LEVEL_RADIUS_2, START_TIMEOUT_TICKS);
            Ticket radius2 = Ticket.at(startType, START_TICKET_LEVEL_RADIUS_2, "Unit.INSTANCE", 0L);

            // First landing (mirrors both /setworldspawn and the Phase 5.6
            // RegionizedChunkLifecycle.onChunkLoaded START seed).
            assertThat(manager.addTicket(region.id(), SPAWN, radius2)).isTrue();
            NewChunkHolder holder = manager.holderAt(SPAWN);
            assertThat(holder).isNotNull();
            assertThat(holder.level())
                    .as("START at level 32 promotes spawn chunk to TICKING")
                    .isEqualTo(ChunkLoadLevel.TICKING);
            assertThat(startType.timeoutTicks())
                    .as("START must be permanent — TicketExpiryTicker never touches it")
                    .isZero();

            // Sim /setworldspawn: remove the old radius, add the new one.
            assertThat(manager.removeTicket(region.id(), SPAWN, radius2)).isTrue();
            assertThat(holder.level())
                    .as("removeRegionTicket demotes the previous spawn ticket")
                    .isEqualTo(ChunkLoadLevel.INACCESSIBLE);

            TicketType largerType = TicketType.of(START_NAME, START_TICKET_LEVEL_RADIUS_3, START_TIMEOUT_TICKS);
            Ticket radius3 = Ticket.at(largerType, START_TICKET_LEVEL_RADIUS_3, "Unit.INSTANCE", 0L);
            assertThat(manager.addTicket(region.id(), SPAWN, radius3)).isTrue();
            assertThat(holder.level())
                    .as("larger START radius (level 31) promotes to ENTITY_TICKING")
                    .isEqualTo(ChunkLoadLevel.ENTITY_TICKING);
        }
    }
}
