/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionizedTaskQueue;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.region.TickRegionScheduler;
import net.multiforge.runtime.shutdown.RegionShutdownCoordinator;
import net.multiforge.runtime.shutdown.ShutdownPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@link RegionJournalLifecycle}: opens journals on region
 * creation, closes them on region death, and hands each opened journal
 * to the {@link RegionShutdownCoordinator} for the {@code FLUSHING_JOURNAL}
 * shutdown phase.
 */
class RegionJournalLifecycleTest {

    private static final WorldRef WORLD = WorldRef.of("test:journal-lifecycle");

    @Test
    void opensJournalOnRegionCreationAndClosesOnDeath(@TempDir Path journalDir) throws IOException {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionJournalLifecycle lifecycle = new RegionJournalLifecycle(journalDir, null);
        regionizer.addListener(lifecycle);

        assertThat(lifecycle.size()).isEqualTo(0);
        Region region = regionizer.addChunk(new ChunkPos(0, 0));
        assertThat(lifecycle.size()).isEqualTo(1);
        RegionJournal journal = lifecycle.journalFor(region.id());
        assertThat(journal).isNotNull();

        // A journal file exists under the temp dir with the region id in its name.
        Path expected = journalDir.resolve("region-" + region.id().value() + ".mjl");
        assertThat(expected).exists();

        // Death path: remove the last section, expect the journal closed + removed.
        regionizer.removeChunk(new ChunkPos(0, 0));
        assertThat(lifecycle.size()).isEqualTo(0);
        assertThat(lifecycle.journalFor(region.id())).isNull();
        assertThat(journal.isClosed()).isTrue();
    }

    @Test
    void tracksJournalsInShutdownCoordinator(@TempDir Path journalDir) throws IOException {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        TickRegionScheduler scheduler = new TickRegionScheduler(1, r -> {}, queue, 32);
        try {
            RegionShutdownCoordinator coord = new RegionShutdownCoordinator(scheduler, queue);
            RegionJournalLifecycle lifecycle = new RegionJournalLifecycle(journalDir, coord);
            regionizer.addListener(lifecycle);

            Region region = regionizer.addChunk(new ChunkPos(0, 0));
            RegionJournal journal = lifecycle.journalFor(region.id());
            assertThat(journal).isNotNull();

            // The coordinator's region-id-keyed journal slots include this one.
            assertThat(coord.trackedJournalsByRegion()).containsExactly(journal);

            // Death untracks it from the coordinator too.
            regionizer.removeChunk(new ChunkPos(0, 0));
            assertThat(coord.trackedJournalsByRegion()).isEmpty();
        } finally {
            scheduler.close();
        }
    }

    @Test
    void shutdownFlushingJournalPhaseClosesTrackedJournals(@TempDir Path journalDir) throws IOException {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        TickRegionScheduler scheduler = new TickRegionScheduler(1, r -> {}, queue, 32);
        RegionShutdownCoordinator coord = new RegionShutdownCoordinator(scheduler, queue);
        RegionJournalLifecycle lifecycle = new RegionJournalLifecycle(journalDir, coord);
        regionizer.addListener(lifecycle);

        Region region = regionizer.addChunk(new ChunkPos(0, 0));
        RegionJournal journal = lifecycle.journalFor(region.id());
        assertThat(journal).isNotNull();
        coord.trackRegion(region);

        assertThat(coord.shutdown(java.time.Duration.ofMillis(200))).isEqualTo(ShutdownPhase.STOPPED);
        assertThat(journal.isClosed()).isTrue();
    }

    @Test
    void mergeCloseIsNoopIfNoJournalOpened(@TempDir Path journalDir) {
        // A regionizer with no listener wired — RegionJournalLifecycle.onRegionsMerging
        // still tolerates a not-yet-tracked region id (no NPE, no throw).
        RegionJournalLifecycle lifecycle = new RegionJournalLifecycle(journalDir, null);
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region a = regionizer.addChunk(new ChunkPos(0, 0));
        Region b = regionizer.addChunk(new ChunkPos(2, 2));
        // Call directly to force the merge branch — no journal was ever
        // opened for either region because the listener wasn't wired.
        lifecycle.onRegionsMerging(a, b);
        lifecycle.onRegionDied(a);
        lifecycle.onRegionDied(b);
        assertThat(lifecycle.size()).isEqualTo(0);
    }
}
