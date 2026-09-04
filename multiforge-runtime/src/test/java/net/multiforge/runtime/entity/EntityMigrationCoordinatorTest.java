/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
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
}
