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
package net.multiforge.neoforge.chunk;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.ChunkLoadLevel;
import net.multiforge.runtime.chunk.InstanceRegistry;
import net.multiforge.runtime.chunk.NewChunkHolder;
import net.multiforge.runtime.chunk.PerChunkTickets;
import net.multiforge.runtime.chunk.PerRegionTicketMap;
import net.multiforge.runtime.chunk.TicketExpiryTicker;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionId;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import org.jetbrains.annotations.Nullable;

/**
 * MultiForge replacement for Vanilla {@link DistanceManager}.
 *
 * <p>Frozen design lives at {@code docs/design/multiforge-distancemanager.md};
 * public API contract at {@code docs/design/m9-contracts.md} §6. This is
 * Phase 4.2b of the M9 landing plan
 * ({@code plans/bubbly-jumping-comet.md}).
 *
 * <p><b>Purpose.</b> Vanilla {@code DistanceManager} owns a single
 * global {@code Long2ObjectMap<SortedArraySet<Ticket>>} and applies
 * ticket-level updates from the main thread in
 * {@code runAllUpdates(ChunkMap)}. Under MultiForge chunk state is
 * per-region; this class REPLACES the ledger and routes every ticket
 * write to the owning region's
 * {@link net.multiforge.runtime.chunk.PerRegionTicketMap} via
 * {@link ChunkHolderManager#addTicket(RegionId, net.multiforge.api.world.ChunkPos, net.multiforge.runtime.chunk.Ticket)}.
 * Promotion / demotion enqueues a full-load update on the owning region's
 * {@link net.multiforge.runtime.chunk.HolderManagerRegionData} that the
 * region worker drains in {@code Phase.INBOUND_MAILBOX} (Phase 5.1
 * wiring).
 *
 * <p><b>NeoForge {@code forceTicks} preservation.</b> The 5-arg
 * {@code addRegionTicket(..., boolean forceTicks)} and
 * {@code removeRegionTicket(..., boolean forceTicks)} overloads
 * introduced by NeoForge are preserved verbatim.
 * {@link #shouldForceTicks(long)} reports true iff any live ticket on the
 * chunk was added with {@code forceTicks=true}. Runtime truth for the
 * flag lives on the MultiForge runtime {@code Ticket} record after Phase
 * 4.8a; until that lands, this facade tracks a per-chunk force-ticks
 * counter as a side channel so the {@code shouldForceTicks} gate stays
 * honest for {@code ServerChunkCache}'s natural-spawning path.
 *
 * <p><b>Extending the Vanilla abstract base.</b> Extending
 * {@link DistanceManager} is required — {@code ChunkMap} exposes a
 * {@code DistanceManager}-typed reference, mods and NeoForge itself use
 * {@code instanceof} against it, and {@code ChunkMap$DistanceManager} is
 * Vanilla's only production subclass. The inherited long maps
 * ({@code tickets}, {@code forcedTickets}, {@code playersPerChunk}) stay
 * declared on the base and are intentionally empty at runtime — Vanilla
 * source that walks them via reflection sees empty (documented drift per
 * design §7). The abstract seams
 * ({@link #isChunkToRemove(long)}, {@link #getChunk(long)},
 * {@link #updateChunkScheduling(long, int, ChunkHolder, int)}) are
 * fulfilled by the {@code MultiForgeChunkMap.DistanceManager} inner
 * class (Phase 4.1b); until that lands, this class provides throwing
 * stubs so callers get a clear "wired by MultiForgeChunkMap" error
 * rather than an obscure NPE.
 *
 * <p><b>Threading.</b> Every public / protected method is safe from the
 * main thread and from any region worker. Ticket writes route via the
 * lock-free {@link ThreadedRegionizer#regionAtChunk(int, int)} into
 * {@link ChunkHolderManager#addTicket}, which is designed for
 * cross-region callers (contracts §2.2). The class holds no locks;
 * CLAUDE.md §4 golden rule applies — no {@code Thread.sleep}, no
 * {@code CompletableFuture.get}, no {@code synchronized}.
 */
public abstract class MultiForgeDistanceManager extends DistanceManager {
    /** Vanilla ticket-level corresponding to {@link FullChunkStatus#FULL} (== 33). */
    private static final int FULL_STATUS_LEVEL = ChunkLevel.byStatus(FullChunkStatus.FULL);

    /** Vanilla ticket-level corresponding to {@link FullChunkStatus#ENTITY_TICKING} (== 31). */
    private static final int ENTITY_TICKING_LEVEL = ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);

    /** Warn site for the rate-limited "chunk pre-regionizer" drop path. */
    private static final String NULL_REGION_WARN_SITE = "distance-manager.null-region";

    /** Immutable set of ticket-type names retained through {@link #removeTicketsOnClosing()}. */
    private static final java.util.Set<String> SHUTDOWN_KEEP_TYPE_NAMES = java.util.Set.of(TicketType.UNKNOWN.toString(), TicketType.POST_TELEPORT.toString());

    /**
     * Identity-keyed registry of every live {@link MultiForgeDistanceManager}
     * instance, keyed by the (abstract) {@link DistanceManager} reference the
     * Vanilla source-tree observation hunks see as {@code this}. Populated in
     * the constructor of this class; used by {@link #of(DistanceManager)} to
     * resolve observation callbacks fired from the base-class methods
     * {@code addPlayer}, {@code removePlayer}, {@code updateChunkForced} and
     * {@code runAllUpdates} — see
     * {@code multiforge-patches/04-chunk-system/net/minecraft/server/level/DistanceManager.java.patch}
     * (Phase 4 task 4.2c).
     *
     * <p>Backed by {@link InstanceRegistry#weak()} — weak keys let a
     * discarded {@link DistanceManager} + {@link MultiForgeDistanceManager}
     * pair reclaim without the registry pinning them, and the shared
     * helper's {@code synchronized(WeakHashMap)} wrapper adds only a tiny
     * critical section around a hash lookup: no cross-region contention
     * (CLAUDE.md §4 stays satisfied because the section is unconditionally
     * microscopic and never blocks the owning worker on anything the
     * calling thread doesn't own).
     *
     * <p>Round-5 H6: previously a raw {@code Collections.synchronizedMap(new
     * WeakHashMap<>())} field on this class. That shape is safe for
     * {@code get}/{@code put} but requires the caller hold the wrapper's
     * monitor for any iteration — a latent hazard for a future
     * all-instances walk (e.g. a {@code /multiforge distancemanagers}
     * command). {@link InstanceRegistry} centralizes this exact shape
     * (shared with {@link MultiForgeChunkMap} and
     * {@link MultiForgeLightEngine}) and exposes {@link
     * InstanceRegistry#snapshot()} for safe iteration, so the hazard is
     * fixed once for every facade instead of per call site.
     */
    private static final InstanceRegistry<DistanceManager, MultiForgeDistanceManager> INSTANCE_REGISTRY = InstanceRegistry.weak();

    protected final MultiThreadedSchedulerHost host;
    protected final WorldRef worldRef;
    protected final ChunkHolderManager holderManager;

    /**
     * Per-chunk-key counter of live tickets that were added with
     * {@code forceTicks=true}. Incremented on add, decremented on
     * remove; {@link #shouldForceTicks(long)} returns true whenever the
     * counter is positive. Kept as a side channel until Phase 4.8a
     * threads the {@code forceTicks} bit through
     * {@link net.multiforge.runtime.chunk.Ticket}.
     */
    private final ConcurrentMap<Long, AtomicInteger> forcedChunkCounts = new ConcurrentHashMap<>();

    /**
     * Simulation distance in chunks. Mirrors Vanilla's private
     * {@code simulationDistance} field (default 10). Updated only via
     * {@link #updateSimulationDistance(int)}; read by
     * {@link #getPlayerTicketLevel()} and by
     * {@link #addPlayer(SectionPos, ServerPlayer)}.
     */
    private volatile int mfSimulationDistance = 10;

    /**
     * View distance mirror. Updated only via
     * {@link #updatePlayerTickets(int)}. Kept for observability and for
     * Phase 5 wiring; not consumed on this class today.
     */
    private volatile int mfViewDistance = 0;

    /**
     * Sweeps expired tickets across every region owned by
     * {@link #holderManager}. Delegated to from
     * {@link #purgeStaleTickets()} — Vanilla's per-tick clock is
     * amortised by MultiForge (see {@link TicketExpiryTicker} docs).
     */
    private final TicketExpiryTicker expiryTicker;

    /**
     * Monotonic tick counter used to stamp {@code Ticket.createdAtTick}
     * on outbound MultiForge tickets. Vanilla increments its own
     * {@code ticketTickCounter} inside {@code purgeStaleTickets}; ours
     * increments on the same call so the two clocks stay aligned.
     */
    private volatile long mfTickCounter = 0L;

    /**
     * @param mainThreadExec Vanilla main-thread executor. Passed as the
     *                       second super arg so {@code super.mainThreadExecutor} is
     *                       populated for source-compat with mods reflecting on the field.
     * @param tickThreadExec Region-worker / background executor. Passed
     *                       as the first super arg where Vanilla's ctor constructs its
     *                       unused-under-MultiForge {@code ChunkTaskPriorityQueueSorter}
     *                       (see design §3).
     * @param host           The process-wide {@link MultiThreadedSchedulerHost}
     *                       that owns the per-world regionizer and chunk-holder-manager
     *                       tables. Never null in production; tests may pass a fake.
     * @param worldRef       The world this distance manager belongs to. Every
     *                       ticket write scopes region resolution to this world.
     */
    protected MultiForgeDistanceManager(
            Executor mainThreadExec, Executor tickThreadExec, MultiThreadedSchedulerHost host, WorldRef worldRef) {
        // Vanilla base ctor is DistanceManager(Executor bgExec, Executor mainExec).
        // Pass tickThreadExec as the bg slot and mainThreadExec as the main slot so
        // super.mainThreadExecutor lines up correctly. Neither is invoked at runtime
        // under MultiForge — the throttler and priority-queue-sorter super allocates
        // remain unreferenced (design §3) — but the executors themselves must be
        // non-null for the super allocations to succeed.
        super(tickThreadExec, mainThreadExec);
        this.host = java.util.Objects.requireNonNull(host, "host");
        this.worldRef = java.util.Objects.requireNonNull(worldRef, "worldRef");
        this.holderManager = host.chunkManagerFor(worldRef);
        this.expiryTicker = new TicketExpiryTicker(this.holderManager);
        // Publish this instance to the identity-keyed registry so
        // observation hunks in the abstract DistanceManager base
        // (multiforge-patches/04-chunk-system/.../DistanceManager.java.patch)
        // can resolve `this` back to a MultiForgeDistanceManager.
        INSTANCE_REGISTRY.register(this, this);
    }

    // === Region resolution ===

    /**
     * @return the region owning {@code (chunkX, chunkZ)} in this
     *         distance manager's world, or {@code null} when the section
     *         isn't yet registered with the regionizer (early boot, chunk
     *         far from any player). Callers on the null path emit a
     *         rate-limited warn — plan §Ground rule 5 — and drop the write;
     *         the next {@code addTicket} after the section joins a region
     *         succeeds.
     */
    @Nullable
    private Region regionOrNull(int chunkX, int chunkZ) {
        ThreadedRegionizer regionizer = host.regionizerForOrNull(worldRef);
        if (regionizer == null) return null;
        return regionizer.regionAtChunk(chunkX, chunkZ);
    }

    @Nullable
    private Region regionOrNull(long chunkKey) {
        return regionOrNull(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
    }

    private static net.multiforge.api.world.ChunkPos toMfPos(long chunkKey) {
        return new net.multiforge.api.world.ChunkPos(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
    }

    private static net.multiforge.api.world.ChunkPos toMfPos(ChunkPos vanilla) {
        return new net.multiforge.api.world.ChunkPos(vanilla.x, vanilla.z);
    }

    /**
     * Translate a Vanilla {@link Ticket} to a MultiForge runtime
     * {@link net.multiforge.runtime.chunk.Ticket}. Preserves the
     * Vanilla ticket level as the MultiForge {@code distance} field
     * (both are the same "lower is more loaded" scale). The Vanilla
     * per-type timeout carries through so
     * {@link TicketExpiryTicker} sweeps expiring tickets under the
     * same clock as Vanilla's own {@code purgeStaleTickets}. The
     * {@code createdAtTick} is stamped from this class's own
     * {@link #mfTickCounter} so writes made outside a tick body still
     * get a monotonic origin.
     */
    private net.multiforge.runtime.chunk.Ticket translate(Ticket<?> vanilla) {
        String typeName = vanilla.getType().toString();
        int level = vanilla.getTicketLevel();
        int timeout = (int) vanilla.getType().timeout();
        net.multiforge.runtime.chunk.TicketType mfType = net.multiforge.runtime.chunk.TicketType.of(typeName, level, timeout);
        // Deduplicate on the ticket's identity string — matches the
        // shadow bridge's approach in DistanceManagerBridge.
        String keyStr = String.valueOf(vanilla);
        long createdAt = vanilla.getCreatedTick();
        if (createdAt <= 0L) createdAt = mfTickCounter;
        return net.multiforge.runtime.chunk.Ticket.at(mfType, level, keyStr, createdAt);
    }

    /**
     * Build a MultiForge runtime {@code Ticket} directly from public
     * add/remove arguments (used when we don't have a Vanilla
     * {@link Ticket} in hand, e.g. player-add path).
     */
    private net.multiforge.runtime.chunk.Ticket translate(TicketType<?> type, int level, Object key, int timeout) {
        net.multiforge.runtime.chunk.TicketType mfType = net.multiforge.runtime.chunk.TicketType.of(type.toString(), level, timeout);
        return net.multiforge.runtime.chunk.Ticket.at(mfType, level, String.valueOf(key), mfTickCounter);
    }

    // === §4.2 Choke (REPLACED) ===
    //
    // Vanilla's own `addTicket(long, Ticket)` / `removeTicket(long, Ticket)`
    // are package-private in `net.minecraft.server.level`; Java does not
    // let a subclass in a different package override a package-private
    // method. Instead we route every one of this class's public entry
    // points (both the Vanilla-shape and NeoForge overloads,
    // updateChunkForced, addPlayer/removePlayer) through this private
    // choke. Nothing outside this file calls super's package-private
    // version once Phase 4.2c's patch shell instantiates this facade;
    // the Vanilla `tickets` map stays empty (design §3).

    /**
     * Choke that every public {@code addTicket / addRegionTicket /
     * addPlayer / updateChunkForced} eventually reaches. REPLACED per
     * design §4.2: routes to {@link ChunkHolderManager#addTicket}.
     */
    private void routeAddTicket(long pos, Ticket<?> vanilla) {
        Region region = regionOrNull(pos);
        if (region == null) {
            ViolationLogger.warn(
                    NULL_REGION_WARN_SITE,
                    "add " + vanilla.getType() + " at " + new ChunkPos(pos) + " in " + worldRef.dimensionId()
                            + " — no region; dropping (fires again once the section is regionized)");
            return;
        }
        net.multiforge.api.world.ChunkPos mfPos = toMfPos(pos);
        // Ensure a holder exists so ticket writes have a target (mirrors
        // DistanceManagerBridge — createHolder is idempotent).
        if (holderManager.holderAt(mfPos) == null) {
            holderManager.createHolder(mfPos, region.id());
        }
        holderManager.addTicket(region.id(), mfPos, translate(vanilla));
        if (vanilla.isForceTicks()) trackForceTicks(pos, true);
    }

    /**
     * Symmetric REPLACEMENT of Vanilla {@code removeTicket(long, Ticket)}.
     * Routes to {@link ChunkHolderManager#removeTicket}; symmetric
     * force-ticks decrement.
     */
    private void routeRemoveTicket(long pos, Ticket<?> vanilla) {
        Region region = regionOrNull(pos);
        if (region == null) {
            // No region means no per-region ticket map either — silent drop.
            return;
        }
        holderManager.removeTicket(region.id(), toMfPos(pos), translate(vanilla));
        if (vanilla.isForceTicks()) trackForceTicks(pos, false);
    }

    // === §4.3 Public add / remove — KEPT signatures, REPLACED bodies ===

    /**
     * Public entry that hands the caller-supplied {@code (type, level,
     * key)} through as a Vanilla {@link Ticket}, then routes via the
     * package-private choke. Preserves Vanilla signature; identical
     * shape to Vanilla's {@code DistanceManager.addTicket:182-184}.
     */
    @Override
    public <T> void addTicket(TicketType<T> type, ChunkPos pos, int level, T key) {
        // 4-arg Ticket ctor is the only publicly-accessible one; the
        // 3-arg form is protected in Vanilla and unreachable from this
        // package.
        this.routeAddTicket(pos.toLong(), new Ticket<>(type, level, key, false));
    }

    @Override
    public <T> void removeTicket(TicketType<T> type, ChunkPos pos, int level, T key) {
        this.routeRemoveTicket(pos.toLong(), new Ticket<>(type, level, key, false));
    }

    /** Convenience — forwards to the 5-arg overload with {@code forceTicks=false}. */
    @Override
    public <T> void addRegionTicket(TicketType<T> type, ChunkPos pos, int distance, T key) {
        this.addRegionTicket(type, pos, distance, key, false);
    }

    /**
     * NeoForge 5-arg overload — MUST preserve. Sets the runtime
     * {@code forceTicks} bit on the resulting MultiForge ticket by way
     * of the per-chunk force-ticks counter (side channel until Phase
     * 4.8a lands the bit on the runtime {@code Ticket} record itself).
     * Vanilla ticket-level math: {@code FULL_STATUS_LEVEL - distance}.
     */
    @Override
    public <T> void addRegionTicket(TicketType<T> type, ChunkPos pos, int distance, T key, boolean forceTicks) {
        Ticket<T> ticket = new Ticket<>(type, FULL_STATUS_LEVEL - distance, key, forceTicks);
        this.routeAddTicket(pos.toLong(), ticket);
    }

    @Override
    public <T> void removeRegionTicket(TicketType<T> type, ChunkPos pos, int distance, T key) {
        this.removeRegionTicket(type, pos, distance, key, false);
    }

    /** Symmetric NeoForge 5-arg overload. */
    @Override
    public <T> void removeRegionTicket(TicketType<T> type, ChunkPos pos, int distance, T key, boolean forceTicks) {
        Ticket<T> ticket = new Ticket<>(type, FULL_STATUS_LEVEL - distance, key, forceTicks);
        this.routeRemoveTicket(pos.toLong(), ticket);
    }

    // === §4.4 Forced chunks (REPLACED) ===

    /**
     * Route {@code /forceload add|remove} through MultiForge's
     * {@code TicketType.FORCED} equivalent. Sets {@code forceTicks=true}
     * on the runtime ticket per design §4.4 so
     * {@link #shouldForceTicks(long)} covers this chunk after the add.
     */
    @Override
    protected void updateChunkForced(ChunkPos pos, boolean add) {
        Ticket<ChunkPos> ticket = new Ticket<>(TicketType.FORCED, ChunkMap.FORCED_TICKET_LEVEL, pos, true);
        long key = pos.toLong();
        if (add) {
            this.routeAddTicket(key, ticket);
        } else {
            this.routeRemoveTicket(key, ticket);
        }
    }

    // === §4.5 Players (REPLACED) ===

    /**
     * Register {@code player} against the chunk-holder's watching set
     * (Phase 2.4 wiring on {@link NewChunkHolder#addPlayer}) and emit
     * a {@code TicketType.PLAYER} region ticket at
     * {@link #getPlayerTicketLevel()}. Full Phase 1.9 promoter wiring —
     * radius fan-out over surrounding chunks — is a Phase 5.6 task; this
     * covers the center-chunk write so the holder's watcher set is
     * correct today.
     */
    @Override
    public void addPlayer(SectionPos section, ServerPlayer player) {
        ChunkPos chunkPos = section.chunk();
        long key = chunkPos.toLong();
        Region region = regionOrNull(key);
        if (region != null) {
            NewChunkHolder holder = holderManager.holderAt(toMfPos(key));
            if (holder == null) {
                holder = holderManager.createHolder(toMfPos(key), region.id());
            }
            holder.addPlayer(player);
        }
        // Emit the player ticket via the addTicket choke so region
        // resolution + null-region handling is uniform.
        Ticket<ChunkPos> ticket = new Ticket<>(TicketType.PLAYER, getPlayerTicketLevel(), chunkPos, false);
        this.routeAddTicket(key, ticket);
    }

    @Override
    public void removePlayer(SectionPos section, ServerPlayer player) {
        ChunkPos chunkPos = section.chunk();
        long key = chunkPos.toLong();
        Region region = regionOrNull(key);
        if (region != null) {
            NewChunkHolder holder = holderManager.holderAt(toMfPos(key));
            if (holder != null) holder.removePlayer(player);
        }
        Ticket<ChunkPos> ticket = new Ticket<>(TicketType.PLAYER, getPlayerTicketLevel(), chunkPos, false);
        this.routeRemoveTicket(key, ticket);
    }

    /**
     * Vanilla ticket level a player ticket keeps its center chunk at.
     * Pure math — no state touched. Matches Vanilla
     * {@code DistanceManager.getPlayerTicketLevel:249-251}.
     */
    private int getPlayerTicketLevel() {
        return Math.max(0, ENTITY_TICKING_LEVEL - mfSimulationDistance);
    }

    // === §4.6 View / simulation distance (REPLACED) ===

    /**
     * Vanilla dispatches to its internal {@code PlayerTicketTracker};
     * MultiForge fans the change out to every live region so the
     * per-region player-radius promoter (Phase 5) re-evaluates. Today
     * this stores the value + emits a diagnostic probe; the actual
     * fan-out lands with Phase 5 wiring.
     */
    @Override
    protected void updatePlayerTickets(int viewDistance) {
        this.mfViewDistance = viewDistance;
        // Phase 5.6 wires this into per-region promoters; the value is
        // stored on this facade so Phase 4.1b's MultiForgeChunkMap can
        // read it in the interim.
    }

    /**
     * Track simulation distance so {@link #getPlayerTicketLevel()}
     * reports the correct level for freshly-added players. Fan-out to
     * existing player tickets lands with Phase 5.
     */
    @Override
    public void updateSimulationDistance(int simulationDistance) {
        if (simulationDistance != this.mfSimulationDistance) {
            this.mfSimulationDistance = simulationDistance;
        }
    }

    // === §4.7 Reads (REPLACED) ===

    /**
     * Read the holder's effective load level and answer against the
     * ENTITY_TICKING threshold. Cross-region safe: {@link ChunkHolderManager}
     * is backed by a {@link ConcurrentMap}.
     */
    @Override
    public boolean inEntityTickingRange(long pos) {
        NewChunkHolder holder = holderManager.holderAt(toMfPos(pos));
        return holder != null && holder.level().isAtLeast(ChunkLoadLevel.ENTITY_TICKING);
    }

    @Override
    public boolean inBlockTickingRange(long pos) {
        NewChunkHolder holder = holderManager.holderAt(toMfPos(pos));
        return holder != null && holder.level().isAtLeast(ChunkLoadLevel.TICKING);
    }

    /**
     * Naturally-spawnable chunk count. Design §4.7 puts the underlying
     * {@code FixedPlayerDistanceChunkTracker} on
     * {@code HolderManagerRegionData} in Phase 4.2b step 10 — until that
     * lands, sum the count of chunks with at least one live
     * player-watcher across the per-world holder table. Matches
     * Vanilla's semantic (chunks with a player within 8) closely
     * enough for the mob-cap gate without requiring the tracker.
     */
    @Override
    public int getNaturalSpawnChunkCount() {
        int count = 0;
        for (NewChunkHolder holder : holderManager.holders()) {
            if (holder.hasPlayers()) count++;
        }
        return count;
    }

    /**
     * True iff the chunk has at least one player-watcher. Vanilla checks
     * whether {@code naturalSpawnChunkCounter.chunks} contains the key
     * with level {@code <= 8}; MultiForge answers on the holder's
     * watcher-set as an initial approximation. Full parity with
     * Vanilla's fixed-distance tracker lands with Phase 4.2b step 10.
     */
    @Override
    public boolean hasPlayersNearby(long pos) {
        NewChunkHolder holder = holderManager.holderAt(toMfPos(pos));
        return holder != null && holder.hasPlayers();
    }

    /**
     * @return a short per-world summary of ticket / holder counts.
     *         Replaces Vanilla's {@code ticketThrottler.getDebugStatus()}
     *         which no longer exists under MultiForge.
     */
    @Override
    public String getDebugStatus() {
        return "MultiForgeDistanceManager["
                + worldRef.dimensionId()
                + " holders=" + holderManager.holderCount()
                + " simDist=" + mfSimulationDistance
                + " viewDist=" + mfViewDistance
                + "]";
    }

    /**
     * NeoForge {@code shouldForceTicks} contract — MUST preserve.
     * Returns true iff any live ticket on {@code chunkPos} was added
     * with {@code forceTicks=true}. Read by NeoForge's
     * {@code ServerChunkCache.tickChunks} to opt a chunk into natural
     * spawning independently of a nearby player, and by the mob-tick
     * pipeline. Preservation of this gate is what keeps `/forceload`ed
     * chunks with force-ticks flags spawning mobs after Phase 4 lands.
     */
    @Override
    public boolean shouldForceTicks(long chunkPos) {
        AtomicInteger counter = forcedChunkCounts.get(chunkPos);
        return counter != null && counter.get() > 0;
    }

    /** Increment / decrement the per-chunk force-ticks counter. */
    private void trackForceTicks(long chunkKey, boolean add) {
        if (add) {
            forcedChunkCounts
                    .computeIfAbsent(chunkKey, k -> new AtomicInteger())
                    .incrementAndGet();
        } else {
            forcedChunkCounts.compute(chunkKey, (k, v) -> {
                if (v == null) return null;
                if (v.decrementAndGet() <= 0) return null;
                return v;
            });
        }
    }

    /**
     * Return a short debug string for the highest-priority ticket on a
     * chunk, or {@code "no_ticket"} if none. Reads the per-region
     * ticket map for the chunk's owning region — no allocation on the
     * empty path.
     */
    @Override
    protected String getTicketDebugString(long pos) {
        Region region = regionOrNull(pos);
        if (region == null) return "no_ticket";
        PerRegionTicketMap map = holderManager.ticketsFor(region.id());
        PerChunkTickets tickets = map.ticketsAt(toMfPos(pos));
        if (tickets == null || tickets.isEmpty()) return "no_ticket";
        // PerChunkTickets doesn't expose a "first" accessor — snapshot
        // is O(n) but per-chunk ticket lists are tiny (Vanilla docs the
        // sorted-array-set at initial capacity 4). Only ever called
        // from debug / observability paths.
        java.util.Iterator<net.multiforge.runtime.chunk.Ticket> it = tickets.snapshot().iterator();
        return it.hasNext() ? it.next().toString() : "no_ticket";
    }

    // === §4.8 Tick end-of-frame ===

    /**
     * NO-OP under MultiForge (design §6). Vanilla batches per-tick
     * ticket-level changes here; MultiForge applies them inline via
     * {@link ChunkHolderManager#addTicket} which enqueues a full-load
     * update on the owning region's data. That queue drains in the
     * region's own tick phase 1 ({@code Phase.INBOUND_MAILBOX}) once
     * Phase 5.1 wiring lands — this facade has nothing to do at end of
     * frame. Signature preserved so {@code ChunkMap.tick}'s call site
     * still compiles.
     *
     * @return always {@code false} — nothing dispatched from here.
     */
    @Override
    public boolean runAllUpdates(ChunkMap chunkMap) {
        return false;
    }

    /**
     * Sweep expired tickets. Vanilla walks the global {@code tickets}
     * map each tick; MultiForge delegates to
     * {@link TicketExpiryTicker#runOnce(long)} which iterates every
     * region's per-region ticket map. The internal tick counter is
     * bumped so runtime {@code Ticket.createdAtTick} stamps advance
     * with each pass, matching Vanilla's
     * {@code ticketTickCounter} progression.
     */
    @Override
    protected void purgeStaleTickets() {
        mfTickCounter++;
        expiryTicker.runOnce(mfTickCounter);
    }

    // === §4.9 Shutdown / debug (REPLACED) ===

    /**
     * Walk every region's ticket map and remove everything whose type
     * name is not in {@link #SHUTDOWN_KEEP_TYPE_NAMES}. Design §4.9
     * pins execution to the main thread during shutdown after
     * {@code RegionShutdownCoordinator} (Phase 5.5) has quiesced region
     * workers, so this walk is single-writer. Removes travel through
     * {@link ChunkHolderManager#removeTicket} so demotion runs through
     * the normal channel.
     */
    @Override
    public void removeTicketsOnClosing() {
        ThreadedRegionizer regionizer = host.regionizerForOrNull(worldRef);
        if (regionizer == null) return;
        for (Region region : regionizer.regions()) {
            RegionId id = region.id();
            PerRegionTicketMap map = holderManager.ticketsFor(id);
            // Snapshot the chunk keys first so we can mutate the map
            // while iterating (PerRegionTicketMap.removeTicket may drop
            // the chunk-tickets entry when the last ticket goes).
            java.util.List<net.multiforge.api.world.ChunkPos> chunks = new java.util.ArrayList<>(map.loadedChunks());
            for (net.multiforge.api.world.ChunkPos pos : chunks) {
                PerChunkTickets tickets = map.ticketsAt(pos);
                if (tickets == null) continue;
                for (net.multiforge.runtime.chunk.Ticket t : tickets.snapshot()) {
                    if (!SHUTDOWN_KEEP_TYPE_NAMES.contains(t.type().name())) {
                        holderManager.removeTicket(id, pos, t);
                    }
                }
            }
        }
        // Also clear the force-ticks counter — the underlying tickets
        // are gone (or protected UNKNOWN/POST_TELEPORT, neither of
        // which sets forceTicks).
        forcedChunkCounts.clear();
    }

    /**
     * @return true iff at least one region has at least one live
     *         ticket. Cheap scan over per-region maps (typically single
     *         digits of regions).
     */
    @Override
    public boolean hasTickets() {
        ThreadedRegionizer regionizer = host.regionizerForOrNull(worldRef);
        if (regionizer == null) return false;
        for (Region region : regionizer.regions()) {
            if (holderManager.ticketsFor(region.id()).chunkCount() > 0) return true;
        }
        return false;
    }

    // === §4.10 Abstract seams — fulfilled by MultiForgeChunkMap.DistanceManager ===
    //
    // Design §9 says these three are provided by Phase 4.1b's inner
    // class subclassing this facade. Concrete throwers here so that
    // (a) subclasses that DO override compile, and (b) if this class
    // ever gets instantiated directly, callers get a clear message
    // pointing at the wiring gap rather than an obscure NPE downstream.

    @Override
    protected boolean isChunkToRemove(long pos) {
        throw new UnsupportedOperationException("wired by MultiForgeChunkMap");
    }

    @Nullable
    @Override
    protected ChunkHolder getChunk(long pos) {
        throw new UnsupportedOperationException("wired by MultiForgeChunkMap");
    }

    @Nullable
    @Override
    protected ChunkHolder updateChunkScheduling(long pos, int newLevel, @Nullable ChunkHolder oldHolder, int oldLevel) {
        throw new UnsupportedOperationException("wired by MultiForgeChunkMap");
    }

    // === Accessors for test / observability ===

    /** @return this facade's world identifier. */
    public WorldRef worldRef() {
        return worldRef;
    }

    /** @return the per-world holder manager routing every ticket write. */
    public ChunkHolderManager holderManager() {
        return holderManager;
    }

    /** @return current simulation distance (chunks). Test-visible accessor. */
    public int simulationDistance() {
        return mfSimulationDistance;
    }

    /** @return current view distance (chunks). Test-visible accessor. */
    public int viewDistance() {
        return mfViewDistance;
    }

    /**
     * @return current internal tick counter driving expiry-clock
     *         stamps on outbound MultiForge tickets. Bumped by
     *         {@link #purgeStaleTickets()}.
     */
    public long tickCounter() {
        return mfTickCounter;
    }

    // === §4.11 Static observers ===
    //
    // Called from thin observation hunks patched into the abstract
    // net.minecraft.server.level.DistanceManager base (Phase 4 task 4.2c).
    // Vanilla keeps owning canonical state for `tickets`, `playersPerChunk`,
    // `ticketTracker` etc.; these hooks build a parallel MultiForge view.
    // Contract: never throw, never block, safe from any thread. When the
    // supplied DistanceManager isn't a MultiForge instance (bootstrap or
    // pure-Vanilla path) each observer no-ops after bumping an ".unbound"
    // probe so the omission is visible in /multiforge diagnostics without
    // touching Vanilla control flow.

    /**
     * @return the {@link MultiForgeDistanceManager} previously registered
     *         against {@code dm} in {@link #INSTANCE_REGISTRY}, or empty when
     *         none. Never throws.
     */
    public static java.util.Optional<MultiForgeDistanceManager> of(@Nullable DistanceManager dm) {
        return INSTANCE_REGISTRY.of(dm);
    }

    /**
     * Observation hook for
     * {@link DistanceManager#addPlayer(SectionPos, ServerPlayer)}. Called
     * from the patched base after Vanilla has committed the player to its
     * per-chunk set + trackers. Zero behaviour change; bumps
     * {@code mfdistmgr.observe.playerAdded} (plus {@code .unbound} when
     * {@code dm} has no MultiForge instance registered).
     */
    public static void observePlayerAdded(
            @Nullable DistanceManager dm, @Nullable SectionPos section, @Nullable ServerPlayer player) {
        java.util.Optional<MultiForgeDistanceManager> resolved = of(dm);
        ProbeRegistry.bump("mfdistmgr.observe.playerAdded");
        if (resolved.isEmpty()) {
            ProbeRegistry.bump("mfdistmgr.observe.playerAdded.unbound");
        }
        // Phase 5 wiring may fan the section/player out to a per-region
        // player-tracker; today the probe bump is the observation.
    }

    /** Symmetric of {@link #observePlayerAdded(DistanceManager, SectionPos, ServerPlayer)}. */
    public static void observePlayerRemoved(
            @Nullable DistanceManager dm, @Nullable SectionPos section, @Nullable ServerPlayer player) {
        java.util.Optional<MultiForgeDistanceManager> resolved = of(dm);
        ProbeRegistry.bump("mfdistmgr.observe.playerRemoved");
        if (resolved.isEmpty()) {
            ProbeRegistry.bump("mfdistmgr.observe.playerRemoved.unbound");
        }
    }

    /**
     * Observation hook for
     * {@link DistanceManager#updateChunkForced(ChunkPos, boolean)}. Called
     * from the patched base after Vanilla has added/removed the
     * {@code FORCED} ticket. Bumps {@code mfdistmgr.observe.chunkForced}
     * (plus {@code .add} / {@code .remove} branches for finer-grained
     * accounting, plus {@code .unbound} when unregistered).
     */
    public static void observeChunkForced(@Nullable DistanceManager dm, @Nullable ChunkPos pos, boolean add) {
        java.util.Optional<MultiForgeDistanceManager> resolved = of(dm);
        ProbeRegistry.bump("mfdistmgr.observe.chunkForced");
        ProbeRegistry.bump(add ? "mfdistmgr.observe.chunkForced.add" : "mfdistmgr.observe.chunkForced.remove");
        if (resolved.isEmpty()) {
            ProbeRegistry.bump("mfdistmgr.observe.chunkForced.unbound");
        }
    }

    /**
     * Observation hook for the entry of
     * {@link DistanceManager#runAllUpdates(ChunkMap)}. Paired with
     * {@link #observeRunAllUpdatesEnd(DistanceManager)} — the patched base
     * only fires End on return paths reached after Start ran.
     */
    public static void observeRunAllUpdatesStart(@Nullable DistanceManager dm) {
        java.util.Optional<MultiForgeDistanceManager> resolved = of(dm);
        ProbeRegistry.bump("mfdistmgr.observe.runAllUpdatesStart");
        if (resolved.isEmpty()) {
            ProbeRegistry.bump("mfdistmgr.observe.runAllUpdatesStart.unbound");
        }
    }

    /** Companion to {@link #observeRunAllUpdatesStart(DistanceManager)}. */
    public static void observeRunAllUpdatesEnd(@Nullable DistanceManager dm) {
        java.util.Optional<MultiForgeDistanceManager> resolved = of(dm);
        ProbeRegistry.bump("mfdistmgr.observe.runAllUpdatesEnd");
        if (resolved.isEmpty()) {
            ProbeRegistry.bump("mfdistmgr.observe.runAllUpdatesEnd.unbound");
        }
    }
}
