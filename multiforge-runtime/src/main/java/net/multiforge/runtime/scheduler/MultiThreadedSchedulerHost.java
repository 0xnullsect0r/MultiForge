/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.scheduler;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import net.multiforge.runtime.chunk.Ticket;
import net.multiforge.runtime.chunk.TicketType;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.ownership.OwnerToken;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionTickBody;
import net.multiforge.runtime.region.RegionizedTaskQueue;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.region.TickRegionScheduler;

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

    private final MultiForgeConfig config;
    private final ConcurrentMap<String, ThreadedRegionizer> regionizers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ChunkHolderManager> chunkManagers = new ConcurrentHashMap<>();
    private final ChunkTaskScheduler chunkTaskScheduler;
    private final Function<WorldRef, ThreadedRegionizer> regionizerFactory;
    private final RegionizedTaskQueue taskQueue;
    private final TickRegionScheduler scheduler;

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
        this.taskQueue = new RegionizedTaskQueue((w, x, z) -> {
            // Use regionizerForOrNull here (not regionizerFor): OwnerLookup's
            // contract is "return null for unloaded/unknown chunks", and
            // auto-creating a regionizer on lookup lets a typoed WorldRef
            // grow the map unboundedly (finding #11 of the /67 review).
            ThreadedRegionizer r = regionizerForOrNull(w);
            return r == null ? null : r.regionAtChunk(x, z);
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
            return r;
        });
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
        return chunkManagers.computeIfAbsent(world.dimensionId(), id -> new ChunkHolderManager(world));
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
