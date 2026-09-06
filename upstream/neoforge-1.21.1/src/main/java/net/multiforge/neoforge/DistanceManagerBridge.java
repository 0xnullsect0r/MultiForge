/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.multiforge.neoforge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.ChunkLoadLevel;
import net.multiforge.runtime.chunk.TicketType;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;

/**
 * M9 shadow bridge from Vanilla {@code DistanceManager} into MultiForge's
 * pre-built {@link ChunkHolderManager}. Called from the two-line patches
 * added into {@code DistanceManager.addTicket(long, Ticket)} and
 * {@code removeTicket(long, Ticket)} — the internal choke points that
 * every public addRegionTicket / removeRegionTicket / addPlayer /
 * removePlayer / updateChunkForced call eventually reaches.
 *
 * <p>Phase 1 fix 1.8 + 1.9 in {@code plans/bubbly-jumping-comet.md}.
 * Before this bridge existed, MultiForge's {@code ChunkHolderManager}
 * saw only tickets that MultiForge itself minted via {@code touchChunk}
 * — every Vanilla-side ticket (players, /forceload, ender pearls,
 * portals) was invisible to the shadow, so /multiforge chunks
 * under-reported and Phase 5's tick-phase wiring would have no ticket
 * data to drive from.
 *
 * <p>Scope: still a shadow, not a drive. Vanilla remains authoritative
 * for ticket state; the mirror populates MultiForge's per-region ticket
 * map so future Phase 4/5 code can flip the arrow and make MultiForge
 * authoritative. Zero behavior change to Vanilla ticket-level
 * transitions.
 */
public final class DistanceManagerBridge {
    private DistanceManagerBridge() {}

    /**
     * Called from the {@code addTicket} patch after Vanilla has committed
     * the ticket to its own sorted-array set. Mirrors the write into
     * {@link ChunkHolderManager#addTicket}.
     */
    public static void onAddTicket(ServerLevel level, long chunkKey, Ticket<?> vanillaTicket) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return; // runtime not installed yet
        WorldRef world = RegionizedTickCoordinator.asWorldRef(level);
        ThreadedRegionizer regionizer = host.regionizerFor(world);

        int chunkX = (int) chunkKey;
        int chunkZ = (int) (chunkKey >> 32);
        Region region = regionizer.regionAtChunk(chunkX, chunkZ);
        if (region == null) return; // region hasn't materialized yet — ChunkEvent.Load will re-seed

        ChunkHolderManager manager = host.chunkManagerFor(world);
        net.multiforge.api.world.ChunkPos mfPos = new net.multiforge.api.world.ChunkPos(chunkX, chunkZ);
        // Ensure the holder exists so ticket writes have a target.
        if (manager.holderAt(mfPos) == null) {
            manager.createHolder(mfPos, region.id());
        }
        manager.addTicket(region.id(), mfPos, toMultiForgeTicket(vanillaTicket));
    }

    /**
     * Called from the {@code removeTicket} patch after Vanilla has
     * removed the ticket from its own sorted-array set. Mirrors the
     * removal via {@link ChunkHolderManager#removeTicket}.
     */
    public static void onRemoveTicket(ServerLevel level, long chunkKey, Ticket<?> vanillaTicket) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return;
        WorldRef world = RegionizedTickCoordinator.asWorldRef(level);
        ThreadedRegionizer regionizer = host.regionizerForOrNull(world);
        if (regionizer == null) return;
        int chunkX = (int) chunkKey;
        int chunkZ = (int) (chunkKey >> 32);
        Region region = regionizer.regionAtChunk(chunkX, chunkZ);
        if (region == null) return;
        ChunkHolderManager manager = host.chunkManagerForOrNull(world);
        if (manager == null) return;
        manager.removeTicket(
                region.id(), new net.multiforge.api.world.ChunkPos(chunkX, chunkZ), toMultiForgeTicket(vanillaTicket));
    }

    /**
     * Map a Vanilla {@link Ticket} to MultiForge's {@link net.multiforge.runtime.chunk.Ticket}.
     * MultiForge's ticket record is (type, distance, key, createdAtTick);
     * Vanilla's is (type, ticketLevel, key, createdTick). Named type
     * strings and integer level map one-to-one. The key becomes an
     * opaque String derived from the Vanilla key's identity so
     * per-key deduplication in {@link
     * net.multiforge.runtime.chunk.PerChunkTickets} stays honest.
     *
     * <p>Phase 4 fix 4.8: the Vanilla {@code createdTick} (set by
     * {@code DistanceManager.addTicket} immediately before this bridge
     * fires) and the Vanilla {@code TicketType.timeout} are threaded
     * through so MultiForge's {@link
     * net.multiforge.runtime.chunk.TicketExpiryTicker} sweeps expiring
     * tickets on the same clock as Vanilla's own
     * {@code DistanceManager.purgeStaleTickets}. Vanilla remains
     * authoritative — this only aligns the shadow's expiry accounting
     * with the source of truth so a future flip of the arrow doesn't
     * silently re-parent already-expiring tickets to tick 0.
     */
    private static net.multiforge.runtime.chunk.Ticket toMultiForgeTicket(Ticket<?> v) {
        String typeName = v.getType().toString();
        int level = v.getTicketLevel();
        // Vanilla TicketType.timeout is 0 for permanent tickets; carry it
        // through so MultiForge's TicketType.timeoutTicks matches Vanilla's
        // per-type expiry (POST_TELEPORT=5, PORTAL=300, UNKNOWN=1, ender
        // pearls=40, everything else permanent). Cast is safe: Vanilla
        // timeouts are game-tick counts well under Integer.MAX_VALUE.
        int timeout = (int) v.getType().timeout();
        TicketType mfType = TicketType.of(typeName, level, timeout);
        String keyStr = String.valueOf(v);
        // Vanilla's setCreatedTick runs inside DistanceManager.addTicket
        // just before it dispatches through ChunkMap.DistanceManagerImpl,
        // so getCreatedTick() is always populated by the time this bridge
        // sees the ticket. See Ticket.java.patch under 04-chunk-system.
        long createdAtTick = v.getCreatedTick();
        return net.multiforge.runtime.chunk.Ticket.at(mfType, level, keyStr, createdAtTick);
    }

    /**
     * Convenience for observability: report the effective load level of
     * a chunk as MultiForge sees it via ticket state. Distinct from
     * {@link ChunkHolderManagerBridge#currentLevel} which reads the
     * shadowed level directly. Returns null when the manager isn't yet
     * established for {@code level}'s world.
     */
    public static ChunkLoadLevel currentEffectiveLevel(ServerLevel level, int chunkX, int chunkZ) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return null;
        WorldRef world = RegionizedTickCoordinator.asWorldRef(level);
        ChunkHolderManager manager = host.chunkManagerForOrNull(world);
        if (manager == null) return null;
        ThreadedRegionizer regionizer = host.regionizerForOrNull(world);
        if (regionizer == null) return null;
        Region region = regionizer.regionAtChunk(chunkX, chunkZ);
        if (region == null) return null;
        return manager.ticketsFor(region.id())
                .effectiveLevel(new net.multiforge.api.world.ChunkPos(chunkX, chunkZ));
    }
}
