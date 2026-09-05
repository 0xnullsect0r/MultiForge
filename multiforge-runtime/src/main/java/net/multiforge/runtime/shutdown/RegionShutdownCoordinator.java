/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.shutdown;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.multiforge.runtime.entity.MigratingEntityRef;
import net.multiforge.runtime.entity.MigrationState;
import net.multiforge.runtime.journal.RegionJournal;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionId;
import net.multiforge.runtime.region.RegionizedTaskQueue;
import net.multiforge.runtime.region.TickRegionScheduler;

/**
 * Coordinator for graceful shutdown ({@code /stop}, SIGTERM). Walks
 * every open region through {@link ShutdownPhase}:
 *
 * <ol>
 *   <li>Flip to {@link ShutdownPhase#DRAINING_INBOX} — the network
 *       layer stops accepting new packets. Workers keep ticking.</li>
 *   <li>Wait until every region's {@link RegionizedTaskQueue} inbox is
 *       empty, every migrating entity has landed
 *       ({@link MigrationState#MIGRATING} count == 0), and the orphan
 *       queue is empty — bounded by a wall-clock deadline.</li>
 *   <li>Flip to {@link ShutdownPhase#FLUSHING_JOURNAL}, close every
 *       {@link RegionJournal} (this fsyncs and closes the fd).</li>
 *   <li>Flip to {@link ShutdownPhase#STOPPING_WORKERS} and call
 *       {@link TickRegionScheduler#close()}.</li>
 * </ol>
 *
 * <p>Progress is observable via {@link #phase()}. A blocked shutdown
 * (deadline exceeded) still advances to the next phase — the operator
 * asked to stop, so we finish and log rather than hanging.
 */
public final class RegionShutdownCoordinator {

    /** Callback invoked when the shutdown coordinator emits progress. */
    @FunctionalInterface
    public interface ProgressListener {
        void onPhaseAdvanced(ShutdownPhase from, ShutdownPhase to, long inboxRemaining, long migrationsInFlight);
    }

    private final TickRegionScheduler scheduler;
    private final RegionizedTaskQueue taskQueue;
    private final List<Region> regions = new CopyOnWriteArrayList<>();
    private final List<RegionJournal> journals = new CopyOnWriteArrayList<>();
    // Region-id-keyed journal slots, populated by RegionJournalLifecycle and
    // similar wire-in listeners. Kept parallel to `journals` (which is the
    // legacy list-only API) so callers can trackJournal(RegionId, journal)
    // and later untrackJournal(RegionId) without a linear scan or a
    // reference-identity collision. Both containers are walked during the
    // FLUSHING_JOURNAL phase; a single journal appearing in both is closed
    // twice, which RegionJournal.close() tolerates via its idempotent guard.
    private final ConcurrentMap<RegionId, RegionJournal> journalsByRegion = new ConcurrentHashMap<>();
    private final List<MigratingEntityRef> migratingRefs = new CopyOnWriteArrayList<>();
    private final AtomicReference<ShutdownPhase> phase = new AtomicReference<>(ShutdownPhase.ACCEPTING);
    private final CopyOnWriteArrayList<ProgressListener> listeners = new CopyOnWriteArrayList<>();

    public RegionShutdownCoordinator(TickRegionScheduler scheduler, RegionizedTaskQueue taskQueue) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.taskQueue = Objects.requireNonNull(taskQueue, "taskQueue");
    }

    public ShutdownPhase phase() {
        return phase.get();
    }

    public boolean acceptingWork() {
        return phase.get() == ShutdownPhase.ACCEPTING;
    }

    public void trackRegion(Region region) {
        regions.add(Objects.requireNonNull(region, "region"));
    }

    public void trackJournal(RegionJournal journal) {
        journals.add(Objects.requireNonNull(journal, "journal"));
    }

    /**
     * Region-id-keyed journal tracking. Used by {@link
     * net.multiforge.runtime.journal.RegionJournalLifecycle} so the
     * coordinator's {@link ShutdownPhase#FLUSHING_JOURNAL} phase closes
     * every live per-region journal, and so a dead region's journal can
     * be un-tracked via {@link #untrackJournal(RegionId)} without a
     * linear scan. Replaces a prior slot for the same region id
     * (silently — the old journal must have been closed by its owner
     * before re-registering).
     */
    public void trackJournal(RegionId regionId, RegionJournal journal) {
        Objects.requireNonNull(regionId, "regionId");
        Objects.requireNonNull(journal, "journal");
        journalsByRegion.put(regionId, journal);
    }

    /**
     * Remove the journal slot previously registered via
     * {@link #trackJournal(RegionId, RegionJournal)}. No-op if none.
     * Caller is responsible for closing the journal; this only detaches
     * it from the coordinator's flush walk.
     */
    public RegionJournal untrackJournal(RegionId regionId) {
        Objects.requireNonNull(regionId, "regionId");
        return journalsByRegion.remove(regionId);
    }

    /** Snapshot of every region-id-keyed journal currently tracked. */
    public List<RegionJournal> trackedJournalsByRegion() {
        return List.copyOf(journalsByRegion.values());
    }

    public void trackMigrationRef(MigratingEntityRef ref) {
        migratingRefs.add(Objects.requireNonNull(ref, "ref"));
    }

    public void addListener(ProgressListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Kick off the shutdown, blocking the caller until every phase
     * completes. Safe to call from a shutdown hook — never from a
     * region worker thread.
     *
     * @return the final phase (always {@link ShutdownPhase#STOPPED}).
     */
    public ShutdownPhase shutdown(Duration drainDeadline) throws IOException {
        Objects.requireNonNull(drainDeadline, "drainDeadline");
        advance(ShutdownPhase.ACCEPTING, ShutdownPhase.DRAINING_INBOX);

        long deadline = System.nanoTime() + drainDeadline.toNanos();
        long pollNanos = TimeUnit.MILLISECONDS.toNanos(50);
        while (!drainedCompletely() && System.nanoTime() < deadline) {
            park(pollNanos);
        }

        advance(ShutdownPhase.DRAINING_INBOX, ShutdownPhase.FLUSHING_JOURNAL);
        IOException first = null;
        for (RegionJournal j : journals) {
            try {
                j.close();
            } catch (IOException e) {
                if (first == null) first = e;
                else first.addSuppressed(e);
            }
        }
        // Also drain region-id-keyed journals registered via
        // trackJournal(RegionId, ...). A journal that lives in both
        // slots is closed twice; RegionJournal.close() is idempotent
        // (guarded by its own `closed` flag) so this is safe.
        for (RegionJournal j : journalsByRegion.values()) {
            try {
                j.close();
            } catch (IOException e) {
                if (first == null) first = e;
                else first.addSuppressed(e);
            }
        }

        advance(ShutdownPhase.FLUSHING_JOURNAL, ShutdownPhase.STOPPING_WORKERS);
        scheduler.close();
        advance(ShutdownPhase.STOPPING_WORKERS, ShutdownPhase.STOPPED);
        if (first != null) throw first;
        return ShutdownPhase.STOPPED;
    }

    /**
     * Blocking predicate exposed for tests: every inbox is empty, the
     * orphan queue is empty, and no entity is mid-migration.
     */
    public boolean drainedCompletely() {
        for (Region r : regions) {
            if (taskQueue.inboxSize(r) > 0) return false;
        }
        if (taskQueue.orphanedSize() > 0) return false;
        for (MigratingEntityRef ref : migratingRefs) {
            if (ref.migrationState() == MigrationState.MIGRATING) return false;
        }
        return true;
    }

    public long totalInboxDepth() {
        long total = 0;
        for (Region r : regions) total += taskQueue.inboxSize(r);
        total += taskQueue.orphanedSize();
        return total;
    }

    public long migrationsInFlight() {
        long n = 0;
        for (MigratingEntityRef ref : migratingRefs) {
            if (ref.migrationState() == MigrationState.MIGRATING) n++;
        }
        return n;
    }

    public List<RegionJournal> journalsForTesting() {
        return List.copyOf(journals);
    }

    private void advance(ShutdownPhase from, ShutdownPhase to) {
        if (!phase.compareAndSet(from, to)) {
            // Already advanced by a concurrent shutdown — the caller
            // asked twice. Skip rather than reversing.
            return;
        }
        long inbox = totalInboxDepth();
        long migrations = migrationsInFlight();
        for (ProgressListener l : listeners) {
            l.onPhaseAdvanced(from, to, inbox, migrations);
        }
    }

    private static void park(long nanos) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Snapshot for diagnostics (region ids only). */
    public List<String> trackedRegionIds() {
        List<String> ids = new ArrayList<>(regions.size());
        for (Region r : regions) ids.add(r.id().toString());
        return ids;
    }
}
