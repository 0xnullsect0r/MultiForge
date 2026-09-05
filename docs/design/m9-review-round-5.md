# M9 Review — /67 Round 5

**Scope.** Every commit from `054f8f9` (Phase 0 design freeze) through `HEAD`
after the sibling verification-runbook commit (`9a6dde7`). 41 commits cover
Phase 1 blocker fixes, Phase 2 NewChunkHolder extension, Phase 3 MCA I/O,
Phase 4 Vanilla-surface fork facades + delegation-observer hunks, Phase 5
tick-body wiring + real ticket writes, Phase 6 downstream audits, and
Phase 7.1 semantic NBT diff.

**Reviewer.** M9 landing solo reviewer (round 5), post-implementation. This is
not the pre-implementation `/67` fan-out — round 4 covered that. Round 5 is a
correctness + hygiene sweep against the frozen contract in
`docs/design/m9-contracts.md` and the CLAUDE.md ground rules.

**Verification against HEAD.**
- `./gradlew :multiforge-runtime:build :multiforge-runtime:spotlessCheck` — green.
- `./gradlew :multiforge-runtime:publishToMavenLocal` — green.
- `:neoforge:compileJava` — green after the round-5 fixes below.
- Every finding in this doc was cross-checked against the actual current file,
  not just the commit that introduced it.

---

## 1. Summary

| Severity | Count | Disposition |
|----------|-------|-------------|
| CRITICAL | 0     | —           |
| HIGH     | 6     | 2 fixed this commit; 4 documented (require design-scope work or Phase 4/5 wire-in) |
| MEDIUM   | 7     | Documented; feed the round-6 backlog |
| LOW      | 6     | Documented; nits and stale-comment cleanups |

**Applied in this commit:**

- **H1 — `mfShadow` API surface tightening** (patch + upstream tree).
- **H3 — misplaced `ChunkGenerationTask.java.patch` moved to match its header.**

**Not applied in this commit** (each >30 LOC or requires design-scope change;
noted for round-6):

- H2 (facade internal-type leak on `MultiForgeChunkMap` public methods) —
  needs annotation sweep + one static-import fix; low-risk but multi-site.
- H4 (`ChunkHolderManager.addTicket/removeTicket` read-lock fix) —
  requires wiring the regionizer read lock through `ChunkHolderManager`;
  30–60 LOC + a race regression test. Latent today because callers are
  single-threaded main-thread only; lights up on Phase 4/5 wire-in.
- H5 (`RegionizedChunkLifecycle` add-ticket read-lock) — same shape as H4.
- H6 (`MultiForgeDistanceManager.INSTANCE_REGISTRY` iteration under
  synchronized `WeakHashMap`) — spot-instrumentation only under
  hypothetical future usage; documented so a round-6 fix reads the
  full context.

---

## 2. CRITICAL findings

None.

The two categorical bug classes the Phase-1 batches were meant to eliminate
(FOLDING quiescence + queueChunkTask merge race) both landed correct code:

- `fb8e99e` puts a bounded spin on `mergeInto` targeting `READY→FOLDING` before
  firing merge listeners; `tryMarkTicking` refuses `FOLDING`.
  `RegionizedDataTest.mergeSpinWaitsForSurvivingRegionToStopTicking` proves
  the wait actually blocks under a pinned worker.
- `daf3d3e` converts `ThreadedRegionizer.writeLock` to a `ReentrantReadWriteLock`
  and threads its read side through `RegionizedTaskQueue.queueChunkTask`.
  `RegionizedTaskQueueTest.queueChunkTaskUnderConcurrentMergeDoesNotLoseTask`
  drives the race deterministically.

Both fixes are load-bearing and correct.

---

## 3. HIGH findings

### H1 — Internal type leaked as public field on Vanilla `ChunkHolder` (FIXED)

**Site.** `multiforge-patches/04-chunk-system/net/minecraft/server/level/ChunkHolder.java.patch:22`
adds `public volatile net.multiforge.runtime.chunk.NewChunkHolder mfShadow;`.

**Problem.** `NewChunkHolder` lives in `net.multiforge.runtime.chunk` and is
marked `@ApiStatus.Internal` in every neighbouring runtime class. CLAUDE.md §Code
style: "`@ApiStatus.Internal` on every non-API type until we ship v1.0."
Exposing it as a `public` field on a Vanilla-facing class that every mod can
extend or read via reflection bakes an internal type into the mod-facing
contract for the entire lifetime of v0.9.0. Concretely: mods can now write
`chunkHolder.mfShadow.setLevel(...)` and MultiForge cannot rename or refactor
`NewChunkHolder` without a mod break.

Compounding: the only current field readers are `this.mfShadow` reads inside
`ChunkHolder` itself (5 sites, all same-class). No cross-package caller reads
the field; the shim uses its own `shadow` reference. So tightening visibility
is a safe cleanup.

**Fix applied.** Change `public volatile` → `volatile` (package-private). Updated
both the patch file and the applied upstream copy so the compile stays green.
Reworded the javadoc to name the round-5 rationale and point cross-package
callers at `MultiForgeChunkMap.getVisibleChunkIfPresent(long)` +
`ChunkHolderShim.shadowOrNull(ChunkHolder)`.

### H2 — Facade public methods leak internal-package types (NOT FIXED)

**Sites.**
- `upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/chunk/MultiForgeChunkMap.java:394`
  — `public @Nullable ChunkHolderManager holders()`.
- Same file, `:402` — `public @Nullable ChunkTaskScheduler tasks()`.
- Same file, `:413` — `public @Nullable RegionId regionIdFor(ChunkPos pos)`.
- Same file, `:1554` — `public ChunkHolderManager _testHolders()`.

`MultiForgeChunkMap` is `@ApiStatus.Internal` at the class level, so JB-aware
tooling flags callers. But class-level `@ApiStatus.Internal` does not stop a
mod that ignores JB annotations from reaching the methods. The concern is
categorically the same as H1: internal types on a Vanilla-adjacent public
surface.

**Deferred to round 6.** The facade is not yet instantiated
(`grep -r 'new MultiForgeChunkMap' upstream` returns nothing), so the leak is
latent today. Round-6 fix should either (a) annotate each method with
`@ApiStatus.Internal` for redundancy with the class-level annotation, (b)
make them package-private (Phase 4.1c will patch Vanilla `ChunkMap` to
instantiate the facade — the patch lives in the same package so package-private
is workable), or (c) return an opaque handle carrying the debug-string shape
today's `/multiforge chunks` needs. Preferred: (b) after Phase 4.1c lands.

### H3 — `ChunkGenerationTask.java.patch` filesystem location wrong (FIXED)

**Site.** Before this commit,
`multiforge-patches/04-chunk-system/net/minecraft/world/level/chunk/status/ChunkGenerationTask.java.patch`.
Patch header: `--- a/net/minecraft/server/level/ChunkGenerationTask.java`.

**Problem.** In NeoForge 1.21.1, `ChunkGenerationTask` lives at
`net.minecraft.server.level.ChunkGenerationTask` (the header path). But the
patch file on disk was under `net/minecraft/world/level/chunk/status/` — the
1.21.5 location the design plan appears to have been drafted against. This
mismatch:

- Breaks any patch-apply script that derives the target from the on-disk path
  (`git apply -p1 --directory=...` with a per-file loop). The current apply
  path uses the header, so it applied fine — but silent divergence between
  on-disk path and header is a maintenance foot-gun. `./gradlew :createPatches`
  rebuilds patches from the tree and writes them at the header path — a
  rebuild would create a second copy at the correct location and orphan the
  wrong-location file.
- Confuses the reader: the patch is filed under `world/level/chunk/status/`
  but touches a `server/level/` class.

**Fix applied.** `git mv` the patch to
`multiforge-patches/04-chunk-system/net/minecraft/server/level/ChunkGenerationTask.java.patch`.
Removed the now-empty `world/level/chunk/status/` sub-tree so `find` doesn't
list a phantom dir.

### H4 — `ChunkHolderManager.addTicket/removeTicket` don't hold the regionizer read lock (NOT FIXED)

**Site.** `multiforge-runtime/src/main/java/net/multiforge/runtime/chunk/ChunkHolderManager.java:81-117`.

**Problem.** The Phase 1.2 fix (`daf3d3e`) taught `RegionizedTaskQueue.queueChunkTask`
to acquire the regionizer read lock around its resolve-then-enqueue pair so a
concurrent merge/death cannot silently drop the task by clearing the dying
region's inbox. The same resolve-then-write pattern exists in every ticket
path:

```java
// MultiForgeDistanceManager.routeAddTicket (upstream/…/MultiForgeDistanceManager.java:298)
Region region = regionOrNull(pos);
if (region == null) { warn; return; }
// … no read lock …
holderManager.addTicket(region.id(), mfPos, translate(vanilla));
```

If a merge fires between `regionOrNull()` and `addTicket()`, the caller writes
into `ticketsFor(region.id())` — which is a fresh empty `PerRegionTicketMap`
inserted via `computeIfAbsent` on the ConcurrentHashMap for the DYING region's
key. `onRegionMerged` already ran `ticketsByRegion.remove(source)` — so the
ticket is orphaned: it lives in no map any regionizer walks. Same silent-loss
pattern as pre-1.2 `queueChunkTask`.

Same shape applies to `RegionizedChunkLifecycle.onChunkLoaded/onChunkUnloaded`
(H5 below).

**Latent today.** Every current writer of `ChunkHolderManager.addTicket` runs
on the main thread — `DistanceManagerBridge.onAddTicket` (invoked from Vanilla
`ChunkMap$DistanceManager.addTicket`, main-thread only), plus
`RegionizedChunkLifecycle.onChunkLoaded` (invoked from `ChunkEvent.Load`,
main-thread only under the current Vanilla dispatch). Merges also fire from
the main thread (chunk load/unload cascades). So the race window is closed by
single-threading, not by a lock. That single-threading dissolves the moment
Phase 4.2c wires `MultiForgeDistanceManager` as authoritative — every region
worker becomes a valid caller.

**Deferred to round 6.** The fix is symmetric to Phase 1.2: thread the
regionizer read lock through `ChunkHolderManager` via a
`Function<WorldRef, Lock>` accessor (or through the `WorldRef` in the
constructor, which already accepts one). The public entry points wrap the
`byChunk` + `ticketsFor` mutation in `readLock.lock()/unlock()`. Add a
regression test mirroring
`RegionizedTaskQueueTest.queueChunkTaskUnderConcurrentMergeDoesNotLoseTask`.
Estimated 30–60 LOC.

### H5 — `RegionizedChunkLifecycle.onChunkLoaded` writes ticket without read lock (NOT FIXED)

**Site.** `upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/RegionizedChunkLifecycle.java:84-105`.

Same class of race as H4: the sequence
```java
Region region = host.registerChunk(world, pos.x, pos.z);   // (1)
// … no read lock …
manager.addTicket(region.id(), mfPos, ticket);              // (2)
```
is not atomic w.r.t. the regionizer's write lock. `registerChunk`'s
`addChunk` acquires-and-releases the write lock; the returned `Region` is
correct at the instant of return, but by (2) a merger could have folded
`region` into a neighbour, and (2) writes to a `PerRegionTicketMap` keyed on
the DYING region id.

The onChunkUnloaded path has the same issue on the removeTicket side.

**Deferred to round 6** for the same reason as H4 — the fix is one call site
that acquires the read lock around the resolve+addTicket pair. Latent because
`ChunkEvent.Load` fires on the main thread today. Fix should land together
with H4 so the same read-lock plumbing serves both.

### H6 — `MultiForgeDistanceManager.INSTANCE_REGISTRY` synchronized-WeakHashMap iteration hazard (NOT FIXED)

**Site.** `upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/chunk/MultiForgeDistanceManager.java:130-131`.

```java
private static final java.util.Map<DistanceManager, MultiForgeDistanceManager>
    INSTANCE_REGISTRY = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
```

`Collections.synchronizedMap(WeakHashMap)` is correct for `.put`/`.get`/`.remove`
of individual entries — but any iterator over the entry set requires the
caller to hold the wrapper's monitor externally (`Collections.synchronizedMap`
javadoc). The current callers only invoke `.get(dm)` via `of(dm)`, so no
iterator escapes today. But the class exposes `of(DistanceManager)` as
public — a future feature that iterates all facades (e.g. `/multiforge
distancemanagers` command) could inherit the hazard without warning.

**Deferred to round 6.** Cheap fix: replace with a
`ConcurrentHashMap<DistanceManager, WeakReference<MultiForgeDistanceManager>>`
+ periodic sweep, or drop the weak wrapper if the manager count is bounded by
world count (single digits). Not urgent because no iterator exists today.

---

## 4. MEDIUM findings

### M1 — `PerRegionTicketMap` and `PerChunkTickets` use plain `HashMap`/`HashSet`

Files:
- `multiforge-runtime/src/main/java/net/multiforge/runtime/chunk/PerRegionTicketMap.java:25` — `private final Map<ChunkPos, PerChunkTickets> byChunk = new HashMap<>();`
- `multiforge-runtime/src/main/java/net/multiforge/runtime/chunk/PerChunkTickets.java:19` — `private final Set<Ticket> tickets = new HashSet<>(2);`

Class javadoc pins "owning region worker is the only writer, so no lock is
needed". True under current wiring (main-thread only). Fails under
Phase 4.2c's any-thread contract — H4 fix removes the merge race but not the
two-writer race on the same region's map (e.g. two region workers both
doing `holder.setLevel` cascades that touch the same ticket map during a
merge fold). Fix pairs with H4: either take the region worker lock inside
`addTicket` (add a `ReentrantLock` per region), or switch backing collections
to `ConcurrentHashMap` / `ConcurrentHashMap.newKeySet()`. Preferred:
concurrent collections + validate `merge`/`split` iteration snapshots.

### M2 — `MultiForgeChunkMap.hasWork()` is O(regions × priorities) with allocation per call

Site: `upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/chunk/MultiForgeChunkMap.java:1177-1201`.

Every call allocates a fresh `regions()` snapshot via `ThreadedRegionizer.regions()`
and walks `ChunkTaskPriority.values()` × regions. Vanilla `hasWork` is called
from `ServerChunkCache.hasWork` which fires per-tick under some conditions.
The Nested probe is fine for the current no-op body but should be cached
(e.g. an `AtomicInteger` bumped on every enqueue, decremented on drain — or
just check the shared `RegionizedTaskQueue` inbox aggregate) before the
facade goes hot.

### M3 — No test coverage for `addTicket` vs. concurrent merge/split

`PerRegionTicketMapTest.concurrentAddPreservesAllTicketsAcrossRegions` uses
one thread per region — matches the current single-writer discipline, does
not exercise the H4/H5 race. Round-6 fix for H4/H5 must land a regression
test that pins two threads (one bridging chunks to trigger a merge, one
adding tickets to the region about to die) and asserts no ticket is lost.

### M4 — `MultiForgeDistanceManager` abstract seams throw `UnsupportedOperationException` and are never wired

Site: `.../MultiForgeDistanceManager.java:696-711` implements `isChunkToRemove`,
`getChunk`, `updateChunkScheduling` as throwing stubs, with a comment saying
"Design §9 says these three are provided by Phase 4.1b's inner class
subclassing this facade." No such subclass exists yet. If Phase 4.2c wires
`MultiForgeDistanceManager` directly (skipping the inner class), the first
call reaches the throw path with no compile-time warning. Add either a
`sealed` marker forcing the wiring subclass to exist, or a bootstrap-time
assertion in `MultiForgeDistanceManager`'s ctor that a subclass has been
registered. Preferred: `sealed class` + a stub subclass in the fork tree
that the wiring must implement.

### M5 — `NewChunkHolder.addSendDependency` documented as "any thread" but the write is non-atomic

Site: `.../NewChunkHolder.java:592-599`.

```java
public void addSendDependency(CompletableFuture<?> dep) {
    // Non-atomic combine — races between concurrent addSendDependency
    // callers publish the last winner and drop the intermediates.
    this.sendSyncFuture = this.sendSyncFuture.thenCombine(dep, (a, b) -> null);
}
```

The `docs/design/m9-contracts.md` §1.3 lists `addSendDependency` under
"Any-thread safe writes". The code doesn't match: two concurrent callers can
each read the same `sendSyncFuture`, both call `.thenCombine`, then both
write back — the second write wins and the first dependency is silently
dropped. The inline comment acknowledges this ("Vanilla shape is main-thread-only
so this matches"). Either tighten the contract in §1.3 to say "owning region
worker only" (matches the Vanilla shape), or wrap the assignment in a
`getAndUpdate`-style CAS on an `AtomicReference<CompletableFuture<Void>>`.
Preferred: contract tightening — every current caller is single-writer per
holder.

### M6 — `MultiForgeChunkMap.getVisibleChunkIfPresent` allocates a `ChunkHolderShim` per call

Site: `.../MultiForgeChunkMap.java:454-465` and `:504-512` (`getChunks` mints
one shim per holder in the returned Iterable).

`ChunkMap.getVisibleChunkIfPresent` is called hot from Vanilla (every
`ServerChunkCache.getChunk` MISS lookup, every player-radius scan). Allocating
per call is fine for a facade that never runs, but before Phase 4.1c wire-in
lands, add a per-shadow cache (WeakReference or ConcurrentHashMap keyed on
shadow identity) so hot-path allocation is bounded.

### M7 — `TicketExpiryTicker.runOnce` snapshots every holder in the world per call

Site: `.../TicketExpiryTicker.java:49` calls `manager.holders()` which returns
`List.copyOf(byChunk.values())`. For a world with 10K loaded holders and a
Vanilla-parity per-tick sweep, this is a 10K-element allocation per tick per
world. Vanilla walks only the tickets-side map (`Long2ObjectMap`), which is
almost always smaller. Round-6 improvement: iterate `manager.ticketsFor(region).loadedChunks()`
directly across every region, skipping holders with no live tickets.

---

## 5. LOW findings

- **L1** — `ChunkHolderShim.equals()` compares by shadow position only, ignoring
  the level/world. Two shims for the same chunk position on different worlds
  compare equal. Unlikely under current usage but breaks any per-shim `HashSet`
  keyed cross-world. `.../ChunkHolderShim.java:157-159`.
- **L2** — `MultiForgeChunkMap.toMf(ChunkPos)` at `:1434` is defined but has no
  callers (only `toVanilla` and inline constructions are used). Delete.
- **L3** — `MultiForgeChunkMap.updateChunkScheduling` javadoc still references
  the deleted Phase 5.7 shadow mirror in its inline comment (`:704-712`). The
  code below is correct; the comment is stale.
- **L4** — `ChunkHolderShim` javadoc comment says "attachMfShadow is
  package-private" (`.../ChunkHolderShim.java:66-73`) but the actual patched
  Vanilla declaration is `protected` (`.../ChunkHolder.java.patch:113`). Update
  the comment.
- **L5** — `MultiForgeChunkMap.getUpdatingChunkIfPresent` and
  `getVisibleChunkIfPresent` share a body; the "updating" variant mints a
  shim, discards it, then the "visible" variant mints another. Trivially
  `return getVisibleChunkIfPresent(pos)` (already does). No behaviour issue —
  the naming just suggests a distinction the M9 shadow deliberately deletes;
  a doc note would help future readers.
- **L6** — `MultiForgeLightEngine.NO_OP_TASK_MAILBOX` is a static field
  initialised via `ProcessorMailbox.create(...)`. The Vanilla mailbox spins
  up an executor thread on creation — the static init pins one thread for
  the JVM lifetime even when no light engine is ever constructed. Cheap to
  fix (lazy-init on first ctor call).

---

## 6. Cross-cutting observations

**What the M9 landing did well.**

- Phase 1 batches nail the two race classes the blueprint had documented as
  "deferred to a design session". Both fixes (FOLDING quiescence, read-lock
  around `queueChunkTask`) carry deterministic regression tests that pin the
  race, not just the happy path. This is the pattern to keep for H4/H5.
- The observation-first patch strategy under `multiforge-patches/04-chunk-system/`
  keeps every Vanilla-side hunk strictly additive. Every patch either logs a
  probe or forwards to a shadow — no Vanilla control flow changes. `git apply`
  friendliness against NeoForge upstream drift is preserved as promised in
  the M9 patch strategy doc.
- `TicketExpiryTicker`, `ChunkTaskScheduler.ChunkPositionedTask`, and the
  `RegionListener` auto-wiring in `MultiThreadedSchedulerHost.regionizerFor`
  are all cases where a subtle discipline (single-writer per region,
  position-aware split, listener registration order) is enforced by the
  data-shape rather than by convention. That's the right shape.

**Emergent patterns worth naming.**

- The `resolve-owner-then-mutate` pair is the recurring hazard across the
  chunk-system code base. Phase 1.2 solved it for `queueChunkTask`; H4/H5
  identify it for the ticket paths. A helper on `ThreadedRegionizer` —
  `withRegionAt(WorldRef, int, int, Consumer<Region>)` — that internally
  acquires the read lock and invokes the consumer with a stable region
  reference would DRY the pattern and make new resolve-then-mutate sites
  hard to write incorrectly.
- Every fork facade under `upstream/…/net/multiforge/neoforge/chunk/` shares
  the same `INSTANCES` / `of(Vanilla)` / `WeakHashMap` boilerplate
  (`MultiForgeChunkMap:200-215`, `MultiForgeDistanceManager:130-131`,
  `MultiForgeLightEngine:120-121`). Consider a shared `FacadeRegistry<K, V>`
  helper in the runtime module — one code path to audit for the H6 iteration
  hazard.
- Observation hunks (`observeSave`, `observePlayerAdded`, `observeGenStep`,
  `observeCheckBlock` …) all follow the same 2-line pattern. That's fine —
  but the pattern is now duplicated across four Vanilla files. A single
  `MfProbes.observe(...)` entry point on the runtime side would let the
  patches shrink from `observeGenStep(this.level, chunkpos, step)` to
  `MfProbes.observe(this, chunkpos)`, cutting fork-tree drift when NeoForge
  reorganises a probed method.

**What I'd want to see before v0.9.0-m9 tags.**

- H4 + H5 fixed (bundled), with regression coverage that proves the race.
- H2 addressed by either package-private methods or `@ApiStatus.Internal` on
  each method.
- M3's test lands with H4/H5.
- A `sealed`-class or ctor-time assertion for M4 so the never-wired throw
  path can't ship silently.

Round 6 backlog carries M1–M7 and L1–L6 plus H2/H4/H5/H6 fixes.

---

## Appendix — patch-hygiene sweep (`git apply --check`)

Applied a copy-and-apply against a fresh copy of every current upstream
target file:

| Patch | Result |
|-------|--------|
| `ChunkHolder.java.patch` | Applied (already present in tree). |
| `ChunkMap.java.patch` | Applied. |
| `DistanceManager.java.patch` | Applied. |
| `GenerationChunkHolder.java.patch` | Applied. |
| `ServerChunkCache.java.patch` | Applied. |
| `ThreadedLevelLightEngine.java.patch` | Applied. |
| `Ticket.java.patch` | Applied with fuzz 2 / offset 11 — expected because the upstream tree already contains the patched output. |
| `ChunkGenerationTask.java.patch` | Filesystem location fixed this commit (H3). Header path was already correct. |

No true patch failure.

