/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.network;

import java.util.Objects;
import net.multiforge.api.entity.EntityRef;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.RegionizedTaskQueue;

/**
 * Routes incoming gameplay packets to the region worker that currently
 * owns the sender player. The M5 patch calls into this from every
 * {@code ServerGamePacketListenerImpl.handle*} entry point, replacing
 * Vanilla's {@code PacketUtils.ensureRunningOnSameThread(this)} call.
 *
 * <p>Semantics match Folia: the packet is enqueued into the region's
 * mailbox at the player's current chunk position — if the player
 * moves between decode and dispatch, the packet still lands on the
 * new owner.
 */
public final class NetworkPacketRouter {

    private final RegionizedTaskQueue taskQueue;
    private final WorldRef globalWorld;

    public NetworkPacketRouter(RegionizedTaskQueue taskQueue, WorldRef globalWorld) {
        this.taskQueue = Objects.requireNonNull(taskQueue, "taskQueue");
        this.globalWorld = Objects.requireNonNull(globalWorld, "globalWorld");
    }

    /** Route to the sender player's current region. Used for movement, interact, break, chat-within-region, etc. */
    public void routeToPlayer(EntityRef player, Runnable handler) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(handler, "handler");
        taskQueue.queueChunkTask(
                player.world(), player.chunkPos().x(), player.chunkPos().z(), handler);
    }

    /** Route to the global region. Used for chat broadcast, command execution, gamerule changes. */
    public void routeToGlobal(Runnable handler) {
        Objects.requireNonNull(handler, "handler");
        taskQueue.queueChunkTask(globalWorld, 0, 0, handler);
    }

    /** Route to a specific chunk. Used for block-scoped packets that don't have a natural entity owner. */
    public void routeToChunk(WorldRef world, int chunkX, int chunkZ, Runnable handler) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(handler, "handler");
        taskQueue.queueChunkTask(world, chunkX, chunkZ, handler);
    }
}
