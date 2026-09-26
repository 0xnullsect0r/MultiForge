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
    private final ConcurrentMap<RegionId, Set<ChunkPos>> byRegion = new ConcurrentHashMap<>();

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
            Set<ChunkPos> prevSet = byRegion.get(prev);
            if (prevSet != null) prevSet.remove(holder.position());
        }
        byRegion.computeIfAbsent(owner, r -> ConcurrentHashMap.newKeySet()).add(holder.position());
    }

    /** Forget the chunk at {@code pos} (it unloaded). */
    public NewChunkHolder dropHolder(ChunkPos pos) {
        NewChunkHolder h = byChunk.remove(pos);
        if (h != null && h.owningRegion() != null) {
            Set<ChunkPos> set = byRegion.get(h.owningRegion());
            if (set != null) set.remove(pos);
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

    /** The holders owned by {@code region}, from the owner index. */
    public List<NewChunkHolder> holdersOwnedBy(RegionId region) {
        Set<ChunkPos> chunks = byRegion.get(region);
        if (chunks == null) return List.of();
        List<NewChunkHolder> out = new ArrayList<>(chunks.size());
        for (ChunkPos pos : chunks) {
            NewChunkHolder h = byChunk.get(pos);
            if (h != null && region.equals(h.owningRegion())) out.add(h);
        }
        return List.copyOf(out);
    }

    /** Merge {@code source}'s chunks and data into {@code target}. */
    public void onRegionMerged(RegionId target, RegionId source) {
        HolderManagerRegionData sourceData = perRegion.remove(source);
        if (sourceData != null) regionData(target).merge(sourceData);
        Set<ChunkPos> moved = byRegion.remove(source);
        if (moved != null) {
            for (ChunkPos pos : moved) {
                NewChunkHolder h = byChunk.get(pos);
                if (h != null && source.equals(h.owningRegion())) assignOwner(h, target);
            }
        }
    }

    /** Move the chunks of {@code source} matching {@code shouldLeave}, and their data, to {@code target}. */
    public void onRegionSplit(RegionId source, RegionId target, Predicate<ChunkPos> shouldLeave) {
        HolderManagerRegionData src = perRegion.get(source);
        if (src != null) regionData(target).merge(src.split(shouldLeave));
        Set<ChunkPos> sourceChunks = byRegion.get(source);
        if (sourceChunks != null) {
            for (ChunkPos pos : List.copyOf(sourceChunks)) {
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
