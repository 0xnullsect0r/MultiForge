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
package net.multiforge.runtime.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import org.junit.jupiter.api.Test;

/**
 * A1.3 — {@link MigratingEntityRef#beginPassengerTreeSnapshot(List)} atomicity, and the CAS-based
 * fixes to {@link MigratingEntityRef#retire()} / {@link MigratingEntityRef#completeMigration}.
 * Maps to docs/design/entity-migration.md §1.3, §1.4, §2, test invariants T6 and T10.
 */
class MigratingEntityRefPassengerAtomicityTest {

    private static final WorldRef OW = WorldRef.of("minecraft:overworld");

    private static MigratingEntityRef ref() {
        return new MigratingEntityRef(UUID.randomUUID(), OW, new ChunkPos(0, 0));
    }

    // === beginPassengerTreeSnapshot: happy path =============================================

    @Test
    void allRefsFlipToMigratingOnSuccess() {
        List<MigratingEntityRef> tree = List.of(ref(), ref(), ref());
        assertThat(MigratingEntityRef.beginPassengerTreeSnapshot(tree)).isTrue();
        for (MigratingEntityRef r : tree) {
            assertThat(r.migrationState()).isEqualTo(MigrationState.MIGRATING);
        }
    }

    /** T6: a losing CAS anywhere in the pass rolls back every ref already flipped, atomically. */
    @Test
    void aSingleLosingCasRollsBackTheWholeTree() {
        MigratingEntityRef vehicle = ref();
        MigratingEntityRef rider = ref();
        MigratingEntityRef alreadyMigrating = ref();
        alreadyMigrating.beginMigration(); // pre-flip so the tree's CAS on it will fail
        MigratingEntityRef untouched = ref();

        List<MigratingEntityRef> tree = List.of(vehicle, rider, alreadyMigrating, untouched);
        assertThat(MigratingEntityRef.beginPassengerTreeSnapshot(tree)).isFalse();

        // Every ref that WAS flipped by this call must be rolled back to RESIDENT...
        assertThat(vehicle.migrationState()).isEqualTo(MigrationState.RESIDENT);
        assertThat(rider.migrationState()).isEqualTo(MigrationState.RESIDENT);
        // ...the one that was never touched by this call stays exactly as it was...
        assertThat(untouched.migrationState()).isEqualTo(MigrationState.RESIDENT);
        // ...and the one that caused the failure keeps its pre-existing state (still MIGRATING
        // from its own earlier, independent beginMigration() call — never touched by the rollback).
        assertThat(alreadyMigrating.migrationState()).isEqualTo(MigrationState.MIGRATING);
    }

    @Test
    void retiredRefInTreeAbortsTheWholeAttempt() {
        MigratingEntityRef vehicle = ref();
        MigratingEntityRef deadPassenger = ref();
        deadPassenger.retire();

        List<MigratingEntityRef> tree = List.of(vehicle, deadPassenger);
        assertThat(MigratingEntityRef.beginPassengerTreeSnapshot(tree)).isFalse();
        assertThat(vehicle.migrationState()).isEqualTo(MigrationState.RESIDENT);
        assertThat(deadPassenger.migrationState()).isEqualTo(MigrationState.RETIRED);
    }

    /**
     * T7-adjacent stress: many threads race {@link MigratingEntityRef#beginPassengerTreeSnapshot}
     * concurrently against a small pool of shared 5-deep mount stacks, while a concurrent observer
     * samples every ref in every tree.
     *
     * <p>Note on what "no mixed state" can mean for a lock-free multi-CAS pass: a passive observer
     * polling live state <em>can</em> legitimately catch the winning attempt's own single-pass CAS
     * loop mid-flight (ref[0] MIGRATING, ref[1] not yet) — flipping N independent
     * {@code AtomicReference}s one at a time cannot itself be made instantaneous without a lock,
     * which CLAUDE.md rule 4 rules out anyway. That transient, monotonically-progressing window is
     * not what docs/design/entity-migration.md §2.5 forbids. What the invariant actually forbids —
     * and what this test asserts — is <b>regression</b>: once a tree has been observed fully
     * MIGRATING (the CAS pass committed), it must never again be observed as anything other than
     * fully MIGRATING. Combined with {@link #aSingleLosingCasRollsBackTheWholeTree} (which proves
     * a losing attempt never leaves a partial mark), this covers the full "never partial, never
     * un-does a commit" claim. Full T7 (observing an actual in-flight tree walk under a stampede of
     * <em>both</em> begins and end-to-end completions) is A2.10's job once the Vanilla passenger
     * walk exists.
     *
     * <p>Deliberately does <em>not</em> reset a tree back to RESIDENT after a successful attempt —
     * once a tree wins its race it stays MIGRATING for the rest of the test, exactly like a real
     * migration's old ref instance (§1.1) — every subsequent attempt against it is a guaranteed-safe
     * no-op (the first ref's CAS fails immediately, nothing is flipped, nothing rolls back).
     */
    @Test
    void concurrentStampedeNeverRegressesAFullyCommittedTree() throws InterruptedException {
        int stackDepth = 5;
        int treeCount = 8;
        int attemptsPerTree = 500;

        List<List<MigratingEntityRef>> trees = new ArrayList<>(treeCount);
        for (int t = 0; t < treeCount; t++) {
            List<MigratingEntityRef> tree = new ArrayList<>(stackDepth);
            for (int i = 0; i < stackDepth; i++) tree.add(ref());
            trees.add(tree);
        }

        AtomicBoolean stop = new AtomicBoolean(false);
        Set<String> violations = new CopyOnWriteArraySet<>();
        boolean[] everFullyCommitted = new boolean[treeCount];
        CountDownLatch observerReady = new CountDownLatch(1);

        Thread observer = new Thread(() -> {
            observerReady.countDown();
            while (!stop.get()) {
                for (int t = 0; t < treeCount; t++) {
                    boolean allMigrating = true;
                    for (MigratingEntityRef r : trees.get(t)) {
                        if (r.migrationState() != MigrationState.MIGRATING) {
                            allMigrating = false;
                            break;
                        }
                    }
                    if (allMigrating) {
                        everFullyCommitted[t] = true;
                    } else if (everFullyCommitted[t]) {
                        // Regression: this tree was fully committed on an earlier sample but is
                        // no longer — that would mean something un-did (part of) a completed
                        // atomic commit, which must never happen.
                        violations.add("tree " + t + " regressed after being fully committed");
                    }
                }
            }
        });
        observer.setDaemon(true);
        observer.start();
        observerReady.await(5, TimeUnit.SECONDS);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < attemptsPerTree; i++) {
                for (List<MigratingEntityRef> tree : trees) {
                    results.add(pool.submit(() -> MigratingEntityRef.beginPassengerTreeSnapshot(tree)));
                }
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            // Exactly one attempt per tree may ever have won (subsequent attempts are safe no-ops).
            int totalWins = 0;
            for (var f : results) {
                if (f.get()) totalWins++;
            }
            assertThat(totalWins).isEqualTo(treeCount);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new AssertionError(e);
        } finally {
            stop.set(true);
            observer.join(5000);
        }

        assertThat(violations).isEmpty();
        for (List<MigratingEntityRef> tree : trees) {
            for (MigratingEntityRef r : tree) {
                assertThat(r.migrationState()).isEqualTo(MigrationState.MIGRATING);
            }
        }
    }

    // === retire(): CAS-based, idempotent, deferred while MIGRATING ==========================

    @Test
    void retireFromResidentTransitionsToRetired() {
        MigratingEntityRef r = ref();
        assertThat(r.retire()).isTrue();
        assertThat(r.migrationState()).isEqualTo(MigrationState.RETIRED);
    }

    @Test
    void secondRetireCallIsNoOp() {
        MigratingEntityRef r = ref();
        assertThat(r.retire()).isTrue();
        assertThat(r.retire()).isFalse();
        assertThat(r.migrationState()).isEqualTo(MigrationState.RETIRED);
    }

    /** T10: retire() while MIGRATING defers rather than clobbering state (§1.4). */
    @Test
    void retireWhileMigratingDefersAndDoesNotOverwriteState() {
        MigratingEntityRef r = ref();
        assertThat(r.beginMigration()).isTrue();

        assertThat(r.retire()).isTrue(); // deferred
        assertThat(r.migrationState()).isEqualTo(MigrationState.MIGRATING); // NOT clobbered to RETIRED
        assertThat(r.consumePendingRetire()).isTrue();
        assertThat(r.consumePendingRetire()).isFalse(); // consumed exactly once
    }

    @Test
    void pendingRetireDoesNotLeakAcrossUnrelatedRefs() {
        MigratingEntityRef a = ref();
        MigratingEntityRef b = ref();
        a.beginMigration();
        a.retire();

        assertThat(a.consumePendingRetire()).isTrue();
        assertThat(b.consumePendingRetire()).isFalse();
    }

    // === completeMigration(): CAS-first, publish-on-success-only ============================

    @Test
    void completeMigrationPublishesLocationOnlyOnCasSuccess() {
        MigratingEntityRef r = ref();
        r.beginMigration();
        WorldRef nether = WorldRef.of("minecraft:the_nether");

        assertThat(r.completeMigration(nether, new ChunkPos(5, 5))).isTrue();
        assertThat(r.world().dimensionId()).isEqualTo("minecraft:the_nether");
        assertThat(r.chunkPos()).isEqualTo(new ChunkPos(5, 5));
        assertThat(r.migrationState()).isEqualTo(MigrationState.RESIDENT);
    }

    /** Duplicate/out-of-order delivery: a second completeMigration() call must not move location. */
    @Test
    void duplicateCompleteMigrationDoesNotClobberLocation() {
        MigratingEntityRef r = ref();
        r.beginMigration();
        WorldRef nether = WorldRef.of("minecraft:the_nether");
        WorldRef end = WorldRef.of("minecraft:the_end");

        assertThat(r.completeMigration(nether, new ChunkPos(5, 5))).isTrue();
        // A stale/duplicate redelivery tries to move it again — must fail and must not touch location.
        assertThat(r.completeMigration(end, new ChunkPos(9, 9))).isFalse();

        assertThat(r.world().dimensionId()).isEqualTo("minecraft:the_nether");
        assertThat(r.chunkPos()).isEqualTo(new ChunkPos(5, 5));
    }

    @Test
    void completeMigrationOnNonMigratingRefIsNoOp() {
        MigratingEntityRef r = ref(); // starts RESIDENT, never began migration
        WorldRef nether = WorldRef.of("minecraft:the_nether");
        ChunkPos original = r.chunkPos();

        assertThat(r.completeMigration(nether, new ChunkPos(1, 1))).isFalse();
        assertThat(r.chunkPos()).isEqualTo(original);
        assertThat(r.world().dimensionId()).isEqualTo("minecraft:overworld");
    }
}
