/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.entity;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.multiforge.api.entity.EntityRef;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;

/**
 * Runtime {@link EntityRef} that mutates its world/chunk pointer as
 * the entity migrates between regions. All observers (schedulers,
 * region workers) hold a stable reference to this object and re-read
 * {@link #world()} / {@link #chunkPos()} on every dispatch to hit the
 * current owner.
 *
 * <p>State transitions are atomic — a mid-flight cancel or retirement
 * cannot leave the ref in an inconsistent state.
 */
public final class MigratingEntityRef implements EntityRef {

    private final UUID uuid;
    private final AtomicReference<Location> location;
    private final AtomicReference<MigrationState> state = new AtomicReference<>(MigrationState.RESIDENT);

    public MigratingEntityRef(UUID uuid, WorldRef world, ChunkPos chunkPos) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.location = new AtomicReference<>(
                new Location(Objects.requireNonNull(world, "world"), Objects.requireNonNull(chunkPos, "chunkPos")));
    }

    @Override
    public UUID uuid() {
        return uuid;
    }

    @Override
    public WorldRef world() {
        return location.get().world();
    }

    @Override
    public ChunkPos chunkPos() {
        return location.get().chunkPos();
    }

    @Override
    public boolean isRetired() {
        return state.get() == MigrationState.RETIRED;
    }

    public MigrationState migrationState() {
        return state.get();
    }

    /**
     * Attempt RESIDENT → MIGRATING. Returns {@code true} iff we won
     * the CAS; caller then owns the migration.
     */
    public boolean beginMigration() {
        return state.compareAndSet(MigrationState.RESIDENT, MigrationState.MIGRATING);
    }

    /**
     * Called on the destination region worker after the entity has
     * been re-added there. Publishes the new location and transitions
     * MIGRATING → RESIDENT atomically.
     */
    public void completeMigration(WorldRef newWorld, ChunkPos newChunkPos) {
        location.set(new Location(newWorld, newChunkPos));
        if (!state.compareAndSet(MigrationState.MIGRATING, MigrationState.RESIDENT)) {
            // Retired mid-flight — leave state at RETIRED.
        }
    }

    /**
     * Called if the migration fails (destination refused) and the
     * source is putting the entity back.
     */
    public void abortMigration() {
        state.compareAndSet(MigrationState.MIGRATING, MigrationState.RESIDENT);
    }

    /** Terminal transition. Idempotent. */
    public void retire() {
        state.set(MigrationState.RETIRED);
    }

    private record Location(WorldRef world, ChunkPos chunkPos) {}
}
