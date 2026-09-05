/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.chunk;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionId;
import net.multiforge.runtime.region.RegionListener;
import net.multiforge.runtime.region.SectionPos;

/**
 * Per-world map of {@code (chunk pos) → NewChunkHolder} plus per-region
 * {@link HolderManagerRegionData}. Handles ticket promotion and
 * demotion, and produces the merge/split callbacks that
 * {@link net.multiforge.runtime.region.ThreadedRegionizer} invokes on
 * region topology changes.
 *
 * <p>Ticket writes go through here; they update the holder's
 * effective {@link ChunkLoadLevel} and, when the level crosses a
 * threshold, enqueue a full-load-update on the owning region's data.
 *
 * <p>Also implements {@link RegionListener} so the regionizer's own
 * fire path drives merge/split/death cleanup automatically — matching
 * the pattern already used by {@link
 * net.multiforge.runtime.region.RegionizedTaskQueue} and
 * {@link net.multiforge.runtime.region.RegionizedData}. Prior to /67
 * round-4 the two {@code onRegionMerged}/{@code onRegionSplit} methods
 * below existed but were never invoked (signature mismatch against
 * {@code RegionListener}) — per-region tickets, region data, and
 * holder ownership silently leaked across every merge/split.
 */
public final class ChunkHolderManager implements RegionListener {

    private final WorldRef world;
    private final ConcurrentMap<ChunkPos, NewChunkHolder> byChunk = new ConcurrentHashMap<>();
    private final ConcurrentMap<RegionId, HolderManagerRegionData> perRegion = new ConcurrentHashMap<>();
    private final ConcurrentMap<RegionId, PerRegionTicketMap> ticketsByRegion = new ConcurrentHashMap<>();

    public ChunkHolderManager(WorldRef world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    public WorldRef world() {
        return world;
    }

    public NewChunkHolder holderAt(ChunkPos pos) {
        return byChunk.get(pos);
    }

    public NewChunkHolder createHolder(ChunkPos pos, RegionId owner) {
        return byChunk.computeIfAbsent(pos, p -> {
            NewChunkHolder h = new NewChunkHolder(world, p);
            h.setOwningRegion(owner);
            return h;
        });
    }

    public HolderManagerRegionData regionData(RegionId region) {
        return perRegion.computeIfAbsent(region, id -> new HolderManagerRegionData());
    }

    public PerRegionTicketMap ticketsFor(RegionId region) {
        return ticketsByRegion.computeIfAbsent(region, id -> new PerRegionTicketMap());
    }

    /**
     * Add a ticket, promoting the holder's level if the ticket lowers
     * the effective distance below the current level's threshold. The
     * holder must already exist ({@link #createHolder(ChunkPos, RegionId)}).
     */
    public boolean addTicket(RegionId owner, ChunkPos pos, Ticket ticket) {
        NewChunkHolder holder = byChunk.computeIfAbsent(pos, p -> {
            NewChunkHolder h = new NewChunkHolder(world, p);
            h.setOwningRegion(owner);
            return h;
        });
        PerRegionTicketMap tickets = ticketsFor(owner);
        boolean added = tickets.addTicket(pos, ticket);
        if (!added) return false;
        ChunkLoadLevel prev = holder.level();
        ChunkLoadLevel now = tickets.effectiveLevel(pos);
        if (!now.equals(prev)) {
            holder.setLevel(now);
            regionData(owner).enqueueFullLoadUpdate(holder);
        }
        return true;
    }

    /**
     * Remove a ticket, demoting the holder's level if the effective
     * distance rises above the current level's threshold. Removing
     * the last ticket transitions the holder to INACCESSIBLE.
     */
    public boolean removeTicket(RegionId owner, ChunkPos pos, Ticket ticket) {
        PerRegionTicketMap tickets = ticketsByRegion.get(owner);
        if (tickets == null) return false;
        boolean removed = tickets.removeTicket(pos, ticket);
        if (!removed) return false;
        NewChunkHolder holder = byChunk.get(pos);
        if (holder == null) return true;
        ChunkLoadLevel now = tickets.effectiveLevel(pos);
        if (!now.equals(holder.level())) {
            holder.setLevel(now);
            regionData(owner).enqueueFullLoadUpdate(holder);
        }
        return true;
    }

    /** Marks {@code pos} dirty and puts it on the owning region's autosave queue. */
    public void markDirty(RegionId owner, ChunkPos pos) {
        NewChunkHolder holder = byChunk.get(pos);
        if (holder == null) return;
        holder.markDirty();
        regionData(owner).enqueueAutoSave(holder);
    }

    /**
     * Callback for region merges — fold {@code source}'s per-region
     * data + ticket map into {@code target}'s.
     */
    public void onRegionMerged(RegionId target, RegionId source) {
        HolderManagerRegionData sourceData = perRegion.remove(source);
        if (sourceData != null) regionData(target).merge(sourceData);
        PerRegionTicketMap sourceTickets = ticketsByRegion.remove(source);
        if (sourceTickets != null) ticketsFor(target).merge(sourceTickets);
        for (NewChunkHolder h : byChunk.values()) {
            if (source.equals(h.owningRegion())) h.setOwningRegion(target);
        }
    }

    /**
     * Callback for region splits — peel off holders whose position
     * satisfies {@code shouldLeave} into a new region {@code target}.
     */
    public void onRegionSplit(RegionId source, RegionId target, java.util.function.Predicate<ChunkPos> shouldLeave) {
        HolderManagerRegionData src = perRegion.get(source);
        if (src != null) regionData(target).merge(src.split(h -> shouldLeave.test(h.position())));
        PerRegionTicketMap srcTickets = ticketsByRegion.get(source);
        if (srcTickets != null) ticketsFor(target).merge(srcTickets.split(shouldLeave));
        for (NewChunkHolder h : byChunk.values()) {
            if (source.equals(h.owningRegion()) && shouldLeave.test(h.position())) h.setOwningRegion(target);
        }
    }

    public Collection<NewChunkHolder> holders() {
        return List.copyOf(byChunk.values());
    }

    public int holderCount() {
        return byChunk.size();
    }

    /**
     * Remove and return the holder at {@code pos}. Used by the M9 shadow
     * bridge on chunk unload / INACCESSIBLE ticket transitions to keep
     * {@link #byChunk} from growing unbounded over a server's lifetime.
     */
    public NewChunkHolder dropHolder(ChunkPos pos) {
        return byChunk.remove(pos);
    }

    // === RegionListener ===

    /**
     * Regionizer fired a merge: fold {@code dying}'s side state into
     * {@code surviving}. Delegates to the RegionId-typed {@link
     * #onRegionMerged(RegionId, RegionId)} which handles the actual
     * data movement.
     */
    @Override
    public void onRegionsMerging(Region surviving, Region dying) {
        onRegionMerged(surviving.id(), dying.id());
    }

    /**
     * Regionizer fired a split: hand child region {@code child} its
     * share of holders + region data + tickets that were previously
     * under {@code source}. Determines membership by testing whether
     * each holder's chunk position falls in one of {@code child}'s
     * sections.
     */
    @Override
    public void onRegionSplit(Region source, Region child) {
        int shift = child.sectionChunkShift();
        Set<SectionPos> childSections = child.sections();
        onRegionSplit(
                source.id(), child.id(), pos -> childSections.contains(SectionPos.ofChunk(pos.x(), pos.z(), shift)));
    }

    /**
     * Regionizer fired region death — clean up per-region maps that
     * would otherwise leak. In the merge case {@link #onRegionsMerging}
     * has already moved the data; this handles the natural-death case
     * (last chunk removed) where no merge happened. Holders keyed by
     * chunk position are cleaned up separately by the bridge on
     * {@code ChunkEvent.Unload} / INACCESSIBLE transitions.
     */
    @Override
    public void onRegionDied(Region region) {
        perRegion.remove(region.id());
        ticketsByRegion.remove(region.id());
    }
}
