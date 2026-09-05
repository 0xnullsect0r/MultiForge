# M9 Patch Strategy — Per-Class Topology

**Status:** Phase 0 task 0.2 of the M9 landing plan (`plans/bubbly-jumping-comet.md`).
**Purpose:** Freeze the REPLACE / PATCH / INTERCEPT / UNCHANGED verdict for each
Vanilla chunk-system class so Phase 4 executes without further design.
**Non-goals:** interface contracts (0.1), semantic NBT diff (0.3), MCA format (0.4).

Vocabulary (repeated here so verdicts are unambiguous):

- **REPLACE** — a MultiForge facade class under `net.multiforge.neoforge.chunk.*`
  owns the state. The Vanilla class is patched to be a thin delegate wrapper
  (constructor forwards, every mutating method calls the facade) so mods
  referencing the Vanilla type resolve, and the field layout survives for
  reflective mods. Patch hunks stay under 100 LOC.
- **PATCH** — the Vanilla class keeps its state but individual methods gain
  hunks that redirect work to a MultiForge helper (facade class or pure-Java
  runtime). Used when the class is too small to bother replacing but too
  entangled to leave alone.
- **INTERCEPT** — a single-line shadow call, like the current sub-step-1
  bridge in `multiforge-patches/04-chunk-system/net/minecraft/server/level/ChunkMap.java.patch:5-11`.
  Real behaviour still lives Vanilla-side; MultiForge only observes.
- **UNCHANGED** — Vanilla source is reused verbatim. No patch, no facade.

---

## Per-class verdicts

### ChunkMap (1317 LOC base / 1346 NeoForge)
**Verdict:** REPLACE

**Justification:** ChunkMap owns the holder table
(`Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap`), the worldgen
dispatch (`ChunkMap.java:187-204` builds a `WorldGenContext` around `mainThreadMailbox`),
player tracking (`isChunkTracked`, `updatePlayerStatus`), broadcast fan-out,
and chunk save. Every one of those becomes per-region under Folia/Moonrise.
Patching this class hunk-by-hunk would touch ~40 methods and leave the
mailbox-driven executor half-alive — exactly the shape the plan calls out as
"the single biggest anti-pattern to break". A clean facade
(`MultiForgeChunkMap`) implementing `GeneratingChunkMap` and
`ChunkHolder.PlayerProvider` is the smaller, testable path.

**MultiForge home:** `upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/chunk/MultiForgeChunkMap.java`
(NEW). The Vanilla-side patch shell at
`multiforge-patches/04-chunk-system/net/minecraft/server/level/ChunkMap.java.patch`
(existing file, rewritten) turns every public method into a delegate.

**Rationale-specific notes:**
- Extends `ChunkStorage` and implements `ChunkHolder.PlayerProvider` +
  `GeneratingChunkMap` (`ChunkMap.java:101`); the delegating shell must
  preserve that signature so `ThreadedLevelLightEngine` and downstream
  callers still compile.
- `mainThreadMailbox` field (`ChunkMap.java:131` neoforge mirror) and its
  `WorldGenContext` wiring at line 204 must be *removed* — worldgen fans out
  to per-region workers via `ChunkTaskScheduler`. NeoForge's
  `scheduleOnMainThreadMailbox` (`ChunkMap.java:1343-1344`) must be
  preserved as a delegate that routes through the region owner.
- Preserve NeoForge event fires: `ChunkTicketLevelUpdatedEvent`
  (`ChunkMap.java:398`), `ChunkEvent.Unload` (`ChunkMap.java:522`),
  `ChunkDataEvent.Save` (`ChunkMap.java:769`), `ChunkWatchEvent`
  (`ChunkMap.java:831,835`), `CommonHooks.onChunkUnload` (`ChunkMap.java:518`).
  These must fire in a defined order relative to the ticket transition;
  document in facade javadoc.
- `visibleChunkMap` becomes a *view* backed by
  `ChunkHolderManager.holderAt(...)` (`ChunkHolderManager.java:56`); the
  Vanilla `ChunkHolder` shell wraps a `NewChunkHolder`.
- Task 5.7 deletes the sub-step-1 bridge line — do NOT re-add it inside the
  facade.

**Downstream ripple:** 11 files reference `ChunkMap` directly in the
neoforge tree (grep). The interface preservation makes most callers
untouched; Phase 6 audits.

---

### DistanceManager (511 LOC base / 535 NeoForge)
**Verdict:** REPLACE

**Justification:** The class is `abstract` (`DistanceManager.java:38`) and
its only production subclass is `ChunkMap$DistanceManager`. Its state —
`tickets`, `playersPerChunk`, `ticketTracker`, `naturalSpawnChunkCounter`,
`playerTicketManager`, plus NeoForge's `forcedTickets` map — all becomes
per-region under MultiForge. Every write already needs to reach
`ChunkHolderManager.addTicket/removeTicket`; keeping the Vanilla trackers
alive as a parallel truth is a bug factory. Cleaner to REPLACE and route.

**MultiForge home:** `upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/chunk/MultiForgeDistanceManager.java`
(NEW). Patch shell at
`multiforge-patches/04-chunk-system/net/minecraft/server/level/DistanceManager.java.patch`
(NEW — this is also the target for Phase 1 tasks 1.8 and 1.9).

**Rationale-specific notes:**
- Preserve NeoForge's `addRegionTicket(TicketType,ChunkPos,int,T,boolean forceTicks)`
  overload (`DistanceManager.java:194` NeoForge mirror) and the
  `shouldForceTicks(long)` accessor (`DistanceManager.java:291` NeoForge
  mirror). ServerChunkCache reads it at `ServerChunkCache.java:364` NeoForge
  mirror for natural-spawn allowance.
- `runAllUpdates(ChunkMap)` (`DistanceManager.java:106`) becomes a no-op
  because per-region ticket writes commit in tick phase 1
  (`INBOUND_MAILBOX`, per plan task 5.1). Keep the signature; return `false`.
- `purgeStaleTickets()` (`DistanceManager.java:66`) is superseded by
  `TicketExpiryTicker` (Phase 1 task 1.10). Delegate to it; do not
  re-implement.
- The abstract `updateChunkScheduling` and `getChunk` hooks
  (`DistanceManager.java:104,101`) are inversion-of-control seams for
  `ChunkMap$DistanceManager`; the facade fulfils them by calling into
  `MultiForgeChunkMap` (no anonymous inner subclass).

**Downstream ripple:** 4 files (grep). Low.

---

### ServerChunkCache (561 LOC base / 572 NeoForge)
**Verdict:** PATCH (heavy)

**Justification:** ServerChunkCache is the API boundary — `Level.getChunk`,
`Level.getChunkAt`, `ChunkSource` extension — with a huge downstream caller
surface (44 files use `getChunkSource()`). REPLACING it forces every one of
those to change or wear a compat shim. The class's *internals* are the
problem: `MainThreadExecutor` (`ServerChunkCache.java:526-534` NeoForge),
`managedBlock` drain (`ServerChunkCache.java:155,211`), the recursive
`getChunkFutureMainThread` (line 222). PATCH lets us surgically excise
those while keeping the field layout, constructor signature, and public
method shapes that mods/NeoForge rely on.

**MultiForge home:** `multiforge-patches/04-chunk-system/net/minecraft/server/level/ServerChunkCache.java.patch`
(NEW). Delegate helper at
`upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/chunk/ServerChunkCacheDelegate.java`
(NEW) for the heavy per-region wiring (region resolve, holder-lookup, autosave).

**Rationale-specific notes:**
- `MainThreadExecutor` inner class (`ServerChunkCache.java:526`) must be
  deleted entirely. The `mainThreadProcessor` field
  (`ServerChunkCache.java:54`), constructor argument, and every internal
  caller (lines 83, 94, 138, 155, 159, 211, 216, 219, 275, 403, 434) must
  be replaced with `TickRegionScheduler` fan-out. Do NOT leave a stubbed
  executor — that's what the current shadow bridge does and it's the
  reason the tick loop still deadlocks under strict mode.
- Preserve `IServerChunkCacheExtension` (`ServerChunkCache.java:48`
  NeoForge) and the `public final ServerLevel level` visibility widening
  (line 51 NeoForge).
- Preserve NeoForge's `currentlyLoading` bypass at lines 153-156 and 191 —
  it exists precisely because Vanilla deadlocks the future chain during
  chunk load. Under MultiForge the code path becomes moot (no future
  chain), but mods may still probe `chunkholder.currentlyLoading` so keep
  the field wired.
- Preserve NeoForge `addRegionTicket(..., boolean forceTicks)` overload
  (`ServerChunkCache.java:445` NeoForge) — delegate to
  `MultiForgeDistanceManager.addRegionTicket`.
- `runDistanceManagerUpdates` becomes a no-op (writes are in-region).
- `save(boolean flush)` / `close()` orderly flush: `AutoSaveRunner` per
  region, then `RegionShutdownCoordinator.awaitAll()`.

**Downstream ripple:** 6 files (grep) + 44 `getChunkSource()` callers.
API preservation keeps that footprint zero-code-change.

---

### ChunkHolder (331 LOC, no NeoForge delta)
**Verdict:** PATCH

**Justification:** Public superclass for `NewChunkHolder`'s Vanilla-facing
view. Mods reflect on its fields (`fullChunkFuture`, `tickingChunkFuture`,
`playerProvider`). Replacing wholesale breaks that reflection surface;
leaving Vanilla to own state alongside MultiForge is the current bridge's
sin. PATCH with a `shadow` field on `ChunkHolder` that prefers the
`NewChunkHolder` state and falls back to the Vanilla field only when null
gives a clean transitional shape that Phase 5 can fully rip out.

**MultiForge home:** `multiforge-patches/04-chunk-system/net/minecraft/server/level/ChunkHolder.java.patch`
(NEW). No new facade class — the state lives in `NewChunkHolder` already
(`net.multiforge.runtime.chunk.NewChunkHolder`; Phase 2 extends it).

**Rationale-specific notes:**
- Preserve the `LevelChangeListener` and `PlayerProvider` inner interfaces
  (`ChunkHolder.java:323-330`) — `ChunkTaskPriorityQueueSorter` implements
  the former (`ChunkTaskPriorityQueueSorter.java:27`).
- `updateFutures(ChunkMap, Executor)` (`ChunkHolder.java:260`) delegates
  to `NewChunkHolder.updateFutures(...)`. Do not run Vanilla's transition
  logic in parallel.
- Preserve NeoForge `currentlyLoading` field on `GenerationChunkHolder`
  (`GenerationChunkHolder.java:36` NeoForge mirror) — this is on the
  parent class, not `ChunkHolder`.
- The three `CompletableFuture<ChunkResult<LevelChunk>>` fields at lines
  36-38 remain but are populated *by* `NewChunkHolder` state gates from
  Phase 2 task 2.2, not by `ChunkMap.prepareTickingChunk` directly.

**Downstream ripple:** 12 files (grep). Field-surface-preserving means
most compile untouched.

---

### GenerationChunkHolder (311 LOC base / 312 NeoForge)
**Verdict:** PATCH

**Justification:** Same argument as `ChunkHolder`: it's the parent class of
`ChunkHolder`, mods extend or reflect on it. The mutation is one-method:
`rescheduleChunkTask` must route through the region-owning worker. Small,
localised, keeps the reflective surface intact.

**MultiForge home:** `multiforge-patches/04-chunk-system/net/minecraft/server/level/GenerationChunkHolder.java.patch`
(NEW).

**Rationale-specific notes:**
- Preserve `AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>> futures`
  (`GenerationChunkHolder.java:33`) — Phase 2 task 2.3 mirrors this in
  `NewChunkHolder`; both must stay in sync while the transitional shadow
  is in place.
- Preserve NeoForge `currentlyLoading` public field
  (`GenerationChunkHolder.java:36` NeoForge). It's read from
  `ServerChunkCache.java:155,191` NeoForge — do not delete.
- `scheduleChunkGenerationTask` (`GenerationChunkHolder.java:41`) stays
  Vanilla; the region-owning thread guarantee is enforced by
  `rescheduleChunkTask` routing.

**Downstream ripple:** 11 files (grep).

---

### ChunkGenerationTask (169 LOC, no NeoForge delta)
**Verdict:** PATCH

**Justification:** The class is a small state machine driving the
`ChunkStep` ladder. `runUntilWait` (`ChunkGenerationTask.java:41`) is the
only method whose continuation touches worker-thread assumptions. The rest
(`scheduleNextLayer`, `waitForScheduledLayer`, `releaseClaim`) is pure
control flow. REPLACE is overkill.

**MultiForge home:** `multiforge-patches/04-chunk-system/net/minecraft/world/level/chunk/status/ChunkGenerationTask.java.patch`
(NEW). Path is `world/level/chunk/status/` — sanity check: this class is in
`net.minecraft.server.level`, so patch path is
`multiforge-patches/04-chunk-system/net/minecraft/server/level/ChunkGenerationTask.java.patch`.

**Rationale-specific notes:**
- Cross-region neighbour reads go through `RegionizedTaskQueue.queueChunkTask`.
- Preserve `ChunkStep`/`ChunkPyramid`/`ChunkStatus` tree references —
  those are UNCHANGED (`chunks.md` "reuse verbatim").
- Ownership of the continuation is the region worker owning `this.pos`, not
  the calling thread.

**Downstream ripple:** 5 files (grep). Low.

---

### ChunkTaskPriorityQueue (114 LOC, no NeoForge delta)
**Verdict:** REPLACE (via internal-class no-op)

**Justification:** Pure data structure — a per-priority `LinkedOpenHashMap`
of task lists (`ChunkTaskPriorityQueue.java:17-25`). MultiForge already has
its per-region equivalent in `net.multiforge.runtime.chunk.ChunkTaskScheduler`
(plan §"Reusable pieces"). The Vanilla class needs to stay compile-visible
because `ChunkTaskPriorityQueueSorter.getProcessor` returns typed handles
that reference it, but at runtime it must never receive work — every submit
becomes a no-op that logs a warn under strict mode.

**MultiForge home:** `multiforge-patches/04-chunk-system/net/minecraft/server/level/ChunkTaskPriorityQueue.java.patch`
(NEW). No new facade — the runtime home is `ChunkTaskScheduler` (existing).

**Rationale-specific notes:**
- Every non-constructor method reduces to `no-op` + strict-mode warn.
- Preserve the class shape so `ChunkTaskPriorityQueueSorter`'s generic
  bounds still resolve.
- Do NOT delete `PRIORITY_LEVEL_COUNT` (`ChunkTaskPriorityQueue.java:18`)
  — it may be referenced by mods computing priority ranges.

**Downstream ripple:** 3 files (grep).

---

### ChunkTaskPriorityQueueSorter (211 LOC, no NeoForge delta)
**Verdict:** PATCH (thin shim)

**Justification:** Whole class is a mailbox-orchestrated
priority-queue sorter (`ChunkTaskPriorityQueueSorter.java:31` +
`ProcessorMailbox` on line 37). Under per-region MultiForge dispatch, the
whole abstraction is subsumed by `ChunkTaskScheduler.scheduleChunkTask(...)`
with a `ChunkTaskPriority`. But the class publishes three mandatory API
seams:
1. `implements ChunkHolder.LevelChangeListener` (line 27) — read by
   `ChunkMap` construction and `updateFutures`. **This seam must survive.**
2. `Message<T>` and `Release` static holders (lines 188-210) — used by
   NeoForge's `scheduleOnMainThreadMailbox` at `ChunkMap.java:1343-1344`.
3. `getProcessor` / `getReleaseProcessor` returning `ProcessorHandle` — used
   by `ChunkMap` construction wiring at `ChunkMap.java:188`.

Preserving those makes PATCH cleaner than REPLACE.

**MultiForge home:** `multiforge-patches/04-chunk-system/net/minecraft/server/level/ChunkTaskPriorityQueueSorter.java.patch`
(NEW). The returned `ProcessorHandle`s route into
`net.multiforge.runtime.chunk.ChunkTaskScheduler.scheduleChunkTask(...)`.

**Rationale-specific notes:**
- **The `LevelChangeListener` seam must survive** because `ChunkHolder.updateFutures`
  calls `this.onLevelChange.onLevelChange(...)` at `ChunkHolder.java:311`.
  Rewire `onLevelChange` to push the level into `NewChunkHolder`.
- `ProcessorMailbox` field (line 31) is deleted; the class becomes a stateless
  router.
- Preserve public static factory methods `message(...)` and `release(...)`
  (lines 44-65) so downstream code compiles.

**Downstream ripple:** 6 files (grep).

---

### ThreadedLevelLightEngine (228 LOC, no NeoForge delta)
**Verdict:** REPLACE

**Justification:** The class is *the* mailbox-driven off-tick executor:
`ProcessorMailbox<Runnable> taskMailbox` at
`ThreadedLevelLightEngine.java:29`, `sorterMailbox` at line 32,
`lightTasks` list at line 30, `scheduled` AtomicBoolean at line 34. Every
public method is a `taskMailbox.tell(...)` wrapper. Under per-region
dispatch light propagation runs on the chunk-owning worker and cross-region
propagation routes via `RegionizedTaskQueue`. Nothing of the mailbox model
survives; PATCHing it hunk-by-hunk would leave dead fields and dead
executors. REPLACE with a `MultiForgeLightEngine` extending
`LevelLightEngine`.

**MultiForge home:** `upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/chunk/MultiForgeLightEngine.java`
(NEW). Vanilla-side patch shell at
`multiforge-patches/04-chunk-system/net/minecraft/server/level/ThreadedLevelLightEngine.java.patch`
turns `ThreadedLevelLightEngine` into a delegate that forwards to the
facade.

**Rationale-specific notes:**
- Constructor signature must survive (five-arg form at line 36) so
  `ChunkMap` construction compiles.
- `close()` (line 50), `runLightUpdates()` (line 54, throws
  `UnsupportedOperationException` per Vanilla), `checkBlock`, `updateChunkStatus`,
  `retainData`, `setLightEnabled`, `queueSectionData`, `updateSectionStatus`,
  `tryScheduleUpdate` — all delegate.
- Cross-region light propagation MUST route through
  `RegionizedTaskQueue.queueChunkTask(world, chunkX, chunkZ, ...)` — never a
  direct call into another region's `LayerLightEngine`.
- Preserve `taskMailbox` public shape only if mods reflect on it; verify
  by grep before deletion.

**Downstream ripple:** 5 files (grep).

---

### ChunkLevel (70 LOC, no NeoForge delta)
**Verdict:** UNCHANGED

**Justification:** Pure math — a level integer ↔ `ChunkStatus` /
`FullChunkStatus` bijection (`ChunkLevel.java:36-57`) with no state and no
threading assumptions. Reused verbatim per the plan ("Reusable pieces"
list) and per `net.multiforge.runtime.chunk.ChunkLoadLevel` already
delegates to `ChunkLevel.byStatus`.

**MultiForge home:** N/A.

**Rationale-specific notes:** Do not add a MultiForge shadow. Any code
converting between level integers and statuses calls Vanilla directly.

**Downstream ripple:** 7 files (grep). Zero-touch.

---

### TicketType (47 LOC, no NeoForge delta)
**Verdict:** UNCHANGED (Vanilla side); EXTEND (MultiForge side)

**Justification:** Pure metadata — `(name, comparator, timeout)`
(`TicketType.java:9-33`) with static instances for `START`, `DRAGON`,
`PLAYER`, `FORCED`, `PORTAL`, `POST_TELEPORT`, `UNKNOWN`. Reused verbatim
per the plan. MultiForge has its own
`net.multiforge.runtime.chunk.TicketType` for internal ticket
identity; those two type spaces coexist. Phase 1 task 1.10 adds an
expiry field to the *MultiForge* `TicketType`, not to Vanilla's.

**MultiForge home:** N/A for Vanilla. Runtime-side extension in
`multiforge-runtime/src/main/java/net/multiforge/runtime/chunk/TicketType.java`
(existing) per plan task 1.10.

**Rationale-specific notes:** `MultiForgeDistanceManager.addTicket` MUST
translate Vanilla `TicketType<T>` → MultiForge `TicketType` at the
boundary. The translation table lives in `MultiForgeDistanceManager`,
not in `TicketType` itself.

**Downstream ripple:** 9 files (grep). Zero-touch.

---

### Ticket (64 LOC base / 76 NeoForge)
**Verdict:** PATCH

**Justification:** NeoForge already adds a `forceTicks` field, an alt
constructor, and equality/hash/toString delta (`Ticket.java:12-15,19,45,50,68-74`
NeoForge). Phase 4 task 4.8a adds an `expiryTick` field so
`TicketExpiryTicker` (Phase 1 task 1.10) can read it without a side table.
Both changes are additive fields with defaults — PATCH is trivial.

**MultiForge home:** `multiforge-patches/04-chunk-system/net/minecraft/server/level/Ticket.java.patch`
(NEW).

**Rationale-specific notes:**
- Preserve NeoForge `isForceTicks()` accessor
  (`Ticket.java:71-73` NeoForge). MultiForge's expiry field is a separate
  additive field.
- Update equals/hashCode/toString to include the new field.
- The Vanilla `timedOut(long)` (`Ticket.java:60`) stays; MultiForge's
  expiry sweep uses the *added* field, not this method.

**Downstream ripple:** 3 files (grep).

---

### FullChunkStatus (12 LOC, no NeoForge delta)
**Verdict:** UNCHANGED

**Justification:** Pure enum with a four-value ordinal ladder
(`FullChunkStatus.java:3-11`) and one `isOrAfter` predicate. No state, no
threading, no extension point. Reused verbatim.

**MultiForge home:** N/A.

**Rationale-specific notes:** `MultiForgeChunkMap.onFullChunkStatusChange`
takes a Vanilla `FullChunkStatus` argument, same as
`ChunkMap.onFullChunkStatusChange`.

**Downstream ripple:** 14 files (grep). Zero-touch.

---

## Cross-cutting rules

- **Every patch** for chunk-system classes lives under
  `multiforge-patches/04-chunk-system/`. No exceptions — not
  `03-world-data`, not `08-globals`.
- **Every MultiForge facade class** lives under a new subpackage
  `net.multiforge.neoforge.chunk.*`. This is new — today's classes live
  directly in `net.multiforge.neoforge.*`. Rationale: 5 REPLACE + delegate
  helpers means ≥7 classes; a subpackage keeps navigation sane and makes
  `@ApiStatus.Internal` grouping obvious.
- **Every pure-Java support class** stays under
  `net.multiforge.runtime.chunk.*` (existing package;
  `ChunkHolderManager`, `NewChunkHolder`, `ChunkTaskScheduler`,
  `PerRegionTicketMap`, etc. already live here).
- **Patch hunks stay thin.** If a `.java.patch` file exceeds ~150
  contiguous LOC of insertion, the heavy logic belongs in the facade, not
  in `net.minecraft.*`. Rebase drift on `net.minecraft.*` is a maintenance
  cost NeoForge upstream drift makes very expensive
  (`CLAUDE.md` "NeoForge upstream drift is a maintenance cost").
- **Every facade class** carries `@ApiStatus.Internal` until v1.0 per
  `CLAUDE.md` "Code style".

---

## Migration order (Phase 4 sequencing)

Interdependencies dictate the order:

1. **`ChunkHolder` + `GenerationChunkHolder` PATCHes first (4.4, 4.7b).**
   They add the `shadow → NewChunkHolder` seam that everything else reads.
   Without it, `MultiForgeChunkMap` can't populate the Vanilla-visible
   `fullChunkFuture`.
2. **`Ticket` PATCH (4.8a).** Adds the expiry field.
   `MultiForgeDistanceManager` needs it to write tickets in the shape
   `TicketExpiryTicker` reads.
3. **`MultiForgeChunkMap` implement (4.1a-b) parallel with
   `MultiForgeDistanceManager` implement (4.2a-b).** Both new files; no
   Vanilla patches yet; independent unit tests.
4. **`ChunkMap` PATCH shell (4.1c) + `DistanceManager` PATCH shell (4.2c)
   land together.** Compile-and-boot gate. Landing one without the other
   breaks construction because `ChunkMap`'s constructor instantiates its
   inner `DistanceManager`.
5. **`ChunkGenerationTask` PATCH (4.7a).** Depends on 4.1 landing (needs
   the facade's `ChunkTaskScheduler` handle).
6. **`ChunkTaskPriorityQueueSorter` PATCH shim (4.6a) + `ChunkTaskPriorityQueue`
   no-op (implicit in 4.6a).** Depends on the whole priority-queue path
   being unused by production code, which is only true after 4.1 lands.
7. **`MultiForgeLightEngine` implement (4.5a-b) + `ThreadedLevelLightEngine`
   PATCH (4.5c).** Independent of chunk-map path; can start immediately
   after step 1 but must not merge until step 6 to keep the CI green
   window small.
8. **`ServerChunkCache` PATCH (4.3a-e) LAST.** Every other class must be
   in place before we delete `MainThreadExecutor` — otherwise
   `getChunkFuture` returns a broken future chain and the server won't
   boot.

Peak Phase-4 parallelism is at step 3 (three concurrent implementations).

---

## Rebase-drift risk assessment

REPLACE classes' rebase cost when moving to a newer 1.21.x NeoForge:

| Class | Vanilla-add rate | Rebase risk | Strategy |
|---|---|---|---|
| `MultiForgeChunkMap` | HIGH — Vanilla adds worldgen methods, player-tracking tweaks, save-order fixes almost every point release. | **HIGH** | Facade implements a stable `GeneratingChunkMap` interface; new Vanilla methods land on the delegate shell first, then bubble down. Design the facade's public API to be a superset of `GeneratingChunkMap` + `ChunkHolder.PlayerProvider` so a new Vanilla method only needs a delegate line. |
| `MultiForgeDistanceManager` | LOW — Vanilla's ticket model has been stable since 1.19; NeoForge's `forceTicks` extension is the only churn. | **LOW** | Preserve NeoForge's `addRegionTicket(...,forceTicks)` overload as first-class; new tickets appear only as new `TicketType` static instances (additive, safe). |
| `MultiForgeLightEngine` | MEDIUM — Vanilla occasionally adds `LightEngine.checkBlock` variants; NeoForge doesn't diverge. | **MEDIUM** | Facade extends `LevelLightEngine` (not `ThreadedLevelLightEngine`) so new light methods land as `super` overrides. If Vanilla renames a public method, patch shell breaks first — cheap signal. |
| `ChunkTaskPriorityQueue` no-op | ZERO — the class is inert. | **ZERO** | New Vanilla priority levels harmlessly ignored. |

PATCH classes' rebase cost:

- `ServerChunkCache`, `ChunkHolder`, `GenerationChunkHolder`,
  `ChunkGenerationTask`, `ChunkTaskPriorityQueueSorter`, `Ticket`,
  `DistanceManager` — patch hunks touch specific line ranges. Rebase risk
  is proportional to hunk count. Keep each patch under ~5 hunks per file
  and group by concern so a rebase conflict resolves per-hunk.
- **Stable seams** (safe to bind against): `ChunkHolder.LevelChangeListener`,
  `ChunkHolder.PlayerProvider`, `FullChunkStatus`, `ChunkLevel.byStatus`,
  `TicketType.create`, `ChunkStatus.getStatusList()`.
- **Volatile seams** (bind through the facade, not directly):
  `ChunkMap$DistanceManager` inner subclass shape, `WorldGenContext`
  constructor, `ChunkSerializer.write/read` signature,
  `mainThreadMailbox` and `mainThreadProcessor` — all of these change
  across 1.21 point releases.

Cadence: expect a full-day rebase per NeoForge tag bump; the topology
above front-loads that cost into `MultiForgeChunkMap` where the design
already anticipates it.
