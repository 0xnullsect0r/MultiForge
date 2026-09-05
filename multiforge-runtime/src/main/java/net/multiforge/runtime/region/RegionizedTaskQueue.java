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
public final class RegionizedTaskQueue implements RegionListener {

    /** Look up the owner region for a chunk. May return {@code null} for unloaded chunks. */
    @FunctionalInterface
    public interface OwnerLookup {
        Region regionAtChunk(WorldRef world, int chunkX, int chunkZ);
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
    private final ConcurrentMap<RegionId, Queue<Runnable>> inboxes = new ConcurrentHashMap<>();
    private final ConcurrentMap<OrphanBucket, Queue<PendingTask>> orphanedBySection = new ConcurrentHashMap<>();

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
     *
     * <p><b>Known race (documented, unfixed as of session-2 revert):</b>
     * this method is lock-free relative to the regionizer's merge/death
     * path. A concurrent {@code mergeInto} or last-chunk removal that
     * fires between our lookup and add can leave the task in a dying
     * region's inbox, which the death listener then clears. The task
     * is silently lost. A prior fix attempted a post-add recheck but
     * itself had a double-execute race (see /67 round-2 findings) and
     * was reverted. Proper fix requires taking the regionizer read
     * lock around the enqueue, deferred to a design session.
     */
    public void queueChunkTask(WorldRef world, int chunkX, int chunkZ, Runnable task) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(task, "task");
        Region owner = ownerLookup.regionAtChunk(world, chunkX, chunkZ);
        if (owner == null) {
            orphanBucketFor(world, chunkX, chunkZ).add(new PendingTask(world, chunkX, chunkZ, task));
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
        int n = 0;
        for (Queue<PendingTask> q : orphanedBySection.values()) n += q.size();
        return n;
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

    private Queue<Runnable> inboxFor(Region region) {
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
