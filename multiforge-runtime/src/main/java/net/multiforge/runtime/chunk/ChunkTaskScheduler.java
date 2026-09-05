/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.chunk;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionId;
import net.multiforge.runtime.region.RegionListener;
import net.multiforge.runtime.region.RegionizedTaskQueue;
import net.multiforge.runtime.region.SectionPos;

/**
 * Routes chunk-level work (load / unload / generate / light / IO)
 * through the region owner's inbox in the given priority order.
 * Priorities are strict: within one region, BLOCKING drains before
 * HIGHEST, HIGHEST before HIGH, etc.
 *
 * <p>The scheduler doesn't run tasks itself — it only enqueues them.
 * {@link net.multiforge.runtime.region.TickRegionScheduler} draining
 * the {@link RegionizedTaskQueue} is what pulls them.
 *
 * <p>Implements {@link RegionListener} so the regionizer's fire path
 * folds per-region priority deques automatically on merge and PEELS
 * per-priority queue entries into the child region on split. Prior to
 * /67 round-4 the split path was a documented no-op ("chunk-priority
 * deques stay with the source") — safe only because no production
 * caller wired the scheduler yet. Phase-1 fix 1.6 wraps each queued
 * task in a {@link ChunkPositionedTask} so the split callback can
 * filter by chunk position via the child region's section membership.
 */
public final class ChunkTaskScheduler implements RegionListener {

    private final RegionizedTaskQueue taskQueue;
    private final RegionOwnerLookup regionLookup;
    private final ConcurrentMap<RegionId, EnumMap<ChunkTaskPriority, ConcurrentLinkedDeque<ChunkPositionedTask>>>
            perRegionPriority = new ConcurrentHashMap<>();

    /** Resolve the owner region for a chunk. Same shape as {@link RegionizedTaskQueue}'s. */
    @FunctionalInterface
    public interface RegionOwnerLookup {
        Region regionAtChunk(WorldRef world, int chunkX, int chunkZ);
    }

    /**
     * Deque element that carries the chunk position alongside the
     * runnable so the split path can filter by position. Records also
     * carry the enqueuing world so future distance-based prioritisation
     * has the info it needs.
     */
    private record ChunkPositionedTask(WorldRef world, int chunkX, int chunkZ, Runnable task) {}

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
        priorityDeque(owner.id(), priority).addLast(new ChunkPositionedTask(world, chunkX, chunkZ, task));
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
        EnumMap<ChunkTaskPriority, ConcurrentLinkedDeque<ChunkPositionedTask>> pri = perRegionPriority.get(region);
        if (pri == null) return 0;
        int run = 0;
        for (ChunkTaskPriority p : ChunkTaskPriority.values()) {
            ConcurrentLinkedDeque<ChunkPositionedTask> q = pri.get(p);
            if (q == null) continue;
            while (run < max) {
                ChunkPositionedTask wrapped = q.pollFirst();
                if (wrapped == null) break;
                try {
                    wrapped.task().run();
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
        EnumMap<ChunkTaskPriority, ConcurrentLinkedDeque<ChunkPositionedTask>> pri = perRegionPriority.get(region);
        if (pri == null) return 0;
        ConcurrentLinkedDeque<ChunkPositionedTask> q = pri.get(priority);
        return q == null ? 0 : q.size();
    }

    /** Merge {@code source}'s priority queues into {@code target}'s. */
    public void onRegionMerged(RegionId target, RegionId source) {
        EnumMap<ChunkTaskPriority, ConcurrentLinkedDeque<ChunkPositionedTask>> src = perRegionPriority.remove(source);
        if (src == null) return;
        EnumMap<ChunkTaskPriority, ConcurrentLinkedDeque<ChunkPositionedTask>> tgt =
                perRegionPriority.computeIfAbsent(target, id -> newPriorityMap());
        for (Map.Entry<ChunkTaskPriority, ConcurrentLinkedDeque<ChunkPositionedTask>> e : src.entrySet()) {
            ConcurrentLinkedDeque<ChunkPositionedTask> tgtQ = tgt.get(e.getKey());
            ChunkPositionedTask r;
            while ((r = e.getValue().pollFirst()) != null) tgtQ.addLast(r);
        }
    }

    /**
     * For split events: move tasks matching {@code shouldLeave} into
     * {@code target}'s queues. Task deque is treated in insertion
     * order — callers get FIFO within each priority.
     *
     * <p>Note: retained for API compatibility with the RegionId-typed
     * legacy overload. Prefer the {@link RegionListener} overload
     * that takes {@code Region} instances — the regionizer's fire path
     * already invokes it under the write lock with the child region's
     * section membership implicitly available via
     * {@link Region#sections()}.
     */
    public void onRegionSplit(RegionId source, RegionId target, BiConsumer<RegionId, Runnable> reroute) {
        // Deferred: callers using the RegionId-typed overload must
        // provide their own position → predicate mapping. Production
        // fires via the RegionListener overload below.
    }

    private ConcurrentLinkedDeque<ChunkPositionedTask> priorityDeque(RegionId region, ChunkTaskPriority priority) {
        return perRegionPriority.computeIfAbsent(region, id -> newPriorityMap()).get(priority);
    }

    private static EnumMap<ChunkTaskPriority, ConcurrentLinkedDeque<ChunkPositionedTask>> newPriorityMap() {
        EnumMap<ChunkTaskPriority, ConcurrentLinkedDeque<ChunkPositionedTask>> m =
                new EnumMap<>(ChunkTaskPriority.class);
        for (ChunkTaskPriority p : ChunkTaskPriority.values()) m.put(p, new ConcurrentLinkedDeque<>());
        return m;
    }

    // === RegionListener ===

    @Override
    public void onRegionsMerging(Region surviving, Region dying) {
        onRegionMerged(surviving.id(), dying.id());
    }

    /**
     * /67 round-4 fix (1.6): peel per-priority tasks whose chunk
     * position falls in {@code child}'s section membership out of
     * {@code source}'s deque into {@code child}'s. Preserves per-
     * priority FIFO ordering; source deque keeps every task the child
     * doesn't claim. Prior no-op behaviour would have discarded the
     * child region's chunk work when the source region died via a
     * subsequent merge (onRegionDied removes source's map, and split
     * had never moved the child's tasks out).
     */
    @Override
    public void onRegionSplit(Region source, Region child) {
        EnumMap<ChunkTaskPriority, ConcurrentLinkedDeque<ChunkPositionedTask>> src = perRegionPriority.get(source.id());
        if (src == null) return;
        int shift = child.sectionChunkShift();
        Set<SectionPos> childSections = child.sections();
        EnumMap<ChunkTaskPriority, ConcurrentLinkedDeque<ChunkPositionedTask>> dst =
                perRegionPriority.computeIfAbsent(child.id(), id -> newPriorityMap());
        for (ChunkTaskPriority p : ChunkTaskPriority.values()) {
            ConcurrentLinkedDeque<ChunkPositionedTask> srcQ = src.get(p);
            if (srcQ == null || srcQ.isEmpty()) continue;
            ConcurrentLinkedDeque<ChunkPositionedTask> dstQ = dst.get(p);
            // ConcurrentLinkedDeque iterator.remove() is weakly-consistent and
            // may silently fail to remove — use removeIf which is spec'd to
            // remove all matching elements atomically per-element. Collect
            // matches first, add to child, then removeIf on source to detach.
            List<ChunkPositionedTask> moving = new ArrayList<>();
            for (ChunkPositionedTask t : srcQ) {
                if (childSections.contains(SectionPos.ofChunk(t.chunkX(), t.chunkZ(), shift))) {
                    moving.add(t);
                }
            }
            for (ChunkPositionedTask t : moving) dstQ.addLast(t);
            srcQ.removeIf(moving::contains);
        }
    }

    @Override
    public void onRegionDied(Region region) {
        perRegionPriority.remove(region.id());
    }
}
