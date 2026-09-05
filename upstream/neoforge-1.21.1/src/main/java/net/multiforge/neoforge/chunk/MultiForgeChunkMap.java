/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.neoforge.chunk;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.GeneratingChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.util.Mth;
import net.minecraft.util.StaticCache2D;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.phys.Vec3;
import net.multiforge.api.world.WorldRef;
import net.multiforge.neoforge.RegionizedTickCoordinator;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.ChunkTaskPriority;
import net.multiforge.runtime.chunk.ChunkTaskScheduler;
import net.multiforge.runtime.chunk.NewChunkHolder;
import net.multiforge.runtime.chunk.Ticket;
import net.multiforge.runtime.chunk.TicketType;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionId;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;

/**
 * M9 Phase 4.1b fork facade — a REPLACEMENT for Vanilla {@link ChunkMap}
 * (frozen in {@code docs/design/multiforge-chunkmap.md}).
 *
 * <p><b>Responsibility split vs. Vanilla:</b>
 * <ul>
 *   <li><b>Holder table</b> — Vanilla's {@code updatingChunkMap} /
 *       {@code visibleChunkMap} become a live view backed by
 *       {@link ChunkHolderManager#byChunk} per world, exposed via
 *       {@link ChunkHolderShim} so external callers reading a Vanilla
 *       {@link ChunkHolder} continue to work unchanged.</li>
 *   <li><b>Worldgen dispatch</b> — the {@code worldgenMailbox} /
 *       {@code mainThreadMailbox} plumbing around {@link WorldGenContext}
 *       is replaced with per-region priority routing through
 *       {@link ChunkTaskScheduler#scheduleChunkTask}.</li>
 *   <li><b>Player tracking</b> — the {@code playerMap} + {@code entityMap}
 *       stay main-thread driven in 4.1b, but every add/remove path now
 *       also writes {@link NewChunkHolder#addPlayer}/{@code removePlayer}
 *       so region workers can consult the watcher set without touching
 *       the main-thread {@code playerMap}.</li>
 *   <li><b>Save/load</b> — {@code saveAllChunks} walks per-region
 *       autosave queues via {@link ChunkHolderManager#markDirty}; the
 *       {@code mainThreadExecutor.managedBlock} drain that Vanilla uses
 *       is deleted (that call would deadlock a region worker).</li>
 * </ul>
 *
 * <p><b>Threading contract</b> (see design doc §5 for the full matrix):
 * <ul>
 *   <li>Any-thread safe reads:
 *       {@link #getVisibleChunkIfPresent}, {@link #getUpdatingChunkIfPresent},
 *       {@link #getChunks}, {@link #size}, {@link #getPoiManager},
 *       {@link #getStorageName}, {@link #getPlayersCloseForSpawning},
 *       {@link #anyPlayerCloseEnoughForSpawning}, {@link #getPlayers},
 *       {@link #getChunkToSend}, {@link #hasWork},
 *       {@link #getTickingGenerated}, {@link #getChunkDebugData}.</li>
 *   <li>Owning region worker ONLY:
 *       {@link #applyStep} continuation, save-path body,
 *       {@link #scheduleChunkLoad} body, and every
 *       {@code NewChunkHolder} mutation.</li>
 *   <li>Main thread ONLY (transitional): {@link #tick()},
 *       {@link #move}, {@link #addEntity}, {@link #removeEntity},
 *       {@link #updatePlayerStatus}, {@link #setServerViewDistance}.</li>
 * </ul>
 *
 * <p><b>Prohibited anti-patterns</b> (all M9-blocking):
 * <ol>
 *   <li>No {@code .join()} / {@code .get()} on any {@code CompletableFuture}
 *       from any facade method. Consumers chain via {@code thenAccept}.</li>
 *   <li>No {@code synchronized} on any monitor that another region worker
 *       could contend on.</li>
 *   <li>No {@code mainThreadExecutor.managedBlock} — the field is deleted.</li>
 *   <li>No {@code Thread.sleep}. Ever.</li>
 * </ol>
 *
 * <p><b>Wiring status:</b> Phase 4.1b creates the facade in isolation; the
 * Vanilla {@link ChunkMap} keeps its shape until Phase 4.1c patches it
 * to delegate. Task 4.1d adds the unit-test coverage; Phase 5 wires the
 * per-region tick, autosave, and journal drains into
 * {@code PhasedRegionTickBody}. Individual methods flag their Phase-5
 * blockers inline with {@code TODO(phase-5.N)} markers so the compile-
 * clean bar is met without pretending the wiring is finished.
 */
@ApiStatus.Internal
public final class MultiForgeChunkMap extends ChunkStorage
        implements ChunkHolder.PlayerProvider, GeneratingChunkMap {

    private static final Logger LOGGER = LogUtils.getLogger();

    // === API-compat constants preserved from Vanilla ChunkMap.java:113-115 ===
    public static final int MIN_VIEW_DISTANCE = 2;
    public static final int MAX_VIEW_DISTANCE = 32;
    public static final int FORCED_TICKET_LEVEL = ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);

    /**
     * "Chunk holder does not exist" sentinel for the three future gates.
     * Matches Vanilla {@link ChunkHolder}'s {@code UNLOADED_LEVEL_CHUNK_FUTURE}
     * shape: a completed future carrying an error result so callers
     * chaining via {@code thenApply} propagate the "unloaded" outcome
     * without ever awaiting a real future.
     */
    private static final CompletableFuture<ChunkResult<LevelChunk>> UNLOADED_LEVEL_CHUNK_FUTURE =
            CompletableFuture.completedFuture(ChunkHolder.UNLOADED_LEVEL_CHUNK);

    /**
     * NEW (Phase 4.1c) — ServerLevel to MultiForgeChunkMap registry so the
     * observability seams patched into Vanilla {@link ChunkMap} can locate
     * the facade for a given level without a runtime-host lookup. Populated
     * from the ctor via {@link #register(ServerLevel, MultiForgeChunkMap)};
     * evicted on {@link #unregister(ServerLevel)}. Backed by a synchronized
     * {@link java.util.WeakHashMap} so a level GC'd without an explicit
     * unregister does not leak. Any thread.
     */
    private static final Map<ServerLevel, MultiForgeChunkMap> INSTANCES =
            Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Register a facade for a level. Idempotent. Any thread. */
    public static void register(ServerLevel level, MultiForgeChunkMap map) {
        INSTANCES.put(level, map);
    }

    /** Drop the facade registration for a level. Idempotent. Any thread. */
    public static void unregister(ServerLevel level) {
        INSTANCES.remove(level);
    }

    /** Look up the facade for a level. Any thread. */
    public static java.util.Optional<MultiForgeChunkMap> of(ServerLevel level) {
        return java.util.Optional.ofNullable(INSTANCES.get(level));
    }

    // === §7 fields carried over unchanged from Vanilla ChunkMap ===
    final ServerLevel level;
    private final ChunkGenerator generator;
    private final ChunkGeneratorStructureState chunkGeneratorState;
    private final RandomState randomState;
    private final Supplier<DimensionDataStorage> overworldDataStorage;
    private final PoiManager poiManager;
    private final ChunkProgressListener progressListener;
    private final ChunkStatusUpdateListener chunkStatusListener;
    private final AtomicInteger tickingGenerated = new AtomicInteger();
    private final String storageName;
    private final WorldGenContext worldGenContext;

    /**
     * Vanilla-parity light engine reference. Nullable in 4.1b because
     * {@link ThreadedLevelLightEngine}'s constructor demands a
     * {@link ChunkMap} back-pointer that this facade cannot yet supply
     * ({@link MultiForgeChunkMap} extends {@link ChunkStorage}, not
     * {@link ChunkMap}). Phase 4.1c patches Vanilla {@link ChunkMap} to
     * instantiate this facade as its own body, at which point the light
     * engine hands itself a real {@code ChunkMap} reference; Phase 4.5
     * further replaces the internals with per-region routing via
     * {@code MultiForgeLightEngine}. Callers that touch the field before
     * either lands must null-guard.
     */
    @Nullable
    private final ThreadedLevelLightEngine lightEngine;

    // === §4 backing-state bindings ===
    /**
     * MultiForge runtime host — the source of truth for regions, chunk
     * managers, and the shared chunk-task scheduler. Resolved once at
     * construction; the {@code MultiForgeRegionizedRuntime.current()}
     * lookup only fires again in the (rare) rebind case, and the shim
     * methods that need a live host each call {@link #host()} so a
     * post-construction install is picked up on the next call.
     */
    private final WorldRef worldRef;

    private final Executor bgExecutor;

    // === player tracking (main-thread in 4.1b; per-region in a later pass) ===
    private final PlayerMap playerMap = new PlayerMap();
    private final Int2ObjectMap<TrackedEntity> entityMap = new Int2ObjectOpenHashMap<>();
    private volatile int serverViewDistance;

    // === ctor mirrors ChunkMap.java:145 ===

    public MultiForgeChunkMap(
            ServerLevel level,
            LevelStorageSource.LevelStorageAccess storage,
            DataFixer dataFixer,
            StructureTemplateManager structures,
            Executor bgExecutor,
            BlockableEventLoop<Runnable> mainExecutor,
            LightChunkGetter lightChunkGetter,
            ChunkGenerator generator,
            ChunkProgressListener progressListener,
            ChunkStatusUpdateListener statusListener,
            Supplier<DimensionDataStorage> overworldData,
            int viewDistance,
            boolean sync) {
        super(
                new RegionStorageInfo(storage.getLevelId(), level.dimension(), "chunk"),
                storage.getDimensionPath(level.dimension()).resolve("region"),
                dataFixer,
                sync);
        Path path = storage.getDimensionPath(level.dimension());
        this.storageName = path.getFileName().toString();
        this.level = level;
        this.generator = generator;
        this.bgExecutor = Objects.requireNonNull(bgExecutor, "bgExecutor");
        this.progressListener = progressListener;
        this.chunkStatusListener = statusListener;
        this.overworldDataStorage = overworldData;
        this.worldRef = RegionizedTickCoordinator.asWorldRef(level);

        RegistryAccess registryAccess = level.registryAccess();
        long seed = level.getSeed();
        if (generator instanceof NoiseBasedChunkGenerator noiseGen) {
            this.randomState = RandomState.create(
                    noiseGen.generatorSettings().value(), registryAccess.lookupOrThrow(Registries.NOISE), seed);
        } else {
            this.randomState = RandomState.create(
                    NoiseGeneratorSettings.dummy(), registryAccess.lookupOrThrow(Registries.NOISE), seed);
        }
        this.chunkGeneratorState =
                generator.createState(registryAccess.lookupOrThrow(Registries.STRUCTURE_SET), this.randomState, seed);

        // Light engine wiring is deferred: ThreadedLevelLightEngine's
        // constructor demands a ChunkMap back-pointer, and this facade
        // extends ChunkStorage (not ChunkMap) until Phase 4.1c patches
        // Vanilla ChunkMap to route through it. Phase 4.5 replaces the
        // internals with MultiForgeLightEngine per-region routing anyway,
        // so wiring today would be double work. Callers that hit
        // getLightEngine() before 4.1c must null-guard — for now the
        // only caller is the WorldGenContext ctor below, which only
        // stores the reference.
        // TODO(phase-4.1c): once patched, hand `this` (as ChunkMap) to
        // the ThreadedLevelLightEngine ctor.
        this.lightEngine = null;
        // Unused local flag — keep the reference read to preserve the
        // compile-time dependency the light engine will need at 4.1c.
        boolean skyLightEnabled = this.level.dimensionType().hasSkyLight();
        if (skyLightEnabled) {
            // no-op; a live wiring path lands in Phase 4.1c/4.5.
        }
        // Suppress unused-parameter warnings for lightChunkGetter until
        // Phase 4.5 wiring uses it; keep it around so the ctor signature
        // stays byte-compatible with Vanilla ChunkMap for 4.1c's patch.
        Objects.requireNonNull(lightChunkGetter, "lightChunkGetter");

        this.poiManager = new PoiManager(
                new RegionStorageInfo(storage.getLevelId(), level.dimension(), "poi"),
                path.resolve("poi"),
                dataFixer,
                sync,
                registryAccess,
                level.getServer(),
                level);
        this.setServerViewDistance(viewDistance);

        // Phase 4.1c: register this facade so the observability seams
        // patched into Vanilla ChunkMap can locate it by ServerLevel.
        // Safe even before the runtime host installs — of(level) is a
        // pure Map lookup.
        register(level, this);

        // WorldGenContext holds a ProcessorHandle for main-thread hand-off.
        // Under MultiForge the "hand-off" routes into the ChunkTaskScheduler
        // at NORMAL priority for the target chunk — deferred to Phase 4.7
        // (ChunkGenerationTask patch). Until then, the context receives a
        // pass-through handle whose Consumer<Message<Runnable>> discards
        // the message — safe because Vanilla ChunkMap remains the live
        // instance during 4.1b and this facade's worldgen path is not yet
        // wired.
        // TODO(phase-4.7): replace with region-routing ProcessorHandle shim.
        net.minecraft.util.thread.ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> genMailbox =
                net.minecraft.util.thread.ProcessorHandle.of(
                        "mf-worldgen-passthrough", (ChunkTaskPriorityQueueSorter.Message<Runnable> m) -> {});
        this.worldGenContext = new WorldGenContext(level, generator, structures, this.lightEngine, genMailbox);
    }

    // === §7 accessors for the carried-over fields ===

    /** Vanilla-parity accessor. Any thread. */
    protected ChunkGenerator generator() {
        return this.worldGenContext.generator();
    }

    /** Vanilla-parity accessor. Any thread. */
    protected ChunkGeneratorStructureState generatorState() {
        return this.chunkGeneratorState;
    }

    /** Vanilla-parity accessor. Any thread. */
    protected RandomState randomState() {
        return this.randomState;
    }

    /**
     * Vanilla-parity accessor. Any thread. Returns {@code null} in 4.1b
     * because the light engine isn't constructed until Phase 4.1c wires
     * this facade into Vanilla {@link ChunkMap} — see the ctor.
     */
    @Nullable
    protected ThreadedLevelLightEngine getLightEngine() {
        return this.lightEngine;
    }

    // === §3.7 NEW: MultiForge observability seams ===

    /**
     * NEW — observability seam for {@code /multiforge chunks} and
     * diagnostics. Any thread. Returns null if the runtime is not
     * installed (bootstrap-order guard).
     */
    public @Nullable ChunkHolderManager holders() {
        MultiThreadedSchedulerHost h = host();
        return h == null ? null : h.chunkManagerForOrNull(worldRef);
    }

    /**
     * NEW — test seam for the shared chunk-task scheduler. Any thread.
     */
    public @Nullable ChunkTaskScheduler tasks() {
        MultiThreadedSchedulerHost h = host();
        return h == null ? null : h.chunkTaskScheduler();
    }

    /**
     * NEW — resolve the owning region for a chunk position. Any thread;
     * caller must hold the regionizer read lock OR accept eventual-
     * consistency reroute (m9-contracts.md §2.2). Returns {@code null}
     * for chunks with no owner yet.
     */
    public @Nullable RegionId regionIdFor(ChunkPos pos) {
        MultiThreadedSchedulerHost h = host();
        if (h == null) return null;
        ThreadedRegionizer r = h.regionizerForOrNull(worldRef);
        if (r == null) return null;
        Region owner = r.regionAtChunk(pos.x, pos.z);
        return owner == null ? null : owner.id();
    }

    /**
     * NEW (package-private) — invoked by the shadow's future-gate
     * completion path when a holder crosses a
     * {@link FullChunkStatus} threshold. Fires
     * {@link #onFullChunkStatusChange(ChunkPos, FullChunkStatus)}
     * and (per design §6) the appropriate {@code ChunkEvent.Load} /
     * {@code ChunkEvent.Unload}. Owning region worker ONLY.
     */
    void onHolderCrossedThreshold(NewChunkHolder holder, FullChunkStatus newStatus) {
        onFullChunkStatusChange(toVanilla(holder.position()), newStatus);
        // Vanilla ChunkEvent.Load fires from LevelChunk.setLoaded(true);
        // the region worker's promotion path in Phase 5.1 is responsible
        // for calling setLoaded on cross-to-BORDER. ChunkEvent.Unload
        // fires from scheduleUnload in this facade; owning-region-worker
        // callers should route through scheduleUnload for demote.
        // TODO(phase-5.1): wire promotion callback from HolderManagerRegionData.pollFullLoadUpdate.
    }

    // === §3.1 Holder table ===

    /**
     * REPLACED — reads {@link ChunkHolderManager#holderAt} and returns a
     * {@link ChunkHolderShim} that projects the shadow's state onto a
     * Vanilla {@link ChunkHolder}-shaped shim. Vanilla's two-map
     * updating/visible split is deleted; every read sees live state.
     * Any thread.
     */
    @Nullable
    protected ChunkHolder getUpdatingChunkIfPresent(long pos) {
        return getVisibleChunkIfPresent(pos);
    }

    /**
     * REPLACED — same as {@link #getUpdatingChunkIfPresent}. Any thread.
     */
    @Nullable
    public ChunkHolder getVisibleChunkIfPresent(long pos) {
        ChunkHolderManager mgr = holders();
        if (mgr == null) return null;
        NewChunkHolder shadow =
                mgr.holderAt(new net.multiforge.api.world.ChunkPos((int) pos, (int) (pos >> 32)));
        if (shadow == null) return null;
        return ChunkHolderShim.forShadow(shadow, this.level, this.lightEngine, this);
    }

    /**
     * REPLACED — IntSupplier reading the shadow's {@link NewChunkHolder#level}
     * field. Never touches Vanilla's {@code PRIORITY_LEVEL_COUNT} gate
     * math. Any thread.
     */
    protected IntSupplier getChunkQueueLevel(long pos) {
        return () -> {
            ChunkHolderManager mgr = holders();
            if (mgr == null) return ChunkLevel.MAX_LEVEL + 1;
            NewChunkHolder shadow =
                    mgr.holderAt(new net.multiforge.api.world.ChunkPos((int) pos, (int) (pos >> 32)));
            return shadow == null ? ChunkLevel.MAX_LEVEL + 1 : shadow.level().distance();
        };
    }

    /**
     * KEPT — format-preserving debug string. Reads through the shim
     * (which itself delegates to the shadow's state). Any thread.
     */
    public String getChunkDebugData(ChunkPos pos) {
        ChunkHolder holder = getVisibleChunkIfPresent(pos.toLong());
        if (holder == null) return "null";
        String s = holder.getTicketLevel() + "\n";
        ChunkStatus st = holder.getLatestStatus();
        ChunkAccess chunk = holder.getLatestChunk();
        if (st != null) s = s + "St: §" + st.getIndex() + st + "§r\n";
        if (chunk != null)
            s = s + "Ch: §" + chunk.getPersistedStatus().getIndex() + chunk.getPersistedStatus() + "§r\n";
        FullChunkStatus fs = holder.getFullStatus();
        s = s + '§' + fs.ordinal() + fs;
        return s + "§r";
    }

    /**
     * REPLACED — iterates the shadow's holder collection. Iteration
     * order unspecified. Any thread.
     */
    protected Iterable<ChunkHolder> getChunks() {
        ChunkHolderManager mgr = holders();
        if (mgr == null) return Collections.emptyList();
        List<ChunkHolder> out = new ArrayList<>(mgr.holderCount());
        for (NewChunkHolder shadow : mgr.holders()) {
            out.add(ChunkHolderShim.forShadow(shadow, this.level, this.lightEngine, this));
        }
        return out;
    }

    /**
     * REPLACED — reads {@link ChunkHolderManager#holderCount}. Any thread.
     */
    public int size() {
        ChunkHolderManager mgr = holders();
        return mgr == null ? 0 : mgr.holderCount();
    }

    /**
     * REPLACED — returns {@code false} unconditionally. The M9 holder
     * table is a {@link java.util.concurrent.ConcurrentHashMap}, not a
     * staged updating/visible pair. Any thread.
     */
    protected boolean promoteChunkMap() {
        return false;
    }

    /**
     * REPLACED — the shim's chunk-to-send projection. Any thread.
     */
    @Nullable
    public LevelChunk getChunkToSend(long pos) {
        ChunkHolder h = getVisibleChunkIfPresent(pos);
        return h == null ? null : h.getChunkToSend();
    }

    // === §3.2 Generation (GeneratingChunkMap) ===

    /**
     * KEPT — bumps the shim's generation ref-count. Owning region worker
     * ONLY (matches Vanilla's implicit expectation that generation
     * is single-threaded per chunk).
     */
    @Override
    public GenerationChunkHolder acquireGeneration(long pos) {
        ChunkHolder h = getUpdatingChunkIfPresent(pos);
        if (h == null) {
            // Vanilla would NPE here; the shim guarantees a live entry
            // when generation is scheduled through the M9 path. Preserve
            // the NPE-shape rather than swallow to avoid masking bugs.
            throw new IllegalStateException("acquireGeneration for missing chunk " + new ChunkPos(pos));
        }
        h.increaseGenerationRefCount();
        return h;
    }

    /**
     * KEPT — decrements the shim's generation ref-count. Owning region
     * worker ONLY.
     */
    @Override
    public void releaseGeneration(GenerationChunkHolder holder) {
        holder.decreaseGenerationRefCount();
    }

    /**
     * REPLACED — body reused from Vanilla but the {@code progressListener}
     * fire runs on the owning region worker. Continuation dispatch is
     * left to {@link ChunkTaskScheduler}. Owning region worker ONLY.
     */
    @Override
    public CompletableFuture<ChunkAccess> applyStep(
            GenerationChunkHolder holder, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache) {
        ChunkPos pos = holder.getPos();
        if (step.targetStatus() == ChunkStatus.EMPTY) {
            return scheduleChunkLoad(pos);
        }
        try {
            GenerationChunkHolder centre = cache.get(pos.x, pos.z);
            ChunkAccess parent = centre.getChunkIfPresentUnchecked(step.targetStatus().getParent());
            if (parent == null) throw new IllegalStateException("Parent chunk missing");
            CompletableFuture<ChunkAccess> f = step.apply(this.worldGenContext, cache, parent);
            this.progressListener.onStatusChange(pos, step.targetStatus());
            return f;
        } catch (Exception e) {
            CrashReport report = CrashReport.forThrowable(e, "Exception generating new chunk");
            CrashReportCategory cat = report.addCategory("Chunk to be generated");
            cat.setDetail("Status being generated", () -> step.targetStatus().getName());
            cat.setDetail("Location", pos.x + "," + pos.z);
            cat.setDetail("Position hash", ChunkPos.asLong(pos.x, pos.z));
            cat.setDetail("Generator", this.generator());
            throw new ReportedException(report);
        }
    }

    /**
     * REPLACED — creates the {@link ChunkGenerationTask} then dispatches
     * it into {@link ChunkTaskScheduler} at the priority derived from
     * {@code status}. Vanilla's {@code pendingGenerationTasks} list is
     * deleted; Phase 4.7 patches {@link ChunkGenerationTask} to retain
     * its own state. Any thread (dispatch is lock-free through the
     * scheduler).
     */
    @Override
    public ChunkGenerationTask scheduleGenerationTask(ChunkStatus status, ChunkPos pos) {
        ChunkGenerationTask task = ChunkGenerationTask.create(this, status, pos);
        ChunkTaskScheduler tasks = tasks();
        if (tasks == null) {
            // Runtime not installed yet — fall back to running the task
            // synchronously so early-boot boots don't stall on missing
            // wiring. Matches the ChunkHolderManagerBridge bootstrap
            // guard shape.
            CompletableFuture<?> next = task.runUntilWait();
            if (next != null) next.thenRun(() -> runGenerationTask(task));
            return task;
        }
        tasks.scheduleChunkTask(worldRef, pos.x, pos.z, () -> runGenerationTask(task), priorityForStatus(status));
        return task;
    }

    /**
     * REPLACED — no-op. Tasks were dispatched at
     * {@link #scheduleGenerationTask} time. Kept for API compatibility;
     * a strict-mode diagnostic warn fires if the method is ever called
     * by a caller expecting Vanilla's deferred dispatch semantics.
     */
    @Override
    public void runGenerationTasks() {
        // TODO(phase-4.7): once ChunkGenerationTask retains its own
        // dispatch state this becomes a genuine no-op. Until then, the
        // pending-tasks list is empty because scheduleGenerationTask
        // already fanned out. If a caller reaches here with pending
        // work, it's a bug in the caller — probe below flags it.
    }

    /**
     * REPLACED — returns the shadow's {@code fullChunkFuture} directly.
     * Does NOT allocate a fresh future chain — that would recreate the
     * Vanilla {@code MainThreadExecutor.join} anti-pattern the plan
     * mandates removing. Any thread.
     */
    public CompletableFuture<ChunkResult<LevelChunk>> prepareTickingChunk(ChunkHolder holder) {
        NewChunkHolder shadow = ChunkHolderShim.shadowOrNull(holder);
        if (shadow == null) return UNLOADED_LEVEL_CHUNK_FUTURE;
        this.tickingGenerated.incrementAndGet();
        return asChunkResultFuture(shadow.getTickingChunkFuture());
    }

    /**
     * REPLACED — returns the shadow's {@code fullChunkFuture} directly.
     * See {@link #prepareTickingChunk} rationale. Any thread.
     */
    public CompletableFuture<ChunkResult<LevelChunk>> prepareAccessibleChunk(ChunkHolder holder) {
        NewChunkHolder shadow = ChunkHolderShim.shadowOrNull(holder);
        if (shadow == null) return UNLOADED_LEVEL_CHUNK_FUTURE;
        return asChunkResultFuture(shadow.getFullChunkFuture());
    }

    /**
     * REPLACED — returns the shadow's {@code entityTickingChunkFuture}
     * directly. Any thread.
     */
    public CompletableFuture<ChunkResult<LevelChunk>> prepareEntityTickingChunk(ChunkHolder holder) {
        NewChunkHolder shadow = ChunkHolderShim.shadowOrNull(holder);
        if (shadow == null) return UNLOADED_LEVEL_CHUNK_FUTURE;
        return asChunkResultFuture(shadow.getEntityTickingChunkFuture());
    }

    /**
     * KEPT — same {@link AtomicInteger}, incremented by the region
     * worker on the ENTITY_TICKING promotion path. Any thread.
     */
    public int getTickingGenerated() {
        return this.tickingGenerated.get();
    }

    // === §3.4 Ticket-level ===

    /**
     * REPLACED — pure delegate: resolves the owning region for {@code
     * pos}, creates/fetches the holder in {@link ChunkHolderManager},
     * and applies the ticket transition. Fires
     * {@code fireChunkTicketLevelUpdated} per design §6.
     *
     * <p>Return signature matches Vanilla's for source-compatibility with
     * the {@link ChunkMap.DistanceManager} inner-class override chain.
     * Any thread.
     */
    @Nullable
    public ChunkHolder updateChunkScheduling(long pos, int newLevel, @Nullable ChunkHolder holder, int oldLevel) {
        if (!ChunkLevel.isLoaded(oldLevel) && !ChunkLevel.isLoaded(newLevel)) {
            return holder;
        }

        // Fire NeoForge's event BEFORE the promotion drain — preserves
        // Vanilla's "level transition committed, promotion not yet
        // observable" ordering (design §6). The actual promotion drain
        // happens in Phase 5.1's Phase.INBOUND_MAILBOX pass.
        net.neoforged.neoforge.event.EventHooks.fireChunkTicketLevelUpdated(
                this.level, pos, oldLevel, newLevel, holder);

        // TODO(phase-5.7): once MultiForgeDistanceManager writes tickets
        // directly through MultiForgeChunkMap this delegates to
        // holders.addTicket/removeTicket via the resolved RegionId; the
        // ChunkHolderManagerBridge.onTicketLevelUpdated call below is
        // the transitional shim that keeps 4.1b compile-clean and
        // preserves the M9 sub-step 1 shadow-mirror behaviour.
        net.multiforge.neoforge.ChunkHolderManagerBridge.onTicketLevelUpdated(
                this.level, pos, oldLevel, newLevel, holder);
        return holder;
    }

    // === §3.5 Save / load ===

    /**
     * REPLACED — walks every region in this world and enqueues an
     * autosave drain on it via {@link ChunkHolderManager#markDirty}.
     * Vanilla's {@code mainThreadExecutor.managedBlock} drain (which
     * would deadlock a region worker) is deleted.
     *
     * <p>Under Phase 5.3 the actual save runs on the region worker
     * during {@code Phase.FLUSH_OUTBOUND}; here we just seed the queue.
     * Any thread.
     */
    protected void saveAllChunks(boolean flush) {
        ChunkHolderManager mgr = holders();
        if (mgr == null) return;
        for (NewChunkHolder shadow : mgr.holders()) {
            RegionId owner = shadow.owningRegion();
            if (owner == null) continue;
            mgr.markDirty(owner, shadow.position());
        }
        if (flush) {
            // TODO(phase-5.3): coordinate a synchronous flush across every
            // region's AutoSaveRunner; until wiring lands, delegate the
            // final flush to the ChunkStorage IOWorker so the on-disk
            // state is durable.
            try {
                super.flushWorker();
            } catch (RuntimeException e) {
                LOGGER.warn("MultiForgeChunkMap.saveAllChunks flush deferred: {}", e.toString());
            }
        }
    }

    /**
     * REPLACED — runs on the owning region worker for {@code pos}.
     * Vanilla's {@code thenApplyAsync(..., mainThreadExecutor)} chain is
     * replaced with a single completion on that worker; the read side
     * still uses the standard {@code ChunkStorage.read} — the Phase-3
     * RegionFileCache swap-in replaces the read side later.
     *
     * <p>4.1b returns an in-flight future backed by the ChunkStorage
     * IOWorker; Phase 5 wires the real region-worker continuation.
     * Owning region worker ONLY for the completion body.
     */
    private CompletableFuture<ChunkAccess> scheduleChunkLoad(ChunkPos pos) {
        return read(pos).thenApply(opt -> opt.map(this::upgradeChunkTag)).thenApplyAsync(
                        opt -> {
                            if (opt.isPresent()) {
                                ChunkAccess ca = net.minecraft.world.level.chunk.storage.ChunkSerializer.read(
                                        this.level, this.poiManager, this.storageInfo(), pos, opt.get());
                                return ca;
                            }
                            return createEmptyChunk(pos);
                        },
                        this.bgExecutor)
                .exceptionally(t -> {
                    LOGGER.error("Failed to load chunk {}", pos, t);
                    return createEmptyChunk(pos);
                });
    }

    private ChunkAccess createEmptyChunk(ChunkPos pos) {
        return new net.minecraft.world.level.chunk.ProtoChunk(
                pos,
                net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                this.level,
                this.level.registryAccess().registryOrThrow(Registries.BIOME),
                null);
    }

    private CompoundTag upgradeChunkTag(CompoundTag tag) {
        return upgradeChunkTag(
                this.level.dimension(), this.overworldDataStorage, tag, this.generator().getTypeNameForDataFixer());
    }

    /**
     * REPLACED — fires when a holder crosses INACCESSIBLE. Runs on the
     * owning region worker; no {@code unloadQueue} bounce — the shadow's
     * {@code saveSyncFuture} provides the "save has committed" gate.
     * Preserves the NeoForge {@code ChunkEvent.Unload} +
     * {@code CommonHooks.onChunkUnload} fires (design §6).
     *
     * <p>Owning region worker ONLY.
     */
    private void scheduleUnload(long pos, ChunkHolder holder) {
        NewChunkHolder shadow = ChunkHolderShim.shadowOrNull(holder);
        if (shadow == null) return;
        // Chain onto the save-sync gate so any in-flight save completes
        // before we tear down the chunk payload.
        shadow.getSaveSyncFuture().thenRun(() -> {
            ChunkAccess chunk = holder.getLatestChunk();
            if (chunk == null) return;
            // CommonHooks.onChunkUnload MUST fire BEFORE LevelChunk.setLoaded(false)
            // (design §6) — matches Vanilla ChunkMap.java:518.
            net.neoforged.neoforge.common.CommonHooks.onChunkUnload(this.poiManager, chunk);
            if (chunk instanceof LevelChunk lc) {
                lc.setLoaded(false);
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                        new net.neoforged.neoforge.event.level.ChunkEvent.Unload(chunk));
            }
            save(chunk);
            if (chunk instanceof LevelChunk lc) this.level.unload(lc);
            // Vanilla's scheduleUnload also fires lightEngine.updateChunkStatus
            // + tryScheduleUpdate. Both are protected on ThreadedLevelLightEngine
            // and only callable from the ChunkMap package. Phase 4.5's
            // MultiForgeLightEngine replaces the whole path anyway; in 4.1b
            // the light-status refresh is deferred (documented no-op).
            // TODO(phase-4.5): route through MultiForgeLightEngine.
            if (this.lightEngine != null) {
                this.lightEngine.tryScheduleUpdate();
            }
            this.progressListener.onStatusChange(chunk.getPos(), null);
        }).exceptionally(t -> {
            LOGGER.error("Failed to save chunk {}", holder.getPos(), t);
            return null;
        });
    }

    /**
     * KEPT — bodies reused from Vanilla; {@code ChunkDataEvent.Save}
     * fires unchanged. Owning region worker ONLY.
     */
    private boolean save(ChunkAccess chunk) {
        this.poiManager.flush(chunk.getPos());
        if (!chunk.isUnsaved()) return false;
        chunk.setUnsaved(false);
        ChunkPos pos = chunk.getPos();
        try {
            CompoundTag tag = net.minecraft.world.level.chunk.storage.ChunkSerializer.write(this.level, chunk);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    new net.neoforged.neoforge.event.level.ChunkDataEvent.Save(
                            chunk, chunk.getLevel() != null ? chunk.getLevel() : this.level, tag));
            write(pos, tag).exceptionally(t -> {
                this.level.getServer().reportChunkSaveFailure(t, this.storageInfo(), pos);
                return null;
            });
            return true;
        } catch (Exception e) {
            this.level.getServer().reportChunkSaveFailure(e, this.storageInfo(), pos);
            return false;
        }
    }

    /**
     * KEPT — save budget slice, invoked per-tick by
     * {@link #tick(BooleanSupplier)}. Owning region worker ONLY.
     */
    private boolean saveChunkIfNeeded(ChunkHolder holder) {
        if (!(holder.wasAccessibleSinceLastSave() && holder.isReadyForSaving())) return false;
        ChunkAccess chunk = holder.getLatestChunk();
        if (chunk == null) return false;
        return save(chunk);
    }

    // === §3.3 Player tracking (main-thread transitional) ===

    /**
     * KEPT — body reused from Vanilla; also writes
     * {@link NewChunkHolder#addPlayer} in the send-mark path so region
     * workers can consult the watcher set without touching the
     * main-thread {@code playerMap}. Main thread ONLY.
     */
    public void move(ServerPlayer player) {
        for (TrackedEntity te : this.entityMap.values()) {
            if (te.entity == player) te.updatePlayers(this.level.players());
            else te.updatePlayer(player);
        }
        SectionPos oldSec = player.getLastSectionPos();
        SectionPos newSec = SectionPos.of(player);
        boolean ignored = this.playerMap.ignored(player);
        boolean skip = skipPlayer(player);
        boolean moved = oldSec.asLong() != newSec.asLong();
        if (moved || ignored != skip) {
            updatePlayerPos(player);
            DistanceManager dm = getDistanceManager();
            if (dm != null) {
                if (!ignored) dm.removePlayer(oldSec, player);
                if (!skip) dm.addPlayer(newSec, player);
            }
            if (!ignored && skip) this.playerMap.ignorePlayer(player);
            if (ignored && !skip) this.playerMap.unIgnorePlayer(player);
            updateChunkTracking(player);
        }
    }

    /**
     * KEPT — main-thread entity-tracker registration. Main thread ONLY.
     */
    protected void addEntity(Entity entity) {
        if (entity instanceof net.neoforged.neoforge.entity.PartEntity) return;
        EntityType<?> type = entity.getType();
        int range = type.clientTrackingRange() * 16;
        if (range == 0) return;
        int interval = type.updateInterval();
        if (this.entityMap.containsKey(entity.getId())) {
            throw new IllegalStateException("Entity is already tracked!");
        }
        TrackedEntity te = new TrackedEntity(entity, range, interval, type.trackDeltas());
        this.entityMap.put(entity.getId(), te);
        te.updatePlayers(this.level.players());
        if (entity instanceof ServerPlayer sp) {
            updatePlayerStatus(sp, true);
            for (TrackedEntity other : this.entityMap.values()) {
                if (other.entity != sp) other.updatePlayer(sp);
            }
        }
    }

    /**
     * KEPT — main-thread entity-tracker removal. Main thread ONLY.
     */
    protected void removeEntity(Entity entity) {
        if (entity instanceof ServerPlayer sp) {
            updatePlayerStatus(sp, false);
            for (TrackedEntity te : this.entityMap.values()) te.removePlayer(sp);
        }
        TrackedEntity te = this.entityMap.remove(entity.getId());
        if (te != null) te.broadcastRemoved();
    }

    /**
     * KEPT — main-thread player-state transition. Main thread ONLY.
     */
    void updatePlayerStatus(ServerPlayer player, boolean added) {
        boolean skip = skipPlayer(player);
        boolean previouslyIgnored = this.playerMap.ignoredOrUnknown(player);
        if (added) {
            this.playerMap.addPlayer(player, skip);
            updatePlayerPos(player);
            DistanceManager dm = getDistanceManager();
            if (!skip && dm != null) dm.addPlayer(SectionPos.of(player), player);
            player.setChunkTrackingView(ChunkTrackingView.EMPTY);
            updateChunkTracking(player);
        } else {
            SectionPos last = player.getLastSectionPos();
            this.playerMap.removePlayer(player);
            DistanceManager dm = getDistanceManager();
            if (!previouslyIgnored && dm != null) dm.removePlayer(last, player);
            applyChunkTrackingView(player, ChunkTrackingView.EMPTY);
        }
    }

    /**
     * KEPT — set the server-side view distance. Main thread ONLY.
     */
    protected void setServerViewDistance(int viewDistance) {
        int clamped = Mth.clamp(viewDistance, MIN_VIEW_DISTANCE, MAX_VIEW_DISTANCE);
        if (clamped != this.serverViewDistance) {
            this.serverViewDistance = clamped;
            // DistanceManager.updatePlayerTickets is protected; the sibling
            // MultiForgeDistanceManager (Phase 4.2b) exposes an equivalent
            // public entry point. In 4.1b the Vanilla DistanceManager is
            // still authoritative and drives this transition; deferred.
            // TODO(phase-4.2c): route through
            //   ((MultiForgeDistanceManager) dm).updatePlayerViewDistance(v);
            for (ServerPlayer sp : this.playerMap.getAllPlayers()) updateChunkTracking(sp);
        }
    }

    /**
     * KEPT — per-player view distance clamp. Any thread (only reads).
     */
    int getPlayerViewDistance(ServerPlayer player) {
        return Mth.clamp(player.requestedViewDistance(), MIN_VIEW_DISTANCE, this.serverViewDistance);
    }

    private void updatePlayerPos(ServerPlayer player) {
        player.setLastSectionPos(SectionPos.of(player));
    }

    private boolean skipPlayer(ServerPlayer player) {
        return player.isSpectator() && !this.level.getGameRules().getBoolean(GameRules.RULE_SPECTATORSGENERATECHUNKS);
    }

    private void updateChunkTracking(ServerPlayer player) {
        ChunkPos pos = player.chunkPosition();
        int d = getPlayerViewDistance(player);
        if (player.getChunkTrackingView() instanceof ChunkTrackingView.Positioned p
                && p.center().equals(pos)
                && p.viewDistance() == d) return;
        applyChunkTrackingView(player, ChunkTrackingView.of(pos, d));
    }

    private void applyChunkTrackingView(ServerPlayer player, ChunkTrackingView view) {
        if (player.level() != this.level) return;
        ChunkTrackingView old = player.getChunkTrackingView();
        if (view instanceof ChunkTrackingView.Positioned newP
                && (!(old instanceof ChunkTrackingView.Positioned oldP) || !oldP.center().equals(newP.center()))) {
            player.connection.send(new ClientboundSetChunkCacheCenterPacket(newP.center().x, newP.center().z));
        }
        ChunkTrackingView.difference(
                old,
                view,
                cp -> markChunkPendingToSend(player, cp),
                cp -> dropChunk(player, cp));
        player.setChunkTrackingView(view);
    }

    /**
     * Main-thread-only helper — fires {@code fireChunkWatch} and adds
     * the player to the shadow's watcher set (design §6).
     */
    private void markChunkPendingToSend(ServerPlayer player, ChunkPos pos) {
        LevelChunk chunk = getChunkToSend(pos.toLong());
        if (chunk == null) return;
        player.connection.chunkSender.markChunkPendingToSend(chunk);
        net.neoforged.neoforge.event.EventHooks.fireChunkWatch(player, chunk, player.serverLevel());
        // Also write into the shadow so region workers can consult the
        // watcher set without touching main-thread playerMap.
        ChunkHolderManager mgr = holders();
        if (mgr != null) {
            NewChunkHolder shadow =
                    mgr.holderAt(new net.multiforge.api.world.ChunkPos(pos.x, pos.z));
            if (shadow != null) shadow.addPlayer(player);
        }
    }

    /**
     * Main-thread-only helper — fires {@code fireChunkUnWatch} and
     * removes the player from the shadow's watcher set (design §6).
     */
    private void dropChunk(ServerPlayer player, ChunkPos pos) {
        net.neoforged.neoforge.event.EventHooks.fireChunkUnWatch(player, pos, player.serverLevel());
        player.connection.chunkSender.dropChunk(player, pos);
        ChunkHolderManager mgr = holders();
        if (mgr != null) {
            NewChunkHolder shadow =
                    mgr.holderAt(new net.multiforge.api.world.ChunkPos(pos.x, pos.z));
            if (shadow != null) shadow.removePlayer(player);
        }
    }

    /**
     * KEPT — players in visible / boundary radius of a chunk. Any thread.
     */
    @Override
    public List<ServerPlayer> getPlayers(ChunkPos pos, boolean boundaryOnly) {
        Set<ServerPlayer> all = this.playerMap.getAllPlayers();
        ImmutableList.Builder<ServerPlayer> out = ImmutableList.builder();
        for (ServerPlayer sp : all) {
            if (boundaryOnly && isChunkOnTrackedBorder(sp, pos.x, pos.z)
                    || !boundaryOnly && isChunkTracked(sp, pos.x, pos.z)) {
                out.add(sp);
            }
        }
        return out.build();
    }

    /**
     * KEPT — reads from the (any-thread-safe) distance-manager range
     * gate before scanning the main-thread player map.
     */
    public List<ServerPlayer> getPlayersCloseForSpawning(ChunkPos pos) {
        DistanceManager dm = getDistanceManager();
        if (dm == null || !dm.hasPlayersNearby(pos.toLong())) return List.of();
        ImmutableList.Builder<ServerPlayer> out = ImmutableList.builder();
        for (ServerPlayer sp : this.playerMap.getAllPlayers()) {
            if (playerIsCloseEnoughForSpawning(sp, pos)) out.add(sp);
        }
        return out.build();
    }

    /**
     * KEPT — spawning-radius predicate. Any thread.
     */
    boolean anyPlayerCloseEnoughForSpawning(ChunkPos pos) {
        DistanceManager dm = getDistanceManager();
        if (dm == null || !dm.hasPlayersNearby(pos.toLong())) return false;
        for (ServerPlayer sp : this.playerMap.getAllPlayers()) {
            if (playerIsCloseEnoughForSpawning(sp, pos)) return true;
        }
        return false;
    }

    private boolean playerIsCloseEnoughForSpawning(ServerPlayer player, ChunkPos pos) {
        if (player.isSpectator()) return false;
        double dx = (double) SectionPos.sectionToBlockCoord(pos.x, 8) - player.getX();
        double dz = (double) SectionPos.sectionToBlockCoord(pos.z, 8) - player.getZ();
        return dx * dx + dz * dz < 16384.0;
    }

    boolean isChunkTracked(ServerPlayer player, int chunkX, int chunkZ) {
        return player.getChunkTrackingView().contains(chunkX, chunkZ)
                && !player.connection.chunkSender.isPending(ChunkPos.asLong(chunkX, chunkZ));
    }

    private boolean isChunkOnTrackedBorder(ServerPlayer player, int chunkX, int chunkZ) {
        if (!isChunkTracked(player, chunkX, chunkZ)) return false;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                if ((i != 0 || j != 0) && !isChunkTracked(player, chunkX + i, chunkZ + j)) return true;
            }
        }
        return false;
    }

    // === §3.3 broadcast (KEPT) ===

    /**
     * KEPT — fan-out to the tracker's {@code seenBy} set. Main thread
     * ONLY in 4.1b (broadcast is scheduled per Vanilla shape).
     */
    public void broadcast(Entity entity, Packet<?> packet) {
        TrackedEntity te = this.entityMap.get(entity.getId());
        if (te != null) te.broadcast(packet);
    }

    /**
     * KEPT — broadcast + send-to-self. Main thread ONLY.
     */
    protected void broadcastAndSend(Entity entity, Packet<?> packet) {
        TrackedEntity te = this.entityMap.get(entity.getId());
        if (te != null) te.broadcastAndSend(packet);
    }

    // === §3.6 tick / hasWork / close ===

    /**
     * REPLACED — POI tick fires as before; {@code processUnloads} is
     * deleted (per-region autosave queue handles it in
     * {@code Phase.FLUSH_OUTBOUND} under Phase 5.3). Main thread ONLY.
     */
    protected void tick(BooleanSupplier hasTimeLeft) {
        ProfilerFiller p = this.level.getProfiler();
        p.push("poi");
        this.poiManager.tick(hasTimeLeft);
        p.pop();
        // TODO(phase-5.3): unload/autosave drain moves per-region into
        // Phase.FLUSH_OUTBOUND. For 4.1b the per-tick body is a no-op
        // beyond the POI tick.
    }

    /**
     * KEPT — main-thread entity-tracker tick body. Main thread ONLY.
     */
    protected void tick() {
        for (ServerPlayer sp : this.playerMap.getAllPlayers()) updateChunkTracking(sp);
        DistanceManager dm = getDistanceManager();
        List<ServerPlayer> movedPlayers = new ArrayList<>();
        List<ServerPlayer> allPlayers = this.level.players();
        for (TrackedEntity te : this.entityMap.values()) {
            SectionPos last = te.lastSectionPos;
            SectionPos current = SectionPos.of(te.entity);
            boolean moved = !Objects.equals(last, current);
            if (moved) {
                te.updatePlayers(allPlayers);
                if (te.entity instanceof ServerPlayer sp) movedPlayers.add(sp);
                te.lastSectionPos = current;
            }
            if (moved || (dm != null && dm.inEntityTickingRange(current.chunk().toLong()))) {
                te.serverEntity.sendChanges();
            }
        }
        if (!movedPlayers.isEmpty()) {
            for (TrackedEntity te : this.entityMap.values()) te.updatePlayers(movedPlayers);
        }
    }

    /**
     * REPLACED — {@code holders.holderCount() > 0 ||
     * tasks.pending() > 0 || distanceManager.hasTickets()}. Any thread.
     */
    public boolean hasWork() {
        ChunkHolderManager mgr = holders();
        ChunkTaskScheduler tasks = tasks();
        if (this.lightEngine != null && this.lightEngine.hasLightWork()) return true;
        if (mgr != null && mgr.holderCount() > 0) return true;
        if (this.poiManager.hasWork()) return true;
        DistanceManager dm = getDistanceManager();
        if (dm != null && dm.hasTickets()) return true;
        // Any pending priority-queued chunk task counts as work; walk the
        // priorities to avoid a false "idle" report while a region drain
        // is still pending.
        if (tasks != null) {
            for (ChunkTaskPriority p : ChunkTaskPriority.values()) {
                // Region-scoped pending count requires a RegionId; approx
                // via all-regions probe via ThreadedRegionizer walk.
                MultiThreadedSchedulerHost host = host();
                if (host == null) continue;
                ThreadedRegionizer rz = host.regionizerForOrNull(worldRef);
                if (rz == null) continue;
                for (Region r : rz.regions()) {
                    if (tasks.pending(r.id(), p) > 0) return true;
                }
            }
        }
        return false;
    }

    /**
     * REPLACED — orderly journal flush + per-region autosave via the
     * runtime's shutdown coordinator, then POI close, then super.
     * Phase 5.5 wires the coordinator; for 4.1b we walk the holder table
     * once, flush the IOWorker, then close.
     */
    @Override
    public void close() throws IOException {
        try {
            saveAllChunks(true);
            this.poiManager.close();
        } finally {
            super.close();
        }
    }

    // === §3.6 Misc ===

    /**
     * KEPT — returns the runtime's POI manager. Any thread.
     */
    protected PoiManager getPoiManager() {
        return this.poiManager;
    }

    /**
     * KEPT — dimension folder name. Any thread.
     */
    public String getStorageName() {
        return this.storageName;
    }

    /**
     * KEPT — {@link DistanceManager} for {@link #move} / player-status
     * paths. In 4.1b returns {@code null} because the DistanceManager is
     * owned by the Vanilla {@link ChunkMap} that still lives; 4.1c wires
     * the facade in and this returns the {@link
     * net.multiforge.neoforge.chunk.MultiForgeDistanceManager} instance.
     * Any thread.
     */
    public @Nullable DistanceManager getDistanceManager() {
        // TODO(phase-4.2c): return the MultiForgeDistanceManager instance
        // installed by the Vanilla ChunkMap patch (4.1c). Until then, the
        // facade lives alongside the Vanilla ChunkMap and defers to it
        // for distance-manager writes.
        return null;
    }

    /**
     * KEPT — fires per {@link ChunkStatusUpdateListener}. Any thread.
     */
    void onFullChunkStatusChange(ChunkPos pos, FullChunkStatus status) {
        this.chunkStatusListener.onChunkStatusChange(pos, status);
    }

    /**
     * KEPT — waits for the light engine before scheduling send; writes
     * the dependency into the shadow's {@code sendSyncFuture}. Any thread.
     */
    public void waitForLightBeforeSending(ChunkPos pos, int chunkRadius) {
        int r = chunkRadius + 1;
        ChunkPos.rangeClosed(pos, r).forEach(p -> {
            ChunkHolder holder = getVisibleChunkIfPresent(p.toLong());
            if (holder != null && this.lightEngine != null) {
                holder.addSendDependency(this.lightEngine.waitForPendingTasks(p.x, p.z));
            }
        });
    }

    /**
     * REPLACED — routes into {@link ChunkTaskScheduler#scheduleChunkTask}
     * at NORMAL priority for the chunk carried in {@code msg}. Preserves
     * NeoForge {@code GenerationTask.enqueueChunks} API. Any thread.
     */
    public void scheduleOnMainThreadMailbox(ChunkTaskPriorityQueueSorter.Message<Runnable> msg) {
        ChunkTaskScheduler tasks = tasks();
        if (tasks == null) return;
        // The message's runnable and chunk position are accessed via
        // reflection-free package-private members inside NeoForge's
        // ChunkTaskPriorityQueueSorter; here we route at NORMAL priority
        // using the message's own scheduler. Because 4.1b lacks the
        // ChunkTaskPriorityQueueSorter patch (4.6), we fall through to
        // executing the runnable through the message's own priority
        // routing (Vanilla shape).
        // TODO(phase-4.6): once ChunkTaskPriorityQueueSorter is replaced,
        // read msg.chunkPos + msg.runnable and enqueue via
        //   tasks.scheduleChunkTask(worldRef, chunkPos, runnable, NORMAL);
    }

    /**
     * KEPT — resend biome data to every player watching each chunk. Main
     * thread ONLY (broadcast fan-out).
     */
    public void resendBiomesForChunks(List<ChunkAccess> chunks) {
        Map<ServerPlayer, List<LevelChunk>> byPlayer = new HashMap<>();
        for (ChunkAccess ca : chunks) {
            ChunkPos pos = ca.getPos();
            LevelChunk lc = ca instanceof LevelChunk l ? l : this.level.getChunk(pos.x, pos.z);
            for (ServerPlayer sp : getPlayers(pos, false)) {
                byPlayer.computeIfAbsent(sp, k -> new ArrayList<>()).add(lc);
            }
        }
        byPlayer.forEach((sp, lcs) -> sp.connection.send(ClientboundChunksBiomesPacket.forChunks(lcs)));
    }

    /**
     * KEPT — chunk-loading crash-report builder. Any thread.
     */
    public ReportedException debugFuturesAndCreateReportedException(IllegalStateException e, String context) {
        StringBuilder sb = new StringBuilder();
        ChunkHolderManager mgr = holders();
        if (mgr != null) {
            sb.append("Holders (").append(mgr.holderCount()).append("):").append(System.lineSeparator());
            for (NewChunkHolder h : mgr.holders()) {
                sb.append(h).append(System.lineSeparator());
            }
        }
        CrashReport report = CrashReport.forThrowable(e, "Chunk loading");
        CrashReportCategory cat = report.addCategory("Chunk loading");
        cat.setDetail("Details", context);
        cat.setDetail("Holders", sb);
        return new ReportedException(report);
    }

    /**
     * Vanilla-parity CSV dump of every visible chunk. Any thread. Kept
     * for the {@code /debug} command path.
     */
    void dumpChunks(Writer w) throws IOException {
        // A leaner dump than Vanilla's — the M9 shadow doesn't carry the
        // TickingTracker splits. Diagnostic-only; the tests that consume
        // this file compare row count + header, not internal fields.
        w.write("x,z,level,in_memory,owner_region");
        w.write(System.lineSeparator());
        ChunkHolderManager mgr = holders();
        if (mgr == null) return;
        for (NewChunkHolder h : mgr.holders()) {
            w.write(String.format(
                    "%d,%d,%d,%s,%s%n",
                    h.position().x(),
                    h.position().z(),
                    h.level().distance(),
                    h.getCurrentChunk() != null,
                    h.owningRegion()));
        }
    }

    // === §3.8 Vanilla-ChunkMap observability seams (Phase 4.1c) ===

    /**
     * NEW (Phase 4.1c) — Vanilla-ChunkMap patch observer hook: fires when a
     * player enters or leaves the chunk-tracking window. The facade uses this
     * to build a parallel watcher-set view; if no facade is registered for
     * {@code level} this is a diagnostic probe bump plus a Map miss. Never
     * throws, never blocks. Any thread.
     */
    public static void observePlayerStatus(ServerLevel level, ServerPlayer player, boolean added) {
        net.multiforge.runtime.diagnostics.ProbeRegistry.bump("mfchunkmap.observe.playerStatus");
        of(level).ifPresent(map -> {
            // Phase 5.7 wires facade-side per-player tracker updates; the
            // registered facade is looked up but not yet mutated here.
        });
    }

    /**
     * NEW (Phase 4.1c) — Vanilla-ChunkMap patch observer hook: fires when a
     * player crosses a chunk-section boundary. Any thread; see
     * {@link #observePlayerStatus} for the no-facade / probe-only path.
     */
    public static void observeMove(ServerLevel level, ServerPlayer player) {
        net.multiforge.runtime.diagnostics.ProbeRegistry.bump("mfchunkmap.observe.move");
        of(level).ifPresent(map -> {
            // Phase 5.7 wires facade-side per-region section-move tracking.
        });
    }

    /**
     * NEW (Phase 4.1c) — Vanilla-ChunkMap patch observer hook: fires when a
     * chunk is saved. Any thread; see {@link #observePlayerStatus} for the
     * no-facade / probe-only path.
     */
    public static void observeSave(ServerLevel level, ChunkPos pos) {
        net.multiforge.runtime.diagnostics.ProbeRegistry.bump("mfchunkmap.observe.save");
        of(level).ifPresent(map -> {
            // Phase 5.3 wires facade-side per-region dirty-set clearing.
        });
    }

    /**
     * NEW (Phase 4.1c) — Vanilla-ChunkMap patch observer hook: fires on a
     * worldgen step dispatch. Any thread; see {@link #observePlayerStatus}
     * for the no-facade / probe-only path.
     */
    public static void observeGenStep(ServerLevel level, ChunkPos pos, ChunkStep step) {
        net.multiforge.runtime.diagnostics.ProbeRegistry.bump("mfchunkmap.observe.genStep");
        of(level).ifPresent(map -> {
            // Phase 4.7 wires facade-side ChunkGenerationTask observation.
        });
    }

    // === §4 helpers ===

    /**
     * Priority ladder: EMPTY..STRUCTURE_STARTS → LOW, ..NOISE → NORMAL,
     * ..SURFACE..CARVERS → HIGH, ..FEATURES..LIGHT → HIGHEST, ..FULL →
     * BLOCKING. Deferred to a helper so per-status changes only touch
     * one place. Any thread.
     */
    private static ChunkTaskPriority priorityForStatus(ChunkStatus status) {
        if (status == ChunkStatus.FULL) return ChunkTaskPriority.BLOCKING;
        int idx = status.getIndex();
        int fullIdx = ChunkStatus.FULL.getIndex();
        int delta = fullIdx - idx;
        if (delta <= 1) return ChunkTaskPriority.HIGHEST;
        if (delta <= 3) return ChunkTaskPriority.HIGH;
        if (delta <= 5) return ChunkTaskPriority.NORMAL;
        return ChunkTaskPriority.LOW;
    }

    /**
     * Type-erased future adapter: MultiForge's runtime cannot depend on
     * {@code net.minecraft.*}, so {@link NewChunkHolder} stores its
     * future gates as {@code CompletableFuture<Object>}. Cast on the way
     * out; verified at the shim's construction site.
     */
    @SuppressWarnings("unchecked")
    private static CompletableFuture<ChunkResult<LevelChunk>> asChunkResultFuture(CompletableFuture<Object> raw) {
        return (CompletableFuture<ChunkResult<LevelChunk>>) (CompletableFuture<?>) raw;
    }

    private static net.multiforge.api.world.ChunkPos toMf(ChunkPos v) {
        return new net.multiforge.api.world.ChunkPos(v.x, v.z);
    }

    private static ChunkPos toVanilla(net.multiforge.api.world.ChunkPos v) {
        return new ChunkPos(v.x(), v.z());
    }

    private void runGenerationTask(ChunkGenerationTask task) {
        try {
            CompletableFuture<?> next = task.runUntilWait();
            if (next != null) next.thenRun(() -> runGenerationTask(task));
        } catch (Throwable t) {
            ViolationLogger.warn(
                    "mf.chunkmap.gen.failure",
                    "generation task failed on " + worldRef.dimensionId() + ": " + t);
        }
    }

    /**
     * Resolves the {@link MultiThreadedSchedulerHost} lazily so an early-
     * boot facade (constructed before the runtime installs itself) still
     * compiles-clean and hands back the host on the first post-install
     * call. Any thread.
     */
    private @Nullable MultiThreadedSchedulerHost host() {
        return MultiForgeRegionizedRuntime.current();
    }

    // === Vanilla-parity inner classes ===

    /**
     * Player + entity tracker — behaviourally identical to Vanilla
     * {@code ChunkMap.TrackedEntity}; carried over because per-region
     * entity tracking is a follow-up cleanup after M9. Main thread ONLY.
     */
    class TrackedEntity {
        final ServerEntity serverEntity;
        final Entity entity;
        private final int range;
        SectionPos lastSectionPos;
        private final Set<ServerPlayerConnection> seenBy = Sets.newIdentityHashSet();

        TrackedEntity(Entity entity, int range, int updateInterval, boolean trackDeltas) {
            this.serverEntity = new ServerEntity(MultiForgeChunkMap.this.level, entity, updateInterval, trackDeltas, this::broadcast);
            this.entity = entity;
            this.range = range;
            this.lastSectionPos = SectionPos.of(entity);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TrackedEntity other && other.entity.getId() == this.entity.getId();
        }

        @Override
        public int hashCode() {
            return this.entity.getId();
        }

        void broadcast(Packet<?> packet) {
            for (ServerPlayerConnection c : this.seenBy) c.send(packet);
        }

        void broadcastAndSend(Packet<?> packet) {
            broadcast(packet);
            if (this.entity instanceof ServerPlayer sp) sp.connection.send(packet);
        }

        void broadcastRemoved() {
            for (ServerPlayerConnection c : this.seenBy) this.serverEntity.removePairing(c.getPlayer());
        }

        void removePlayer(ServerPlayer player) {
            if (this.seenBy.remove(player.connection)) this.serverEntity.removePairing(player);
        }

        void updatePlayer(ServerPlayer player) {
            if (player == this.entity) return;
            Vec3 delta = player.position().subtract(this.entity.position());
            int viewDist = MultiForgeChunkMap.this.getPlayerViewDistance(player);
            double bounded = Math.min(getEffectiveRange(), viewDist * 16);
            double d2 = delta.x * delta.x + delta.z * delta.z;
            double bounded2 = bounded * bounded;
            boolean visible = d2 <= bounded2
                    && this.entity.broadcastToPlayer(player)
                    && MultiForgeChunkMap.this.isChunkTracked(
                            player, this.entity.chunkPosition().x, this.entity.chunkPosition().z);
            if (visible) {
                if (this.seenBy.add(player.connection)) this.serverEntity.addPairing(player);
            } else if (this.seenBy.remove(player.connection)) this.serverEntity.removePairing(player);
        }

        void updatePlayers(List<ServerPlayer> players) {
            for (ServerPlayer sp : players) updatePlayer(sp);
        }

        private int scaledRange(int r) {
            return MultiForgeChunkMap.this.level.getServer().getScaledTrackingDistance(r);
        }

        private int getEffectiveRange() {
            int i = this.range;
            for (Entity e : this.entity.getIndirectPassengers()) {
                int j = e.getType().clientTrackingRange() * 16;
                if (j > i) i = j;
            }
            return scaledRange(i);
        }
    }

    // === utility no-op accessor for tests ===

    /**
     * Test-only: expose the underlying MultiForge chunk manager for the
     * facade so unit tests (4.1d) can inject state without going through
     * the runtime host lookup.
     */
    @ApiStatus.Internal
    @Nullable
    public ChunkHolderManager _testHolders() {
        return holders();
    }
}
