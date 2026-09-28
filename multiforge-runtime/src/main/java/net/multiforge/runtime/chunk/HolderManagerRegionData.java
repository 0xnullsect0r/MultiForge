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

    private static final TickingBlockEntityRef[] EMPTY = new TickingBlockEntityRef[0];

    // An array list of the region's tickers, in insertion order. A plain array
    // so tickTickers can compact it in place.
    private TickingBlockEntityRef[] tickers = EMPTY;
    private int size;

    /**
     * Register {@code ticker} as owned by this region. A ticker lives in
     * exactly one region's list at a time; {@link #split} and {@link #merge}
     * preserve that.
     */
    public void addBlockEntityTicker(TickingBlockEntityRef ticker) {
        if (size == tickers.length) tickers = java.util.Arrays.copyOf(tickers, Math.max(16, size * 2));
        tickers[size++] = ticker;
    }

    /** Deregister {@code ticker}; {@code false} if it was not present. */
    public boolean removeBlockEntityTicker(TickingBlockEntityRef ticker) {
        for (int i = 0; i < size; i++) {
            if (java.util.Objects.equals(tickers[i], ticker)) {
                System.arraycopy(tickers, i + 1, tickers, i, size - i - 1);
                tickers[--size] = null;
                return true;
            }
        }
        return false;
    }

    public int blockEntityTickerCount() {
        return size;
    }

    /**
     * A copy of the ticker list, safe to iterate while the list changes (a
     * block entity removing itself during its own {@code tick()}).
     */
    public List<TickingBlockEntityRef> snapshotBlockEntityTickers() {
        return List.of(java.util.Arrays.copyOf(tickers, size));
    }

    /**
     * One tick of this region's block entities, as Vanilla's {@code
     * Level.tickBlockEntities} iterates its list: in list order, a ticker that
     * reports {@link TickingBlockEntityRef#isRemoved()} is dropped from the list,
     * and every other one is handed to {@code tick}. The list is compacted in
     * place as the pass goes — no copy, no per-removal shift.
     *
     * <p>Tickers added during the pass (a block entity placing another) are
     * appended past the pass's end: they are not visited this tick and keep
     * their order after it, exactly as with a snapshot of the list taken before
     * the pass. If {@code tick} throws, the list is left consistent: the ticker
     * that threw and every one not yet visited stay, in order.
     */
    public void tickTickers(java.util.function.Consumer<TickingBlockEntityRef> tick) {
        final int end = size;
        int write = 0;
        int next = 0; // first ticker not yet visited
        try {
            while (next < end) {
                // Re-read the array each step: tick may append and grow it.
                TickingBlockEntityRef ticker = tickers[next];
                if (ticker.isRemoved()) {
                    next++;
                    continue;
                }
                tickers[write++] = ticker;
                next++;
                tick.accept(ticker);
            }
        } finally {
            // Close the gap left by the dropped tickers: move the unvisited ones
            // and those appended during the pass down behind the kept ones.
            if (write != next) {
                int tail = size - next;
                System.arraycopy(tickers, next, tickers, write, tail);
                java.util.Arrays.fill(tickers, write + tail, size, null);
                size = write + tail;
            }
        }
    }

    /** Fold {@code other} into this. Used when regions merge. */
    public void merge(HolderManagerRegionData other) {
        // Disjoint by construction (one region per ticker), so no de-dup.
        for (int i = 0; i < other.size; i++) addBlockEntityTicker(other.tickers[i]);
        other.tickers = EMPTY;
        other.size = 0;
    }

    /**
     * Move every ticker whose block lies in a chunk matching {@code
     * chunkShouldLeave} into a new instance. Used when a region splits: a
     * ticker always ends up in the region that owns its block's chunk.
     */
    public HolderManagerRegionData split(Predicate<ChunkPos> chunkShouldLeave) {
        HolderManagerRegionData out = new HolderManagerRegionData();
        int write = 0;
        for (int i = 0; i < size; i++) {
            TickingBlockEntityRef ticker = tickers[i];
            if (chunkShouldLeave.test(ticker.pos().toChunkPos())) {
                out.addBlockEntityTicker(ticker);
            } else {
                tickers[write++] = ticker;
            }
        }
        java.util.Arrays.fill(tickers, write, size, null);
        size = write;
        return out;
    }
}
