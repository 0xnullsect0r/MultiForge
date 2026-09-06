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
import net.multiforge.runtime.chunk.NewChunkHolder;
import net.multiforge.runtime.chunk.Ticket;
import net.multiforge.runtime.chunk.TicketType;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import org.junit.jupiter.api.Test;

/**
 * Phase 6 (wave A, cohort 6.5) regression pin.
 *
 * <p>Vanilla {@code net.minecraft.server.level.ServerPlayer#teleportTo}
 * (and callers of {@code moveTo(...)} on cross-dimension teleports) at
 * {@code ServerPlayer.java:1488} writes:
 * <pre>
 *   p_265564_.getChunkSource().addRegionTicket(TicketType.POST_TELEPORT, chunkpos, 1, this.getId());
 * </pre>
 * The POST_TELEPORT ticket is keyed on the player's entity id so
 * repeated teleports don't accumulate on the same chunk; it holds the
 * destination chunk at ENTITY_TICKING for a short window so the client
 * receives entities before the ticket expires. Vanilla per-type timeout
 * is {@code 5} game ticks (~250 ms at 20 TPS).
 *
 * <p>This test pins:
 * <ol>
 *   <li>POST_TELEPORT at Vanilla level 33 (radius 1 → {@code MAX_VIEW_DISTANCE + 1 - 1 = 33}) promotes to BORDER (Vanilla wraps this immediately with a PLAYER ticket at the ENTITY_TICKING distance, but the POST_TELEPORT hold itself is only BORDER — the runtime keeps that shape exactly);</li>
 *   <li>the 5-tick timeout carries through the type record so
 *       {@code TicketExpiryTicker} sweeps on the same clock as Vanilla
 *       {@code DistanceManager.purgeStaleTickets};</li>
 *   <li>per-player key ({@code entityId}) means two teleports by two
 *       different players land as two distinct tickets on overlapping
 *       destination chunks — no clobber.</li>
 * </ol>
 */
class ServerPlayer_postTeleportTicketRoutingTest {

    private static final WorldRef WORLD = WorldRef.of("test:teleport");

    private static final String POST_TELEPORT_NAME = "post_teleport";

    /** Vanilla per-type timeout — 5 game ticks. */
    private static final int POST_TELEPORT_TIMEOUT_TICKS = 5;

    /**
     * Runtime distance the bridge writes for Vanilla
     * {@code addRegionTicket(POST_TELEPORT, chunkpos, 1, playerId)}:
     * {@code MAX_VIEW_DISTANCE + 1 - 1 = 33}. That's exactly
     * {@link ChunkLoadLevel#BORDER}.
     */
    private static final int POST_TELEPORT_TICKET_LEVEL = 33;

    private static final ChunkPos DESTINATION = new ChunkPos(50, -20);

    @Test
    void postTeleportTicketPromotesDestinationAndKeysPerPlayer() {
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(1).withThreadsPerCore(1);
        try (MultiThreadedSchedulerHost host = new MultiThreadedSchedulerHost(config, region -> {})) {
            host.registerChunk(WORLD, DESTINATION.x(), DESTINATION.z());
            ChunkHolderManager manager = host.chunkManagerFor(WORLD);
            ThreadedRegionizer regionizer = host.regionizerFor(WORLD);
            Region region = regionizer.regionAtChunk(DESTINATION.x(), DESTINATION.z());
            assertThat(region).isNotNull();

            TicketType type =
                    TicketType.of(POST_TELEPORT_NAME, POST_TELEPORT_TICKET_LEVEL, POST_TELEPORT_TIMEOUT_TICKS);

            // Two players teleport to the same chunk on the same tick.
            String playerA = "42";
            String playerB = "43";
            Ticket ticketA = Ticket.at(type, POST_TELEPORT_TICKET_LEVEL, playerA, /* createdAtTick */ 1000L);
            Ticket ticketB = Ticket.at(type, POST_TELEPORT_TICKET_LEVEL, playerB, /* createdAtTick */ 1000L);

            assertThat(manager.addTicket(region.id(), DESTINATION, ticketA)).isTrue();
            assertThat(manager.addTicket(region.id(), DESTINATION, ticketB))
                    .as("per-player key means playerB's ticket is distinct from playerA's on the same chunk")
                    .isTrue();

            NewChunkHolder holder = manager.holderAt(DESTINATION);
            assertThat(holder).isNotNull();
            assertThat(holder.level())
                    .as("POST_TELEPORT at level 33 promotes to BORDER")
                    .isEqualTo(ChunkLoadLevel.BORDER);
            assertThat(manager.ticketsFor(region.id()).ticketsAt(DESTINATION).size())
                    .as("two distinct tickets present after both adds")
                    .isEqualTo(2);
            assertThat(type.timeoutTicks())
                    .as("POST_TELEPORT timeout must be preserved (5 ticks) for expiry parity")
                    .isEqualTo(POST_TELEPORT_TIMEOUT_TICKS);

            // Simulate an expiry sweep or explicit removal — the ticket
            // is keyed per-player, so removing playerA's does not evict
            // playerB's (and the holder stays at BORDER).
            assertThat(manager.removeTicket(region.id(), DESTINATION, ticketA)).isTrue();
            assertThat(holder.level())
                    .as("playerB's POST_TELEPORT still holds the chunk at BORDER")
                    .isEqualTo(ChunkLoadLevel.BORDER);

            // Second remove drops the last ticket — INACCESSIBLE.
            assertThat(manager.removeTicket(region.id(), DESTINATION, ticketB)).isTrue();
            assertThat(holder.level()).isEqualTo(ChunkLoadLevel.INACCESSIBLE);
        }
    }
}
