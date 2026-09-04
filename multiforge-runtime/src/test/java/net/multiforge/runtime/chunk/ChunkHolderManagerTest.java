/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.RegionId;
import org.junit.jupiter.api.Test;

class ChunkHolderManagerTest {

    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");

    @Test
    void addingPlayerTicketPromotesHolderToEntityTicking() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId r = new RegionId(1);
        ChunkPos pos = new ChunkPos(0, 0);
        m.createHolder(pos, r);

        m.addTicket(r, pos, Ticket.of(TicketType.PLAYER, "p1"));
        assertThat(m.holderAt(pos).level()).isEqualTo(ChunkLoadLevel.ENTITY_TICKING);
        assertThat(m.regionData(r).pendingFullLoadCount()).isEqualTo(1);
    }

    @Test
    void removingLastTicketDemotesHolderToInaccessible() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId r = new RegionId(2);
        ChunkPos pos = new ChunkPos(5, 5);

        Ticket ticket = Ticket.of(TicketType.PLUGIN, "held");
        m.addTicket(r, pos, ticket);
        assertThat(m.holderAt(pos).level()).isEqualTo(ChunkLoadLevel.BORDER);

        m.removeTicket(r, pos, ticket);
        assertThat(m.holderAt(pos).level()).isEqualTo(ChunkLoadLevel.INACCESSIBLE);
    }

    @Test
    void mergeMovesRegionDataAndReassignsOwnership() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId src = new RegionId(3);
        RegionId tgt = new RegionId(4);
        ChunkPos pos = new ChunkPos(1, 1);

        m.createHolder(pos, src);
        m.addTicket(src, pos, Ticket.of(TicketType.PLUGIN, "k"));
        m.markDirty(src, pos);

        m.onRegionMerged(tgt, src);
        assertThat(m.holderAt(pos).owningRegion()).isEqualTo(tgt);
        assertThat(m.regionData(tgt).autoSaveCount()).isEqualTo(1);
        assertThat(m.ticketsFor(tgt).chunkCount()).isEqualTo(1);
    }

    @Test
    void splitPeelsOffMatchingChunks() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId src = new RegionId(5);
        RegionId tgt = new RegionId(6);
        ChunkPos stay = new ChunkPos(0, 0);
        ChunkPos leave = new ChunkPos(10, 10);

        m.createHolder(stay, src);
        m.createHolder(leave, src);
        m.addTicket(src, stay, Ticket.of(TicketType.PLUGIN, "stay"));
        m.addTicket(src, leave, Ticket.of(TicketType.PLUGIN, "leave"));

        m.onRegionSplit(src, tgt, p -> p.equals(leave));
        assertThat(m.holderAt(stay).owningRegion()).isEqualTo(src);
        assertThat(m.holderAt(leave).owningRegion()).isEqualTo(tgt);
        assertThat(m.ticketsFor(src).chunkCount()).isEqualTo(1);
        assertThat(m.ticketsFor(tgt).chunkCount()).isEqualTo(1);
    }
}
