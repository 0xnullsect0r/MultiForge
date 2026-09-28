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

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether region workers are running right now, and the server-thread work
 * held back until they stop.
 *
 * <p>{@link TickRegionScheduler#driveTick} sets the flag, on the server thread,
 * for exactly the window in which at least one region ticks on a worker (the
 * global region included): from submitting the first worker to the barrier.
 * During that window the server thread only pumps: serial-lane listeners and,
 * for a worker waiting on a chunk load, the chunk source's tasks. Those tasks
 * promote and demote chunks, and an entity-visibility change applied then
 * races the workers moving entities between sections: a stale "stop ticking"
 * lands after a worker's "start ticking" and leaves the entity in limbo (known,
 * in a ticking section, but never ticked). So {@code
 * PersistentEntitySectionManager.updateChunkStatus} {@link #defer defers}
 * itself while the flag is set, and {@link #endWorkers} replays the calls in
 * order, on the server thread, once every worker finished.
 *
 * <p>The flag is global, not per level: the pump polls every level's chunk
 * source, and a non-player entity changing dimension moves into another level.
 * Mode {@code off} never ticks a region, so never sets it.
 *
 * <p>{@code entities.deferVisibility = false} (or {@code
 * -Dmultiforge.entities.deferVisibility=false}) turns the deferral off: the
 * transition then runs immediately, as in v1.10, and is counted ({@code
 * entity.visibility.unguarded}); in strict mode the barrier then fails.
 */
@ApiStatus.Internal
public final class RegionPhase {
    private static final Logger LOG = LoggerFactory.getLogger("multiforge.region-phase");

    /** A server-thread call held back while workers run; replayed on the server thread, in order. */
    @FunctionalInterface
    public interface Deferred {
        void replay();
    }

    private static volatile boolean workersInFlight;
    private static volatile boolean deferVisibility = true;
    private static final ConcurrentLinkedQueue<Deferred> DEFERRED = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger UNGUARDED_THIS_PHASE = new AtomicInteger();

    private RegionPhase() {}

    /** Whether at least one region is ticking on a worker right now. */
    public static boolean workersInFlight() {
        return workersInFlight;
    }

    /** The {@code entities.deferVisibility} kill switch. */
    public static boolean deferVisibility() {
        return deferVisibility;
    }

    public static void setDeferVisibility(boolean enabled) {
        deferVisibility = enabled;
    }

    /**
     * Hold {@code call} back until the workers stop, if they are running.
     * Callers check {@link #workersInFlight()} first, so the common case costs
     * one volatile read and no allocation.
     *
     * @return {@code true} if deferred and the caller must return; {@code
     *     false} if the caller runs inline (no worker in flight, or the kill
     *     switch is off)
     */
    public static boolean defer(Deferred call) {
        if (!workersInFlight) return false;
        if (!deferVisibility) {
            UNGUARDED_THIS_PHASE.incrementAndGet();
            ProbeRegistry.bump("entity.visibility.unguarded");
            return false;
        }
        DEFERRED.add(call);
        ProbeRegistry.bump("entity.visibility.deferred");
        return true;
    }

    /** Server thread: workers are about to start. */
    public static void beginWorkers() {
        UNGUARDED_THIS_PHASE.set(0);
        workersInFlight = true;
    }

    /**
     * Server thread, every worker finished: clear the flag and replay what was
     * held back, in order. A replayed call that throws is logged and skipped,
     * as the main-thread executor it came from would have done.
     *
     * @throws IllegalStateException in strict mode, when a visibility change
     *     ran while workers were in flight (the kill switch is off)
     */
    public static void endWorkers() {
        workersInFlight = false;
        flush();
        int unguarded = UNGUARDED_THIS_PHASE.getAndSet(0);
        if (unguarded > 0) {
            String msg = unguarded + " entity-visibility change(s) ran while region workers were in flight"
                    + " (entities.deferVisibility is off); entities may be left in limbo";
            if (RegionTickWatchdog.mode() == RegionTickWatchdog.Mode.STRICT) throw new IllegalStateException(msg);
            ViolationLogger.warn("entity.visibility.unguarded", msg);
        }
    }

    /** Replay every held-back call, in order. @return how many ran */
    public static int flush() {
        int n = 0;
        for (Deferred call; (call = DEFERRED.poll()) != null; ) {
            n++;
            try {
                call.replay();
            } catch (RuntimeException e) {
                ProbeRegistry.bump("entity.visibility.replay-failed");
                LOG.error("deferred entity-visibility change failed", e);
            }
        }
        return n;
    }

    /** Calls held back so far (diagnostics and tests). */
    public static int pending() {
        return DEFERRED.size();
    }

    /** Tests: clear the flag and drop anything held back, without replaying it. */
    public static void resetForTesting() {
        workersInFlight = false;
        deferVisibility = true;
        DEFERRED.clear();
        UNGUARDED_THIS_PHASE.set(0);
    }
}
