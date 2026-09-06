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
import net.multiforge.runtime.chunk.HolderManagerRegionData;
import net.multiforge.runtime.chunk.NewChunkHolder;
import net.multiforge.runtime.chunk.PerChunkTickets;
import net.multiforge.runtime.chunk.Ticket;
import net.multiforge.runtime.chunk.TicketType;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import org.junit.jupiter.api.Test;

/**
 * Phase 6 (wave A, cohort 6.1) regression pin.
 *
 * <p>Vanilla {@code net.minecraft.world.entity.Entity#handleNetherPortal}
 * calls {@code serverlevel.getChunkSource().addRegionTicket(TicketType.PORTAL,
 * new ChunkPos(pos), 3, pos)} at {@code Entity.java:2627}. That flows
 * through {@code DistanceManager.addTicket} into the
 * {@code ChunkMap.DistanceManagerImpl} override added by
 * {@code multiforge-patches/04-chunk-system/net/minecraft/server/level/ChunkMap.java.patch},
 * which invokes
 * {@code net.multiforge.neoforge.DistanceManagerBridge.onAddTicket}. The
 * bridge translates the Vanilla ticket via
 * {@code toMultiForgeTicket(...)} into a runtime {@link Ticket} whose
 * type is {@code TicketType.of("portal", level, 300)} — PORTAL's Vanilla
 * per-type timeout is 300 ticks (15 seconds). The runtime then routes
 * the write into {@link ChunkHolderManager#addTicket}.
 *
 * <p>The runtime module has no Minecraft classpath, so this test cannot
 * exercise the fork bridge directly; instead it simulates the shape of
 * ticket the bridge produces (name = {@code "portal"}, timeout = 300)
 * and pins:
 * <ol>
 *   <li>the PORTAL ticket promotes its chunk holder to
 *       {@link ChunkLoadLevel#ENTITY_TICKING} (Vanilla ticket level
 *       {@code MAX_VIEW_DISTANCE + 1 - 3 = 31}, matching Vanilla
 *       Entity.handleNetherPortal's intent — the portal chunk ticks
 *       entities while in use);</li>
 *   <li>the paired remove (Vanilla eventually purges via the expiry
 *       sweep or an explicit removeRegionTicket in a mod path) demotes
 *       the holder to {@link ChunkLoadLevel#INACCESSIBLE};</li>
 *   <li>both transitions enqueue exactly one pending full-load update
 *       per holder — the invariant Phase 5.1
 *       ({@code INBOUND_MAILBOX} drain) relies on.</li>
 * </ol>
 */
class Entity_portalTicketRoutingTest {

    private static final WorldRef WORLD = WorldRef.of("test:portal");

    /** Vanilla name of {@code TicketType.PORTAL}. Must match the string {@code v.getType().toString()} returns. */
    private static final String PORTAL_NAME = "portal";

    /** Vanilla per-type timeout (game ticks). 300 = 15 s at 20 TPS. */
    private static final int PORTAL_TIMEOUT_TICKS = 300;

    /**
     * Runtime distance the bridge writes for a Vanilla {@code
     * addRegionTicket(PORTAL, pos, 3, key)}: Vanilla
     * {@code DistanceManager.addRegionTicket} computes
     * {@code ChunkMap.MAX_VIEW_DISTANCE + 1 - 3 = 31}. That's
     * ENTITY_TICKING on the {@link ChunkLoadLevel} ladder.
     */
    private static final int PORTAL_TICKET_LEVEL = 31;

    private static final ChunkPos PORTAL_CHUNK = new ChunkPos(0, 0);

    /**
     * The Vanilla key for a portal ticket is the block pos of the
     * portal — {@code BlockPos.toString()} in the runtime shadow.
     * The exact value doesn't matter for routing; only that add/remove
     * carry the same key so dedup in {@link PerChunkTickets} works.
     */
    private static final String PORTAL_KEY = "BlockPos{x=0, y=64, z=0}";

    @Test
    void portalTicketPromotesToEntityTickingAndRemoveDemotes() {
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(1).withThreadsPerCore(1);
        try (MultiThreadedSchedulerHost host = new MultiThreadedSchedulerHost(config, region -> {})) {
            host.registerChunk(WORLD, PORTAL_CHUNK.x(), PORTAL_CHUNK.z());
            ChunkHolderManager manager = host.chunkManagerFor(WORLD);
            ThreadedRegionizer regionizer = host.regionizerFor(WORLD);
            Region region = regionizer.regionAtChunk(PORTAL_CHUNK.x(), PORTAL_CHUNK.z());
            assertThat(region)
                    .as("portal chunk must be regionised on registerChunk")
                    .isNotNull();

            TicketType portalType = TicketType.of(PORTAL_NAME, PORTAL_TICKET_LEVEL, PORTAL_TIMEOUT_TICKS);
            Ticket portalTicket = Ticket.at(portalType, PORTAL_TICKET_LEVEL, PORTAL_KEY, /* createdAtTick */ 100L);

            // Add — simulates DistanceManagerBridge.onAddTicket firing.
            boolean added = manager.addTicket(region.id(), PORTAL_CHUNK, portalTicket);
            assertThat(added).as("PORTAL ticket must land on a fresh chunk").isTrue();

            NewChunkHolder holder = manager.holderAt(PORTAL_CHUNK);
            assertThat(holder).as("holder must be materialised by add").isNotNull();
            assertThat(holder.level())
                    .as("PORTAL at Vanilla level 31 promotes to ENTITY_TICKING")
                    .isEqualTo(ChunkLoadLevel.ENTITY_TICKING);

            HolderManagerRegionData data = manager.regionData(region.id());
            assertThat(data.pollFullLoadUpdate())
                    .as("add must enqueue exactly one pending-full-load-update")
                    .isEqualTo(holder);
            assertThat(data.pollFullLoadUpdate()).as("no duplicate enqueue").isNull();

            // Idempotent add — a portal ticket held while the entity is
            // still in the frame should be a no-op.
            boolean addedAgain = manager.addTicket(region.id(), PORTAL_CHUNK, portalTicket);
            assertThat(addedAgain)
                    .as("duplicate PORTAL ticket must be idempotent")
                    .isFalse();
            assertThat(data.pollFullLoadUpdate())
                    .as("no enqueue on duplicate add")
                    .isNull();

            // Remove — simulates the paired removeRegionTicket call or
            // the expiry sweep firing DistanceManagerBridge.onRemoveTicket.
            boolean removed = manager.removeTicket(region.id(), PORTAL_CHUNK, portalTicket);
            assertThat(removed).as("PORTAL ticket must remove cleanly").isTrue();
            assertThat(holder.level())
                    .as("with no other tickets holder demotes to INACCESSIBLE")
                    .isEqualTo(ChunkLoadLevel.INACCESSIBLE);
            assertThat(data.pollFullLoadUpdate())
                    .as("remove crossing threshold enqueues one update")
                    .isEqualTo(holder);
            assertThat(data.pollFullLoadUpdate()).isNull();

            // Sanity: the PerChunkTickets bucket is dropped after the last
            // remove — PerRegionTicketMap#removeTicket evicts empty
            // per-chunk buckets so byChunk doesn't grow unbounded. Both
            // "null bucket" and "empty bucket" satisfy the invariant.
            var bucket = manager.ticketsFor(region.id()).ticketsAt(PORTAL_CHUNK);
            assertThat(bucket == null || bucket.isEmpty())
                    .as("PerChunkTickets bucket empty or absent after remove")
                    .isTrue();
            assertThat(portalType.timeoutTicks())
                    .as("PORTAL timeout must be preserved through the bridge for expiry parity")
                    .isEqualTo(PORTAL_TIMEOUT_TICKS);
        }
    }
}
