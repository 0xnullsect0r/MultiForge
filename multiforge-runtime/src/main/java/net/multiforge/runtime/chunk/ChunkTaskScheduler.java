/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.chunk;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionId;
import net.multiforge.runtime.region.RegionizedTaskQueue;

/**
 * Routes chunk-level work (load / unload / generate / light / IO)
 * through the region owner's inbox in the given priority order.
 * Priorities are strict: within one region, BLOCKING drains before
 * HIGHEST, HIGHEST before HIGH, etc.
 *
 * <p>The scheduler doesn't run tasks itself — it only enqueues them.
 * {@link net.multiforge.runtime.region.TickRegionScheduler} draining
 * the {@link RegionizedTaskQueue} is what pulls them.
 */
public final class ChunkTaskScheduler {

    private final RegionizedTaskQueue taskQueue;
    private final RegionOwnerLookup regionLookup;
    private final ConcurrentMap<RegionId, EnumMap<ChunkTaskPriority, ConcurrentLinkedDeque<Runnable>>>
            perRegionPriority = new ConcurrentHashMap<>();

    /** Resolve the owner region for a chunk. Same shape as {@link RegionizedTaskQueue}'s. */
    @FunctionalInterface
    public interface RegionOwnerLookup {
        Region regionAtChunk(WorldRef world, int chunkX, int chunkZ);
    }

    public ChunkTaskScheduler(RegionizedTaskQueue taskQueue, RegionOwnerLookup regionLookup) {
        this.taskQueue = Objects.requireNonNull(taskQueue, "taskQueue");
        this.regionLookup = Objects.requireNonNull(regionLookup, "regionLookup");
    }

    /**
     * Enqueue {@code task} for the region owning {@code (chunkX, chunkZ)}
     * at the given priority. If the chunk has no owner yet, the task
     * lands in the underlying task queue's orphan list.
     */
    public void scheduleChunkTask(WorldRef world, int chunkX, int chunkZ, Runnable task, ChunkTaskPriority priority) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(priority, "priority");
        Region owner = regionLookup.regionAtChunk(world, chunkX, chunkZ);
        if (owner == null) {
            // Fall back to the taskQueue's orphan path — reroute() will retry later.
            taskQueue.queueChunkTask(world, chunkX, chunkZ, task);
            return;
        }
        priorityDeque(owner.id(), priority).addLast(task);
        // Wake the owning region by enqueueing a drain trampoline into its inbox.
        // The trampoline pulls tasks in priority order at region tick time.
        taskQueue.queueChunkTask(world, chunkX, chunkZ, () -> drainInto(owner.id()));
    }

    /** Convenience wrapper. */
    public void scheduleChunkTask(WorldRef world, ChunkPos pos, Runnable task, ChunkTaskPriority priority) {
        scheduleChunkTask(world, pos.x(), pos.z(), task, priority);
    }

    /**
     * Drain up to {@code max} tasks from {@code region}'s priority
     * queues, in strict priority order (BLOCKING first). Returns the
     * number of tasks executed.
     */
    public int drainInto(RegionId region) {
        return drainInto(region, Integer.MAX_VALUE);
    }

    public int drainInto(RegionId region, int max) {
        EnumMap<ChunkTaskPriority, ConcurrentLinkedDeque<Runnable>> pri = perRegionPriority.get(region);
        if (pri == null) return 0;
        int run = 0;
        for (ChunkTaskPriority p : ChunkTaskPriority.values()) {
            ConcurrentLinkedDeque<Runnable> q = pri.get(p);
            if (q == null) continue;
            while (run < max) {
                Runnable r = q.pollFirst();
                if (r == null) break;
                try {
                    r.run();
                } catch (Throwable t) {
                    Thread.currentThread().getUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), t);
                }
                run++;
            }
            if (run >= max) break;
        }
        return run;
    }

    public int pending(RegionId region, ChunkTaskPriority priority) {
        EnumMap<ChunkTaskPriority, ConcurrentLinkedDeque<Runnable>> pri = perRegionPriority.get(region);
        if (pri == null) return 0;
        ConcurrentLinkedDeque<Runnable> q = pri.get(priority);
        return q == null ? 0 : q.size();
    }

    /** Merge {@code source}'s priority queues into {@code target}'s. */
    public void onRegionMerged(RegionId target, RegionId source) {
        EnumMap<ChunkTaskPriority, ConcurrentLinkedDeque<Runnable>> src = perRegionPriority.remove(source);
        if (src == null) return;
        EnumMap<ChunkTaskPriority, ConcurrentLinkedDeque<Runnable>> tgt =
                perRegionPriority.computeIfAbsent(target, id -> newPriorityMap());
        for (Map.Entry<ChunkTaskPriority, ConcurrentLinkedDeque<Runnable>> e : src.entrySet()) {
            ConcurrentLinkedDeque<Runnable> tgtQ = tgt.get(e.getKey());
            Runnable r;
            while ((r = e.getValue().pollFirst()) != null) tgtQ.addLast(r);
        }
    }

    /**
     * For split events: move tasks matching {@code shouldLeave} into
     * {@code target}'s queues. Task deque is treated in insertion
     * order — callers get FIFO within each priority.
     */
    public void onRegionSplit(RegionId source, RegionId target, BiConsumer<RegionId, Runnable> reroute) {
        // Simplified: the M3 tick-body binding knows exactly which
        // tasks belong to which chunk. For now, we hand off nothing —
        // the split callback exists to preserve the API shape.
        // (Real chunk tasks are re-enqueued via scheduleChunkTask when
        // the new owner processes its holders.)
    }

    private ConcurrentLinkedDeque<Runnable> priorityDeque(RegionId region, ChunkTaskPriority priority) {
        return perRegionPriority.computeIfAbsent(region, id -> newPriorityMap()).get(priority);
    }

    private static EnumMap<ChunkTaskPriority, ConcurrentLinkedDeque<Runnable>> newPriorityMap() {
        EnumMap<ChunkTaskPriority, ConcurrentLinkedDeque<Runnable>> m = new EnumMap<>(ChunkTaskPriority.class);
        for (ChunkTaskPriority p : ChunkTaskPriority.values()) m.put(p, new ConcurrentLinkedDeque<>());
        return m;
    }
}
