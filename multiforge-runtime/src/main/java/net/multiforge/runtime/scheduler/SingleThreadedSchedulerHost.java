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
import net.multiforge.runtime.ownership.Domain;
import net.multiforge.runtime.ownership.OwnerToken;

/**
 * M1 reference implementation of {@link SchedulerHost}. Runs every
 * region/entity/global task on a single "tick" executor (matching
 * vanilla NeoForge's single-threaded server semantics); runs async
 * tasks on a small pool.
 *
 * <p>M2 replaces this with a region-per-worker pool. The API surface
 * stays identical.
 */
public final class SingleThreadedSchedulerHost implements SchedulerHost {

    /** Milliseconds per Minecraft tick. */
    private static final long TICK_MS = 50L;

    private final ScheduledExecutorService tickExec;
    private final ScheduledExecutorService asyncExec;
    private final ConcurrentMap<ModIdentifier, ConcurrentMap<SchedulerTaskImpl, Boolean>> asyncByMod =
            new ConcurrentHashMap<>();

    public SingleThreadedSchedulerHost() {
        AtomicInteger asyncSeq = new AtomicInteger();
        this.tickExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "multiforge-tick-worker");
            t.setDaemon(true);
            return t;
        });
        this.asyncExec = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "multiforge-async-" + asyncSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Install this host as the singleton {@link ServerDomains} binding. */
    public void install() {
        ServerDomains.install(this);
    }

    /** Test/shutdown helper. */
    public void shutdown() {
        tickExec.shutdownNow();
        asyncExec.shutdownNow();
    }

    @Override
    public RegionDomain region(WorldRef world, ChunkPos pos) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");
        return new RegionDomainImpl(pos);
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

    // ---- shared scheduling primitives ----

    private ScheduledTask scheduleTick(
            ModIdentifier mod, Consumer<ScheduledTask> body, long delayTicks, OwnerToken tok) {
        SchedulerTaskImpl task = new SchedulerTaskImpl(mod, false);
        var future = tickExec.schedule(() -> runOnce(task, body, tok), delayTicks * TICK_MS, TimeUnit.MILLISECONDS);
        task.bindFuture(future);
        return task;
    }

    private ScheduledTask scheduleTickRepeating(
            ModIdentifier mod, Consumer<ScheduledTask> body, long initialTicks, long periodTicks, OwnerToken tok) {
        SchedulerTaskImpl task = new SchedulerTaskImpl(mod, true);
        var future = tickExec.scheduleAtFixedRate(
                () -> runRepeating(task, body, tok),
                Math.max(0, initialTicks) * TICK_MS,
                Math.max(1, periodTicks) * TICK_MS,
                TimeUnit.MILLISECONDS);
        task.bindFuture(future);
        return task;
    }

    private void runOnce(SchedulerTaskImpl task, Consumer<ScheduledTask> body, OwnerToken tok) {
        if (!task.enterExecuting()) return; // already cancelled
        try {
            OwnerToken.runAs(tok, () -> body.accept(task));
        } finally {
            task.markFinished();
        }
    }

    private void runRepeating(SchedulerTaskImpl task, Consumer<ScheduledTask> body, OwnerToken tok) {
        if (!task.enterExecuting()) return; // cancelled
        try {
            OwnerToken.runAs(tok, () -> body.accept(task));
        } finally {
            // If cancel() was called while we were running, state is now CANCELLED_RUNNING and
            // the Future was cancelled; scheduleAtFixedRate will not re-invoke. Otherwise bounce
            // back to IDLE so the next iteration transition succeeds.
            task.prepareNextIteration();
        }
    }

    // ---- domain implementations ----

    private final class RegionDomainImpl implements RegionDomain {
        private final OwnerToken tok;

        RegionDomainImpl(ChunkPos pos) {
            // M1 has no real regions; every region maps to the single tick worker,
            // but we still stamp OwnerToken.REGION so assertion sites see a plausible domain.
            long syntheticRegionId = ((long) pos.x() << 32) ^ (pos.z() & 0xFFFFFFFFL);
            this.tok = OwnerToken.forRegion(syntheticRegionId);
        }

        @Override
        public ScheduledTask execute(ModIdentifier mod, Runnable task) {
            return scheduleTick(mod, ignored -> task.run(), 0L, tok);
        }

        @Override
        public ScheduledTask run(ModIdentifier mod, Consumer<ScheduledTask> task) {
            return scheduleTick(mod, task, 0L, tok);
        }

        @Override
        public ScheduledTask runDelayed(ModIdentifier mod, Consumer<ScheduledTask> task, long delayTicks) {
            return scheduleTick(mod, task, delayTicks, tok);
        }

        @Override
        public ScheduledTask runAtFixedRate(
                ModIdentifier mod, Consumer<ScheduledTask> task, long initialTicks, long periodTicks) {
            return scheduleTickRepeating(mod, task, initialTicks, periodTicks, tok);
        }
    }

    private final class GlobalDomainImpl implements GlobalDomain {
        @Override
        public ScheduledTask execute(ModIdentifier mod, Runnable task) {
            return scheduleTick(mod, ignored -> task.run(), 0L, OwnerToken.GLOBAL);
        }

        @Override
        public ScheduledTask run(ModIdentifier mod, Consumer<ScheduledTask> task) {
            return scheduleTick(mod, task, 0L, OwnerToken.GLOBAL);
        }

        @Override
        public ScheduledTask runDelayed(ModIdentifier mod, Consumer<ScheduledTask> task, long delayTicks) {
            return scheduleTick(mod, task, delayTicks, OwnerToken.GLOBAL);
        }

        @Override
        public ScheduledTask runAtFixedRate(
                ModIdentifier mod, Consumer<ScheduledTask> task, long initialTicks, long periodTicks) {
            return scheduleTickRepeating(mod, task, initialTicks, periodTicks, OwnerToken.GLOBAL);
        }
    }

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
            return scheduleTick(mod, wrap(h -> task.run(), retired), 0L, currentEntityToken());
        }

        @Override
        public ScheduledTask run(ModIdentifier mod, Consumer<ScheduledTask> task, Runnable retired) {
            return scheduleTick(mod, wrap(task, retired), 0L, currentEntityToken());
        }

        @Override
        public ScheduledTask runDelayed(
                ModIdentifier mod, Consumer<ScheduledTask> task, Runnable retired, long delayTicks) {
            return scheduleTick(mod, wrap(task, retired), delayTicks, currentEntityToken());
        }

        @Override
        public ScheduledTask runAtFixedRate(
                ModIdentifier mod,
                Consumer<ScheduledTask> task,
                Runnable retired,
                long initialTicks,
                long periodTicks) {
            return scheduleTickRepeating(mod, wrap(task, retired), initialTicks, periodTicks, currentEntityToken());
        }

        private OwnerToken currentEntityToken() {
            ChunkPos p = entity.chunkPos();
            long syntheticRegionId = ((long) p.x() << 32) ^ (p.z() & 0xFFFFFFFFL);
            return new OwnerToken(Domain.ENTITY, syntheticRegionId);
        }
    }

    private final class AsyncDomainImpl implements AsyncDomain {
        @Override
        public ScheduledTask runNow(ModIdentifier mod, Consumer<ScheduledTask> task) {
            return scheduleAsync(mod, task, 0L, TimeUnit.MILLISECONDS);
        }

        @Override
        public ScheduledTask runDelayed(ModIdentifier mod, Consumer<ScheduledTask> task, long delay, TimeUnit unit) {
            return scheduleAsync(mod, task, delay, unit);
        }

        @Override
        public ScheduledTask runAtFixedRate(
                ModIdentifier mod, Consumer<ScheduledTask> task, long initial, long period, TimeUnit unit) {
            SchedulerTaskImpl handle = new SchedulerTaskImpl(mod, true);
            track(mod, handle);
            var future = asyncExec.scheduleAtFixedRate(
                    () -> runAsyncIteration(handle, task, true),
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
            int cancelled = 0;
            for (SchedulerTaskImpl t : set.keySet()) {
                if (t.cancel()) cancelled++;
            }
            return cancelled;
        }

        private ScheduledTask scheduleAsync(
                ModIdentifier mod, Consumer<ScheduledTask> body, long delay, TimeUnit unit) {
            SchedulerTaskImpl handle = new SchedulerTaskImpl(mod, false);
            track(mod, handle);
            var future = asyncExec.schedule(
                    () -> runAsyncIteration(handle, body, false), unit.toMillis(delay), TimeUnit.MILLISECONDS);
            handle.bindFuture(future);
            return handle;
        }

        private void runAsyncIteration(SchedulerTaskImpl handle, Consumer<ScheduledTask> body, boolean repeating) {
            if (!handle.enterExecuting()) return;
            try {
                OwnerToken.runAs(OwnerToken.ASYNC, () -> body.accept(handle));
            } finally {
                if (repeating) handle.prepareNextIteration();
                else handle.markFinished();
            }
        }

        private void track(ModIdentifier mod, SchedulerTaskImpl handle) {
            asyncByMod.computeIfAbsent(mod, k -> new ConcurrentHashMap<>()).put(handle, Boolean.TRUE);
        }
    }
}
