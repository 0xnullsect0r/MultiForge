/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.neoforge;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.Ticket;
import net.multiforge.runtime.chunk.TicketType;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * NeoForge event listeners that keep the regionizer and per-world
 * {@link ChunkHolderManager} in sync with Vanilla's chunk load/unload
 * state. Every time a chunk goes FULL (Vanilla fires
 * {@link ChunkEvent.Load}), a region is created or grown for it and a
 * MultiForge {@link TicketType#START} ticket is written into the
 * owning region's {@link ChunkHolderManager} — promoting the holder to
 * TICKING for the load window. On {@link ChunkEvent.Unload}, the
 * ticket is symmetrically released and the chunk is removed from its
 * region; region-listener auto-wiring in
 * {@link net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost#regionizerFor}
 * takes care of downstream cascades.
 *
 * <p>Phase 5.6 migration: previously this class only <em>shadowed</em>
 * the load window into the holder manager via
 * {@link ChunkHolderManagerBridge#onChunkLoaded}/{@code onChunkUnloaded}
 * (an observation seam that lived alongside Vanilla's own ticket set).
 * The bridge write is now a <em>real</em> ticket write through
 * {@link ChunkHolderManager#addTicket}, so the MultiForge holder is
 * driven by the same per-region ticket map that
 * {@link net.multiforge.neoforge.chunk.MultiForgeDistanceManager}
 * feeds — no more shadow, no more silent "ticket-level transitions
 * fired before ChunkEvent.Load" race (the ticket is the seed itself).
 *
 * <p>Registration is idempotent per JVM: {@link #installOnEventBus}
 * only registers once even if called from repeated
 * {@code handleServerAboutToStart} invocations (dedi GameTestServer
 * reuses one JVM across servers).
 */
public final class RegionizedChunkLifecycle {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    /**
     * Stable per-chunk ticket key for the START ticket that
     * {@link #onChunkLoaded} writes and {@link #onChunkUnloaded}
     * releases. Every {@code (world, chunk)} pair holds at most one
     * ticket keyed on this string, so the add/remove pair balances
     * without needing to construct a Vanilla-shaped identifier.
     */
    private static final String CHUNK_LOADED_TICKET_KEY = "chunk-loaded";

    private RegionizedChunkLifecycle() {}

    /**
     * Register {@link ChunkEvent.Load} and {@link ChunkEvent.Unload}
     * handlers on {@link NeoForge#EVENT_BUS}. Idempotent — second and
     * later calls do nothing.
     */
    public static void installOnEventBus() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        NeoForge.EVENT_BUS.addListener(RegionizedChunkLifecycle::onChunkLoaded);
        NeoForge.EVENT_BUS.addListener(RegionizedChunkLifecycle::onChunkUnloaded);
    }

    private static void onChunkLoaded(final ChunkEvent.Load event) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return; // runtime not installed yet (bootstrap ordering)
        LevelAccessor level = event.getLevel();
        if (level.isClientSide()) return; // regionizer is server-side only
        WorldRef world = worldRefFor(level);
        if (world == null) return;
        ChunkPos pos = event.getChunk().getPos();
        // registerChunk both materialises the regionizer for this world and
        // returns the freshly-owned Region so we can write the ticket
        // against the same identity without a second lookup.
        Region region = host.registerChunk(world, pos.x, pos.z);
        // Phase 5.6: replace the M9 sub-step 1 shadow-seed with a real
        // ticket write. TicketType.START defaults to TICKING (32) which
        // matches Vanilla's own start-ticket parity for a freshly-FULL
        // chunk (Vanilla ChunkEvent.Load fires when a chunk transitions
        // to FULL/BORDER, and the ticket promotion drives the holder
        // through the pending-full-load-update pump). The addTicket
        // choke below is thread-safe and cross-region-callable — see
        // ChunkHolderManager.addTicket contract (§ M9 contracts 2.2).
        ChunkHolderManager manager = host.chunkManagerFor(world);
        net.multiforge.api.world.ChunkPos mfPos = new net.multiforge.api.world.ChunkPos(pos.x, pos.z);
        if (manager.holderAt(mfPos) == null) {
            manager.createHolder(mfPos, region.id());
        }
        // createdAtTick=-1 keeps the add/remove pair equal — {@link Ticket}
        // records include every component in equals, so a mismatched
        // createdAtTick on removeTicket would silently orphan the ticket.
        // START's timeoutTicks is 0 (never expires) so the sentinel tick
        // stamp is fine — TicketExpiryTicker skips permanent tickets.
        Ticket ticket = Ticket.of(TicketType.START, CHUNK_LOADED_TICKET_KEY);
        manager.addTicket(region.id(), mfPos, ticket);
        // Drain the orphan queue for this chunk's section only: tasks queued
        // BEFORE the region existed (typically from mods scheduling at
        // ServerAboutToStart) can now be delivered. /67 round-4 changed the
        // per-chunk O(orphans) full-queue scan to an O(bucket) per-section
        // reroute — for N chunks loaded and K persistent orphans that live
        // in unrelated sections, boot cost drops from O(N × K) to O(N).
        // Real ticket writes and reroute are complementary: the ticket
        // promotes the holder, the reroute delivers already-queued work.
        host.taskQueue().rerouteAtChunk(world, pos.x, pos.z);
    }

    private static void onChunkUnloaded(final ChunkEvent.Unload event) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return;
        LevelAccessor level = event.getLevel();
        if (level.isClientSide()) return;
        WorldRef world = worldRefFor(level);
        if (world == null) return;
        ChunkPos pos = event.getChunk().getPos();
        // Phase 5.6: symmetric ticket release. Removing the START ticket
        // BEFORE unregistering the chunk keeps the holder's owning
        // RegionId live for the duration of the removeTicket call — the
        // subsequent unregisterChunk cascades through onRegionDied to
        // drop per-region maps only after the ticket is gone. Uses the
        // non-creating chunkManagerForOrNull so a foreign-world Unload
        // (theoretically possible for a world MultiForge never saw
        // Load-side) doesn't allocate a fresh manager on the way out.
        ChunkHolderManager manager = host.chunkManagerForOrNull(world);
        if (manager != null) {
            net.multiforge.runtime.region.ThreadedRegionizer regionizer = host.regionizerForOrNull(world);
            Region region = regionizer == null ? null : regionizer.regionAtChunk(pos.x, pos.z);
            if (region != null) {
                // Same shape as the addTicket on the Load side — see the
                // comment there about createdAtTick=-1 keeping equals aligned.
                Ticket ticket = Ticket.of(TicketType.START, CHUNK_LOADED_TICKET_KEY);
                manager.removeTicket(
                        region.id(), new net.multiforge.api.world.ChunkPos(pos.x, pos.z), ticket);
            }
        }
        host.unregisterChunk(world, pos.x, pos.z);
    }

    /**
     * Extract a {@link WorldRef} from a {@link LevelAccessor}. Only
     * ServerLevels have a well-defined dimension id we can address via
     * WorldRef; returns null for anything else so the caller can no-op.
     */
    private static WorldRef worldRefFor(LevelAccessor level) {
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            return RegionizedTickCoordinator.asWorldRef(serverLevel);
        }
        return null;
    }

    /** Test-only: reset the installed flag so a fresh test JVM can re-install. */
    static void resetForTesting() {
        INSTALLED.set(false);
    }
}
