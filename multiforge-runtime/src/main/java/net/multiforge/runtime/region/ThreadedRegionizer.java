/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.region;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;

/**
 * Per-world regionizer: maintains the section→region map and enforces
 * the invariant that every occupied chunk belongs to exactly one
 * region. Handles add/remove of sections and merge/split of regions
 * based on adjacency.
 *
 * <p>Design follows Folia's {@code ThreadedRegionizer} at a coarse
 * grain: 2^{@code sectionChunkShift} chunks per side make up one
 * <em>section</em>, and regions are the transitive closure of adjacent
 * occupied sections within a configurable merge radius.
 *
 * <p>The class is safe for concurrent reads via {@link
 * #regionAtChunk(int, int)}; structural mutations ({@link
 * #addChunk(ChunkPos)} / {@link #removeChunk(ChunkPos)}) are guarded
 * by an internal {@link ReentrantReadWriteLock}. External callers that
 * must observe a stable region ownership for the duration of some
 * multi-step read (e.g. {@link RegionizedTaskQueue#queueChunkTask
 * queueChunkTask}'s resolve-then-enqueue) can acquire {@link #readLock()}
 * to block merge/split for the duration of the read.
 */
public final class ThreadedRegionizer {

    /** 8 immediate neighbours around a section, radius 1. */
    private static final int[][] NEIGHBOURS =
            new int[][] {{-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}};

    private final WorldRef world;
    private final int sectionChunkShift;
    private final ConcurrentMap<SectionPos, Region> sectionToRegion = new ConcurrentHashMap<>();

    /**
     * Structural mutation lock. Write side is held by {@link #addChunk} /
     * {@link #removeChunk} (and the {@link #mergeInto} / {@link
     * #splitIfDisconnected} helpers they call). Read side is exposed via
     * {@link #readLock()} so external callers can pin the section→region
     * map for the duration of a multi-step read.
     *
     * <p>Phase 1 task 1.2: {@link RegionizedTaskQueue#queueChunkTask}
     * acquires this read lock around its resolve-then-enqueue pair so
     * that a concurrent merge/death cannot silently drop the task by
     * clearing the dying region's inbox between the two steps.
     */
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    private final List<RegionListener> listeners = new CopyOnWriteArrayList<>();

    public ThreadedRegionizer(WorldRef world, int sectionChunkShift) {
        if (sectionChunkShift < 0 || sectionChunkShift > 8) {
            throw new IllegalArgumentException("sectionChunkShift out of range: " + sectionChunkShift);
        }
        this.world = Objects.requireNonNull(world, "world");
        this.sectionChunkShift = sectionChunkShift;
    }

    public WorldRef world() {
        return world;
    }

    public int sectionChunkShift() {
        return sectionChunkShift;
    }

    /**
     * Register a {@link RegionListener} that receives merge/split/death
     * notifications for regions in this world. Listeners fire under the
     * regionizer's write lock; they must not block or reenter the
     * regionizer.
     */
    public void addListener(RegionListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Remove a previously-added listener. No-op if not present. */
    public void removeListener(RegionListener listener) {
        listeners.remove(listener);
    }

    /**
     * Expose the read side of the structural mutation lock. Held for the
     * duration of a read that must observe a stable section→region
     * mapping (see the class javadoc). Cheap to acquire (multiple readers
     * proceed in parallel); blocks only while a merger/split is in
     * progress.
     */
    public Lock readLock() {
        return rwLock.readLock();
    }

    /** @return the region currently owning {@code (chunkX, chunkZ)}, or null if unoccupied. */
    public Region regionAtChunk(int chunkX, int chunkZ) {
        return sectionToRegion.get(SectionPos.ofChunk(chunkX, chunkZ, sectionChunkShift));
    }

    public Region regionAtChunk(ChunkPos pos) {
        return regionAtChunk(pos.x(), pos.z());
    }

    /**
     * Snapshot of all currently-live regions. Order is unspecified.
     */
    public Collection<Region> regions() {
        // Deduplicate: a merged region can transiently appear under multiple keys.
        Set<Region> seen = new HashSet<>(sectionToRegion.size());
        seen.addAll(sectionToRegion.values());
        return List.copyOf(seen);
    }

    /**
     * Mark the chunk containing {@code pos} as occupied. Creates a new
     * region if necessary, or merges neighbouring regions if the new
     * section bridges them.
     *
     * @return the region that owns the section after the call.
     */
    public Region addChunk(ChunkPos pos) {
        SectionPos section = SectionPos.ofChunk(pos.x(), pos.z(), sectionChunkShift);
        rwLock.writeLock().lock();
        try {
            Region existing = sectionToRegion.get(section);
            if (existing != null) return existing;

            Set<Region> neighbours = neighbouringRegions(section);
            Region target;
            boolean freshRegion = false;
            if (neighbours.isEmpty()) {
                target = new Region(RegionId.next(), sectionChunkShift);
                freshRegion = true;
            } else {
                target = pickAnchor(neighbours);
                neighbours.remove(target);
                for (Region other : neighbours) {
                    mergeInto(target, other);
                }
            }
            target.addSection(section);
            sectionToRegion.put(section, target);
            if (freshRegion) {
                target.markReady(); // safe: transient → ready
                fireRegionCreated(target);
            }
            return target;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Remove the section containing {@code pos} from its region. If the
     * removal disconnects the region, the connected components become
     * independent regions.
     */
    public void removeChunk(ChunkPos pos) {
        SectionPos section = SectionPos.ofChunk(pos.x(), pos.z(), sectionChunkShift);
        rwLock.writeLock().lock();
        try {
            Region region = sectionToRegion.remove(section);
            if (region == null) return;
            region.removeSection(section);
            if (region.sectionCount() == 0) {
                region.markDead();
                fireRegionDied(region);
                return;
            }
            // Recompute connected components; each becomes its own region.
            splitIfDisconnected(region);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** Force a merge of {@code other} into {@code target}. Package-private for {@link Region} tests. */
    void mergeInto(Region target, Region other) {
        if (target == other) return;
        // Fire the pre-death listener notification BEFORE touching state so
        // listeners can fold side state while both regions are still queryable
        // (chunks.md:60-72). After fold, sections migrate and other dies.
        fireRegionsMerging(target, other);
        Set<SectionPos> drained = other.drainSections();
        for (SectionPos s : drained) {
            target.addSection(s);
            sectionToRegion.put(s, target);
        }
        other.markDead();
        fireRegionDied(other);
    }

    private Set<Region> neighbouringRegions(SectionPos section) {
        Set<Region> out = new HashSet<>(4);
        for (int[] delta : NEIGHBOURS) {
            SectionPos n = new SectionPos(section.x() + delta[0], section.z() + delta[1]);
            Region r = sectionToRegion.get(n);
            if (r != null && r.state() != RegionState.DEAD) out.add(r);
        }
        return out;
    }

    /** Prefer the largest neighbour so total copy work is minimised. */
    private static Region pickAnchor(Set<Region> neighbours) {
        Region best = null;
        int bestSize = -1;
        for (Region r : neighbours) {
            int s = r.sectionCount();
            if (s > bestSize) {
                best = r;
                bestSize = s;
            }
        }
        return best;
    }

    /**
     * Rebuild the section→region mapping for {@code region} by
     * flood-filling from its remaining sections. Sections in a
     * different connected component are moved to fresh regions.
     *
     * <p>/67 round-4 fix (1.3): the previous implementation removed
     * leaving sections from {@link #sectionToRegion} BEFORE
     * constructing the replacement regions, opening a window where
     * concurrent lock-free {@link #regionAtChunk} readers (including
     * the M9 shadow bridge) saw a spurious {@code null} and silently
     * dropped observations. The fixed shape stages every reassignment
     * off-map first, then applies each `put(section, fresh)` directly
     * over the existing source-region mapping — no intermediate null
     * state ever exists in {@code sectionToRegion}. Listener fires
     * happen only after all reassignments are applied so callbacks
     * observe a fully-consistent map.
     */
    private void splitIfDisconnected(Region region) {
        Set<SectionPos> remaining = new HashSet<>(region.sections());
        if (remaining.size() <= 1) return; // nothing to split

        // Flood fill from any starting section.
        Set<SectionPos> firstComponent =
                floodFill(remaining, remaining.iterator().next());
        if (firstComponent.size() == remaining.size()) return; // still connected

        // Stage the (section → fresh region) reassignment map fully off-map first.
        List<Region> freshRegions = new ArrayList<>();
        Map<SectionPos, Region> reassignments = new HashMap<>();
        Set<SectionPos> orphaned = new HashSet<>(remaining);
        orphaned.removeAll(firstComponent);
        while (!orphaned.isEmpty()) {
            SectionPos start = orphaned.iterator().next();
            Set<SectionPos> component = floodFill(orphaned, start);
            Region fresh = new Region(RegionId.next(), sectionChunkShift);
            for (SectionPos s : component) {
                fresh.addSection(s);
                reassignments.put(s, fresh);
            }
            fresh.markReady();
            freshRegions.add(fresh);
            orphaned.removeAll(component);
        }

        // Apply: each put overwrites the existing source-region mapping directly,
        // so a concurrent regionAtChunk never sees a null entry. removeSection
        // on the source is a distinct set mutation and does not affect the
        // sectionToRegion map's atomicity for readers.
        for (Map.Entry<SectionPos, Region> e : reassignments.entrySet()) {
            region.removeSection(e.getKey());
            sectionToRegion.put(e.getKey(), e.getValue());
        }

        // Fire listeners AFTER all reassignments so any callback that reads
        // regionAtChunk observes final state, not partial state.
        for (Region fresh : freshRegions) {
            fireRegionSplit(region, fresh);
            fireRegionCreated(fresh);
        }
    }

    private Set<SectionPos> floodFill(Set<SectionPos> universe, SectionPos start) {
        Set<SectionPos> reached = new HashSet<>();
        ArrayDeque<SectionPos> stack = new ArrayDeque<>();
        stack.push(start);
        reached.add(start);
        while (!stack.isEmpty()) {
            SectionPos p = stack.pop();
            for (int[] delta : NEIGHBOURS) {
                SectionPos n = new SectionPos(p.x() + delta[0], p.z() + delta[1]);
                if (universe.contains(n) && reached.add(n)) stack.push(n);
            }
        }
        return reached;
    }

    /** Test/observability hook — visit every live region exactly once. */
    public void forEachRegion(Consumer<Region> visitor) {
        for (Region r : regions()) visitor.accept(r);
    }

    private void fireRegionCreated(Region region) {
        for (RegionListener l : listeners) l.onRegionCreated(region);
    }

    private void fireRegionsMerging(Region surviving, Region dying) {
        for (RegionListener l : listeners) l.onRegionsMerging(surviving, dying);
    }

    private void fireRegionSplit(Region source, Region child) {
        for (RegionListener l : listeners) l.onRegionSplit(source, child);
    }

    private void fireRegionDied(Region region) {
        for (RegionListener l : listeners) l.onRegionDied(region);
    }
}
