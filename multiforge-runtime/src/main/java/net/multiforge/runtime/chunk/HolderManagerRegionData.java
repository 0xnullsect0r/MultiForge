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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.multiforge.api.world.ChunkPos;

/**
 * Per-region state kept by {@link ChunkHolderManager}: the region's slice of
 * what Vanilla stores level-wide as {@code Level.blockEntityTickers}. Merged
 * and split along with the region it belongs to.
 *
 * <p>Written by the owning region's worker during its tick, or by the server
 * thread between ticks (tickers routed from the level), never both at once:
 * see docs/design/barrier-tick-model.md.
 */
public final class HolderManagerRegionData {

    private final List<TickingBlockEntityRef> blockEntityTickers = new ArrayList<>();

    /**
     * Register {@code ticker} as owned by this region. A ticker lives in
     * exactly one region's list at a time; {@link #split} and {@link #merge}
     * preserve that.
     */
    public void addBlockEntityTicker(TickingBlockEntityRef ticker) {
        blockEntityTickers.add(ticker);
    }

    /** Deregister {@code ticker}; {@code false} if it was not present. */
    public boolean removeBlockEntityTicker(TickingBlockEntityRef ticker) {
        return blockEntityTickers.remove(ticker);
    }

    public int blockEntityTickerCount() {
        return blockEntityTickers.size();
    }

    /**
     * A copy of the ticker list, safe to iterate while the list changes (a
     * block entity removing itself during its own {@code tick()}).
     */
    public List<TickingBlockEntityRef> snapshotBlockEntityTickers() {
        return List.copyOf(blockEntityTickers);
    }

    /** Fold {@code other} into this. Used when regions merge. */
    public void merge(HolderManagerRegionData other) {
        // Disjoint by construction (one region per ticker), so no de-dup.
        blockEntityTickers.addAll(other.blockEntityTickers);
        other.blockEntityTickers.clear();
    }

    /**
     * Move every ticker whose block lies in a chunk matching {@code
     * chunkShouldLeave} into a new instance. Used when a region splits: a
     * ticker always ends up in the region that owns its block's chunk.
     */
    public HolderManagerRegionData split(Predicate<ChunkPos> chunkShouldLeave) {
        HolderManagerRegionData out = new HolderManagerRegionData();
        var it = blockEntityTickers.iterator();
        while (it.hasNext()) {
            TickingBlockEntityRef ticker = it.next();
            if (chunkShouldLeave.test(ticker.pos().toChunkPos())) {
                it.remove();
                out.blockEntityTickers.add(ticker);
            }
        }
        return out;
    }
}
