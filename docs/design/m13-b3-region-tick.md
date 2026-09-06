# Full B3 — Per-Region Entity/Block-Tick Wiring — Design

**Status:** Task B3.0 of the B3 + M12 landing plan
(`plans/bubbly-jumping-comet.md`). Freezes the contract that B3.1
through B3.5 implement against; changes to any signature below require
a re-review of every B3.x task that depends on it.

**Companion docs:** `docs/design/global-region.md` (§6, the
`vanillaBody.run()` retirement path this document completes; §6.4 in
particular is the frozen `dispatchLevelTick` shape reproduced in §2
below). This document does not restate the global-subsystem contract
(weather, time, world-border, scoreboard, bossbars, raids, dragon
fight, command-dispatch) — that migration (B2/M5) is already landed.
This document covers only the *residual* per-level tick work that
survives after B2: entity AI, block-entity ticking, and scheduled
block/fluid ticks.

**Non-goals:** global-subsystem semantics (see `global-region.md`),
entity cross-region migration semantics (see
`docs/design/entity-migration.md`), chunk-source ticking (deferred,
§9). This document covers: the problem statement, the frozen target
`dispatchLevelTick` shape, the six-phase tick body layout, the
per-region ownership contract (B3.1), the per-subsystem migration plan
for BLOCK_FLUID_TICKS/ENTITY_AI/BLOCK_ENTITIES (B3.2/B3.3/B3.4),
correctness invariants, the `dispatchLevelTick` refactor (B3.5), test
strategy, and deferred non-goals.

Cite conventions: `file:line` refers to the repo at freeze time
(2026-09-06, `develop`). `net.mf.rt.*` = `net.multiforge.runtime.*`
(pure Java, no Minecraft dependency, lives under
`multiforge-runtime/src/main/java/`); `net.mf.nf.*` =
`net.multiforge.neoforge.*` (the fork facade, lives under
`upstream/neoforge-1.21.1/src/main/java/`, may depend on
`net.minecraft.*`). Vanilla source line numbers refer to the vendored
tree at `upstream/neoforge-1.21.1/`.

---

## 1. Context and problem statement

### 1.1 Where we are today

`RegionizedTickCoordinator.dispatchLevelTick` (`upstream/neoforge-1.21.1/
src/main/java/net/multiforge/neoforge/RegionizedTickCoordinator.java`)
is the fork-local facade patched `MinecraftServer.tickChildren` code
routes each `serverlevel.tick(p)` call through. As of the B2/M5
landing (all eight global subsystems migrated — weather, time,
world-border, scoreboard, bossbars, raids, dragon fight,
command-dispatch), the method's steady-state flow is:

1. Snapshot the world's live regions and call
   `TickRegionScheduler.tickAll(regions, deadline)` as the
   synchronisation barrier for the region worker pool.
2. Unconditionally call `vanillaBody.run()` — Vanilla's per-level tick
   body — **on the caller thread** (`RegionizedTickCoordinator.java:193`).

Because every migrated global subsystem's Vanilla method now
early-returns via an `xxxReady()` guard, that trailing call is a
no-op for the eight global-subsystem pieces. But it is *not* a no-op
for everything else Vanilla's per-level tick body does. Per the
class's own Javadoc (`RegionizedTickCoordinator.java:41-62`) and the
inline comment directly above the call site
(`RegionizedTickCoordinator.java:177-192`), `vanillaBody.run()`
still executes, inline, on the main server thread:

- `entityTickList.forEach(this::checkDespawn)` and
  `entityTickList.forEach(guardEntityTick(this::tickNonPassenger))` —
  the entire entity AI/physics loop.
- `blockEntityTickers.tick()` — every block entity in the level.
- `blockTicks.tick(...)` / `fluidTicks.tick(...)` — scheduled block and
  fluid updates (redstone, crop growth, water/lava flow, etc.).
- `serverChunkCache.tick()` — the chunk source's own tick (deferred,
  §9; not in scope for B3).

`MultiThreadedSchedulerHost.installM9WiredTickBody`
(`multiforge-runtime/src/main/java/net/multiforge/runtime/scheduler/
MultiThreadedSchedulerHost.java:560-604`) confirms the region-worker
side of the gap: `BLOCK_FLUID_TICKS` and `ENTITY_AI` are left as the
`PhasedRegionTickBody` no-op default — nothing is ever wired into
them in production. `BLOCK_ENTITIES` *is* wired
(`MultiThreadedSchedulerHost.java:600`, `phaseGlobalSystemsTick`,
defined at `MultiThreadedSchedulerHost.java:629`), but only for the
synthetic global region — `phaseGlobalSystemsTick` early-returns
(`MultiThreadedSchedulerHost.java:630`) for every real, per-world
region. So today, block entities across the entire server — hoppers,
furnaces, chests, spawners, beacons, everything a mod adds — tick on
the main thread inside `vanillaBody.run()`, same as Vanilla, same as
entity AI, same as scheduled block/fluid ticks.

**The practical consequence:** region workers currently handle chunk
loading, chunk-ticket bookkeeping, light propagation, and (as of B2)
the eight global subsystems — but the actual hot loop that
parallelization exists to speed up (tens of thousands of entities and
block entities across a busy server) is still fully single-threaded.
Region workers tick empty regions in parallel while the main thread
serially walks every entity and block entity in the world. This is
not a cosmetic gap: it is the reason B3 exists as its own tracked
milestone rather than a footnote of B2.

### 1.2 The /67 round-6 fork B F1 finding

An earlier version of this migration's landing plan proposed
deleting the trailing `vanillaBody.run()` call as part of the B2/M5
global-subsystem work, on the theory that once all eight subsystems
were migrated, the call was "just running no-op guards" and could be
safely removed. A `/67` six-strategy review (round 6, fork B,
finding F1) caught that this reasoning was incomplete: the eight
`xxxReady()` guards only cover the *global* subsystems. The
call also runs the residual per-level work enumerated in §1.1 — none
of which had (or, as of this document, has) a region-worker
replacement. Deleting the call at that point would have **silently
disabled entity ticking, block-entity ticking, and scheduled
block/fluid ticks on every MultiForge-installed server** — mobs would
stop moving, hoppers would stop pulling items, redstone would stop
updating, all without a crash or a log line, because the deletion
itself produces no error; the tick loop keeps advancing, just doing
less work each pass.

This finding is preserved verbatim as a code comment at
`RegionizedTickCoordinator.java:190` ("Removing this call silently
disables entity/block/blockentity ticking on the MultiForge-installed
path — /67 round-6 fork B F1 caught this.") specifically so a future
contributor does not repeat the naive revert. **B3 exists to make that
comment obsolete by giving every one of those Vanilla call sites a
real per-region destination before the trailing call is finally
deleted (B3.5).** Until B3.2, B3.3, and B3.4 all land and are
verified, `vanillaBody.run()` stays exactly where it is.

### 1.3 What sub-step 6b actually did (and did not do)

`docs/blueprint.md`'s M8 section currently describes sub-step 6b as
"DONE" and claims it swapped `dispatchLevelTick` "from pass-through to
real per-region dispatch, decomposing `ServerLevel.tick`'s per-chunk
work (block/fluid ticks, entity iteration, block-entity iteration) so
each region owns its slice." Reading the coordinator source directly
contradicts this: `RegionizedTickCoordinator.java:177-193`'s own
comment says in as many words that "Full B3 (per-region entity +
block-tick wiring) is deferred past v1.2.0 to a follow-up milestone."
What sub-step 6b actually delivered was the `TickRegionScheduler.tickAll(...)`
call — a **barrier and fan-out mechanism**, not a decomposition of the
per-chunk tick work itself. It ensures every live region's worker has
finished its (at the time, mostly empty) `PhasedRegionTickBody`
before the main thread proceeds to run Vanilla's per-level body — a
necessary synchronisation primitive, but not the migration blueprint.md's
prose claims it is. §10 corrects that prose block in the same commit
as this document.

---

## 2. Frozen target shape

The target `dispatchLevelTick` shape is frozen in
`docs/design/global-region.md` §6.4 ("Final `dispatchLevelTick`
shape", `global-region.md:765-794`) as the end state once "B3.1 lands
(all of B2low + B2high + A4's entity-tick `vanillaBody.run()`
deletion)". Reproduced verbatim here so B3.5 has a single
implementation target without needing to cross-reference two
documents mid-patch:

```java
public static void dispatchLevelTick(ServerLevel level) {
    MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
    if (host == null) {
        ViolationLogger.warn("region-tick.dispatch.no-runtime", "... — skipping tick for " + level.dimension());
        return;
    }
    ThreadedRegionizer regionizer = host.regionizerForOrNull(asWorldRef(level));
    if (regionizer == null) {
        ViolationLogger.warn("region-tick.dispatch.no-regionizer", "... — skipping tick");
        return;
    }
    try {
        TickRegionScheduler.TickAllResult result = host.scheduler().tickAll(regionizer.regions(), DISPATCH_DEADLINE_NANOS);
        if (!result.allCompleted()) { /* unchanged strict/warn handling, :145-156 */ }
    } catch (Throwable t) {
        ProbeRegistry.bump("region-tick.dispatch.failure");
        ViolationLogger.warn("region-tick.dispatch.failure", /* ... */);
        // no inline fallback left to run — the tick is skipped this cycle.
    }
}
```

Three properties of this shape are load-bearing and must survive
B3.5's actual patch unchanged:

1. **The `Runnable vanillaBody` parameter is gone entirely** — not
   just unused, not just always-empty. `global-region.md:796-799`
   notes the exit gate's own check greps the literal token
   `vanillaBody` and expects zero matches, because the token also
   matches the parameter name itself, not only call sites.
2. **Every fallback path (bootstrap, no-regionizer, dispatch-failure)
   changes shape**, from "run the full Vanilla body inline" to
   "log-and-skip via a named, rate-limited `ViolationLogger.warn` call
   and a `ProbeRegistry` bump, then return." This is a deliberate
   risk-trade: CLAUDE.md rule 4 (no blocking calls on a region worker
   thread) and rule 5 (auto-reroute + warn, never silently swallow)
   both point toward "correctness over liveness for one tick" once a
   full inline-Vanilla-tick fallback would itself risk racing real
   per-region bodies that now do the entity/block-entity/block-tick
   work concurrently. A silent skip would violate rule 5; a full
   inline Vanilla re-run would risk exactly the double-tick /
   cross-thread-touch hazard §7 exists to rule out. Log-and-skip is
   the only remaining safe option once real per-region bodies exist.
3. **No trailing unconditional call of any kind.** The old shape's
   line 193 has no replacement — once BLOCK_FLUID_TICKS, ENTITY_AI,
   and (per-region) BLOCK_ENTITIES are wired, the residual work is
   fully covered by `TickRegionScheduler.tickAll`'s dispatch to the
   `PhasedRegionTickBody` phases themselves. There is nothing left
   for `dispatchLevelTick` to do after the barrier returns.

§8 (`dispatchLevelTick` refactor) below restates each of these three
properties as concrete acceptance criteria for B3.5's patch, with the
exact probe-key names it must bump.

---

## 3. Six-phase tick body layout

`PhasedRegionTickBody` (`multiforge-runtime/src/main/java/net/multiforge/
runtime/region/PhasedRegionTickBody.java:47-54`) defines the six
ordered phases every region worker runs, per tick, in this order —
matching `docs/blueprint.md`'s "Region local phase ordering" section
(`blueprint.md:176-184`):

| # | Phase | Status before B3 | Status after B3 |
|---|---|---|---|
| 1 | `INBOUND_MAILBOX` | Wired (`phasePollFullLoadUpdate`, prepended) | Unchanged |
| 2 | `BLOCK_FLUID_TICKS` | No-op default | **Wired — B3.2** |
| 3 | `ENTITY_AI` | No-op default | **Wired — B3.3** |
| 4 | `BLOCK_ENTITIES` | Wired for global region only (`phaseGlobalSystemsTick`) | **Extended per-region — B3.4**, global-region body unchanged |
| 5 | `REGION_EVENTS` | Wired (`phaseDrainChunkTasks`, appended) | Unchanged |
| 6 | `FLUSH_OUTBOUND` | Wired (`phaseAutoSave`, appended) | Unchanged |

Each phase runs inside its own `try`/`catch` in
`PhasedRegionTickBody.tickOnce` (`PhasedRegionTickBody.java:74-80`,
the "/67 round-4 fix (B7)" comment) — one throwing phase never strands
a later phase in the same tick. B3's new phase bodies inherit this
isolation for free; they do not need their own top-level try/catch,
though §5's per-subsystem sections still call out where an
inner-loop guard is needed (an individual entity or block entity
throwing must not abort the rest of that phase's iteration — see
§5.3, §5.4).

Two phases are notable for what they are *not* used for by B3:
`INBOUND_MAILBOX` and `FLUSH_OUTBOUND` stay exactly as they are — B3
adds no new mailbox or outbound-flush work, because none of the three
migrated subsystems produce cross-region messages of their own (an
entity that crosses a region boundary is an M4 concern, not a B3
concern; see §9).

---

## 4. Per-region ownership contract (B3.1 foundation)

All three phase bodies (§5) need the same primitive: "given a region,
what does it own?" B3.1 lands three additions that answer this
without introducing a second source of truth alongside
`ChunkHolderManager`'s existing `byChunk` map (CLAUDE.md's M9
chunk-system convention #1 — chunk work goes through
`ChunkHolderManager`, never a parallel structure).

### 4.1 `ChunkHolderManager.holdersOwnedBy(RegionId)`

New method on `ChunkHolderManager`
(`multiforge-runtime/src/main/java/net/multiforge/runtime/chunk/
ChunkHolderManager.java`), following the exact pattern the class
already uses for `getBorderHolderCount()`
(`ChunkHolderManager.java:390-396`) — a single linear pass over the
`byChunk` map's values, filtered by predicate, returning a snapshot:

```java
public List<NewChunkHolder> holdersOwnedBy(RegionId region) {
    List<NewChunkHolder> out = new ArrayList<>();
    for (NewChunkHolder h : byChunk.values()) {
        if (region.equals(h.owningRegion())) out.add(h);
    }
    return out;
}
```

This is deliberately **not** cached or indexed by region up front.
`byChunk` is a `ConcurrentHashMap<ChunkPos, NewChunkHolder>` written
from arbitrary region-worker threads (chunk load/unload,
`onRegionMerged`, `onRegionSplit`); maintaining a second
region-indexed structure in sync with every one of those write paths
is real complexity for a call that, per B3.1's own task note, fires
**once per phase per region per tick** — not once per chunk, not on
any tick-hot inner loop. A full scan of `byChunk.values()` is
O(chunks in the world), which for a busy world is a few thousand
entries; three such scans per tick (one per B3 phase) is comparable
cost to a single `getBorderHolderCount()` call today, which already
runs on a similar cadence. If a future benchmark shows this
dominating tick time, per-region caching becomes an option — but it
is out of scope for B3 per the task note ("with per-region caching if
benchmarks say otherwise").

### 4.2 `HolderManagerRegionData.blockEntityTickers`

`HolderManagerRegionData`
(`multiforge-runtime/src/main/java/net/multiforge/runtime/chunk/
HolderManagerRegionData.java`) already carries two per-region queues —
`pendingFullLoadUpdate` and `autoSaveQueue` — each with matching
`merge`/`split` methods invoked from `ChunkHolderManager.onRegionMerged`
/ `onRegionSplit`. B3.1 adds a third: `blockEntityTickers`, a
per-region slice of what would be `Level.blockEntityTickers` in
Vanilla (a single, level-wide list). The new field follows the same
shape as the existing two:

```java
private final List<TickingBlockEntity> blockEntityTickers = new ArrayList<>();

public void addBlockEntityTicker(TickingBlockEntity ticker) { ... }
public void removeBlockEntityTicker(TickingBlockEntity ticker) { ... }
public List<TickingBlockEntity> blockEntityTickersSnapshot() { ... }
```

`merge(HolderManagerRegionData other)` gains one more fold (append
`other.blockEntityTickers` into `this.blockEntityTickers`, clear
`other`'s), and `split(Predicate<NewChunkHolder> shouldLeave)` gains
one more peel — but block-entity tickers are not indexed by
`NewChunkHolder`, they are indexed by the block position they tick.
The split predicate for this field is therefore over
`BlockPos`-derived chunk membership (does this ticker's block
position fall in a chunk `shouldLeave` accepts?), not directly over a
`NewChunkHolder` the way the existing two fields are. This is the one
place B3.1's split/merge logic differs in shape from the existing
pattern, and it is the load-bearing correctness property §6 depends
on: **a block-entity ticker whose block position resolves to region R
is only ever present in R's slice of `blockEntityTickers`,** so a
split or merge must re-resolve that position against the *new*
region boundaries, not merely copy references across.

### 4.3 `Region.ownedChunkSnapshot()`

`Region` (`multiforge-runtime/src/main/java/net/multiforge/runtime/
region/Region.java`) gains two new accessors, backed by
`ChunkHolderManager.holdersOwnedBy` (§4.1):

```java
public int ownedChunkCount();
public List<ChunkPos> ownedChunkSnapshot();
```

These are the single entry point all three downstream phase bodies
(§5) use to answer "which chunks does this region own, right now, on
this worker thread?" A `RegionOwnedChunks` utility class
(`net.multiforge.runtime.region.RegionOwnedChunks`) is introduced only
if the accessor plumbing between `Region` and
`ChunkHolderManager.holdersOwnedBy` needs more than direct
delegation (e.g. if `Region` cannot see `ChunkHolderManager` directly
without a package-visibility change) — B3.1's implementation task
decides this at patch time; both shapes satisfy this document's
contract as long as the call site in each `phaseXxxTick` method
(§5) is `region.ownedChunkSnapshot()`.

### 4.4 Test coverage for the foundation

`ChunkHolderManagerOwnershipTest` (new,
`multiforge-runtime/src/test/java/net/multiforge/runtime/chunk/`):
regionize 100 chunks across 4 regions (matching the shape of existing
`ChunkHolderManager` tests), assert `holdersOwnedBy(region)` for each
of the 4 regions returns exactly the chunks whose `owningRegion`
matches, with no chunk appearing in two regions' results and no chunk
missing from all four. A second case drives a region split and merge
through `ChunkHolderManager.onRegionSplit` / `onRegionMerged` and
re-asserts the same invariant afterward, including for
`blockEntityTickers` (§4.2) — this is the regression test that would
have caught a ticker staying registered under its old region after a
split moved its chunk to a new one.

---

## 5. Per-subsystem migration plan

Each of the three phase bodies below follows the same four-part
pattern (per the landing plan's B3.2/B3.3/B3.4 task shape):

1. A new static-ish method on `MultiThreadedSchedulerHost` —
   `phaseXxxTick(Region region)` — that walks
   `region.ownedChunkSnapshot()` (§4.3) and invokes the per-chunk /
   per-level work in the current region worker's context.
2. Wired into `installM9WiredTickBody`
   (`MultiThreadedSchedulerHost.java:560-604`) via
   `.append(Phase.XXX, this::phaseXxxTick)` — **appended**, never
   `.set`, matching the existing convention at
   `MultiThreadedSchedulerHost.java:600-602` (`BLOCK_ENTITIES` already
   carries `phaseGlobalSystemsTick`; B3.4 must layer on top of it, not
   replace it — see §5.3).
3. A thin patch hunk under `multiforge-patches/02-region-tick/`
   (extending its existing scope) that extracts the relevant Vanilla
   method body into a new `mfXxxBody()` public method on `ServerLevel`
   — the same "wrap-and-rename" shape the B2 globals patches already
   use for the eight `xxxReady()`-guarded subsystem methods. The
   original `tick(BooleanSupplier)` call site keeps calling
   `mfXxxBody()` only when `RegionizedTickCoordinator` reports the
   region-worker path is *not* handling it (fallback stays live until
   B3.5 deletes the fallback entirely).
4. A guard at the top of the region-worker-side implementation reading
   `OwnerToken.current().domain()` to confirm the call is running on
   the owning region's own worker thread; a mismatch auto-reroutes and
   warns (CLAUDE.md rule 5) rather than ticking foreign state.

### 5.1 BLOCK_FLUID_TICKS (B3.2)

**Vanilla site.** `ServerLevel.getBlockTicks().tick(65536, tickBlock)`
and `ServerLevel.getFluidTicks().tick(65536, tickFluid)` —
`ServerLevel.java:363-371` — the two `LevelTicks<Block>` and
`LevelTicks<Fluid>` instances Vanilla drains every tick, bounded at
65536 scheduled ticks per call per level.

**Per-region operation.** `phaseBlockFluidTicks(Region region)` walks
`region.ownedChunkSnapshot()` and, for each owned `ChunkPos`, drains
only the scheduled block/fluid ticks whose position falls inside that
chunk. Vanilla's `LevelTicks` container is a single per-level
structure keyed by tick time, not by chunk — so the per-region
operation has two viable shapes, and B3.2's implementation task picks
one at patch time:

- **(a) Split-by-region:** maintain a per-region `LevelTicks`
  instance (mirroring the `blockEntityTickers` split in §4.2), fed by
  a chunk-ownership-aware scheduling call whenever a block/fluid tick
  is scheduled (`ServerLevel.scheduleTick` and friends). Pro: drains
  are O(this region's scheduled ticks) with no filtering pass. Con:
  every `scheduleTick` call site needs to resolve the target chunk's
  owning region at schedule time, which is more invasive.
- **(b) Filter-at-drain:** keep Vanilla's single per-level
  `LevelTicks`, but change the drain call itself to accept a chunk
  predicate (`region.ownedChunkSnapshot()`-backed), so each region's
  drain only consumes entries matching its own owned chunks. Pro:
  minimal change to the scheduling side. Con: every region's drain
  pass still has to walk (a bounded slice of) the full level's
  scheduled-tick structure, which reintroduces a shared-structure
  contention point across regions unless the container itself is
  internally partitioned.

This document does not pick between (a) and (b) — that is B3.2's own
implementation decision, informed by whichever keeps `LevelTicks`'
existing scheduling API (used pervasively by block behaviors —
redstone, crop growth, liquid flow) intact with the smallest patch
footprint. Whichever shape is chosen, the per-region drain must still
respect Vanilla's 65536-per-call cap *per region*, not per level (a
region's worker budget is independent of its neighbors').

**Concurrency invariants.** No two regions may drain overlapping
scheduled-tick entries in the same server tick (would double-apply a
block/fluid update); no region's drain touches a
`BlockPos`/`ChunkPos` outside its own `ownedChunkSnapshot()`.

**Gate condition.** `ThreadedRegionizer`'s existing chunk-ownership
resolution (the same one `ChunkHolderManager.holdersOwnedBy` reads,
§4.1) — a block/fluid tick only fires if its position's chunk is
currently owned by the ticking region. No separate "should tick
blocks at this position" gate exists beyond ownership for this phase
(unlike ENTITY_AI's distance-based ticking-range gate, §5.2) — Vanilla
schedules block/fluid ticks only for loaded chunks already, and
per-region ownership already implies loaded.

### 5.2 ENTITY_AI (B3.3)

**Vanilla site.**
`entityTickList.forEach(this::checkDespawn); entityTickList.forEach(guardEntityTick(this::tickNonPassenger))`
at `ServerLevel.java:400-426` — the despawn check pass followed by the
guarded per-entity AI/physics tick pass. `guardEntityTick` wraps each
call so one entity's exception doesn't abort the whole list (Vanilla's
own per-entity isolation, orthogonal to and layered under B3's
region-level isolation from §3).

**Per-region operation.** `phaseEntityAiTick(Region region)` walks
`region.ownedChunkSnapshot()`, resolves the set of entities whose
current chunk falls in that snapshot, and runs the same two-pass
shape Vanilla uses (`checkDespawn`, then guarded `tickNonPassenger`)
over exactly that set. The entity set itself is **not** re-derived by
scanning every chunk's entities fresh each tick — it is maintained
incrementally by the existing chunk-load/unload and
entity-move-across-chunk-boundary hooks the M9 chunk-system port
already installs (`RegionizedChunkLifecycle`), the same way Vanilla's
own `entityTickList` is maintained incrementally rather than
recomputed per tick.

**Gate condition.** `DistanceManager.inEntityTickingRange` — Vanilla's
real ticking-range gate (a chunk must be at
`ChunkLoadLevel.ENTITY_TICKING` or higher for its entities to tick,
not merely loaded). This is the same gate Vanilla's own
`tickNonPassenger` path checks; B3.3 does not introduce a new gate,
it re-evaluates the existing one per-region instead of per-level. Per
`MultiForgeDistanceManager`'s existing per-region ticket routing
(CLAUDE.md's M9 convention #2), the ticking-range determination itself
is already per-region-scoped — B3.3 consumes that result rather than
recomputing it.

**Concurrency invariants (the two load-bearing ones for this phase):**

- **No entity is ticked twice per server tick.** An entity belongs to
  exactly one region at any instant — the region owning the chunk its
  current position resolves to. `region.ownedChunkSnapshot()` for two
  distinct regions is disjoint by construction (`ChunkHolderManager`'s
  `owningRegion` field on each `NewChunkHolder` is single-valued), so
  two regions' `phaseEntityAiTick` calls cannot both claim the same
  entity in the same tick — *provided* no entity migration is
  in-flight for that entity concurrently with this phase running (see
  §9 for why cross-region entity migration itself is out of scope for
  B3 and left to M4).
- **No entity is ticked on a thread that isn't its owning region's
  worker.** This is the `OwnerToken.current().domain()` guard from
  §5's part 4. If a region worker's `ownedChunkSnapshot()` somehow
  contains a chunk it does not actually own by the time the phase
  runs (a stale snapshot raced by a concurrent split/merge — see §6),
  the guard catches the mismatch, warns via `OwnershipEnforcer`
  (matching the landing-plan task note's exact wording — "if a region
  worker sees a foreign entity in its iteration, that's an M4
  migration bug and warns via `OwnershipEnforcer`, does not tick"),
  and skips that entity rather than ticking foreign state.

### 5.3 BLOCK_ENTITIES per-region (B3.4)

**Vanilla site.** `Level.tickBlockEntities()` at `ServerLevel.java:428`
— iterates `Level.blockEntityTickers`, a single per-level list.

**Per-region operation.** `phaseBlockEntitiesTick(Region region)`
walks `HolderManagerRegionData.blockEntityTickers` (§4.2) for this
region — **not** `region.ownedChunkSnapshot()` directly, since the
per-region ticker list is already split by chunk membership at the
point a chunk's block entities are registered (chunk load) or a
ticker is added/removed (block placed/broken). Walking the
pre-split list avoids re-deriving chunk-to-ticker membership on every
tick, matching how Vanilla's own `blockEntityTickers` list is itself
maintained incrementally rather than rebuilt per tick.

**BLOCK_ENTITIES phase composition.** This phase already carries
`phaseGlobalSystemsTick`
(`MultiThreadedSchedulerHost.java:600`,`:629-636`), which early-returns
for every region except the synthetic global region
(`MultiThreadedSchedulerHost.java:630`). B3.4's new
`phaseBlockEntitiesTick` is a **second** body appended into the same
phase slot, wired as:

```java
PhasedRegionTickBody.Builder wired = userBuilder
        .prepend(PhasedRegionTickBody.Phase.INBOUND_MAILBOX, this::phasePollFullLoadUpdate)
        .append(PhasedRegionTickBody.Phase.BLOCK_ENTITIES, this::phaseGlobalSystemsTick)
        .append(PhasedRegionTickBody.Phase.BLOCK_ENTITIES, this::phaseBlockEntitiesTick)
        .append(PhasedRegionTickBody.Phase.REGION_EVENTS, this::phaseDrainChunkTasks)
        .append(PhasedRegionTickBody.Phase.FLUSH_OUTBOUND, this::phaseAutoSave);
```

The two bodies do not conflict: `phaseGlobalSystemsTick` only ever
does real work for `globalRegion.id()`, and `phaseBlockEntitiesTick`
only ever does real work for a region with a non-empty
`blockEntityTickers` slice — the global region's synthetic single
chunk (`ChunkPos(0, 0)` on the synthetic `"multiforge:global"` world,
per `global-region.md` §1.1) never has ordinary block entities
registered against it, so the two bodies are naturally
mutually-exclusive in practice even though both run, in order, for
every region every tick. `.append` order matters for one reason:
`phaseGlobalSystemsTick` runs first so that any global-subsystem
mutation visible to a region's block-entity tickers this same tick
(unlikely in practice, but not structurally ruled out) is applied
before the region's own block entities tick — consistent with the
existing ordering rationale documented at
`MultiThreadedSchedulerHost.java:612-616` for why `BLOCK_ENTITIES`
runs after `INBOUND_MAILBOX` and before `REGION_EVENTS`.

**Gate condition.** A block entity ticks if (a) its owning
`NewChunkHolder`'s chunk is at `ChunkLoadLevel.TICKING` or higher
(Vanilla parity — block entities do not require full entity-ticking
range, only "ticking", a lower bar than `ENTITY_TICKING`) and (b) its
position resolves to *this* region per `owningRegion` (§4.1's
invariant). Each individual ticker call is wrapped the same way
Vanilla wraps `TickingBlockEntity.tick()` calls today (a
try/catch around each ticker, not around the whole batch) so one
broken block entity — a mod bug in a custom `BlockEntity#tick`
implementation — cannot stop the rest of the region's block entities
from ticking that pass; this mirrors `guardEntityTick`'s per-entity
isolation in §5.2 and CLAUDE.md rule 5's auto-reroute-and-warn
default.

**Concurrency invariant (the load-bearing one for this phase):** a
block-entity ticker whose block position resolves to region R is only
ever present in R's slice of `blockEntityTickers` — this is §4.2's
split/merge invariant restated as the runtime property B3.4's phase
body depends on. If a chunk changes owning region mid-tick (a split
or merge racing this phase), the ticker must not be ticked by two
regions' `phaseBlockEntitiesTick` calls in the same server tick, nor
dropped entirely — §6 states this as a global invariant and §4.2
states how split/merge maintains it.

---

## 6. Correctness invariants

These four invariants must hold end-to-end, across all three new
phase bodies and across a live region split or merge, not merely
within a single phase body in isolation:

1. **No entity is ticked twice per server tick.** Guaranteed by
   `ownedChunkSnapshot()` disjointness (§5.2) plus the
   `OwnerToken.current().domain()` guard rejecting a foreign-region
   tick attempt rather than proceeding.
2. **No entity is ticked on a thread that isn't its owning region's
   worker.** Guaranteed by the same guard — this is a stronger
   property than (1): even if two regions' snapshots somehow
   overlapped (a bug), the guard on the *ticking* side, not merely the
   iteration side, is what prevents the actual `tickNonPassenger`
   call from executing off the wrong thread.
3. **A block-entity ticker whose block position resolves to region R
   is only in R's slice of `blockEntityTickers`.** Guaranteed by
   §4.2's split/merge re-resolution against block position (not
   copied `NewChunkHolder` references) and by ticker
   registration/deregistration happening at chunk-load/block-place
   time, resolved against the chunk's *current* owning region at that
   moment.
4. **When a region splits or merges, per-region tickers are
   re-distributed atomically.** `ChunkHolderManager.onRegionSplit` /
   `onRegionMerged` (`ChunkHolderManager.java:346-368`) already run
   under the regionizer's own split/merge coordination — the same
   call that moves `NewChunkHolder.owningRegion` also folds/peels
   `HolderManagerRegionData`, including the new `blockEntityTickers`
   field (§4.2), in the same method invocation. There is no window
   where a chunk's `owningRegion` has moved but its block-entity
   tickers have not (or vice versa) — both transitions happen inside
   the same `onRegionSplit`/`onRegionMerged` call, under whatever lock
   or single-threaded-coordinator guarantee `ThreadedRegionizer`
   already provides for split/merge (the same guarantee M9's
   chunk-ownership transfer already relies on — B3 introduces no new
   locking primitive here, it reuses the existing one).

A region split or merge racing a phase body **mid-tick** (not just
between ticks) is explicitly out of scope for a data race the phase
body itself must detect: `ThreadedRegionizer` split/merge operations
are themselves synchronized against the tick loop (a split/merge does
not execute concurrently with that same region's own
`PhasedRegionTickBody.tickOnce`), so `region.ownedChunkSnapshot()`
taken at the top of a phase body is guaranteed stable for the
duration of that phase body's execution. This is the same "snapshot,
not tick-hot" design note from §4.1 — the snapshot's staleness window
is bounded by "since the top of this phase," not "since some
arbitrary earlier point," precisely because split/merge cannot
interleave with a single region's own tick.

---

## 7. Test strategy

Extends `PhasedRegionTickBodyWiringTest`'s existing pattern
(`multiforge-runtime/src/test/java/net/multiforge/runtime/region/
PhasedRegionTickBodyWiringTest.java:52-67` — construct a
`MultiThreadedSchedulerHost` with the scheduler's worker pool shut
down up-front so tests can drive `body.tickOnce(region)` by hand
without racing the pool, per the class's own `@BeforeEach` comment at
`:56-61`) for each of the three new phases:

- **BLOCK_FLUID_TICKS:** seed two regions each owning a distinct
  chunk with a pending scheduled block tick; drive one tick on each
  region; assert each region's drain only consumed its own chunk's
  scheduled tick and left the other region's untouched. A second case
  schedules a tick against a chunk *not* owned by the ticking region
  (simulating a stale hint) and asserts it is not consumed.
- **ENTITY_AI:** seed two regions each owning an entity in an
  `ENTITY_TICKING`-range chunk; drive one tick on region A only;
  assert region A's entity ticked (a test double `Entity` recording a
  tick counter) and region B's entity did not. A second case places
  an entity in a chunk below `ENTITY_TICKING` range and asserts it is
  skipped (gate condition, §5.2) without erroring.
- **BLOCK_ENTITIES per-region:** extends the existing global-region
  case (`phaseGlobalSystemsTick` already has coverage per
  `global-region.md` §8) with a second assertion that a *non-global*
  region with a registered `TickingBlockEntity` in its
  `HolderManagerRegionData.blockEntityTickers` slice ticks it, and
  that `phaseGlobalSystemsTick`'s early-return for that same region
  is unaffected (both bodies present in the phase, only one does real
  work).
- **Split/merge redistribution (`ChunkHolderManagerOwnershipTest`,
  §4.4):** the primary regression coverage for invariant 4 (§6) —
  split a region with a live block-entity ticker and a pending
  scheduled block tick mid-simulation, assert both land in the
  correct post-split region's per-region data, then merge back and
  assert both are restored to a single region's data with no
  duplication and no loss.
- **Cross-phase ordering:** extend the existing
  `pollFullLoadUpdateFiresInInboundMailboxPhase` test's `observed`
  list pattern (`PhasedRegionTickBodyWiringTest.java:83-107`) to
  include the three new phase bodies, asserting the full six-phase
  order still holds with all of B3's wiring installed simultaneously.

None of these tests require the MC-dependent
`upstream/neoforge-1.21.1` module — they exercise
`multiforge-runtime` in isolation the same way the existing
`PhasedRegionTickBodyWiringTest` does, using test-double entities and
block entities rather than real Vanilla types. B3.6's live-smoke pass
(spawn 50 mobs, place a hopper, place a redstone repeater, 5-minute
soak with zero `RegionTickOverrunException`) is the integration-level
check that ties this unit coverage back to real Vanilla behavior; it
is out of scope for this design document but is listed in the landing
plan as B3.6.

---

## 8. The `dispatchLevelTick` refactor (B3.5)

B3.5 replaces the entire method body per §2's frozen shape, once
B3.2, B3.3, and B3.4 have all landed and are independently verified
(each is a parallel, independent phase-body addition — B3.5 is the
convergence point that finally removes the fallback). Concretely:

- **Signature change:** `dispatchLevelTick(ServerLevel level, Runnable vanillaBody)`
  becomes `dispatchLevelTick(ServerLevel level)`. Every call site
  (the patched `MinecraftServer.tickChildren` hunk in
  `multiforge-patches/02-region-tick/net/minecraft/server/
  MinecraftServer.java.patch`) updates to match — this is the one
  patch-file edit B3.5 must make outside `RegionizedTickCoordinator.java`
  itself.
- **Bootstrap fallback** (`host == null`, today's
  `RegionizedTickCoordinator.java:131-135`): becomes
  `ProbeRegistry.bump("region-tick.bootstrap-skip")` +
  `ViolationLogger.warn("region-tick.dispatch.no-runtime", ...)` +
  `return` — log-and-skip, not silent, per §2 point 2. This path is
  expected to be should-never-happen in steady state (the same
  argument `global-region.md:730` makes for the pre-B2 version of this
  same branch) but must not regress to a bare `return` with no probe
  or warning if it ever does fire (e.g. a future lifecycle-ordering
  bug).
- **No-regionizer fallback** (`regionizer == null`, today's
  `RegionizedTickCoordinator.java:138-143`): same log-and-skip shape,
  distinct probe key `region-tick.dispatch.no-regionizer` (already the
  `ViolationLogger` key used today; B3.5 adds the matching
  `ProbeRegistry.bump` call, which today's version does not have on
  this branch — only the `region-tick.dispatch.failure` and
  `region-tick.dispatch.overrun` branches currently bump a probe).
- **Dispatch-failure fallback** (`tickAll` throws, today's
  `RegionizedTickCoordinator.java:148-162`): keeps its existing
  `ProbeRegistry.bump("region-tick.dispatch.failure")` and
  `ViolationLogger.warn` calls, but drops the trailing
  `vanillaBody.run()` — the catch block's last line becomes a comment
  noting there is no inline fallback left to run, matching §2's
  reproduced target shape exactly.
- **No trailing `vanillaBody.run()`.** The unconditional call at
  today's line 193, along with the multi-line comment above it
  explaining why it is still needed (`RegionizedTickCoordinator.java:
  177-192`, including the /67 round-6 finding note, §1.2), is deleted
  in full. By the time B3.5 lands, that comment's premise (BLOCK_FLUID_TICKS
  and ENTITY_AI have no production wiring) is false, so the comment
  itself becomes the thing that would mislead a future reader if left
  in place.
- **Class Javadoc update.** The class-level Javadoc block
  (`RegionizedTickCoordinator.java:30-63`), which currently describes
  the "Post-M5 state (v1.2.0)" with the trailing inline call, is
  rewritten to describe the post-B3 state: no `vanillaBody` parameter,
  three log-and-skip fallback paths, full per-region dispatch of
  entity AI, block entities, and scheduled block/fluid ticks via the
  `PhasedRegionTickBody` phases wired in B3.2/B3.3/B3.4.
- **`RegionDispatchOverrunException`** and its strict-mode handling
  (`RegionizedTickCoordinator.java:164-175`, `:216-243`) are
  unaffected by B3.5 — the overrun-detection and strict/warn split is
  orthogonal to the `vanillaBody` removal and stays exactly as it is
  today.

The exit-gate check for B3.5 is the same one `global-region.md:796-799`
already specifies: `git grep vanillaBody
upstream/neoforge-1.21.1/src/main/java/net/multiforge/` must return
empty.

---

## 9. Deferred / non-goals

Two pieces of the original Vanilla per-level tick body are explicitly
**not** covered by B3, and are called out here so a future reader does
not assume B3's completion implies full per-region parity with
Vanilla's `ServerLevel.tick`:

### 9.1 Per-region `serverChunkCache.tick` — deferred to M14

Vanilla's chunk source (`ServerChunkCache`) does its own per-tick
work independent of the block/fluid/entity/block-entity passes B3
covers — chunk generation/loading progress, player chunk-view-distance
bookkeeping, and (in Vanilla) natural mob spawning. The M9
chunk-system port already forks the chunk *map*, *distance manager*,
and *light engine* pieces of this (per CLAUDE.md's M9 conventions),
but the chunk source's own tick method is a separate, larger surface
that touches spawning heuristics and world-generation scheduling in
ways that don't cleanly decompose into a single new
`PhasedRegionTickBody` phase without their own design pass. This is
material for a future milestone (tracked as M14 in the current
milestone numbering) and is out of scope for B3.

### 9.2 Cross-region entity migration — M4's responsibility, not B3's

B3.3's ENTITY_AI phase body (§5.2) assumes an entity's owning region
is authoritative at the moment that region's phase body runs — it
does not itself move an entity from one region to another when the
entity's position crosses a region boundary mid-tick. That migration
mechanism (detecting the crossing, handing the entity's state to the
destination region's worker, updating `owningRegion` bookkeeping
atomically) is `docs/design/entity-migration.md`'s scope, tracked
under M4. B3 depends on M4's existing guarantees (an entity is always
resolvable to exactly one owning region at the start of any given
tick) rather than re-implementing them; the `OwnershipEnforcer` warn
path in §5.2's second concurrency invariant exists specifically to
catch the case where that guarantee is violated (an M4 bug, not a B3
one) without crashing the region worker that observes it.

---

## 10. Companion correction to `docs/blueprint.md`

`docs/blueprint.md`'s M8 section, sub-step 6b
(`docs/blueprint.md:512-516` at freeze time), is corrected in the same
commit as this document. The prose claiming sub-step 6b is "DONE" and
"decomposed `ServerLevel.tick`'s per-chunk work (block/fluid ticks,
entity iteration, block-entity iteration) so each region owns its
slice" is factually wrong per §1.1 and §1.3 above — the coordinator's
own source comments contradict it, and this document exists precisely
because that decomposition had not happened. The corrected prose marks
sub-step 6b **PARTIAL**, points to this document for the completing
work, and clarifies that what 6b actually delivered was the
`TickRegionScheduler.tickAll(...)` barrier/fan-out mechanism — real
per-region dispatch of the *scheduling* concern, not of the per-chunk
tick *work* itself, which remained (and, until B3.2/B3.3/B3.4 land,
still remains) inline in `vanillaBody.run()`.
