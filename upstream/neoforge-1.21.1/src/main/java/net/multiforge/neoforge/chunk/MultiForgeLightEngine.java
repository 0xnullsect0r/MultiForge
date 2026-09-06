/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.neoforge.chunk;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.InstanceRegistry;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.region.RegionizedTaskQueue;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import org.jetbrains.annotations.Nullable;

/**
 * M9 Phase 4 task 4.5b — fork facade replacing the single-mailbox
 * {@link ThreadedLevelLightEngine} with per-region light propagation
 * routed through {@link RegionizedTaskQueue}. Design frozen at
 * {@code docs/design/multiforge-lightengine.md}.
 *
 * <p>Every mutating call (checkBlock, updateSectionStatus,
 * propagateLightSources, setLightEnabled, queueSectionData, retainData,
 * initializeLight, lightChunk, updateChunkStatus) is queued as a chunk
 * task on the target chunk's owning region worker via
 * {@link RegionizedTaskQueue#queueChunkTask(WorldRef, int, int, Runnable)}.
 * The task body invokes the corresponding {@code super} method — so
 * block/sky storage updates happen in the region worker's serial
 * context, matching Vanilla's per-chunk mailbox invariant. Reads (see
 * {@code LevelLightEngine#getRawBrightness}, {@code getLightData} via
 * {@link LightChunkGetter}, {@code getBlockLight} / {@code getSkyLight})
 * are inherited verbatim — the superclass storage
 * ({@code LayerLightSectionStorage} + block/sky engines) is publish-once
 * and safe from any thread.
 *
 * <p>Cross-region light propagation is handled by design §6: when a
 * region worker running {@code super.checkBlock} triggers a neighbour
 * update that lands back in this override for a chunk in a different
 * region, {@link RegionizedTaskQueue#queueChunkTask} resolves the
 * neighbour's owning region under the regionizer read lock and hops the
 * task onto that region's inbox. Per-chunk light updates are FIFO
 * within a region; across regions there is no total order, but
 * {@code BlockLightEngine.checkNeighborsAfterUpdate} is idempotent per
 * position — no correctness issue.
 *
 * <p>The Vanilla super's mailbox / sorter fields
 * ({@code taskMailbox}, {@code sorterMailbox}, {@code lightTasks},
 * {@code scheduled}) are inherited but never used by this class. The
 * constructor forwards a pair of {@linkplain #NO_OP_TASK_MAILBOX no-op
 * handles} so reflective mods that probe those fields resolve without
 * NPE. {@link #runLightUpdates()} is a NO-OP — Phase 5 (M11) wires
 * per-region drain from
 * {@code PhasedRegionTickBody.Phase.REGION_EVENTS}, driven off a region
 * worker rather than the caller thread.
 *
 * <p><b>Boundary with Phase 4 task 4.5c:</b> this task ({@code 4.5b})
 * creates the facade only. Task 4.5c will patch Vanilla
 * {@link net.minecraft.server.level.ChunkMap ChunkMap} to construct
 * {@code MultiForgeLightEngine} in place of {@code new
 * ThreadedLevelLightEngine(...)} and patch
 * {@link ThreadedLevelLightEngine} to route {@code super.<mutator>}
 * calls inline rather than through the (no-op) sorter mailbox — closing
 * the invocation-site loop so the delegated
 * {@code super.checkBlock(...)} inside each region task body actually
 * propagates light. Until 4.5c lands, this class is compile-clean but
 * the runtime path is inert; no Vanilla behaviour changes.
 *
 * <p>Constructor signature diverges from Vanilla's 5-arg shape (the
 * mailbox+sorter slots are replaced by
 * {@link MultiThreadedSchedulerHost} + {@link WorldRef}). Design §3
 * mandates the swap so the routing target is bound at construction
 * rather than resolved lazily on every call; task 4.5c's ChunkMap
 * patch will pass {@code MultiForgeRegionizedRuntime.current()} and
 * {@code RegionizedTickCoordinator.asWorldRef(this.level)}. The super
 * call still uses the Vanilla 5-arg shape with no-op mailbox handles,
 * preserving reflective-mod compatibility.
 */
public final class MultiForgeLightEngine extends ThreadedLevelLightEngine {
    /**
     * No-op mailbox handed to super so the inherited {@code taskMailbox}
     * field resolves for any reflective mod that probes it. The
     * executor swallows every {@code Runnable} submitted; we never
     * enqueue onto this mailbox from this class.
     */
    private static final ProcessorMailbox<Runnable> NO_OP_TASK_MAILBOX = ProcessorMailbox.create(r -> {}, "multiforge-light-noop-mailbox");

    /**
     * Task 4.5c — weak identity registry that lets the Vanilla
     * {@link ThreadedLevelLightEngine} observation hunks resolve the
     * per-instance {@code MultiForgeLightEngine} facade without holding
     * the facade alive past ChunkMap teardown. Keyed by the vanilla
     * engine identity ({@code WeakHashMap} compares by {@code equals},
     * which for the un-overriding {@code ThreadedLevelLightEngine}
     * defaults to identity), value is a {@link WeakReference} so the
     * value chain does not strong-hold the key (facade is-a key: this
     * class registers itself, under itself, so an {@code InstanceRegistry
     * <ThreadedLevelLightEngine, MultiForgeLightEngine>} storing the
     * facade directly as the strong value would pin the very key it is
     * supposed to let go — the {@link WeakReference} indirection is load
     * -bearing, not incidental, and must survive the round-5 H6 swap).
     * The hunks call {@link #of} once per invocation and no hot path
     * lives here.
     *
     * <p>Round-5 H6: backed by {@link InstanceRegistry#weak()} instead of
     * a hand-rolled {@code Collections.synchronizedMap(new
     * WeakHashMap<>())} — same rationale as {@link MultiForgeDistanceManager
     * #INSTANCE_REGISTRY}. {@link InstanceRegistry#snapshot()} gives any
     * future all-engines walk a safe-iteration path this class doesn't
     * currently need.
     */
    private static final InstanceRegistry<ThreadedLevelLightEngine, WeakReference<MultiForgeLightEngine>> REGISTRY = InstanceRegistry.weak();

    /**
     * No-op sorter handle handed to super so the inherited
     * {@code sorterMailbox} field resolves for reflective mods. Every
     * {@code tell(msg)} drops the message — this class never enqueues
     * onto the sorter path.
     */
    private static final ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> NO_OP_SORTER = ProcessorHandle.of("multiforge-light-noop-sorter", msg -> {});

    /**
     * Nullable — resolved at construction from
     * {@link MultiForgeRegionizedRuntime#current()} when possible.
     * When {@code null} (bootstrap / unit test without an installed
     * runtime), every mutating override falls back to inline invocation
     * of {@code super}, preserving Vanilla behaviour until the runtime
     * comes up.
     */
    @Nullable
    private final MultiThreadedSchedulerHost host;

    private final WorldRef worldRef;

    public MultiForgeLightEngine(
            LightChunkGetter lightChunkGetter,
            ChunkMap chunkMap,
            boolean skyLight,
            @Nullable MultiThreadedSchedulerHost host,
            WorldRef worldRef) {
        super(lightChunkGetter, chunkMap, skyLight, NO_OP_TASK_MAILBOX, NO_OP_SORTER);
        this.host = host;
        this.worldRef = Objects.requireNonNull(worldRef, "worldRef");
        // Task 4.5c — publish this facade under the vanilla engine
        // identity so Vanilla-side observation hunks can locate it via
        // {@link #of}.
        REGISTRY.register(this, new WeakReference<>(this));
    }

    /**
     * Task 4.5c — unregister on close so the identity slot is freed
     * when a ChunkMap tears down. The registry is weak-keyed so this
     * is belt-and-braces; the explicit remove keeps the visible-size
     * bounded for {@code /forge probes} readouts.
     */
    @Override
    public void close() {
        REGISTRY.unregister(this);
        super.close();
    }

    /**
     * Task 4.5c — look up the {@code MultiForgeLightEngine} facade
     * registered against {@code engine}. Returns {@link Optional#empty()}
     * when either {@code engine} is {@code null}, the vanilla engine
     * is not a MultiForge facade (e.g. during bootstrap before
     * {@code ChunkMap} rewires the construction site, or in a unit
     * test that builds a bare {@link ThreadedLevelLightEngine}), or the
     * facade has already been GC'd. Never throws.
     */
    public static Optional<MultiForgeLightEngine> of(@Nullable ThreadedLevelLightEngine engine) {
        if (engine == null) {
            return Optional.empty();
        }
        Optional<WeakReference<MultiForgeLightEngine>> ref = REGISTRY.of(engine);
        if (ref.isEmpty()) {
            return Optional.empty();
        }
        MultiForgeLightEngine facade = ref.get().get();
        return facade == null ? Optional.empty() : Optional.of(facade);
    }

    // -----------------------------------------------------------------
    // Task 4.5c — observation hooks driven by Vanilla
    // ThreadedLevelLightEngine patch hunks. Each is a no-op unless the
    // vanilla engine has a MultiForge facade registered; each bumps a
    // probe counter and returns without throwing or blocking so the
    // Vanilla call site is guaranteed side-effect-safe.
    // -----------------------------------------------------------------

    /** Task 4.5c observer for {@link ThreadedLevelLightEngine#checkBlock}. */
    public static void observeCheckBlock(@Nullable ThreadedLevelLightEngine engine, BlockPos pos) {
        if (of(engine).isEmpty()) {
            return;
        }
        ProbeRegistry.bump("mflightengine.observe.checkBlock");
    }

    /** Task 4.5c observer for {@link ThreadedLevelLightEngine#updateChunkStatus}. */
    public static void observeUpdateChunkStatus(@Nullable ThreadedLevelLightEngine engine, ChunkPos pos) {
        if (of(engine).isEmpty()) {
            return;
        }
        ProbeRegistry.bump("mflightengine.observe.updateChunkStatus");
    }

    /** Task 4.5c observer for {@link ThreadedLevelLightEngine#updateSectionStatus}. */
    public static void observeUpdateSectionStatus(
            @Nullable ThreadedLevelLightEngine engine, SectionPos sectionPos, boolean isEmpty) {
        if (of(engine).isEmpty()) {
            return;
        }
        ProbeRegistry.bump("mflightengine.observe.updateSectionStatus");
    }

    /** Task 4.5c observer for {@link ThreadedLevelLightEngine#retainData}. */
    public static void observeRetainData(@Nullable ThreadedLevelLightEngine engine, ChunkPos pos, boolean retain) {
        if (of(engine).isEmpty()) {
            return;
        }
        ProbeRegistry.bump("mflightengine.observe.retainData");
    }

    /** Task 4.5c observer for {@link ThreadedLevelLightEngine#queueSectionData}. */
    public static void observeQueueSectionData(
            @Nullable ThreadedLevelLightEngine engine, LightLayer layer, SectionPos sectionPos) {
        if (of(engine).isEmpty()) {
            return;
        }
        ProbeRegistry.bump("mflightengine.observe.queueSectionData");
    }

    /**
     * Task 4.5c observer for {@link ThreadedLevelLightEngine#waitForPendingTasks}
     * — start marker fired at method entry.
     */
    public static void observeWaitForTasksStart(@Nullable ThreadedLevelLightEngine engine) {
        if (of(engine).isEmpty()) {
            return;
        }
        ProbeRegistry.bump("mflightengine.observe.waitForTasks.start");
    }

    /**
     * Task 4.5c observer for {@link ThreadedLevelLightEngine#waitForPendingTasks}
     * — end marker fired when the returned future completes.
     */
    public static void observeWaitForTasksEnd(@Nullable ThreadedLevelLightEngine engine) {
        if (of(engine).isEmpty()) {
            return;
        }
        ProbeRegistry.bump("mflightengine.observe.waitForTasks.end");
    }

    // -----------------------------------------------------------------
    // Delegation helpers
    // -----------------------------------------------------------------

    /**
     * Lazy host resolution: prefer the reference bound at construction,
     * otherwise fall back to
     * {@link MultiForgeRegionizedRuntime#current()} so a runtime that
     * came up after this engine was built is still picked up. Returns
     * {@code null} only during bootstrap before the runtime installs
     * (or in unit tests without a runtime), in which case callers run
     * the delegated body inline as a Vanilla-parity fallback.
     */
    @Nullable
    private MultiThreadedSchedulerHost currentHost() {
        return host != null ? host : MultiForgeRegionizedRuntime.current();
    }

    /**
     * Route {@code task} to the region owning {@code (chunkX, chunkZ)}
     * in this engine's world. Falls back to inline execution when the
     * runtime is not yet installed (bootstrap) — the caller's context
     * is then equivalent to Vanilla's single-mailbox thread and no
     * cross-region hazard can exist because no regions exist.
     */
    private void routeChunkTask(int chunkX, int chunkZ, Runnable task) {
        MultiThreadedSchedulerHost h = currentHost();
        if (h == null) {
            // Bootstrap: no runtime, no regions — behave like Vanilla.
            task.run();
            return;
        }
        h.taskQueue().queueChunkTask(worldRef, chunkX, chunkZ, task);
    }

    // -----------------------------------------------------------------
    // Mutating overrides — design §4
    // -----------------------------------------------------------------

    /** Design §4.1 — route per-block light-update onto the owning region. */
    @Override
    public void checkBlock(BlockPos pos) {
        BlockPos immutable = pos.immutable();
        int chunkX = SectionPos.blockToSectionCoord(immutable.getX());
        int chunkZ = SectionPos.blockToSectionCoord(immutable.getZ());
        routeChunkTask(chunkX, chunkZ, () -> super.checkBlock(immutable));
    }

    /** Design §4.2 — route section-status update onto the owning region. */
    @Override
    public void updateSectionStatus(SectionPos pos, boolean isEmpty) {
        routeChunkTask(pos.x(), pos.z(), () -> super.updateSectionStatus(pos, isEmpty));
    }

    /** Design §4.3 — route light-source propagation onto the owning region. */
    @Override
    public void propagateLightSources(ChunkPos pos) {
        routeChunkTask(pos.x, pos.z, () -> super.propagateLightSources(pos));
    }

    /** Design §4.4 — route light-enable toggle onto the owning region. */
    @Override
    public void setLightEnabled(ChunkPos pos, boolean enabled) {
        routeChunkTask(pos.x, pos.z, () -> super.setLightEnabled(pos, enabled));
    }

    /** Design §4.5 — route section-data replacement onto the owning region. */
    @Override
    public void queueSectionData(LightLayer layer, SectionPos pos, @Nullable DataLayer data) {
        routeChunkTask(pos.x(), pos.z(), () -> super.queueSectionData(layer, pos, data));
    }

    /** Design §4.6 — route retain-data toggle onto the owning region. */
    @Override
    public void retainData(ChunkPos pos, boolean retain) {
        routeChunkTask(pos.x, pos.z, () -> super.retainData(pos, retain));
    }

    /**
     * Design §4.7 — collapse Vanilla's PRE/POST split into a single
     * region-worker task. Under Vanilla the PRE pass seeds
     * {@code updateSectionStatus} for every non-air section and the POST
     * pass follows with {@code setLightEnabled} + {@code retainData} on a
     * batch-flush boundary. Under MultiForge the region worker is
     * already single-threaded per chunk, so no batch-flush interleave
     * exists and both passes run inline in FIFO order on the owning
     * region.
     */
    @Override
    public CompletableFuture<ChunkAccess> initializeLight(ChunkAccess chunk, boolean lit) {
        ChunkPos pos = chunk.getPos();
        CompletableFuture<ChunkAccess> future = new CompletableFuture<>();
        routeChunkTask(pos.x, pos.z, () -> {
            try {
                LevelChunkSection[] sections = chunk.getSections();
                int sectionCount = chunk.getSectionsCount();
                for (int i = 0; i < sectionCount; i++) {
                    LevelChunkSection section = sections[i];
                    if (!section.hasOnlyAir()) {
                        int sy = this.levelHeightAccessor.getSectionYFromSectionIndex(i);
                        super.updateSectionStatus(SectionPos.of(pos, sy), false);
                    }
                }
                super.setLightEnabled(pos, lit);
                super.retainData(pos, false);
                future.complete(chunk);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Design §4.8 — clear {@code setLightCorrect} on the caller thread
     * (matches Vanilla; some callers rely on the flag flipping before
     * this method returns), then queue the propagate + flip-back onto
     * the owning region worker.
     */
    @Override
    public CompletableFuture<ChunkAccess> lightChunk(ChunkAccess chunk, boolean lit) {
        ChunkPos pos = chunk.getPos();
        chunk.setLightCorrect(false);
        CompletableFuture<ChunkAccess> future = new CompletableFuture<>();
        routeChunkTask(pos.x, pos.z, () -> {
            try {
                if (!lit) {
                    super.propagateLightSources(pos);
                }
                chunk.setLightCorrect(true);
                future.complete(chunk);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Design §4.9 — the multi-step clear-and-reset dance runs as one
     * task on the owning region worker so no other light task on that
     * chunk can interleave a partial state.
     */
    @Override
    protected void updateChunkStatus(ChunkPos pos) {
        routeChunkTask(pos.x, pos.z, () -> {
            super.retainData(pos, false);
            super.setLightEnabled(pos, false);
            int minSection = this.getMinLightSection();
            int maxSection = this.getMaxLightSection();
            for (int y = minSection; y < maxSection; y++) {
                super.queueSectionData(LightLayer.BLOCK, SectionPos.of(pos, y), null);
                super.queueSectionData(LightLayer.SKY, SectionPos.of(pos, y), null);
            }
            int minWorldSection = this.levelHeightAccessor.getMinSection();
            int maxWorldSection = this.levelHeightAccessor.getMaxSection();
            for (int y = minWorldSection; y < maxWorldSection; y++) {
                super.updateSectionStatus(SectionPos.of(pos, y), true);
            }
        });
    }

    /**
     * Design §4.10 / task 4.5b — NO-OP. Vanilla throws
     * {@code UnsupportedOperationException} to make caller-thread drain
     * an obvious bug; MultiForge instead relies on Phase 5 (M11) wiring
     * per-region drain from
     * {@code PhasedRegionTickBody.Phase.REGION_EVENTS} in the tick
     * pipeline — that call path always lands on a region worker, so
     * "the caller thread is wrong" is not a class of bug we can catch
     * here. Returning zero indicates "no light propagated by this call"
     * which is truthful for a NO-OP.
     */
    @Override
    public int runLightUpdates() {
        // Phase 5 (M11) will wire real per-region drain in tick phase 5
        // (REGION_EVENTS). See docs/design/multiforge-lightengine.md §4.10.
        return 0;
    }

    /**
     * Design §4.11 — NO-OP. Under Vanilla,
     * {@code tryScheduleUpdate} posts the batch drain onto
     * {@code taskMailbox}; under MultiForge each region task runs
     * inline on its worker, so there is nothing to schedule.
     */
    @Override
    public void tryScheduleUpdate() {
        // Intentionally empty — Phase 5 wires per-region drain instead.
    }

    /**
     * Design §4.12 — barrier for chunk-send ordering. Because
     * {@link RegionizedTaskQueue#queueChunkTask} is FIFO within a region,
     * queuing an empty completion task after every prior light task on
     * {@code (chunkX, chunkZ)} guarantees the returned future completes
     * only once every previously queued light task on that chunk has
     * finished — preserving Vanilla's happens-before contract for
     * {@code PlayerChunkSender}.
     */
    @Override
    public CompletableFuture<?> waitForPendingTasks(int chunkX, int chunkZ) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        routeChunkTask(chunkX, chunkZ, () -> future.complete(null));
        return future;
    }
}
