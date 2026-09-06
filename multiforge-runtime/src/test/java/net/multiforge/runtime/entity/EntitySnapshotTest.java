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
package net.multiforge.runtime.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.WorldRef;
import org.junit.jupiter.api.Test;

/**
 * A1.1 — round-trip coverage for {@link EntitySnapshot}'s {@code byte[]} payload (the pure-Java
 * stand-in for Vanilla's {@code CompoundTag}, docs/design/entity-migration.md §6.1) and its
 * recursive passenger tree (§2.4, §6.2).
 */
class EntitySnapshotTest {

    private static final WorldRef OW = WorldRef.of("minecraft:overworld");

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void payloadRoundTripsByteForByte() {
        byte[] payload = bytes("some-nbt-bytes");
        EntitySnapshot snap = new EntitySnapshot(UUID.randomUUID(), OW, new BlockPos(1, 2, 3), payload, List.of());

        assertThat(snap.payload()).isEqualTo(payload);
        assertThat(new String(snap.payload(), StandardCharsets.UTF_8)).isEqualTo("some-nbt-bytes");
    }

    @Test
    void payloadIsDefensivelyCopiedOnConstruction() {
        byte[] source = bytes("original");
        EntitySnapshot snap = new EntitySnapshot(UUID.randomUUID(), OW, new BlockPos(0, 0, 0), source, List.of());

        source[0] = (byte) 'X'; // mutate caller's array after construction

        assertThat(new String(snap.payload(), StandardCharsets.UTF_8)).isEqualTo("original");
    }

    @Test
    void payloadAccessorReturnsDefensiveCopyEachTime() {
        EntitySnapshot snap = new EntitySnapshot(UUID.randomUUID(), OW, new BlockPos(0, 0, 0), bytes("abc"), List.of());

        byte[] first = snap.payload();
        first[0] = (byte) 'Z'; // mutate the returned copy

        assertThat(new String(snap.payload(), StandardCharsets.UTF_8)).isEqualTo("abc");
    }

    @Test
    void nullPayloadBecomesEmptyArray() {
        EntitySnapshot snap = new EntitySnapshot(UUID.randomUUID(), OW, new BlockPos(0, 0, 0), null, List.of());
        assertThat(snap.payload()).isEmpty();
    }

    @Test
    void nullPassengersBecomesEmptyList() {
        EntitySnapshot snap = new EntitySnapshot(UUID.randomUUID(), OW, new BlockPos(0, 0, 0), bytes("x"), null);
        assertThat(snap.passengers()).isEmpty();
        assertThat(snap.hasPassengers()).isFalse();
    }

    @Test
    void requiredFieldsAreValidated() {
        assertThatThrownBy(() -> new EntitySnapshot(null, OW, new BlockPos(0, 0, 0), bytes("x"), List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () -> new EntitySnapshot(UUID.randomUUID(), null, new BlockPos(0, 0, 0), bytes("x"), List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EntitySnapshot(UUID.randomUUID(), OW, null, bytes("x"), List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    /** T2 (docs/design/entity-migration.md §8): a 3-deep passenger stack round-trips with mount order preserved. */
    @Test
    void threeDeepPassengerStackPreservesMountOrder() {
        UUID boatId = UUID.randomUUID();
        UUID minecartId = UUID.randomUUID();
        UUID pigId = UUID.randomUUID();
        BlockPos dest = new BlockPos(10, 64, 10);

        EntitySnapshot pig = new EntitySnapshot(pigId, OW, dest, bytes("pig"), List.of());
        EntitySnapshot minecart = new EntitySnapshot(minecartId, OW, dest, bytes("minecart"), List.of(pig));
        EntitySnapshot boat = new EntitySnapshot(boatId, OW, dest, bytes("boat"), List.of(minecart));

        assertThat(boat.totalEntities()).isEqualTo(3);
        assertThat(boat.hasPassengers()).isTrue();
        assertThat(boat.passengers()).hasSize(1);
        assertThat(boat.passengers().get(0).uuid()).isEqualTo(minecartId);
        assertThat(boat.passengers().get(0).passengers()).hasSize(1);
        assertThat(boat.passengers().get(0).passengers().get(0).uuid()).isEqualTo(pigId);
        assertThat(new String(boat.passengers().get(0).passengers().get(0).payload(), StandardCharsets.UTF_8))
                .isEqualTo("pig");
    }

    @Test
    void orderMattersAmongSiblingPassengers() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        BlockPos dest = new BlockPos(0, 64, 0);
        EntitySnapshot passengerA = new EntitySnapshot(a, OW, dest, bytes("a"), List.of());
        EntitySnapshot passengerB = new EntitySnapshot(b, OW, dest, bytes("b"), List.of());

        EntitySnapshot vehicle1 =
                new EntitySnapshot(UUID.randomUUID(), OW, dest, bytes("v"), List.of(passengerA, passengerB));
        EntitySnapshot vehicle2 =
                new EntitySnapshot(UUID.randomUUID(), OW, dest, bytes("v"), List.of(passengerB, passengerA));

        assertThat(vehicle1.passengers().get(0).uuid()).isEqualTo(a);
        assertThat(vehicle2.passengers().get(0).uuid()).isEqualTo(b);
    }

    /** T1-adjacent structural check: equals()/hashCode() compare payload bytes by content, not array identity. */
    @Test
    void equalsComparesPayloadContentNotArrayIdentity() {
        UUID id = UUID.randomUUID();
        BlockPos dest = new BlockPos(5, 5, 5);
        EntitySnapshot a = new EntitySnapshot(id, OW, dest, bytes("same"), List.of());
        EntitySnapshot b = new EntitySnapshot(id, OW, dest, bytes("same"), List.of());

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());

        EntitySnapshot c = new EntitySnapshot(id, OW, dest, bytes("different"), List.of());
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void equalsIsDeepAcrossPassengerTree() {
        UUID rootId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        BlockPos dest = new BlockPos(1, 1, 1);

        EntitySnapshot child1 = new EntitySnapshot(childId, OW, dest, bytes("child"), List.of());
        EntitySnapshot child2 = new EntitySnapshot(childId, OW, dest, bytes("child"), List.of());
        EntitySnapshot root1 = new EntitySnapshot(rootId, OW, dest, bytes("root"), List.of(child1));
        EntitySnapshot root2 = new EntitySnapshot(rootId, OW, dest, bytes("root"), List.of(child2));

        assertThat(root1).isEqualTo(root2);

        EntitySnapshot child3 = new EntitySnapshot(childId, OW, dest, bytes("different-child"), List.of());
        EntitySnapshot root3 = new EntitySnapshot(rootId, OW, dest, bytes("root"), List.of(child3));
        assertThat(root1).isNotEqualTo(root3);
    }

    /**
     * Structural smoke test independent of actual NBT, using the pure-Java {@code String} payload
     * path threaded through {@link EntityMigrationCoordinator#beginMigrationWithTree}: the
     * recursive passenger nesting survives the full begin→complete round trip
     * (docs/design/entity-migration.md §6.4, third bullet).
     */
    @Test
    void passengerTreeSurvivesCoordinatorRoundTrip() {
        var overworld = new net.multiforge.runtime.region.ThreadedRegionizer(OW, 0);
        var dest = overworld.addChunk(new net.multiforge.api.world.ChunkPos(100, 100));
        overworld.addChunk(new net.multiforge.api.world.ChunkPos(0, 0));
        var taskQueue =
                new net.multiforge.runtime.region.RegionizedTaskQueue((w, x, z) -> overworld.regionAtChunk(x, z));
        EntityRegistry registry = new EntityRegistry();
        EntityMigrationCoordinator migrator = new EntityMigrationCoordinator(taskQueue, registry);

        MigratingEntityRef vehicle =
                new MigratingEntityRef(UUID.randomUUID(), OW, new net.multiforge.api.world.ChunkPos(0, 0));
        MigratingEntityRef rider =
                new MigratingEntityRef(UUID.randomUUID(), OW, new net.multiforge.api.world.ChunkPos(0, 0));
        registry.add(vehicle, "boat");
        registry.add(rider, "player");

        var passengers = List.of(new EntityMigrationCoordinator.PassengerSpec(rider, List.of()));
        assertThat(migrator.beginMigrationWithTree(vehicle, OW, new BlockPos(1600, 64, 1600), passengers))
                .isTrue();
        taskQueue.drain(dest, Integer.MAX_VALUE);

        assertThat(registry.get(vehicle.uuid())).isNotNull();
        assertThat(registry.get(rider.uuid())).isNotNull();
        assertThat(registry.get(rider.uuid()).ref().chunkPos())
                .isEqualTo(new net.multiforge.api.world.ChunkPos(100, 100));
    }
}
