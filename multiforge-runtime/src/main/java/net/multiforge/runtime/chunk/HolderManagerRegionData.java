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
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Per-region state that {@link ChunkHolderManager} carries. Merged /
 * split alongside the {@link net.multiforge.runtime.region.Region} it
 * belongs to (see {@link net.multiforge.runtime.region.ThreadedRegionizer}).
 *
 * <p>Two queues live here — matching Folia's {@code
 * ChunkHolderManager.HolderManagerRegionData}:
 *
 * <ul>
 *   <li>{@link #pendingFullLoadUpdate} — chunks that just crossed a
 *       load-level threshold and need their full-chunk future resolved
 *       (i.e. their block entities / entity iterators updated) on the
 *       next tick.</li>
 *   <li>{@link #autoSaveQueue} — dirty chunks to persist to disk on
 *       the next autosave budget slice.</li>
 * </ul>
 *
 * <p>Access is single-writer (the owning region worker); no
 * synchronisation is needed.
 */
public final class HolderManagerRegionData {

    private final Deque<NewChunkHolder> pendingFullLoadUpdate = new ArrayDeque<>();
    private final Set<NewChunkHolder> autoSaveQueue = new LinkedHashSet<>();

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

    /** Fold {@code other} into this. Used when regions merge. */
    public void merge(HolderManagerRegionData other) {
        while (!other.pendingFullLoadUpdate.isEmpty()) {
            NewChunkHolder h = other.pendingFullLoadUpdate.poll();
            h.setPendingFullLoadUpdate(false); // re-enqueue via this
            enqueueFullLoadUpdate(h);
        }
        for (NewChunkHolder h : other.autoSaveQueue) autoSaveQueue.add(h);
        other.autoSaveQueue.clear();
    }

    /**
     * Peel off every holder whose position matches {@code shouldLeave}
     * into a new data instance — used when a region splits.
     */
    public HolderManagerRegionData split(Predicate<NewChunkHolder> shouldLeave) {
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
        return out;
    }
}
