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
import net.multiforge.runtime.chunk.Ticket;
import net.multiforge.runtime.chunk.TicketType;
import net.multiforge.runtime.region.RegionId;
import org.junit.jupiter.api.Test;

/**
 * Phase 6 cohort 6.8 regression — {@code ForceLoadCommand}.
 *
 * <p>The forceload command flows through Vanilla
 * {@code ServerLevel.setChunkForced} → {@code ServerChunkCache.updateChunkForced}
 * → {@code DistanceManager.updateChunkForced}, which builds a
 * {@code Ticket<>(TicketType.FORCED, ChunkMap.FORCED_TICKET_LEVEL, pos)}
 * where {@code FORCED_TICKET_LEVEL == ChunkLevel.byStatus(ENTITY_TICKING) == 31}.
 * The Phase 4.1c inner-class {@code addTicket} override on
 * {@code ChunkMap.DistanceManagerImpl} then mirrors that write into
 * {@link ChunkHolderManager#addTicket} through
 * {@code DistanceManagerBridge.onAddTicket}, which translates via
 * {@code Ticket.at(mfType, 31, key, createdTick)} (level 31 explicit).
 *
 * <p>This regression pins the invariant that a Vanilla-shaped forceload
 * ticket (explicit level 31) routes to a MultiForge effective level of
 * {@link ChunkLoadLevel#ENTITY_TICKING} — the same load status Vanilla
 * would grant. Complements {@code ForceloadIntegrationTest} in the
 * parent package, which exercises the MultiForge-native default distance
 * on {@link TicketType#FORCED}.
 */
class Cohort68ForceloadTicketRoutingTest {

    private static final WorldRef WORLD = WorldRef.of("test:cohort68");

    /** Vanilla {@code ChunkMap.FORCED_TICKET_LEVEL}. */
    private static final int VANILLA_FORCED_LEVEL = 31;

    @Test
    void vanillaShapedForcedTicketPromotesHolderToEntityTicking() {
        ChunkHolderManager manager = new ChunkHolderManager(WORLD);
        RegionId region = RegionId.next();
        ChunkPos pos = new ChunkPos(4, 4);
        manager.createHolder(pos, region);

        // Replay what DistanceManagerBridge.toMultiForgeTicket produces
        // for a Vanilla /forceload write: explicit level == 31, key
        // derived from the source ticket's identity.
        TicketType mfForced = TicketType.of("forced", VANILLA_FORCED_LEVEL);
        Ticket ticket = Ticket.at(mfForced, VANILLA_FORCED_LEVEL, "forceload:" + pos);
        assertThat(manager.addTicket(region, pos, ticket)).isTrue();

        ChunkLoadLevel effective = manager.ticketsFor(region).effectiveLevel(pos);
        assertThat(effective)
                .as("Vanilla-shaped forceload ticket (level %d) must promote to ENTITY_TICKING", VANILLA_FORCED_LEVEL)
                .isEqualTo(ChunkLoadLevel.ENTITY_TICKING);

        // Removing the ticket returns the holder to INACCESSIBLE, matching
        // Vanilla's /forceload remove semantic.
        assertThat(manager.removeTicket(region, pos, ticket)).isTrue();
        assertThat(manager.ticketsFor(region).effectiveLevel(pos)).isEqualTo(ChunkLoadLevel.INACCESSIBLE);
    }

    @Test
    void forcedRewriteIsIdempotentAcrossAddAdd() {
        // Vanilla /forceload add on an already-forced chunk should be a
        // no-op. The ticket record's equals uses (type,distance,key), so
        // re-adding the exact ticket returns false at the second write.
        ChunkHolderManager manager = new ChunkHolderManager(WORLD);
        RegionId region = RegionId.next();
        ChunkPos pos = new ChunkPos(-3, 7);
        manager.createHolder(pos, region);

        Ticket first =
                Ticket.at(TicketType.of("forced", VANILLA_FORCED_LEVEL), VANILLA_FORCED_LEVEL, "forceload:" + pos);
        Ticket second =
                Ticket.at(TicketType.of("forced", VANILLA_FORCED_LEVEL), VANILLA_FORCED_LEVEL, "forceload:" + pos);

        assertThat(manager.addTicket(region, pos, first)).isTrue();
        assertThat(manager.addTicket(region, pos, second))
                .as("duplicate FORCED write must be idempotent — the second /forceload add is a no-op")
                .isFalse();
        assertThat(manager.ticketsFor(region).effectiveLevel(pos)).isEqualTo(ChunkLoadLevel.ENTITY_TICKING);
    }
}
