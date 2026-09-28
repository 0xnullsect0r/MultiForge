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
import java.util.function.Supplier;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.pin.RegionPin;

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
     * {@link #sectionToRegion} keyed by packed section coordinates, for {@link
     * #regionAtChunk(int, int)}: lock-free and allocation-free. Written in step
     * with {@code sectionToRegion} (see {@link #mapPut}), under the write lock.
     */
    private final PackedSectionIndex packed = new PackedSectionIndex();

    /**
     * Bumped after every change to the section → region map, so {@link
     * #regions()} can hand out one cached snapshot until the topology changes.
     */
    private volatile long topologyVersion;

    private record RegionsSnapshot(long version, List<Region> regions) {}

    private volatile RegionsSnapshot regionsSnapshot;

    /**
     * The loaded chunks of each occupied section (packed {@code x, z}). A
     * section stays in its region until its last chunk is removed; removing
     * one chunk of a section that still has others loaded changes nothing.
     * Guarded by the write lock.
     */
    private final Map<SectionPos, Set<Long>> sectionChunks = new HashMap<>();

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

    /** Every pin of every world; filtered to this world on use. Empty until {@link #setPins}. */
    private volatile Supplier<? extends Collection<RegionPin>> pins = List::of;

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

    /** Whether chunk {@code pos} is loaded (added and not removed). */
    public boolean isChunkLoaded(ChunkPos pos) {
        rwLock.readLock().lock();
        try {
            Set<Long> loaded = sectionChunks.get(SectionPos.ofChunk(pos.x(), pos.z(), sectionChunkShift));
            return loaded != null && loaded.contains(packChunk(pos));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    private static long packChunk(ChunkPos pos) {
        return ((long) pos.x() << 32) | (pos.z() & 0xFFFFFFFFL);
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
        return packed.get(PackedSectionIndex.pack(chunkX >> sectionChunkShift, chunkZ >> sectionChunkShift));
    }

    public Region regionAtChunk(ChunkPos pos) {
        return regionAtChunk(pos.x(), pos.z());
    }

    /**
     * Snapshot of all currently-live regions (immutable). Order is unspecified.
     * The same snapshot is returned until the section → region map changes.
     */
    public Collection<Region> regions() {
        long version = topologyVersion;
        RegionsSnapshot cached = regionsSnapshot;
        if (cached != null && cached.version() == version) return cached.regions();
        // Deduplicate: a merged region can transiently appear under multiple keys.
        Set<Region> seen = new HashSet<>(sectionToRegion.size());
        seen.addAll(sectionToRegion.values());
        List<Region> out = List.copyOf(seen);
        // Tagged with the version read before the copy: a change during the copy
        // bumps the version after it, so this snapshot is never served past it.
        regionsSnapshot = new RegionsSnapshot(version, out);
        return out;
    }

    /** Map {@code section} to {@code region} in both indexes. Caller holds the write lock. */
    private void mapPut(SectionPos section, Region region) {
        sectionToRegion.put(section, region);
        packed.put(PackedSectionIndex.pack(section.x(), section.z()), region);
        topologyVersion++;
    }

    /** Unmap {@code section} from both indexes. Caller holds the write lock. */
    private Region mapRemove(SectionPos section) {
        Region removed = sectionToRegion.remove(section);
        packed.remove(PackedSectionIndex.pack(section.x(), section.z()));
        topologyVersion++;
        return removed;
    }

    /**
     * Mark the chunk {@code pos} as loaded. The first chunk of a section
     * adds the section: a new region, or the neighbouring region (merging
     * neighbours the section bridges). Adding a chunk that is already
     * loaded is a no-op.
     *
     * @return the region that owns the chunk's section after the call.
     */
    public Region addChunk(ChunkPos pos) {
        SectionPos section = SectionPos.ofChunk(pos.x(), pos.z(), sectionChunkShift);
        rwLock.writeLock().lock();
        try {
            sectionChunks.computeIfAbsent(section, k -> new HashSet<>()).add(packChunk(pos));
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
            mapPut(section, target);
            if (freshRegion) {
                target.markReady(); // safe: transient → ready
                fireRegionCreated(target);
            }
            for (RegionPin pin : pinsOverlapping(section)) mergePin(pin, target);
            return target;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Mark the chunk {@code pos} as unloaded. When it was its section's last
     * loaded chunk, the section leaves its region; if that disconnects the
     * region, the connected components become independent regions. Removing
     * a chunk that is not loaded is a no-op.
     */
    public void removeChunk(ChunkPos pos) {
        SectionPos section = SectionPos.ofChunk(pos.x(), pos.z(), sectionChunkShift);
        rwLock.writeLock().lock();
        try {
            Set<Long> loaded = sectionChunks.get(section);
            if (loaded == null || !loaded.remove(packChunk(pos))) return;
            if (!loaded.isEmpty()) return;
            sectionChunks.remove(section);
            Region region = mapRemove(section);
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

    /**
     * Remove every chunk: each live region dies ({@link
     * RegionListener#onRegionDied}) and the regionizer is empty. Used to
     * re-partition a world with a different section size.
     */
    public void clear() {
        rwLock.writeLock().lock();
        try {
            Collection<Region> live = regions();
            sectionToRegion.clear();
            packed.clear();
            topologyVersion++;
            sectionChunks.clear();
            for (Region region : live) {
                region.drainSections();
                region.markDead();
                fireRegionDied(region);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Force a merge of {@code other} into {@code target}. Package-private for
     * {@link Region} tests.
     *
     * <p><b>Quiescence guarantee (Phase 1 task 1.1).</b> Before firing merge
     * listeners, {@code target} is transitioned from {@link RegionState#READY}
     * to {@link RegionState#FOLDING} via {@link Region#tryMarkFolding()}. If
     * {@code target} is currently {@link RegionState#TICKING}, this thread
     * spin-waits (via {@link Thread#onSpinWait()}) until the tick completes
     * and the state returns to READY. Once in FOLDING, {@link
     * Region#tryMarkTicking()} refuses so no worker can start a new tick on
     * {@code target} while listeners fold side state into its slots.
     * After section transfer, {@code target} is transitioned back to READY
     * via {@link Region#markReadyFromFolding()}.
     *
     * <p>Callers holding the regionizer write lock are already serialised
     * against other structural mutations; the spin here bounds only on
     * {@code target}'s remaining tick time (one tick period at most).
     * A {@code Condition.await} approach was rejected because the caller
     * may itself be a region worker (CLAUDE.md rule 4: no blocking calls
     * on a region worker thread) and because we already hold the write lock
     * which would deadlock any wake path.
     */
    void mergeInto(Region target, Region other) {
        if (target == other) return;

        // Quiesce target: it must not be TICKING while merge listeners fold
        // dying region's state into surviving region's slot values. Bounded
        // spin — write lock holder is already serialised against structural
        // mutations, so the only wait is target finishing its in-flight tick.
        boolean folding = false;
        while (true) {
            if (target.tryMarkFolding()) {
                folding = true;
                break;
            }
            RegionState ts = target.state();
            if (ts == RegionState.DEAD) {
                // Target died out from under us (shouldn't happen under the write lock,
                // but defensive). Nothing to merge into.
                return;
            }
            if (ts == RegionState.TRANSIENT) {
                // Not yet published to the scheduler — no worker can tick it,
                // so no quiesce is needed. Proceed without a state transition;
                // markReady() at end of addChunk() will still see TRANSIENT.
                break;
            }
            // ts is TICKING (worker is mid-tick) or FOLDING (should be unreachable
            // under the write lock, but tolerate). Spin until it returns to READY.
            Thread.onSpinWait();
        }

        try {
            // Fire the pre-death listener notification BEFORE touching state so
            // listeners can fold side state while both regions are still queryable
            // (chunks.md:60-72). After fold, sections migrate and other dies.
            // Listeners are guaranteed target is non-TICKING per the quiesce above.
            fireRegionsMerging(target, other);
            Set<SectionPos> drained = other.drainSections();
            for (SectionPos s : drained) {
                target.addSection(s);
                mapPut(s, target);
            }
            other.markDead();
            fireRegionDied(other);
        } finally {
            if (folding) {
                target.markReadyFromFolding();
            }
        }
    }

    /**
     * Supply the region pins (see {@link RegionPin}) this regionizer honours,
     * then apply them to the current regions. Pins of other worlds are ignored.
     */
    public void setPins(Supplier<? extends Collection<RegionPin>> pins) {
        this.pins = Objects.requireNonNull(pins, "pins");
        refreshPins();
    }

    /**
     * Re-apply the pins after one was added or removed: merge the regions
     * holding sections of each pin, then split every region whose sections
     * are no longer connected (by adjacency or a shared pin).
     */
    public void refreshPins() {
        rwLock.writeLock().lock();
        try {
            for (RegionPin pin : worldPins()) mergePin(pin, null);
            for (Region region : regions()) {
                if (region.state() != RegionState.DEAD) splitIfDisconnected(region);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private List<RegionPin> worldPins() {
        Collection<RegionPin> all = pins.get();
        if (all.isEmpty()) return List.of();
        List<RegionPin> out = new ArrayList<>(all.size());
        for (RegionPin p : all) {
            if (p.world().dimensionId().equals(world.dimensionId())) out.add(p);
        }
        return out;
    }

    private List<RegionPin> pinsOverlapping(SectionPos section) {
        List<RegionPin> worldPins = worldPins();
        if (worldPins.isEmpty()) return List.of();
        int minX = section.x() << sectionChunkShift;
        int minZ = section.z() << sectionChunkShift;
        int maxX = minX + (1 << sectionChunkShift) - 1;
        int maxZ = minZ + (1 << sectionChunkShift) - 1;
        List<RegionPin> out = new ArrayList<>(1);
        for (RegionPin p : worldPins) {
            if (p.fromChunkX() <= maxX && p.toChunkX() >= minX && p.fromChunkZ() <= maxZ && p.toChunkZ() >= minZ) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * Invoke {@code visitor} for every section of {@code candidates} that
     * overlaps {@code pin} — walking the pin's section range or the
     * candidates, whichever is smaller.
     */
    private void forEachPinSection(RegionPin pin, Set<SectionPos> candidates, Consumer<SectionPos> visitor) {
        int fromX = pin.fromChunkX() >> sectionChunkShift;
        int toX = pin.toChunkX() >> sectionChunkShift;
        int fromZ = pin.fromChunkZ() >> sectionChunkShift;
        int toZ = pin.toChunkZ() >> sectionChunkShift;
        long span = (long) (toX - fromX + 1) * (toZ - fromZ + 1);
        if (span > candidates.size()) {
            for (SectionPos s : List.copyOf(candidates)) {
                if (s.x() >= fromX && s.x() <= toX && s.z() >= fromZ && s.z() <= toZ) visitor.accept(s);
            }
            return;
        }
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                SectionPos s = new SectionPos(x, z);
                if (candidates.contains(s)) visitor.accept(s);
            }
        }
    }

    /**
     * Merge every live region holding a section of {@code pin} into one —
     * into {@code into} when non-null, else into the largest. Caller holds
     * the write lock.
     */
    private void mergePin(RegionPin pin, Region into) {
        Set<Region> holders = new HashSet<>();
        forEachPinSection(pin, sectionToRegion.keySet(), s -> {
            Region r = sectionToRegion.get(s);
            if (r != null && r.state() != RegionState.DEAD) holders.add(r);
        });
        if (holders.isEmpty()) return;
        Region target = into != null && into.state() != RegionState.DEAD ? into : pickAnchor(holders);
        holders.remove(target);
        for (Region other : holders) mergeInto(target, other);
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
            mapPut(e.getKey(), e.getValue());
        }

        // Fire listeners AFTER all reassignments so any callback that reads
        // regionAtChunk observes final state, not partial state.
        for (Region fresh : freshRegions) {
            fireRegionSplit(region, fresh);
            fireRegionCreated(fresh);
        }
    }

    /**
     * Sections of {@code universe} reachable from {@code start}, where two
     * sections are connected when they touch (8-neighbourhood) or overlap
     * the same pin.
     */
    private Set<SectionPos> floodFill(Set<SectionPos> universe, SectionPos start) {
        Set<SectionPos> reached = new HashSet<>();
        ArrayDeque<SectionPos> stack = new ArrayDeque<>();
        stack.push(start);
        reached.add(start);
        Set<RegionPin> pinsVisited = new HashSet<>();
        while (!stack.isEmpty()) {
            SectionPos p = stack.pop();
            for (int[] delta : NEIGHBOURS) {
                SectionPos n = new SectionPos(p.x() + delta[0], p.z() + delta[1]);
                if (universe.contains(n) && reached.add(n)) stack.push(n);
            }
            for (RegionPin pin : pinsOverlapping(p)) {
                if (!pinsVisited.add(pin)) continue;
                forEachPinSection(pin, universe, n -> {
                    if (reached.add(n)) stack.push(n);
                });
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
