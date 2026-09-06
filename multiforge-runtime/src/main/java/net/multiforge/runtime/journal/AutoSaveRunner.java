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
package net.multiforge.runtime.journal;

import java.util.Objects;
import java.util.function.BiFunction;
import net.multiforge.runtime.chunk.HolderManagerRegionData;
import net.multiforge.runtime.chunk.NewChunkHolder;
import net.multiforge.runtime.region.Region;

/**
 * Drains {@link HolderManagerRegionData#autoSaveSnapshot()} on a
 * rolling budget: at most {@code maxChunksPerTick} chunks per tick,
 * or until the wall-clock budget is exceeded. Each save is journaled
 * before it hits disk.
 *
 * <p>Called from the region worker's tick body (M6 patch).
 */
public final class AutoSaveRunner {

    private final HolderManagerRegionData data;
    private final RegionJournal journal;
    private final BiFunction<Region, NewChunkHolder, byte[]> serializer;
    private final int maxChunksPerTick;
    private final long maxNanosPerTick;

    public AutoSaveRunner(
            HolderManagerRegionData data,
            RegionJournal journal,
            BiFunction<Region, NewChunkHolder, byte[]> serializer,
            int maxChunksPerTick,
            long maxNanosPerTick) {
        this.data = Objects.requireNonNull(data, "data");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        if (maxChunksPerTick <= 0) throw new IllegalArgumentException("maxChunksPerTick must be > 0");
        if (maxNanosPerTick <= 0) throw new IllegalArgumentException("maxNanosPerTick must be > 0");
        this.maxChunksPerTick = maxChunksPerTick;
        this.maxNanosPerTick = maxNanosPerTick;
    }

    /** @return number of chunks saved this call. */
    public int runOnce(Region region) throws java.io.IOException {
        long start = System.nanoTime();
        // Saturating add so callers passing Long.MAX_VALUE for "no deadline"
        // don't trip an overflow that would break the loop immediately.
        long deadline = maxNanosPerTick > Long.MAX_VALUE - start ? Long.MAX_VALUE : start + maxNanosPerTick;
        int saved = 0;
        for (NewChunkHolder holder : data.autoSaveSnapshot()) {
            if (saved >= maxChunksPerTick) break;
            if (System.nanoTime() >= deadline) break;
            if (!holder.isDirty()) {
                data.removeAutoSave(holder);
                continue;
            }
            byte[] payload = serializer.apply(region, holder);
            journal.append(JournalEntryKind.CHUNK_SAVE, payload);
            holder.clearDirty();
            data.removeAutoSave(holder);
            saved++;
        }
        return saved;
    }
}
