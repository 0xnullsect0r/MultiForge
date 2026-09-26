/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.multiforge.runtime.region;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;

/**
 * Per-region mailboxes used to route cross-region work. The primary
 * entry point is {@link #queueChunkTask(WorldRef, int, int, Runnable)}:
 * the task is queued on the region that owns that chunk when it is
 * queued, and it follows the chunk if ownership changes before it runs:
 * a merge moves the dying region's inbox to the survivor, a split moves
 * each task whose chunk went to the new region, and a task for an unloaded
 * chunk waits in the orphan queue until the chunk loads.
 *
 * <p>Inboxes are unbounded per region — the tick pipeline drains them
 * in bounded batches instead. Backpressure shows up as region MSPT.
 *
 * <p>The queue does not know about {@link ThreadedRegionizer} state
 * transitions on its own; callers hand it a resolver so it can look
 * up ownership when a task is queued or re-routed. This lets us wire the same queue to
 * either the {@link ThreadedRegionizer} (production) or a stub map
 * (unit tests).
 */
public final class RegionizedTaskQueue implements RegionListener {

    /** Look up the owner region for a chunk. May return {@code null} for unloaded chunks. */
    @FunctionalInterface
    public interface OwnerLookup {
        Region regionAtChunk(WorldRef world, int chunkX, int chunkZ);
    }

    /**
     * Look up the regionizer read lock for a world so {@link
     * #queueChunkTask(WorldRef, int, int, Runnable)} can pin the
     * section→region mapping across its resolve-then-enqueue pair. May
     * return {@code null} for callers that opt out of read-lock
     * enforcement (unit tests with a stub owner lookup, or callers whose
     * regionizer is not yet materialised for {@code world}). Phase 1
     * task 1.2.
     */
    @FunctionalInterface
    public interface ReadLockLookup {
        Lock readLockFor(WorldRef world);
    }

    /**
     * Section-shift used to bucket orphaned tasks. /67 round-4 changed
     * the orphan queue from a single flat list to a per-section
     * partition so per-chunk-load reroute is O(bucket) instead of
     * O(all orphans). 4 = 16-chunk sections (matches the regionizer's
     * default sectionChunkShift); orphan-bucket size is independent of
     * region size so we hardcode it.
     */
    private static final int ORPHAN_SECTION_SHIFT = 4;

    private final OwnerLookup ownerLookup;
    private final ReadLockLookup readLockLookup;
    private final ConcurrentMap<RegionId, Queue<PendingTask>> inboxes = new ConcurrentHashMap<>();
    private final ConcurrentMap<OrphanBucket, Queue<PendingTask>> orphanedBySection = new ConcurrentHashMap<>();

    /**
     * Legacy constructor: no read-lock enforcement around {@link
     * #queueChunkTask}. Only safe when the caller can guarantee no
     * concurrent merge/death (e.g. unit tests with a stub regionizer).
     * Production callers must use {@link
     * #RegionizedTaskQueue(OwnerLookup, ReadLockLookup)} — Phase 1 task
     * 1.2.
     */
    public RegionizedTaskQueue(OwnerLookup ownerLookup) {
        this(ownerLookup, world -> null);
    }

    public RegionizedTaskQueue(OwnerLookup ownerLookup, ReadLockLookup readLockLookup) {
        this.ownerLookup = Objects.requireNonNull(ownerLookup, "ownerLookup");
        this.readLockLookup = Objects.requireNonNull(readLockLookup, "readLockLookup");
    }

    /**
     * Convenience wrapper for callers that already hold a
     * {@link ThreadedRegionizer} directly. Wires the regionizer's
     * {@link ThreadedRegionizer#readLock() read lock} so {@link
     * #queueChunkTask} is safe against concurrent merges/deaths.
     */
    public static RegionizedTaskQueue of(ThreadedRegionizer regionizer) {
        return new RegionizedTaskQueue((w, x, z) -> regionizer.regionAtChunk(x, z), w -> regionizer.readLock());
    }

    /**
     * Enqueue {@code task} for the region owning {@code (chunkX, chunkZ)}
     * in {@code world}. If the chunk is currently unloaded the task
     * lands in the orphan queue and will be re-routed the next time
     * {@link #reroute()} runs — after chunk load.
     *
     * <p><b>Phase 1 task 1.2 fix.</b> The resolve-then-enqueue pair
     * <em>(ownerLookup → inboxFor(owner).add)</em> now runs under the
     * regionizer's read lock (via {@link ReadLockLookup#readLockFor}).
     * This blocks any concurrent {@code mergeInto} or last-chunk removal
     * for the duration of the pair, so the owner we resolved cannot die
     * or be folded into another region before our {@code inboxFor(owner).add}
     * commits. On the merge side, {@link #onRegionsMerging} runs under
     * the same regionizer's write lock and moves any inbox entries we
     * had just added into the surviving region — no task is lost.
     *
     * <p>The read lock is intentionally optional: legacy constructors and
     * unit tests without a live regionizer pass a lookup that returns
     * {@code null}, degrading to the prior lock-free shape. Production
     * wiring in {@code MultiThreadedSchedulerHost} and the {@link
     * #of(ThreadedRegionizer)} convenience always provides a real lock.
     */
    public void queueChunkTask(WorldRef world, int chunkX, int chunkZ, Runnable task) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(task, "task");
        Lock readLock = readLockLookup.readLockFor(world);
        if (readLock != null) readLock.lock();
        try {
            Region owner = ownerLookup.regionAtChunk(world, chunkX, chunkZ);
            if (owner == null) {
                orphanBucketFor(world, chunkX, chunkZ).add(new PendingTask(world, chunkX, chunkZ, task));
                return;
            }
            inboxFor(owner).add(new PendingTask(world, chunkX, chunkZ, task));
        } finally {
            if (readLock != null) readLock.unlock();
        }
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
        Queue<PendingTask> inbox = inboxes.get(region.id());
        if (inbox == null) return 0;
        int run = 0;
        while (run < max) {
            PendingTask p = inbox.poll();
            if (p == null) break;
            try {
                p.task().run();
            } catch (Throwable t) {
                // Never let a mod-thrown exception drop the tick pipeline.
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
        for (OrphanBucket key : orphanedBySection.keySet()) {
            rerouteBucket(key);
        }
    }

    /**
     * Reroute only the orphan bucket covering {@code (chunkX, chunkZ)}
     * in {@code world}. /67 round-4 fix — {@link
     * net.multiforge.neoforge.RegionizedChunkLifecycle} calls this
     * once per {@code ChunkEvent.Load} instead of the whole-queue
     * {@link #reroute()}, so per-chunk cost is O(bucket) rather than
     * O(all orphans).
     */
    public void rerouteAtChunk(WorldRef world, int chunkX, int chunkZ) {
        rerouteBucket(OrphanBucket.of(world, chunkX, chunkZ));
    }

    private void rerouteBucket(OrphanBucket key) {
        Queue<PendingTask> bucket = orphanedBySection.get(key);
        if (bucket == null) return;
        int size = bucket.size();
        int drained = 0;
        PendingTask pending;
        while (drained < size && (pending = bucket.poll()) != null) {
            Region owner = ownerLookup.regionAtChunk(pending.world, pending.chunkX, pending.chunkZ);
            if (owner == null) {
                bucket.add(pending);
            } else {
                inboxFor(owner).add(pending);
            }
            drained++;
        }
    }

    public int inboxSize(Region region) {
        Queue<PendingTask> q = inboxes.get(region.id());
        return q == null ? 0 : q.size();
    }

    public int orphanedSize() {
        int n = 0;
        for (Queue<PendingTask> q : orphanedBySection.values()) n += q.size();
        return n;
    }

    /** Merge {@code source}'s pending inbox into {@code target}'s. */
    public void moveInbox(Region source, Region target) {
        Queue<PendingTask> src = inboxes.remove(source.id());
        if (src == null) return;
        Queue<PendingTask> tgt = inboxFor(target);
        PendingTask p;
        while ((p = src.poll()) != null) tgt.add(p);
    }

    /** Drop every task for {@code region} — used when a region dies. */
    public void clear(Region region) {
        inboxes.remove(region.id());
    }

    /**
     * {@link RegionListener} hook: on a merge, hand the dying region's
     * pending inbox to the survivor so no queued task is lost.
     */
    @Override
    public void onRegionsMerging(Region surviving, Region dying) {
        moveInbox(dying, surviving);
    }

    /**
     * {@link RegionListener} hook: on region death (after a merge or
     * last-section removal), drop any residual entries. In the merge
     * case {@link #onRegionsMerging} already moved them; in the
     * last-chunk case the queue simply disappears.
     */
    @Override
    public void onRegionDied(Region region) {
        clear(region);
    }

    /**
     * {@link RegionListener} hook: after a split, tasks queued on the source
     * for chunks that now belong to {@code child} move to the child's inbox, so
     * they run on the worker that owns their chunk. Each task carries the chunk
     * it was queued for; ownership is re-resolved here, after the split.
     */
    @Override
    public void onRegionSplit(Region source, Region child) {
        Queue<PendingTask> src = inboxes.get(source.id());
        if (src == null) return;
        int size = src.size();
        for (int i = 0; i < size; i++) {
            PendingTask p = src.poll();
            if (p == null) break;
            Region owner = ownerLookup.regionAtChunk(p.world, p.chunkX, p.chunkZ);
            if (owner == null) {
                orphanBucketFor(p.world, p.chunkX, p.chunkZ).add(p);
            } else {
                inboxFor(owner).add(p);
            }
        }
    }

    private Queue<PendingTask> inboxFor(Region region) {
        return inboxes.computeIfAbsent(region.id(), id -> new ConcurrentLinkedQueue<>());
    }

    private Queue<PendingTask> orphanBucketFor(WorldRef world, int chunkX, int chunkZ) {
        return orphanedBySection.computeIfAbsent(
                OrphanBucket.of(world, chunkX, chunkZ), k -> new ConcurrentLinkedQueue<>());
    }

    /** Section-shifted key used to bucket orphan tasks per (world, section). */
    private record OrphanBucket(String worldId, int sectionX, int sectionZ) {
        static OrphanBucket of(WorldRef world, int chunkX, int chunkZ) {
            return new OrphanBucket(
                    world.dimensionId(), chunkX >> ORPHAN_SECTION_SHIFT, chunkZ >> ORPHAN_SECTION_SHIFT);
        }
    }

    private record PendingTask(WorldRef world, int chunkX, int chunkZ, Runnable task) {}
}
