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
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.Ticket;
import net.multiforge.runtime.chunk.TicketType;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionizedTaskQueue;
import net.multiforge.runtime.region.ThreadedRegionizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M4 Track A2.10 regression coverage — MC-free (stub {@link MigratingEntityRef} + stub {@link
 * Region} setup, per the Track A2 work order), exercising {@link EntityMigrationCoordinator} at a
 * scale and under an adversarial fixture that {@link EntityMigrationCoordinatorTest}'s
 * unit-level cases don't reach:
 *
 * <ol>
 *   <li>{@link #fiveHundredMobCrossRegionStampede()} — 500 independent entities migrating from one
 *       region to another concurrently, exercising {@link RegionizedTaskQueue}'s per-region inbox
 *       and {@link EntityRegistry}'s {@code ConcurrentHashMap} under real thread contention rather
 *       than the single-threaded shape of the existing unit tests.
 *   <li>{@link #fiveDeepPassengerStackMovesAtomically()} — a boat -&gt; minecart -&gt; player -&gt;
 *       parrot -&gt; bee mount chain (docs/design/entity-migration.md §2's "vehicle+passenger tree
 *       is atomic" contract, at a deeper nesting than {@code
 *       EntityMigrationCoordinatorTest#vehicleAndPassengerTreeMoveAtomically}'s 3-deep case).
 *   <li>{@link #adversarialRapidLoadUnloadEventuallyAbortsAndRestores()} — a destination holder
 *       whose BORDER ticket is added and immediately removed several times in a row (simulating
 *       load/unload churn) before settling unloaded, forcing the BORDER-wait deadline
 *       (docs/design/entity-migration.md §3.3/§7.3) to actually expire and exercising {@code
 *       abortAndRestore}'s retire-original + restore-fresh-at-source path end to end.
 * </ol>
 */
class EntityMigrationStressTest {

    private static final WorldRef OW = WorldRef.of("minecraft:overworld");
    private static final ChunkPos SOURCE_CHUNK = new ChunkPos(0, 0);
    private static final ChunkPos DEST_CHUNK = new ChunkPos(1000, 1000);

    private ThreadedRegionizer regionizer;
    private RegionizedTaskQueue taskQueue;
    private EntityRegistry registry;
    private EntityMigrationCoordinator migrator;
    private ExecutorService pool;

    @BeforeEach
    void setup() {
        regionizer = new ThreadedRegionizer(OW, 0);
        taskQueue = RegionizedTaskQueue.of(regionizer);
        registry = new EntityRegistry();
        migrator = new EntityMigrationCoordinator(taskQueue, registry);
    }

    @AfterEach
    void teardown() {
        registry.close();
        if (pool != null) pool.shutdownNow();
    }

    // === 500-mob cross-region stampede ============================================================

    @Test
    void fiveHundredMobCrossRegionStampede() throws InterruptedException {
        final int mobCount = 500;
        Region source = regionizer.addChunk(SOURCE_CHUNK);
        Region dest = regionizer.addChunk(DEST_CHUNK);

        List<MigratingEntityRef> mobs = new ArrayList<>(mobCount);
        for (int i = 0; i < mobCount; i++) {
            MigratingEntityRef ref = new MigratingEntityRef(UUID.randomUUID(), OW, SOURCE_CHUNK);
            registry.add(ref, "mob-" + i);
            mobs.add(ref);
        }
        assertThat(registry.size()).isEqualTo(mobCount);

        // Fire all 500 beginMigration calls concurrently from a worker pool — exercises the
        // registry + task-queue inbox under real contention, not just the single-threaded shape of
        // EntityMigrationCoordinatorTest's cases.
        pool = Executors.newFixedThreadPool(16);
        CountDownLatch ready = new CountDownLatch(1);
        List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>(mobCount);
        for (MigratingEntityRef ref : mobs) {
            results.add(pool.submit(() -> {
                ready.await();
                return migrator.beginMigration(ref, OW, new BlockPos(16000, 64, 16000));
            }));
        }
        ready.countDown();

        int accepted = 0;
        for (var f : results) {
            try {
                if (Boolean.TRUE.equals(f.get(10, TimeUnit.SECONDS))) accepted++;
            } catch (Exception e) {
                throw new AssertionError("beginMigration task failed", e);
            }
        }
        assertThat(accepted).isEqualTo(mobCount); // every distinct-UUID attempt should win its own CAS

        // Drain the destination's inbox until every one of the 500 completions has landed —
        // draining is itself concurrent-safe (RegionizedTaskQueue's inbox is a ConcurrentLinkedQueue)
        // but a single pass may race the still-in-flight enqueues from the pool above.
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            taskQueue.drain(dest, Integer.MAX_VALUE);
            return mobs.stream().allMatch(ref -> {
                EntityRegistry.Entry e = registry.get(ref.uuid());
                return e != null && e.ref().migrationState() == MigrationState.RESIDENT;
            });
        });

        assertThat(registry.size()).isEqualTo(mobCount); // no duplicates, no drops
        Set<UUID> distinctDestUuids =
                mobs.stream().map(ref -> registry.get(ref.uuid()).ref().uuid()).collect(Collectors.toSet());
        assertThat(distinctDestUuids).hasSize(mobCount);
        for (MigratingEntityRef ref : mobs) {
            assertThat(registry.get(ref.uuid()).ref().chunkPos()).isEqualTo(DEST_CHUNK);
        }
        assertThat(taskQueue.drain(source, Integer.MAX_VALUE)).isZero(); // nothing stuck at the source
    }

    // === 5-deep passenger stack ====================================================================

    @Test
    void fiveDeepPassengerStackMovesAtomically() {
        Region dest = regionizer.addChunk(DEST_CHUNK);
        regionizer.addChunk(SOURCE_CHUNK);

        // boat -> minecart -> player -> parrot -> bee, five levels deep.
        MigratingEntityRef boat = new MigratingEntityRef(UUID.randomUUID(), OW, SOURCE_CHUNK);
        MigratingEntityRef minecart = new MigratingEntityRef(UUID.randomUUID(), OW, SOURCE_CHUNK);
        MigratingEntityRef player = new MigratingEntityRef(UUID.randomUUID(), OW, SOURCE_CHUNK);
        MigratingEntityRef parrot = new MigratingEntityRef(UUID.randomUUID(), OW, SOURCE_CHUNK);
        MigratingEntityRef bee = new MigratingEntityRef(UUID.randomUUID(), OW, SOURCE_CHUNK);
        List<MigratingEntityRef> all = List.of(boat, minecart, player, parrot, bee);
        registry.add(boat, "boat");
        registry.add(minecart, "minecart");
        registry.add(player, "player");
        registry.add(parrot, "parrot");
        registry.add(bee, "bee");

        var tree = List.of(new EntityMigrationCoordinator.PassengerSpec(
                minecart,
                List.of(new EntityMigrationCoordinator.PassengerSpec(
                        player,
                        List.of(new EntityMigrationCoordinator.PassengerSpec(
                                parrot, List.of(new EntityMigrationCoordinator.PassengerSpec(bee, List.of()))))))));

        // Every ref in the tree must be MIGRATING the instant the CAS pass wins — before any
        // completion has drained — matching §2.5's "no external observer can catch a mixed state".
        assertThat(migrator.beginMigrationWithTree(boat, OW, new BlockPos(16000, 64, 16000), tree))
                .isTrue();
        for (MigratingEntityRef ref : all) {
            assertThat(ref.migrationState()).isEqualTo(MigrationState.MIGRATING);
        }

        assertThat(taskQueue.drain(dest, Integer.MAX_VALUE)).isEqualTo(1);

        assertThat(registry.size()).isEqualTo(5);
        for (MigratingEntityRef ref : all) {
            EntityRegistry.Entry entry = registry.get(ref.uuid());
            assertThat(entry).isNotNull();
            assertThat(entry.ref().migrationState()).isEqualTo(MigrationState.RESIDENT);
            assertThat(entry.ref().chunkPos()).isEqualTo(DEST_CHUNK);
        }
    }

    /**
     * A losing CAS anywhere in a 5-deep tree must roll back every ref that already flipped to
     * MIGRATING earlier in the same pass (docs/design/entity-migration.md §2.5) — here the
     * second-from-bottom passenger is pre-retired so its {@code beginMigration} CAS is guaranteed
     * to lose.
     */
    @Test
    void fiveDeepPassengerStackAbortsCleanlyOnMidTreeCasFailure() {
        regionizer.addChunk(SOURCE_CHUNK);
        regionizer.addChunk(DEST_CHUNK);

        MigratingEntityRef boat = new MigratingEntityRef(UUID.randomUUID(), OW, SOURCE_CHUNK);
        MigratingEntityRef minecart = new MigratingEntityRef(UUID.randomUUID(), OW, SOURCE_CHUNK);
        MigratingEntityRef player = new MigratingEntityRef(UUID.randomUUID(), OW, SOURCE_CHUNK);
        MigratingEntityRef parrot = new MigratingEntityRef(UUID.randomUUID(), OW, SOURCE_CHUNK);
        MigratingEntityRef bee = new MigratingEntityRef(UUID.randomUUID(), OW, SOURCE_CHUNK);
        registry.add(boat, "boat");
        registry.add(minecart, "minecart");
        registry.add(player, "player");
        registry.add(parrot, "parrot");
        registry.add(bee, "bee");
        parrot.retire(); // guarantees parrot.beginMigration() loses its CAS below

        var tree = List.of(new EntityMigrationCoordinator.PassengerSpec(
                minecart,
                List.of(new EntityMigrationCoordinator.PassengerSpec(
                        player,
                        List.of(new EntityMigrationCoordinator.PassengerSpec(
                                parrot, List.of(new EntityMigrationCoordinator.PassengerSpec(bee, List.of()))))))));

        assertThat(migrator.beginMigrationWithTree(boat, OW, new BlockPos(16000, 64, 16000), tree))
                .isFalse();

        // boat/minecart/player flipped to MIGRATING before the failure and must be rolled back;
        // bee was never reached; parrot stays RETIRED throughout.
        assertThat(boat.migrationState()).isEqualTo(MigrationState.RESIDENT);
        assertThat(minecart.migrationState()).isEqualTo(MigrationState.RESIDENT);
        assertThat(player.migrationState()).isEqualTo(MigrationState.RESIDENT);
        assertThat(parrot.migrationState()).isEqualTo(MigrationState.RETIRED);
        assertThat(bee.migrationState()).isEqualTo(MigrationState.RESIDENT);
        assertThat(registry.size()).isEqualTo(5); // nothing removed from the registry
    }

    // === Adversarial rapid load/unload -> BORDER retry -> abortAndRestore =========================

    @Test
    void adversarialRapidLoadUnloadEventuallyAbortsAndRestores() {
        Region source = regionizer.addChunk(SOURCE_CHUNK);
        Region dest = regionizer.addChunk(DEST_CHUNK);

        ChunkHolderManager holderManager = new ChunkHolderManager(OW);
        holderManager.createHolder(SOURCE_CHUNK, source.id());
        holderManager.addTicket(source.id(), SOURCE_CHUNK, Ticket.of(TicketType.PLUGIN, "source-stays-loaded"));
        holderManager.createHolder(DEST_CHUNK, dest.id()); // exists but never durably promoted below

        long shortDeadlineMs = 80L;
        EntityMigrationCoordinator gated =
                new EntityMigrationCoordinator(taskQueue, registry, w -> holderManager, shortDeadlineMs);
        MigratingEntityRef ref = new MigratingEntityRef(UUID.randomUUID(), OW, SOURCE_CHUNK);
        registry.add(ref, "payload");

        assertThat(gated.beginMigration(ref, OW, new BlockPos(16000, 64, 16000)))
                .isTrue();

        // Rapid-load-unload churn on the destination holder: add the BORDER-promoting ticket and
        // immediately remove it again, several times in a row, draining the destination inbox
        // between each toggle so any premature "ready" observation re-checks the holder's *current*
        // (already-demoted) level and re-arms rather than materializing (§3.3's re-check contract) —
        // never letting the drain observe the ticket in the added state.
        Ticket flicker = Ticket.of(TicketType.PLUGIN, "flicker");
        for (int i = 0; i < 5; i++) {
            holderManager.addTicket(dest.id(), DEST_CHUNK, flicker);
            holderManager.removeTicket(dest.id(), DEST_CHUNK, flicker);
            taskQueue.drain(dest, Integer.MAX_VALUE);
        }

        // Ticket now permanently removed (holder sits below BORDER) — the deadline armed by the
        // *last* re-arm above must eventually elapse for real.
        await().atMost(Duration.ofSeconds(5)).until(() -> ref.migrationState() == MigrationState.RETIRED);

        // Fallback completion re-completes at the source chunk once its own BORDER gate (against
        // the legitimately-loaded source holder) passes.
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            taskQueue.drain(source, Integer.MAX_VALUE);
            EntityRegistry.Entry e = registry.get(ref.uuid());
            return e != null && e.ref().migrationState() == MigrationState.RESIDENT;
        });
        EntityRegistry.Entry restored = registry.get(ref.uuid());
        assertThat(restored.ref().world().dimensionId()).isEqualTo("minecraft:overworld");
        assertThat(restored.ref().chunkPos()).isEqualTo(SOURCE_CHUNK);
    }
}
