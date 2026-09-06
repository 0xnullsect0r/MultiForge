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
package net.multiforge.runtime.chunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.multiforge.api.world.ChunkPos;

/**
 * Per-region state that {@link ChunkHolderManager} carries. Merged /
 * split alongside the {@link net.multiforge.runtime.region.Region} it
 * belongs to (see {@link net.multiforge.runtime.region.ThreadedRegionizer}).
 *
 * <p>Three queues/slices live here — the first two matching Folia's
 * {@code ChunkHolderManager.HolderManagerRegionData}, the third added
 * by B3.1 (docs/design/m13-b3-region-tick.md §4.2):
 *
 * <ul>
 *   <li>{@link #pendingFullLoadUpdate} — chunks that just crossed a
 *       load-level threshold and need their full-chunk future resolved
 *       (i.e. their block entities / entity iterators updated) on the
 *       next tick.</li>
 *   <li>{@link #autoSaveQueue} — dirty chunks to persist to disk on
 *       the next autosave budget slice.</li>
 *   <li>{@link #blockEntityTickers} — this region's slice of what
 *       Vanilla stores level-wide as {@code Level.blockEntityTickers}.
 *       Unlike the first two fields, tickers are not indexed by {@link
 *       NewChunkHolder} — they are indexed by the block position they
 *       tick ({@link TickingBlockEntityRef#pos()}), so {@link #split}
 *       takes a second, {@code ChunkPos}-keyed predicate for this
 *       field alone. See {@link #split} for why.</li>
 * </ul>
 *
 * <p>Access is single-writer (the owning region worker); no
 * synchronisation is needed.
 */
public final class HolderManagerRegionData {

    private final Deque<NewChunkHolder> pendingFullLoadUpdate = new ArrayDeque<>();
    private final Set<NewChunkHolder> autoSaveQueue = new LinkedHashSet<>();
    private final List<TickingBlockEntityRef> blockEntityTickers = new ArrayList<>();

    public void enqueueFullLoadUpdate(NewChunkHolder holder) {
        if (!holder.pendingFullLoadUpdate()) {
            holder.setPendingFullLoadUpdate(true);
            pendingFullLoadUpdate.add(holder);
        }
    }

    public NewChunkHolder pollFullLoadUpdate() {
        NewChunkHolder h = pendingFullLoadUpdate.poll();
        if (h != null) h.setPendingFullLoadUpdate(false);
        return h;
    }

    public int pendingFullLoadCount() {
        return pendingFullLoadUpdate.size();
    }

    public void enqueueAutoSave(NewChunkHolder holder) {
        autoSaveQueue.add(holder);
    }

    public boolean removeAutoSave(NewChunkHolder holder) {
        return autoSaveQueue.remove(holder);
    }

    public int autoSaveCount() {
        return autoSaveQueue.size();
    }

    public Set<NewChunkHolder> autoSaveSnapshot() {
        return Set.copyOf(autoSaveQueue);
    }

    // === B3.1 — block-entity tickers (docs/design/m13-b3-region-tick.md §4.2) =========

    /**
     * Register {@code ticker} as owned by this region. Owning region
     * worker only. Idempotent-ish in the sense that the invariant this
     * whole slice depends on — a ticker lives in exactly one region's
     * list at a time — is the caller's responsibility to maintain (the
     * B3.4 phase-body wiring adds a ticker to exactly one region when
     * its block entity is created, and {@link #split}/{@link #merge}
     * preserve the invariant across topology changes).
     */
    public void addBlockEntityTicker(TickingBlockEntityRef ticker) {
        blockEntityTickers.add(ticker);
    }

    /**
     * Deregister {@code ticker} (block entity removed, or migrating to
     * another region outside a split/merge). Owning region worker
     * only. Returns {@code false} if the ticker was not present.
     */
    public boolean removeBlockEntityTicker(TickingBlockEntityRef ticker) {
        return blockEntityTickers.remove(ticker);
    }

    public int blockEntityTickerCount() {
        return blockEntityTickers.size();
    }

    /**
     * Unmodifiable copy of the current ticker list, safe to iterate
     * even if the owning region worker concurrently adds/removes
     * tickers mid-iteration (e.g. a block entity removing itself
     * during its own {@code tick()} call) — the snapshot is a distinct
     * list, so such a mutation can never raise a {@code
     * ConcurrentModificationException} against it.
     */
    public List<TickingBlockEntityRef> snapshotBlockEntityTickers() {
        return List.copyOf(blockEntityTickers);
    }

    /** Fold {@code other} into this. Used when regions merge. */
    public void merge(HolderManagerRegionData other) {
        while (!other.pendingFullLoadUpdate.isEmpty()) {
            NewChunkHolder h = other.pendingFullLoadUpdate.poll();
            h.setPendingFullLoadUpdate(false); // re-enqueue via this
            enqueueFullLoadUpdate(h);
        }
        for (NewChunkHolder h : other.autoSaveQueue) autoSaveQueue.add(h);
        other.autoSaveQueue.clear();
        // Safe to concatenate without a de-dup pass: a ticker lives in
        // exactly one region's list at a time (see addBlockEntityTicker),
        // so a merge of two disjoint regions' lists cannot introduce a
        // duplicate reference.
        blockEntityTickers.addAll(other.blockEntityTickers);
        other.blockEntityTickers.clear();
    }

    /**
     * Peel off every holder whose position matches {@code shouldLeave}
     * — and every block-entity ticker whose {@link
     * TickingBlockEntityRef#pos()} resolves to a chunk matching {@code
     * chunkShouldLeave} — into a new data instance. Used when a region
     * splits.
     *
     * <p>Two predicates, not one: {@link #pendingFullLoadUpdate} and
     * {@link #autoSaveQueue} are indexed by {@link NewChunkHolder}, so
     * the existing {@code shouldLeave} predicate (over a holder) is
     * enough for them. {@link #blockEntityTickers} has no {@code
     * NewChunkHolder} of its own — a ticker's only stable coordinate
     * is the {@code BlockPos} it ticks at — so its membership test
     * must go through {@link TickingBlockEntityRef#pos()} →
     * {@link net.multiforge.api.world.BlockPos#toChunkPos()} instead.
     * Callers (see {@link ChunkHolderManager#onRegionSplit(RegionId,
     * RegionId, Predicate)}) pass the same underlying {@code ChunkPos}
     * membership test to both parameters — this is the load-bearing
     * correctness property docs/design/m13-b3-region-tick.md §4.2
     * calls out: a ticker's block position resolves to the region
     * holding that ticker, both before and after a split.
     */
    public HolderManagerRegionData split(Predicate<NewChunkHolder> shouldLeave, Predicate<ChunkPos> chunkShouldLeave) {
        HolderManagerRegionData out = new HolderManagerRegionData();
        var it = pendingFullLoadUpdate.iterator();
        while (it.hasNext()) {
            NewChunkHolder h = it.next();
            if (shouldLeave.test(h)) {
                it.remove();
                h.setPendingFullLoadUpdate(false);
                out.enqueueFullLoadUpdate(h);
            }
        }
        var it2 = autoSaveQueue.iterator();
        while (it2.hasNext()) {
            NewChunkHolder h = it2.next();
            if (shouldLeave.test(h)) {
                it2.remove();
                out.autoSaveQueue.add(h);
            }
        }
        var it3 = blockEntityTickers.iterator();
        while (it3.hasNext()) {
            TickingBlockEntityRef ticker = it3.next();
            if (chunkShouldLeave.test(ticker.pos().toChunkPos())) {
                it3.remove();
                out.blockEntityTickers.add(ticker);
            }
        }
        return out;
    }
}
