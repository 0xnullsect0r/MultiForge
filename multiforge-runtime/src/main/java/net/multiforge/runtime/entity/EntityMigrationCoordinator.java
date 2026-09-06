/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.entity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.ChunkLoadLevel;
import net.multiforge.runtime.chunk.NewChunkHolder;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.region.RegionId;
import net.multiforge.runtime.region.RegionizedTaskQueue;

/**
 * Orchestrator for two-phase entity migration between regions or dimensions. The protocol matches
 * Folia's {@code teleportAsync}:
 *
 * <ol>
 *   <li>Source calls {@link #beginMigration(MigratingEntityRef, WorldRef, BlockPos)} on its
 *       region worker.
 *   <li>Coordinator atomically transitions the ref's state (and every passenger's) to {@link
 *       MigrationState#MIGRATING} via {@link MigratingEntityRef#beginPassengerTreeSnapshot(List)};
 *       if any CAS fails, the whole attempt aborts and rolls back, and the call returns {@code
 *       false}.
 *   <li>Coordinator recursively captures the passenger tree into an {@link EntitySnapshot},
 *       removes the entity + passengers from the source {@link EntityRegistry}, then enqueues a
 *       chunk task on the destination via {@link RegionizedTaskQueue}.
 *   <li>The destination region's next drain runs {@link #completeAt(EntitySnapshot)}, which
 *       defers until the destination {@code NewChunkHolder} is at least {@link
 *       ChunkLoadLevel#BORDER} (docs/design/entity-migration.md §3), then reinflates: re-adds each
 *       entity to the destination's {@link EntityRegistry}, publishes the new location, and
 *       transitions state back to {@link MigrationState#RESIDENT}.
 * </ol>
 *
 * <p>Vehicle + passenger tree is treated as an atomic transfer — every passenger has its own
 * {@link MigratingEntityRef} whose location gets updated in the same commit. Nothing rides between
 * regions.
 */
public final class EntityMigrationCoordinator {

    /** Default BORDER-wait deadline before {@link #abortAndRestore} fires (§3.3). */
    public static final long DEFAULT_BORDER_DEADLINE_MS = 500L;

    private final RegionizedTaskQueue taskQueue;
    private final EntityRegistry registry;

    /**
     * Optional per-world {@link ChunkHolderManager} lookup, used to gate {@link
     * #completeAt(EntitySnapshot)} on the destination holder reaching {@link
     * ChunkLoadLevel#BORDER} and to resolve {@link RegionId}s for {@link
     * ProbeRegistry#recordMigration}. {@code null} (the legacy 2-arg constructor) disables both —
     * completion runs immediately, matching the pre-A1.4 scaffold behaviour so existing
     * pure-Java-only tests keep working unchanged.
     */
    private final Function<WorldRef, ChunkHolderManager> chunkManagers;

    private final long borderDeadlineMs;

    public EntityMigrationCoordinator(RegionizedTaskQueue taskQueue, EntityRegistry registry) {
        this(taskQueue, registry, null, DEFAULT_BORDER_DEADLINE_MS);
    }

    public EntityMigrationCoordinator(
            RegionizedTaskQueue taskQueue,
            EntityRegistry registry,
            Function<WorldRef, ChunkHolderManager> chunkManagers) {
        this(taskQueue, registry, chunkManagers, DEFAULT_BORDER_DEADLINE_MS);
    }

    public EntityMigrationCoordinator(
            RegionizedTaskQueue taskQueue,
            EntityRegistry registry,
            Function<WorldRef, ChunkHolderManager> chunkManagers,
            long borderDeadlineMs) {
        this.taskQueue = Objects.requireNonNull(taskQueue, "taskQueue");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.chunkManagers = chunkManagers;
        this.borderDeadlineMs = borderDeadlineMs;
    }

    /**
     * Attempt to migrate {@code ref} to {@code (destWorld, destPos)}.
     *
     * @return {@code true} iff the source thread won the CAS and the migration is now in flight;
     *     {@code false} if the entity was already migrating or retired.
     */
    public boolean beginMigration(MigratingEntityRef ref, WorldRef destWorld, BlockPos destPos) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(destWorld, "destWorld");
        Objects.requireNonNull(destPos, "destPos");
        EntityRegistry.Entry entry = registry.get(ref.uuid());
        if (entry == null) return false;

        List<MigratingEntityRef> tree = collectPassengerTree(ref);
        if (!MigratingEntityRef.beginPassengerTreeSnapshot(tree)) {
            ProbeRegistry.bump("entity-migration.outcome.aborted-cas");
            return false;
        }

        WorldRef srcWorld = ref.world();
        ChunkPos srcChunk = ref.chunkPos();
        long startNanos = System.nanoTime();
        EntitySnapshot snapshot = snapshot(ref, destWorld, destPos, entry.payload());
        for (MigratingEntityRef p : tree) registry.remove(p.uuid());

        ChunkPos destChunk = destPos.toChunkPos();
        taskQueue.queueChunkTask(
                destWorld,
                destChunk.x(),
                destChunk.z(),
                () -> completeAt(snapshot, tree, srcWorld, srcChunk, startNanos));
        return true;
    }

    /**
     * Destination-side completion. Runs on the destination region's worker after {@code
     * queueChunkTask} delivers it.
     *
     * <p>Frozen public entry point (docs/design/entity-migration.md §3.2). Direct callers that
     * bypass {@link #beginMigration}/{@link #beginMigrationWithTree} (tests, or a future call
     * site) get BORDER gating (when a {@link ChunkHolderManager} lookup is wired) but not the
     * deferred-retire consultation or timeout rollback, since there is no source ref to consult or
     * restore against — those require the richer overload used internally.
     */
    public void completeAt(EntitySnapshot snapshot) {
        completeAt(snapshot, List.of(), null, null, System.nanoTime());
    }

    /**
     * Internal completion entry point carrying the DFS-ordered list of <em>source</em> refs
     * (parent-before-children, same order as {@code snapshot}'s own recursive passenger walk) so
     * {@link #completeRecursive} can consult each one's {@link
     * MigratingEntityRef#consumePendingRetire()} (§1.4/§7.2) and, on a BORDER-wait timeout, {@link
     * #abortAndRestore} can retire the originals and roll back to the source location (§3.3/§7.3).
     */
    private void completeAt(
            EntitySnapshot snapshot,
            List<MigratingEntityRef> sourceRefs,
            WorldRef srcWorld,
            ChunkPos srcChunk,
            long startNanos) {
        ChunkHolderManager destManager = chunkManagers == null ? null : chunkManagers.apply(snapshot.destWorld());
        if (destManager == null) {
            finishComplete(snapshot, sourceRefs, srcWorld, srcChunk, startNanos, null);
            return;
        }
        ChunkPos destChunk = snapshot.destChunk();
        Runnable onReady = () -> taskQueue.queueChunkTask(snapshot.destWorld(), destChunk.x(), destChunk.z(), () -> {
            // Re-check per §3.3 — the BORDER-wait signal fired, but by the time we're actually
            // running on the destination region's own thread the holder may have demoted again
            // under adversarial load/unload churn. Re-arm rather than materialize into a
            // sub-BORDER chunk.
            NewChunkHolder holder = destManager.holderAt(destChunk);
            if (holder != null && holder.level().isAtLeast(ChunkLoadLevel.BORDER)) {
                finishComplete(snapshot, sourceRefs, srcWorld, srcChunk, startNanos, destManager);
            } else {
                completeAt(snapshot, sourceRefs, srcWorld, srcChunk, startNanos);
            }
        });
        Runnable onTimeout = () -> abortAndRestore(snapshot, sourceRefs, srcWorld, srcChunk);
        destManager.scheduleWhenHolderAt(destChunk, ChunkLoadLevel.BORDER, onReady, borderDeadlineMs, onTimeout);
    }

    private void finishComplete(
            EntitySnapshot snapshot,
            List<MigratingEntityRef> sourceRefs,
            WorldRef srcWorld,
            ChunkPos srcChunk,
            long startNanos,
            ChunkHolderManager destManager) {
        completeRecursive(snapshot, sourceRefs.iterator());
        RegionId srcRegion = regionIdAt(srcWorld, srcChunk);
        RegionId dstRegion = destManager == null ? null : regionIdOf(destManager, snapshot.destChunk());
        ProbeRegistry.recordMigration(srcRegion, dstRegion, System.nanoTime() - startNanos);
        ProbeRegistry.bump("entity-migration.outcome.success");
    }

    /**
     * Timeout / never-reaches-BORDER fallback (§3.3, §7.3). Retires every original source ref
     * (they are superseded — either a fresh ref will materialize at the fallback position, or, for
     * the direct-{@code completeAt} legacy call with no source refs, there is nothing to restore)
     * and, when we do have source refs, re-attempts completion at the root ref's last-known-good
     * (pre-migration) location using the same BORDER-gated machinery.
     */
    private void abortAndRestore(
            EntitySnapshot snapshot, List<MigratingEntityRef> sourceRefs, WorldRef srcWorld, ChunkPos srcChunk) {
        ProbeRegistry.bump("entity-migration.outcome.timed-out");
        ViolationLogger.warn(
                "entity-migration",
                "migration of " + snapshot.uuid() + " to "
                        + snapshot.destWorld().dimensionId()
                        + " timed out waiting for destination BORDER; restoring at source");
        for (MigratingEntityRef sourceRef : sourceRefs) {
            // §7.3: "the original ref transitions to RETIRED... since by definition it is not
            // currently MIGRATING from the fallback-completion's perspective." This specific ref
            // object is being abandoned — the fallback below materializes an entirely new ref
            // instance instead — so it goes straight from MIGRATING to RETIRED in one atomic step
            // (a plain retire() on a still-MIGRATING ref defers per §1.4, which would be wrong
            // here: nothing will ever call completeMigration on this specific object again, and a
            // two-step abortMigration()-then-retire() would fire any registered settled listener
            // while transiently RESIDENT instead of at the real terminal state).
            sourceRef.forceTerminalFromMigrating();
            registry.retire(sourceRef);
        }
        if (sourceRefs.isEmpty() || srcWorld == null || srcChunk == null) {
            return; // legacy direct completeAt() call — nothing to restore against
        }
        BlockPos fallbackPos = centerOfChunk(srcChunk);
        EntitySnapshot fallbackSnapshot = withDest(snapshot, srcWorld, fallbackPos);
        completeAt(fallbackSnapshot, List.of(), null, null, System.nanoTime());
    }

    private void completeRecursive(EntitySnapshot snapshot, Iterator<MigratingEntityRef> sourceRefs) {
        MigratingEntityRef sourceRef = sourceRefs != null && sourceRefs.hasNext() ? sourceRefs.next() : null;
        boolean midFlightRetire = sourceRef != null && sourceRef.consumePendingRetire();
        MigratingEntityRef fresh = new MigratingEntityRef(snapshot.uuid(), snapshot.destWorld(), snapshot.destChunk());
        if (midFlightRetire) {
            // §1.4/§7.2: retire()d while MIGRATING — the fresh copy must never be observed
            // RESIDENT anywhere, source or destination. Register it directly as retired.
            fresh.retire();
            registry.retire(fresh);
            ProbeRegistry.bump("entity-migration.outcome.retired-midflight");
            ViolationLogger.warn(
                    "entity-migration",
                    "entity " + snapshot.uuid() + " retired mid-flight; destination materialization suppressed");
        } else {
            // `fresh` is constructed directly at RESIDENT with the destination location already
            // set (§1.1: a migration materializes a brand-new ref instance at RESIDENT on the
            // destination, it does not itself transition MIGRATING → RESIDENT — that CAS is for
            // same-object completions, e.g. MigratingEntityRefTest's direct-call shape). Do NOT
            // additionally call fresh.completeMigration(...) here — fresh is never MIGRATING, so
            // that CAS would only ever fail and spuriously log a "duplicate delivery" warning on
            // every single successful migration.
            registry.add(fresh, decodePayload(snapshot.payload()));
            // The old ref instance is superseded by `fresh` under the same UUID — §1.1: the Java
            // object is not the stable identity, the UUID is. Retire the old object's local state
            // only; do NOT push it into EntityRegistry.retiredRefs, since that UUID is legitimately
            // live again via `fresh`.
            //
            // sourceRef is still MIGRATING at this exact point (nothing transitions it away on the
            // ordinary success path — completeMigration() is a same-object CAS used only by
            // direct-call test shapes, not this fresh-object materialization flow). A plain
            // retire() call here would hit retire()'s own §1.4 defer branch (state == MIGRATING ->
            // sets pendingRetire, does not reach RETIRED) — correct for retire()'s intended
            // "entity died mid-flight" caller, wrong here: this is not a death, it's an ordinary
            // superseded-by-a-successful-hop terminal transition, and callers holding this exact
            // object (e.g. NetworkMigrationBridge's per-hop MigratingEntityRef#addSettledListener,
            // M4 Track A3.2) need it to actually reach a terminal state so their listener fires.
            // rather than a two-step abortMigration()-then-retire() dance that would fire a
            // registered settled listener while transiently RESIDENT instead of RETIRED — one
            // atomic step, same idiom abortAndRestore uses a few lines up in this file.
            if (sourceRef != null) {
                sourceRef.forceTerminalFromMigrating();
            }
        }
        for (EntitySnapshot child : snapshot.passengers()) completeRecursive(child, sourceRefs);
    }

    private EntitySnapshot snapshot(MigratingEntityRef ref, WorldRef destWorld, BlockPos destPos, String payload) {
        List<EntitySnapshot> childSnapshots = new ArrayList<>();
        // Real Vanilla walks entity.getPassengers(). In pure-Java tests the
        // passenger tree is provided out-of-band via ExtendedRegistry helper.
        return new EntitySnapshot(ref.uuid(), destWorld, destPos, encodePayload(payload), childSnapshots);
    }

    // === Cross-region entity creation (docs/design/global-region.md §3.5) =========================

    /**
     * Cross-region-safe entity <em>creation</em> — as opposed to {@link #beginMigration}/{@link
     * #beginMigrationWithTree}, which move an already-registered entity. Used by global-region
     * code that needs to materialize a brand-new entity into a region it does not itself own —
     * {@link net.multiforge.runtime.globals.RaidsSystem}'s raider spawn is the first production
     * caller (docs/design/global-region.md §8.2 integration test 6); a future {@code
     * DragonFightSystem} is expected to reuse this same entry point.
     *
     * <p>Never touches the destination region's entity list from the calling thread: {@code
     * factory} is invoked only after {@link RegionizedTaskQueue#queueChunkTask} has delivered this
     * task to the destination region's own worker. This is a creation, not a migration — there is
     * no source-side ref to transition or roll back, so unlike {@link #beginMigration} this method
     * cannot fail a CAS; it can only be dropped defensively (§ below) if {@code factory} throws or
     * declines by returning {@code null}.
     *
     * @param destWorld world the new entity is created in.
     * @param destPos block position the new entity is created at.
     * @param factory invoked on the destination region's worker thread with {@code destPos} to
     *     produce the new entity's identity and opaque payload. May return {@code null} to abort
     *     the spawn (e.g. a Vanilla-side precondition re-checked at drain time no longer holds) —
     *     treated as a no-op, never an error.
     */
    public void spawnInDestRegion(WorldRef destWorld, BlockPos destPos, Function<BlockPos, NewEntitySpec> factory) {
        Objects.requireNonNull(destWorld, "destWorld");
        Objects.requireNonNull(destPos, "destPos");
        Objects.requireNonNull(factory, "factory");
        ChunkPos destChunk = destPos.toChunkPos();
        taskQueue.queueChunkTask(destWorld, destChunk.x(), destChunk.z(), () -> {
            try {
                NewEntitySpec spec = factory.apply(destPos);
                if (spec == null) {
                    ProbeRegistry.bump("entity-migration.spawn.aborted");
                    return;
                }
                MigratingEntityRef fresh = new MigratingEntityRef(spec.uuid(), destWorld, destChunk);
                registry.add(fresh, spec.payload() == null ? "" : spec.payload());
                ProbeRegistry.bump("entity-migration.spawn.success");
            } catch (Throwable t) {
                // Auto-reroute+warn (CLAUDE.md rule 5): a bad factory must never break the
                // destination region's mailbox drain, nor propagate back to the caller — this
                // whole body already runs asynchronously on the destination region's own worker.
                ProbeRegistry.bump("entity-migration.spawn.failure");
                ViolationLogger.warn(
                        "entity-migration.spawn",
                        "spawnInDestRegion factory threw for " + destWorld.dimensionId() + ": "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
    }

    /** New entity's identity + opaque payload, produced by a {@link #spawnInDestRegion} factory. */
    public record NewEntitySpec(UUID uuid, String payload) {}

    /**
     * Snapshot including a caller-supplied passenger tree. Used when the source-side code knows
     * the mount structure (Vanilla patches or test fixtures).
     */
    public boolean beginMigrationWithTree(
            MigratingEntityRef ref, WorldRef destWorld, BlockPos destPos, List<PassengerSpec> passengers) {
        EntityRegistry.Entry entry = registry.get(ref.uuid());
        if (entry == null) return false;

        List<MigratingEntityRef> all = new ArrayList<>();
        all.add(ref);
        collectFromSpec(passengers, all);

        if (!MigratingEntityRef.beginPassengerTreeSnapshot(all)) {
            ProbeRegistry.bump("entity-migration.outcome.aborted-cas");
            return false;
        }

        WorldRef srcWorld = ref.world();
        ChunkPos srcChunk = ref.chunkPos();
        long startNanos = System.nanoTime();
        EntitySnapshot snapshot = snapshotWithSpec(ref, destWorld, destPos, entry.payload(), passengers);
        for (MigratingEntityRef p : all) registry.remove(p.uuid());
        ChunkPos destChunk = destPos.toChunkPos();
        taskQueue.queueChunkTask(
                destWorld,
                destChunk.x(),
                destChunk.z(),
                () -> completeAt(snapshot, all, srcWorld, srcChunk, startNanos));
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
        return new EntitySnapshot(ref.uuid(), destWorld, destPos, encodePayload(payload), children);
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

    private RegionId regionIdAt(WorldRef world, ChunkPos pos) {
        if (chunkManagers == null || world == null || pos == null) return null;
        ChunkHolderManager manager = chunkManagers.apply(world);
        return manager == null ? null : regionIdOf(manager, pos);
    }

    private static RegionId regionIdOf(ChunkHolderManager manager, ChunkPos pos) {
        NewChunkHolder holder = manager.holderAt(pos);
        return holder == null ? null : holder.owningRegion();
    }

    private static BlockPos centerOfChunk(ChunkPos pos) {
        return new BlockPos(pos.x() * 16 + 8, 64, pos.z() * 16 + 8);
    }

    private static EntitySnapshot withDest(EntitySnapshot snapshot, WorldRef world, BlockPos pos) {
        List<EntitySnapshot> children = new ArrayList<>(snapshot.passengers().size());
        for (EntitySnapshot child : snapshot.passengers()) children.add(withDest(child, world, pos));
        return new EntitySnapshot(snapshot.uuid(), world, pos, snapshot.payload(), children);
    }

    private static byte[] encodePayload(String payload) {
        return payload == null || payload.isEmpty() ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8);
    }

    private static String decodePayload(byte[] payload) {
        return payload == null || payload.length == 0 ? "" : new String(payload, StandardCharsets.UTF_8);
    }

    /** Explicit passenger tree spec — the M4 patch fills this from Vanilla's mount tree. */
    public record PassengerSpec(MigratingEntityRef ref, List<PassengerSpec> passengers) {}
}
