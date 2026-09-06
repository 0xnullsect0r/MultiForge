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
package net.multiforge.runtime.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.RegionId;
import net.multiforge.runtime.region.SectionPos;
import org.junit.jupiter.api.Test;

/**
 * Integration coverage for the pure-Java per-region ticket layer that
 * {@code net.multiforge.neoforge.chunk.MultiForgeDistanceManager}
 * (Minecraft-classpath facade — task 4.2b) delegates to.
 *
 * <p>The facade lives on the Minecraft classpath and extends
 * {@code net.minecraft.server.level.DistanceManager}, so it cannot be
 * unit-tested from {@code multiforge-runtime}. Every add/remove ticket
 * choke inside the facade routes through {@link ChunkHolderManager}
 * which stores state in {@link PerRegionTicketMap} + {@link
 * PerChunkTickets} — pinning the routing behavior of this layer is
 * therefore equivalent to pinning what the facade will observe.
 *
 * <p>Covers scenarios not exercised by
 * {@link PerChunkTicketsTest}/{@link ChunkHolderManagerTest}:
 * cross-region balance, merge/split callbacks, concurrent add,
 * ticket expiry, idempotent add, safe remove-of-missing, and the
 * BORDER-threshold full-load promotion path.
 */
class PerRegionTicketMapTest {

    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");

    // === 1. Cross-region add/remove balance ============================

    /**
     * Ticket writes to disjoint regions must not leak: removing from one
     * leaves the other's chunk count and effective level untouched.
     * Locks in the routing invariant the facade's {@code routeAddTicket}
     * choke relies on — every ticket lands in exactly its owner region's
     * map ({@code ChunkHolderManager.ticketsFor(owner)}).
     */
    @Test
    void crossRegionAddRemoveBalance() {
        PerRegionTicketMap regionA = new PerRegionTicketMap();
        PerRegionTicketMap regionB = new PerRegionTicketMap();
        ChunkPos posA = new ChunkPos(0, 0);
        ChunkPos posB = new ChunkPos(100, 100);
        Ticket ticketA = Ticket.of(TicketType.PLUGIN, "keyA");
        Ticket ticketB = Ticket.of(TicketType.PLUGIN, "keyB");

        regionA.addTicket(posA, ticketA);
        regionB.addTicket(posB, ticketB);
        assertThat(regionA.chunkCount()).isEqualTo(1);
        assertThat(regionB.chunkCount()).isEqualTo(1);

        assertThat(regionA.removeTicket(posA, ticketA)).isTrue();
        assertThat(regionA.chunkCount()).isZero();
        assertThat(regionA.effectiveLevel(posA)).isEqualTo(ChunkLoadLevel.INACCESSIBLE);

        // regionB must be untouched.
        assertThat(regionB.chunkCount()).isEqualTo(1);
        assertThat(regionB.ticketsAt(posB).snapshot()).containsExactly(ticketB);
        assertThat(regionB.effectiveLevel(posB)).isEqualTo(ChunkLoadLevel.BORDER);
    }

    // === 2. Merge callback preserves both ticket sets ==================

    /**
     * When two regions merge, the surviving region's {@link
     * PerRegionTicketMap#merge} must fold in every chunk/ticket entry
     * from the dying region — union semantics, no losses, correct
     * per-chunk keying. Mirrors {@code
     * ChunkHolderManager.onRegionsMerging} which delegates to this
     * method.
     */
    @Test
    void mergeCallbackPreservesBothTicketSets() {
        PerRegionTicketMap surviving = new PerRegionTicketMap();
        PerRegionTicketMap dying = new PerRegionTicketMap();

        ChunkPos survivingPos = new ChunkPos(1, 1);
        ChunkPos dyingPos = new ChunkPos(50, 50);
        ChunkPos overlapPos = new ChunkPos(7, 7);
        Ticket survivingTicket = Ticket.of(TicketType.PLUGIN, "surviving");
        Ticket dyingTicket = Ticket.of(TicketType.PLUGIN, "dying");
        Ticket overlapSurviving = Ticket.of(TicketType.PLAYER, "player-1");
        Ticket overlapDying = Ticket.of(TicketType.PLUGIN, "extra");

        surviving.addTicket(survivingPos, survivingTicket);
        surviving.addTicket(overlapPos, overlapSurviving);
        dying.addTicket(dyingPos, dyingTicket);
        dying.addTicket(overlapPos, overlapDying);

        surviving.merge(dying);

        assertThat(surviving.chunkCount()).isEqualTo(3);
        assertThat(surviving.ticketsAt(survivingPos).snapshot()).containsExactly(survivingTicket);
        assertThat(surviving.ticketsAt(dyingPos).snapshot()).containsExactly(dyingTicket);
        assertThat(surviving.ticketsAt(overlapPos).snapshot())
                .containsExactlyInAnyOrder(overlapSurviving, overlapDying);
        // Overlap: min(PLAYER=31, PLUGIN=33) → ENTITY_TICKING.
        assertThat(surviving.effectiveLevel(overlapPos)).isEqualTo(ChunkLoadLevel.ENTITY_TICKING);
    }

    // === 3. Split callback re-partitions per section membership ========

    /**
     * A region spanning multiple sections is split by handing chunks
     * from a caller-chosen subset of sections to a new region. Verifies
     * that {@link PerRegionTicketMap#split}: (a) moves only the matching
     * chunks, (b) leaves the source with the residual set, and (c) keys
     * are preserved intact on both sides — mirroring the section-based
     * predicate {@code ChunkHolderManager.onRegionSplit(Region, Region)}
     * builds from {@code Region.sections()}.
     */
    @Test
    void splitCallbackRePartitionsPerSectionMembership() {
        // sectionChunkShift = 4 → 16×16 chunks per section.
        int shift = 4;
        PerRegionTicketMap source = new PerRegionTicketMap();

        // One ticket per section, spanning 4 sections in a 2×2 arrangement.
        ChunkPos posSec00 = new ChunkPos(0, 0); // section (0,0)
        ChunkPos posSec10 = new ChunkPos(16, 0); // section (1,0)
        ChunkPos posSec01 = new ChunkPos(0, 16); // section (0,1)
        ChunkPos posSec11 = new ChunkPos(16, 16); // section (1,1)
        Ticket t00 = Ticket.of(TicketType.PLUGIN, "s00");
        Ticket t10 = Ticket.of(TicketType.PLUGIN, "s10");
        Ticket t01 = Ticket.of(TicketType.PLUGIN, "s01");
        Ticket t11 = Ticket.of(TicketType.PLUGIN, "s11");
        source.addTicket(posSec00, t00);
        source.addTicket(posSec10, t10);
        source.addTicket(posSec01, t01);
        source.addTicket(posSec11, t11);

        // Child owns section-column x=1 (sections (1,0) and (1,1)). Source
        // keeps section-column x=0 (sections (0,0) and (0,1)).
        Set<SectionPos> childSections = Set.of(new SectionPos(1, 0), new SectionPos(1, 1));
        PerRegionTicketMap child =
                source.split(pos -> childSections.contains(SectionPos.ofChunk(pos.x(), pos.z(), shift)));

        assertThat(source.chunkCount()).isEqualTo(2);
        assertThat(source.loadedChunks()).containsExactlyInAnyOrder(posSec00, posSec01);
        assertThat(source.ticketsAt(posSec00).snapshot()).containsExactly(t00);
        assertThat(source.ticketsAt(posSec01).snapshot()).containsExactly(t01);

        assertThat(child.chunkCount()).isEqualTo(2);
        assertThat(child.loadedChunks()).containsExactlyInAnyOrder(posSec10, posSec11);
        assertThat(child.ticketsAt(posSec10).snapshot()).containsExactly(t10);
        assertThat(child.ticketsAt(posSec11).snapshot()).containsExactly(t11);
    }

    // === 4. Concurrent add correctness =================================

    /**
     * {@link PerRegionTicketMap} is documented single-writer, but the
     * outer per-region routing in {@link ChunkHolderManager} is
     * concurrent (backed by a {@code ConcurrentHashMap}). Simulate the
     * production discipline — one region worker per region — by giving
     * each of 8 threads its own {@link RegionId}, then have every thread
     * add 100 distinct tickets through {@code addTicket}. Assert every
     * ticket landed in the correct per-region map (no cross-region
     * leaks) and no distinct ticket was dropped as a duplicate.
     */
    @Test
    void concurrentAddPreservesAllTicketsAcrossRegions() throws Exception {
        ChunkHolderManager manager = new ChunkHolderManager(WORLD);
        final int threads = 8;
        final int perThread = 100;

        RegionId[] regions = new RegionId[threads];
        for (int i = 0; i < threads; i++) regions[i] = RegionId.next();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier gate = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger();

        try {
            for (int t = 0; t < threads; t++) {
                final int threadIdx = t;
                final RegionId region = regions[t];
                pool.submit(() -> {
                    try {
                        gate.await();
                        for (int i = 0; i < perThread; i++) {
                            // Distinct chunk per (thread, i) — the outer
                            // holder map is a ConcurrentHashMap, so the
                            // computeIfAbsent creates cleanly. The inner
                            // per-region map is only ever touched by this
                            // one thread → matches production single-writer
                            // per-region discipline.
                            ChunkPos pos = new ChunkPos(threadIdx * 1000 + i, threadIdx);
                            Ticket ticket = Ticket.of(TicketType.PLUGIN, "t" + threadIdx + "-" + i);
                            if (manager.addTicket(region, pos, ticket)) successes.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        // fall through — done latch still counts down
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(successes.get()).isEqualTo(threads * perThread);
        for (int t = 0; t < threads; t++) {
            assertThat(manager.ticketsFor(regions[t]).chunkCount()).isEqualTo(perThread);
        }
        assertThat(manager.holderCount()).isEqualTo(threads * perThread);
    }

    // === 5. Ticket expiry integration ==================================

    /**
     * A POST_TELEPORT ticket (5-tick timeout in {@link TicketType})
     * added at tick 100 must be swept by {@link TicketExpiryTicker} once
     * "now" reaches tick 105 (Vanilla boundary: {@code createdTick +
     * timeout <= now}). Pinning this end-to-end guards the expiry chain
     * the facade fires each tick: {@code TicketExpiryTicker.runOnce →
     * ChunkHolderManager.removeTicket → PerRegionTicketMap.removeTicket}.
     */
    @Test
    void ticketExpiryIntegrationRemovesTimedOutTicket() {
        ChunkHolderManager manager = new ChunkHolderManager(WORLD);
        RegionId owner = RegionId.next();
        ChunkPos pos = new ChunkPos(4, 4);
        Ticket teleport = Ticket.of(TicketType.POST_TELEPORT, "player-42", 100L);
        manager.createHolder(pos, owner);
        manager.addTicket(owner, pos, teleport);
        assertThat(manager.ticketsFor(owner).ticketsAt(pos).size()).isEqualTo(1);

        TicketExpiryTicker ticker = new TicketExpiryTicker(manager);
        // Before the boundary — nothing expires.
        assertThat(ticker.runOnce(104L)).isZero();
        assertThat(manager.ticketsFor(owner).ticketsAt(pos).size()).isEqualTo(1);

        // At and past the boundary — the ticket is swept, per-chunk entry
        // collapses (last ticket removed), holder demotes to INACCESSIBLE.
        assertThat(ticker.runOnce(106L)).isEqualTo(1);
        assertThat(manager.ticketsFor(owner).ticketsAt(pos)).isNull();
        assertThat(manager.holderAt(pos).level()).isEqualTo(ChunkLoadLevel.INACCESSIBLE);
    }

    // === 6. Idempotent add of the same ticket ==========================

    /**
     * Adding the same {@link Ticket} object twice is a no-op — the
     * second call returns {@code false} (already present) and the
     * per-chunk set size stays at 1. Codifies the current
     * {@link PerChunkTickets#add} semantic; the facade relies on this
     * so a Vanilla {@code addTicket} that re-emits an equal ticket
     * (identical type + distance + key) does not double-count and does
     * not trigger a spurious full-load-update.
     */
    @Test
    void idempotentAddSameTicketTwiceIsNoOp() {
        PerRegionTicketMap map = new PerRegionTicketMap();
        ChunkPos pos = new ChunkPos(3, 3);
        Ticket ticket = Ticket.of(TicketType.PLUGIN, "same-key");

        assertThat(map.addTicket(pos, ticket)).isTrue();
        assertThat(map.addTicket(pos, ticket)).isFalse();
        assertThat(map.ticketsAt(pos).size()).isEqualTo(1);

        // A distinct Ticket instance with equal record fields is still equal
        // (Ticket is a record with createdAtTick=-1 by default) — should
        // also be a no-op.
        Ticket equal = Ticket.of(TicketType.PLUGIN, "same-key");
        assertThat(map.addTicket(pos, equal)).isFalse();
        assertThat(map.ticketsAt(pos).size()).isEqualTo(1);
    }

    // === 7. Remove nonexistent ticket is safe ==========================

    /**
     * Removing a ticket that was never added — either because the chunk
     * has no entry at all, or because the entry has a different ticket
     * set — must return {@code false} and never throw. The facade calls
     * this path unconditionally on the Vanilla removeTicket entry point;
     * a NPE or ISE here would blow up a mod's chunk-management code.
     */
    @Test
    void removeNonexistentTicketIsSafe() {
        PerRegionTicketMap map = new PerRegionTicketMap();
        ChunkPos emptyPos = new ChunkPos(0, 0);
        ChunkPos populatedPos = new ChunkPos(1, 1);
        Ticket missing = Ticket.of(TicketType.PLUGIN, "not-in-map");
        Ticket present = Ticket.of(TicketType.PLUGIN, "present");

        // (a) Removing from an entirely-empty map.
        assertThatCode(() -> assertThat(map.removeTicket(emptyPos, missing)).isFalse())
                .doesNotThrowAnyException();
        assertThat(map.chunkCount()).isZero();

        // (b) Chunk has a set, but the specific ticket is not in it.
        map.addTicket(populatedPos, present);
        assertThatCode(() -> assertThat(map.removeTicket(populatedPos, missing)).isFalse())
                .doesNotThrowAnyException();
        // Existing ticket still present, chunk entry retained.
        assertThat(map.ticketsAt(populatedPos).snapshot()).containsExactly(present);
        assertThat(map.chunkCount()).isEqualTo(1);
    }

    // === 8. BORDER threshold gate ======================================

    /**
     * When adding a ticket at the BORDER distance (33) crosses the
     * holder's effective load level from INACCESSIBLE up to BORDER,
     * {@link ChunkHolderManager#addTicket} must (a) publish the new
     * level on the holder and (b) enqueue a full-load-update pollable
     * via {@link HolderManagerRegionData#pollFullLoadUpdate}. This is
     * the promotion pump the facade drives every tick.
     */
    @Test
    void borderThresholdGateEnqueuesFullLoadUpdate() {
        ChunkHolderManager manager = new ChunkHolderManager(WORLD);
        RegionId owner = RegionId.next();
        ChunkPos pos = new ChunkPos(2, 2);
        NewChunkHolder holder = manager.createHolder(pos, owner);
        assertThat(holder.level()).isEqualTo(ChunkLoadLevel.INACCESSIBLE);

        // START ticket at distance 33 (== BORDER). Crossing INACCESSIBLE →
        // BORDER promotes the holder and enqueues a full-load-update.
        Ticket startTicket = Ticket.at(TicketType.START, 33, "spawn");
        assertThat(manager.addTicket(owner, pos, startTicket)).isTrue();

        assertThat(holder.level()).isEqualTo(ChunkLoadLevel.BORDER);
        assertThat(manager.ticketsFor(owner).effectiveLevel(pos)).isEqualTo(ChunkLoadLevel.BORDER);

        HolderManagerRegionData regionData = manager.regionData(owner);
        assertThat(regionData.pendingFullLoadCount()).isEqualTo(1);
        NewChunkHolder polled = regionData.pollFullLoadUpdate();
        assertThat(polled).isSameAs(holder);
        // Poll clears the flag → count drops to zero, no duplicate enqueue.
        assertThat(regionData.pendingFullLoadCount()).isZero();
        assertThat(regionData.pollFullLoadUpdate()).isNull();

        // Sanity: loadedChunks reflects the new entry.
        assertThat(manager.ticketsFor(owner).loadedChunks()).containsExactly(pos);
        assertThat(new HashSet<>(manager.ticketsFor(owner).loadedChunks())).containsExactly(pos);
    }
}
