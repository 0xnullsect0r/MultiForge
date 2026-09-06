/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.entity;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.multiforge.api.entity.EntityRef;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.diagnostics.ViolationLogger;

/**
 * Runtime {@link EntityRef} that mutates its world/chunk pointer as the entity migrates between
 * regions. All observers (schedulers, region workers) hold a stable reference to this object and
 * re-read {@link #world()} / {@link #chunkPos()} on every dispatch to hit the current owner.
 *
 * <p>State transitions are atomic — a mid-flight cancel or retirement cannot leave the ref in an
 * inconsistent state. See {@code docs/design/entity-migration.md} §1 for the frozen state-machine
 * contract this class implements.
 */
public final class MigratingEntityRef implements EntityRef {

    private final UUID uuid;
    private final AtomicReference<Location> location;
    private final AtomicReference<MigrationState> state = new AtomicReference<>(MigrationState.RESIDENT);

    /**
     * Set when {@link #retire()} is called while this ref is {@link MigrationState#MIGRATING}: the
     * state is deliberately left at {@code MIGRATING} (no worker may mutate a ref mid-flight) and
     * the retirement is deferred until the destination discovers it via {@link
     * #consumePendingRetire()} — docs/design/entity-migration.md §1.4.
     */
    private final AtomicBoolean pendingRetire = new AtomicBoolean(false);

    /**
     * Packets a caller attempted to send to this entity (a player, in practice) while this ref was
     * {@link MigrationState#MIGRATING} — docs/design/entity-migration.md's networking rules (M4
     * Track A3.2). Element type is deliberately {@code Object}: this class is Minecraft-free, so
     * the fork glue ({@code net.multiforge.neoforge.entity.NetworkMigrationBridge}) is the one that
     * knows the real packet/listener/flush shape and boxes it before calling {@link
     * #enqueueOutbound(Object)}. Order-preserving (FIFO) — drained in the same order enqueued.
     */
    private final Deque<Object> pendingOutbound = new ConcurrentLinkedDeque<>();

    /**
     * Fired exactly once per real state transition that leaves this ref {@code MIGRATING}
     * ({@link #completeMigration}, {@link #abortMigration}, or the immediate — non-deferred —
     * branch of {@link #retire()}). A2/A3 fork glue uses this to know when it's safe to drain
     * {@link #pendingOutbound} and/or apply a deferred Vanilla position update. Registering a
     * listener while this ref is already past {@code MIGRATING} runs it inline immediately.
     *
     * <p>Element type is {@link FiredOnceListener}, not a bare {@code Runnable} — see {@link
     * #addSettledListener} for why the at-most-once guard lives on each entry rather than being
     * implied by "removed from the list means it already ran."
     */
    private final List<FiredOnceListener> settledListeners = new CopyOnWriteArrayList<>();

    public MigratingEntityRef(UUID uuid, WorldRef world, ChunkPos chunkPos) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.location = new AtomicReference<>(
                new Location(Objects.requireNonNull(world, "world"), Objects.requireNonNull(chunkPos, "chunkPos")));
    }

    @Override
    public UUID uuid() {
        return uuid;
    }

    @Override
    public WorldRef world() {
        return location.get().world();
    }

    @Override
    public ChunkPos chunkPos() {
        return location.get().chunkPos();
    }

    @Override
    public boolean isRetired() {
        return state.get() == MigrationState.RETIRED;
    }

    public MigrationState migrationState() {
        return state.get();
    }

    /**
     * Attempt RESIDENT → MIGRATING. Returns {@code true} iff we won the CAS; caller then owns the
     * migration. A losing caller must treat {@code false} as "already migrating or retired,
     * no-op" — never retry in the same tick, never throw (CLAUDE.md ground rule 5).
     */
    public boolean beginMigration() {
        return state.compareAndSet(MigrationState.RESIDENT, MigrationState.MIGRATING);
    }

    /**
     * Called on the destination region worker after the entity has been re-added there. Publishes
     * the new location and transitions MIGRATING → RESIDENT atomically.
     *
     * <p><b>CAS-first, publish-on-success-only.</b> The prior scaffold published {@code location}
     * unconditionally before checking the CAS, so a duplicate or out-of-order delivery (which
     * should not happen — {@link net.multiforge.runtime.region.RegionizedTaskQueue#drain} is
     * single-delivery per enqueue — but a defensive contract is required regardless, per
     * docs/design/entity-migration.md §1.3) could silently move an already-resident entity
     * backward even though the CAS then failed. Location is now published only when the CAS
     * actually wins; a failing CAS is a rate-limited {@link ViolationLogger} warning and otherwise
     * a no-op.
     *
     * @return {@code true} iff the CAS won and the location was published.
     */
    public boolean completeMigration(WorldRef newWorld, ChunkPos newChunkPos) {
        Objects.requireNonNull(newWorld, "newWorld");
        Objects.requireNonNull(newChunkPos, "newChunkPos");
        if (state.compareAndSet(MigrationState.MIGRATING, MigrationState.RESIDENT)) {
            location.set(new Location(newWorld, newChunkPos));
            fireSettled();
            return true;
        }
        ViolationLogger.warn(
                "entity-migration",
                "completeMigration() called on " + uuid + " while state=" + state.get()
                        + " (expected MIGRATING) — duplicate or out-of-order delivery, ignored");
        return false;
    }

    /**
     * Called if the migration fails (destination refused) and the source is putting the entity
     * back. No-op if the ref is not currently MIGRATING (e.g. the destination already completed
     * it, or the ref was retired mid-flight) — intentional, per §1.3.
     */
    public void abortMigration() {
        if (state.compareAndSet(MigrationState.MIGRATING, MigrationState.RESIDENT)) {
            fireSettled();
        }
    }

    /**
     * Terminal transition — CAS-based and idempotent (docs/design/entity-migration.md §1.4).
     *
     * <ul>
     *   <li>{@code RESIDENT → RETIRED}: normal case, any thread that owns the entity at the
     *       moment of death.
     *   <li>{@code MIGRATING → RETIRED}: deferred. The state is left at {@code MIGRATING} (no
     *       worker may mutate it) and {@link #pendingRetire} is set instead; the destination's
     *       {@code EntityMigrationCoordinator.completeAt} consults {@link
     *       #consumePendingRetire()} before publishing the freshly-materialized ref as {@code
     *       RESIDENT} and retires it directly instead if set.
     *   <li>Already {@code RETIRED}: no-op, returns {@code false}.
     * </ul>
     *
     * @return {@code true} iff this call changed something (transitioned to RETIRED or set the
     *     deferred-retire marker); {@code false} if the ref was already RETIRED or a racing
     *     transition beat us to it.
     */
    public boolean retire() {
        MigrationState prev = state.get();
        if (prev == MigrationState.RETIRED) return false;
        if (prev == MigrationState.MIGRATING) {
            // Deliberately does NOT call fireSettled() here: the state is left at MIGRATING
            // (§1.4), this specific ref object never itself settles, and any packets already
            // queued in pendingOutbound are abandoned along with it — the destination's fresh
            // ref (materialized RETIRED directly, per completeRecursive) is what the rest of the
            // system observes from here on.
            pendingRetire.set(true);
            return true;
        }
        boolean changed = state.compareAndSet(prev, MigrationState.RETIRED);
        if (changed) fireSettled();
        return changed;
    }

    /**
     * Consumes (clears and returns) the deferred-retire marker set by a {@link #retire()} call
     * that landed while this ref was {@code MIGRATING}. Callers (the destination's completion
     * path) must check this exactly once per completion attempt before publishing the fresh ref
     * as {@code RESIDENT}.
     */
    public boolean consumePendingRetire() {
        return pendingRetire.getAndSet(false);
    }

    /**
     * Passenger-tree atomic snapshot (docs/design/entity-migration.md §2.1-§2.2): given an
     * already-DFS-collected, parent-before-children list of refs, attempt {@link
     * #beginMigration()} on each in order. The first CAS failure aborts the <em>entire</em>
     * attempt — every ref already flipped to {@code MIGRATING} earlier in this same pass is
     * immediately rolled back via {@link #abortMigration()} before returning.
     *
     * <p>Two-pass by construction: the caller collects the full list first (DFS walk of {@code
     * Entity.getPassengers()} in the real Vanilla patch), then this method does the single CAS
     * pass. This lets the abort loop iterate the same fully-materialized list rather than
     * reconstructing "which prefix did we already flip" mid-DFS.
     *
     * <p>Invariant (§2.5): no external observer can catch a mixed {@code {RESIDENT, MIGRATING}}
     * state within {@code refs} once this method returns — either every element is {@code
     * MIGRATING} ({@code true} returned) or every element is back at its pre-attempt state
     * ({@code false} returned).
     *
     * @return {@code true} iff every ref in {@code refs} is now {@code MIGRATING}; {@code false}
     *     if the whole attempt was aborted and rolled back.
     */
    public static boolean beginPassengerTreeSnapshot(List<MigratingEntityRef> refs) {
        Objects.requireNonNull(refs, "refs");
        List<MigratingEntityRef> flipped = new ArrayList<>(refs.size());
        for (MigratingEntityRef ref : refs) {
            if (ref.beginMigration()) {
                flipped.add(ref);
            } else {
                for (MigratingEntityRef toRestore : flipped) {
                    toRestore.abortMigration();
                }
                return false;
            }
        }
        return true;
    }

    // === Networking hand-off (docs/design/entity-migration.md networking rules, M4 Track A3) =====

    /**
     * Queues {@code packet} (an opaque, fork-glue-boxed send request) instead of letting it go out
     * over the wire while this ref is {@code MIGRATING}. Callers are responsible for checking
     * {@link #migrationState()} themselves before calling this — this method does not itself
     * re-check the state, since the caller (e.g. {@code Connection.send}) already made that
     * decision atomically enough for its own purposes.
     *
     * <p><b>Not harmless on its own.</b> A caller that checks {@code migrationState() ==
     * MIGRATING} and then calls this method has a check-then-enqueue race against the terminal
     * CAS + {@link #fireSettled()}'s drain-and-fire (which is a snapshot-and-clear, not a
     * repeating drain): if the settle wins that race in between the caller's check and this
     * call, nothing will ever come back to flush {@code packet}. A caller that cares about
     * zero-drop delivery (e.g. {@code NetworkMigrationBridge.enqueueIfMigrating}) must re-check
     * {@link #migrationState()} <em>after</em> calling this method and, if no longer {@code
     * MIGRATING}, race to reclaim {@code packet} via {@link #removeOutbound(Object)} and deliver
     * it directly — identity-based deque removal is the at-most-once gate against the settle
     * path draining the same instance concurrently.
     */
    public void enqueueOutbound(Object packet) {
        pendingOutbound.addLast(Objects.requireNonNull(packet, "packet"));
    }

    /**
     * Attempts to remove {@code packet} (by identity/equality, whichever the element's own {@code
     * equals} uses) from {@link #pendingOutbound} before the settle path's {@link
     * #fireSettled()}-triggered drain gets to it. Returns {@code true} iff this call actually
     * removed it.
     *
     * <p>This is the at-most-once gate for the {@link #enqueueOutbound} check-then-enqueue race
     * documented there: at most one of "a concurrent {@link #drainOutbound()} call" or this
     * method can find and remove any given enqueued instance, so a caller that gets {@code true}
     * back is the sole, guaranteed-once owner of delivering {@code packet} from here on. A {@code
     * false} return means the settle path already claimed and is delivering (or has delivered) it
     * — the caller must not resend.
     */
    public boolean removeOutbound(Object packet) {
        return pendingOutbound.remove(Objects.requireNonNull(packet, "packet"));
    }

    /**
     * Drains every currently-queued outbound item in FIFO (enqueue) order. Safe to call more than
     * once — a second call simply returns an empty list once the deque is empty.
     */
    public List<Object> drainOutbound() {
        List<Object> out = new ArrayList<>();
        Object next;
        while ((next = pendingOutbound.pollFirst()) != null) {
            out.add(next);
        }
        return out;
    }

    /**
     * Coordinator-only: forces this ref from {@code MIGRATING} straight to {@code RETIRED} as a
     * single atomic step, bypassing {@link #retire()}'s §1.4 defer-while-MIGRATING semantics.
     * Fires {@link #addSettledListener} listeners exactly once, at the actual terminal state —
     * unlike calling {@link #abortMigration()} then {@link #retire()} back to back, which would
     * fire listeners twice, first while transiently {@code RESIDENT} (before {@link #isRetired()}
     * is true).
     *
     * <p>Used only when this ref is being superseded by an already-decided outcome elsewhere — a
     * successful migration (the fresh ref at the destination is what's live now) or a timed-out
     * migration's fallback restore (a fresh ref at the source is what's live now) — never for an
     * actual entity death, which must go through {@link #retire()}'s defer-until-the-destination-
     * discovers-it path instead.
     *
     * @return {@code true} iff this call performed the transition (was {@code MIGRATING}); {@code
     *     false} if some other thread already moved this ref off {@code MIGRATING} first.
     */
    boolean forceTerminalFromMigrating() {
        if (!state.compareAndSet(MigrationState.MIGRATING, MigrationState.RETIRED)) {
            return false;
        }
        fireSettled();
        return true;
    }

    /**
     * Registers {@code listener} to run once this ref next leaves {@code MIGRATING} via a real
     * state transition ({@link #completeMigration}, {@link #abortMigration}, {@link
     * #forceTerminalFromMigrating()}, or the immediate branch of {@link #retire()}). If this ref
     * is not currently {@code MIGRATING}, runs {@code listener} inline immediately instead of
     * queuing it — there is nothing left to wait for. Fires at most once per ref instance: every
     * one of those transitions is a terminal-or-stable exit from {@code MIGRATING} for that
     * instance (the state machine never re-enters {@code MIGRATING} from {@code RESIDENT}/{@code
     * RETIRED}), so there is nothing left to wait for after the first firing.
     *
     * <p><b>Lost-notification race and its fix.</b> The pre-existing check-then-add here (read
     * {@link #state}, then conditionally {@code settledListeners.add}) raced against {@link
     * #fireSettled()}: another thread could win the terminal CAS and run {@link #fireSettled()}'s
     * snapshot-and-clear of {@link #settledListeners} in the gap between this method's state read
     * and its {@code add} — {@code fireSettled()} never revisits {@link #settledListeners} after
     * that snapshot, so a listener landing in it afterward would sit forever, unfired. This method
     * now re-reads {@link #state} <em>after</em> adding, and if the ref has already left {@code
     * MIGRATING} by then, removes the just-added entry and fires it inline itself. Each entry is
     * wrapped in a {@link FiredOnceListener} so that if this inline path and a concurrently
     * in-flight {@link #fireSettled()} both try to run the same entry, only one of them wins — the
     * exactly-once contract holds either way.
     */
    public void addSettledListener(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        if (state.get() != MigrationState.MIGRATING) {
            listener.run();
            return;
        }
        FiredOnceListener wrapped = new FiredOnceListener(listener);
        settledListeners.add(wrapped);
        // Re-check: fireSettled() may have snapshotted-and-cleared settledListeners between the
        // check above and the add just above, in which case nothing will ever iterate `wrapped`
        // again. If we're no longer MIGRATING now, reclaim and fire it ourselves.
        if (state.get() != MigrationState.MIGRATING) {
            settledListeners.remove(wrapped);
            wrapped.fire();
        }
    }

    private void fireSettled() {
        if (settledListeners.isEmpty()) return;
        List<FiredOnceListener> toRun = new ArrayList<>(settledListeners);
        settledListeners.clear();
        for (FiredOnceListener listener : toRun) {
            listener.fire();
        }
    }

    /**
     * At-most-once wrapper around a {@link #addSettledListener} {@code Runnable}, so that the
     * inline-fire-on-lost-race path in {@link #addSettledListener} and a concurrently in-flight
     * {@link #fireSettled()} can both hold a reference to the same entry without risking a double
     * fire — whichever of the two calls {@link #fire()} first wins the {@link AtomicBoolean} CAS
     * and runs the delegate; the loser is a no-op.
     */
    private static final class FiredOnceListener {
        private final Runnable delegate;
        private final AtomicBoolean fired = new AtomicBoolean(false);

        FiredOnceListener(Runnable delegate) {
            this.delegate = delegate;
        }

        void fire() {
            if (fired.compareAndSet(false, true)) {
                delegate.run();
            }
        }
    }

    private record Location(WorldRef world, ChunkPos chunkPos) {}
}
