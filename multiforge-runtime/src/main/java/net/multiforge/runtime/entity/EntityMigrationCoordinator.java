/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.RegionizedTaskQueue;

/**
 * Orchestrator for two-phase entity migration between regions or
 * dimensions. The protocol matches Folia's {@code teleportAsync}:
 *
 * <ol>
 *   <li>Source calls {@link #beginMigration(MigratingEntityRef,
 *       WorldRef, BlockPos)} on its region worker.</li>
 *   <li>Coordinator atomically transitions the ref's state to
 *       {@link MigrationState#MIGRATING}; if the CAS fails (already
 *       migrating or retired), the call returns {@code false}.</li>
 *   <li>Coordinator recursively captures the passenger tree into an
 *       {@link EntitySnapshot}, removes the entity + passengers from
 *       the source {@link EntityRegistry}, then enqueues a chunk task
 *       on the destination via {@link RegionizedTaskQueue}.</li>
 *   <li>The destination region's next drain runs the reinflate:
 *       re-add each entity to the destination's {@link EntityRegistry},
 *       re-mount the passenger tree, publish the new location, and
 *       transition state back to {@link MigrationState#RESIDENT}.</li>
 * </ol>
 *
 * <p>Vehicle + passenger tree is treated as an atomic transfer — every
 * passenger has its own {@link MigratingEntityRef} whose location gets
 * updated in the same commit. Nothing rides between regions.
 */
public final class EntityMigrationCoordinator {

    private final RegionizedTaskQueue taskQueue;
    private final EntityRegistry registry;

    public EntityMigrationCoordinator(RegionizedTaskQueue taskQueue, EntityRegistry registry) {
        this.taskQueue = Objects.requireNonNull(taskQueue, "taskQueue");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Attempt to migrate {@code ref} to {@code (destWorld, destPos)}.
     *
     * @return {@code true} iff the source thread won the CAS and the
     *         migration is now in flight; {@code false} if the entity
     *         was already migrating or retired.
     */
    public boolean beginMigration(MigratingEntityRef ref, WorldRef destWorld, BlockPos destPos) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(destWorld, "destWorld");
        Objects.requireNonNull(destPos, "destPos");
        EntityRegistry.Entry entry = registry.get(ref.uuid());
        if (entry == null) return false;

        // 1. Mark migrating; also mark every passenger.
        List<MigratingEntityRef> tree = collectPassengerTree(ref);
        for (MigratingEntityRef p : tree) {
            if (!p.beginMigration()) {
                // Roll back any partial marks.
                for (MigratingEntityRef q : tree) q.abortMigration();
                return false;
            }
        }

        // 2. Snapshot the tree.
        EntitySnapshot snapshot = snapshot(ref, destWorld, destPos, entry.payload());

        // 3. Remove from source registry (destination will re-add).
        for (MigratingEntityRef p : tree) registry.remove(p.uuid());

        // 4. Enqueue destination task.
        ChunkPos destChunk = destPos.toChunkPos();
        taskQueue.queueChunkTask(destWorld, destChunk.x(), destChunk.z(), () -> completeAt(snapshot));
        return true;
    }

    /**
     * Destination-side completion. Runs on the destination region's
     * worker after {@code queueChunkTask} delivers it.
     */
    public void completeAt(EntitySnapshot snapshot) {
        completeRecursive(snapshot);
    }

    private void completeRecursive(EntitySnapshot snapshot) {
        // The M4 patch inflates a fresh Entity from the snapshot payload.
        // Pure-Java: re-materialize the MigratingEntityRef with the new
        // world/chunkPos and put it back in the registry.
        MigratingEntityRef fresh = new MigratingEntityRef(snapshot.uuid(), snapshot.destWorld(), snapshot.destChunk());
        registry.add(fresh, snapshot.payload());
        fresh.completeMigration(snapshot.destWorld(), snapshot.destChunk());
        for (EntitySnapshot child : snapshot.passengers()) completeRecursive(child);
    }

    private EntitySnapshot snapshot(MigratingEntityRef ref, WorldRef destWorld, BlockPos destPos, String payload) {
        List<EntitySnapshot> childSnapshots = new ArrayList<>();
        // Real Vanilla walks entity.getPassengers(). In pure-Java tests the
        // passenger tree is provided out-of-band via ExtendedRegistry helper.
        return new EntitySnapshot(ref.uuid(), destWorld, destPos, payload, childSnapshots);
    }

    /**
     * Snapshot including a caller-supplied passenger tree. Used when
     * the source-side code knows the mount structure (Vanilla patches
     * or test fixtures).
     */
    public boolean beginMigrationWithTree(
            MigratingEntityRef ref, WorldRef destWorld, BlockPos destPos, List<PassengerSpec> passengers) {
        EntityRegistry.Entry entry = registry.get(ref.uuid());
        if (entry == null) return false;

        List<MigratingEntityRef> all = new ArrayList<>();
        all.add(ref);
        collectFromSpec(passengers, all);

        for (MigratingEntityRef p : all) {
            if (!p.beginMigration()) {
                for (MigratingEntityRef q : all) q.abortMigration();
                return false;
            }
        }

        EntitySnapshot snapshot = snapshotWithSpec(ref, destWorld, destPos, entry.payload(), passengers);
        for (MigratingEntityRef p : all) registry.remove(p.uuid());
        ChunkPos destChunk = destPos.toChunkPos();
        taskQueue.queueChunkTask(destWorld, destChunk.x(), destChunk.z(), () -> completeAt(snapshot));
        return true;
    }

    private EntitySnapshot snapshotWithSpec(
            MigratingEntityRef ref,
            WorldRef destWorld,
            BlockPos destPos,
            String payload,
            List<PassengerSpec> passengers) {
        List<EntitySnapshot> children = new ArrayList<>(passengers.size());
        for (PassengerSpec spec : passengers) {
            EntityRegistry.Entry childEntry = registry.get(spec.ref().uuid());
            String childPayload = childEntry == null ? "" : childEntry.payload();
            children.add(snapshotWithSpec(spec.ref(), destWorld, destPos, childPayload, spec.passengers()));
        }
        return new EntitySnapshot(ref.uuid(), destWorld, destPos, payload, children);
    }

    private void collectFromSpec(List<PassengerSpec> passengers, List<MigratingEntityRef> out) {
        for (PassengerSpec spec : passengers) {
            out.add(spec.ref());
            collectFromSpec(spec.passengers(), out);
        }
    }

    private List<MigratingEntityRef> collectPassengerTree(MigratingEntityRef root) {
        List<MigratingEntityRef> out = new ArrayList<>();
        out.add(root);
        // Base implementation has no passengers — the M4 patch overrides
        // via beginMigrationWithTree with an actual passenger walk.
        return out;
    }

    /** Explicit passenger tree spec — the M4 patch fills this from Vanilla's mount tree. */
    public record PassengerSpec(MigratingEntityRef ref, List<PassengerSpec> passengers) {}
}
