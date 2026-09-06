/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.RegionId;
import org.junit.jupiter.api.Test;

/**
 * Coverage for Phase 2 of the M9 landing plan — every new field on
 * {@link NewChunkHolder} gets a default-state, set/get, and (where
 * concurrency matters) a small barrier-driven visibility test.
 */
class NewChunkHolderTest {

    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");
    private static final ChunkPos POS = new ChunkPos(0, 0);

    private NewChunkHolder holder() {
        return new NewChunkHolder(WORLD, POS);
    }

    // === existing behaviour still passes ===============================

    @Test
    void identityAndOwnerRoundTrip() {
        NewChunkHolder h = holder();
        assertThat(h.world()).isEqualTo(WORLD);
        assertThat(h.position()).isEqualTo(POS);
        assertThat(h.owningRegion()).isNull();
        assertThat(h.level()).isEqualTo(ChunkLoadLevel.INACCESSIBLE);
        assertThat(h.isDirty()).isFalse();
        assertThat(h.pendingFullLoadUpdate()).isFalse();

        RegionId id = new RegionId(7);
        assertThat(h.setOwningRegion(id)).isTrue();
        assertThat(h.owningRegion()).isEqualTo(id);
        assertThat(h.setOwningRegion(id)).isFalse();
    }

    // === Phase 2.1 — currentChunk ======================================

    @Test
    void currentChunkDefaultsNullAndRoundTrips() {
        NewChunkHolder h = holder();
        assertThat(h.getCurrentChunk()).isNull();
        Object payload = new Object();
        h.setCurrentChunk(payload);
        assertThat(h.getCurrentChunk()).isSameAs(payload);
        h.setCurrentChunk(null);
        assertThat(h.getCurrentChunk()).isNull();
    }

    @Test
    void currentChunkVisibleAcrossThreads() throws Exception {
        NewChunkHolder h = holder();
        Object payload = new Object();
        try (var scope = newScope(2)) {
            CountDownLatch published = new CountDownLatch(1);
            scope.submit(() -> {
                h.setCurrentChunk(payload);
                published.countDown();
            });
            var reader = scope.submit(() -> {
                published.await();
                return h.getCurrentChunk();
            });
            assertThat(reader.get(5, TimeUnit.SECONDS)).isSameAs(payload);
        }
    }

    // === Phase 2.2 — future gates ======================================

    @Test
    void futureGatesDefaultToIncompleteAndAreReplaceable() {
        NewChunkHolder h = holder();
        assertThat(h.getFullChunkFuture()).isNotNull();
        assertThat(h.getFullChunkFuture().isDone()).isFalse();
        assertThat(h.getTickingChunkFuture().isDone()).isFalse();
        assertThat(h.getEntityTickingChunkFuture().isDone()).isFalse();

        CompletableFuture<Object> f = CompletableFuture.completedFuture("full-payload");
        h.setFullChunkFuture(f);
        assertThat(h.getFullChunkFuture()).isSameAs(f);
        h.setFullChunkFuture(null);
        assertThat(h.getFullChunkFuture().isDone()).isFalse();

        h.setTickingChunkFuture(f);
        h.setEntityTickingChunkFuture(f);
        assertThat(h.getTickingChunkFuture().isDone()).isTrue();
        assertThat(h.getEntityTickingChunkFuture().isDone()).isTrue();
    }

    // === Phase 2.3 — status ladder =====================================

    @Test
    void statusLadderIsSizedFromCtorAndSlotsAreAtomic() {
        NewChunkHolder h = new NewChunkHolder(WORLD, POS, 12);
        assertThat(h.statusLadderSize()).isEqualTo(12);
        for (int i = 0; i < 12; i++) {
            assertThat(h.getStatusFuture(i)).isNull();
        }
        CompletableFuture<Object> f = CompletableFuture.completedFuture("empty-status");
        h.setStatusFuture(4, f);
        assertThat(h.getStatusFuture(4)).isSameAs(f);
        assertThat(h.getStatusFuture(3)).isNull();

        // Out-of-bounds read returns null; out-of-bounds write throws.
        assertThat(h.getStatusFuture(-1)).isNull();
        assertThat(h.getStatusFuture(99)).isNull();
        assertThatThrownBy(() -> h.setStatusFuture(-1, f)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> h.setStatusFuture(999, f)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void statusLadderDefaultConstructorUsesDefaultSize() {
        NewChunkHolder h = holder();
        assertThat(h.statusLadderSize()).isEqualTo(NewChunkHolder.DEFAULT_STATUS_LADDER_SIZE);
    }

    @Test
    void negativeLadderSizeRejected() {
        assertThatThrownBy(() -> new NewChunkHolder(WORLD, POS, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statusLadderWritesVisibleAcrossThreads() throws Exception {
        NewChunkHolder h = new NewChunkHolder(WORLD, POS, 8);
        try (var scope = newScope(4)) {
            CyclicBarrier gate = new CyclicBarrier(4);
            var writes = new java.util.ArrayList<java.util.concurrent.Future<Void>>();
            for (int i = 0; i < 4; i++) {
                final int slot = i * 2;
                writes.add(scope.submit(() -> {
                    gate.await();
                    h.setStatusFuture(slot, CompletableFuture.completedFuture("slot-" + slot));
                    return (Void) null;
                }));
            }
            for (var w : writes) w.get(5, TimeUnit.SECONDS);
            for (int i = 0; i < 4; i++) {
                assertThat(h.getStatusFuture(i * 2).join()).isEqualTo("slot-" + (i * 2));
            }
        }
    }

    // === Phase 2.4 — players ===========================================

    @Test
    void playerWatcherDefaultsEmptyAndAddRemoveWork() {
        NewChunkHolder h = holder();
        assertThat(h.hasPlayers()).isFalse();
        assertThat(h.playersSnapshot()).isEmpty();

        Object p1 = new Object();
        Object p2 = new Object();
        assertThat(h.addPlayer(p1)).isTrue();
        assertThat(h.addPlayer(p1)).isFalse(); // idempotent
        assertThat(h.addPlayer(p2)).isTrue();
        assertThat(h.hasPlayers()).isTrue();
        assertThat(h.playersSnapshot()).containsExactlyInAnyOrder(p1, p2);
        assertThat(h.playersWatching()).containsExactlyInAnyOrder(p1, p2);

        assertThat(h.removePlayer(p1)).isTrue();
        assertThat(h.removePlayer(p1)).isFalse();
        assertThat(h.playersSnapshot()).containsExactly(p2);
    }

    @Test
    void playerAddsFromManyThreadsAllLandInSet() throws Exception {
        NewChunkHolder h = holder();
        int threads = 16;
        int perThread = 50;
        try (var scope = newScope(threads)) {
            CyclicBarrier gate = new CyclicBarrier(threads);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Void>>();
            for (int t = 0; t < threads; t++) {
                final int me = t;
                futures.add(scope.submit(() -> {
                    gate.await();
                    for (int i = 0; i < perThread; i++) h.addPlayer("p-" + me + "-" + i);
                    return (Void) null;
                }));
            }
            for (var f : futures) f.get(5, TimeUnit.SECONDS);
            assertThat(h.playersSnapshot()).hasSize(threads * perThread);
        }
    }

    // === Phase 2.5 — entity cache ======================================

    @Test
    void entityCacheDefaultsNullRoundTripsAndInvalidates() {
        NewChunkHolder h = holder();
        assertThat(h.getEntitiesInChunk()).isNull();
        Iterable<Object> entities = List.of("e1", "e2");
        h.setEntitiesInChunk(entities);
        assertThat(h.getEntitiesInChunk()).isSameAs(entities);
        h.invalidateEntitiesCache();
        assertThat(h.getEntitiesInChunk()).isNull();
    }

    // === Phase 2.6 — block-entity cache ================================

    @Test
    void blockEntityCacheDefaultsNullRoundTripsAndInvalidates() {
        NewChunkHolder h = holder();
        assertThat(h.getBlockEntitiesInChunk()).isNull();
        Iterable<Object> bes = List.of("be1");
        h.setBlockEntitiesInChunk(bes);
        assertThat(h.getBlockEntitiesInChunk()).isSameAs(bes);
        h.invalidateBlockEntitiesCache();
        assertThat(h.getBlockEntitiesInChunk()).isNull();
    }

    // === Phase 2.7 — block-broadcast accumulator =======================

    @Test
    void blockChangeAccumulatesAndDrainsIntoFreshCopy() {
        NewChunkHolder h = holder();
        assertThat(h.pendingBroadcastCount()).isZero();
        assertThat(h.drainBlockChanges()).isEmpty();

        h.blockChanged(1, 2, 3);
        h.blockChanged(1, 2, 3); // dedup — same packed short
        h.blockChanged(15, 255, 15);
        assertThat(h.pendingBroadcastCount()).isEqualTo(3); // counter includes dupes
        Set<Short> drained = h.drainBlockChanges();
        assertThat(drained).hasSize(2);
        // Draining resets both the set and the counter.
        assertThat(h.pendingBroadcastCount()).isZero();
        assertThat(h.drainBlockChanges()).isEmpty();
    }

    @Test
    void blockChangeFromManyThreadsCoalescesUnderLock() throws Exception {
        NewChunkHolder h = holder();
        int threads = 8;
        int perThread = 200;
        AtomicInteger writesIssued = new AtomicInteger();
        try (var scope = newScope(threads)) {
            CyclicBarrier gate = new CyclicBarrier(threads);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Void>>();
            for (int t = 0; t < threads; t++) {
                final int me = t;
                futures.add(scope.submit(() -> {
                    gate.await();
                    for (int i = 0; i < perThread; i++) {
                        h.blockChanged(me & 0xF, i & 0xFF, i & 0xF);
                        writesIssued.incrementAndGet();
                    }
                    return (Void) null;
                }));
            }
            for (var f : futures) f.get(5, TimeUnit.SECONDS);
            assertThat(h.pendingBroadcastCount()).isEqualTo(writesIssued.get());
            Set<Short> drained = h.drainBlockChanges();
            // No writes were lost; every unique packed short lands in the set.
            Set<Short> expected = new HashSet<>();
            for (int t = 0; t < threads; t++) {
                for (int i = 0; i < perThread; i++) {
                    expected.add((short) (((t & 0xF) << 12) | ((i & 0xF) << 8) | (i & 0xFF)));
                }
            }
            assertThat(drained).isEqualTo(expected);
        }
    }

    // === Phase 2.8 — light-change accumulator ==========================

    @Test
    void sectionLightAccumulatesAndDrainsIntoFreshBitSet() {
        NewChunkHolder h = holder();
        assertThat(h.drainLightChanges().isEmpty()).isTrue();
        h.sectionLightChanged(0);
        h.sectionLightChanged(7);
        h.sectionLightChanged(23);
        BitSet drained = h.drainLightChanges();
        assertThat(drained.get(0)).isTrue();
        assertThat(drained.get(7)).isTrue();
        assertThat(drained.get(23)).isTrue();
        assertThat(drained.cardinality()).isEqualTo(3);
        assertThat(h.drainLightChanges().isEmpty()).isTrue();
    }

    @Test
    void sectionLightFromManyThreadsRegistersAllBits() throws Exception {
        NewChunkHolder h = holder();
        int threads = 8;
        try (var scope = newScope(threads)) {
            CyclicBarrier gate = new CyclicBarrier(threads);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Void>>();
            for (int t = 0; t < threads; t++) {
                final int me = t;
                futures.add(scope.submit(() -> {
                    gate.await();
                    for (int i = 0; i < 32; i++) h.sectionLightChanged(me * 32 + i);
                    return (Void) null;
                }));
            }
            for (var f : futures) f.get(5, TimeUnit.SECONDS);
            BitSet drained = h.drainLightChanges();
            assertThat(drained.cardinality()).isEqualTo(threads * 32);
        }
    }

    // === Phase 2.9 — save / send sync ==================================

    @Test
    void saveAndSendSyncFuturesDefaultCompletedAndReplaceable() {
        NewChunkHolder h = holder();
        assertThat(h.getSaveSyncFuture().isDone()).isTrue();
        assertThat(h.getSendSyncFuture().isDone()).isTrue();

        CompletableFuture<Void> f = new CompletableFuture<>();
        h.setSaveSyncFuture(f);
        h.setSendSyncFuture(f);
        assertThat(h.getSaveSyncFuture()).isSameAs(f);
        assertThat(h.getSendSyncFuture()).isSameAs(f);

        // null resets to a completed sentinel.
        h.setSaveSyncFuture(null);
        h.setSendSyncFuture(null);
        assertThat(h.getSaveSyncFuture().isDone()).isTrue();
        assertThat(h.getSendSyncFuture().isDone()).isTrue();
    }

    @Test
    void addSendDependencyChainsIntoTheGate() {
        NewChunkHolder h = holder();
        CompletableFuture<Void> dep = new CompletableFuture<>();
        h.addSendDependency(dep);
        assertThat(h.getSendSyncFuture().isDone()).isFalse();
        dep.complete(null);
        assertThat(h.getSendSyncFuture().isDone()).isTrue();
    }

    @Test
    void addSendDependencyIgnoresNullAndCompleted() {
        NewChunkHolder h = holder();
        CompletableFuture<Void> before = h.getSendSyncFuture();
        h.addSendDependency(null);
        h.addSendDependency(CompletableFuture.completedFuture(null));
        assertThat(h.getSendSyncFuture()).isSameAs(before);
    }

    // === Phase 2.10 — level-change listener ============================

    @Test
    void levelChangeListenerFiresOnSetLevelWithOldAndNewLevels() {
        NewChunkHolder h = holder();
        AtomicInteger fires = new AtomicInteger();
        int[] captured = new int[2]; // [oldLevel, newLevel]
        h.setLevelChangeListener((pos, oldLevel, newLevel, setter) -> {
            assertThat(pos).isEqualTo(POS);
            captured[0] = oldLevel.getAsInt();
            captured[1] = newLevel;
            fires.incrementAndGet();
        });
        assertThat(h.getLevelChangeListener()).isNotNull();

        h.setLevel(ChunkLoadLevel.BORDER);
        assertThat(fires.get()).isEqualTo(1);
        assertThat(captured[0]).isEqualTo(ChunkLoadLevel.INACCESSIBLE.distance());
        assertThat(captured[1]).isEqualTo(ChunkLoadLevel.BORDER.distance());

        // No-op transition does not fire.
        h.setLevel(ChunkLoadLevel.BORDER);
        assertThat(fires.get()).isEqualTo(1);

        // Real transition fires again.
        h.setLevel(ChunkLoadLevel.TICKING);
        assertThat(fires.get()).isEqualTo(2);
        assertThat(captured[1]).isEqualTo(ChunkLoadLevel.TICKING.distance());
    }

    @Test
    void levelChangeListenerSetterRepublishesLevel() {
        NewChunkHolder h = holder();
        h.setLevelChangeListener((pos, oldLevel, newLevel, setter) -> {
            // Force the effective level up to INACCESSIBLE on any change.
            if (newLevel != ChunkLoadLevel.INACCESSIBLE.distance()) {
                setter.accept(ChunkLoadLevel.INACCESSIBLE.distance());
            }
        });
        h.setLevel(ChunkLoadLevel.BORDER);
        assertThat(h.level()).isEqualTo(ChunkLoadLevel.INACCESSIBLE);
    }

    @Test
    void levelChangeListenerCanBeCleared() {
        NewChunkHolder h = holder();
        AtomicInteger fires = new AtomicInteger();
        h.setLevelChangeListener((pos, oldLevel, newLevel, setter) -> fires.incrementAndGet());
        h.setLevel(ChunkLoadLevel.BORDER);
        assertThat(fires.get()).isEqualTo(1);
        h.setLevelChangeListener(null);
        h.setLevel(ChunkLoadLevel.TICKING);
        assertThat(fires.get()).isEqualTo(1);
    }

    // === helpers ========================================================

    /** Minimal try-with-resources ExecutorService wrapper. */
    private static ExecScope newScope(int threads) {
        return new ExecScope(Executors.newFixedThreadPool(threads));
    }

    private record ExecScope(ExecutorService pool) implements AutoCloseable {
        <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
            return pool.submit(task);
        }

        void submit(Runnable task) {
            pool.submit(task);
        }

        @Override
        public void close() {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) pool.shutdownNow();
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
