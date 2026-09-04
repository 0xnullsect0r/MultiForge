/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.region;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
 * by an internal write lock.
 */
public final class ThreadedRegionizer {

    /** 8 immediate neighbours around a section, radius 1. */
    private static final int[][] NEIGHBOURS =
            new int[][] {{-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}};

    private final WorldRef world;
    private final int sectionChunkShift;
    private final ConcurrentMap<SectionPos, Region> sectionToRegion = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

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
        synchronized (writeLock) {
            Region existing = sectionToRegion.get(section);
            if (existing != null) return existing;

            Set<Region> neighbours = neighbouringRegions(section);
            Region target;
            if (neighbours.isEmpty()) {
                target = new Region(RegionId.next(), sectionChunkShift);
            } else {
                target = pickAnchor(neighbours);
                neighbours.remove(target);
                for (Region other : neighbours) {
                    mergeInto(target, other);
                }
            }
            target.addSection(section);
            sectionToRegion.put(section, target);
            target.markReady(); // safe: transient → ready
            return target;
        }
    }

    /**
     * Remove the section containing {@code pos} from its region. If the
     * removal disconnects the region, the connected components become
     * independent regions.
     */
    public void removeChunk(ChunkPos pos) {
        SectionPos section = SectionPos.ofChunk(pos.x(), pos.z(), sectionChunkShift);
        synchronized (writeLock) {
            Region region = sectionToRegion.remove(section);
            if (region == null) return;
            region.removeSection(section);
            if (region.sectionCount() == 0) {
                region.markDead();
                return;
            }
            // Recompute connected components; each becomes its own region.
            splitIfDisconnected(region);
        }
    }

    /** Force a merge of {@code other} into {@code target}. Package-private for {@link Region} tests. */
    void mergeInto(Region target, Region other) {
        if (target == other) return;
        Set<SectionPos> drained = other.drainSections();
        for (SectionPos s : drained) {
            target.addSection(s);
            sectionToRegion.put(s, target);
        }
        other.markDead();
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
     */
    private void splitIfDisconnected(Region region) {
        Set<SectionPos> remaining = new HashSet<>(region.sections());
        if (remaining.size() <= 1) return; // nothing to split

        // Flood fill from any starting section.
        Set<SectionPos> firstComponent =
                floodFill(remaining, remaining.iterator().next());
        if (firstComponent.size() == remaining.size()) return; // still connected

        // Multiple components. Region keeps the first component; the
        // rest are peeled off into new regions.
        for (SectionPos leaving : new ArrayList<>(remaining)) {
            if (firstComponent.contains(leaving)) continue;
            region.removeSection(leaving);
            sectionToRegion.remove(leaving);
        }
        Set<SectionPos> orphaned = new HashSet<>(remaining);
        orphaned.removeAll(firstComponent);
        while (!orphaned.isEmpty()) {
            SectionPos start = orphaned.iterator().next();
            Set<SectionPos> component = floodFill(orphaned, start);
            Region fresh = new Region(RegionId.next(), sectionChunkShift);
            for (SectionPos s : component) {
                fresh.addSection(s);
                sectionToRegion.put(s, fresh);
            }
            fresh.markReady();
            orphaned.removeAll(component);
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
}
