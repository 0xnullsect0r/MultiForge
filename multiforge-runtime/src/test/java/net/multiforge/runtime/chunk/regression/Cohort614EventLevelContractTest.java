/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
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
 * Phase 6 cohort 6.14 regression — {@code ChunkTicketLevelUpdatedEvent}
 * level-transition parity.
 *
 * <p>NeoForge fires {@code ChunkTicketLevelUpdatedEvent} from Vanilla
 * {@code ChunkMap.updateChunkScheduling} with {@code oldTicketLevel} and
 * {@code newTicketLevel}. Mods rely on the semantic mapping between the
 * Vanilla level int and the ticked/loaded chunk status — e.g. crossing
 * {@code ChunkLevel.byStatus(FULL) == 33} means the chunk becomes
 * inaccessible, crossing {@code byStatus(BLOCK_TICKING) == 32} means
 * block ticks stop, crossing {@code byStatus(ENTITY_TICKING) == 31}
 * means entity ticks stop.
 *
 * <p>MultiForge's {@link ChunkLoadLevel#forDistance(int)} must produce
 * the same status boundaries so that downstream event consumers see the
 * same "chunk crossed the border" moments whether they read Vanilla's
 * post-event {@code chunkHolder.getFullStatus()} or MultiForge's
 * {@code ChunkHolderManager.ticketsFor(...).effectiveLevel(...)}. This
 * regression pins those four boundary levels.
 */
class Cohort614EventLevelContractTest {

    /** Vanilla {@code ChunkLevel.byStatus(FullChunkStatus.FULL)}. */
    private static final int VANILLA_FULL_LEVEL = 33;

    /** Vanilla {@code ChunkLevel.byStatus(FullChunkStatus.BLOCK_TICKING)}. */
    private static final int VANILLA_BLOCK_TICKING_LEVEL = 32;

    /** Vanilla {@code ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING)}. */
    private static final int VANILLA_ENTITY_TICKING_LEVEL = 31;

    @Test
    void vanillaLevelIntsMapToMatchingMultiForgeStatus() {
        // The four canonical Vanilla boundaries — these are the levels a
        // ChunkTicketLevelUpdatedEvent listener reads from the event
        // payload and matches against ChunkLevel.isLoaded / .isTicking /
        // .isEntityTicking predicates.
        assertThat(ChunkLoadLevel.forDistance(VANILLA_FULL_LEVEL)).isEqualTo(ChunkLoadLevel.BORDER);
        assertThat(ChunkLoadLevel.forDistance(VANILLA_BLOCK_TICKING_LEVEL)).isEqualTo(ChunkLoadLevel.TICKING);
        assertThat(ChunkLoadLevel.forDistance(VANILLA_ENTITY_TICKING_LEVEL)).isEqualTo(ChunkLoadLevel.ENTITY_TICKING);
        assertThat(ChunkLoadLevel.forDistance(VANILLA_FULL_LEVEL + 1)).isEqualTo(ChunkLoadLevel.INACCESSIBLE);
    }

    @Test
    void crossingBorderThresholdIsSymmetricOnAddAndRemove() {
        // A ChunkTicketLevelUpdatedEvent listener observing a mod-added
        // ticket at ENTITY_TICKING distance sees oldLevel go 34 → 31 on
        // add, then 31 → 34 on remove. The MultiForge holder must mirror
        // that transition — INACCESSIBLE → ENTITY_TICKING → INACCESSIBLE.
        WorldRef world = WorldRef.of("test:cohort614");
        ChunkHolderManager manager = new ChunkHolderManager(world);
        RegionId region = RegionId.next();
        ChunkPos pos = new ChunkPos(1, 1);
        manager.createHolder(pos, region);

        assertThat(manager.ticketsFor(region).effectiveLevel(pos)).isEqualTo(ChunkLoadLevel.INACCESSIBLE);

        Ticket ticket = Ticket.at(
                TicketType.of("neoforge:entity_ticking", VANILLA_ENTITY_TICKING_LEVEL),
                VANILLA_ENTITY_TICKING_LEVEL,
                "mod-ticket-a");
        assertThat(manager.addTicket(region, pos, ticket)).isTrue();
        assertThat(manager.ticketsFor(region).effectiveLevel(pos)).isEqualTo(ChunkLoadLevel.ENTITY_TICKING);

        assertThat(manager.removeTicket(region, pos, ticket)).isTrue();
        assertThat(manager.ticketsFor(region).effectiveLevel(pos)).isEqualTo(ChunkLoadLevel.INACCESSIBLE);
    }
}
