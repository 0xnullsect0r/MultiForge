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
package net.multiforge.runtime.journal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionId;
import net.multiforge.runtime.region.RegionListener;
import net.multiforge.runtime.shutdown.RegionShutdownCoordinator;

/**
 * {@link RegionListener} that opens a per-region {@link RegionJournal}
 * on {@link #onRegionCreated} and closes it on {@link #onRegionDied}.
 *
 * <p>Registered by {@link
 * net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost} on every
 * per-world regionizer so the M9 write-ahead-log plumbing runs
 * automatically: a freshly-published region already has a journal by
 * the time its first tick fires, and the journal is flushed and closed
 * the moment the region dies (either by merge or natural section loss).
 *
 * <p>Also handshakes with the {@link RegionShutdownCoordinator} so the
 * coordinator's {@link
 * net.multiforge.runtime.shutdown.ShutdownPhase#FLUSHING_JOURNAL} phase
 * closes every live per-region journal, even those whose owning region
 * never fired {@link #onRegionDied} before the shutdown began.
 *
 * <h2>Merge semantics</h2>
 * On {@link #onRegionsMerging(Region, Region)} the {@code dying}
 * region's journal is closed. Its committed entries linger on disk in
 * the dying region's {@code region-&lt;id&gt;.mjl} file, and
 * {@link JournalReplayHarness#replayAll(Path, JournalReplayHarness)}
 * picks them up on the next boot — matching the design contract at
 * {@code docs/design/m9-contracts.md} §"Merge preserves durability".
 *
 * <h2>Failure handling</h2>
 * A journal that fails to open (disk full, permission denied) is
 * logged via {@link ViolationLogger} and the region proceeds without a
 * journal. This preserves CLAUDE.md rule 5 (auto-reroute + warn) — the
 * region continues ticking; only its crash-recovery durability is
 * degraded until the operator fixes the underlying I/O condition. The
 * shutdown coordinator will still walk its (empty) journal slot on
 * shutdown, which is a no-op.
 */
public final class RegionJournalLifecycle implements RegionListener {

    private final Path journalDir;
    private final RegionShutdownCoordinator shutdownCoordinator;
    private final ConcurrentMap<RegionId, RegionJournal> byRegion = new ConcurrentHashMap<>();

    /**
     * Construct a lifecycle listener that stores journals under
     * {@code journalDir}. When {@code shutdownCoordinator} is non-null,
     * every opened journal is registered with it via
     * {@link RegionShutdownCoordinator#trackJournal(RegionId, RegionJournal)}
     * so it participates in the {@code FLUSHING_JOURNAL} shutdown phase.
     */
    public RegionJournalLifecycle(Path journalDir, RegionShutdownCoordinator shutdownCoordinator) {
        this.journalDir = Objects.requireNonNull(journalDir, "journalDir");
        this.shutdownCoordinator = shutdownCoordinator;
    }

    @Override
    public void onRegionCreated(Region region) {
        RegionId id = region.id();
        // computeIfAbsent so a rapid create-die-create for the same id
        // (impossible under RegionId.next()'s monotonic contract, but
        // cheap insurance for tests) never double-opens the same file.
        byRegion.computeIfAbsent(id, key -> {
            try {
                RegionJournal j = RegionJournal.open(id, journalDir);
                if (shutdownCoordinator != null) {
                    shutdownCoordinator.trackJournal(id, j);
                }
                return j;
            } catch (IOException e) {
                ViolationLogger.warn(
                        "RegionJournalLifecycle.onRegionCreated",
                        "failed to open journal for region " + id + " in " + journalDir + ": " + e.getMessage());
                return null;
            }
        });
    }

    /**
     * Regionizer signals a merge is about to fire. The dying region's
     * journal is closed here so any in-flight append completes before
     * the region's ownership dissolves. {@code onRegionDied} still
     * fires immediately after and untracks the same slot; both callers
     * tolerate a null journal because a merge without an open journal
     * (e.g. failed-open case above) leaves nothing to clean up.
     */
    @Override
    public void onRegionsMerging(Region surviving, Region dying) {
        closeAndUntrack(dying.id());
    }

    @Override
    public void onRegionDied(Region region) {
        closeAndUntrack(region.id());
    }

    private void closeAndUntrack(RegionId id) {
        RegionJournal j = byRegion.remove(id);
        if (j != null) {
            try {
                j.close();
            } catch (IOException e) {
                ViolationLogger.warn(
                        "RegionJournalLifecycle.close",
                        "failed to close journal for region " + id + ": " + e.getMessage());
            }
        }
        if (shutdownCoordinator != null) {
            shutdownCoordinator.untrackJournal(id);
        }
    }

    /** Returns the journal for {@code id}, or {@code null} if none. */
    public RegionJournal journalFor(RegionId id) {
        return byRegion.get(id);
    }

    /** Test-only: current count of open journals tracked by this listener. */
    public int size() {
        return byRegion.size();
    }
}
