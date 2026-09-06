# Entity Migration Protocol — Design

**Status:** Phase 0 task 0.1 of the M4+M5+M6 landing plan
(`plans/bubbly-jumping-comet.md`).
**Freezes:** the semantics Track A (M4, tasks A1.1–A4.2) implements
against. Blocks Track A1–A4 implementation until reviewed.
**Existing scaffold (pure-Java, never invoked from a patch):**
`multiforge-runtime/src/main/java/net/multiforge/runtime/entity/`:
`MigratingEntityRef.java`, `EntityMigrationCoordinator.java`,
`EntityRegistry.java`, `EntitySnapshot.java`, `MigrationState.java`,
`PlayerJoinCoordinator.java` — 482 LOC total.
**Non-goals:** Vanilla patch hunks (A2), networking wiring (A3),
`GlobalSystems`/`GlobalTicker` (see `docs/design/global-region.md`,
Phase 0.2). This document specifies the contract those tasks build
against; it does not itself change any `.java` file.

---

## 0. Why two-phase migration exists

A regionized server has no single "world lock" a teleport can hold
across two chunk owners. Vanilla's `Entity.setPosRaw` mutates the
entity's position and the section-manager's spatial index inline, on
whichever thread happens to be ticking. Under MultiForge that thread
is a per-region worker with no right to touch another region's chunk
map, entity list, or tick queue (CLAUDE.md ground rule 4: no blocking
calls, no cross-region `synchronized`).

Folia solves this with `teleportAsync`: source captures a snapshot,
hands it to the destination's task queue, and the destination
re-materializes on its own thread at its own convenience. MultiForge's
existing scaffold already has this shape —
`EntityMigrationCoordinator.beginMigration` /
`EntityMigrationCoordinator.completeAt`
(`multiforge-runtime/.../entity/EntityMigrationCoordinator.java:56-91`)
— routed through `RegionizedTaskQueue.queueChunkTask`
(`multiforge-runtime/.../region/RegionizedTaskQueue.java:121-136`).
This document freezes the parts of that protocol that are
correctness-critical and under-specified in the current scaffold:
the state machine's transition ownership, passenger-tree atomicity,
the border-mid-tick race, retired-UUID lookups, cross-dimension
routing, the snapshot payload format, and rollback.

Every section below states the **contract** (what A1–A4 must
implement) and, where the current scaffold already deviates from that
contract, a **gap** the corresponding task must close.

---

## 1. `MigratingEntityRef` state machine

### 1.1 States

Defined in `MigrationState.java:12-29`:

| State | Meaning | Who may mutate the entity |
|---|---|---|
| `RESIDENT` | Owned by exactly one region worker. | That region's tick thread only. |
| `MIGRATING` | Snapshot captured, handed to destination's inbox. In flight. | Nobody. The old copy is frozen; the new copy does not exist yet. |
| `RETIRED` | Terminal. Killed, unloaded permanently, or player disconnected. | Nobody — further mutation is a bug. |

`RESIDENT` and `MIGRATING` are **per-`MigratingEntityRef` instance**,
not per-UUID. A migration always ends with the *old* ref instance
either back at `RESIDENT` (aborted) or advancing straight to
`RETIRED` (see §7.2), and a *new* `MigratingEntityRef` instance
materializing at `RESIDENT` on the destination, registered under the
same UUID. The UUID is the stable identity across the transition; the
Java object is not.

### 1.2 Transition table

| Transition | Trigger | Caller thread | Mechanism |
|---|---|---|---|
| `RESIDENT → MIGRATING` | `beginMigration()` called by the coordinator | Source region worker (owning-region tick, or a Vanilla hook running on it — `setPosRaw`, `onMove`, `teleportTo`, `changeDimension`) | `AtomicReference<MigrationState>.compareAndSet(RESIDENT, MIGRATING)` — `MigratingEntityRef.java:64-66` |
| `MIGRATING → RESIDENT` | `completeMigration(world, chunkPos)` called from the destination's drained task | Destination region worker (draining its `RegionizedTaskQueue` inbox — `ChunkHolderManager`/tick-phase INBOUND_MAILBOX) | CAS `MIGRATING → RESIDENT`, `location` published first — `MigratingEntityRef.java:73-78` |
| `MIGRATING → RESIDENT` (abort) | `abortMigration()` — destination refused, timed out, or the source rolled back a passenger-tree partial-CAS | Source region worker (same thread that called `beginMigration`), synchronously, **before** the entity is removed from the source `EntityRegistry` | CAS `MIGRATING → RESIDENT` — `MigratingEntityRef.java:84-86` |
| `RESIDENT → RETIRED` | `retire()` | Any thread that owns the entity at the moment of death: source region worker (kill, unload-without-save-slot) | Contract: CAS `RESIDENT → RETIRED`. **Gap** — see §1.4. |
| `MIGRATING → RETIRED` (deferred) | `retire()` called while a migration is in flight (entity killed by a cross-region effect, plugin command, or global-region event while its snapshot is already in the destination's inbox) | Any thread | Contract: does **not** immediately overwrite `MIGRATING`; sets a deferred-retire marker the destination's `completeAt` consults. **Gap** — see §1.4 and §7.2. |

No other transitions exist. In particular there is no
`RETIRED → *` — retirement is a one-way door, matching
`MigrationState.java:23-28`'s "Terminal — retirement callbacks fire
and further mutation is disallowed."

### 1.3 Double-transition attempts

Every transition above is a single CAS (or, for the deferred-retire
case, a single CAS against a companion flag). A loser of a CAS race
**must not** touch shared state — registry, chunk holder, packet
queue — as a side effect of losing:

- Two source-thread callers racing `beginMigration()` on the same
  ref (e.g. a plugin-issued `/tp` racing a Vanilla movement-triggered
  hop in the same tick): exactly one wins the `RESIDENT → MIGRATING`
  CAS. The loser's `beginMigration()` returns `false`
  (`EntityMigrationCoordinator.java:56-61`, `entry == null` /
  CAS-failure early-return path at `:60-70`) and the caller **must**
  treat this as "already migrating, no-op" — never retry in the same
  tick, never throw (CLAUDE.md ground rule 5: auto-reroute + warn,
  never throw from a mod's code path).
- `completeMigration()` invoked twice for the same ref (duplicate
  delivery is not supposed to happen — `RegionizedTaskQueue.drain`
  is single-delivery per enqueue, `RegionizedTaskQueue.java:148-165`
  — but a defensive contract is still required because a bug
  upstream must fail safe, not corrupt location). Second call's CAS
  `MIGRATING → RESIDENT` fails (state is already `RESIDENT`). The
  location write must be **conditioned on CAS success** so a stale
  duplicate cannot silently move an already-resident entity backward.
  **Gap:** current code at `MigratingEntityRef.java:73-78` calls
  `location.set(...)` unconditionally before the CAS, so a duplicate
  or out-of-order delivery *would* clobber location even though the
  CAS then fails. A1.3 must swap the order: CAS first, publish
  location only on success, else no-op (log a rate-limited warning —
  this indicates an upstream at-least-once delivery bug, worth
  surfacing to `ViolationLogger` even though it isn't fatal to
  correctness once fixed).
- `retire()` called twice (double-kill race, e.g. explosion damage
  landing in the same tick from two different cause chains that both
  resolve to death): the second call is a no-op. `MigrationState` is
  a plain `set()` in the current scaffold
  (`MigratingEntityRef.java:89-91`), which is trivially idempotent for
  the terminal state itself, but §1.4 below changes `retire()`'s body
  to a CAS-based dispatch — that CAS-based version must remain
  idempotent too: a second `retire()` call when already `RETIRED`
  is simply a failed CAS, silently ignored.
- `abortMigration()` called when the ref is not currently `MIGRATING`
  (e.g. the destination already completed it, or the ref was retired
  mid-flight by the deferred path): CAS `MIGRATING → RESIDENT` fails
  silently (`MigratingEntityRef.java:84-86`); this is intentional and
  requires no caller-side branching — the caller does not need to
  know which of "already completed" or "already retired" occurred.

### 1.4 Gap: `retire()` must become CAS-based with a deferred-retire path

`MigrationState.java:16-21`'s own javadoc already specifies the
target behavior for the `MIGRATING` state: *"No worker may mutate; a
mid-flight retirement is deferred until the destination discovers
it."* The current `retire()` implementation
(`MigratingEntityRef.java:88-91`, `state.set(MigrationState.RETIRED)`)
does not honor this — it slams straight to `RETIRED` regardless of
the ref's current state, including from `MIGRATING`. That breaks the
destination's `completeMigration()` CAS silently (CAS `MIGRATING →
RESIDENT` fails because state is now `RETIRED`, which is handled — see
the comment at `MigratingEntityRef.java:75-77` — but the fresh ref the
destination creates in `EntityMigrationCoordinator.completeRecursive`
(`EntityMigrationCoordinator.java:93-101`) is a **brand-new object**
that starts life at `RESIDENT` regardless of what happened to the old
ref. The retirement is lost — the destination materializes and
publishes a "resident" entity that should never have existed.

**A1.3 must implement:**

```java
private final AtomicBoolean pendingRetire = new AtomicBoolean(false);

public void retire() {
    MigrationState prev = state.get();
    if (prev == MigrationState.MIGRATING) {
        // Defer: do not overwrite MIGRATING. The destination's
        // completeAt() consults pendingRetire and, if set, retires
        // the freshly-materialized ref instead of publishing it.
        pendingRetire.set(true);
        return;
    }
    state.compareAndSet(prev, MigrationState.RETIRED); // no-op if already RETIRED
}

public boolean consumePendingRetire() {
    return pendingRetire.getAndSet(false);
}
```

And `EntitySnapshot` (§6) must carry a reference (or the coordinator
must re-`registry.get(uuid)`) back to the **source** ref at
`completeAt` time so `completeRecursive` can check
`consumePendingRetire()` before publishing the fresh ref as
`RESIDENT`. If set, the fresh ref is registered directly into
`EntityRegistry.retiredRefs` (§5) instead of the live map — the
entity never ticks anywhere, source or destination, after a mid-flight
kill. This is the mechanism that satisfies plan §"Correctness-critical
entity-migration semantics" item 1: *"No dual-writer window."*

### 1.5 ASCII state diagram

```
                    beginMigration()
                    [source worker]
        ┌──────────────────────────────────┐
        │                                   ▼
   ┌─────────┐                        ┌───────────┐
   │ RESIDENT│◄───────────────────────│ MIGRATING │
   └────┬────┘   abortMigration()     └─────┬─────┘
        │        [source worker,             │
        │         destination refused/       │ completeMigration()
        │         timed out]                 │ [destination worker]
        │                                     ▼
        │                              ┌───────────┐
        │       retire()               │ RESIDENT  │  (new instance,
        │  [any thread, RESIDENT ──────┤  (fresh)  │   same UUID)
        │       state only]            └───────────┘
        ▼
   ┌─────────┐        retire() while MIGRATING
   │ RETIRED │◄────── sets pendingRetire; completeAt()
   └─────────┘        consumes it and retires the FRESH
                       ref instead of publishing RESIDENT
```

---

## 2. Passenger-tree atomicity

### 2.1 Contract

A vehicle and everything riding it — recursively, including riders of
riders (boat → minecart → player) — migrates as a single atomic unit,
or none of it moves. Plan §"Correctness-critical" item 3: *"a boat
with 4 players never migrates 3 of them."*

The algorithm has two passes, both required, run in this order:

1. **DFS collect.** Starting from the root (the entity whose position
   change triggered the migration — typically the vehicle, since
   Vanilla moves the vehicle and lets passengers follow), walk
   `Entity.getPassengers()` depth-first, collecting every
   `MigratingEntityRef` in the tree — parent before children,
   children in `getPassengers()` order (order matters: Vanilla
   restores mount order on reinflate and a shuffled order is an
   observable, if minor, correctness bug for stacked mounts).
2. **Single-pass CAS.** Walk the collected list *once*, calling
   `beginMigration()` on each ref in DFS order. The first CAS failure
   aborts the **entire** attempt: every ref already flipped to
   `MIGRATING` earlier in this same pass is immediately rolled back
   via `abortMigration()`, in any order (they're independent CASes,
   no cross-ref ordering constraint on rollback).

This two-pass shape (collect fully, then CAS) rather than
CAS-while-walking is deliberate: it lets the abort loop iterate the
same fully-materialized list rather than trying to reconstruct "which
prefix of the tree did we already flip" during a partial DFS. The
existing scaffold already has this shape in
`EntityMigrationCoordinator.beginMigration`
(`EntityMigrationCoordinator.java:63-71`) and
`beginMigrationWithTree` (`:115-129`) — both collect into a `List`
first, then CAS-loop with an abort-all-on-failure branch. That pattern
is correct and must be preserved verbatim; A2's Vanilla patch only
needs to supply a *real* passenger walk.

### 2.2 Gap: `collectPassengerTree` is a stub

`EntityMigrationCoordinator.collectPassengerTree`
(`EntityMigrationCoordinator.java:160-166`) currently returns
`List.of(root)` — no passenger walk at all. The comment on that method
is explicit that this is intentional for the pure-Java scaffold: *"The
M4 patch overrides via `beginMigrationWithTree` with an actual
passenger walk."* A1.3's job is exactly this: implement
`MigratingEntityRef.beginPassengerTreeSnapshot()` — a DFS that, given
the entity's live `getPassengers()` graph (supplied by the A2 Vanilla
patch as a `List<EntityMigrationCoordinator.PassengerSpec>`, matching
the shape `beginMigrationWithTree` already accepts at
`EntityMigrationCoordinator.java:115-136`), returns the fully-flattened
ref list in DFS order and performs the single-pass CAS described
above. `beginMigration()` (the no-tree convenience overload) becomes a
thin wrapper that calls this with an empty passenger list once A1.3
lands — `collectPassengerTree`'s stub-list body disappears.

### 2.3 Snapshot capture happens *after* the CAS pass, not during

Order matters for a second reason beyond rollback simplicity: a
passenger could, in principle, be concurrently retired by another
thread between "list collected" and "CAS attempted" (e.g. a
plugin command kills a passenger mid-teleport). Because `retire()`
transitions `RESIDENT → RETIRED` directly (only the `MIGRATING` case
defers, per §1.4), a passenger that dies in this window simply loses
the `beginMigration()` CAS race (its state is no longer `RESIDENT`),
which correctly aborts the *whole* tree — a vehicle does not migrate
with a dead passenger silently dropped, it aborts and the caller
(A2.1/A2.2/A2.3's Vanilla hook) retries the whole `setPosRaw`/`onMove`
next tick against the now-current (smaller) passenger tree. This is
the "auto-reroute + warn" default in action: no exception, just a
deferred retry with fresh state.

### 2.4 Snapshot format for the tree

See §6 for the NBT shape. The structural point here: `EntitySnapshot`
is already a **recursive** record —
`passengers: List<EntitySnapshot>` (`EntitySnapshot.java:29-30`) — so
one `EntitySnapshot` for the vehicle carries its entire passenger
subtree. `EntityMigrationCoordinator.snapshotWithSpec`
(`EntityMigrationCoordinator.java:138-151`) already builds this
recursively from a `PassengerSpec` tree. `completeRecursive`
(`:93-101`) reinflates it recursively, re-adding each entity to the
destination's `EntityRegistry` and re-publishing its location,
finishing the parent before its own recursive call reinflates
children — the parent vehicle exists in the destination registry
before any rider is materialized, matching Vanilla's own
mount-then-passenger ordering expectations on load.

### 2.5 Invariant

**No `MigratingEntityRef` in a passenger tree is ever observed at
`MIGRATING` state without every other ref in that same tree also
being at `MIGRATING` or terminal (`RETIRED`, if independently killed
mid-flight per §2.3).** Equivalently: at any instant, for any tree,
either all live members are `RESIDENT` (not migrating), or the entire
attempted subset that survived the CAS pass is `MIGRATING`, or the
attempt has fully unwound back to `RESIDENT`. A1.3's stress test
(A2.10) must assert this directly by sampling `migrationState()` on
every ref in a 5-deep mount stack from a concurrent observer thread
during a stampede of migrations and asserting no snapshot shows a
mixed `{RESIDENT, MIGRATING}` state within one tree.

---

## 3. Border-mid-tick rule

### 3.1 The race

Vanilla's `ChunkHolder` reaches `FullChunkStatus.BORDER`
(MultiForge's `ChunkLoadLevel.BORDER`, distance 33 —
`ChunkLoadLevel.java:19,52`) before it reaches `TICKING` or
`ENTITY_TICKING`. Entities must not be inserted into a chunk's entity
list before the chunk itself is at least `BORDER` — inserting earlier
means the entity's first tick could run before block state, light,
and neighbor data are in a consistent state for that position (plan
§"Correctness-critical" item 4: *"inserting before BORDER means the
entity ticks before its chunk is fully loaded"*).

Because `queueChunkTask` delivers to *whichever region currently owns
the destination chunk, at drain time* (`RegionizedTaskQueue.java:16-22`
docblock), there's no guarantee the destination region has even
created a `NewChunkHolder` for that position yet, let alone promoted
it to `BORDER`, by the time the migration's completion task is
sitting in that region's inbox.

### 3.2 Contract

`EntityMigrationCoordinator.completeAt` must not run
`completeRecursive` (i.e. must not touch the destination
`EntityRegistry` or publish any ref to `RESIDENT`) until
`ChunkHolderManager.holderAt(destPos).level().isAtLeast(ChunkLoadLevel.BORDER)`
is true. If the holder is below `BORDER`, the completion is deferred
via a **new** method:

```java
// ChunkHolderManager.java — A1.4 addition
public void scheduleWhenHolderAt(ChunkPos pos, ChunkLoadLevel minLevel, Runnable task)
```

**Non-blocking requirement (CLAUDE.md ground rule 4):** this method
must never call `.get()`/`.join()` on any future, and must never spin.
`NewChunkHolder` already exposes exactly the primitive this needs: a
`CompletableFuture<Object>` that completes at `BORDER` —
`fullChunkFuture` (`NewChunkHolder.java:117-124`, accessor
`getFullChunkFuture()` at `:351-354`). `scheduleWhenHolderAt` for
`minLevel == BORDER` is a thin wrapper:

```java
public void scheduleWhenHolderAt(ChunkPos pos, ChunkLoadLevel minLevel, Runnable task) {
    NewChunkHolder holder = createHolder(pos, /* owner resolved at call time */ ownerAt(pos));
    if (holder.level().isAtLeast(minLevel)) {
        task.run(); // already there — run inline on the calling (destination) worker
        return;
    }
    holder.getFullChunkFuture().thenRun(() ->
        taskQueue.queueChunkTask(world, pos, task)); // re-enter through the queue, not run on the future's own completion thread
}
```

The `thenRun` callback must **re-enqueue** through
`RegionizedTaskQueue.queueChunkTask` rather than run `task` directly
on whatever thread completes `fullChunkFuture` — that completion is
published by "the owning region worker" per the future's own javadoc
(`NewChunkHolder.java:119-122`), which *should* be the same region by
the time `BORDER` is reached, but re-entering through the queue costs
nothing and removes any doubt about which thread ultimately runs
`completeRecursive`. This also naturally absorbs the case where the
chunk's owning region changed (merge/split) between holder creation
and `BORDER` completion — the queue re-resolves ownership at drain
time.

`EntityMigrationCoordinator.completeAt` becomes:

```java
public void completeAt(EntitySnapshot snapshot) {
    chunkHolderManager.scheduleWhenHolderAt(
        snapshot.destChunk(), ChunkLoadLevel.BORDER, () -> completeRecursive(snapshot));
}
```

### 3.3 Timeout / never-reaches-BORDER

A destination chunk might never reach `BORDER` — e.g. the destination
position is outside any loaded region and no ticket keeps it loaded
(a `/tp` to an unloaded, unforced position far outside simulation
distance). `EntityMigrationCoordinator` must hold an implicit
load-request ticket for the destination chunk for the duration of the
pending completion — practically, `beginMigration`/`beginMigrationWithTree`
add a short-lived `TicketType.PLUGIN`-class ticket (§ see
`docs/design/global-region.md` for the exact `TicketType`; this
document only requires *some* ticket exists so the chunk is not
permanently starved) at `destPos`'s chunk before calling
`queueChunkTask`, and release it once `completeRecursive` runs (success
or the fallback below). Without this, a migration into a cold region
could wait forever with no external force pulling the chunk to
`BORDER`.

Even with a load ticket, `scheduleWhenHolderAt` must have a bounded
wait: if `BORDER` is not reached within a configurable timeout (default
600 ticks / 30s — generous, since region startup after a
long-unloaded position can be slow, but bounded), the coordinator
**must not** leave the entity in `MIGRATING` limbo forever. See §7.3
for the fallback (retry-at-source vs. drop-to-a-safe-fallback-position).
A2.10's adversarial rapid-load-unload fixture exists specifically to
exercise the case where the destination chunk flickers across
`BORDER` repeatedly before settling — `scheduleWhenHolderAt`'s
one-shot `thenRun` must not fire on a *transient* `BORDER` that then
immediately demotes back below it before `completeRecursive` runs; the
re-entry through `queueChunkTask` (rather than running inline on the
future-completion thread) means `completeRecursive` runs on the
region's own tick thread at the next drain, which re-checks nothing —
this is a real gap: **A1.4 must re-check `level().isAtLeast(BORDER)`
inside `completeRecursive` itself** (or immediately before invoking
it, on the region thread) and, if it has since demoted, re-arm
`scheduleWhenHolderAt` rather than materializing into a
sub-`BORDER` chunk.

---

## 4. Lost-UUID-ref prevention

### 4.1 The problem

A task in flight — a scheduled effect, a delayed packet, a scoreboard
callback — can hold a bare `UUID` and resolve it against
`EntityRegistry` well after the entity has migrated away, been
retired, or (worse, from the perspective of a naive `null`-returning
lookup) had its UUID slot reused by an unrelated new entity spawned in
the interim. Plan §"Correctness-critical" item 2: *"a stale-UUID task
resolves to `state == RETIRED` instead of `null` — task no-ops
deterministically instead of NPE-ing or resurrecting the entity in the
wrong region."*

### 4.2 Contract

`EntityRegistry` gains a second map, `retiredRefs`, populated by
`retire()`'s CAS-success path (§1.4) and by the `MIGRATING`-deferred
path once the destination consumes `pendingRetire`. **No Caffeine or
any other new dependency** — per the plan's explicit user answer and
CLAUDE.md's "no new external dep without a license-compat + binary-size
review" gate — this is `ConcurrentHashMap` + a single
`ScheduledExecutorService`:

```java
// EntityRegistry.java — A1.2 addition
private final ConcurrentMap<UUID, RetiredEntry> retiredRefs = new ConcurrentHashMap<>();
private final ScheduledExecutorService retiredSweeper =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "multiforge-entity-retired-sweep");
            t.setDaemon(true);
            return t;
        });

private record RetiredEntry(MigratingEntityRef ref, long retiredAtTick) {}

public void retire(MigratingEntityRef ref, long nowTick) {
    byUuid.remove(ref.uuid());
    retiredRefs.put(ref.uuid(), new RetiredEntry(ref, nowTick));
}
```

`retiredSweeper` runs a periodic sweep (**not** a per-entry scheduled
task — one scheduled task per retirement would be an unbounded number
of live `ScheduledFuture`s under a stampede; a single fixed-rate sweep
every N ticks scanning `retiredRefs` and evicting anything older than
200 ticks is O(retired count) per sweep and bounded in scheduler
overhead regardless of retirement rate):

```java
retiredSweeper.scheduleAtFixedRate(this::sweepRetired, 0, SWEEP_PERIOD_MS, TimeUnit.MILLISECONDS);

private void sweepRetired() {
    long cutoff = currentTick.get() - RETIRED_TTL_TICKS; // 200
    retiredRefs.entrySet().removeIf(e -> e.getValue().retiredAtTick() <= cutoff);
}
```

`currentTick` is an `AtomicLong` the owning `MultiThreadedSchedulerHost`
(or a designated global-tick source) increments once per server tick —
`EntityRegistry` does not run its own clock; TTL is expressed in game
ticks, not wall time, so a debugger-paused server does not silently
evict everything the moment it resumes. (Wall-clock `TimeUnit`
governs only how often the *sweep itself* runs, which is a pure
housekeeping cadence — e.g. every 1000ms is plenty for a 200-tick /
10s TTL — and is independent of tick-based expiry correctness.)

### 4.3 Lookup contract

```java
// EntityRegistry.java — lookup() replaces bare get() for cross-region callers
public MigratingEntityRef lookup(UUID uuid) {
    Entry live = byUuid.get(uuid);
    if (live != null) return live.ref();
    RetiredEntry retired = retiredRefs.get(uuid);
    if (retired != null) return retired.ref(); // ref.migrationState() == RETIRED
    return null; // truly unknown UUID — never existed, or evicted past TTL
}
```

Callers that only need "does this UUID currently resolve to something
live" keep using `get(uuid)` (unchanged, returns `null` for both
retired and unknown). Callers holding a UUID across a tick boundary —
delayed effects, scoreboard objectives, any cross-region task queued
via `RegionizedTaskQueue` that captured a UUID rather than a live
`MigratingEntityRef` reference — **must** use `lookup(uuid)` and branch
on `ref.migrationState() == MigrationState.RETIRED` to no-op, rather
than on `ref == null`. A `null` from `lookup()` after the 200-tick
window has elapsed is still possible (nothing keeps a UUID resolvable
forever) — a task holding a UUID from more than 200 ticks in the past
must already be prepared for `null`, same as today. The TTL's purpose
is narrower: it closes the window where a *fast-following* task (the
overwhelming majority — same tick or within a handful of ticks) would
otherwise race the retirement and see `null` nondeterministically
depending on exactly how the scheduler interleaved the retirement and
the lookup.

### 4.4 Why 200 ticks

200 ticks = 10 seconds at 20 TPS. This is deliberately generous
relative to typical cross-region task latency (a `RegionizedTaskQueue`
hop plus a `BORDER`-wait per §3 is expected to resolve within single
digits of ticks in the common case) — the TTL is sized to absorb
worst-case scheduling jitter (a saturated region's inbox backing up
under MSPT pressure) rather than the expected case, so a
correctness-relevant lookup essentially never legitimately needs the
`null` fallback path in practice. 200 ticks is also short enough that
`retiredRefs` cannot grow unbounded under sustained churn (500-mob
stampede in A2.10 retires and re-resolves within a small multiple of
this window, not across a whole session).

---

## 5. Cross-dimension migration — 2-hop through the global region

### 5.1 Why not direct region-to-region

Cross-dimension migration (`Entity.changeDimension`) is structurally
different from an intra-dimension border crossing: it must acquire
ownership context in **two different `ThreadedRegionizer` instances**
(each dimension in MultiForge has its own regionizer — see
`MultiThreadedSchedulerHost`'s per-world `regionizers` map,
`MultiThreadedSchedulerHost.java:191` for the analogous global-world
registration). A naive direct source-region → destination-region hand-off
would require the source region's worker (or the coordinator acting on
its behalf) to reason about, and potentially lock against, a region in
an entirely different regionizer's tree at the same time it holds
whatever state it's using to answer "is my region's chunk still mine."
Two independent lock hierarchies acquired in caller-determined order
is exactly the shape of a deadlock (region A's worker waits on
dimension 2's regionizer while dimension 2's worker, migrating a
different entity back, waits on dimension 1's regionizer).

### 5.2 Contract: source → global → destination

`changeDimension` splits into two independent `queueChunkTask` hops,
both already expressible with the existing primitives:

```java
// A2.4 patch — Entity.changeDimension(DimensionTransition), conceptually
void changeDimension(DimensionTransition transition) {
    if (!ref.beginMigration()) return; // reroute + warn per §1.3

    EntitySnapshot snapshot = /* capture, same shape as §6 */;
    taskQueue.queueChunkTask(globalWorld, 0, 0, () ->
        globalDimensionHop(snapshot, transition.newDimension(), transition.pos()));
}

// Runs on the global region's single dedicated thread.
void globalDimensionHop(EntitySnapshot snapshot, WorldRef destWorld, BlockPos destPos) {
    // The global region is the one place in the system that may
    // "observe both dimensions simultaneously" — it does no direct
    // mutation of either region's chunk map itself, but it is the
    // single-threaded rendezvous point both hops route through, so
    // there is exactly one thread deciding the hand-off order, never
    // two regions negotiating with each other directly.
    ChunkPos destChunk = destPos.toChunkPos();
    taskQueue.queueChunkTask(destWorld, destChunk.x(), destChunk.z(), () ->
        coordinator.completeAt(snapshot.withDest(destWorld, destPos)));
}
```

This mirrors `PlayerJoinCoordinator.onPlayerLoginCompleted`
(`PlayerJoinCoordinator.java:54-63`), which already implements exactly
this 2-hop shape for the Netty-thread → global → spawn-region case:
step 1 enqueues onto `globalWorld` at the fixed `(0,0)` chunk (the
synthetic global region's sole chunk — `MultiThreadedSchedulerHost.java:192`,
`globalRegion = globalRegionizer.addChunk(new ChunkPos(0, 0))`), step 2
(`globalRegionAssign`, `PlayerJoinCoordinator.java:65-75`) runs on the
global region's own worker and re-enqueues onward to the destination.
`changeDimension`'s 2-hop is the same pattern generalized from "Netty
thread → global → region" to "any region → global → any region (in a
different dimension)."

### 5.3 Why "the global region observes both dims simultaneously" resolves the deadlock

The global region is a **synthetic region pinned to its own dedicated
single-thread executor** (`MultiThreadedSchedulerHost.java:57,132-134`
docblock: *"the 'global region' in Folia terminology"*). It is not a
member of either dimension's regionizer tree in the sense of owning
chunks in them — it has its own regionizer over a synthetic
`multiforge:global` world (`MultiThreadedSchedulerHost.java:174-176`).
Because exactly one thread ever executes global-region tasks, there is
no possibility of two threads each holding one dimension's lock and
waiting on the other's: the global hop is a strict sequence point.
`globalDimensionHop` above does not hold any regionizer lock across
its own `queueChunkTask` call into the destination dimension — it only
*enqueues*, which is itself lock-scoped internally by
`RegionizedTaskQueue.queueChunkTask`'s own resolve-then-enqueue pair
(`RegionizedTaskQueue.java:121-136`) against the *destination
dimension's* regionizer alone. The source dimension's regionizer is
never touched by the global hop at all — the source region already
released everything it needed to release by the time
`beginMigration()`'s CAS committed and the snapshot was queued.

### 5.4 Passenger tree crosses dimensions atomically too

§2's tree-atomicity contract applies unchanged across
`changeDimension`: the whole ridden tree snapshots and CASes together
before the first hop, and the global-region relay carries the full
recursive `EntitySnapshot` (§6) through both hops as one unit. There is
no per-passenger fan-out into separate global-region tasks — one
`EntitySnapshot` (with its nested `passengers` list), one hop through
global, one hop into the destination dimension, one `completeRecursive`
call that reinflates the whole tree together on the destination
worker.

---

## 6. NBT snapshot format

### 6.1 Contract

`EntitySnapshot.payload` (currently `String`, a test stand-in —
`EntitySnapshot.java:29-36` docblock: *"the M4 patch binds this to
Vanilla's `CompoundTag`; pure-Java uses a String for tests"*) becomes
`net.minecraft.nbt.CompoundTag` in the patched runtime. Capture and
restore bind to Vanilla's own serialization entry points, not a
MultiForge-invented format — this is the same principle M9 used for
`WorldDiff.DiffMode.SEMANTIC` (reuse verbatim, don't reinvent NBT
shape):

```java
// Capture (A2.1/A2.3/A2.4 call sites, source region worker)
CompoundTag tag = new CompoundTag();
boolean saved = entity.save(tag); // Entity.save(CompoundTag) — Vanilla, unmodified
// saved == false means Vanilla itself declined to serialize (e.g. a
// player being saved through the wrong path, or an entity that opted
// out via `Entity.shouldBeSaved()`); coordinator treats false as an
// abort of this entity's migration — see §7.1.

// Restore (destination region worker, inside completeRecursive)
Entity fresh = EntityType.loadEntityRecursive(tag, destLevel, spawned -> {
    spawned.moveTo(destPos.x(), destPos.y(), destPos.z(), spawned.getYRot(), spawned.getXRot());
    return spawned;
}); // Vanilla helper — reconstructs concrete Entity subtype from `id` tag
```

`Entity.save` is Vanilla's own full-fidelity capture — inventory,
enchantments, potion effects, AI goal state that persists across save
(most transient AI state does not survive a normal chunk save/load
either, so migration's fidelity bar matches Vanilla's save/load bar,
not a stricter one), health, air, fire ticks, everything. Using it
means MultiForge's migration format has *zero* independent
maintenance burden as Vanilla's entity NBT schema evolves across
version bumps — it just re-runs `entity.save` again.

### 6.2 Passenger list in the snapshot

`EntitySnapshot.passengers: List<EntitySnapshot>`
(`EntitySnapshot.java:30`) is populated by walking
`Entity.getPassengers()` at capture time (§2.2's DFS) and calling
`entity.save(tag)` independently for the vehicle and for each
passenger — Vanilla's own `CompoundTag` format already has a
`"Passengers"` list tag when an entity is saved *with* its riders
attached (`Entity.save` → `saveWithoutId` → passenger serialization),
but MultiForge does **not** rely on that nested built-in format for
the cross-region hand-off, for one specific reason: Vanilla's
nested-passenger NBT assumes the whole tree saves and loads as one
unit on one thread, and does not give MultiForge a hook to apply the
per-ref CAS from §2 to each tree member independently before
committing to move any of them. Keeping `EntitySnapshot`'s own
recursive `List<EntitySnapshot>` — each element wrapping an
independently-captured, top-level-shaped `CompoundTag` (i.e. captured
via `entity.save(tag)` *without* Vanilla walking into its own
passengers, achieved by snapshotting each entity individually and
never following `entity.getPassengers()` from inside `save` itself) —
means the reinflate step (§6.3) controls mount order explicitly rather
than trusting Vanilla's own passenger-tag re-mounting, and each
member's success/failure is independently observable to the
coordinator.

### 6.3 Reinflate and re-mount order

`completeRecursive` (§2.4) reinflates parent-before-children. After
each `EntityType.loadEntityRecursive` call for a passenger, the
coordinator explicitly calls `parent.addPassenger(childEntity)` (or
the Vanilla equivalent mount call) rather than depending on any
mount-linkage embedded in the NBT — the DFS order captured in §2.1
*is* the re-mount order, no additional metadata needed.

### 6.4 Round-trip test contract (A1.1)

`EntitySnapshotTest.java` (new, A1.1) must assert:

- A `CompoundTag` captured via `entity.save` and restored via
  `EntityType.loadEntityRecursive` produces an entity whose own
  `entity.save` output is tag-equal to the original (byte-identical
  NBT — the same bar M9's `WorldDiff.DiffMode.SEMANTIC` regression
  uses for full-world saves, applied here to a single entity).
- A 3-deep passenger stack (boat → minecart → pig) round-trips with
  mount order preserved — re-saving the reinflated tree and comparing
  `getPassengers()` order at each level, not just NBT tag equality.
- `EntitySnapshot`'s own `passengers` list nesting survives a
  Java-serialization-free round trip through the `EntityMigrationCoordinator`
  API (`beginMigrationWithTree` → `completeAt`) using the pure-Java
  test payload path (`String` payload, pre-CompoundTag-migration form)
  as a structural smoke test independent of actual NBT — this is the
  test that already exists in shape (`EntityMigrationCoordinator`'s
  `PassengerSpec`-based tests) and continues to pass once A1.1 changes
  the payload type.

---

## 7. Failure / rollback semantics

### 7.1 Source-side capture failure

If `entity.save(tag)` returns `false` for the root or any passenger
(§6.1), the **entire** migration attempt aborts before any CAS is
attempted for that tree — capture happens after DFS-collect but before
the CAS pass in the ordering from §2.1 is adjusted slightly for this
case: a capture-failure check runs during collection, and if any tree
member fails to serialize, `beginMigrationWithTree` returns `false`
immediately with **no** ref in the tree having been CASed at all (not
even a CAS-then-rollback — the failure is caught before any CAS is
attempted, which is strictly safer and cheaper than CAS-then-unwind
for this particular failure mode, since it's detectable up front).
Per CLAUDE.md ground rule 5, this is a reroute-and-warn: the entity
stays exactly where it was, `RESIDENT`, and a rate-limited warning
goes to `ViolationLogger`/`ProbeRegistry` (A1.5) — never a thrown
exception into Vanilla's `setPosRaw`/`onMove` call site.

### 7.2 Mid-flight retirement (already covered in depth in §1.4)

Summary for this section's purposes: `retire()` during `MIGRATING`
defers via `pendingRetire` rather than clobbering state; the
destination's `completeAt`/`completeRecursive` consults it before
publishing `RESIDENT` and, if set, retires the fresh ref directly into
`EntityRegistry.retiredRefs` instead. No packet, no tick, no observer
ever sees the entity as `RESIDENT` anywhere after a mid-flight kill.

### 7.3 Destination refuses / times out (§3.3)

Two distinct failure shapes, both must reach the same rollback:

- **Destination chunk never reaches `BORDER`** within
  `scheduleWhenHolderAt`'s timeout (§3.3, default 600 ticks).
- **Destination region rejects the insert outright** — a future
  extension point (e.g. a full-server world-border check, or a mod
  hook that vetoes an incoming entity) that is out of scope for A1–A4
  but the rollback path must be shape-compatible with it.

In both cases, the fallback is **not** silently dropping the entity.
`EntityMigrationCoordinator` must retry the completion at the
*source*'s last-known-good location:

```java
// Fallback fires from the timeout path or an explicit destination-reject signal.
void abortAndRestore(EntitySnapshot snapshot, WorldRef fallbackWorld, BlockPos fallbackPos) {
    // Re-materialize at the fallback position using the same
    // completeRecursive machinery — the fallback IS a completeAt,
    // just targeting the source position instead of the originally
    // requested destination. This reuses §3's BORDER-wait guarantee
    // for the fallback position too (it should already be BORDER+,
    // since the entity was just resident there, but the region could
    // have unloaded in the interim under extreme churn).
    coordinator.completeAt(snapshot.withDest(fallbackWorld, fallbackPos));
}
```

This is why `abortMigration()` (§1.2) exists as a *source-side,
synchronous* rollback distinct from this *destination-timeout,
asynchronous* rollback: `abortMigration()` handles the case where the
source thread itself discovers a same-tick reason to cancel (a
passenger-tree CAS failure elsewhere in the tree, §2.1) before the
snapshot ever left the source's hands. `abortAndRestore` handles the
case where the snapshot already left — the source region has already
moved on, possibly ticked many times — so recovery must go through
the same queue-and-complete machinery as a forward migration, just
aimed back at (or near) the source.

**Ref identity across a rollback:** the entity that reappears at the
fallback position is, per §1.1, a **new** `MigratingEntityRef`
instance (same as any `completeAt` outcome) — not the original,
now-orphaned `MIGRATING`-state ref. The original ref's terminal state
after a successful rollback is a policy choice this document fixes:
**the original ref transitions to `RETIRED`** (via the same
CAS-based `retire()` from §1.4, since by definition it is not
currently `MIGRATING` from the fallback-completion's perspective — the
fallback's `completeAt` is itself a normal completion, so by the time
it runs, retiring the *original* stale ref is just ordinary cleanup,
symmetric to §1.4's handling of any other superseded ref) and is
registered into `retiredRefs` (§4) so any task still holding the
original ref's UUID resolves deterministically to `RETIRED` rather
than finding a "resident but wrong" ghost.

### 7.4 Never partial, never silent

Across every failure mode in this section, two invariants hold
without exception:

1. **Never partial.** No migration attempt ever leaves a subset of a
   passenger tree at the destination and a subset at the source. This
   falls directly out of §2's single-pass CAS + full-rollback design —
   there is no code path that commits part of a tree.
2. **Never silent.** Every abort/rollback/timeout path routes through
   `ProbeRegistry.recordMigration` (A1.5) with an outcome tag
   (`SUCCESS` / `ABORTED_CAS` / `ABORTED_CAPTURE` / `TIMED_OUT` /
   `RETIRED_MIDFLIGHT`) and, for anything other than `SUCCESS`, a
   rate-limited `ViolationLogger` warning. Operators must be able to
   see migration failure rates in the debug HUD (M6, out of this
   document's scope) without needing to reproduce the failure by hand.

---

## 8. Test invariants

These are the properties Track A's tests (A1.1–A1.5, A2.10, A3.4, and
Phase X's X.1) **must** assert, not merely exercise. Each maps back to
a section above.

| # | Invariant | Proves | Test(s) |
|---|---|---|---|
| T1 | `entity.save` → `EntityType.loadEntityRecursive` round-trips to tag-identical NBT for a single entity. | §6.1, §6.4 | `EntitySnapshotTest` (A1.1) |
| T2 | A 3+-deep passenger stack round-trips with mount order preserved. | §2.4, §6.3 | `EntitySnapshotTest` (A1.1) |
| T3 | A stale UUID looked up via `lookup()` within the 200-tick window after retirement returns a ref with `migrationState() == RETIRED`, never `null`. | §4.3 | `EntityRegistry` unit test (A1.2) |
| T4 | The same UUID looked up via `lookup()` after the 200-tick window (simulated tick advance) returns `null`. | §4.4 | `EntityRegistry` unit test (A1.2) |
| T5 | Under concurrent retire+sweep, `retiredRefs` never grows unbounded across a sustained churn workload (bounded by TTL × churn rate, not by total lifetime retirements). | §4.2 | `EntityRegistry` stress test (A1.2) |
| T6 | For any passenger tree, a losing CAS anywhere in the single-pass loop results in **every** ref in that tree observably back at `RESIDENT` (or its pre-attempt state) before `beginMigration`/`beginMigrationWithTree` returns `false` — no external observer can catch an intermediate mixed state. | §2.5 | `MigratingEntityRef`/`EntityMigrationCoordinator` unit test (A1.3) |
| T7 | A concurrent observer thread sampling `migrationState()` across all refs in a 5-deep mount stack during a stampede of migrations never observes a tree with a mixed `{RESIDENT, MIGRATING}` state. | §2.5 | `EntityMigrationStressTest` (A2.10) |
| T8 | `completeAt` never runs `completeRecursive` while `holderAt(destPos).level()` is below `BORDER`; an adversarial fixture that repeatedly promotes/demotes the destination holder across `BORDER` never observes a materialize during a sub-`BORDER` window. | §3.2, §3.3 | `EntityMigrationStressTest` (A2.10), `ChunkHolderManager` unit test (A1.4) |
| T9 | A migration whose destination never reaches `BORDER` within the timeout resolves via `abortAndRestore`, and the original ref ends at `RETIRED` while the fallback ref is `RESIDENT` at the fallback position — never a permanently `MIGRATING`-stuck ref. | §3.3, §7.3 | `EntityMigrationStressTest` (A2.10) |
| T10 | An entity `retire()`d while `MIGRATING` never becomes observable as `RESIDENT` anywhere (source or destination) — the fresh destination ref lands directly in `retiredRefs`. | §1.4, §7.2 | `MigratingEntityRef` unit test (A1.3) |
| T11 | 500-mob cross-region stampede: `EntityRegistry.size()` (live map) plus `retiredRefs` size accounts for every mob at every sampled instant — no entity vanishes from both maps simultaneously (the "lost entity" bug class). | §4, §7.4 | `EntityMigrationStressTest` (A2.10) |
| T12 | `changeDimension` never deadlocks under concurrent cross-dimension churn in both directions (dimension A→B and B→A migrations racing through the shared global-region hop simultaneously) — bounded-time completion, no thread ever blocks on another dimension's regionizer lock. | §5.3 | `EntityMigrationStressTest` (A2.10) or a dedicated cross-dim stress test |
| T13 | Cross-dimension passenger tree (players riding a boat through a nether portal) migrates as one unit through both global-region hops — no partial tree observed at the global relay point either. | §5.4, §2.5 | Cross-dim stress test (A2.10 or A3.4) |
| T14 | Rapid `/tp` between regions during heavy chat + inventory packet traffic: zero packet drop, zero client desync (packets destined to a `MIGRATING` player queue rather than send-and-fail). | A3's `pendingOutbound` contract (out of this doc's scope, frozen by A3.2) | `NetworkingMigrationTest` (A3.4) |
| T15 | 100 rapid `/tp` across 4 regions, semantic NBT parity vs. an unpatched-fork baseline (reuse `WorldDiff.DiffMode.SEMANTIC` from M9) — migration changes position/dimension and nothing else about entity state. | §6.1 (Vanilla `entity.save` fidelity) | Phase X.1 bench |
| T16 | No `RegionTickOverrunException` and no `OwnershipEnforcer REROUTE` hit attributable to entity migration during a 30-minute strict-mode headless swarm. | Ground rules 4–5 applied end-to-end | A4.2 bench run |

---

## 9. Summary of gaps this document requires A1–A4 to close

For quick cross-reference against the plan's task table
(`plans/bubbly-jumping-comet.md`, Track A):

| Gap | Section | Owning task |
|---|---|---|
| `EntitySnapshot.payload` is `String`, needs `CompoundTag` + real passenger capture via `entity.save` | §6 | A1.1 |
| `EntityRegistry` has no `retiredRefs` / TTL sweep / `lookup()` | §4 | A1.2 |
| `collectPassengerTree` is a `List.of(root)` stub — no real DFS | §2.2 | A1.3 |
| `retire()` unconditionally overwrites state instead of CAS + deferred-`MIGRATING` path | §1.4, §7.2 | A1.3 |
| `completeMigration()` publishes `location` before checking the CAS result | §1.3 | A1.3 |
| `ChunkHolderManager.scheduleWhenHolderAt` does not exist | §3.2 | A1.4 |
| `EntityMigrationCoordinator.completeAt` does not gate on `BORDER` at all today | §3.2 | A1.4 |
| No timeout / `abortAndRestore` fallback path | §3.3, §7.3 | A1.4 |
| No `ProbeRegistry.recordMigration` hook, no violation emit on abort | §7.4 | A1.5 |
| Vanilla hop sites (`setPosRaw`, `onMove`, `teleportTo`, `changeDimension`) are unpatched — nothing in this document is invoked yet | §5, plan context | A2.1–A2.4 |
| No 2-hop `changeDimension` implementation | §5.2 | A2.4 |

Every other shape described in this document — the coordinator's
two-phase structure, the CAS-based state machine's happy path, the
recursive `EntitySnapshot`, the `PlayerJoinCoordinator` 2-hop pattern
this document generalizes for cross-dimension — already matches the
existing scaffold and should be preserved, not rewritten (plan
§"Reusable pieces").
