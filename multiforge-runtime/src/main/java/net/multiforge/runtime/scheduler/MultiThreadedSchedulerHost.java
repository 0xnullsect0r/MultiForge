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
package net.multiforge.runtime.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
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
import net.multiforge.runtime.chunk.NewChunkHolder;
import net.multiforge.runtime.chunk.TickingBlockEntityRef;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.diagnostics.ChunkCost;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.ownership.Domain;
import net.multiforge.runtime.ownership.OwnerToken;
import net.multiforge.runtime.ownership.OwnershipEnforcer;
import net.multiforge.runtime.region.BlockEntityTickRunner;
import net.multiforge.runtime.region.EntityTickRunner;
import net.multiforge.runtime.region.PhasedRegionTickBody;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionChunkSource;
import net.multiforge.runtime.region.RegionId;
import net.multiforge.runtime.region.RegionListener;
import net.multiforge.runtime.region.RegionTickBody;
import net.multiforge.runtime.region.RegionizedTaskQueue;
import net.multiforge.runtime.region.ScheduledTickRunner;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.region.TickRegionScheduler;
import net.multiforge.runtime.region.pin.RegionPinManager;

/**
 * Parallel {@link SchedulerHost} that runs region/entity work on the
 * {@link TickRegionScheduler} worker pool. Global work runs in the
 * synthetic global region, ticked once per server tick before any
 * level's regions. Async work runs on a shared pool.
 *
 * <p>Region/entity task <em>dispatch</em> is deferred: calling
 * {@code region.execute(mod, r)} enqueues {@code r} in the owner
 * region's inbox and the pool drains it on the next tick of that
 * region. This is Folia's guarantee: cross-thread work never runs on
 * the wrong region.
 */
public final class MultiThreadedSchedulerHost implements SchedulerHost, AutoCloseable {

    private static final long TICK_MS = 50L;
    /** The synthetic world holding the global region; not a Minecraft level. */
    public static final WorldRef GLOBAL_WORLD = WorldRef.of("multiforge:global");

    private volatile MultiForgeConfig config;
    private final ConcurrentMap<String, ThreadedRegionizer> regionizers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ChunkHolderManager> chunkManagers = new ConcurrentHashMap<>();
    private final Function<WorldRef, ThreadedRegionizer> regionizerFactory;
    private final RegionizedTaskQueue taskQueue;
    private final TickRegionScheduler scheduler;

    // Region → world map maintained by an auto-wired RegionListener on each
    // regionizer. Populated on onRegionCreated / onRegionSplit; cleared on
    // onRegionDied. Read by the Phase 5 wiring to route a Region back to
    // its owning ChunkHolderManager without a linear scan over every world.
    private final ConcurrentMap<RegionId, WorldRef> regionToWorld = new ConcurrentHashMap<>();

    // Region pins honoured by every world's regionizer (bindPins).
    private volatile RegionPinManager pins;
    private volatile AutoCloseable pinSubscription;

    // B3.2 wiring (docs/design/m13-b3-region-tick.md §5.1): pluggable
    // per-region BLOCK_FLUID_TICKS runner. Defaults to a no-op so tests
    // and any pre-fork-wiring boot window keep working exactly as
    // before — the fork's ScheduledTickRunnerBridge (net.multiforge.
    // neoforge.tick) swaps this for the real Vanilla-backed
    // implementation via setBlockFluidRunner, registered from
    // RegionRuntimeInit on ServerAboutToStart. Until a real
    // runner is registered, Vanilla's own inline blockTicks/fluidTicks
    // drain (guarded by RegionizedTickCoordinator.regionsHandleBlockFluidTicks)
    // stays the fallback, so this default never silently drops ticks
    // (CLAUDE.md rule 5).
    // Identity sentinel for the no-op default above — hasBlockFluidRunner()
    // compares against this constant (not merely "non-null", since the
    // field is never null) to answer "has a real runner been registered?".
    private static final ScheduledTickRunner NOOP_BLOCK_FLUID_RUNNER = region -> {};
    private volatile ScheduledTickRunner blockFluidRunner = NOOP_BLOCK_FLUID_RUNNER;

    // B3.3 (docs/design/m13-b3-region-tick.md §5.2): the Vanilla-backed
    // per-region ENTITY_AI implementation, bound by the fork's
    // RegionRuntimeInit.install on a fresh runtime install
    // (net.multiforge.neoforge.tick.EntityTickRunnerBridge). Defaults
    // to a no-op so tests and any pre-fork-wiring boot window keep
    // working exactly as before install — matches the {@link
    // #chunkSerializer} default-stub convention above.
    // entityTickRunnerRegistered tracks whether a *real* (non-default)
    // runner has ever been set, since the default itself is a
    // non-null no-op — RegionizedTickCoordinator.regionsHandleEntityTicks
    // needs to distinguish the two to decide whether the Vanilla-inline
    // fallback should still run.
    private volatile EntityTickRunner entityTickRunner = region -> {};
    private volatile boolean entityTickRunnerRegistered = false;

    private final ScheduledExecutorService delayedExec;
    private final ScheduledExecutorService asyncExec;
    private final ConcurrentMap<ModIdentifier, ConcurrentMap<SchedulerTaskImpl, Boolean>> asyncByMod =
            new ConcurrentHashMap<>();

    // The "global region" — a synthetic region pinned to the pool, ticking
    // like any other region. Owns global-only state (weather, time, etc.).
    private final Region globalRegion;
    private final ThreadedRegionizer globalRegionizer;

    // B3.4 (docs/design/m13-b3-region-tick.md §5.3): per-region BLOCK_ENTITIES
    // phase body. Built once against this host's own worldForRegion/
    // chunkManagerForOrNull lookups so a fresh call always sees the
    // freshest regionToWorld/chunkManagers state (both mutate after
    // construction as worlds/regions materialise).
    private final BlockEntityTickRunner blockEntityTickRunner =
            BlockEntityTickRunner.standard(this::worldForRegion, this::chunkManagerForOrNull);

    public MultiThreadedSchedulerHost(MultiForgeConfig config) {
        this(config, region -> {}); // no-op tick body until M2 patch supplies the vanilla body
    }

    public MultiThreadedSchedulerHost(MultiForgeConfig config, RegionTickBody body) {
        this(config, body, TickRegionScheduler.Mode.FREE_RUNNING);
    }

    /**
     * @param mode {@link TickRegionScheduler.Mode#BARRIER} on a NeoForge
     *     server (the server thread drives each tick via {@link
     *     #driveRegions}/{@link #driveGlobalTick}); {@link
     *     TickRegionScheduler.Mode#FREE_RUNNING} for headless runtime use.
     */
    public MultiThreadedSchedulerHost(MultiForgeConfig config, RegionTickBody body, TickRegionScheduler.Mode mode) {
        this.config = Objects.requireNonNull(config, "config");
        this.regionizerFactory = world -> new ThreadedRegionizer(world, this.config.regionSize());
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
        this.scheduler = new TickRegionScheduler(config.tickWorkerCount(), body, taskQueue, 128, mode);
        applyInlinePolicy(config);

        // Global region is exposed under a synthetic world so it uses the
        // same inbox+tick plumbing as any other region. Publish it into
        // the regionizers map BEFORE calling addChunk so
        // `taskQueue.queueChunkTask(GLOBAL_WORLD, ...)` reaches the same
        // regionizer as `globalRegionizer` here.
        WorldRef globalWorld = GLOBAL_WORLD;
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
        OwnershipEnforcer.bindPositionRouter(positionRouter);
        net.multiforge.runtime.event.SerialDispatchProbes.bindWorldLookup(id -> {
            WorldRef world = regionToWorld.get(new RegionId(id));
            return world == null ? null : world.dimensionId();
        });
    }

    /**
     * Hand the config's tick placement knobs to the scheduler. {@code
     * -Dmultiforge.inlineSingleRegion} and {@code
     * -Dmultiforge.serialLaneHotWaitMs} override the file (benchmarks compare
     * with and without); v1.8's {@code -Dmultiforge.serialLaneInlineThreshold=0}
     * still turns the hot move off.
     */
    private void applyInlinePolicy(MultiForgeConfig c) {
        boolean single = c.inlineSingleRegion();
        String singleProp = System.getProperty("multiforge.inlineSingleRegion");
        if (singleProp != null && !singleProp.isBlank()) single = Boolean.parseBoolean(singleProp.trim());
        long hotWaitMs = c.serialLaneHotWaitMs();
        String legacyProp = System.getProperty("multiforge.serialLaneInlineThreshold");
        if (legacyProp != null && legacyProp.trim().equals("0")) hotWaitMs = 0;
        String hotProp = System.getProperty("multiforge.serialLaneHotWaitMs");
        if (hotProp != null && !hotProp.isBlank()) {
            try {
                hotWaitMs = Long.parseLong(hotProp.trim());
            } catch (NumberFormatException ignored) {
                // keep the configured value
            }
        }
        scheduler.setInlinePolicy(single, hotWaitMs);
    }

    /** How a world's regions ticked last: see {@link #tickMode(WorldRef)}. */
    public enum WorldTickMode {
        /** No region: the level ticks on the server thread as in NeoForge. */
        SERVER_THREAD_NO_REGIONS,
        /** One region, ticked on the server thread. */
        SERVER_THREAD_SINGLE_REGION,
        /** Every region on a worker. */
        WORKERS,
        /** Some regions on workers, the hot ones on the server thread. */
        WORKERS_AND_SERVER_THREAD
    }

    /** How {@code world}'s regions ticked last (for the debug client and {@code /multiforge region list}). */
    public WorldTickMode tickMode(WorldRef world) {
        ThreadedRegionizer regionizer = regionizerForOrNull(world);
        if (regionizer == null) return WorldTickMode.SERVER_THREAD_NO_REGIONS;
        java.util.Collection<Region> regions = regionizer.regions();
        if (regions.isEmpty()) return WorldTickMode.SERVER_THREAD_NO_REGIONS;
        boolean worker = false;
        boolean server = false;
        for (Region region : regions) {
            TickRegionScheduler.TickPlacement p = scheduler.lastTickPlacement(region);
            if (p == null) continue;
            if (p == TickRegionScheduler.TickPlacement.SERVER_THREAD_SINGLE) {
                return WorldTickMode.SERVER_THREAD_SINGLE_REGION;
            }
            if (p == TickRegionScheduler.TickPlacement.WORKER) worker = true;
            else server = true;
        }
        if (worker && server) return WorldTickMode.WORKERS_AND_SERVER_THREAD;
        if (server) return WorldTickMode.SERVER_THREAD_SINGLE_REGION;
        return WorldTickMode.WORKERS;
    }

    /**
     * Chunk-ownership lookups for {@link OwnershipEnforcer#canMutateAt}:
     * the owner of a chunk is the region its regionizer section maps to,
     * and a rerouted mutation is queued on that region's mailbox.
     */
    private final OwnershipEnforcer.PositionRouter positionRouter = new OwnershipEnforcer.PositionRouter() {
        @Override
        public long ownerOf(WorldRef world, int chunkX, int chunkZ) {
            ThreadedRegionizer r = regionizerForOrNull(world);
            Region owner = r == null ? null : r.regionAtChunk(chunkX, chunkZ);
            return owner == null ? UNOWNED : owner.id().value();
        }

        @Override
        public long globalRegionId() {
            return globalRegion.id().value();
        }

        @Override
        public boolean queueOnOwner(WorldRef world, int chunkX, int chunkZ, Runnable mutation) {
            if (ownerOf(world, chunkX, chunkZ) == UNOWNED) return false;
            taskQueue.queueChunkTask(world, chunkX, chunkZ, mutation);
            return true;
        }

        @Override
        public boolean isPendingRegistration(WorldRef world, int chunkX, int chunkZ) {
            return !pendingLoads.isEmpty() && pendingLoads.contains(pendingKey(world, chunkX, chunkZ));
        }
    };

    /** Chunks loaded while regions ticked, awaiting registration (see {@link #chunkLoaded}). */
    private final java.util.Set<String> pendingLoads = ConcurrentHashMap.newKeySet();

    private static String pendingKey(WorldRef world, int chunkX, int chunkZ) {
        return world.dimensionId() + '|' + chunkX + ',' + chunkZ;
    }

    /**
     * Replace the {@link ScheduledTickRunner} the {@code
     * BLOCK_FLUID_TICKS} phase body ({@link #phaseBlockFluidTicksTick})
     * invokes (docs/design/m13-b3-region-tick.md §5.1). Idempotent —
     * safe to call more than once (e.g. a server restart re-registering
     * from {@code RegionRuntimeInit}); the last-registered
     * runner wins and every already-wired {@code phaseBlockFluidTicksTick}
     * body re-reads this volatile field on every invocation, so a
     * mid-life swap takes effect immediately.
     *
     * <p>Not part of {@link SchedulerHost}: this is a MultiForge-only
     * escape hatch for MC-dependent glue that cannot live in this
     * MC-free module. See {@code
     * net.multiforge.neoforge.tick.ScheduledTickRunnerBridge} in the
     * fork module for the production implementation; the default (a
     * no-op) is deliberate so tests and any pre-fork-wiring boot window
     * keep working unchanged.
     *
     * @param runner must never block (CLAUDE.md rule 4)
     */
    public void setBlockFluidRunner(ScheduledTickRunner runner) {
        this.blockFluidRunner = Objects.requireNonNull(runner, "runner");
    }

    /**
     * @return {@code true} iff a real {@link ScheduledTickRunner} has been
     *     registered via {@link #setBlockFluidRunner} — i.e. {@link
     *     #blockFluidRunner} is no longer the constructor-time no-op
     *     default. Read by {@code
     *     net.multiforge.neoforge.RegionizedTickCoordinator.regionsHandleBlockFluidTicks}
     *     to decide whether the {@code BLOCK_FLUID_TICKS} phase is
     *     actually handling a level's scheduled block/fluid ticks, or
     *     whether Vanilla's inline fallback should still run
     *     (docs/design/m13-b3-region-tick.md §5.1).
     */
    public boolean hasBlockFluidRunner() {
        return this.blockFluidRunner != NOOP_BLOCK_FLUID_RUNNER;
    }

    /**
     * Replace the {@link EntityTickRunner} the {@code ENTITY_AI} phase
     * body ({@link #phaseEntityAiTick}) invokes. Bound once by the
     * fork's {@code RegionRuntimeInit.install} on a fresh
     * runtime install ({@code net.multiforge.neoforge.tick.
     * EntityTickRunnerBridge}) — see docs/design/m13-b3-region-tick.md
     * §5.2. Before this is called (or in any test that never calls it),
     * the ENTITY_AI phase's runner is the default no-op, and {@link
     * net.multiforge.neoforge.RegionizedTickCoordinator#regionsHandleEntityTicks}
     * reports {@code false} so the Vanilla-inline fallback
     * ({@code ServerLevel.mfTickEntitiesAll}) stays live.
     *
     * @param runner must never block (CLAUDE.md rule 4).
     */
    public void setEntityTickRunner(EntityTickRunner runner) {
        this.entityTickRunner = Objects.requireNonNull(runner, "runner");
        this.entityTickRunnerRegistered = true;
    }

    /**
     * @return {@code true} iff {@link #setEntityTickRunner} has been
     *     called at least once on this host — distinguishes "a real
     *     runner is bound" from "the default no-op is still active",
     *     which {@link #entityTickRunner} alone cannot (the default is
     *     itself non-null). Consulted by {@code
     *     RegionizedTickCoordinator.regionsHandleEntityTicks}.
     */
    public boolean hasEntityTickRunner() {
        return entityTickRunnerRegistered;
    }

    public RegionizedTaskQueue taskQueue() {
        return taskQueue;
    }

    public TickRegionScheduler scheduler() {
        return scheduler;
    }

    /**
     * {@link TickRegionScheduler.Mode#BARRIER} entry point for one level's
     * tick: run every live region of {@code world} once, in parallel, and
     * return when all finished. {@code pump} runs on the calling (server)
     * thread while it waits — see {@link TickRegionScheduler#driveTick}.
     * Returns an empty result when {@code world} has no regionizer yet.
     */
    public TickRegionScheduler.TickAllResult driveRegions(WorldRef world, long deadlineNanos, BooleanSupplier pump) {
        ThreadedRegionizer regionizer = regionizerForOrNull(world);
        if (regionizer == null) return scheduler.driveTick(List.of(), deadlineNanos, pump);
        regionsTicking = true;
        ChunkCost.beginLevelTick(world.dimensionId());
        try {
            return scheduler.driveTick(regionizer.regions(), deadlineNanos, pump);
        } finally {
            ChunkCost.endLevelTick();
            regionsTicking = false;
            applyDeferredChunkChanges();
            // Fire-and-forget event posts the regions left (SerialLane.defer), now that
            // no region worker runs.
            net.multiforge.runtime.event.SerialLane.drainDeferred();
        }
    }

    /**
     * A chunk was loaded ({@code ChunkEvent.Load}): register it with its world's
     * regionizer, index it in the world's {@link ChunkHolderManager} under its
     * region, and hand it any work queued for it before it had an owner.
     *
     * <p>While a level's regions are ticking, the change is queued and applied on
     * the server thread when the barrier completes. A chunk loads mid-tick when a
     * region worker hands a load to the server thread ({@code MainThreadHandoff});
     * registering it then could merge the ticking region, and a merge waits for
     * its target to stop ticking — while that region's worker waits for the load.
     * Until the barrier ends the chunk is unowned, and work on it goes to the
     * server thread, as for any unowned chunk.
     */
    public void chunkLoaded(WorldRef world, int chunkX, int chunkZ) {
        if (regionsTicking) {
            pendingLoads.add(pendingKey(world, chunkX, chunkZ));
            deferredChunkChanges.add(() -> chunkLoaded(world, chunkX, chunkZ));
            return;
        }
        pendingLoads.remove(pendingKey(world, chunkX, chunkZ));
        Region region = registerChunk(world, chunkX, chunkZ);
        chunkManagerFor(world).createHolder(new net.multiforge.api.world.ChunkPos(chunkX, chunkZ), region.id());
        taskQueue.rerouteAtChunk(world, chunkX, chunkZ);
    }

    /** A chunk was unloaded ({@code ChunkEvent.Unload}); deferred like {@link #chunkLoaded}. */
    public void chunkUnloaded(WorldRef world, int chunkX, int chunkZ) {
        if (regionsTicking) {
            deferredChunkChanges.add(() -> chunkUnloaded(world, chunkX, chunkZ));
            return;
        }
        pendingLoads.remove(pendingKey(world, chunkX, chunkZ));
        unregisterChunk(world, chunkX, chunkZ);
        ChunkHolderManager manager = chunkManagerForOrNull(world);
        if (manager != null) manager.dropHolder(new net.multiforge.api.world.ChunkPos(chunkX, chunkZ));
    }

    private void applyDeferredChunkChanges() {
        Runnable change;
        while ((change = deferredChunkChanges.poll()) != null) change.run();
    }

    /**
     * {@link TickRegionScheduler.Mode#BARRIER} entry point for the synthetic
     * global region: runs the tasks queued on the global domain ({@code
     * ServerDomains.global()} work, GLOBAL-domain event listeners). Called
     * once per server tick on the server thread, before any level's regions;
     * the world-wide Vanilla systems (weather, time, world border, raids,
     * dragon fight) run in {@code ServerLevel.tick} on the server thread
     * itself, which never overlaps region work.
     */
    public TickRegionScheduler.TickAllResult driveGlobalTick(long deadlineNanos, BooleanSupplier pump) {
        try {
            return scheduler.driveTick(List.of(globalRegion), deadlineNanos, pump);
        } finally {
            net.multiforge.runtime.event.SerialLane.drainDeferred();
        }
    }

    /**
     * The synthetic global region — a real {@link Region}, materialised
     * eagerly at host-construction time and stable for the life of this
     * host instance (docs/design/global-region.md §1.2).
     */
    public Region globalRegion() {
        return globalRegion;
    }

    public MultiForgeConfig config() {
        return config;
    }

    /** True while a level's regions tick (server thread inside {@link #driveRegions}). */
    private volatile boolean regionsTicking;

    /** Chunk loads/unloads that arrived while regions were ticking, in order. */
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> deferredChunkChanges =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * Apply a changed configuration live: resize the worker pool and, if the
     * region size changed, re-partition every world. Server thread, between
     * ticks (commands run there; region workers defer them to it).
     */
    public void applyConfig(MultiForgeConfig next) {
        Objects.requireNonNull(next, "next");
        MultiForgeConfig prev = this.config;
        this.config = next;
        applyInlinePolicy(next);
        if (next.tickWorkerCount() != scheduler.workerCount()) scheduler.resize(next.tickWorkerCount());
        if (next.regionSize() != prev.regionSize()) repartition();
    }

    /**
     * Rebuild every world's regions with the current section size: mailboxes
     * are drained first (no rerouted work is lost), each world's regions are
     * dissolved, its loaded chunks re-added, and every block-entity ticker
     * moved to the region now owning its chunk.
     */
    private void repartition() {
        drainMailboxesOnCaller();
        for (ThreadedRegionizer old : List.copyOf(regionizers.values())) {
            if (old == globalRegionizer) continue;
            WorldRef world = old.world();
            ChunkHolderManager manager = chunkManagers.get(world.dimensionId());
            List<ChunkPos> loaded = new ArrayList<>();
            List<TickingBlockEntityRef> tickers = new ArrayList<>();
            if (manager != null) {
                for (NewChunkHolder h : manager.holders()) loaded.add(h.position());
                for (Region region : old.regions()) {
                    tickers.addAll(manager.regionData(region.id()).snapshotBlockEntityTickers());
                }
            }
            old.clear();
            regionizers.remove(world.dimensionId(), old);
            ThreadedRegionizer fresh = regionizerFor(world);
            for (ChunkPos pos : loaded) {
                Region region = fresh.addChunk(pos);
                scheduler.register(region);
                manager.createHolder(pos, region.id());
            }
            for (TickingBlockEntityRef ticker : tickers) {
                if (ticker.isRemoved()) continue;
                Region region = fresh.regionAtChunk(ticker.pos().toChunkPos());
                if (region != null) manager.regionData(region.id()).addBlockEntityTicker(ticker);
            }
        }
    }

    /**
     * Honour {@code pinManager}'s region pins in every world's regionizer,
     * current and future, and re-apply them whenever a pin is added or
     * removed. Server thread, between ticks.
     */
    public void bindPins(RegionPinManager pinManager) {
        Objects.requireNonNull(pinManager, "pinManager");
        AutoCloseable previous = this.pinSubscription;
        if (previous != null) {
            try {
                previous.close();
            } catch (Exception ignored) {
                // CopyOnWriteArrayList removal; cannot fail
            }
        }
        this.pins = pinManager;
        for (ThreadedRegionizer r : regionizers.values()) {
            if (r != globalRegionizer) r.setPins(pinManager::all);
        }
        this.pinSubscription = pinManager.addChangeListener(() -> {
            for (ThreadedRegionizer r : regionizers.values()) {
                if (r != globalRegionizer) r.refreshPins();
            }
        });
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
            // Also the per-world ChunkHolderManager, so each indexed chunk's
            // owning region and the per-region block-entity tickers follow
            // merges and splits and are cleaned up on death.
            r.addListener(chunkManagerFor(world));
            // Phase 5.1/5.3 wiring: region → world map so the M9-wired
            // tick body can route Region → ChunkHolderManager without
            // linear-scanning every world's manager.
            r.addListener(newRegionWorldTracker(world));
            RegionPinManager boundPins = this.pins;
            if (boundPins != null) r.setPins(boundPins::all);
            return r;
        });
    }

    /**
     * Build a {@link RegionListener} that keeps {@link #regionToWorld}
     * in sync with the given world's live region set, and (B3.1, docs/
     * design/m13-b3-region-tick.md §4.3) wires every region created
     * under {@code world} to the {@link RegionChunkSource} backing
     * {@link Region#ownedChunkSnapshot()}. Called once per world at
     * regionizer materialisation time.
     */
    private RegionListener newRegionWorldTracker(WorldRef world) {
        // Captured once per world (not per region) — chunkManagerForOrNull
        // is a non-creating lookup so a world that never shadows a chunk
        // (e.g. the synthetic global world) never materialises a
        // ChunkHolderManager just because a region was created; the
        // source simply resolves to an empty snapshot in that case.
        RegionChunkSource chunkSource = regionId -> {
            ChunkHolderManager mgr = chunkManagerForOrNull(world);
            if (mgr == null) return List.of();
            List<NewChunkHolder> holders = mgr.holdersOwnedBy(regionId);
            List<ChunkPos> out = new ArrayList<>(holders.size());
            for (NewChunkHolder h : holders) out.add(h.position());
            return List.copyOf(out);
        };
        return new RegionListener() {
            @Override
            public void onRegionCreated(Region region) {
                regionToWorld.put(region.id(), world);
                region.withChunkSource(chunkSource);
            }

            @Override
            public void onRegionSplit(Region source, Region child) {
                regionToWorld.put(child.id(), world);
                child.withChunkSource(chunkSource);
            }

            @Override
            public void onRegionDied(Region region) {
                regionToWorld.remove(region.id());
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

    /**
     * Read-only snapshot of every world's regionizer, keyed by {@link
     * WorldRef#dimensionId()} (includes the synthetic {@code
     * multiforge:global} world). Observability call sites (e.g. the
     * {@code diagnostics.emitters} package's {@code RegionMapEmitter})
     * use this to enumerate every live region across every world
     * without reaching into host internals.
     */
    public Map<String, ThreadedRegionizer> regionizers() {
        return Map.copyOf(regionizers);
    }

    public ChunkHolderManager chunkManagerFor(WorldRef world) {
        return chunkManagers.computeIfAbsent(world.dimensionId(), id -> new ChunkHolderManager(world));
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
    // Region tick body
    // ============================================================

    /**
     * Install the per-region tick body: after the region's mailbox (drained
     * by the scheduler before the body runs), {@link
     * PhasedRegionTickBody.Phase#BLOCK_FLUID_TICKS} runs the registered
     * {@link ScheduledTickRunner} (scheduled block and fluid ticks, then the
     * block events they queued), {@link PhasedRegionTickBody.Phase#ENTITY_AI}
     * the registered {@link EntityTickRunner}, and {@link
     * PhasedRegionTickBody.Phase#BLOCK_ENTITIES} the region's block-entity
     * tickers. See docs/design/barrier-tick-model.md.
     *
     * <p>{@code userBuilder} carries any extra per-phase work (tests use it
     * for probes); the region work is appended so both run. Safe to call
     * more than once: each call rebuilds the body and swaps it in.
     */
    public void installRegionTickBody(PhasedRegionTickBody.Builder userBuilder) {
        Objects.requireNonNull(userBuilder, "userBuilder");
        PhasedRegionTickBody.Builder wired = userBuilder
                .append(PhasedRegionTickBody.Phase.BLOCK_FLUID_TICKS, this::phaseBlockFluidTicksTick)
                .append(PhasedRegionTickBody.Phase.ENTITY_AI, this::phaseEntityAiTick)
                .append(PhasedRegionTickBody.Phase.BLOCK_ENTITIES, this::phaseBlockEntitiesTickPerRegion);
        scheduler.setBody(wired.build());
    }

    /**
     * {@code BLOCK_FLUID_TICKS} phase body (B3.2, docs/design/
     * m13-b3-region-tick.md §5.1) — invokes the currently-registered
     * {@link #blockFluidRunner} for {@code region}. Phases tick in {@link
     * PhasedRegionTickBody.Phase} declaration order, so a redstone/crop/
     * liquid update this phase applies is visible to the same tick's entity
     * and block-entity passes, as in Vanilla. A throwable propagates: the
     * barrier rethrows it on the server thread (see {@link
     * TickRegionScheduler#driveTick}), where Vanilla's crash handling applies.
     */
    private void phaseBlockFluidTicksTick(Region region) {
        try {
            blockFluidRunner.runBlockFluidTicks(region);
        } finally {
            flushChunkCost(region);
        }
    }

    /** Move the per-chunk tick time this thread measured for {@code region} to its world's window. */
    private void flushChunkCost(Region region) {
        if (!ChunkCost.enabled()) return;
        WorldRef world = worldForRegion(region.id());
        if (world != null) ChunkCost.flush(world.dimensionId());
    }

    /**
     * {@code ENTITY_AI} phase body (B3.3, docs/design/
     * m13-b3-region-tick.md §5.2) — invokes the bound {@link
     * #entityTickRunner} for {@code region}, after first asserting the
     * call is actually running on {@code region}'s own worker thread.
     *
     * <p><b>Correctness guard.</b> Per §5's part 4 and §6 invariant 2
     * ("no entity is ticked on a thread that isn't its owning region's
     * worker"), this method reads {@link OwnerToken#current()} and
     * refuses to invoke {@link #entityTickRunner} unless the calling
     * thread's token is {@link Domain#REGION} for exactly this {@code
     * region}'s id. A mismatch — a stale snapshot racing a split/merge,
     * a test driving the body directly without setting a token, or a
     * genuine bug — auto-reroutes to "skip, don't tick" rather than
     * proceeding (CLAUDE.md rule 5): it bumps a probe, logs a rate-
     * limited warning, and returns without calling the runner.
     *
     * <p>The runner invocation itself is wrapped in a second, defensive
     * try/catch so a throwing {@link EntityTickRunner} (the fork
     * bridge, or a test double) can never strand a later phase in the
     * same tick — {@link PhasedRegionTickBody#tickOnce} already
     * isolates each phase this way, but the per-entity iteration this
     * phase delegates to is exactly the kind of large, mod-influenced
     * body CLAUDE.md rule 5 asks call sites to defend individually too.
     */
    private void phaseEntityAiTick(Region region) {
        OwnerToken tok = OwnerToken.current();
        if (tok.domain() != Domain.REGION || tok.regionId() != region.id().value()) {
            ProbeRegistry.bump("entity-ai.wrong-owner");
            ViolationLogger.warn(
                    "entity-ai.wrong-owner",
                    "phaseEntityAiTick(" + region.id() + ") invoked from a thread whose OwnerToken is domain="
                            + tok.domain() + " regionId=" + tok.regionId()
                            + " — skipping this tick rather than ticking foreign entity state");
            return;
        }
        try {
            entityTickRunner.tickEntitiesForRegion(region);
        } finally {
            flushChunkCost(region);
        }
    }

    /**
     * {@code BLOCK_ENTITIES} phase body (B3.4, docs/design/
     * m13-b3-region-tick.md §5.3). Ticks {@code region}'s own slice of {@code HolderManagerRegionData
     * .blockEntityTickers} via {@link #blockEntityTickRunner}.
     *
     * <p>OwnerToken correctness guard: mirrors {@code
     * RegionizedData.assertOwnedOrIgnore}'s shape (same file's package,
     * {@code net.multiforge.runtime.region.RegionizedData}) — {@link
     * Domain#UNKNOWN} and {@link Domain#GLOBAL} pass through untouched
     * (tests driving {@code tickOnce} by hand off any worker thread, and
     * the global region's own worker, both need to reach this body
     * normally); a {@link Domain#REGION} token whose {@code regionId}
     * does not match {@code region.id()} is a foreign-thread call — per
     * CLAUDE.md rule 5 this warns + bumps a probe and skips ticking
     * rather than touching another region's chunk-owned state.
     */
    private void phaseBlockEntitiesTickPerRegion(Region region) {
        OwnerToken tok = OwnerToken.current();
        if (tok.domain() == Domain.REGION && tok.regionId() != region.id().value()) {
            ProbeRegistry.bump("block-entities.phase.wrong-owner");
            ViolationLogger.warn(
                    "MultiThreadedSchedulerHost.phaseBlockEntitiesTickPerRegion",
                    "region=" + region.id() + " token=" + tok
                            + " — skipping per-region block-entity tick (foreign-thread guard)");
            return;
        }
        try {
            blockEntityTickRunner.tickBlockEntitiesForRegion(region);
        } finally {
            flushChunkCost(region);
        }
    }

    /**
     * Ensure a chunk is occupied and its region is registered with the
     * scheduler, and record it in the world's {@link ChunkHolderManager}
     * as owned by that region. For MC-free callers (tests, benchmarks);
     * a server registers loaded chunks through {@link #registerChunk}.
     */
    public Region touchChunk(WorldRef world, int chunkX, int chunkZ) {
        ThreadedRegionizer regionizer = regionizerFor(world);
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        Region r = regionizer.addChunk(pos);
        scheduler.register(r);
        ChunkHolderManager manager = chunkManagerFor(world);
        manager.createHolder(pos, r.id());
        return r;
    }

    /**
     * Register a Vanilla-loaded chunk with the regionizer and tick
     * scheduler, without adding any keep-loaded ticket or creating a
     * chunk holder. Used from the {@link
     * net.neoforged.neoforge.event.level.ChunkEvent.Load} handler
     * : the chunk is already loaded by Vanilla, so MultiForge only
     * needs to know it exists so its region ticks it. The caller records
     * the holder in the world's {@link ChunkHolderManager}.
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

    /**
     * Run every task still waiting in a region mailbox on the calling thread,
     * until none remain (tasks may enqueue more). For server stop: called on
     * the server thread after the last tick barrier, while no region runs, and
     * before the world is saved, so rerouted mutations are not lost.
     *
     * @return the number of tasks run
     */
    public int drainMailboxesOnCaller() {
        int total = 0;
        for (int pass = 0; pass < 16; pass++) {
            int ran = 0;
            for (ThreadedRegionizer regionizer : regionizers.values()) {
                for (Region region : regionizer.regions()) ran += taskQueue.drain(region, Integer.MAX_VALUE);
            }
            total += ran;
            if (ran == 0) return total;
        }
        // Tasks that keep queueing more tasks: stop rather than spin, but say so.
        int left = 0;
        for (ThreadedRegionizer regionizer : regionizers.values()) {
            for (Region region : regionizer.regions()) left += taskQueue.inboxSize(region);
        }
        if (left > 0) {
            org.slf4j.LoggerFactory.getLogger("multiforge.scheduler")
                    .warn("{} rerouted task(s) still queued after 16 drain passes at stop; they are dropped", left);
        }
        return total;
    }

    @Override
    public void close() {
        OwnershipEnforcer.unbindPositionRouter();
        net.multiforge.runtime.event.SerialDispatchProbes.unbindWorldLookup();
        AutoCloseable pinSub = this.pinSubscription;
        if (pinSub != null) {
            try {
                pinSub.close();
            } catch (Exception ignored) {
                // CopyOnWriteArrayList removal; cannot fail
            }
        }
        scheduler.close();
        delayedExec.shutdownNow();
        asyncExec.shutdownNow();
        try {
            delayedExec.awaitTermination(1, TimeUnit.SECONDS);
            asyncExec.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Deliberately not shutting down DiagExecutorHolder.EXEC here: it is
        // a JVM-wide singleton shared by every host instance (see its
        // javadoc), and other still-live hosts' diagnostics emitters may
        // depend on it. Individual scheduleGlobal callers own their own
        // ScheduledFuture and are responsible for cancelling it.
    }

    // ============================================================
    // Diagnostics emitter scheduling (Track C1 / M6)
    // ============================================================

    /**
     * Lazily-initialised, JVM-wide singleton executor backing {@link
     * #scheduleGlobal}. A single daemon thread is enough: the {@code
     * net.multiforge.runtime.diagnostics.emitters} producers this
     * drives (region snapshots, heatmaps, heartbeats) are cheap,
     * non-blocking, 4&nbsp;Hz jobs that read already-published
     * snapshot state — see CLAUDE.md rule 4 and
     * {@code docs/design/client-debug-protocol.md} §3. Kept as a
     * nested holder class (not a field) so the thread is never created
     * unless some emitter actually calls {@link #scheduleGlobal}.
     */
    private static final class DiagExecutorHolder {
        private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mf-diag-emitters");
            t.setDaemon(true);
            return t;
        });

        private DiagExecutorHolder() {}
    }

    /**
     * Schedule {@code task} to run at a fixed rate on the shared {@code
     * mf-diag-emitters} daemon thread, starting immediately. Used
     * exclusively by {@code net.multiforge.runtime.diagnostics.emitters}
     * producers to drive their 4&nbsp;Hz (250&nbsp;ms) cadence per
     * {@code docs/design/client-debug-protocol.md} §3 — never by
     * gameplay code, and never on a region worker thread.
     *
     * <p>{@code task} is wrapped so a thrown {@link RuntimeException}
     * never kills the shared scheduled-executor thread (which would
     * silently stop every other registered emitter): the exception is
     * caught, counted via {@link ProbeRegistry}, and rate-limit logged
     * via {@link ViolationLogger} instead (CLAUDE.md rule 5).
     *
     * @param task the periodic job; must not block (CLAUDE.md rule 4)
     * @param periodMillis must be {@code > 0}
     * @return a handle the caller uses to cancel the schedule
     */
    public ScheduledFuture<?> scheduleGlobal(Runnable task, long periodMillis) {
        Objects.requireNonNull(task, "task");
        if (periodMillis <= 0) throw new IllegalArgumentException("periodMillis must be > 0: " + periodMillis);
        return DiagExecutorHolder.EXEC.scheduleAtFixedRate(
                () -> {
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        ProbeRegistry.bump("diag-emitter.failure");
                        ViolationLogger.warn(
                                "MultiThreadedSchedulerHost.scheduleGlobal",
                                "diagnostics emitter task threw: "
                                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                },
                0L,
                periodMillis,
                TimeUnit.MILLISECONDS);
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
            } catch (Throwable t) {
                // A throw escaping here would be swallowed by the executor's future and,
                // for a repeating task, silently end it. Log it; a repeating task keeps running.
                org.slf4j.LoggerFactory.getLogger("multiforge.scheduler").error("async task {} threw", handle, t);
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
}
