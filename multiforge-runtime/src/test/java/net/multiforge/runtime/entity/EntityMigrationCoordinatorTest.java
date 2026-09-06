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
import java.util.List;
import java.util.UUID;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.Ticket;
import net.multiforge.runtime.chunk.TicketType;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionizedTaskQueue;
import net.multiforge.runtime.region.ThreadedRegionizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EntityMigrationCoordinatorTest {

    private static final WorldRef OW = WorldRef.of("minecraft:overworld");
    private static final WorldRef NETHER = WorldRef.of("minecraft:the_nether");

    private ThreadedRegionizer overworld;
    private ThreadedRegionizer nether;
    private RegionizedTaskQueue taskQueue;
    private EntityRegistry registry;
    private EntityMigrationCoordinator migrator;

    @BeforeEach
    void setup() {
        overworld = new ThreadedRegionizer(OW, 0);
        nether = new ThreadedRegionizer(NETHER, 0);
        taskQueue = new RegionizedTaskQueue((w, x, z) -> {
            ThreadedRegionizer rz = OW.dimensionId().equals(w.dimensionId()) ? overworld : nether;
            return rz.regionAtChunk(x, z);
        });
        registry = new EntityRegistry();
        migrator = new EntityMigrationCoordinator(taskQueue, registry);
    }

    @Test
    void singleEntityCrossesRegions() {
        Region source = overworld.addChunk(new ChunkPos(0, 0));
        Region dest = overworld.addChunk(new ChunkPos(100, 100));
        MigratingEntityRef ref = new MigratingEntityRef(UUID.randomUUID(), OW, new ChunkPos(0, 0));
        registry.add(ref, "payload-1");

        assertThat(migrator.beginMigration(ref, OW, new BlockPos(1600, 64, 1600)))
                .isTrue();
        // Task in destination region's inbox — drain it.
        int drained = taskQueue.drain(dest, Integer.MAX_VALUE);
        assertThat(drained).isEqualTo(1);

        EntityRegistry.Entry migrated = registry.get(ref.uuid());
        assertThat(migrated).isNotNull();
        assertThat(migrated.ref().world().dimensionId()).isEqualTo("minecraft:overworld");
        assertThat(migrated.ref().chunkPos()).isEqualTo(new ChunkPos(100, 100));
        assertThat(migrated.ref().migrationState()).isEqualTo(MigrationState.RESIDENT);
        assertThat(registry.size()).isEqualTo(1); // no duplicate
    }

    @Test
    void crossDimensionMigration() {
        overworld.addChunk(new ChunkPos(0, 0));
        Region netherTarget = nether.addChunk(new ChunkPos(0, 0));
        MigratingEntityRef ref = new MigratingEntityRef(UUID.randomUUID(), OW, new ChunkPos(0, 0));
        registry.add(ref, "portal-jumper");

        assertThat(migrator.beginMigration(ref, NETHER, new BlockPos(0, 64, 0))).isTrue();
        taskQueue.drain(netherTarget, Integer.MAX_VALUE);

        EntityRegistry.Entry migrated = registry.get(ref.uuid());
        assertThat(migrated).isNotNull();
        assertThat(migrated.ref().world().dimensionId()).isEqualTo("minecraft:the_nether");
    }

    @Test
    void vehicleAndPassengerTreeMoveAtomically() {
        overworld.addChunk(new ChunkPos(0, 0));
        Region dest = overworld.addChunk(new ChunkPos(100, 100));
        MigratingEntityRef vehicle = new MigratingEntityRef(UUID.randomUUID(), OW, new ChunkPos(0, 0));
        MigratingEntityRef rider = new MigratingEntityRef(UUID.randomUUID(), OW, new ChunkPos(0, 0));
        MigratingEntityRef pet = new MigratingEntityRef(UUID.randomUUID(), OW, new ChunkPos(0, 0));
        registry.add(vehicle, "boat");
        registry.add(rider, "player");
        registry.add(pet, "parrot");

        var passengers = List.of(new EntityMigrationCoordinator.PassengerSpec(
                rider, List.of(new EntityMigrationCoordinator.PassengerSpec(pet, List.of()))));

        assertThat(migrator.beginMigrationWithTree(vehicle, OW, new BlockPos(1600, 64, 1600), passengers))
                .isTrue();
        taskQueue.drain(dest, Integer.MAX_VALUE);

        assertThat(registry.size()).isEqualTo(3);
        assertThat(registry.get(vehicle.uuid()).ref().chunkPos()).isEqualTo(new ChunkPos(100, 100));
        assertThat(registry.get(rider.uuid()).ref().chunkPos()).isEqualTo(new ChunkPos(100, 100));
        assertThat(registry.get(pet.uuid()).ref().chunkPos()).isEqualTo(new ChunkPos(100, 100));
    }

    @Test
    void doubleMigrationAttemptRejected() {
        overworld.addChunk(new ChunkPos(0, 0));
        overworld.addChunk(new ChunkPos(100, 100));
        MigratingEntityRef ref = new MigratingEntityRef(UUID.randomUUID(), OW, new ChunkPos(0, 0));
        registry.add(ref, "x");

        assertThat(migrator.beginMigration(ref, OW, new BlockPos(1600, 64, 1600)))
                .isTrue();
        // Second attempt while MIGRATING should fail.
        assertThat(migrator.beginMigration(ref, OW, new BlockPos(200, 64, 200))).isFalse();
    }

    @Test
    void retiredEntityCannotMigrate() {
        overworld.addChunk(new ChunkPos(0, 0));
        overworld.addChunk(new ChunkPos(100, 100));
        MigratingEntityRef ref = new MigratingEntityRef(UUID.randomUUID(), OW, new ChunkPos(0, 0));
        registry.add(ref, "x");
        ref.retire();

        assertThat(migrator.beginMigration(ref, OW, new BlockPos(1600, 64, 1600)))
                .isFalse();
    }

    // === A1.4 — BORDER gating + adversarial unload (docs/design/entity-migration.md §3, §7.3) ===

    /** T8: completeAt must not materialize the entity while the destination holder is below BORDER. */
    @Test
    void completeAtDefersUntilDestinationHolderReachesBorderThenCompletes() {
        Region source = overworld.addChunk(new ChunkPos(0, 0));
        Region dest = overworld.addChunk(new ChunkPos(100, 100));
        ChunkHolderManager destManager = new ChunkHolderManager(OW);
        destManager.createHolder(new ChunkPos(100, 100), dest.id()); // exists but INACCESSIBLE

        EntityMigrationCoordinator gated = new EntityMigrationCoordinator(taskQueue, registry, w -> destManager, 2000);
        MigratingEntityRef ref = new MigratingEntityRef(UUID.randomUUID(), OW, new ChunkPos(0, 0));
        registry.add(ref, "payload");

        assertThat(gated.beginMigration(ref, OW, new BlockPos(1600, 64, 1600))).isTrue();
        assertThat(taskQueue.drain(dest, Integer.MAX_VALUE)).isEqualTo(1);

        // completeAt ran but must have deferred — holder is still below BORDER.
        assertThat(registry.get(ref.uuid())).isNull();

        // Promote to BORDER; the deferred completion fires asynchronously and re-enters via the
        // destination's own inbox, so poll-drain until it lands.
        destManager.addTicket(dest.id(), new ChunkPos(100, 100), Ticket.of(TicketType.PLUGIN, "promote"));
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            taskQueue.drain(dest, Integer.MAX_VALUE);
            return registry.get(ref.uuid()) != null;
        });

        assertThat(registry.get(ref.uuid()).ref().chunkPos()).isEqualTo(new ChunkPos(100, 100));
        assertThat(registry.get(ref.uuid()).ref().migrationState()).isEqualTo(MigrationState.RESIDENT);
    }

    /**
     * T9 / A1.4's adversarial fixture: the destination chunk is unloaded (never reaches BORDER)
     * for the whole test. The migration must time out, retire the original ref, and restore a
     * fresh RESIDENT ref at the source location — never leave anything permanently MIGRATING.
     */
    @Test
    void adversarialUnloadedDestinationTimesOutAndRestoresAtSource() {
        Region source = overworld.addChunk(new ChunkPos(0, 0));
        overworld.addChunk(new ChunkPos(100, 100)); // dest chunk exists in the regionizer...

        ChunkHolderManager holderManager = new ChunkHolderManager(OW);
        // ...but its holder is never created/promoted at all — simulates an adversarially
        // unloaded destination that never reaches BORDER within the deadline.
        // The source position, by contrast, is legitimately still loaded (the entity was just
        // resident there) so the fallback completion can succeed once it's attempted.
        holderManager.createHolder(new ChunkPos(0, 0), source.id());
        holderManager.addTicket(source.id(), new ChunkPos(0, 0), Ticket.of(TicketType.PLUGIN, "source-stays-loaded"));

        EntityMigrationCoordinator gated =
                new EntityMigrationCoordinator(taskQueue, registry, w -> holderManager, 50); // short deadline
        MigratingEntityRef ref = new MigratingEntityRef(UUID.randomUUID(), OW, new ChunkPos(0, 0));
        registry.add(ref, "payload");

        assertThat(gated.beginMigration(ref, OW, new BlockPos(1600, 64, 1600))).isTrue();
        Region dest = overworld.regionAtChunk(100, 100);
        taskQueue.drain(dest, Integer.MAX_VALUE); // runs completeAt -> arms the BORDER wait

        // The original ref must never come back RESIDENT — it ends RETIRED after the timeout.
        await().atMost(Duration.ofSeconds(5)).until(() -> ref.migrationState() == MigrationState.RETIRED);

        // A fresh ref materializes RESIDENT back at the source chunk once the fallback completion
        // (itself BORDER-gated against the source position, which is loaded) drains through.
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            taskQueue.drain(source, Integer.MAX_VALUE);
            return registry.get(ref.uuid()) != null;
        });
        assertThat(registry.get(ref.uuid()).ref().migrationState()).isEqualTo(MigrationState.RESIDENT);
        assertThat(registry.get(ref.uuid()).ref().world().dimensionId()).isEqualTo("minecraft:overworld");
    }
}
