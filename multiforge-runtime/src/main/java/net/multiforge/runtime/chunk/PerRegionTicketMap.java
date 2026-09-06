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
package net.multiforge.runtime.chunk;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import net.multiforge.api.world.ChunkPos;

/**
 * Per-region storage of chunk → tickets. Kept as a plain
 * (unsynchronised) map — the owning region worker is the only writer,
 * so no lock is needed.
 *
 * <p>{@link #merge(PerRegionTicketMap)} folds another map's tickets
 * in when two regions merge; {@link #split(java.util.function.Predicate)}
 * peels off tickets whose chunks the caller wants to hand to a new
 * region (called during a region split).
 */
public final class PerRegionTicketMap {

    private final Map<ChunkPos, PerChunkTickets> byChunk = new HashMap<>();

    public boolean addTicket(ChunkPos pos, Ticket ticket) {
        return byChunk.computeIfAbsent(pos, k -> new PerChunkTickets()).add(ticket);
    }

    public boolean removeTicket(ChunkPos pos, Ticket ticket) {
        PerChunkTickets t = byChunk.get(pos);
        if (t == null) return false;
        boolean out = t.remove(ticket);
        if (out && t.isEmpty()) byChunk.remove(pos);
        return out;
    }

    /** @return the tickets on {@code pos}, or {@code null} if none. */
    public PerChunkTickets ticketsAt(ChunkPos pos) {
        return byChunk.get(pos);
    }

    public ChunkLoadLevel effectiveLevel(ChunkPos pos) {
        PerChunkTickets t = byChunk.get(pos);
        return t == null ? ChunkLoadLevel.INACCESSIBLE : t.effectiveLevel();
    }

    public Set<ChunkPos> loadedChunks() {
        return byChunk.keySet();
    }

    public int chunkCount() {
        return byChunk.size();
    }

    /** Fold {@code other}'s tickets into this map. Caller responsible for {@code other}'s lifetime. */
    public void merge(PerRegionTicketMap other) {
        for (Map.Entry<ChunkPos, PerChunkTickets> e : other.byChunk.entrySet()) {
            PerChunkTickets ours = byChunk.computeIfAbsent(e.getKey(), k -> new PerChunkTickets());
            for (Ticket t : e.getValue().snapshot()) ours.add(t);
        }
    }

    /**
     * Remove and return every chunk whose position satisfies
     * {@code shouldLeave}. Used by region split to hand orphan chunks
     * to their new owner.
     */
    public PerRegionTicketMap split(java.util.function.Predicate<ChunkPos> shouldLeave) {
        PerRegionTicketMap out = new PerRegionTicketMap();
        Iterator<Map.Entry<ChunkPos, PerChunkTickets>> it = byChunk.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (shouldLeave.test(e.getKey())) {
                out.byChunk.put(e.getKey(), e.getValue());
                it.remove();
            }
        }
        return out;
    }
}
