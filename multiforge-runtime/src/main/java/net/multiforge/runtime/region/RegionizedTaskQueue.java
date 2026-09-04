/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.region;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;

/**
 * Per-region mailboxes used to route cross-region work. The primary
 * entry point is {@link #queueChunkTask(WorldRef, int, int, Runnable)}:
 * the task is delivered to the region owning that chunk <em>at drain
 * time</em>, not schedule time. This is how MultiForge (like Folia)
 * handles entities crossing borders, network packets landing on the
 * network thread, and event-bus dispatch fan-out.
 *
 * <p>Inboxes are unbounded per region — the tick pipeline drains them
 * in bounded batches inside its MSPT budget instead. Backpressure is
 * therefore observed as MSPT growth, which the adaptive sizer picks up
 * as a split signal.
 *
 * <p>The queue does not know about {@link ThreadedRegionizer} state
 * transitions on its own; callers hand it a resolver so it can look
 * up ownership at drain time. This lets us wire the same queue to
 * either the {@link ThreadedRegionizer} (production) or a stub map
 * (unit tests).
 */
public final class RegionizedTaskQueue {

    /** Look up the owner region for a chunk. May return {@code null} for unloaded chunks. */
    @FunctionalInterface
    public interface OwnerLookup {
        Region regionAtChunk(WorldRef world, int chunkX, int chunkZ);
    }

    private final OwnerLookup ownerLookup;
    private final ConcurrentMap<RegionId, Queue<Runnable>> inboxes = new ConcurrentHashMap<>();
    private final Queue<PendingTask> orphaned = new ConcurrentLinkedQueue<>();

    public RegionizedTaskQueue(OwnerLookup ownerLookup) {
        this.ownerLookup = Objects.requireNonNull(ownerLookup, "ownerLookup");
    }

    /**
     * Convenience wrapper for callers that already hold a
     * {@link ThreadedRegionizer} directly.
     */
    public static RegionizedTaskQueue of(ThreadedRegionizer regionizer) {
        return new RegionizedTaskQueue((w, x, z) -> regionizer.regionAtChunk(x, z));
    }

    /**
     * Enqueue {@code task} for the region owning {@code (chunkX, chunkZ)}
     * in {@code world}. If the chunk is currently unloaded the task
     * lands in the orphan queue and will be re-routed the next time
     * {@link #reroute()} runs — after chunk load.
     */
    public void queueChunkTask(WorldRef world, int chunkX, int chunkZ, Runnable task) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(task, "task");
        Region owner = ownerLookup.regionAtChunk(world, chunkX, chunkZ);
        if (owner == null) {
            orphaned.add(new PendingTask(world, chunkX, chunkZ, task));
            return;
        }
        inboxFor(owner).add(task);
    }

    public void queueChunkTask(WorldRef world, ChunkPos pos, Runnable task) {
        queueChunkTask(world, pos.x(), pos.z(), task);
    }

    /**
     * Drain up to {@code max} tasks from {@code region}'s inbox, running
     * each one in caller context.
     *
     * @return the number of tasks executed.
     */
    public int drain(Region region, int max) {
        Queue<Runnable> inbox = inboxes.get(region.id());
        if (inbox == null) return 0;
        int run = 0;
        while (run < max) {
            Runnable r = inbox.poll();
            if (r == null) break;
            try {
                r.run();
            } catch (Throwable t) {
                // Never let a mod-thrown exception drop the tick pipeline.
                // Log via the diagnostics layer once its logger sink is wired in M2.5.
                Thread.currentThread().getUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), t);
            }
            run++;
        }
        return run;
    }

    /**
     * Try to re-deliver every orphaned task to its owner region. Called
     * by the tick orchestrator after chunk load barriers, at the start
     * of each epoch.
     */
    public void reroute() {
        PendingTask pending;
        int drained = 0;
        int size = orphaned.size();
        while (drained < size && (pending = orphaned.poll()) != null) {
            Region owner = ownerLookup.regionAtChunk(pending.world, pending.chunkX, pending.chunkZ);
            if (owner == null) {
                orphaned.add(pending); // still no home
            } else {
                inboxFor(owner).add(pending.task);
            }
            drained++;
        }
    }

    public int inboxSize(Region region) {
        Queue<Runnable> q = inboxes.get(region.id());
        return q == null ? 0 : q.size();
    }

    public int orphanedSize() {
        return orphaned.size();
    }

    /** Merge {@code source}'s pending inbox into {@code target}'s. */
    public void moveInbox(Region source, Region target) {
        Queue<Runnable> src = inboxes.remove(source.id());
        if (src == null) return;
        Queue<Runnable> tgt = inboxFor(target);
        Runnable r;
        while ((r = src.poll()) != null) tgt.add(r);
    }

    /** Drop every task for {@code region} — used when a region dies. */
    public void clear(Region region) {
        inboxes.remove(region.id());
    }

    private Queue<Runnable> inboxFor(Region region) {
        return inboxes.computeIfAbsent(region.id(), id -> new ConcurrentLinkedQueue<>());
    }

    private record PendingTask(WorldRef world, int chunkX, int chunkZ, Runnable task) {}
}
