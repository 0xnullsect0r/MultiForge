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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.ThreadedRegionizer;
import org.junit.jupiter.api.Test;

/**
 * /67 round-5 findings H4 + H5 regression coverage.
 *
 * <p>Before the fix, {@link ChunkHolderManager#addTicket} and
 * {@link ChunkHolderManager#removeTicket} did NOT hold the regionizer
 * read lock across their resolve→write pair. Callers resolved the
 * owner {@link net.multiforge.runtime.region.RegionId} outside any
 * lock; if a merge fired between that resolve and the ticket write,
 * the write landed in the DYING region's per-region ticket map. The
 * regionizer's write-lock-held {@link
 * ChunkHolderManager#onRegionMerged} had already moved that map to
 * the survivor, so the newly-written ticket was orphaned — same
 * silent-loss shape as pre-Phase-1.2 {@code queueChunkTask}.
 *
 * <p>The fix (see {@link ChunkHolderManager}) threads an optional
 * {@link ThreadedRegionizer} accessor through the constructor. When
 * wired, {@code addTicket} / {@code removeTicket} take the read lock
 * across the resolve→write pair and re-resolve the current owner of
 * the position — so a stale hint reroutes to the survivor and a
 * concurrent merge cannot fold the target region out from under the
 * write.
 *
 * <p>Test shape mirrors {@code
 * RegionizedTaskQueueTest.queueChunkTaskUnderConcurrentMergeDoesNotLoseTask}.
 */
class ChunkHolderManagerMergeRaceTest {

    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");

    /**
     * 100-iteration stress. Two disjoint regions A and B; T1 adds
     * tickets keyed on A's id in a tight loop while T2 fires a merge
     * that folds A into B (via a bridge chunk that adjoins both). At
     * the end, every ticket must have landed in the surviving region
     * — never in the dying region's map (which
     * {@link ChunkHolderManager#onRegionDied} clears), and never in a
     * fresh empty {@link PerRegionTicketMap} keyed on A's id
     * post-death.
     */
    @Test
    void addTicket_races_with_merge_never_lost() throws Exception {
        for (int iter = 0; iter < 100; iter++) {
            runAddRace(iter);
        }
    }

    private void runAddRace(int iter) throws Exception {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        // Two disjoint regions A and B, 2 sections apart so no auto-merge
        // at add time (radius 1 = 8 immediate neighbours only). The
        // bridge chunk at (1,0) neighbours BOTH sections and triggers
        // the merge.
        Region a = regionizer.addChunk(new ChunkPos(0, 0));
        Region b = regionizer.addChunk(new ChunkPos(2, 0));
        assertThat(a).isNotSameAs(b);

        ChunkHolderManager manager = new ChunkHolderManager(WORLD, () -> regionizer);
        regionizer.addListener(manager);
        // Pre-create the holder so the ticket writes have a stable target
        // regardless of merge ordering (the test's contract is on
        // ticket routing, not holder creation).
        ChunkPos pos = new ChunkPos(0, 0);
        manager.createHolder(pos, a.id());

        // T1: hammer addTicket with A's id. Every add uses a UNIQUE
        // ticket so PerRegionTicketMap.addTicket returns true on every
        // call and we can count writes exactly.
        int ticketCount = 32;
        CountDownLatch producerReady = new CountDownLatch(1);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger addedCount = new AtomicInteger();
        Thread producer = new Thread(
                () -> {
                    producerReady.countDown();
                    try {
                        startGate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < ticketCount; i++) {
                        Ticket t = Ticket.of(TicketType.PLUGIN, "race-" + iter + "-" + i);
                        if (manager.addTicket(a.id(), pos, t)) addedCount.incrementAndGet();
                    }
                },
                "test-add-producer-" + iter);
        producer.start();
        assertThat(producerReady.await(5, TimeUnit.SECONDS)).isTrue();

        // T2: bridge (1,0) → merges A + B. Fires immediately so the
        // producer's writes race the merge.
        Thread merger = new Thread(
                () -> {
                    try {
                        startGate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    regionizer.addChunk(new ChunkPos(1, 0));
                },
                "test-merger-" + iter);
        merger.start();

        startGate.countDown();
        producer.join(5_000);
        merger.join(5_000);
        assertThat(producer.isAlive()).as("producer stuck (iter %d)", iter).isFalse();
        assertThat(merger.isAlive()).as("merger stuck (iter %d)", iter).isFalse();

        // Post-condition: merger has run, so A and B are one region now.
        Region survivor = regionizer.regionAtChunk(0, 0);
        assertThat(regionizer.regionAtChunk(2, 0)).isSameAs(survivor);

        // Every ticket the producer thinks it added must be findable on
        // the survivor. A ticket written into A post-onRegionMerged would
        // sit in a fresh, orphan PerRegionTicketMap under A's id — the
        // survivor's map would then be missing that ticket. Assert the
        // survivor's ticket count matches the producer's add count so
        // no ticket vanished into an orphan map.
        PerRegionTicketMap survivorTickets = manager.ticketsFor(survivor.id());
        PerChunkTickets bucket = survivorTickets.ticketsAt(pos);
        int survivorTicketCount = bucket == null ? 0 : bucket.snapshot().size();
        assertThat(survivorTicketCount)
                .as("survivor ticket count (iter %d)", iter)
                .isEqualTo(addedCount.get());

        // Symmetric: if A survived, that's the survivor and this assertion
        // is trivially true. If B survived, ticketsByRegion.get(a.id())
        // must be either null (never touched) or empty (moved to B).
        if (!survivor.id().equals(a.id())) {
            PerRegionTicketMap dying = manager.ticketsFor(a.id());
            assertThat(dying.chunkCount())
                    .as("dying region A must not retain a ticket bucket (iter %d)", iter)
                    .isZero();
        }
    }

    /**
     * Symmetric to {@link #addTicket_races_with_merge_never_lost}:
     * pre-seed a batch of tickets on A, then race concurrent
     * {@code removeTicket} calls keyed on A's id against a merge that
     * folds A into B. After the merge, the tickets have moved to B,
     * and the removes must have targeted B's map — not a fresh empty
     * map keyed on A's now-dead id. Post-run, B has no residual
     * bucket at {@code pos}.
     */
    @Test
    void removeTicket_races_with_merge_never_leaks() throws Exception {
        for (int iter = 0; iter < 100; iter++) {
            runRemoveRace(iter);
        }
    }

    private void runRemoveRace(int iter) throws Exception {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region a = regionizer.addChunk(new ChunkPos(0, 0));
        Region b = regionizer.addChunk(new ChunkPos(2, 0));
        assertThat(a).isNotSameAs(b);

        ChunkHolderManager manager = new ChunkHolderManager(WORLD, () -> regionizer);
        regionizer.addListener(manager);
        ChunkPos pos = new ChunkPos(0, 0);
        manager.createHolder(pos, a.id());

        // Pre-seed the tickets we'll race to remove.
        int ticketCount = 32;
        Ticket[] tickets = new Ticket[ticketCount];
        for (int i = 0; i < ticketCount; i++) {
            tickets[i] = Ticket.of(TicketType.PLUGIN, "remove-" + iter + "-" + i);
            manager.addTicket(a.id(), pos, tickets[i]);
        }

        CountDownLatch producerReady = new CountDownLatch(1);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger removedCount = new AtomicInteger();
        Thread producer = new Thread(
                () -> {
                    producerReady.countDown();
                    try {
                        startGate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < ticketCount; i++) {
                        if (manager.removeTicket(a.id(), pos, tickets[i])) removedCount.incrementAndGet();
                    }
                },
                "test-remove-producer-" + iter);
        producer.start();
        assertThat(producerReady.await(5, TimeUnit.SECONDS)).isTrue();

        Thread merger = new Thread(
                () -> {
                    try {
                        startGate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    regionizer.addChunk(new ChunkPos(1, 0));
                },
                "test-remove-merger-" + iter);
        merger.start();

        startGate.countDown();
        producer.join(5_000);
        merger.join(5_000);
        assertThat(producer.isAlive())
                .as("remove producer stuck (iter %d)", iter)
                .isFalse();
        assertThat(merger.isAlive()).as("remove merger stuck (iter %d)", iter).isFalse();

        Region survivor = regionizer.regionAtChunk(0, 0);
        assertThat(regionizer.regionAtChunk(2, 0)).isSameAs(survivor);

        // Every seeded ticket must have been successfully removed. A
        // regression that left removeTicket keyed on a stale RegionId
        // would find a fresh empty PerRegionTicketMap under A's id and
        // return false from PerRegionTicketMap.removeTicket, so
        // removedCount would drop below ticketCount.
        assertThat(removedCount.get())
                .as("all pre-seeded tickets must have been removed (iter %d)", iter)
                .isEqualTo(ticketCount);

        // Survivor's map must contain no residual bucket for pos —
        // PerRegionTicketMap.removeTicket clears the bucket when the
        // last ticket goes.
        PerRegionTicketMap survivorTickets = manager.ticketsFor(survivor.id());
        assertThat(survivorTickets.ticketsAt(pos))
                .as("survivor must have no residual ticket bucket at pos (iter %d)", iter)
                .isNull();
    }

    /**
     * Direct read-lock ordering proof — the H4 remediation must
     * actually serialise addTicket against a merge, not just re-route
     * a stale hint after the fact. T1 grabs the regionizer's write
     * lock (mimicking a merge in progress) and holds it. T2 calls
     * {@code addTicket}; because the manager was wired with a
     * regionizer accessor, addTicket takes the READ lock, which
     * cannot be acquired while the write lock is held. Assert T2 is
     * blocked; release the write lock; assert T2 completes and the
     * ticket lands.
     *
     * <p>A regression that removed the read-lock acquire from addTicket
     * would let T2 proceed immediately — the "still blocked" assertion
     * would fail.
     */
    @Test
    void addTicket_under_read_lock_blocks_merge() throws Exception {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region region = regionizer.addChunk(new ChunkPos(0, 0));
        ChunkHolderManager manager = new ChunkHolderManager(WORLD, () -> regionizer);
        regionizer.addListener(manager);
        ChunkPos pos = new ChunkPos(0, 0);
        manager.createHolder(pos, region.id());

        java.util.concurrent.locks.ReentrantReadWriteLock rw = pickRwLockFromRegionizer(regionizer);
        CountDownLatch writerHasLock = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        AtomicBoolean writeInterrupted = new AtomicBoolean();

        Thread blocker = new Thread(
                () -> {
                    rw.writeLock().lock();
                    try {
                        writerHasLock.countDown();
                        if (!releaseWriter.await(5, TimeUnit.SECONDS)) {
                            writeInterrupted.set(true);
                        }
                    } catch (InterruptedException ie) {
                        writeInterrupted.set(true);
                        Thread.currentThread().interrupt();
                    } finally {
                        rw.writeLock().unlock();
                    }
                },
                "test-blocker");
        blocker.start();
        assertThat(writerHasLock.await(5, TimeUnit.SECONDS)).isTrue();

        AtomicBoolean added = new AtomicBoolean();
        Thread producer = new Thread(
                () -> {
                    Ticket t = Ticket.of(TicketType.PLUGIN, "read-lock-blocker");
                    boolean ok = manager.addTicket(region.id(), pos, t);
                    added.set(ok);
                },
                "test-producer");
        producer.start();
        // Give the producer a generous moment to attempt the read-lock
        // acquire; without the H4 fix it would race past and complete.
        Thread.sleep(150);
        assertThat(added.get())
                .as("addTicket must not complete while regionizer write lock is held (round-5 H4)")
                .isFalse();
        assertThat(producer.isAlive()).isTrue();
        // The producer's ticket must NOT have landed in any per-region
        // map yet — the write itself is inside the read-lock critical
        // section.
        PerRegionTicketMap tickets = manager.ticketsFor(region.id());
        assertThat(tickets.ticketsAt(pos))
                .as("producer must not have written a ticket while blocked (round-5 H4)")
                .isNull();

        // Release: producer should complete promptly and the ticket
        // should land in the surviving region's map.
        releaseWriter.countDown();
        producer.join(5_000);
        blocker.join(5_000);
        assertThat(producer.isAlive()).isFalse();
        assertThat(blocker.isAlive()).isFalse();
        assertThat(writeInterrupted.get()).isFalse();
        assertThat(added.get()).isTrue();
        PerChunkTickets postBucket = manager.ticketsFor(region.id()).ticketsAt(pos);
        assertThat(postBucket).isNotNull();
        assertThat(postBucket.snapshot()).hasSize(1);
    }

    /**
     * Reflection reach into the regionizer's private rwLock so this test
     * can drive both sides of the read/write lock without changing
     * production API surface. Identical to {@code
     * RegionizedTaskQueueTest.pickRwLockFromRegionizer} — scoped to the
     * H4 regression proof, not a general-purpose accessor.
     */
    private static java.util.concurrent.locks.ReentrantReadWriteLock pickRwLockFromRegionizer(
            ThreadedRegionizer regionizer) {
        try {
            java.lang.reflect.Field f = ThreadedRegionizer.class.getDeclaredField("rwLock");
            f.setAccessible(true);
            return (java.util.concurrent.locks.ReentrantReadWriteLock) f.get(regionizer);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("rwLock field missing on ThreadedRegionizer (round-5 H4 regression)", e);
        }
    }
}
