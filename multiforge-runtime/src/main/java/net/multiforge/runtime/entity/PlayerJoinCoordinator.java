/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.entity;

import java.util.Objects;
import java.util.UUID;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.RegionizedTaskQueue;

/**
 * Player-join flow — the special case where an entity enters the
 * server from the Netty IO thread rather than migrating between two
 * live regions. Two hops:
 *
 * <ol>
 *   <li>Netty thread completes login handshake, calls
 *       {@link #onPlayerLoginCompleted}.</li>
 *   <li>The global region runs the pending
 *       {@code addFreshPlayerToWorld} step (assigning the player its
 *       UUID row in the shared datastore), then hands off to the
 *       chunk-owning region for the spawn position.</li>
 *   <li>The target region's next tick materializes the player, adds
 *       it to its {@link EntityRegistry}, and signals ready for the
 *       first game packet.</li>
 * </ol>
 *
 * <p>The M4 patch binds step (1) to
 * {@code ServerConnectionListener}'s login flow and step (2) to the
 * global tick body.
 */
public final class PlayerJoinCoordinator {

    private final RegionizedTaskQueue taskQueue;
    private final EntityRegistry registry;
    private final WorldRef globalWorld;
    private final Runnable globalWakeup;

    public PlayerJoinCoordinator(
            RegionizedTaskQueue taskQueue, EntityRegistry registry, WorldRef globalWorld, Runnable globalWakeup) {
        this.taskQueue = Objects.requireNonNull(taskQueue, "taskQueue");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.globalWorld = Objects.requireNonNull(globalWorld, "globalWorld");
        this.globalWakeup = Objects.requireNonNull(globalWakeup, "globalWakeup");
    }

    /**
     * Called from the Netty IO thread after login handshake completes.
     * Hops to the global region, then to the spawn-chunk region.
     */
    public void onPlayerLoginCompleted(UUID playerUuid, WorldRef spawnWorld, BlockPos spawnPos, String initialPayload) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(spawnWorld, "spawnWorld");
        Objects.requireNonNull(spawnPos, "spawnPos");

        // Step 1: hop to the global region.
        taskQueue.queueChunkTask(
                globalWorld, 0, 0, () -> globalRegionAssign(playerUuid, spawnWorld, spawnPos, initialPayload));
        globalWakeup.run();
    }

    /**
     * Named entry point the M4 Track A3.3 wiring calls from {@code PlayerList.placeNewPlayer}
     * (via the fork-side {@code PlayerJoinBridge}) — a thin alias for {@link
     * #onPlayerLoginCompleted} kept under the name the networking design contract uses
     * ("Netty→global→spawn-chunk-owning-region hop"). Both names hop through the exact same
     * two-step queue-and-complete machinery; this one exists purely so call sites read as "begin
     * the join flow" rather than "login completed," which reads oddly from a call site that fires
     * after Vanilla has already placed the player in its destination level.
     */
    public void beginJoin(UUID playerUuid, WorldRef spawnWorld, BlockPos spawnPos, String initialPayload) {
        onPlayerLoginCompleted(playerUuid, spawnWorld, spawnPos, initialPayload);
    }

    private void globalRegionAssign(UUID playerUuid, WorldRef spawnWorld, BlockPos spawnPos, String payload) {
        // Step 2: publish the UUID in whatever server-global data store
        // needs it (Vanilla: PlayerList, saveplayerdata dir), then
        // hand off to the spawn-chunk region.
        ChunkPos spawnChunk = spawnPos.toChunkPos();
        taskQueue.queueChunkTask(
                spawnWorld,
                spawnChunk.x(),
                spawnChunk.z(),
                () -> spawnAtDestination(playerUuid, spawnWorld, spawnPos, payload));
    }

    private void spawnAtDestination(UUID playerUuid, WorldRef spawnWorld, BlockPos spawnPos, String payload) {
        // A3.3: by the time this drains, Vanilla's own PlayerList.placeNewPlayer has typically
        // already registered the player via PersistentEntitySectionManager.addEntity's
        // EntityMigrationBridge.onEntityRegistered hook (A2.6) — this hop is supplementary
        // MultiForge bookkeeping racing (harmlessly) against that Vanilla-driven path, not the
        // only place the ref gets created. Match A2.6's own idempotent register-or-refresh idiom
        // rather than assuming this hop always wins the race and gets to be the first `add()`.
        if (registry.get(playerUuid) == null) {
            MigratingEntityRef ref = new MigratingEntityRef(playerUuid, spawnWorld, spawnPos.toChunkPos());
            registry.add(ref, payload);
        } else {
            registry.updatePayload(playerUuid, payload);
        }
        // The M4 patch signals ServerGamePacketListenerImpl to release
        // the queued initial packets to the player here.
    }
}
