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
package net.multiforge.runtime.region;

import static org.assertj.core.api.Assertions.assertThat;

import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the /67 review's session-1 findings that
 * survived the session-2 revert pass. Only findings whose fixes were
 * verified clean by the second /67 review keep their tests here.
 */
class RegionizerReviewFixesTest {

    private static final WorldRef WORLD = WorldRef.of("test:world");

    // /67 finding #1: split-created regions must enter the scheduler via onRegionCreated.
    @Test
    void splitCreatedRegionsGetScheduled() throws Exception {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        try (TickRegionScheduler scheduler = new TickRegionScheduler(1, r -> {}, queue, 32)) {
            regionizer.addListener(scheduler);
            regionizer.addListener(queue);

            // Build A-B-C (single region), then remove B → C peels off as fresh region.
            regionizer.addChunk(new ChunkPos(0, 0));
            regionizer.addChunk(new ChunkPos(1, 0));
            regionizer.addChunk(new ChunkPos(2, 0));
            Region beforeSplit = regionizer.regionAtChunk(0, 0);
            scheduler.register(beforeSplit); // simulate the touchChunk registration for the original

            regionizer.removeChunk(new ChunkPos(1, 0));

            Region peeled = regionizer.regionAtChunk(2, 0);
            assertThat(peeled).isNotEqualTo(beforeSplit);
            // Critical assertion: peeled region has an MSPT record because it was
            // auto-registered. Before the fix onRegionCreated was default-no-op → mspt returned null.
            assertThat(scheduler.mspt(peeled)).isNotNull();
        }
    }
}
