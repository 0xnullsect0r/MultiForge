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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.HolderManagerRegionData;
import net.multiforge.runtime.chunk.NewChunkHolder;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionId;
import net.multiforge.runtime.region.ThreadedRegionizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutoSaveRunnerTest {

    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");
    private static final RegionId REGION_ID = new RegionId(3);

    @Test
    void drainsDirtyChunksAndClearsFlag(@TempDir Path dir) throws IOException {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region region = regionizer.addChunk(new ChunkPos(0, 0));

        ChunkHolderManager manager = new ChunkHolderManager(WORLD);
        HolderManagerRegionData data = manager.regionData(region.id());

        NewChunkHolder h1 = manager.createHolder(new ChunkPos(1, 0), region.id());
        NewChunkHolder h2 = manager.createHolder(new ChunkPos(2, 0), region.id());
        h1.markDirty();
        h2.markDirty();
        data.enqueueAutoSave(h1);
        data.enqueueAutoSave(h2);

        try (RegionJournal journal = RegionJournal.open(REGION_ID, dir)) {
            AutoSaveRunner runner = new AutoSaveRunner(
                    data,
                    journal,
                    (r, holder) -> ("chunk:" + holder.position().x()).getBytes(StandardCharsets.UTF_8),
                    16,
                    java.util.concurrent.TimeUnit.SECONDS.toNanos(60));

            int saved = runner.runOnce(region);
            assertThat(saved).isEqualTo(2);
            assertThat(h1.isDirty()).isFalse();
            assertThat(h2.isDirty()).isFalse();
            assertThat(data.autoSaveCount()).isZero();
        }

        try (RegionJournal reopen = RegionJournal.open(REGION_ID, dir)) {
            assertThat(reopen.readAll()).hasSize(2).allSatisfy(entry -> {
                assertThat(entry.kind()).isEqualTo(JournalEntryKind.CHUNK_SAVE);
                assertThat(new String(entry.payload(), StandardCharsets.UTF_8)).startsWith("chunk:");
            });
        }
    }

    @Test
    void respectsPerTickBudget(@TempDir Path dir) throws IOException {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region region = regionizer.addChunk(new ChunkPos(0, 0));
        ChunkHolderManager manager = new ChunkHolderManager(WORLD);
        HolderManagerRegionData data = manager.regionData(region.id());

        for (int i = 0; i < 10; i++) {
            NewChunkHolder h = manager.createHolder(new ChunkPos(i, 0), region.id());
            h.markDirty();
            data.enqueueAutoSave(h);
        }

        try (RegionJournal journal = RegionJournal.open(REGION_ID, dir)) {
            AutoSaveRunner runner = new AutoSaveRunner(
                    data,
                    journal,
                    (r, holder) -> new byte[] {(byte) holder.position().x()},
                    3, // maxChunksPerTick
                    java.util.concurrent.TimeUnit.SECONDS.toNanos(60));

            assertThat(runner.runOnce(region)).isEqualTo(3);
            assertThat(data.autoSaveCount()).isEqualTo(7);
        }
    }

    @Test
    void skipsCleanHoldersButStillRemovesThem(@TempDir Path dir) throws IOException {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region region = regionizer.addChunk(new ChunkPos(0, 0));
        ChunkHolderManager manager = new ChunkHolderManager(WORLD);
        HolderManagerRegionData data = manager.regionData(region.id());

        NewChunkHolder clean = manager.createHolder(new ChunkPos(5, 5), region.id());
        data.enqueueAutoSave(clean); // enqueued but never dirtied

        try (RegionJournal journal = RegionJournal.open(REGION_ID, dir)) {
            AutoSaveRunner runner = new AutoSaveRunner(
                    data, journal, (r, h) -> new byte[0], 16, java.util.concurrent.TimeUnit.SECONDS.toNanos(60));
            assertThat(runner.runOnce(region)).isZero();
            assertThat(data.autoSaveCount()).isZero();
            assertThat(journal.readAll()).isEmpty();
        }
    }
}
