/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.chunk;

import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.RegionId;

/**
 * MultiForge's per-chunk holder — the moral equivalent of Vanilla's
 * {@code ChunkHolder} plus Moonrise's {@code NewChunkHolder}. Owns the
 * chunk's identity (world + position), the region currently responsible
 * for ticking it, the effective {@link ChunkLoadLevel}, and — after
 * Phase 2 of the M9 landing plan — the full chunk-holder state:
 *
 * <ul>
 *   <li><b>Payload</b> — the actual {@code LevelChunk} instance once the
 *       holder has reached BORDER ({@code currentChunk}).</li>
 *   <li><b>Future gates</b> — three {@code CompletableFuture}s tracking
 *       promotion to the FULL / TICKING / ENTITY_TICKING stages, plus a
 *       per-{@code ChunkStatus} ladder of generation futures.</li>
 *   <li><b>Player watchers</b> — the set of {@code ServerPlayer}s that
 *       currently receive chunk-payload packets for this chunk.</li>
 *   <li><b>Per-tick caches</b> — memoised iterables of entities and
 *       block-entities in the chunk, invalidated at the start of each
 *       tick to avoid re-scanning the chunk's section list.</li>
 *   <li><b>Broadcast accumulators</b> — a packed-local-position set of
 *       block changes and a bitset of section light changes queued to
 *       fire during the outbound-flush phase.</li>
 *   <li><b>Save/send sync</b> — two {@code CompletableFuture<Void>}s
 *       that gate autosave and outbound-send ordering so late writes
 *       chain onto in-flight work.</li>
 *   <li><b>Level-change listener</b> — a functional hook fired whenever
 *       {@link #setLevel(ChunkLoadLevel)} publishes a new load level,
 *       so the fork glue can propagate the change into Vanilla's
 *       int-level {@code ChunkHolder.setTicketLevel} path.</li>
 * </ul>
 *
 * <h2>Type independence</h2>
 * The {@code multiforge-runtime} module deliberately does not depend on
 * {@code net.minecraft.*}. Fields that logically hold a Minecraft type
 * ({@code LevelChunk}, {@code ChunkAccess}, {@code ChunkResult},
 * {@code ServerPlayer}, {@code Entity}, {@code BlockEntity}) are typed
 * as {@link Object} (or a {@code CompletableFuture<Object>} / an
 * {@code Iterable<Object>}) and the fork glue under
 * {@code net.multiforge.neoforge.chunk.*} casts to the real type on the
 * way in and out. Each field's javadoc records the concrete type the
 * production wiring is expected to store; Phase 4 fork glue makes that
 * type assertion explicit, and a future revision may migrate the
 * holder onto generic type parameters once the ripple is worth paying.
 *
 * <h2>Threading contract</h2>
 * Every added field is either {@code final}, {@code volatile}, or an
 * {@code Atomic*} type. Mutable collections
 * ({@link #playersWatching}, {@link #blocksToBroadcast},
 * {@link #sectionLightChanged}) are guarded by synchronisation on the
 * collection instance itself. See
 * {@code docs/design/m9-contracts.md} §1.3 for the full read/write
 * matrix. In production the following methods are single-writer
 * (owning region worker only): {@link #setLevel},
 * {@link #markDirty}, {@link #setPendingFullLoadUpdate},
 * {@link #setCurrentChunk}, {@link #invalidateEntitiesCache},
 * {@link #invalidateBlockEntitiesCache}, and the future-gate setters.
 * {@link #clearDirty}, {@link #drainBlockChanges} and
 * {@link #drainLightChanges} are drainer-only.
 *
 * <p>The M3 patch binds one holder per {@code (level, chunkPos)} and
 * replaces Vanilla's holder in {@code ServerChunkCache}; after M9
 * Phase 4/5 the same holder is the sole source of truth for the state
 * above, with Vanilla {@code ChunkHolder} carrying only a shadow
 * reference to it.
 */
public final class NewChunkHolder {

    /**
     * Safe upper bound on {@code ChunkStatus.getStatusList().size()}
     * used by the no-ladder-size constructor. Vanilla 1.21.1 has 12
     * statuses; the buffer allows a handful of mod-added intermediate
     * statuses without a runtime resize. Callers that know the exact
     * size (fork glue at Phase 4 wire-in time) should prefer the
     * explicit-size constructor to allocate a tight array.
     */
    public static final int DEFAULT_STATUS_LADDER_SIZE = 16;

    private final WorldRef world;
    private final ChunkPos position;
    private final AtomicReference<RegionId> owningRegion = new AtomicReference<>();
    private volatile ChunkLoadLevel level = ChunkLoadLevel.INACCESSIBLE;
    private volatile boolean dirty;
    private volatile boolean pendingFullLoadUpdate;

    // === Phase 2.1 — chunk payload =====================================

    /**
     * Reference to the actual chunk payload attached at BORDER load and
     * detached on the INACCESSIBLE transition. In production this
     * stores a {@code net.minecraft.world.level.chunk.LevelChunk};
     * runtime code treats it as {@link Object}. Any-thread read; owning
     * region worker only for writes.
     */
    private final AtomicReference<Object> currentChunk = new AtomicReference<>();

    // === Phase 2.2 — future gates ======================================

    /**
     * Future completed once the chunk reaches BORDER, carrying a
     * {@code ChunkResult<LevelChunk>}. Volatile — the owning region
     * worker publishes; any thread reads.
     */
    private volatile CompletableFuture<Object> fullChunkFuture = unavailableFuture();

    /**
     * Future completed once the chunk reaches TICKING, carrying a
     * {@code ChunkResult<LevelChunk>}.
     */
    private volatile CompletableFuture<Object> tickingChunkFuture = unavailableFuture();

    /**
     * Future completed once the chunk reaches ENTITY_TICKING, carrying
     * a {@code ChunkResult<LevelChunk>}.
     */
    private volatile CompletableFuture<Object> entityTickingChunkFuture = unavailableFuture();

    // === Phase 2.3 — generation ladder =================================

    /**
     * Per-{@code ChunkStatus} generation-stage futures indexed by the
     * status ordinal in {@code ChunkStatus.getStatusList()}. Each entry
     * is a {@code CompletableFuture<ChunkResult<ChunkAccess>>}.
     * Outer array is {@code final}; each slot is an
     * {@link AtomicReferenceArray} atomic ref.
     */
    private final AtomicReferenceArray<CompletableFuture<Object>> statusFutures;

    // === Phase 2.4 — player watchers ===================================

    /**
     * Players currently receiving payload packets for this chunk. In
     * production stores {@code net.minecraft.server.level.ServerPlayer}.
     * Backed by {@link ConcurrentHashMap#newKeySet()} — thread-safe
     * add/remove/iterate; iteration tolerates concurrent mutation.
     */
    private final Set<Object> playersWatching = ConcurrentHashMap.newKeySet();

    // === Phase 2.5 — per-tick entity cache =============================

    /**
     * Memoised iterable of entities in the chunk, recomputed once per
     * tick at the start of {@code Phase.ENTITY_AI}. In production
     * stores {@code Iterable<Entity>}. Owning region worker only —
     * both read and write.
     */
    private final AtomicReference<Iterable<Object>> cachedEntities = new AtomicReference<>();

    // === Phase 2.6 — per-tick block-entity cache =======================

    /**
     * Memoised iterable of block-entities in the chunk, recomputed
     * once per tick at the start of {@code Phase.BLOCK_ENTITIES}. In
     * production stores {@code Iterable<BlockEntity>}. Owning region
     * worker only.
     */
    private final AtomicReference<Iterable<Object>> cachedBlockEntities = new AtomicReference<>();

    // === Phase 2.7 — block-broadcast accumulator =======================

    /**
     * Set of packed-local block positions changed since the last
     * outbound-flush drain. Logically a {@code ShortSet} — a
     * {@code fastutil ShortOpenHashSet} would be the production-shape
     * container, but the runtime module deliberately holds no fastutil
     * dependency; a JDK {@code HashSet<Short>} keeps the code
     * framework-free at the cost of boxing per change.
     * <p>Guard: synchronise on the set itself.
     */
    private final Set<Short> blocksToBroadcast = new HashSet<>();

    /**
     * Coarse-grained counter of pending block-broadcast changes. Any
     * thread may increment; drainer reads and resets. Useful for a
     * lock-free "has work?" probe outside the {@link #blocksToBroadcast}
     * monitor.
     */
    private final AtomicInteger broadcastPending = new AtomicInteger();

    // === Phase 2.8 — section-light-change accumulator ==================

    /**
     * Bitset over chunk section indices (Y-index) whose light data has
     * changed since the last drain. Guard: synchronise on the bitset
     * itself.
     */
    private final BitSet sectionLightChanged = new BitSet();

    // === Phase 2.9 — save / send sync ==================================

    /**
     * Chained save-completion sync — every autosave enqueue chains onto
     * this future so drains observe write-write ordering. Owning
     * region worker publishes new stages; any thread may read to add a
     * dependency.
     */
    private volatile CompletableFuture<Void> saveSyncFuture = CompletableFuture.completedFuture(null);

    /**
     * Chained send-completion sync — same shape as
     * {@link #saveSyncFuture} but for outbound send ordering.
     */
    private volatile CompletableFuture<Void> sendSyncFuture = CompletableFuture.completedFuture(null);

    // === Phase 2.10 — level-change listener wire =======================

    /**
     * Fires whenever this holder's {@link #level} publishes a new
     * value. Vanilla-shaped: int levels via
     * {@link ChunkLoadLevel#distance()} on each end, an
     * {@link IntConsumer} setter that re-enters the holder to publish
     * a caller-adjusted level. Single-slot (assign-last-wins) so the
     * fork glue can install exactly one bridge in production; a
     * {@code CopyOnWriteArrayList}-based multi-listener form is left
     * to a later phase if more than one bridge is ever needed.
     */
    private volatile LevelChangeListener levelChangeListener;

    public NewChunkHolder(WorldRef world, ChunkPos position) {
        this(world, position, DEFAULT_STATUS_LADDER_SIZE);
    }

    /**
     * Construct a holder with an explicit generation-ladder size,
     * typically {@code ChunkStatus.getStatusList().size()} at the
     * fork's call site. See {@link #DEFAULT_STATUS_LADDER_SIZE}.
     */
    public NewChunkHolder(WorldRef world, ChunkPos position, int statusLadderSize) {
        this.world = world;
        this.position = position;
        if (statusLadderSize < 0) {
            throw new IllegalArgumentException("statusLadderSize must be >= 0, got " + statusLadderSize);
        }
        this.statusFutures = new AtomicReferenceArray<>(statusLadderSize);
    }

    // === Identity + ownership ==========================================

    public WorldRef world() {
        return world;
    }

    public ChunkPos position() {
        return position;
    }

    public RegionId owningRegion() {
        return owningRegion.get();
    }

    /**
     * Publish a new owner. Returns {@code true} iff the owner actually
     * changed — callers use this to decide whether to enqueue a
     * migration hook.
     */
    public boolean setOwningRegion(RegionId id) {
        RegionId prev = owningRegion.getAndSet(id);
        return prev == null || !prev.equals(id);
    }

    // === Level + listener ==============================================

    public ChunkLoadLevel level() {
        return level;
    }

    /**
     * Publish a new load level and fire the registered
     * {@link LevelChangeListener}, if any. Owning region worker only.
     */
    public void setLevel(ChunkLoadLevel level) {
        ChunkLoadLevel prev = this.level;
        this.level = level;
        LevelChangeListener listener = this.levelChangeListener;
        if (listener != null && prev != level) {
            // The IntConsumer setter accepts a Vanilla-style distance number and
            // re-publishes the corresponding ChunkLoadLevel; the IntSupplier
            // exposes the pre-update level so listeners can compare thresholds
            // without racing another concurrent setLevel.
            IntSupplier oldLevelSupplier = prev::distance;
            IntConsumer levelSetter = d -> this.level = ChunkLoadLevel.forDistance(d);
            listener.onLevelChange(position, oldLevelSupplier, level.distance(), levelSetter);
        }
    }

    // === Dirty / autosave ==============================================

    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void clearDirty() {
        this.dirty = false;
    }

    // === Full-load-update pump =========================================

    public boolean pendingFullLoadUpdate() {
        return pendingFullLoadUpdate;
    }

    public void setPendingFullLoadUpdate(boolean value) {
        this.pendingFullLoadUpdate = value;
    }

    // === Phase 2.1 — chunk payload =====================================

    /**
     * Current chunk payload (a {@code LevelChunk} in production), or
     * {@code null} if the holder is below BORDER. Any thread.
     */
    public Object getCurrentChunk() {
        return currentChunk.get();
    }

    /**
     * Attach or detach the chunk payload. Owning region worker only.
     * @param chunk a {@code LevelChunk} in production, or {@code null}
     *              to detach on the INACCESSIBLE demote path
     */
    public void setCurrentChunk(Object chunk) {
        currentChunk.set(chunk);
    }

    // === Phase 2.2 — future gates ======================================

    /** Future carrying the {@code ChunkResult<LevelChunk>} at BORDER. */
    public CompletableFuture<Object> getFullChunkFuture() {
        return fullChunkFuture;
    }

    /** Owning region worker only. Replaces the BORDER-stage gate. */
    public void setFullChunkFuture(CompletableFuture<Object> future) {
        this.fullChunkFuture = future == null ? unavailableFuture() : future;
    }

    /** Future carrying the {@code ChunkResult<LevelChunk>} at TICKING. */
    public CompletableFuture<Object> getTickingChunkFuture() {
        return tickingChunkFuture;
    }

    /** Owning region worker only. Replaces the TICKING-stage gate. */
    public void setTickingChunkFuture(CompletableFuture<Object> future) {
        this.tickingChunkFuture = future == null ? unavailableFuture() : future;
    }

    /** Future carrying the {@code ChunkResult<LevelChunk>} at ENTITY_TICKING. */
    public CompletableFuture<Object> getEntityTickingChunkFuture() {
        return entityTickingChunkFuture;
    }

    /** Owning region worker only. Replaces the ENTITY_TICKING-stage gate. */
    public void setEntityTickingChunkFuture(CompletableFuture<Object> future) {
        this.entityTickingChunkFuture = future == null ? unavailableFuture() : future;
    }

    // === Phase 2.3 — generation ladder =================================

    /** Length of the generation-ladder array (from ctor). */
    public int statusLadderSize() {
        return statusFutures.length();
    }

    /**
     * Return the {@code CompletableFuture<ChunkResult<ChunkAccess>>}
     * currently published for the status at {@code statusIndex}, or
     * {@code null} if none has been scheduled yet.
     */
    public CompletableFuture<Object> getStatusFuture(int statusIndex) {
        if (statusIndex < 0 || statusIndex >= statusFutures.length()) return null;
        return statusFutures.get(statusIndex);
    }

    /**
     * Publish a status-stage future at {@code statusIndex}. Owning
     * region worker only in production, though the slot is a
     * {@link AtomicReferenceArray} so a foreign-thread write is
     * memory-safe (last-writer-wins).
     */
    public void setStatusFuture(int statusIndex, CompletableFuture<Object> future) {
        if (statusIndex < 0 || statusIndex >= statusFutures.length()) {
            throw new IndexOutOfBoundsException(
                    "statusIndex " + statusIndex + " out of bounds for ladder size " + statusFutures.length());
        }
        statusFutures.set(statusIndex, future);
    }

    // === Phase 2.4 — player watchers ===================================

    /**
     * Add {@code player} to the watching set. Idempotent — a duplicate
     * add is a no-op. Any thread. In production the argument is a
     * {@code net.minecraft.server.level.ServerPlayer}.
     */
    public boolean addPlayer(Object player) {
        return playersWatching.add(player);
    }

    /**
     * Remove {@code player} from the watching set. Idempotent. Any
     * thread.
     */
    public boolean removePlayer(Object player) {
        return playersWatching.remove(player);
    }

    /** True iff at least one player currently watches this chunk. */
    public boolean hasPlayers() {
        return !playersWatching.isEmpty();
    }

    /**
     * Immutable snapshot of the current watcher set. Safe iteration
     * without concurrent-modification concerns; suitable for a
     * broadcast fan-out that must not observe mid-fold adds.
     */
    public Set<Object> playersSnapshot() {
        return Set.copyOf(playersWatching);
    }

    /**
     * Live thread-safe view of the watcher set. Iteration tolerates
     * concurrent add/remove but observers may or may not see
     * concurrent mutations. Prefer {@link #playersSnapshot()} for
     * stability.
     */
    public Set<Object> playersWatching() {
        return Collections.unmodifiableSet(playersWatching);
    }

    // === Phase 2.5 — per-tick entity cache =============================

    /**
     * Memoised iterable of entities in the chunk. In production stores
     * {@code Iterable<Entity>}. Owning region worker only.
     */
    public Iterable<Object> getEntitiesInChunk() {
        return cachedEntities.get();
    }

    /** Owning region worker only. Publishes or clears the entity-cache slot. */
    public void setEntitiesInChunk(Iterable<Object> entities) {
        cachedEntities.set(entities);
    }

    /** Drop the memoised entity iterable so the next read recomputes. */
    public void invalidateEntitiesCache() {
        cachedEntities.set(null);
    }

    // === Phase 2.6 — per-tick block-entity cache =======================

    /**
     * Memoised iterable of block-entities in the chunk. In production
     * stores {@code Iterable<BlockEntity>}. Owning region worker only.
     */
    public Iterable<Object> getBlockEntitiesInChunk() {
        return cachedBlockEntities.get();
    }

    /** Owning region worker only. Publishes or clears the block-entity cache slot. */
    public void setBlockEntitiesInChunk(Iterable<Object> blockEntities) {
        cachedBlockEntities.set(blockEntities);
    }

    /** Drop the memoised block-entity iterable so the next read recomputes. */
    public void invalidateBlockEntitiesCache() {
        cachedBlockEntities.set(null);
    }

    // === Phase 2.7 — block-broadcast accumulator =======================

    /**
     * Record a block change at packed local coordinate
     * {@code (localX, localY, localZ)} for later broadcast. Packing
     * matches Vanilla's {@code ChunkHolder}'s
     * {@code SectionPos.sectionRelativePos} — 4 bits X (0-15), 4 bits
     * Z (0-15), 8 bits Y (0-255) into a {@code short}. Any thread;
     * synchronised on the underlying set.
     */
    public void blockChanged(int localX, int localY, int localZ) {
        short packed = (short) ((localX & 0xF) << 12 | (localZ & 0xF) << 8 | (localY & 0xFF));
        synchronized (blocksToBroadcast) {
            blocksToBroadcast.add(packed);
        }
        broadcastPending.incrementAndGet();
    }

    /**
     * Atomically hand ownership of the accumulated block-change set to
     * the caller and reset the internal accumulator to empty. The
     * returned set is a fresh copy; safe to iterate without holding
     * the lock. Drainer only ({@code Phase.FLUSH_OUTBOUND}).
     */
    public Set<Short> drainBlockChanges() {
        Set<Short> drained;
        synchronized (blocksToBroadcast) {
            if (blocksToBroadcast.isEmpty()) {
                broadcastPending.set(0);
                return Set.of();
            }
            drained = new HashSet<>(blocksToBroadcast);
            blocksToBroadcast.clear();
        }
        broadcastPending.set(0);
        return drained;
    }

    /** Non-blocking probe of whether any block changes are pending. */
    public int pendingBroadcastCount() {
        return broadcastPending.get();
    }

    // === Phase 2.8 — section-light-change accumulator ==================

    /**
     * Record that the light data for chunk section {@code sectionIndex}
     * has changed. Any thread; synchronised on the underlying bitset.
     */
    public void sectionLightChanged(int sectionIndex) {
        synchronized (sectionLightChanged) {
            sectionLightChanged.set(sectionIndex);
        }
    }

    /**
     * Atomically hand ownership of the accumulated section-light-change
     * bitset to the caller and reset the accumulator to empty. Returns
     * a fresh copy. Drainer only.
     */
    public BitSet drainLightChanges() {
        synchronized (sectionLightChanged) {
            if (sectionLightChanged.isEmpty()) return new BitSet();
            BitSet copy = (BitSet) sectionLightChanged.clone();
            sectionLightChanged.clear();
            return copy;
        }
    }

    // === Phase 2.9 — save / send sync ==================================

    /** Chained save-completion sync. Read any thread; write owning region worker only. */
    public CompletableFuture<Void> getSaveSyncFuture() {
        return saveSyncFuture;
    }

    /** Owning region worker only. Replaces the save-sync gate. */
    public void setSaveSyncFuture(CompletableFuture<Void> future) {
        this.saveSyncFuture = future == null ? CompletableFuture.completedFuture(null) : future;
    }

    /** Chained send-completion sync. Same shape as save-sync. */
    public CompletableFuture<Void> getSendSyncFuture() {
        return sendSyncFuture;
    }

    /** Owning region worker only. Replaces the send-sync gate. */
    public void setSendSyncFuture(CompletableFuture<Void> future) {
        this.sendSyncFuture = future == null ? CompletableFuture.completedFuture(null) : future;
    }

    /**
     * Chain {@code dep} into the send-sync gate so any future
     * outbound-send caller waits on it as well. Preserves Vanilla
     * {@code ChunkHolder.addSendDependency} shape. Any thread — the
     * volatile read + CAS on the gate makes late writers monotonic.
     */
    public void addSendDependency(CompletableFuture<?> dep) {
        if (dep == null || dep.isDone()) return;
        // Non-atomic combine — races between concurrent addSendDependency
        // callers publish the last winner and drop the intermediates. The
        // Vanilla shape is main-thread-only so this matches; per-region
        // callers are already single-writer. Documented for reviewers.
        this.sendSyncFuture = this.sendSyncFuture.thenCombine(dep, (a, b) -> null);
    }

    // === Phase 2.10 — level-change listener ============================

    /**
     * Install (or clear, with {@code null}) the level-change listener.
     * Any thread; last-writer-wins.
     */
    public void setLevelChangeListener(LevelChangeListener listener) {
        this.levelChangeListener = listener;
    }

    /** Current listener, or {@code null} if none. */
    public LevelChangeListener getLevelChangeListener() {
        return levelChangeListener;
    }

    /**
     * Fired by {@link #setLevel(ChunkLoadLevel)} whenever the level
     * changes. Vanilla-shaped: int levels via
     * {@link ChunkLoadLevel#distance()}; the {@code levelSetter}
     * consumer re-enters the holder and republishes the level derived
     * from the caller-adjusted int distance.
     */
    @FunctionalInterface
    public interface LevelChangeListener {
        void onLevelChange(ChunkPos pos, IntSupplier oldLevel, int newLevel, IntConsumer levelSetter);
    }

    // === Utility ========================================================

    /**
     * Sentinel "not yet available" future for the promotion gates.
     * Returns an incomplete future so callers awaiting the gate block
     * naturally rather than short-circuiting on a completed sentinel.
     */
    private static CompletableFuture<Object> unavailableFuture() {
        return new CompletableFuture<>();
    }

    /** Package-private read of the raw watching set; test-only helper. */
    List<Object> _testPlayersWatchingSnapshot() {
        return List.copyOf(playersWatching);
    }

    @Override
    public String toString() {
        return "NewChunkHolder[" + world.dimensionId() + " " + position + " level=" + level + " owner="
                + owningRegion.get() + "]";
    }
}
