/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.neoforge.entity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.neoforge.RegionizedTickCoordinator;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.entity.EntityMigrationCoordinator;
import net.multiforge.runtime.entity.EntityMigrationCoordinator.PassengerSpec;
import net.multiforge.runtime.entity.EntityRegistry;
import net.multiforge.runtime.entity.MigratingEntityRef;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;

/**
 * Vanilla {@code Entity} &lt;-&gt; MultiForge {@code EntityRegistry}/{@code
 * EntityMigrationCoordinator} adapter (M4 Track A2). Every {@code
 * multiforge-patches/05-entity-migration/} hunk calls into exactly one of these static methods —
 * all the NBT capture/restore, passenger-tree walking, and region-crossing detection logic lives
 * here rather than in the patch hunks themselves (CLAUDE.md rule 6: keep the patch set thin).
 *
 * <h2>Design notes</h2>
 *
 * <ul>
 *   <li>{@code multiforge-runtime}'s {@link EntityMigrationCoordinator} is deliberately
 *       Minecraft-free (docs/design/entity-migration.md §6.1) — its {@code completeAt} only
 *       updates {@link EntityRegistry} bookkeeping (a fresh {@link MigratingEntityRef} at {@code
 *       RESIDENT}), it never touches a Vanilla {@code Entity} object. This bridge is therefore
 *       responsible for the actual Vanilla-side materialization at the destination: it enqueues
 *       its own follow-up {@link net.multiforge.runtime.region.RegionizedTaskQueue#queueChunkTask}
 *       for the same destination chunk immediately after calling {@code beginMigration} /
 *       {@code beginMigrationWithTree}. Because both enqueues happen back-to-back on the calling
 *       (source) thread against the same per-region inbox ({@link
 *       net.multiforge.runtime.region.RegionizedTaskQueue} is a FIFO queue per region), the
 *       coordinator's own bookkeeping completion always runs before this bridge's materialization
 *       task on the destination region's worker.
 *   <li>The materialize task re-reads the <em>current</em> {@link MigratingEntityRef} from the
 *       registry rather than trusting its originally-captured destination — this is what makes
 *       {@code abortAndRestore} (docs/design/entity-migration.md §3.3/§7.3) degrade safely: if the
 *       destination chunk never reached BORDER in time, the coordinator's fallback re-completes at
 *       the <em>source</em> location instead, and the materialize task detects the mismatch and
 *       re-enqueues itself against the ref's actual final location before creating the Vanilla
 *       object. A ref that ends up {@code RETIRED} (retired mid-flight, §1.4/§7.2) is not
 *       materialized at all.
 *   <li>Region-crossing detection recomputes the owning region for both the entity's last-known
 *       chunk (per its {@link MigratingEntityRef#chunkPos()}) and its live current chunk on every
 *       call — two {@code ConcurrentHashMap} lookups, no allocation on the non-crossing fast path.
 *       In-region micro-movement (walking) never touches {@link EntityMigrationCoordinator} at
 *       all: this is what keeps the hot path (every entity, every tick) cheap.
 * </ul>
 */
public final class EntityMigrationBridge {

    private EntityMigrationBridge() {}

    /** {@code WorldRef.dimensionId() -> ServerLevel}, populated opportunistically by every entry point. */
    private static final ConcurrentHashMap<String, ServerLevel> LEVELS = new ConcurrentHashMap<>();

    // === A2.1 / A2.2 — Entity.setPosRaw / Entity.move: cheap in-place region-crossing check ======

    /**
     * Called from {@code Entity.setPosRaw} (after the coordinate write) and from {@code
     * Entity.move(MoverType, Vec3)} (after Vanilla's physics resolves the final position) — both
     * are safe to call redundantly on the same logical move (a losing/no-op second attempt is a
     * cheap CAS check inside {@link MigratingEntityRef#beginMigration()}, never a duplicate
     * migration). No-op unless the entity is tracked in the registry, {@code RESIDENT}, and its
     * live chunk now resolves to a different {@link Region} than its last-known chunk.
     */
    public static void onPositionChanged(Entity entity) {
        checkAndBeginMigration(entity);
    }

    /**
     * Called from {@code PersistentEntitySectionManager.Callback.onMove()} (A2.5) — Vanilla's own
     * "did the entity's section change" chokepoint. Returns {@code true} iff a migration was
     * initiated, in which case the caller must skip its own section-swap bookkeeping: the whole
     * mount tree is being hard-migrated to another region — torn down out of this dimension via
     * {@link Entity#setRemoved} below and re-materialized as fresh Vanilla objects at the
     * destination (§2.1's "coordinator controls re-mount order explicitly" contract — the same
     * approach Vanilla itself already uses for {@code changeDimension}).
     */
    public static boolean onSectionMove(Entity entity, net.minecraft.core.BlockPos newPos) {
        return checkAndBeginMigration(entity);
    }

    /**
     * Shared entry point for both {@link #onPositionChanged} and {@link #onSectionMove}. Resolves
     * to {@link Entity#getRootVehicle()} before checking for a region crossing — a mounted
     * passenger's own position always tracks its vehicle (Vanilla's {@code positionRider}), so
     * treating the whole mount tree as one unit here avoids ever migrating a rider away from its
     * vehicle (or vice versa) on ordinary in-region movement, not just on an explicit {@link
     * #onTeleport} call.
     */
    private static boolean checkAndBeginMigration(Entity entity) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return false;
        if (!(entity.level() instanceof ServerLevel serverLevel) || entity.isRemoved()) return false;
        trackLevel(serverLevel);
        Entity root = entity.getRootVehicle();
        if (root.isRemoved()) return false;

        EntityRegistry registry = host.entityRegistry();
        MigratingEntityRef rootRef = registry.lookup(root.getUUID());
        if (rootRef == null || rootRef.migrationState() != net.multiforge.runtime.entity.MigrationState.RESIDENT) {
            return false; // untracked, already migrating, or retired — nothing to do
        }

        WorldRef world = RegionizedTickCoordinator.asWorldRef(serverLevel);
        ThreadedRegionizer regionizer = host.regionizerForOrNull(world);
        if (regionizer == null) return false;

        net.minecraft.world.level.ChunkPos vanillaChunk = root.chunkPosition();
        ChunkPos destChunk = new ChunkPos(vanillaChunk.x, vanillaChunk.z);
        Region destRegion = regionizer.regionAtChunk(destChunk.x(), destChunk.z());
        if (destRegion == null) return false; // destination chunk not yet regionized — retry next move

        ChunkPos srcChunk = rootRef.chunkPos();
        Region srcRegion = regionizer.regionAtChunk(srcChunk.x(), srcChunk.z());
        if (srcRegion != null && destRegion.id().equals(srcRegion.id())) {
            return false; // still the same region — cheap fast path, no migration needed
        }

        net.minecraft.core.BlockPos vp = root.blockPosition();
        BlockPos destPos = new BlockPos(vp.getX(), vp.getY(), vp.getZ());
        return attemptHop(host, root, rootRef, world, destPos, "Entity.regionCrossing");
    }

    /**
     * Common atomic-tree hop used by every A2 entry point: refreshes NBT for {@code root} and every
     * still-{@code RESIDENT} passenger, hands the whole tree to {@link
     * EntityMigrationCoordinator#beginMigrationWithTree}, and — only once the CAS actually wins —
     * tears the Vanilla objects out of the source dimension via {@link Entity#setRemoved} with
     * {@link Entity.RemovalReason#CHANGED_DIMENSION} (no destroy/drop side effects, no forced
     * separate chunk-save — matches Vanilla's own semantics for "this object is superseded by
     * another materialization elsewhere") and enqueues this bridge's own destination-side
     * materialization ({@link #enqueueMaterialize}).
     *
     * <p>Deliberately calls {@code setRemoved} directly rather than the patched {@code remove()} —
     * the latter's A2.7 hook ({@link #onRemoved}) would retire the ref via {@link
     * MigratingEntityRef#retire()}, which (correctly) defers to {@code pendingRetire} while the ref
     * is {@code MIGRATING} (§1.4) — but that path means "this entity died mid-flight," not "this
     * object is being superseded by a normal, successful hop." Using {@code setRemoved} directly
     * tears down Vanilla's side of the old object without touching the coordinator's bookkeeping at
     * all, exactly like Vanilla's own {@code changeDimension} already does via {@code
     * removeAfterChangingDimensions}.
     */
    private static boolean attemptHop(
            MultiThreadedSchedulerHost host,
            Entity root,
            MigratingEntityRef rootRef,
            WorldRef destWorld,
            BlockPos destPos,
            String ownershipSite) {
        if (!net.multiforge.neoforge.OwnershipGuard.canMutate(ownershipSite)) {
            net.multiforge.neoforge.OwnershipGuard.reroute(
                    ownershipSite, () -> attemptHop(host, root, rootRef, destWorld, destPos, ownershipSite));
            return false;
        }
        EntityRegistry registry = host.entityRegistry();
        List<PassengerSpec> specTree = new ArrayList<>();
        List<Capture> captureTree = new ArrayList<>();
        List<Entity> included = new ArrayList<>();
        included.add(root);
        collectPassengerTree(root, registry, specTree, captureTree, included);

        String rootPayload = captureNbtSafely(root);
        registry.updatePayload(root.getUUID(), rootPayload);

        boolean began = host.entityMigrationCoordinator().beginMigrationWithTree(rootRef, destWorld, destPos, specTree);
        if (!began) return false;

        UUID uuid = root.getUUID();
        Capture rootCapture = new Capture(uuid, rootPayload, captureTree);
        // Discard only the nodes actually folded into the tree above (`included`) — a passenger
        // skipped by collectPassengerTree (untracked, or already migrating on its own) must be left
        // alone in the source dimension, not destroyed alongside the rest of the tree.
        for (Entity node : included) {
            node.setRemoved(Entity.RemovalReason.CHANGED_DIMENSION);
        }
        enqueueMaterialize(host, uuid, destWorld, destPos, rootCapture);
        return true;
    }

    // === A2.3 — Entity.teleportTo(double, double, double): atomic passenger-tree hop ============

    /**
     * Called from {@code Entity.teleportTo(double, double, double)} <em>after</em> Vanilla's own
     * {@code moveTo} + {@code teleportPassengers()} have run — the whole tree is already
     * repositioned in Vanilla's own bookkeeping by the time this fires, so this just needs to check
     * whether the new position crossed a region boundary and, if so, delegate to the same {@link
     * #attemptHop} used by the incremental-movement paths above.
     */
    public static void onTeleport(Entity root) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return;
        if (!(root.level() instanceof ServerLevel serverLevel) || root.isRemoved()) return;
        trackLevel(serverLevel);

        EntityRegistry registry = host.entityRegistry();
        MigratingEntityRef rootRef = registry.lookup(root.getUUID());
        if (rootRef == null || rootRef.migrationState() != net.multiforge.runtime.entity.MigrationState.RESIDENT) {
            return;
        }

        WorldRef world = RegionizedTickCoordinator.asWorldRef(serverLevel);
        ThreadedRegionizer regionizer = host.regionizerForOrNull(world);
        if (regionizer == null) return;

        net.minecraft.world.level.ChunkPos vc = root.chunkPosition();
        Region destRegion = regionizer.regionAtChunk(vc.x, vc.z);
        if (destRegion == null) return;
        ChunkPos srcChunk = rootRef.chunkPos();
        Region srcRegion = regionizer.regionAtChunk(srcChunk.x(), srcChunk.z());
        if (srcRegion != null && destRegion.id().equals(srcRegion.id())) return;

        net.minecraft.core.BlockPos vp = root.blockPosition();
        BlockPos destPos = new BlockPos(vp.getX(), vp.getY(), vp.getZ());
        attemptHop(host, root, rootRef, world, destPos, "Entity.teleportTo");
    }

    private static void collectPassengerTree(
            Entity entity,
            EntityRegistry registry,
            List<PassengerSpec> specOut,
            List<Capture> captureOut,
            List<Entity> includedOut) {
        for (Entity passenger : entity.getPassengers()) {
            MigratingEntityRef pRef = registry.lookup(passenger.getUUID());
            if (pRef == null || pRef.migrationState() != net.multiforge.runtime.entity.MigrationState.RESIDENT) {
                continue; // untracked or already in flight — leave it behind rather than corrupt the tree
            }
            String payload = captureNbtSafely(passenger);
            registry.updatePayload(passenger.getUUID(), payload);
            List<PassengerSpec> childSpecs = new ArrayList<>();
            List<Capture> childCaptures = new ArrayList<>();
            includedOut.add(passenger);
            collectPassengerTree(passenger, registry, childSpecs, childCaptures, includedOut);
            specOut.add(new PassengerSpec(pRef, childSpecs));
            captureOut.add(new Capture(passenger.getUUID(), payload, childCaptures));
        }
    }

    // === A2.4 — Entity.changeDimension: 2-hop bookkeeping through the global region ==============

    /**
     * Called from {@code Entity.changeDimension(DimensionTransition)} once Vanilla has already
     * created/repositioned {@code result} in {@code destLevel} (Vanilla recreates the {@code
     * Entity} object itself on a real dimension change, so — unlike the same-dimension hops above —
     * there is no Vanilla-side materialization for this bridge to do). This method only keeps
     * {@link EntityRegistry} bookkeeping in sync, and does so via the same two-hop shape as {@link
     * net.multiforge.runtime.entity.PlayerJoinCoordinator}: source thread -&gt; global region -&gt;
     * destination chunk region. Both hops are non-blocking {@code queueChunkTask} enqueues
     * (CLAUDE.md rule 4) — this method never blocks the calling (source region) thread.
     *
     * @param original the entity as it was in the source dimension (may be {@code result} itself,
     *     when {@code changeDimension} moves within the same dimension's y-axis-only variant)
     * @param result the entity now live in {@code destLevel}
     */
    public static void onChangeDimension(Entity original, Entity result, ServerLevel destLevel) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return;
        trackLevel(destLevel);

        UUID uuid = result.getUUID();
        WorldRef destWorld = RegionizedTickCoordinator.asWorldRef(destLevel);
        net.minecraft.world.level.ChunkPos vc = result.chunkPosition();
        int destChunkX = vc.x;
        int destChunkZ = vc.z;
        String payload = captureNbtSafely(result);

        WorldRef globalWorld = host.worldForRegion(host.globalRegion().id());
        if (globalWorld == null) {
            ProbeRegistry.bump("entity-migration.changedim.no-global-world");
            return; // defensive — the global region is materialised eagerly at host construction
        }

        // Hop 1: source thread -> global region (mirrors PlayerJoinCoordinator.onPlayerLoginCompleted).
        host.taskQueue().queueChunkTask(globalWorld, 0, 0, () ->
                // Hop 2: global -> destination chunk region.
                host.taskQueue().queueChunkTask(destWorld, destChunkX, destChunkZ, () -> {
                    EntityRegistry registry = host.entityRegistry();
                    MigratingEntityRef stale = registry.lookup(uuid);
                    if (stale != null && !stale.isRetired()) {
                        stale.retire();
                        registry.retire(stale);
                    }
                    if (registry.get(uuid) == null) {
                        MigratingEntityRef fresh =
                                new MigratingEntityRef(uuid, destWorld, new ChunkPos(destChunkX, destChunkZ));
                        registry.add(fresh, payload);
                    } else {
                        registry.updatePayload(uuid, payload);
                    }
                }));
    }

    // === A2.6 — PersistentEntitySectionManager.addEntity: funnel through EntityRegistry.register ==

    /**
     * Called from {@code PersistentEntitySectionManager.addEntityWithoutEvent} before the section
     * insert, covering every Vanilla entity-add path (fresh spawn, legacy/worldgen chunk load,
     * player join) in one place. Idempotent: a UUID already registered (typically because the M4
     * migration machinery — {@link #enqueueMaterialize} — just re-added it as part of a cross-region
     * or cross-dimension hop) is left alone rather than throwing {@link IllegalStateException}.
     */
    public static void onEntityRegistered(Entity entity) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return;
        if (!(entity.level() instanceof ServerLevel serverLevel)) return;
        trackLevel(serverLevel);

        EntityRegistry registry = host.entityRegistry();
        UUID uuid = entity.getUUID();
        if (registry.get(uuid) != null) return; // already tracked (e.g. mid-migration materialize)

        WorldRef world = RegionizedTickCoordinator.asWorldRef(serverLevel);
        net.minecraft.world.level.ChunkPos vc = entity.chunkPosition();
        MigratingEntityRef ref = new MigratingEntityRef(uuid, world, new ChunkPos(vc.x, vc.z));
        try {
            registry.add(ref, captureNbtSafely(entity));
        } catch (IllegalStateException e) {
            // Lost a race against a concurrent register (e.g. the get()-then-add() pair above is
            // not atomic) — benign, per CLAUDE.md rule 5 this is a warn, not a crash.
            ProbeRegistry.bump("entity-migration.register.race");
        }
    }

    // === A2.7 — Entity.remove(): retire in the registry ============================================

    /**
     * Called from the extended {@code 01-ownership/Entity.java.patch} guard on {@code remove()},
     * once the entity has actually transitioned to {@code RETIRED} in Vanilla terms. Starts the
     * {@link EntityRegistry#RETIRED_TTL_TICKS}-tick TTL window (docs/design/entity-migration.md §4).
     */
    public static void onRemoved(Entity entity) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return;
        EntityRegistry registry = host.entityRegistry();
        MigratingEntityRef ref = registry.lookup(entity.getUUID());
        if (ref == null) return;
        ref.retire();
        registry.retire(ref);
    }

    // === Destination-side Vanilla materialization =================================================

    /** Mirrors {@link net.multiforge.runtime.entity.EntitySnapshot} but carries the live NBT capture. */
    private record Capture(UUID uuid, String nbtBase64, List<Capture> passengers) {}

    private static void enqueueMaterialize(
            MultiThreadedSchedulerHost host, UUID rootUuid, WorldRef destWorld, BlockPos destPos, Capture capture) {
        ChunkPos destChunk = destPos.toChunkPos();
        host.taskQueue()
                .queueChunkTask(
                        destWorld, destChunk.x(), destChunk.z(), () -> materialize(host, rootUuid, destWorld, destChunk, capture, false));
    }

    /**
     * @param rerouted {@code true} once this call has already been re-enqueued once against the
     *     ref's actual final chunk — bounds the reroute to a single hop even if the registry keeps
     *     changing under us, rather than looping forever.
     */
    private static void materialize(
            MultiThreadedSchedulerHost host,
            UUID rootUuid,
            WorldRef expectedWorld,
            ChunkPos expectedChunk,
            Capture capture,
            boolean rerouted) {
        EntityRegistry registry = host.entityRegistry();
        MigratingEntityRef fresh = registry.lookup(rootUuid);
        if (fresh == null) {
            ProbeRegistry.bump("entity-migration.materialize.no-ref");
            return; // fully evicted already — nothing left to show
        }
        if (fresh.isRetired()) {
            return; // retired mid-flight (§1.4/§7.2) — deliberately not materialized
        }

        // abortAndRestore (§3.3/§7.3) may have re-completed at a different location than the one we
        // were originally enqueued against — re-route once to the ref's actual final chunk so this
        // always runs on the worker that actually owns it (CLAUDE.md rule 4), rather than
        // materializing inline on the wrong region's thread.
        ChunkPos actualChunk = fresh.chunkPos();
        if (!rerouted && (!expectedWorld.dimensionId().equals(fresh.world().dimensionId()) || !expectedChunk.equals(actualChunk))) {
            host.taskQueue()
                    .queueChunkTask(
                            fresh.world(),
                            actualChunk.x(),
                            actualChunk.z(),
                            () -> materialize(host, rootUuid, fresh.world(), actualChunk, capture, true));
            return;
        }

        ServerLevel destLevel = LEVELS.get(fresh.world().dimensionId());
        if (destLevel == null) {
            ProbeRegistry.bump("entity-migration.materialize.no-level");
            ViolationLogger.warn(
                    "entity-migration", "materialize(" + rootUuid + "): no tracked ServerLevel for "
                            + fresh.world().dimensionId());
            return;
        }

        BlockPos anchor = new BlockPos(actualChunk.x() * 16 + 8, 64, actualChunk.z() * 16 + 8);
        materializeNode(destLevel, capture, anchor, null);
    }

    /**
     * Materializes one captured node (and recursively its passengers) as a fresh Vanilla {@code
     * Entity}, mounting each passenger onto {@code vehicle} once created. Never throws outward —
     * a bad/undecodable NBT capture is warned and skipped rather than aborting the whole tree.
     */
    private static void materializeNode(ServerLevel destLevel, Capture node, BlockPos anchor, Entity vehicle) {
        Optional<Entity> created = decodeAndCreate(destLevel, node.nbtBase64());
        if (created.isEmpty()) {
            ProbeRegistry.bump("entity-migration.materialize.decode-failure");
            ViolationLogger.warn("entity-migration", "materialize(" + node.uuid() + "): NBT decode/create failed");
            return;
        }
        Entity entity = created.get();
        entity.moveTo(anchor.x() + 0.5, anchor.y(), anchor.z() + 0.5, entity.getYRot(), entity.getXRot());
        destLevel.addFreshEntity(entity);
        if (vehicle != null) {
            entity.startRiding(vehicle, true);
        }
        for (Capture child : node.passengers()) {
            materializeNode(destLevel, child, anchor, entity);
        }
    }

    private static Optional<Entity> decodeAndCreate(ServerLevel level, String nbtBase64) {
        try {
            CompoundTag tag = decodeNbt(nbtBase64);
            return EntityType.create(tag, level);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    // === NBT <-> String payload codec ==============================================================

    private static String captureNbtSafely(Entity entity) {
        try {
            CompoundTag tag = new CompoundTag();
            // saveAsPassenger (not save()) — save() unconditionally returns false and writes
            // nothing when the entity is currently a passenger (Entity.save: "isPassenger() ?
            // false : saveAsPassenger(tag)"), which every non-root node in a teleport passenger
            // tree is at capture time. saveAsPassenger has no such guard and is what Vanilla's own
            // chunk-save path uses for mounted entities.
            entity.saveAsPassenger(tag);
            return encodeNbt(tag);
        } catch (RuntimeException | IOException e) {
            ProbeRegistry.bump("entity-migration.capture.failure");
            ViolationLogger.warn(
                    "entity-migration",
                    "NBT capture failed for " + entity.getUUID() + ": " + e.getClass().getSimpleName());
            return "";
        }
    }

    private static String encodeNbt(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(tag, out);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static CompoundTag decodeNbt(String base64) throws IOException {
        if (base64 == null || base64.isEmpty()) return new CompoundTag();
        byte[] bytes = Base64.getDecoder().decode(base64);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return NbtIo.read(in, NbtAccounter.unlimitedHeap());
        }
    }

    private static void trackLevel(ServerLevel level) {
        LEVELS.putIfAbsent(level.dimension().location().toString(), level);
    }
}
