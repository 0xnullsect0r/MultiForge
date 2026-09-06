/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.multiforge.api.entity.EntityRef;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.diagnostics.ViolationLogger;

/**
 * Runtime {@link EntityRef} that mutates its world/chunk pointer as the entity migrates between
 * regions. All observers (schedulers, region workers) hold a stable reference to this object and
 * re-read {@link #world()} / {@link #chunkPos()} on every dispatch to hit the current owner.
 *
 * <p>State transitions are atomic — a mid-flight cancel or retirement cannot leave the ref in an
 * inconsistent state. See {@code docs/design/entity-migration.md} §1 for the frozen state-machine
 * contract this class implements.
 */
public final class MigratingEntityRef implements EntityRef {

    private final UUID uuid;
    private final AtomicReference<Location> location;
    private final AtomicReference<MigrationState> state = new AtomicReference<>(MigrationState.RESIDENT);

    /**
     * Set when {@link #retire()} is called while this ref is {@link MigrationState#MIGRATING}: the
     * state is deliberately left at {@code MIGRATING} (no worker may mutate a ref mid-flight) and
     * the retirement is deferred until the destination discovers it via {@link
     * #consumePendingRetire()} — docs/design/entity-migration.md §1.4.
     */
    private final AtomicBoolean pendingRetire = new AtomicBoolean(false);

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
     * Attempt RESIDENT → MIGRATING. Returns {@code true} iff we won the CAS; caller then owns the
     * migration. A losing caller must treat {@code false} as "already migrating or retired,
     * no-op" — never retry in the same tick, never throw (CLAUDE.md ground rule 5).
     */
    public boolean beginMigration() {
        return state.compareAndSet(MigrationState.RESIDENT, MigrationState.MIGRATING);
    }

    /**
     * Called on the destination region worker after the entity has been re-added there. Publishes
     * the new location and transitions MIGRATING → RESIDENT atomically.
     *
     * <p><b>CAS-first, publish-on-success-only.</b> The prior scaffold published {@code location}
     * unconditionally before checking the CAS, so a duplicate or out-of-order delivery (which
     * should not happen — {@link net.multiforge.runtime.region.RegionizedTaskQueue#drain} is
     * single-delivery per enqueue — but a defensive contract is required regardless, per
     * docs/design/entity-migration.md §1.3) could silently move an already-resident entity
     * backward even though the CAS then failed. Location is now published only when the CAS
     * actually wins; a failing CAS is a rate-limited {@link ViolationLogger} warning and otherwise
     * a no-op.
     *
     * @return {@code true} iff the CAS won and the location was published.
     */
    public boolean completeMigration(WorldRef newWorld, ChunkPos newChunkPos) {
        Objects.requireNonNull(newWorld, "newWorld");
        Objects.requireNonNull(newChunkPos, "newChunkPos");
        if (state.compareAndSet(MigrationState.MIGRATING, MigrationState.RESIDENT)) {
            location.set(new Location(newWorld, newChunkPos));
            return true;
        }
        ViolationLogger.warn(
                "entity-migration",
                "completeMigration() called on " + uuid + " while state=" + state.get()
                        + " (expected MIGRATING) — duplicate or out-of-order delivery, ignored");
        return false;
    }

    /**
     * Called if the migration fails (destination refused) and the source is putting the entity
     * back. No-op if the ref is not currently MIGRATING (e.g. the destination already completed
     * it, or the ref was retired mid-flight) — intentional, per §1.3.
     */
    public void abortMigration() {
        state.compareAndSet(MigrationState.MIGRATING, MigrationState.RESIDENT);
    }

    /**
     * Terminal transition — CAS-based and idempotent (docs/design/entity-migration.md §1.4).
     *
     * <ul>
     *   <li>{@code RESIDENT → RETIRED}: normal case, any thread that owns the entity at the
     *       moment of death.
     *   <li>{@code MIGRATING → RETIRED}: deferred. The state is left at {@code MIGRATING} (no
     *       worker may mutate it) and {@link #pendingRetire} is set instead; the destination's
     *       {@code EntityMigrationCoordinator.completeAt} consults {@link
     *       #consumePendingRetire()} before publishing the freshly-materialized ref as {@code
     *       RESIDENT} and retires it directly instead if set.
     *   <li>Already {@code RETIRED}: no-op, returns {@code false}.
     * </ul>
     *
     * @return {@code true} iff this call changed something (transitioned to RETIRED or set the
     *     deferred-retire marker); {@code false} if the ref was already RETIRED or a racing
     *     transition beat us to it.
     */
    public boolean retire() {
        MigrationState prev = state.get();
        if (prev == MigrationState.RETIRED) return false;
        if (prev == MigrationState.MIGRATING) {
            pendingRetire.set(true);
            return true;
        }
        return state.compareAndSet(prev, MigrationState.RETIRED);
    }

    /**
     * Consumes (clears and returns) the deferred-retire marker set by a {@link #retire()} call
     * that landed while this ref was {@code MIGRATING}. Callers (the destination's completion
     * path) must check this exactly once per completion attempt before publishing the fresh ref
     * as {@code RESIDENT}.
     */
    public boolean consumePendingRetire() {
        return pendingRetire.getAndSet(false);
    }

    /**
     * Passenger-tree atomic snapshot (docs/design/entity-migration.md §2.1-§2.2): given an
     * already-DFS-collected, parent-before-children list of refs, attempt {@link
     * #beginMigration()} on each in order. The first CAS failure aborts the <em>entire</em>
     * attempt — every ref already flipped to {@code MIGRATING} earlier in this same pass is
     * immediately rolled back via {@link #abortMigration()} before returning.
     *
     * <p>Two-pass by construction: the caller collects the full list first (DFS walk of {@code
     * Entity.getPassengers()} in the real Vanilla patch), then this method does the single CAS
     * pass. This lets the abort loop iterate the same fully-materialized list rather than
     * reconstructing "which prefix did we already flip" mid-DFS.
     *
     * <p>Invariant (§2.5): no external observer can catch a mixed {@code {RESIDENT, MIGRATING}}
     * state within {@code refs} once this method returns — either every element is {@code
     * MIGRATING} ({@code true} returned) or every element is back at its pre-attempt state
     * ({@code false} returned).
     *
     * @return {@code true} iff every ref in {@code refs} is now {@code MIGRATING}; {@code false}
     *     if the whole attempt was aborted and rolled back.
     */
    public static boolean beginPassengerTreeSnapshot(List<MigratingEntityRef> refs) {
        Objects.requireNonNull(refs, "refs");
        List<MigratingEntityRef> flipped = new ArrayList<>(refs.size());
        for (MigratingEntityRef ref : refs) {
            if (ref.beginMigration()) {
                flipped.add(ref);
            } else {
                for (MigratingEntityRef toRestore : flipped) {
                    toRestore.abortMigration();
                }
                return false;
            }
        }
        return true;
    }

    private record Location(WorldRef world, ChunkPos chunkPos) {}
}
