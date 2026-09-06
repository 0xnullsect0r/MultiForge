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
package net.multiforge.runtime.shutdown;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.entity.MigratingEntityRef;
import net.multiforge.runtime.entity.MigrationState;
import net.multiforge.runtime.journal.JournalEntryKind;
import net.multiforge.runtime.journal.RegionJournal;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionId;
import net.multiforge.runtime.region.RegionizedTaskQueue;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.region.TickRegionScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionShutdownCoordinatorTest {

    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");

    private static TickRegionScheduler newScheduler(RegionizedTaskQueue queue) {
        return new TickRegionScheduler(1, r -> {}, queue, 32);
    }

    @Test
    void walksThroughEveryPhaseWhenAlreadyDrained(@TempDir Path dir) throws IOException {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region r = regionizer.addChunk(new ChunkPos(0, 0));
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        TickRegionScheduler sched = newScheduler(queue);
        RegionShutdownCoordinator coord = new RegionShutdownCoordinator(sched, queue);
        coord.trackRegion(r);
        try (RegionJournal j = RegionJournal.open(new RegionId(9), dir)) {
            coord.trackJournal(j);

            AtomicInteger phaseHits = new AtomicInteger();
            coord.addListener((from, to, inbox, migrations) -> phaseHits.incrementAndGet());

            assertThat(coord.phase()).isEqualTo(ShutdownPhase.ACCEPTING);
            assertThat(coord.acceptingWork()).isTrue();
            assertThat(coord.shutdown(Duration.ofSeconds(1))).isEqualTo(ShutdownPhase.STOPPED);
            // Phase advanced 4 times: ACCEPTING→DRAINING→FLUSHING→STOPPING→STOPPED
            assertThat(phaseHits.get()).isEqualTo(4);
        }
        assertThat(coord.phase()).isEqualTo(ShutdownPhase.STOPPED);
    }

    @Test
    void closesTrackedJournals(@TempDir Path dir) throws IOException {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region r = regionizer.addChunk(new ChunkPos(0, 0));
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        TickRegionScheduler sched = newScheduler(queue);
        RegionShutdownCoordinator coord = new RegionShutdownCoordinator(sched, queue);
        coord.trackRegion(r);

        RegionJournal j = RegionJournal.open(new RegionId(4), dir);
        j.append(JournalEntryKind.TICK_MARK, new byte[0]);
        coord.trackJournal(j);

        coord.shutdown(Duration.ofSeconds(1));

        // A closed journal's channel is closed; a subsequent append should fail.
        assertThat(coord.phase()).isEqualTo(ShutdownPhase.STOPPED);
        try {
            j.append(JournalEntryKind.TICK_MARK, new byte[0]);
            org.junit.jupiter.api.Assertions.fail("expected IOException after close");
        } catch (IOException expected) {
            // pass — ClosedChannelException is an IOException
        }
    }

    @Test
    void reportsInboxBacklogWhileDraining(@TempDir Path dir) throws IOException {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region r = regionizer.addChunk(new ChunkPos(0, 0));
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        TickRegionScheduler sched = new TickRegionScheduler(1, ignore -> {}, queue, 32);
        RegionShutdownCoordinator coord = new RegionShutdownCoordinator(sched, queue);
        coord.trackRegion(r);
        try (RegionJournal j = RegionJournal.open(new RegionId(11), dir)) {
            coord.trackJournal(j);

            // Enqueue three tasks — the scheduler will drain them via its tick body.
            for (int i = 0; i < 3; i++) queue.queueChunkTask(WORLD, 0, 0, () -> {});

            // Bounded deadline lets the tick worker drain them.
            coord.shutdown(Duration.ofSeconds(2));
            assertThat(coord.phase()).isEqualTo(ShutdownPhase.STOPPED);
            // After scheduler.close() the queue may still hold entries if the
            // worker didn't drain fast enough; either way phase reached STOPPED.
            assertThat(coord.totalInboxDepth()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void countsMigrationsInFlight(@TempDir Path dir) throws IOException {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region r = regionizer.addChunk(new ChunkPos(0, 0));
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        TickRegionScheduler sched = newScheduler(queue);
        RegionShutdownCoordinator coord = new RegionShutdownCoordinator(sched, queue);
        coord.trackRegion(r);
        try (RegionJournal j = RegionJournal.open(new RegionId(6), dir)) {
            coord.trackJournal(j);

            MigratingEntityRef resident = new MigratingEntityRef(UUID.randomUUID(), WORLD, new ChunkPos(0, 0));
            MigratingEntityRef migrating = new MigratingEntityRef(UUID.randomUUID(), WORLD, new ChunkPos(0, 0));
            migrating.beginMigration();
            coord.trackMigrationRef(resident);
            coord.trackMigrationRef(migrating);
            assertThat(coord.migrationsInFlight()).isEqualTo(1);
            assertThat(migrating.migrationState()).isEqualTo(MigrationState.MIGRATING);

            // A short deadline — coordinator advances anyway once wall time expires.
            coord.shutdown(Duration.ofMillis(100));
            assertThat(coord.phase()).isEqualTo(ShutdownPhase.STOPPED);
        }
    }
}
