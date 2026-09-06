/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
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
 * Phase 6 (wave A, cohort 6.7) regression pin.
 *
 * <p>The mob-cap / natural-spawner surface reads
 * {@code DistanceManager.inEntityTickingRange(chunkKey)} (see
 * {@code ServerLevel.java:408, 1692} and every
 * {@code NaturalSpawner.spawnForChunk} call downstream). The M9 landing
 * preserves the Vanilla {@code DistanceManager} instance and its
 * {@code inEntityTickingRange}/{@code inBlockTickingRange} methods; the
 * per-region ticket map behind the observer bridge answers the same
 * question via {@link ChunkLoadLevel#ENTITY_TICKING} membership.
 *
 * <p>{@code LocalMobCapCalculator} at
 * {@code world/level/LocalMobCapCalculator.java:24} holds a
 * {@code ChunkMap} reference and asks
 * {@code chunkMap.getPlayersCloseForSpawning(sectionPos)} — that method
 * is untouched by every patch under
 * {@code multiforge-patches/04-chunk-system/net/minecraft/server/level/ChunkMap.java.patch}
 * (only the {@code DistanceManagerImpl} inner class and the seams
 * around {@code applyStep} / {@code save} / {@code updatePlayerStatus} /
 * {@code move} are added). This test pins the invariant the cohort
 * ultimately depends on: a PLAYER-scale ticket makes the target chunk
 * ENTITY_TICKING, which is the level natural spawning and mob-cap gate on.
 */
class MobCap_tickingRangeRoutingTest {

    private static final WorldRef WORLD = WorldRef.of("test:spawning");

    /** Vanilla {@code TicketType.PLAYER.toString()}. */
    private static final String PLAYER_NAME = "player";

    /** PLAYER's Vanilla per-type timeout — permanent while online. */
    private static final int PLAYER_TIMEOUT_TICKS = 0;

    /**
     * Vanilla level of the PLAYER ticket at the player's own chunk —
     * {@code ChunkMap.FORCED_TICKET_LEVEL - 1 = 30}, which is
     * ENTITY_TICKING on the runtime ladder. This is the level
     * {@code DistanceManager.inEntityTickingRange} returns true for.
     */
    private static final int PLAYER_TICKET_LEVEL_AT_CENTER = 31;

    /**
     * Level at a chunk one step outside the player's centre — 32 →
     * TICKING (block ticks fire, entities do not). Mob-cap reads
     * {@code inEntityTickingRange}, which is false here — that's the
     * exact boundary Vanilla enforces.
     */
    private static final int PLAYER_TICKET_LEVEL_AT_RING_1 = 32;

    private static final ChunkPos PLAYER_CENTER = new ChunkPos(64, 64);
    private static final ChunkPos PLAYER_RING_1 = new ChunkPos(65, 64);

    @Test
    void playerTicketPromotesCenterToEntityTickingAndRingToTicking() {
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(1).withThreadsPerCore(1);
        try (MultiThreadedSchedulerHost host = new MultiThreadedSchedulerHost(config, region -> {})) {
            host.registerChunk(WORLD, PLAYER_CENTER.x(), PLAYER_CENTER.z());
            host.registerChunk(WORLD, PLAYER_RING_1.x(), PLAYER_RING_1.z());
            ChunkHolderManager manager = host.chunkManagerFor(WORLD);
            ThreadedRegionizer regionizer = host.regionizerFor(WORLD);
            Region region = regionizer.regionAtChunk(PLAYER_CENTER.x(), PLAYER_CENTER.z());
            assertThat(region).isNotNull();

            // A single player's ticket-set materialises as one distance-31
            // ticket at the center chunk and one distance-32 ticket at the
            // ring-1 chunk. The bridge preserves the per-chunk ticketLevel
            // Vanilla's PlayerTicketManager emits.
            TicketType centerType = TicketType.of(PLAYER_NAME, PLAYER_TICKET_LEVEL_AT_CENTER, PLAYER_TIMEOUT_TICKS);
            TicketType ringType = TicketType.of(PLAYER_NAME, PLAYER_TICKET_LEVEL_AT_RING_1, PLAYER_TIMEOUT_TICKS);
            String playerKey = "playerA";

            Ticket centerTicket = Ticket.at(centerType, PLAYER_TICKET_LEVEL_AT_CENTER, playerKey, 0L);
            Ticket ringTicket = Ticket.at(ringType, PLAYER_TICKET_LEVEL_AT_RING_1, playerKey, 0L);
            assertThat(manager.addTicket(region.id(), PLAYER_CENTER, centerTicket))
                    .isTrue();
            assertThat(manager.addTicket(region.id(), PLAYER_RING_1, ringTicket))
                    .isTrue();

            NewChunkHolder centerHolder = manager.holderAt(PLAYER_CENTER);
            NewChunkHolder ringHolder = manager.holderAt(PLAYER_RING_1);

            assertThat(centerHolder.level())
                    .as("PLAYER center chunk must be ENTITY_TICKING — mob-cap and entity ticks fire here")
                    .isEqualTo(ChunkLoadLevel.ENTITY_TICKING);
            assertThat(centerHolder.level().isAtLeast(ChunkLoadLevel.ENTITY_TICKING))
                    .as("inEntityTickingRange invariant — must be true at center")
                    .isTrue();

            assertThat(ringHolder.level())
                    .as("PLAYER ring-1 chunk must be TICKING — block ticks fire, entities do not")
                    .isEqualTo(ChunkLoadLevel.TICKING);
            assertThat(ringHolder.level().isAtLeast(ChunkLoadLevel.ENTITY_TICKING))
                    .as("inEntityTickingRange invariant — must be false at ring-1")
                    .isFalse();
            assertThat(ringHolder.level().isAtLeast(ChunkLoadLevel.TICKING))
                    .as("inBlockTickingRange invariant — must be true at ring-1")
                    .isTrue();

            // Player disconnects (removePlayer flow). Every PLAYER ticket
            // is dropped; both chunks fall back to INACCESSIBLE.
            assertThat(manager.removeTicket(region.id(), PLAYER_CENTER, centerTicket))
                    .isTrue();
            assertThat(manager.removeTicket(region.id(), PLAYER_RING_1, ringTicket))
                    .isTrue();
            assertThat(centerHolder.level()).isEqualTo(ChunkLoadLevel.INACCESSIBLE);
            assertThat(ringHolder.level()).isEqualTo(ChunkLoadLevel.INACCESSIBLE);
        }
    }
}
