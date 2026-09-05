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
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionId;
import net.multiforge.runtime.region.RegionListener;
import net.multiforge.runtime.region.SectionPos;
import net.multiforge.runtime.region.ThreadedRegionizer;

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
 *
 * <p><b>Round-5 H4 fix.</b> When the optional {@link ThreadedRegionizer}
 * accessor is wired at construction, {@link #addTicket} and
 * {@link #removeTicket} acquire the regionizer's read lock across the
 * resolve→write pair — same shape as the Phase 1.2 fix in
 * {@link net.multiforge.runtime.region.RegionizedTaskQueue#queueChunkTask}.
 * A concurrent merge cannot fold the target region out from under a
 * pending ticket write, and a caller's stale {@link RegionId} hint is
 * re-resolved to the current owner of the position (so a ticket keyed
 * on a just-merged-away region routes to the survivor instead of
 * landing in a dead map). Legacy tests that pass no accessor keep the
 * prior lock-free shape.
 */
public final class ChunkHolderManager implements RegionListener {

    private final WorldRef world;
    private final ConcurrentMap<ChunkPos, NewChunkHolder> byChunk = new ConcurrentHashMap<>();
    private final ConcurrentMap<RegionId, HolderManagerRegionData> perRegion = new ConcurrentHashMap<>();
    private final ConcurrentMap<RegionId, PerRegionTicketMap> ticketsByRegion = new ConcurrentHashMap<>();

    /**
     * Lazy accessor for this world's regionizer. Non-null in production
     * wiring (via {@link
     * net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost#chunkManagerFor});
     * {@code null} in legacy tests that construct a bare
     * {@link ChunkHolderManager} without a regionizer. The supplier is
     * queried on every {@link #addTicket}/{@link #removeTicket} call
     * rather than captured at construction so it can bridge the
     * chicken-and-egg between {@code regionizerFor} and
     * {@code chunkManagerFor} in the host (a manager is created inside
     * the regionizer's {@code computeIfAbsent}, so the regionizer is
     * not yet published when the manager's constructor runs).
     */
    private final Supplier<ThreadedRegionizer> regionizerAccess;

    /**
     * Legacy constructor for tests and callers that don't need the
     * round-5 H4 merge-race protection (typically single-threaded unit
     * tests with a stub or absent regionizer). The addTicket/removeTicket
     * path degrades to the prior lock-free shape.
     */
    public ChunkHolderManager(WorldRef world) {
        this(world, null);
    }

    /**
     * Production constructor. {@code regionizerAccess} is a lazy accessor
     * for this world's {@link ThreadedRegionizer}; supply {@code null}
     * (or a supplier returning {@code null}) to keep the legacy
     * lock-free shape. When non-null and the supplier returns a live
     * regionizer, {@link #addTicket}/{@link #removeTicket} acquire the
     * regionizer's {@linkplain ThreadedRegionizer#readLock read lock}
     * across the resolve→write pair (Phase 1.2 pattern, round-5 H4).
     */
    public ChunkHolderManager(WorldRef world, Supplier<ThreadedRegionizer> regionizerAccess) {
        this.world = Objects.requireNonNull(world, "world");
        this.regionizerAccess = regionizerAccess;
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
     * holder is created lazily if it does not already exist.
     *
     * <p><b>Round-5 H4 fix.</b> When a regionizer accessor was wired at
     * construction, the resolve→write pair (holder lookup, ticket write,
     * level promotion, {@link HolderManagerRegionData#enqueueFullLoadUpdate})
     * runs under the regionizer's read lock. This blocks concurrent
     * merges (which hold the write lock in {@link
     * ThreadedRegionizer#mergeInto}) so the target region cannot fold
     * out from under the write. Additionally, the caller's {@code owner}
     * argument is treated as a hint: under the lock we re-resolve the
     * current owner of {@code pos} via
     * {@link ThreadedRegionizer#regionAtChunk(int, int)} and use that
     * as the write target. A stale hint (region merged away between the
     * caller's resolve and this call) is transparently rerouted to the
     * surviving region — the ticket is never written into a dead
     * per-region map.
     *
     * <p>Legacy callers (no regionizer accessor) fall through to the
     * prior lock-free shape and trust the caller-supplied {@code owner}.
     */
    public boolean addTicket(RegionId owner, ChunkPos pos, Ticket ticket) {
        ThreadedRegionizer regionizer = regionizerAccess == null ? null : regionizerAccess.get();
        Lock readLock = regionizer == null ? null : regionizer.readLock();
        if (readLock != null) readLock.lock();
        try {
            RegionId actualOwner = resolveActualOwner(regionizer, pos, owner);
            NewChunkHolder holder = byChunk.computeIfAbsent(pos, p -> {
                NewChunkHolder h = new NewChunkHolder(world, p);
                h.setOwningRegion(actualOwner);
                return h;
            });
            PerRegionTicketMap tickets = ticketsFor(actualOwner);
            boolean added = tickets.addTicket(pos, ticket);
            if (!added) return false;
            ChunkLoadLevel prev = holder.level();
            ChunkLoadLevel now = tickets.effectiveLevel(pos);
            if (!now.equals(prev)) {
                holder.setLevel(now);
                regionData(actualOwner).enqueueFullLoadUpdate(holder);
            }
            return true;
        } finally {
            if (readLock != null) readLock.unlock();
        }
    }

    /**
     * Remove a ticket, demoting the holder's level if the effective
     * distance rises above the current level's threshold. Removing
     * the last ticket transitions the holder to INACCESSIBLE.
     *
     * <p><b>Round-5 H4 fix.</b> Symmetric to {@link #addTicket}: the
     * resolve→write pair runs under the regionizer read lock when a
     * regionizer accessor was wired, and the caller's {@code owner}
     * is re-resolved to the current owner of {@code pos} so a stale
     * hint reroutes to the surviving region instead of missing the
     * merged-away region's ticket map entirely.
     */
    public boolean removeTicket(RegionId owner, ChunkPos pos, Ticket ticket) {
        ThreadedRegionizer regionizer = regionizerAccess == null ? null : regionizerAccess.get();
        Lock readLock = regionizer == null ? null : regionizer.readLock();
        if (readLock != null) readLock.lock();
        try {
            RegionId actualOwner = resolveActualOwner(regionizer, pos, owner);
            PerRegionTicketMap tickets = ticketsByRegion.get(actualOwner);
            if (tickets == null) return false;
            boolean removed = tickets.removeTicket(pos, ticket);
            if (!removed) return false;
            NewChunkHolder holder = byChunk.get(pos);
            if (holder == null) return true;
            ChunkLoadLevel now = tickets.effectiveLevel(pos);
            if (!now.equals(holder.level())) {
                holder.setLevel(now);
                regionData(actualOwner).enqueueFullLoadUpdate(holder);
            }
            return true;
        } finally {
            if (readLock != null) readLock.unlock();
        }
    }

    /**
     * Round-5 H4 helper. Under the regionizer read lock (held by the
     * caller), re-resolve the current owner of {@code pos} to defeat a
     * stale {@link RegionId} hint from a caller that resolved before a
     * merge fired. Falls back to the caller's {@code hint} when either
     * (a) no regionizer accessor is wired (legacy tests), or (b) the
     * chunk is currently unloaded from the regionizer's POV (defensive:
     * addTicket races vs. removeChunk are already covered by the
     * regionizer's own {@link RegionListener#onRegionDied} cleanup).
     */
    private static RegionId resolveActualOwner(ThreadedRegionizer regionizer, ChunkPos pos, RegionId hint) {
        if (regionizer == null) return hint;
        Region current = regionizer.regionAtChunk(pos.x(), pos.z());
        return current == null ? hint : current.id();
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
     * Diagnostic accessor — count of holders currently at
     * {@link ChunkLoadLevel#BORDER} or a more-loaded level (TICKING /
     * ENTITY_TICKING). Used by Phase 5 integration coverage and the
     * {@code /multiforge chunks} operator surface to answer "how many
     * chunks are keep-loaded right now?" without a full holder walk on
     * the caller side. O(N) over the byChunk map, single pass, no
     * allocation beyond the returned int; safe from any thread — the
     * volatile level read on each holder observes a level published by
     * the owning region worker's most recent {@link #addTicket} /
     * {@link #removeTicket}.
     */
    public int getBorderHolderCount() {
        int count = 0;
        for (NewChunkHolder h : byChunk.values()) {
            if (h.level().isAtLeast(ChunkLoadLevel.BORDER)) count++;
        }
        return count;
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
