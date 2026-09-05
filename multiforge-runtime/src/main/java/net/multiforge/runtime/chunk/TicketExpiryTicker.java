/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.chunk;

import java.util.ArrayList;
import java.util.List;
import net.multiforge.runtime.region.RegionId;

/**
 * Sweeps expired {@link Ticket tickets} from every {@link PerRegionTicketMap}
 * a given {@link ChunkHolderManager} owns. Vanilla runs an equivalent pass
 * inside {@code DistanceManager.purgeStaleTickets}; MultiForge runs the
 * sweep as a scheduled task on the global region.
 *
 * <p>Cadence: Vanilla purges every tick. MultiForge amortises: the ticker
 * accepts a {@code strideTicks} so the sweep can spread across N ticks
 * (default N=1 = every tick, matching Vanilla). Even at N=1 the cost is
 * bounded by the number of expiring tickets across all regions, which is
 * typically very small.
 *
 * <p>The sweep is safe to run from a worker thread that is neither the
 * owning region worker nor the global tick thread: expired-ticket removal
 * routes through {@link ChunkHolderManager#removeTicket} (the same entry
 * point regular ticket removal uses). Concurrent regular removals are
 * idempotent (see {@code PerChunkTickets.remove}: returns false when a
 * ticket is already gone).
 */
public final class TicketExpiryTicker {

    private final ChunkHolderManager manager;

    public TicketExpiryTicker(ChunkHolderManager manager) {
        this.manager = manager;
    }

    /**
     * Sweep every region owned by {@link #manager} for tickets whose
     * {@code createdAtTick + type.timeoutTicks() &lt;= now}. Removes them
     * via {@link ChunkHolderManager#removeTicket} so demotion + queued
     * full-load-update run through the normal channel.
     *
     * @param now the current game tick used to evaluate {@link Ticket#isExpiredAt}.
     * @return the number of tickets removed.
     */
    public int runOnce(long now) {
        int removed = 0;
        for (NewChunkHolder holder : manager.holders()) {
            RegionId owner = holder.owningRegion();
            if (owner == null) continue;
            PerRegionTicketMap map = manager.ticketsFor(owner);
            PerChunkTickets tickets = map.ticketsAt(holder.position());
            if (tickets == null || tickets.isEmpty()) continue;
            List<Ticket> expired = collectExpired(tickets, now);
            for (Ticket t : expired) {
                if (manager.removeTicket(owner, holder.position(), t)) removed++;
            }
        }
        return removed;
    }

    private static List<Ticket> collectExpired(PerChunkTickets tickets, long now) {
        List<Ticket> out = new ArrayList<>(0);
        for (Ticket t : tickets.snapshot()) {
            if (t.isExpiredAt(now)) out.add(t);
        }
        return out;
    }
}
