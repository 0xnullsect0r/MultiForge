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
 * Phase 6 (wave A, cohort 6.3) regression pin.
 *
 * <p>Vanilla {@code net.minecraft.world.level.dimension.end.EndDragonFight}
 * lines 161 and 189 write and release a permanent DRAGON ticket:
 * <pre>
 *   this.level.getChunkSource().addRegionTicket(TicketType.DRAGON, new ChunkPos(0,0), 9, Unit.INSTANCE);
 *   this.level.getChunkSource().removeRegionTicket(TicketType.DRAGON, new ChunkPos(0,0), 9, Unit.INSTANCE);
 * </pre>
 * The ticket keeps the origin chunks entity-ticking while the dragon
 * fight is live (spawn, respawn, pillar reset). Vanilla per-type
 * timeout for DRAGON is {@code 0} (permanent — sweep never expires it;
 * only the explicit removeRegionTicket clears it). This test pins the
 * routing invariant the fork's
 * {@code net.multiforge.neoforge.DistanceManagerBridge.onAddTicket}
 * relies on.
 */
class EndDragonFight_dragonTicketRoutingTest {

    private static final WorldRef WORLD = WorldRef.of("test:the_end");

    private static final String DRAGON_NAME = "dragon";

    /** DRAGON's Vanilla per-type timeout — permanent. */
    private static final int DRAGON_TIMEOUT_TICKS = 0;

    /**
     * Runtime distance the bridge writes for Vanilla
     * {@code addRegionTicket(DRAGON, pos, 9, Unit)}: Vanilla
     * {@code DistanceManager.addRegionTicket} computes
     * {@code ChunkMap.MAX_VIEW_DISTANCE + 1 - 9 = 25}. That distance
     * collapses to {@link ChunkLoadLevel#ENTITY_TICKING} via
     * {@link ChunkLoadLevel#forDistance(int)} — matching the Vanilla
     * intent that the fight origin ticks entities.
     */
    private static final int DRAGON_TICKET_LEVEL = 25;

    private static final ChunkPos ORIGIN = new ChunkPos(0, 0);

    private static final String DRAGON_KEY = "Unit.INSTANCE";

    @Test
    void dragonTicketPromotesOriginToEntityTickingUntilExplicitRemove() {
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(1).withThreadsPerCore(1);
        try (MultiThreadedSchedulerHost host = new MultiThreadedSchedulerHost(config, region -> {})) {
            host.registerChunk(WORLD, ORIGIN.x(), ORIGIN.z());
            ChunkHolderManager manager = host.chunkManagerFor(WORLD);
            ThreadedRegionizer regionizer = host.regionizerFor(WORLD);
            Region region = regionizer.regionAtChunk(ORIGIN.x(), ORIGIN.z());
            assertThat(region).isNotNull();

            TicketType dragonType = TicketType.of(DRAGON_NAME, DRAGON_TICKET_LEVEL, DRAGON_TIMEOUT_TICKS);
            Ticket dragonTicket = Ticket.at(dragonType, DRAGON_TICKET_LEVEL, DRAGON_KEY, /* createdAtTick */ 0L);

            // Line 161 flow.
            boolean added = manager.addTicket(region.id(), ORIGIN, dragonTicket);
            assertThat(added).isTrue();
            NewChunkHolder holder = manager.holderAt(ORIGIN);
            assertThat(holder).isNotNull();
            assertThat(holder.level())
                    .as("DRAGON ticket at level 25 promotes to ENTITY_TICKING")
                    .isEqualTo(ChunkLoadLevel.ENTITY_TICKING);
            assertThat(dragonType.timeoutTicks())
                    .as("DRAGON must remain permanent — timeout must be 0")
                    .isZero();

            // Permanence check: a "sweep" pass on an expiry ticker would
            // never touch a timeout=0 ticket. We simulate that by asserting
            // no state change on repeated attempted-adds and by leaving
            // the ticket present until the explicit remove.
            for (int i = 0; i < 8; i++) {
                assertThat(manager.addTicket(region.id(), ORIGIN, dragonTicket))
                        .as("idempotent add %d — DRAGON stays as one ticket", i)
                        .isFalse();
            }
            assertThat(holder.level()).isEqualTo(ChunkLoadLevel.ENTITY_TICKING);

            // Line 189 flow.
            boolean removed = manager.removeTicket(region.id(), ORIGIN, dragonTicket);
            assertThat(removed).isTrue();
            assertThat(holder.level())
                    .as("DRAGON remove drops the last ticket → INACCESSIBLE")
                    .isEqualTo(ChunkLoadLevel.INACCESSIBLE);

            // Double-remove is a safe no-op — the fight can call
            // removeRegionTicket redundantly across dragon phases.
            assertThat(manager.removeTicket(region.id(), ORIGIN, dragonTicket))
                    .as("double-remove is a safe no-op")
                    .isFalse();
        }
    }
}
