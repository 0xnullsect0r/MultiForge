/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import org.junit.jupiter.api.Test;

/**
 * Integration coverage for Phase 5 wave B (task 5.8) — end-to-end
 * proof that a {@code /forceload add} → {@code /forceload remove} pair
 * routed through {@link ChunkHolderManager#addTicket} produces the
 * expected {@link ChunkLoadLevel} transitions in the per-region ticket
 * map and pending-full-load-update queue.
 *
 * <p>The runtime module cannot spawn a Minecraft server, so the test
 * simulates {@code /forceload}'s end-to-end path using only runtime
 * fixtures — {@link MultiThreadedSchedulerHost} boots the same
 * {@link ThreadedRegionizer} + {@link ChunkHolderManager} plumbing the
 * fork glue wires in production ({@code
 * MultiForgeDistanceManager.routeAddTicket} in
 * {@code net.multiforge.neoforge.chunk} lives above this pipe). A
 * single-worker host with a no-op tick body keeps the worker off any
 * queue the test manually drains — the drain here mirrors the
 * production {@code Phase.INBOUND_MAILBOX} pass {@link
 * MultiThreadedSchedulerHost#installM9WiredTickBody} attaches (Phase
 * 5.1). The wire-in doesn't change the level itself (that's published
 * inline by {@code addTicket}) — it clears the pending flag so a
 * follow-up transition on the same holder can re-enqueue, which the
 * test also verifies via the FORCED-remove pass.
 *
 * <p>121 chunks (11×11 grid, {@code (0..10, 0..10)}) fits inside one
 * 16×16 section — the whole grid ends up in a single region. That
 * keeps the assertion simple ({@code region.id()} is stable) without
 * losing the "many chunks per region" shape the FORCED-ticket promotion
 * hits in production.
 */
class ForceloadIntegrationTest {

    private static final WorldRef WORLD = WorldRef.of("test:forceload");

    /** Inclusive corner of the simulated {@code /forceload add 0 0 100 100} range. */
    private static final int GRID_MIN = 0;

    /** Inclusive far corner — 11 chunks per side × 11 = 121 total. */
    private static final int GRID_MAX = 10;

    private static final int GRID_SIZE = (GRID_MAX - GRID_MIN + 1) * (GRID_MAX - GRID_MIN + 1);

    /**
     * Stable dedup key for the FORCED ticket. Matches the shape the
     * production fork emits: {@code MultiForgeDistanceManager.translate}
     * derives per-ticket string keys, and {@code /forceload} always
     * lands one FORCED per chunk.
     */
    private static final String FORCED_KEY = "forceload";

    @Test
    void forceloadAddPromotesGridToBorderAndRemoveDemotes() {
        // Boot: MultiThreadedSchedulerHost with a single worker + no-op tick body.
        // The worker will attempt to auto-tick registered regions but the no-op body
        // never touches pendingFullLoadUpdate, so the test thread's manual drain is
        // race-free (see class javadoc). Try-with-resources shuts the pool down.
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(1).withThreadsPerCore(1);
        try (MultiThreadedSchedulerHost host = new MultiThreadedSchedulerHost(config, region -> {})) {
            // Step 1 — simulate ChunkEvent.Load for every chunk in the grid so a
            // region is materialised over the whole 11×11 patch (the production
            // RegionizedChunkLifecycle.onChunkLoaded path).
            for (int cx = GRID_MIN; cx <= GRID_MAX; cx++) {
                for (int cz = GRID_MIN; cz <= GRID_MAX; cz++) {
                    host.registerChunk(WORLD, cx, cz);
                }
            }

            ChunkHolderManager manager = host.chunkManagerFor(WORLD);
            ThreadedRegionizer regionizer = host.regionizerFor(WORLD);
            Region region = regionizer.regionAtChunk(GRID_MIN, GRID_MIN);
            assertThat(region)
                    .as("grid must be covered by a materialised region")
                    .isNotNull();

            // Step 2 — simulate /forceload add 0 0 100 100. One FORCED ticket per
            // chunk lands via manager.addTicket, promoting each holder to BORDER
            // (FORCED.defaultDistance == ChunkLoadLevel.BORDER.distance()).
            for (int cx = GRID_MIN; cx <= GRID_MAX; cx++) {
                for (int cz = GRID_MIN; cz <= GRID_MAX; cz++) {
                    ChunkPos pos = new ChunkPos(cx, cz);
                    if (manager.holderAt(pos) == null) {
                        manager.createHolder(pos, region.id());
                    }
                    boolean added = manager.addTicket(region.id(), pos, Ticket.of(TicketType.FORCED, FORCED_KEY));
                    assertThat(added)
                            .as("FORCED ticket must land at (%d,%d)", cx, cz)
                            .isTrue();
                }
            }

            // Step 3 — simulate one region tick's INBOUND_MAILBOX drain
            // (Phase 5.1 wiring). The drain doesn't change the holder's level
            // (that was published inline by addTicket); it clears the pending
            // flag so a downstream transition re-enqueues. Verified by the
            // second-drain assertion after the remove pass below.
            HolderManagerRegionData data = manager.regionData(region.id());
            int drainedOnAdd = drainPending(data);
            assertThat(drainedOnAdd)
                    .as("every FORCED add must enqueue exactly one pending-full-load-update")
                    .isEqualTo(GRID_SIZE);

            // Step 4 — assert every chunk is at BORDER or above (FORCED
            // parity with Vanilla /forceload). The diagnostic accessor is the
            // one the task calls out (Phase 5.8) — every holder in the grid
            // counts, and no orphan grid holders show up.
            assertThat(manager.getBorderHolderCount()).isEqualTo(GRID_SIZE);
            for (int cx = GRID_MIN; cx <= GRID_MAX; cx++) {
                for (int cz = GRID_MIN; cz <= GRID_MAX; cz++) {
                    ChunkPos pos = new ChunkPos(cx, cz);
                    NewChunkHolder holder = manager.holderAt(pos);
                    assertThat(holder).as("holder present at (%d,%d)", cx, cz).isNotNull();
                    assertThat(holder.level().isAtLeast(ChunkLoadLevel.BORDER))
                            .as("holder (%d,%d) reached BORDER — actual %s", cx, cz, holder.level())
                            .isTrue();
                }
            }
            // Sanity: the pending queue is drained.
            assertThat(data.pendingFullLoadCount()).isZero();

            // Step 5 — simulate /forceload remove 0 0 100 100. The symmetric
            // remove path pulls each FORCED ticket back out.
            for (int cx = GRID_MIN; cx <= GRID_MAX; cx++) {
                for (int cz = GRID_MIN; cz <= GRID_MAX; cz++) {
                    ChunkPos pos = new ChunkPos(cx, cz);
                    boolean removed = manager.removeTicket(region.id(), pos, Ticket.of(TicketType.FORCED, FORCED_KEY));
                    assertThat(removed)
                            .as("FORCED remove must succeed at (%d,%d)", cx, cz)
                            .isTrue();
                }
            }

            // Step 6 — second drain pass; every remove crossed the BORDER
            // threshold downward and must have re-enqueued a full-load
            // update — Phase 5.1's semantic is symmetric on both directions.
            int drainedOnRemove = drainPending(data);
            assertThat(drainedOnRemove)
                    .as("every FORCED remove must enqueue exactly one pending-full-load-update")
                    .isEqualTo(GRID_SIZE);

            // Step 7 — every holder is now INACCESSIBLE (no live ticket),
            // and no holder is above the BORDER threshold. Holders are not
            // dropped (the FORCED ticket is gone but the holder record
            // survives until an explicit dropHolder — the production
            // ChunkEvent.Unload path handles that separately).
            assertThat(manager.getBorderHolderCount()).isZero();
            for (int cx = GRID_MIN; cx <= GRID_MAX; cx++) {
                for (int cz = GRID_MIN; cz <= GRID_MAX; cz++) {
                    ChunkPos pos = new ChunkPos(cx, cz);
                    NewChunkHolder holder = manager.holderAt(pos);
                    assertThat(holder).isNotNull();
                    assertThat(holder.level())
                            .as("holder (%d,%d) demoted to INACCESSIBLE — actual %s", cx, cz, holder.level())
                            .isEqualTo(ChunkLoadLevel.INACCESSIBLE);
                }
            }
            assertThat(data.pendingFullLoadCount()).isZero();
        }
    }

    /**
     * Manual replay of the Phase 5.1 {@code INBOUND_MAILBOX} phase for
     * one region — walks {@link HolderManagerRegionData#pollFullLoadUpdate}
     * until it returns {@code null}, clearing the pending flag on each
     * drained holder (which the poll itself does). Returns the count so
     * the test can pin the enqueue-cardinality invariant.
     */
    private static int drainPending(HolderManagerRegionData data) {
        int drained = 0;
        while (data.pollFullLoadUpdate() != null) drained++;
        return drained;
    }
}
