/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.scheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import net.multiforge.api.entity.EntityRef;
import net.multiforge.api.mod.ModIdentifier;
import net.multiforge.api.scheduler.AsyncDomain;
import net.multiforge.api.scheduler.EntityDomain;
import net.multiforge.api.scheduler.GlobalDomain;
import net.multiforge.api.scheduler.RegionDomain;
import net.multiforge.api.scheduler.ScheduledTask;
import net.multiforge.api.scheduler.ServerDomains;
import net.multiforge.api.spi.SchedulerHost;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.ChunkTaskScheduler;
import net.multiforge.runtime.chunk.HolderManagerRegionData;
import net.multiforge.runtime.chunk.NewChunkHolder;
import net.multiforge.runtime.chunk.Ticket;
import net.multiforge.runtime.chunk.TicketType;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.journal.AutoSaveRunner;
import net.multiforge.runtime.journal.JournalReplayHarness;
import net.multiforge.runtime.journal.RegionJournal;
import net.multiforge.runtime.journal.RegionJournalLifecycle;
import net.multiforge.runtime.ownership.OwnerToken;
import net.multiforge.runtime.region.PhasedRegionTickBody;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionId;
import net.multiforge.runtime.region.RegionListener;
import net.multiforge.runtime.region.RegionTickBody;
import net.multiforge.runtime.region.RegionizedTaskQueue;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.region.TickRegionScheduler;
import net.multiforge.runtime.shutdown.RegionShutdownCoordinator;

/**
 * Parallel {@link SchedulerHost} that runs region/entity work on the
 * {@link TickRegionScheduler} worker pool. Global work runs on a
 * dedicated single-thread executor (the "global region" in Folia
 * terms). Async work runs on a shared pool.
 *
 * <p>Region/entity task <em>dispatch</em> is deferred: calling
 * {@code region.execute(mod, r)} enqueues {@code r} in the owner
 * region's inbox and the pool drains it on the next tick of that
 * region. This is Folia's guarantee: cross-thread work never runs on
 * the wrong region.
 */
public final class MultiThreadedSchedulerHost implements SchedulerHost, AutoCloseable {

    private static final long TICK_MS = 50L;
    private static final int GLOBAL_REGION_INBOX_BATCH = 4096;
    // Vanilla's default: autosave every 6000 ticks (5 minutes at 20 TPS).
    // Kept as a constant for parity — a config knob can be added later
    // when operators need to tune it.
    static final long DEFAULT_AUTOSAVE_INTERVAL_TICKS = 6000L;
    // Per-tick budget knobs for the Phase 5 wiring. Bounded so that
    // pollFullLoadUpdate / ChunkTaskScheduler.drainInto / AutoSaveRunner
    // can never block a region worker thread past its 50 ms tick target
    // (CLAUDE.md rule 4). Undrained work simply defers to the next tick.
    static final int PHASE_FULL_LOAD_MAX_PER_TICK = 4096;
    static final int PHASE_CHUNK_TASK_MAX_PER_TICK = 256;
    static final long PHASE_CHUNK_TASK_DEADLINE_NANOS = 2_000_000L; // 2ms
    static final int PHASE_AUTOSAVE_MAX_CHUNKS_PER_TICK = 24;
    static final long PHASE_AUTOSAVE_DEADLINE_NANOS = 3_000_000L; // 3ms

    private final MultiForgeConfig config;
    private final ConcurrentMap<String, ThreadedRegionizer> regionizers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ChunkHolderManager> chunkManagers = new ConcurrentHashMap<>();
    private final ChunkTaskScheduler chunkTaskScheduler;
    private final Function<WorldRef, ThreadedRegionizer> regionizerFactory;
    private final RegionizedTaskQueue taskQueue;
    private final TickRegionScheduler scheduler;

    // Region → world map maintained by an auto-wired RegionListener on each
    // regionizer. Populated on onRegionCreated / onRegionSplit; cleared on
    // onRegionDied. Read by the Phase 5 wiring to route a Region back to
    // its owning ChunkHolderManager without a linear scan over every world.
    private final ConcurrentMap<RegionId, WorldRef> regionToWorld = new ConcurrentHashMap<>();

    // Per-region last-autosave tick counter, keyed by region id. Read at
    // the top of the FLUSH_OUTBOUND phase to decide whether the autosave
    // budget has come due for this region (Vanilla parity: 6000 ticks).
    private final ConcurrentMap<RegionId, Long> lastAutosaveTick = new ConcurrentHashMap<>();

    // Per-region AutoSaveRunner cache — one runner per region, bound to
    // that region's HolderManagerRegionData + RegionJournal at first use.
    // Cleared when the region dies (via the same RegionListener that
    // untracks the journal).
    private final ConcurrentMap<RegionId, AutoSaveRunner> autoSaveRunners = new ConcurrentHashMap<>();

    // Optional Phase 5 wiring dependencies — set by installM9WiredTickBody.
    // Null when the M9 wire-in has not been applied, in which case the
    // tick body defaults to whatever was passed in at construction.
    private volatile RegionShutdownCoordinator shutdownCoordinator;
    private volatile RegionJournalLifecycle journalLifecycle;
    private volatile Path journalDir;

    // Phase 5 wave B wiring: pluggable chunk-payload serializer for
    // AutoSaveRunner. Defaults to the phase-6 stub (empty payload) so
    // tests and any pre-fork-wiring boot window keep working exactly as
    // before. The fork's ServerLifecycleHooks glue swaps this for
    // RegionChunkSerializer.serializeForJournal once the real,
    // MC-dependent serializer is available (see
    // net.multiforge.neoforge.io.RegionChunkSerializer in the
    // upstream/neoforge-1.21.1 fork module — multiforge-runtime itself
    // must stay MC-free).
    private volatile BiFunction<Region, NewChunkHolder, byte[]> chunkSerializer = (region, holder) -> new byte[0];

    private final ScheduledExecutorService delayedExec;
    private final ScheduledExecutorService asyncExec;
    private final ConcurrentMap<ModIdentifier, ConcurrentMap<SchedulerTaskImpl, Boolean>> asyncByMod =
            new ConcurrentHashMap<>();

    // The "global region" — a synthetic region pinned to the pool, ticking
    // like any other region. Owns global-only state (weather, time, etc.).
    private final Region globalRegion;
    private final ThreadedRegionizer globalRegionizer;

    public MultiThreadedSchedulerHost(MultiForgeConfig config) {
        this(config, region -> {}); // no-op tick body until M2 patch supplies the vanilla body
    }

    public MultiThreadedSchedulerHost(MultiForgeConfig config, RegionTickBody body) {
        this.config = Objects.requireNonNull(config, "config");
        this.regionizerFactory = world -> new ThreadedRegionizer(world, config.regionSize());
        this.taskQueue = new RegionizedTaskQueue(
                (w, x, z) -> {
                    // Use regionizerForOrNull here (not regionizerFor): OwnerLookup's
                    // contract is "return null for unloaded/unknown chunks", and
                    // auto-creating a regionizer on lookup lets a typoed WorldRef
                    // grow the map unboundedly (finding #11 of the /67 review).
                    ThreadedRegionizer r = regionizerForOrNull(w);
                    return r == null ? null : r.regionAtChunk(x, z);
                },
                // Phase 1 task 1.2: wire the per-world regionizer read lock
                // so RegionizedTaskQueue.queueChunkTask pins section→region
                // mapping across its resolve-then-enqueue. Same "null when
                // regionizer not materialised" contract as OwnerLookup —
                // legitimate lookups without a live regionizer degrade to
                // the orphan-queue path with no locking (no merge race can
                // fire before the regionizer exists).
                w -> {
                    ThreadedRegionizer r = regionizerForOrNull(w);
                    return r == null ? null : r.readLock();
                });
        this.scheduler = new TickRegionScheduler(config.tickWorkerCount(), body, taskQueue, 128);
        this.chunkTaskScheduler = new ChunkTaskScheduler(taskQueue, (w, x, z) -> {
            ThreadedRegionizer r = regionizerForOrNull(w);
            return r == null ? null : r.regionAtChunk(x, z);
        });

        // Global region is exposed under a synthetic world so it uses the
        // same inbox+tick plumbing as any other region. Publish it into
        // the regionizers map BEFORE calling addChunk so
        // `taskQueue.queueChunkTask(GLOBAL_WORLD, ...)` reaches the same
        // regionizer as `globalRegionizer` here.
        WorldRef globalWorld = WorldRef.of("multiforge:global");
        this.globalRegionizer = new ThreadedRegionizer(globalWorld, 0);
        // Wire the scheduler+taskQueue as listeners BEFORE publishing to
        // regionizers so any future addChunk/removeChunk on the global
        // regionizer cascades cleanup exactly like every other world's
        // regionizer. Direct `regionizers.put` here bypasses regionizerFor
        // (which would double-register from computeIfAbsent), so listener
        // wiring must be done explicitly.
        this.globalRegionizer.addListener(this.scheduler);
        this.globalRegionizer.addListener(this.taskQueue);
        // Phase 5.1/5.3 wiring: track region → world so the M9-wired
        // tick body can route a Region back to its owning
        // ChunkHolderManager. Registered on the global regionizer
        // eagerly, and on every per-world regionizer via regionizerFor
        // below.
        this.globalRegionizer.addListener(newRegionWorldTracker(globalWorld));
        regionizers.put(globalWorld.dimensionId(), globalRegionizer);
        this.globalRegion = globalRegionizer.addChunk(new ChunkPos(0, 0));
        // scheduler.register(globalRegion) is redundant now (onRegionCreated
        // handles it via the listener) — keeping the explicit call for
        // symmetry with the previous shape / test readability.
        scheduler.register(globalRegion);

        AtomicInteger dSeq = new AtomicInteger();
        this.delayedExec = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "multiforge-delayed-" + dSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        AtomicInteger aSeq = new AtomicInteger();
        this.asyncExec = Executors.newScheduledThreadPool(Math.max(2, config.cores() / 2), r -> {
            Thread t = new Thread(r, "multiforge-async-" + aSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Install as the {@link ServerDomains} binding. */
    public void install() {
        ServerDomains.install(this);
    }

    /**
     * Replace the chunk-payload serializer every {@link
     * AutoSaveRunner} (existing and future) delegates to via {@link
     * #serializeChunkSafely}. Every cached runner in {@link
     * #autoSaveRunners} was built with {@code this::serializeChunkSafely}
     * as its serializer, which re-reads this volatile field on every
     * call — so swapping it here takes effect immediately for every
     * region, not just ones whose runner is created afterwards.
     *
     * <p>Not part of {@link SchedulerHost}: this is a MultiForge-only
     * escape hatch for MC-dependent glue that cannot live in this
     * MC-free module. See {@code
     * net.multiforge.neoforge.io.RegionChunkSerializer#serializeForJournal}
     * in the fork module for the production implementation; the
     * default ({@code (region, holder) -> new byte[0]}) is a
     * deliberate no-op so tests that never call this method keep
     * working unchanged.
     *
     * @param serializer must never block (CLAUDE.md rule 4). Exceptions
     *     are swallowed by {@link #serializeChunkSafely} — a serializer
     *     bug never reaches {@link AutoSaveRunner#runOnce} or the tick
     *     pipeline, it just degrades that one chunk's payload to
     *     {@code byte[0]} for that call.
     */
    public void setChunkSerializer(BiFunction<Region, NewChunkHolder, byte[]> serializer) {
        this.chunkSerializer = Objects.requireNonNull(serializer, "serializer");
    }

    /**
     * Exception-safe indirection to the current {@link
     * #chunkSerializer}. Every {@link AutoSaveRunner} is built with a
     * method reference to this method (not to {@link #chunkSerializer}
     * directly) so that (a) {@link #setChunkSerializer} takes effect
     * for already-cached runners too, and (b) a misbehaving serializer
     * (the fork's {@code RegionChunkSerializer.serializeForJournal},
     * or a test's spy/throwing lambda) can never propagate an
     * exception into the FLUSH_OUTBOUND phase — CLAUDE.md rule 5's
     * auto-reroute+warn default applies here just as much as it does
     * to mod call sites.
     */
    private byte[] serializeChunkSafely(Region region, NewChunkHolder holder) {
        try {
            byte[] payload = chunkSerializer.apply(region, holder);
            return payload != null ? payload : new byte[0];
        } catch (RuntimeException e) {
            ProbeRegistry.bump("autosave.serializer.failure");
            ViolationLogger.warn(
                    "MultiThreadedSchedulerHost.serializeChunkSafely",
                    "chunk serializer threw for region " + region.id() + ": "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
            return new byte[0];
        }
    }

    public RegionizedTaskQueue taskQueue() {
        return taskQueue;
    }

    public TickRegionScheduler scheduler() {
        return scheduler;
    }

    public ThreadedRegionizer regionizerFor(WorldRef world) {
        return regionizers.computeIfAbsent(world.dimensionId(), id -> {
            ThreadedRegionizer r = regionizerFactory.apply(world);
            // M8: auto-wire the shared scheduler and task queue as
            // RegionListeners on every world's regionizer so region death
            // and merge/split automatically deregister and move pending
            // inboxes — no manual bookkeeping in the M8 patches.
            r.addListener(scheduler);
            r.addListener(taskQueue);
            // /67 round-4 fix: also wire the per-world ChunkHolderManager
            // and the shared ChunkTaskScheduler so ticket state, holder
            // ownership, and chunk-priority deques auto-migrate on merge
            // and are cleaned up on death. Previously these two listeners
            // were silently dropped (signature mismatch); every merge
            // leaked per-region state and left holders pointing at dead
            // RegionIds.
            r.addListener(chunkManagerFor(world));
            r.addListener(chunkTaskScheduler);
            // Phase 5.1/5.3 wiring: region → world map so the M9-wired
            // tick body can route Region → ChunkHolderManager without
            // linear-scanning every world's manager.
            r.addListener(newRegionWorldTracker(world));
            // Phase 5.4/5.5 wiring: when installM9WiredTickBody has run,
            // every subsequently-created world's regionizer also gets
            // the journal lifecycle listener. Worlds materialised
            // before that call miss out — production wiring installs the
            // M9 body early enough (server-lifecycle-hook) that this is
            // benign, and tests can invoke wireJournalLifecycleFor() by
            // hand if they materialise worlds before the lifecycle.
            RegionJournalLifecycle lifecycle = this.journalLifecycle;
            if (lifecycle != null) {
                r.addListener(lifecycle);
            }
            return r;
        });
    }

    /**
     * Build a {@link RegionListener} that keeps {@link #regionToWorld}
     * in sync with the given world's live region set. Called once per
     * world at regionizer materialisation time.
     */
    private RegionListener newRegionWorldTracker(WorldRef world) {
        return new RegionListener() {
            @Override
            public void onRegionCreated(Region region) {
                regionToWorld.put(region.id(), world);
            }

            @Override
            public void onRegionSplit(Region source, Region child) {
                regionToWorld.put(child.id(), world);
            }

            @Override
            public void onRegionDied(Region region) {
                regionToWorld.remove(region.id());
                lastAutosaveTick.remove(region.id());
                autoSaveRunners.remove(region.id());
            }
        };
    }

    /**
     * Return the {@link WorldRef} that currently owns {@code region},
     * or {@code null} if none. Populated by the auto-wired region →
     * world listener at region-creation time.
     */
    public WorldRef worldForRegion(RegionId regionId) {
        return regionToWorld.get(regionId);
    }

    /**
     * Non-creating variant of {@link #regionizerFor}. Returns {@code
     * null} when no regionizer has been established for {@code world}
     * (i.e. no {@code registerChunk} or {@code touchChunk} call has
     * happened for that world yet). Used by owner-lookup call sites
     * where "unloaded/unknown chunk" is the semantically correct
     * answer and where auto-creating on lookup would let typoed
     * WorldRefs grow the map without bound.
     */
    public ThreadedRegionizer regionizerForOrNull(WorldRef world) {
        return regionizers.get(world.dimensionId());
    }

    public ChunkTaskScheduler chunkTaskScheduler() {
        return chunkTaskScheduler;
    }

    public ChunkHolderManager chunkManagerFor(WorldRef world) {
        // Round-5 H4: wire the per-world regionizer accessor so
        // ChunkHolderManager.addTicket/removeTicket pin the section→region
        // mapping across their resolve→write pair. The supplier is queried
        // lazily on every ticket write (not captured at construction) so
        // the manager can be created inside regionizerFor's
        // computeIfAbsent — where the regionizer itself is not yet
        // published to the regionizers map. Same "null when regionizer
        // not materialised" contract as the OwnerLookup / ReadLockLookup
        // pair in RegionizedTaskQueue (Phase 1.2).
        return chunkManagers.computeIfAbsent(
                world.dimensionId(), id -> new ChunkHolderManager(world, () -> regionizerForOrNull(world)));
    }

    /**
     * Non-creating variant of {@link #chunkManagerFor}. Returns {@code
     * null} when no chunk manager has been established for {@code
     * world} (i.e. no chunk in that world has been shadowed yet). Used
     * by observability call sites (e.g. {@code /multiforge chunks} and
     * bridge diagnostics) where "unknown world" is the semantically
     * correct answer and where auto-creating on lookup would let a
     * typoed WorldRef grow the map without bound.
     */
    public ChunkHolderManager chunkManagerForOrNull(WorldRef world) {
        return chunkManagers.get(world.dimensionId());
    }

    // ============================================================
    // Phase 5 wave A — M9 tick-body wiring
    // ============================================================

    /**
     * Install the Phase 5 (M9) tick-body wiring:
     *
     * <ol>
     *   <li><b>{@link PhasedRegionTickBody.Phase#INBOUND_MAILBOX}</b> —
     *       prepends {@link HolderManagerRegionData#pollFullLoadUpdate()}
     *       drain (task 5.1) so downstream phases see the freshest
     *       load-level updates.</li>
     *   <li><b>{@link PhasedRegionTickBody.Phase#REGION_EVENTS}</b> —
     *       appends {@link ChunkTaskScheduler#drainInto(RegionId, int, long)}
     *       (task 5.2), draining BLOCKING→IDLE priority chunk work up
     *       to a bounded budget so the region worker never overspends
     *       its 50 ms tick target (CLAUDE.md rule 4).</li>
     *   <li><b>{@link PhasedRegionTickBody.Phase#FLUSH_OUTBOUND}</b> —
     *       appends {@link AutoSaveRunner#runOnce(Region)} (task 5.3),
     *       gated so autosave runs at most every {@link
     *       #DEFAULT_AUTOSAVE_INTERVAL_TICKS} region ticks (Vanilla
     *       parity: 6000 ticks = 5 minutes at 20 TPS).</li>
     * </ol>
     *
     * <p>Also installs a {@link RegionJournalLifecycle} listener on
     * every regionizer already known to this host <em>and</em> every
     * regionizer created afterwards (task 5.4), and hands the coordinator
     * to the lifecycle so per-region journals are tracked for the
     * {@link net.multiforge.runtime.shutdown.ShutdownPhase#FLUSHING_JOURNAL}
     * shutdown phase (task 5.5).
     *
     * <p>{@code userBuilder} carries the caller-supplied (usually
     * Vanilla-body) work for the other phases; the M9 wiring is
     * prepended / appended so it always fires regardless of what the
     * caller wired. Safe to call more than once — a fresh call rebuilds
     * the body from the passed-in {@code userBuilder} and swaps it into
     * the scheduler (see {@link TickRegionScheduler#setBody}). The
     * region-journal listener install is idempotent; the shutdown
     * coordinator ref is last-writer-wins.
     *
     * @param userBuilder the phase bodies the caller wants to wire
     * @param coordinator shutdown coordinator to hand new journals to; nullable
     * @param journalDir  directory where per-region {@code region-&lt;id&gt;.mjl}
     *                    files live; may not be null
     */
    public void installM9WiredTickBody(
            PhasedRegionTickBody.Builder userBuilder, RegionShutdownCoordinator coordinator, Path journalDir) {
        Objects.requireNonNull(userBuilder, "userBuilder");
        Objects.requireNonNull(journalDir, "journalDir");
        this.shutdownCoordinator = coordinator;
        this.journalDir = journalDir;

        // Install (or replace) the journal lifecycle listener on every
        // known regionizer. Prior lifecycle (if any) stays wired to the
        // old regionizers; a rewire is only useful for tests, so keeping
        // both is benign (an already-created region has its journal
        // opened once by the old listener and left alone by the new).
        RegionJournalLifecycle lifecycle = new RegionJournalLifecycle(journalDir, coordinator);
        this.journalLifecycle = lifecycle;
        for (ThreadedRegionizer r : regionizers.values()) {
            r.addListener(lifecycle);
            // Backfill: regions that were created before the lifecycle
            // was wired won't get onRegionCreated fired retroactively.
            // Open their journals here so the invariant "every live
            // region has an open journal after installM9WiredTickBody"
            // holds. Uses regions() which returns a de-duplicated snapshot.
            for (Region region : r.regions()) {
                lifecycle.onRegionCreated(region);
            }
        }

        // Build the wired body and swap it into the scheduler.
        PhasedRegionTickBody.Builder wired = userBuilder
                .prepend(PhasedRegionTickBody.Phase.INBOUND_MAILBOX, this::phasePollFullLoadUpdate)
                .append(PhasedRegionTickBody.Phase.REGION_EVENTS, this::phaseDrainChunkTasks)
                .append(PhasedRegionTickBody.Phase.FLUSH_OUTBOUND, this::phaseAutoSave);
        scheduler.setBody(wired.build());
    }

    /**
     * Boot-time recovery entry point (task 5.4). Walks every
     * {@code region-*.mjl} file in {@code dir} and dispatches each
     * entry through {@code harness}. Callers register per-{@code
     * JournalEntryKind} handlers on {@code harness} before invoking.
     *
     * <p>Delegates to {@link JournalReplayHarness#replayAll(Path,
     * JournalReplayHarness)}; kept here so production fork glue has a
     * single host-side entry point for boot-time recovery.
     */
    public int replayJournalsFromDir(Path dir, JournalReplayHarness harness) throws IOException {
        return JournalReplayHarness.replayAll(dir, harness);
    }

    /** Test/observability accessor for the installed journal lifecycle. */
    public RegionJournalLifecycle journalLifecycle() {
        return journalLifecycle;
    }

    /** Test/observability accessor for the installed shutdown coordinator. */
    public RegionShutdownCoordinator shutdownCoordinator() {
        return shutdownCoordinator;
    }

    /**
     * INBOUND_MAILBOX phase body — drain the region's pending
     * full-load-update queue so subsequent phases (block/fluid ticks,
     * entity AI, etc.) observe the freshest {@link
     * net.multiforge.runtime.chunk.ChunkLoadLevel} for each holder.
     * Bounded by {@link #PHASE_FULL_LOAD_MAX_PER_TICK} to preserve
     * CLAUDE.md rule 4.
     */
    private void phasePollFullLoadUpdate(Region region) {
        WorldRef world = regionToWorld.get(region.id());
        if (world == null) return; // region already died — nothing to drain
        ChunkHolderManager manager = chunkManagers.get(world.dimensionId());
        if (manager == null) return;
        HolderManagerRegionData data = manager.regionData(region.id());
        int drained = 0;
        while (drained < PHASE_FULL_LOAD_MAX_PER_TICK) {
            NewChunkHolder holder = data.pollFullLoadUpdate();
            if (holder == null) break;
            drained++;
            // The actual level-change re-publish is driven by
            // NewChunkHolder.setLevel() (fires the LevelChangeListener);
            // by the time the holder was enqueued its level had already
            // been updated, so the poll here just clears the pending
            // flag. Full-chunk-future resolution (Vanilla's Phase 6
            // migration) will hang off this same drain path.
        }
    }

    /**
     * REGION_EVENTS phase body — drain BLOCKING→IDLE priority chunk
     * work owned by this region. Bounded by both a max task count and
     * a wall-clock deadline; undrained tasks stay enqueued and are
     * retried next tick.
     */
    private void phaseDrainChunkTasks(Region region) {
        long deadline = System.nanoTime() + PHASE_CHUNK_TASK_DEADLINE_NANOS;
        chunkTaskScheduler.drainInto(region.id(), PHASE_CHUNK_TASK_MAX_PER_TICK, deadline);
    }

    /**
     * FLUSH_OUTBOUND phase body — run the region's per-tick autosave
     * slice, but only every {@link #DEFAULT_AUTOSAVE_INTERVAL_TICKS}
     * region ticks (Vanilla parity). The AutoSaveRunner itself has a
     * bounded per-call chunk count + deadline; it degrades gracefully
     * on a slow disk by simply saving fewer chunks per call.
     */
    private void phaseAutoSave(Region region) {
        long now = region.currentTick();
        Long last = lastAutosaveTick.get(region.id());
        if (last != null && now - last < DEFAULT_AUTOSAVE_INTERVAL_TICKS) return;
        AutoSaveRunner runner = autoSaveRunnerFor(region);
        if (runner == null) return; // journal never opened for this region — skip
        try {
            runner.runOnce(region);
            lastAutosaveTick.put(region.id(), now);
        } catch (IOException e) {
            ViolationLogger.warn(
                    "MultiThreadedSchedulerHost.phaseAutoSave",
                    "autosave failed for region " + region.id() + ": " + e.getMessage());
        }
    }

    /**
     * Lazy per-region AutoSaveRunner factory. Returns {@code null} if
     * the region has no journal (either the M9 wire-in hasn't run yet,
     * or the region's journal failed to open — see {@link
     * RegionJournalLifecycle#onRegionCreated}).
     *
     * <p>The runner is built with {@code this::serializeChunkSafely},
     * an indirection to the pluggable {@link #chunkSerializer} (see
     * {@link #setChunkSerializer}) rather than a hardcoded stub —
     * production boot glue (the fork's {@code ServerLifecycleHooks})
     * registers {@code RegionChunkSerializer.serializeForJournal}
     * there; tests that never call {@link #setChunkSerializer} keep
     * getting the {@code byte[0]} default.
     */
    private AutoSaveRunner autoSaveRunnerFor(Region region) {
        return autoSaveRunners.computeIfAbsent(region.id(), id -> {
            RegionJournalLifecycle lc = this.journalLifecycle;
            if (lc == null) return null;
            RegionJournal journal = lc.journalFor(id);
            if (journal == null) return null;
            WorldRef world = regionToWorld.get(id);
            if (world == null) return null;
            ChunkHolderManager manager = chunkManagers.get(world.dimensionId());
            if (manager == null) return null;
            HolderManagerRegionData data = manager.regionData(id);
            return new AutoSaveRunner(
                    data,
                    journal,
                    this::serializeChunkSafely,
                    PHASE_AUTOSAVE_MAX_CHUNKS_PER_TICK,
                    PHASE_AUTOSAVE_DEADLINE_NANOS);
        });
    }

    /**
     * Ensure a chunk is occupied and its region is registered with the
     * scheduler. Also creates a holder in the world's
     * {@link ChunkHolderManager} owned by the region, and drops a
     * {@link TicketType#PLUGIN} ticket so the chunk stays loaded at
     * BORDER level.
     */
    public Region touchChunk(WorldRef world, int chunkX, int chunkZ) {
        ThreadedRegionizer regionizer = regionizerFor(world);
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        Region r = regionizer.addChunk(pos);
        scheduler.register(r);
        ChunkHolderManager manager = chunkManagerFor(world);
        manager.createHolder(pos, r.id());
        manager.addTicket(r.id(), pos, Ticket.of(TicketType.PLUGIN, "multiforge:touch"));
        return r;
    }

    /**
     * Register a Vanilla-loaded chunk with the regionizer and tick
     * scheduler, without adding any keep-loaded ticket or creating a
     * chunk holder. Used from the {@link
     * net.neoforged.neoforge.event.level.ChunkEvent.Load} handler
     * (M8 sub-step 6a): the chunk is already loaded by Vanilla's own
     * ticket, so MultiForge only needs to know it exists so region
     * workers can eventually tick it. Ticket/holder management stays
     * out of scope until M9.
     */
    public Region registerChunk(WorldRef world, int chunkX, int chunkZ) {
        ThreadedRegionizer regionizer = regionizerFor(world);
        Region r = regionizer.addChunk(new ChunkPos(chunkX, chunkZ));
        scheduler.register(r);
        return r;
    }

    /**
     * Inverse of {@link #registerChunk}: called from {@link
     * net.neoforged.neoforge.event.level.ChunkEvent.Unload}. The
     * regionizer's own listener cascade automatically deregisters the
     * dying region from the scheduler and clears its task-queue inbox
     * (see {@link RegionListener} auto-wiring in {@link
     * #regionizerFor}).
     */
    public void unregisterChunk(WorldRef world, int chunkX, int chunkZ) {
        ThreadedRegionizer regionizer = regionizerFor(world);
        regionizer.removeChunk(new ChunkPos(chunkX, chunkZ));
    }

    @Override
    public void close() {
        scheduler.close();
        delayedExec.shutdownNow();
        asyncExec.shutdownNow();
        try {
            delayedExec.awaitTermination(1, TimeUnit.SECONDS);
            asyncExec.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public RegionDomain region(WorldRef world, ChunkPos pos) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");
        return new RegionDomainImpl(world, pos);
    }

    @Override
    public EntityDomain entity(EntityRef entity) {
        Objects.requireNonNull(entity, "entity");
        return new EntityDomainImpl(entity);
    }

    @Override
    public GlobalDomain global() {
        return new GlobalDomainImpl();
    }

    @Override
    public AsyncDomain async() {
        return new AsyncDomainImpl();
    }

    // ---- region ---------------------------------------------------------

    private final class RegionDomainImpl implements RegionDomain {
        private final WorldRef world;
        private final ChunkPos pos;

        RegionDomainImpl(WorldRef world, ChunkPos pos) {
            this.world = world;
            this.pos = pos;
        }

        @Override
        public ScheduledTask execute(ModIdentifier mod, Runnable task) {
            return run(mod, ignored -> task.run());
        }

        @Override
        public ScheduledTask run(ModIdentifier mod, Consumer<ScheduledTask> task) {
            SchedulerTaskImpl handle = new SchedulerTaskImpl(mod, false);
            taskQueue.queueChunkTask(world, pos.x(), pos.z(), () -> runOnce(handle, task));
            return handle;
        }

        @Override
        public ScheduledTask runDelayed(ModIdentifier mod, Consumer<ScheduledTask> task, long delayTicks) {
            SchedulerTaskImpl handle = new SchedulerTaskImpl(mod, false);
            delayedExec.schedule(
                    () -> taskQueue.queueChunkTask(world, pos.x(), pos.z(), () -> runOnce(handle, task)),
                    Math.max(0, delayTicks) * TICK_MS,
                    TimeUnit.MILLISECONDS);
            return handle;
        }

        @Override
        public ScheduledTask runAtFixedRate(
                ModIdentifier mod, Consumer<ScheduledTask> task, long initialTicks, long periodTicks) {
            SchedulerTaskImpl handle = new SchedulerTaskImpl(mod, true);
            var future = delayedExec.scheduleAtFixedRate(
                    () -> {
                        if (handle.isTerminal()) return;
                        taskQueue.queueChunkTask(world, pos.x(), pos.z(), () -> runRepeatingIteration(handle, task));
                    },
                    Math.max(0, initialTicks) * TICK_MS,
                    Math.max(1, periodTicks) * TICK_MS,
                    TimeUnit.MILLISECONDS);
            handle.bindFuture(future);
            return handle;
        }
    }

    // ---- global ---------------------------------------------------------

    private final class GlobalDomainImpl implements GlobalDomain {
        @Override
        public ScheduledTask execute(ModIdentifier mod, Runnable task) {
            return run(mod, ignored -> task.run());
        }

        @Override
        public ScheduledTask run(ModIdentifier mod, Consumer<ScheduledTask> task) {
            SchedulerTaskImpl handle = new SchedulerTaskImpl(mod, false);
            enqueueOnGlobal(() -> runOnce(handle, task));
            return handle;
        }

        @Override
        public ScheduledTask runDelayed(ModIdentifier mod, Consumer<ScheduledTask> task, long delayTicks) {
            SchedulerTaskImpl handle = new SchedulerTaskImpl(mod, false);
            delayedExec.schedule(
                    () -> enqueueOnGlobal(() -> runOnce(handle, task)),
                    Math.max(0, delayTicks) * TICK_MS,
                    TimeUnit.MILLISECONDS);
            return handle;
        }

        @Override
        public ScheduledTask runAtFixedRate(
                ModIdentifier mod, Consumer<ScheduledTask> task, long initialTicks, long periodTicks) {
            SchedulerTaskImpl handle = new SchedulerTaskImpl(mod, true);
            var future = delayedExec.scheduleAtFixedRate(
                    () -> {
                        if (handle.isTerminal()) return;
                        enqueueOnGlobal(() -> runRepeatingIteration(handle, task));
                    },
                    Math.max(0, initialTicks) * TICK_MS,
                    Math.max(1, periodTicks) * TICK_MS,
                    TimeUnit.MILLISECONDS);
            handle.bindFuture(future);
            return handle;
        }
    }

    /**
     * Deliver {@code r} into the global region's inbox. We keep the
     * enqueue on the shared {@link RegionizedTaskQueue} (rather than
     * poking the region's inbox directly) so the scheduler's tick
     * loop stays the sole reader — but we bypass the world lookup
     * because the global region is created eagerly and never moves.
     */
    private void enqueueOnGlobal(Runnable r) {
        taskQueue.queueChunkTask(globalRegionizer.world(), 0, 0, r);
    }

    // ---- entity ---------------------------------------------------------

    private final class EntityDomainImpl implements EntityDomain {
        private final EntityRef entity;

        EntityDomainImpl(EntityRef entity) {
            this.entity = entity;
        }

        private Consumer<ScheduledTask> wrap(Consumer<ScheduledTask> body, Runnable retired) {
            return handle -> {
                if (entity.isRetired()) {
                    if (retired != null) retired.run();
                    handle.cancel();
                    return;
                }
                body.accept(handle);
            };
        }

        @Override
        public ScheduledTask execute(ModIdentifier mod, Runnable task, Runnable retired) {
            return run(mod, ignored -> task.run(), retired);
        }

        @Override
        public ScheduledTask run(ModIdentifier mod, Consumer<ScheduledTask> task, Runnable retired) {
            SchedulerTaskImpl handle = new SchedulerTaskImpl(mod, false);
            ChunkPos p = entity.chunkPos();
            taskQueue.queueChunkTask(entity.world(), p.x(), p.z(), () -> runOnce(handle, wrap(task, retired)));
            return handle;
        }

        @Override
        public ScheduledTask runDelayed(
                ModIdentifier mod, Consumer<ScheduledTask> task, Runnable retired, long delayTicks) {
            SchedulerTaskImpl handle = new SchedulerTaskImpl(mod, false);
            delayedExec.schedule(
                    () -> {
                        ChunkPos p = entity.chunkPos();
                        taskQueue.queueChunkTask(
                                entity.world(), p.x(), p.z(), () -> runOnce(handle, wrap(task, retired)));
                    },
                    Math.max(0, delayTicks) * TICK_MS,
                    TimeUnit.MILLISECONDS);
            return handle;
        }

        @Override
        public ScheduledTask runAtFixedRate(
                ModIdentifier mod,
                Consumer<ScheduledTask> task,
                Runnable retired,
                long initialTicks,
                long periodTicks) {
            SchedulerTaskImpl handle = new SchedulerTaskImpl(mod, true);
            var future = delayedExec.scheduleAtFixedRate(
                    () -> {
                        if (handle.isTerminal()) return;
                        ChunkPos p = entity.chunkPos();
                        taskQueue.queueChunkTask(
                                entity.world(), p.x(), p.z(), () -> runRepeatingIteration(handle, wrap(task, retired)));
                    },
                    Math.max(0, initialTicks) * TICK_MS,
                    Math.max(1, periodTicks) * TICK_MS,
                    TimeUnit.MILLISECONDS);
            handle.bindFuture(future);
            return handle;
        }
    }

    // ---- async ----------------------------------------------------------

    private final class AsyncDomainImpl implements AsyncDomain {
        @Override
        public ScheduledTask runNow(ModIdentifier mod, Consumer<ScheduledTask> task) {
            SchedulerTaskImpl handle = new SchedulerTaskImpl(mod, false);
            track(mod, handle);
            var future = asyncExec.schedule(() -> runAsync(handle, task, false), 0, TimeUnit.MILLISECONDS);
            handle.bindFuture(future);
            return handle;
        }

        @Override
        public ScheduledTask runDelayed(ModIdentifier mod, Consumer<ScheduledTask> task, long delay, TimeUnit unit) {
            SchedulerTaskImpl handle = new SchedulerTaskImpl(mod, false);
            track(mod, handle);
            var future = asyncExec.schedule(
                    () -> runAsync(handle, task, false), unit.toMillis(delay), TimeUnit.MILLISECONDS);
            handle.bindFuture(future);
            return handle;
        }

        @Override
        public ScheduledTask runAtFixedRate(
                ModIdentifier mod, Consumer<ScheduledTask> task, long initial, long period, TimeUnit unit) {
            SchedulerTaskImpl handle = new SchedulerTaskImpl(mod, true);
            track(mod, handle);
            var future = asyncExec.scheduleAtFixedRate(
                    () -> runAsync(handle, task, true),
                    unit.toMillis(initial),
                    unit.toMillis(Math.max(1, period)),
                    TimeUnit.MILLISECONDS);
            handle.bindFuture(future);
            return handle;
        }

        @Override
        public int cancelTasks(ModIdentifier mod) {
            ConcurrentMap<SchedulerTaskImpl, Boolean> set = asyncByMod.remove(mod);
            if (set == null) return 0;
            int n = 0;
            for (SchedulerTaskImpl t : set.keySet()) if (t.cancel()) n++;
            return n;
        }

        private void track(ModIdentifier mod, SchedulerTaskImpl handle) {
            asyncByMod.computeIfAbsent(mod, k -> new ConcurrentHashMap<>()).put(handle, Boolean.TRUE);
        }

        private void runAsync(SchedulerTaskImpl handle, Consumer<ScheduledTask> body, boolean repeating) {
            if (!handle.enterExecuting()) return;
            try {
                OwnerToken.runAs(OwnerToken.ASYNC, () -> body.accept(handle));
            } finally {
                if (repeating) handle.prepareNextIteration();
                else handle.markFinished();
            }
        }
    }

    // ---- shared task runners --------------------------------------------

    private static void runOnce(SchedulerTaskImpl handle, Consumer<ScheduledTask> body) {
        if (!handle.enterExecuting()) return;
        try {
            body.accept(handle);
        } catch (Throwable t) {
            Thread.currentThread().getUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), t);
        } finally {
            handle.markFinished();
        }
    }

    private static void runRepeatingIteration(SchedulerTaskImpl handle, Consumer<ScheduledTask> body) {
        if (!handle.enterExecuting()) return;
        try {
            body.accept(handle);
        } catch (Throwable t) {
            Thread.currentThread().getUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), t);
        } finally {
            handle.prepareNextIteration();
        }
    }

    @SuppressWarnings("unused")
    private static int globalBatchNoise(int inboxSize) {
        // Placeholder to silence the constant-only compiler warning
        // without wiring the batch value into a config listener yet.
        return Math.min(GLOBAL_REGION_INBOX_BATCH, inboxSize);
    }
}
