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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** Default deadline for {@link #scheduleWhenHolderAt(ChunkPos, ChunkLoadLevel, Runnable)}. */
    public static final long DEFAULT_SCHEDULE_DEADLINE_MS = 500L;

    /** Poll cadence used while a holder has not yet been created at all (no future to hook). */
    private static final long SCHEDULE_POLL_INTERVAL_MS = 5L;

    private static final AtomicInteger SCHEDULER_SEQ = new AtomicInteger();

    /**
     * Shared single-thread scheduler backing {@link #scheduleWhenHolderAt}. Static (not
     * per-instance) since {@link ChunkHolderManager} is created per world — a per-instance
     * executor would leak one daemon thread per world materialised over a server's lifetime.
     * Never runs caller work inline on a region worker thread and never blocks (CLAUDE.md rule
     * 4) — it only polls a volatile field / attaches a {@code thenRun} continuation and hands the
     * result back via the caller-supplied {@code Runnable}, which production callers (see {@code
     * EntityMigrationCoordinator.completeAt}) re-enter through {@code RegionizedTaskQueue} rather
     * than running directly on this thread.
     */
    private static final ScheduledExecutorService SCHEDULE_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mf-chunkholder-scheduler-" + SCHEDULER_SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

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

    /**
     * Run {@code task} once the holder at {@code pos} reaches at least {@code minLevel},
     * deferring rather than blocking when it hasn't yet — see {@code
     * docs/design/entity-migration.md} §3.2. Uses {@link #DEFAULT_SCHEDULE_DEADLINE_MS} and drops
     * the task silently on timeout; prefer the overload with an explicit {@code onTimeout}
     * fallback for any caller that needs to observe a timeout (entity migration's
     * {@code abortAndRestore} path does).
     */
    public void scheduleWhenHolderAt(ChunkPos pos, ChunkLoadLevel minLevel, Runnable task) {
        scheduleWhenHolderAt(pos, minLevel, task, DEFAULT_SCHEDULE_DEADLINE_MS, null);
    }

    /**
     * Run {@code task} once the holder at {@code pos} reaches at least {@code minLevel}, or invoke
     * {@code onTimeout} (if non-null) if it has not by {@code deadlineMs} from now.
     *
     * <p><b>Never blocks, never spins the calling thread</b> (CLAUDE.md rule 4). If the holder
     * already satisfies {@code minLevel}, {@code task} runs inline on the calling thread
     * immediately. Otherwise this arms a bounded, non-blocking poll on the shared {@link
     * #SCHEDULE_EXECUTOR}, re-checking every {@link #SCHEDULE_POLL_INTERVAL_MS} until either the
     * level is reached or {@code deadlineMs} elapses. {@code task} or {@code onTimeout} ultimately
     * runs on {@link #SCHEDULE_EXECUTOR}'s single thread, never inline on a region worker — callers
     * that need the work to run on a specific region's own thread must re-enter through {@code
     * RegionizedTaskQueue.queueChunkTask} from inside {@code task} (see {@code
     * EntityMigrationCoordinator.completeAt}, which does exactly this).
     *
     * <p><b>Why polling, not the promotion future.</b> {@link NewChunkHolder#getFullChunkFuture()}
     * only completes once fork glue outside this Minecraft-free module calls {@link
     * NewChunkHolder#setFullChunkFuture} — plain ticket promotion via {@link #addTicket} does not
     * touch it. Relying on that future here would silently starve {@code task} in exactly this
     * module's own tests (and in any host that has not wired the fork glue yet), so this method
     * intentionally polls the holder's {@link NewChunkHolder#level()} directly instead — the
     * primitive that {@link #addTicket}/{@link #removeTicket} do keep authoritative.
     *
     * <p>A transient promotion that demotes again before {@code task} actually runs is a real
     * possibility under adversarial load/unload churn; each poll re-checks the live level at fire
     * time, so a demotion observed on a later poll simply keeps polling rather than firing early.
     */
    public void scheduleWhenHolderAt(
            ChunkPos pos, ChunkLoadLevel minLevel, Runnable task, long deadlineMs, Runnable onTimeout) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(minLevel, "minLevel");
        Objects.requireNonNull(task, "task");
        NewChunkHolder holder = byChunk.get(pos);
        if (holder != null && holder.level().isAtLeast(minLevel)) {
            task.run();
            return;
        }
        long deadlineAt = System.currentTimeMillis() + Math.max(0L, deadlineMs);
        pollHolderAt(pos, minLevel, task, deadlineAt, onTimeout);
    }

    private void pollHolderAt(
            ChunkPos pos, ChunkLoadLevel minLevel, Runnable task, long deadlineAtMillis, Runnable onTimeout) {
        NewChunkHolder holder = byChunk.get(pos);
        if (holder != null && holder.level().isAtLeast(minLevel)) {
            task.run();
            return;
        }
        if (System.currentTimeMillis() >= deadlineAtMillis) {
            if (onTimeout != null) onTimeout.run();
            return;
        }
        SCHEDULE_EXECUTOR.schedule(
                () -> pollHolderAt(pos, minLevel, task, deadlineAtMillis, onTimeout),
                SCHEDULE_POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
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
        if (src != null) regionData(target).merge(src.split(h -> shouldLeave.test(h.position()), shouldLeave));
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
     * Snapshot of every holder currently owned by {@code region} — the
     * primitive B3.1's three per-region phase bodies (BLOCK_FLUID_TICKS
     * / ENTITY_AI / BLOCK_ENTITIES, docs/design/m13-b3-region-tick.md
     * §4.1) use to answer "which chunks does this region own, right
     * now?" via {@link net.multiforge.runtime.region.Region#ownedChunkSnapshot()}.
     *
     * <p><b>Deliberately a linear scan, not a cached/indexed
     * structure.</b> {@link #byChunk} is a {@code ConcurrentHashMap}
     * written from arbitrary region-worker threads (chunk load/unload,
     * {@link #onRegionMerged}, {@link #onRegionSplit}); maintaining a
     * second region-indexed map in lockstep with every one of those
     * write paths is real complexity for a call that fires roughly
     * once per phase per region per tick (~4 calls per region per
     * tick, per the B3.1 task note) — not once per chunk, and not on
     * any tick-hot inner loop. A full scan is O(chunks in the world);
     * for a busy world that is a few thousand entries, comparable cost
     * to the existing {@link #getBorderHolderCount()} call, which
     * already runs at a similar cadence. If a future benchmark shows
     * this dominating tick time, per-region caching (invalidated on
     * split/merge) becomes an option, but it is out of scope here —
     * see docs/design/m13-b3-region-tick.md §4.1.
     *
     * <p>Thread-safety matches every other read method on this class:
     * {@link #byChunk} is a {@code ConcurrentHashMap}, so the scan
     * tolerates concurrent structural writes (a chunk created or
     * reassigned mid-scan is either included or not, never corrupting
     * the returned list), and each holder's {@link
     * NewChunkHolder#owningRegion()} read is a volatile-backed atomic
     * read. The returned {@link List} is an immutable snapshot — safe
     * to iterate without any further synchronisation, and stable even
     * if a concurrent split/merge reassigns ownership after this call
     * returns.
     */
    public List<NewChunkHolder> holdersOwnedBy(RegionId region) {
        List<NewChunkHolder> out = new ArrayList<>();
        for (NewChunkHolder h : byChunk.values()) {
            if (region.equals(h.owningRegion())) out.add(h);
        }
        return List.copyOf(out);
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
