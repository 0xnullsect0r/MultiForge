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
package net.multiforge.runtime.chunk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionId;
import net.multiforge.runtime.region.RegionListener;
import net.multiforge.runtime.region.SectionPos;

/**
 * One world's index of loaded chunks by owning region, plus each region's
 * {@link HolderManagerRegionData}. Registered as a {@link RegionListener} on
 * the world's regionizer, so ownership and per-region data follow every
 * merge and split.
 *
 * <p>Chunk loading, load levels, tickets and saving stay Vanilla's, on the
 * server thread; this index only answers "which chunks does region R own"
 * ({@link Region#ownedChunkSnapshot()}) and holds the region-local data the
 * region tick phases need.
 */
public final class ChunkHolderManager implements RegionListener {

    private final WorldRef world;
    private final ConcurrentMap<ChunkPos, NewChunkHolder> byChunk = new ConcurrentHashMap<>();
    private final ConcurrentMap<RegionId, HolderManagerRegionData> perRegion = new ConcurrentHashMap<>();
    // Owner index: region -> the chunks it owns. Kept in step with each
    // holder's owningRegion so ownership queries never scan every holder.
    private final ConcurrentMap<RegionId, Owned> byRegion = new ConcurrentHashMap<>();

    /**
     * One region's owned chunks, plus the lists {@link #holdersOwnedBy} and
     * {@link #ownedPositions} last built from them. {@code version} is bumped
     * after every change to the set or to a member holder's owner, so a cached
     * list is served only while nothing it was built from has changed.
     */
    private static final class Owned {
        final Set<ChunkPos> chunks = ConcurrentHashMap.newKeySet();
        final java.util.concurrent.atomic.AtomicLong version = new java.util.concurrent.atomic.AtomicLong();
        volatile Snapshot snapshot;
    }

    private record Snapshot(long version, List<NewChunkHolder> holders, List<ChunkPos> positions) {}

    public ChunkHolderManager(WorldRef world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    public WorldRef world() {
        return world;
    }

    /** @return the holder of the loaded chunk at {@code pos}, or {@code null}. */
    public NewChunkHolder holderAt(ChunkPos pos) {
        return byChunk.get(pos);
    }

    /**
     * Record that the chunk at {@code pos} is loaded and owned by {@code
     * owner}. An existing holder is re-owned (a chunk that loaded again, or
     * whose region changed).
     */
    public NewChunkHolder createHolder(ChunkPos pos, RegionId owner) {
        Objects.requireNonNull(owner, "owner");
        NewChunkHolder h = byChunk.computeIfAbsent(pos, p -> new NewChunkHolder(world, p));
        assignOwner(h, owner);
        return h;
    }

    private void assignOwner(NewChunkHolder holder, RegionId owner) {
        RegionId prev = holder.owningRegion();
        if (!holder.setOwningRegion(owner)) return;
        if (prev != null) {
            Owned prevOwned = byRegion.get(prev);
            if (prevOwned != null) {
                prevOwned.chunks.remove(holder.position());
                prevOwned.version.incrementAndGet();
            }
        }
        Owned owned = byRegion.computeIfAbsent(owner, r -> new Owned());
        owned.chunks.add(holder.position());
        owned.version.incrementAndGet();
    }

    /** Forget the chunk at {@code pos} (it unloaded). */
    public NewChunkHolder dropHolder(ChunkPos pos) {
        NewChunkHolder h = byChunk.remove(pos);
        if (h != null && h.owningRegion() != null) {
            Owned owned = byRegion.get(h.owningRegion());
            if (owned != null) {
                owned.chunks.remove(pos);
                owned.version.incrementAndGet();
            }
        }
        return h;
    }

    public HolderManagerRegionData regionData(RegionId region) {
        return perRegion.computeIfAbsent(region, id -> new HolderManagerRegionData());
    }

    public Collection<NewChunkHolder> holders() {
        return List.copyOf(byChunk.values());
    }

    public int holderCount() {
        return byChunk.size();
    }

    /**
     * The holders owned by {@code region}, from the owner index (immutable). The
     * same list is returned until the region's chunks change.
     */
    public List<NewChunkHolder> holdersOwnedBy(RegionId region) {
        Snapshot s = snapshotOf(region);
        return s == null ? List.of() : s.holders();
    }

    /** The positions of {@link #holdersOwnedBy}, in the same order (immutable, cached the same way). */
    public List<ChunkPos> ownedPositions(RegionId region) {
        Snapshot s = snapshotOf(region);
        return s == null ? List.of() : s.positions();
    }

    private Snapshot snapshotOf(RegionId region) {
        Owned owned = byRegion.get(region);
        if (owned == null) return null;
        long version = owned.version.get();
        Snapshot cached = owned.snapshot;
        if (cached != null && cached.version() == version) return cached;
        List<NewChunkHolder> holders = new ArrayList<>(owned.chunks.size());
        List<ChunkPos> positions = new ArrayList<>(owned.chunks.size());
        for (ChunkPos pos : owned.chunks) {
            NewChunkHolder h = byChunk.get(pos);
            if (h != null && region.equals(h.owningRegion())) {
                holders.add(h);
                positions.add(pos);
            }
        }
        // Tagged with the version read before the walk: a change during it bumps
        // the version after it, so this snapshot is never served past the change.
        Snapshot fresh = new Snapshot(version, List.copyOf(holders), List.copyOf(positions));
        owned.snapshot = fresh;
        return fresh;
    }

    /** Merge {@code source}'s chunks and data into {@code target}. */
    public void onRegionMerged(RegionId target, RegionId source) {
        HolderManagerRegionData sourceData = perRegion.remove(source);
        if (sourceData != null) regionData(target).merge(sourceData);
        Owned moved = byRegion.remove(source);
        if (moved != null) {
            for (ChunkPos pos : moved.chunks) {
                NewChunkHolder h = byChunk.get(pos);
                if (h != null && source.equals(h.owningRegion())) assignOwner(h, target);
            }
        }
    }

    /** Move the chunks of {@code source} matching {@code shouldLeave}, and their data, to {@code target}. */
    public void onRegionSplit(RegionId source, RegionId target, Predicate<ChunkPos> shouldLeave) {
        HolderManagerRegionData src = perRegion.get(source);
        if (src != null) regionData(target).merge(src.split(shouldLeave));
        Owned sourceOwned = byRegion.get(source);
        if (sourceOwned != null) {
            for (ChunkPos pos : List.copyOf(sourceOwned.chunks)) {
                if (!shouldLeave.test(pos)) continue;
                NewChunkHolder h = byChunk.get(pos);
                if (h != null && source.equals(h.owningRegion())) assignOwner(h, target);
            }
        }
    }

    @Override
    public void onRegionsMerging(Region surviving, Region dying) {
        onRegionMerged(surviving.id(), dying.id());
    }

    @Override
    public void onRegionSplit(Region source, Region child) {
        int shift = child.sectionChunkShift();
        Set<SectionPos> childSections = child.sections();
        onRegionSplit(
                source.id(), child.id(), pos -> childSections.contains(SectionPos.ofChunk(pos.x(), pos.z(), shift)));
    }

    @Override
    public void onRegionDied(Region region) {
        perRegion.remove(region.id());
        byRegion.remove(region.id());
    }
}
