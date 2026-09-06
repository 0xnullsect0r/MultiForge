# Global-Region Contract — Design

**Status:** Phase 0 task 0.2 of the M4+M5+M6 landing plan
(`plans/bubbly-jumping-comet.md`). Freezes the contract that Track B
(M5 Global Subsystems, `multiforge-patches/08-globals/`) implements
against; changes to any signature below require a Phase 0 amendment
and a re-review of every Track B task that depends on it.

**Companion docs:** `docs/design/entity-migration.md` (Phase 0.1 —
`Entity.setPosRaw` hop that B2.6 Raids and B2.7 Dragon depend on for
spawning entities into a region they don't own from global code).

**Non-goals:** entity migration semantics (0.1), client debug protocol
(0.3), mod-safety scanner rules (0.4). This document covers only: the
synthetic global region's current wiring, the new phase-4 subsystem
tick slot, the `GlobalSystem` contract, the `TicketType.DRAGON`
schema, the plan to retire `RegionizedTickCoordinator`'s inline
`vanillaBody.run()` calls, failure semantics, and test invariants.

Cite conventions: `file:line` refers to the repo at freeze time
(2026-09-06, `develop` @ `2078327`). `net.mf.rt.*` =
`net.multiforge.runtime.*` (pure Java, no Minecraft dependency,
lives under `multiforge-runtime/src/main/java/`); `net.mf.nf.*` =
`net.multiforge.neoforge.*` (the fork facade, lives under
`upstream/neoforge-1.21.1/src/main/java/`, may depend on
`net.minecraft.*`). Vanilla source line numbers refer to the vendored
tree at `upstream/neoforge-1.21.1/`.

---

## 1. The synthetic global region today

### 1.1 Construction

`MultiThreadedSchedulerHost`'s constructor materialises the global
region eagerly, before any per-world regionizer exists
(`MultiThreadedSchedulerHost.java:170-196`):

```java
WorldRef globalWorld = WorldRef.of("multiforge:global");
this.globalRegionizer = new ThreadedRegionizer(globalWorld, 0);
this.globalRegionizer.addListener(this.scheduler);
this.globalRegionizer.addListener(this.taskQueue);
this.globalRegionizer.addListener(newRegionWorldTracker(globalWorld));
regionizers.put(globalWorld.dimensionId(), globalRegionizer);
this.globalRegion = globalRegionizer.addChunk(new ChunkPos(0, 0));
scheduler.register(globalRegion);
```

Three things worth calling out, because Track B code will build on all
three: it is a **real `Region`**, not a special-cased object —
`globalRegion` is whatever `ThreadedRegionizer.addChunk` returns, the
same type every per-world region is, wired through the same listeners
(`scheduler`, `taskQueue`, the region→world tracker) and registered
with `TickRegionScheduler` the same way (`:196`); it lives under a
**synthetic `WorldRef`** — `"multiforge:global"` is registered into
the same `regionizers` map every other world's regionizer lives in
(`:191`), so `RegionizedTaskQueue.queueChunkTask(GLOBAL_WORLD, ...)`
(§1.4) resolves through the exact same code path as any other
cross-region enqueue, no bespoke "global inbox" type; and the
**class-level javadoc is aspirational, not literal** — it says "Global
work runs on a dedicated single-thread executor" (`:56-58`), but
`TickRegionScheduler` has no such special case (`register`/`tickAll`
treat every `Region` identically, `TickRegionScheduler.java:100`,
`:179`); the global region gets whichever pool worker picks it up,
same as any other. The field-declaration comment (`:132-134`, "a
synthetic region pinned to the pool, ticking like any other region")
is the accurate one; a dedicated single thread is out of scope for M5.

### 1.2 Region-id and regionizer shape (0-shift, single-section)

`new ThreadedRegionizer(globalWorld, 0)` passes
`sectionChunkShift = 0`. Per `ThreadedRegionizer`'s own contract
(`ThreadedRegionizer.java:32-34`), `2^sectionChunkShift` chunks per
side make up one section — with shift `0` that's `2^0 = 1`, i.e. a
section is exactly one chunk. `addChunk(new ChunkPos(0, 0))` then
creates exactly one region containing exactly one section containing
exactly one chunk: chunk `(0, 0)` in the `multiforge:global` world.

This is the smallest, simplest regionizer configuration the class
supports (compare a real world's regionizer, constructed via
`regionizerFactory = world -> new ThreadedRegionizer(world,
config.regionSize())`, `MultiThreadedSchedulerHost.java:143`, where
`config.regionSize()` is the real per-world section shift used for
merge/split). Consequences:

- **Never splits** — split requires disconnected sections; with
  exactly one section, split can never fire.
- **Never merges** — nothing else ever calls
  `globalRegionizer.addChunk` for a second chunk.
- **Stable `RegionId` for the life of the JVM** — `globalRegion` is a
  `final Region` field (`:134`) assigned once at host-construction
  time; any lookup of `regionAtChunk(0, 0)` in world `multiforge:global`
  returns the same `Region`/`RegionId` for the whole server run.

Track B code should treat `globalRegion.id()` as constant per host
instance, but fetch it *through* the host (§4.1 adds an accessor)
rather than caching across host restarts — a `GameTestServer` reusing
one JVM gets a *new* host, and hence a new global `RegionId`, per
instance (`MultiForgeRegionizedRuntime.AlreadyInstalledException`'s
javadoc, `MultiForgeRegionizedRuntime.java:33-49`).

### 1.3 Tick cadence

The global region ticks at the same cadence as every other region:
once per `TickRegionScheduler.tickAll(regions, deadlineNanos)` sweep,
which `RegionizedTickCoordinator.dispatchLevelTick` drives once per
vanilla per-level tick call
(`RegionizedTickCoordinator.java:127-130`). `MultiThreadedSchedulerHost.TICK_MS
= 50L` (`MultiThreadedSchedulerHost.java:68`) is the 20 TPS target
every region — global included — is budgeted against; the dispatch
deadline the coordinator enforces defaults to 500 ms
(`RegionizedTickCoordinator.DEFAULT_DISPATCH_DEADLINE_MS`,
`RegionizedTickCoordinator.java:51`) and is configurable via
`-Dmultiforge.regiontick.dispatch-ms`.

One nuance Track B must not get wrong: `dispatchLevelTick` is called
once per loaded `ServerLevel` per vanilla tick — a server with 3
dimensions calls it 3 times per game tick — but its `regions` variable
is `host.regionizerForOrNull(world).regions()` for the *ticked*
level's own regionizer only (`:127`); the global region's separate
`multiforge:global` regionizer (§1.1) is never a member. So today
nothing drives the global region's tick from `dispatchLevelTick` at
all — see §1.5, closed by §2.3's tick-once fix.

### 1.4 What already runs on the global region

The only production consumer of the global region today is
`GlobalDomainImpl` (`MultiThreadedSchedulerHost.java:730-768`), the
`GlobalDomain` binding returned by `SchedulerHost.global()`
(`MultiThreadedSchedulerHost.java:669-671`). It is a public-API
one-off/repeating task scheduler for mods, not a subsystem tick slot:
`run`, `runDelayed`, and `runAtFixedRate` all funnel through
`enqueueOnGlobal`:

```java
private void enqueueOnGlobal(Runnable r) {
    taskQueue.queueChunkTask(globalRegionizer.world(), 0, 0, r);
}
```

(`MultiThreadedSchedulerHost.java:777-778`). This puts `r` into the
global region's inbox via the same `RegionizedTaskQueue` every
cross-region task uses — there is no bespoke "global queue" type. The
global region's `INBOUND_MAILBOX` phase body drains this inbox exactly
like any region drains its own mailbox (`TickRegionScheduler`'s
before/after-tick drain, per `PhasedRegionTickBody`'s own javadoc,
`PhasedRegionTickBody.java:19-21`).

This mechanism is orthogonal to the `GlobalSystem` subsystem contract
this document defines (§3): `GlobalDomain` is a mod-facing scheduler
API for arbitrary ad-hoc work; `GlobalSystem` is a small, fixed,
registration-time set of *engine* subsystems (weather, time, border,
dragon, ...) that must run unconditionally every global tick. Both
execute on the same region/thread but have different registration
models and failure-isolation requirements (§7).

### 1.5 The gap: nothing ticks the global region today, and `GlobalSystems.tickAll()` is never called

Two independent gaps exist as of this freeze, both confirmed by
reading the code, not inferred from the plan:

1. **The global region is never included in any `tickAll(regions,
   ...)` sweep.** `dispatchLevelTick` only ever fetches
   `regionizer.regions()` for the vanilla `ServerLevel` being ticked
   (`RegionizedTickCoordinator.java:118-127`) — never `host`'s
   `multiforge:global` regionizer. So the global region's
   `INBOUND_MAILBOX` phase (and hence `GlobalDomain` tasks) only
   drains if something else ticks it, and nothing does in production.
   §2.3 closes this.
2. **`GlobalSystems.tickAll()` (`GlobalSystems.java:55-65`) has zero
   call sites outside its own unit test** (`grep -rn "GlobalSystems\."
   multiforge-runtime upstream` matches only `GlobalSystemsTest.java`).
   The registry and the `GlobalTicker` functional interface are
   correctly shaped — Track B does not redesign them from scratch —
   but nothing holds a `GlobalSystems` instance or invokes `tickAll()`
   from the tick pipeline. Exactly the gap B1.1 closes; §2–§4 spec the
   fix.

---

## 2. `GlobalTicker.tickAll(GlobalTickContext)` — the phase-4 slot

### 2.1 The six-phase ladder, recapped

`PhasedRegionTickBody` (`PhasedRegionTickBody.java:17-31`) composes
every region's tick out of six ordered phases, run in `Phase.values()`
order with each phase wrapped in its own try/catch
(`PhasedRegionTickBody.java:57-67`):

| # | `Phase` enum constant | Vanilla-parity purpose | Wired today (M9 Phase 5) |
|---|---|---|---|
| 1 | `INBOUND_MAILBOX` | apply queued inbound tasks | `phasePollFullLoadUpdate` prepended (`MultiThreadedSchedulerHost.java:468`) |
| 2 | `BLOCK_FLUID_TICKS` | scheduled block/fluid updates | not yet wired by M9; M8 patches target it |
| 3 | `ENTITY_AI` | entity iteration, AI, physics | not yet wired by M9; M8 patches target it |
| 4 | `BLOCK_ENTITIES` | block-entity iteration | empty — no-op today |
| 5 | `REGION_EVENTS` | per-region NeoForge events + tasks | `phaseDrainChunkTasks` appended (`MultiThreadedSchedulerHost.java:469`) |
| 6 | `FLUSH_OUTBOUND` | deliver outbound cross-region messages | `phaseAutoSave` appended (`MultiThreadedSchedulerHost.java:470`) |

Every region — global included — runs all six phases every tick;
empty slots are silent no-ops (`PhasedRegionTickBody.java:35`,
the `NOOP` constant).

### 2.2 Why phase 4 (`BLOCK_ENTITIES`)

`BLOCK_ENTITIES` is the correct slot for the global-subsystem tick,
for three independent reasons:

1. **Semantically vacant for the global region.** The global region's
   one chunk (`multiforge:global`, `(0, 0)`) has no vanilla block
   entities and never will. Repurposing `BLOCK_ENTITIES` costs nothing
   — no existing behavior on the global region uses it (§1.5 point 2),
   and no other region's `BLOCK_ENTITIES` wiring is touched, since the
   repurposing is scoped to the global region specifically (§2.3).
2. **Sits exactly where the rationale requires: post-phase 1,
   pre-phase 5.** Phase 1 (`INBOUND_MAILBOX`) runs
   `phasePollFullLoadUpdate`, draining
   `HolderManagerRegionData.pollFullLoadUpdate()` so any ticket the
   *previous* tick added/removed (a hypothetical `DRAGON` ticket, §5
   included) has already had its load-level transition observed by
   phase 4 (`MultiThreadedSchedulerHost.java:517-519`). Phase 5
   (`REGION_EVENTS`) is per-region NeoForge event dispatch; global
   subsystems must run before that so a same-tick phase transition
   (e.g. `DragonFightSystem` adding/removing a ticket) is visible to
   `REGION_EVENTS` handlers the same tick, not lagging by one.
3. **No new enum constant, no ripple through every phase consumer.**
   A seventh `Phase` value would touch `PhasedRegionTickBody.Builder`'s
   convenience methods, every `phasesSnapshot()` consumer, and
   (CLAUDE.md rule 6) every M8 patch already targeting a phase by
   ordinal. Repurposing an already-vacant slot avoids all of that.

"Phase 4" means the 1-indexed position in `Phase.values()` —
`BLOCK_ENTITIES` is the 4th entry. Track B code should refer to it as
`PhasedRegionTickBody.Phase.BLOCK_ENTITIES`, never a bare ordinal.

### 2.3 Wiring shape

B1.1 adds a `GlobalSystems` instance to `MultiThreadedSchedulerHost`,
constructed alongside `globalRegion`/`globalRegionizer`
(`MultiThreadedSchedulerHost.java:132-136`):

```java
// New field, constructed alongside globalRegion/globalRegionizer:
private final GlobalSystems globalSystems = new GlobalSystems();
public GlobalSystems globalSystems() { return globalSystems; }

// installM9WiredTickBody's phase wiring gains one more entry:
PhasedRegionTickBody.Builder wired = userBuilder
        .prepend(PhasedRegionTickBody.Phase.INBOUND_MAILBOX, this::phasePollFullLoadUpdate)
        .set(PhasedRegionTickBody.Phase.BLOCK_ENTITIES, this::phaseGlobalSystemsTick) // NEW — B1.1
        .append(PhasedRegionTickBody.Phase.REGION_EVENTS, this::phaseDrainChunkTasks)
        .append(PhasedRegionTickBody.Phase.FLUSH_OUTBOUND, this::phaseAutoSave);

// BLOCK_ENTITIES phase body, global region only — every non-global region
// sees a no-op (checked on RegionId, stable per §1.2, not on world):
private void phaseGlobalSystemsTick(Region region) {
    if (!region.id().equals(globalRegion.id())) return;
    GlobalTickContext ctx = new GlobalTickContext(globalSystems.currentTick() + 1, region.id());
    globalSystems.tickAll(ctx);
}
```

The phase-4 body is wired the same way M9's Phase 5 wave A wired its
own phases, via `PhasedRegionTickBody.Builder`, but with `.set` (not
`.append`/`.prepend`) scoped to fire only when the ticked region is
the global region — `set`/`prepend`/`append` apply to every region
sharing the one `PhasedRegionTickBody`
(`scheduler.setBody(wired.build())`, `:471`). `.set` is correct
because, unlike
`INBOUND_MAILBOX`/`REGION_EVENTS`/`FLUSH_OUTBOUND`, no M8 patch wires
anything into `BLOCK_ENTITIES` for *any* region as of this freeze
(§2.1 table) — nothing to compose with. If a future patch does start
wiring real block-entity ticking into `BLOCK_ENTITIES`, that wiring
and `phaseGlobalSystemsTick` are already mutually exclusive by
construction (the guard is on `region.id()`, and a real per-world
region is never `globalRegion`).

**Tick-once-per-game-tick fix (closing §1.5 point 1).** Because
`dispatchLevelTick` never includes the global region in its `regions`
collection, `phaseGlobalSystemsTick` must be reached some other way:
`MultiThreadedSchedulerHost` gains `tickGlobalRegionOnce()`, calling
`scheduler.tickAll(List.of(globalRegion), DISPATCH_DEADLINE_NANOS)`
exactly once per game tick — not once per level — from a new
top-level hook (analogous to vanilla's own single-per-tick
`tickChildren`) sitting before the per-level loop that invokes
`dispatchLevelTick`. Exact vanilla hook point is a Track B detail; the
frozen invariant is "exactly one `GlobalSystems.tickAll` call per game
tick, independent of loaded-level count" (§8.2 test 2).

### 2.4 `GlobalTickContext`

A small, pure-Java, immutable carrier — lives in `net.mf.rt.globals`
next to `GlobalSystems`/`GlobalTicker`:

```java
package net.multiforge.runtime.globals;

import net.multiforge.runtime.region.RegionId;

/**
 * Per-invocation context handed to every registered {@link
 * GlobalSystem#tick(GlobalTickContext)} call. Carries only what a
 * subsystem cannot derive on its own — no ServerLevel, no Minecraft
 * type, keeping this class (and the whole {@code globals} package)
 * usable from multiforge-runtime's MC-free test suite.
 */
public record GlobalTickContext(long globalTick, RegionId globalRegionId) {}
```

`globalTick` is the post-increment counter from
`GlobalSystems.currentTick()` (`GlobalSystems.java:67-69`) — the same
counter `GlobalTicker.tick(long)` already receives, just threaded
through a named record instead of a bare `long` so a future field
(a wall-clock timestamp, a "first tick since boot" flag `TimeSystem`'s
`synchronizeTime` parity needs) can be added without another
signature break.

### 2.5 Naming note — drift between the plan's phrasing and the actual types

The landing plan names the entry point
`GlobalTicker.tickAll(GlobalTickContext)`. Reading the actual code:
`tickAll` is (and remains) a method on the **registry**,
`GlobalSystems` (`GlobalSystems.java:55`) — `GlobalTicker` is the
*per-instance* functional interface (`GlobalTicker.java:17`, soon
superseded by `GlobalSystem`, §3). The call this document specifies is
`GlobalSystems.tickAll(GlobalTickContext ctx)`. Noted so nobody
searches for a `tickAll` method on `GlobalTicker` that was never going
to exist.

---

## 3. Subsystem interface — `GlobalSystem`

### 3.1 Interface

```java
package net.multiforge.runtime.globals;

import java.util.Set;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.RegionId;

// A fixed, registration-time-known engine subsystem that must run once
// every global tick on the synthetic global region. Contrast with
// net.multiforge.api.scheduler.GlobalDomain, the mod-facing ad-hoc task
// scheduler that also executes on the global region but has no fixed
// identity or read/write-set declaration. Implementations MUST be
// side-effect-free w.r.t. any region other than one reached through
// crossRegionEffect — never touch a ServerLevel/entity/block belonging
// to another region's chunks directly from tick() (CLAUDE.md rule 4,
// applied to the global region).
public interface GlobalSystem {
    String name();                        // stable, probe/log key (§3.2)
    void tick(GlobalTickContext ctx);      // main body, phase 4 (§3.3)
    Set<WorldRef> readSet();               // advisory only in M5 (§3.4)
    Set<WorldRef> writeSet();              // advisory only in M5 (§3.4)
    void crossRegionEffect(RegionId dest, Runnable task); // §3.5/§3.6
}
```

### 3.2 `name()`

Must be stable across restarts (probe/log key, §7.3; also the
debug-protocol subsystem identifier the M6 client HUD lists).
Convention: lower-`snake_case`, matching the anticipated-bindings list
already on `GlobalSystems` (`:18-28`): `weather`, `time`,
`world_border`, `scoreboard`, `boss_events`, `raids`, `dragon_fight`,
`command_dispatch`.

### 3.3 `tick(ctx)`

Runs once per global tick, at phase 4 (§2.2), in registration order
(`GlobalSystems`'s existing `CopyOnWriteArrayList` order, `:32`,
`:57`). Must complete within its share of the region's 50 ms budget
(CLAUDE.md rule 4) — no per-system deadline enforcement in M5 (unlike
`PHASE_CHUNK_TASK_DEADLINE_NANOS`'s 2 ms budget for
`phaseDrainChunkTasks`); a slow subsystem shows up as global-region
MSPT growth, already surfaced as an overrun by `RegionTickWatchdog`
like any other region (§8.2 test 4).

### 3.4 `readSet()` / `writeSet()`

Declares, per `WorldRef`, what a subsystem touches. In M5 this is
**advisory only** — nothing in `GlobalSystems.tickAll` consults these
sets to serialize, reorder, or reject conflicting subsystems. They
exist now so:

- the M6 debug HUD (`docs/design/client-debug-protocol.md`) can render
  a per-world "which global systems touch me" panel without needing a
  second registration channel;
- a future milestone can add real advisory conflict detection (e.g.
  warn if two subsystems declare overlapping `writeSet()`s) without an
  interface break — the data is already there, just unconsumed.

Example: `WeatherSystem` (B2.1) declares `readSet() == writeSet() ==
Set.of(<the one world it was constructed for>)` — one instance per
world, since vanilla weather state (`ServerLevel.advanceWeatherCycle`)
is per-`ServerLevel`. `TimeSystem` (B2.2) is per-world for
`ServerLevel.tickTime` but also touches every world for
`MinecraftServer.synchronizeTime`'s broadcast — its `writeSet()` is
"every currently-loaded world"; the exact shape is a Track B detail
this document does not pin further, since read/write sets are
non-enforced in M5 (§8.1 test invariant 8).

### 3.5 `crossRegionEffect` — the escape hatch

A `GlobalSystem` never has a live `ServerLevel`, `Region`, or
`RegionId` for the destination region handed to it implicitly — the
whole point of running on the global region is that it does *not* own
any real world chunks. `crossRegionEffect(RegionId dest, Runnable
task)` is the only sanctioned way a global subsystem reaches into a
specific region: through `RegionizedTaskQueue.queueChunkTask`, never a
direct reference to a `ServerLevel`/`Region` captured at construction
time (which could be stale by the time `tick()` runs, across a
merge/split — see `RegionizedTaskQueue.java:99-113`).

`crossRegionEffect` is an **instance method on `GlobalSystem`** (not a
static helper, not a method on `GlobalTickContext`) so a subsystem can
call it from contexts other than `tick()` if needed — but every M5
subsystem (B2.1–B2.8) calls it only from `tick()`. It is implemented
via delegation, not per-subsystem logic — see §3.7.

### 3.6 `CrossRegionEffects` — the resolution algorithm

`crossRegionEffect` needs (world, chunkX, chunkZ) to call
`RegionizedTaskQueue.queueChunkTask(WorldRef, int, int, Runnable)`
(`RegionizedTaskQueue.java:121`), but it is handed only a `RegionId`.
`MultiThreadedSchedulerHost` already tracks `RegionId → WorldRef` via
`regionToWorld` (`:96`, populated by the region-world tracker listener
installed on every regionizer). Combined with `Region.sections()`
(`Region.java:47`), `Region.sectionChunkShift()` (`Region.java:147`),
and `SectionPos`'s chunk-conversion shape (`SectionPos.ofChunk(chunkX,
chunkZ, shift) = (chunkX >> shift, chunkZ >> shift)`,
`SectionPos.java:17-19`, inverted here), the host can resolve any live
`RegionId` to a representative chunk:

```java
// Collaborator GlobalSystems hands to every registered GlobalSystem via
// its constructor, backing GlobalSystem#crossRegionEffect. enqueue is a
// no-op (logged, §7) if dest no longer resolves to a live region.
public interface CrossRegionEffects {
    void enqueue(RegionId dest, Runnable task);
}
```

Resolution algorithm (host-side, one instance per
`MultiThreadedSchedulerHost`, handed out via `globalSystems.effects()`,
§3.7): (1) `WorldRef world = regionToWorld.get(dest)` — `null` means
the region died since the subsystem last observed it, log + drop (§7),
never throw; (2) look up the live `Region` for that id via the world's
regionizer (`ThreadedRegionizer` doesn't expose "region by id" today;
Track B adds a small `regionById(RegionId)` accessor, O(sections)
worst case, acceptable off the tick-hot path); (3) take any one
`SectionPos sp` from `region.sections()` (deterministically, e.g.
minimum by `(x, z)` — correctness doesn't depend on which, since
`queueChunkTask` re-resolves ownership at drain time under
`ThreadedRegionizer`'s read lock, `RegionizedTaskQueue.java:120-134`);
(4) anchor chunk = `(sp.x() << shift, sp.z() << shift)`, the section's
origin chunk, guaranteed live; (5)
`taskQueue.queueChunkTask(world, chunkX, chunkZ, task)`. If the region
dies between steps 1 and 5, `queueChunkTask`'s read-lock discipline
(`RegionizedTaskQueue.java:99-113`) still lands the task somewhere
sane — the resolved region, or the orphan queue rerouted on next chunk
load. No crash path; matches CLAUDE.md rule 5.

### 3.7 `AbstractGlobalSystem` — convenience base

To avoid every one of the eight anticipated subsystems (B2.1–B2.8)
re-implementing the same `crossRegionEffect` delegation,
`net.mf.rt.globals` gains a small abstract base new `GlobalSystem`
implementations may (not must) extend:

```java
public abstract class AbstractGlobalSystem implements GlobalSystem {
    private final CrossRegionEffects effects;
    protected AbstractGlobalSystem(CrossRegionEffects effects) { this.effects = effects; }
    @Override public final void crossRegionEffect(RegionId dest, Runnable task) {
        effects.enqueue(dest, task);
    }
}
```

`GlobalSystems` exposes the `CrossRegionEffects` handle *before*
registration (`public CrossRegionEffects effects()`, host-backed impl
per §3.6) so subsystem constructors can capture it. Fork-side
construction (`net.mf.nf.globals.WeatherSystem` etc., B2.1) then
looks like:

```java
GlobalSystems systems = mfHost.globalSystems();
systems.register(new WeatherSystem(systems.effects(), overworldRef));
```

`crossRegionEffect` is `final` on the base class deliberately — a
subsystem needing a different cross-region strategy is a sign the
interface needs revisiting, not a reason to override.

---

## 4. Registration API

### 4.1 `GlobalSystems.register(GlobalSystem sys)`

`GlobalSystems` today only has `register(String name, GlobalTicker
ticker)` (`GlobalSystems.java:35-41`). Track B adds an overload — not
a replacement, since existing unit tests and any interim `GlobalTicker`
consumer keep working — for the richer contract:

```java
public Registered register(GlobalSystem sys) {
    Objects.requireNonNull(sys, "sys");
    Registered r = new Registered(sys.name(), sys::tick, sys); // ctx-aware tick
    tickers.add(r);
    return r;
}
```

(`Registered` grows an optional `GlobalSystem` back-reference, or
`GlobalSystems` maintains a second `List<GlobalSystem>` — an
implementation detail; the frozen contract is: `register(GlobalSystem)`
returns a handle `unregister` accepts, mirroring the existing
`register(String, GlobalTicker)` / `unregister(Registered)` pair,
`GlobalSystems.java:43-45`.)

`GlobalSystems` does not reject duplicate `name()`s
(`CopyOnWriteArrayList.add` never checks), and this document does not
change that — two subsystems under the same name both tick; the debug
HUD (M6) shows them as separate rows keyed by registration order. M5
does not need a uniqueness check since every B2.x name is a hardcoded,
distinct literal (§3.2).

### 4.2 Call site: `ServerLifecycleHooks.handleServerAboutToStart`

`handleServerAboutToStart` already installs the runtime and wires the
chunk serializer and chunk-lifecycle listeners in a clear sequence
(`ServerLifecycleHooks.java:93-187`). B2.x registration is a new block
appended immediately after the existing
`RegionizedChunkLifecycle.installOnEventBus()` call
(`ServerLifecycleHooks.java:179`), still inside the `mfHost != null`
guard (`ServerLifecycleHooks.java:172-175`) that already establishes
"the runtime is installed and reachable" for this method:

```java
if (mfHost != null) {
    mfHost.setChunkSerializer(net.multiforge.neoforge.io.RegionChunkSerializer::serializeForJournal);

    // MultiForge M5 (Track B): register global subsystems, in tick order
    // (GlobalSystems.java:57 iterates insertion order) — B2low before
    // the B2high systems that depend on A2's Entity.setPosRaw hop.
    net.multiforge.runtime.globals.GlobalSystems systems = mfHost.globalSystems();
    net.multiforge.runtime.globals.CrossRegionEffects effects = systems.effects();
    systems.register(new net.multiforge.neoforge.globals.WeatherSystem(effects));
    systems.register(new net.multiforge.neoforge.globals.TimeSystem(effects));
    systems.register(new net.multiforge.neoforge.globals.WorldBorderSystem(effects));
    systems.register(new net.multiforge.neoforge.globals.ScoreboardSystem(effects));
    systems.register(new net.multiforge.neoforge.globals.BossEventSystem(effects));
    systems.register(new net.multiforge.neoforge.globals.RaidsSystem(effects));
    systems.register(new net.multiforge.neoforge.globals.DragonFightSystem(effects));
    systems.register(new net.multiforge.neoforge.globals.CommandDispatchSystem(effects));
}
```

A patch against `net.neoforged.neoforge.server.ServerLifecycleHooks`
grouped under `08-globals/` per the patch-group table
(`multiforge-patches/README.md`). "After
`MultiForgeRegionizedRuntime.install()`" maps onto "inside the
`mfHost != null` block" — `mfHost` is non-null only once `install()`
has either freshly succeeded or been confirmed already-installed
(`:134-172`), exactly the point `mfHost.globalSystems()` is guaranteed
non-null (constructed in the host's own constructor, §2.3).

### 4.3 Ordering and idempotency

- **Registration order is tick order** (§4.1) — B2low systems
  (weather, time, border, scoreboard, boss events) are listed before
  B2high systems (raids, dragon, command dispatch) in §4.2: raids and
  dragon-fight phase transitions may enqueue `crossRegionEffect` work
  a same-tick `CommandDispatchSystem` invocation could plausibly want
  to observe as already-issued. Advisory, not a hard requirement — no
  B2.x task depends on strict ordering for correctness — but this
  document freezes it as the default so Track B doesn't bikeshed it
  per-PR.
- **`handleServerAboutToStart` can re-run in the same JVM**
  (`GameTestServer` reuse, `:138-140`'s `AlreadyInstalledException`
  comment). When it does, `mfHost` is the *same* instance, so
  registering again would double-register every subsystem. Track B's
  registration block must guard against this — either `GlobalSystems`
  grows a dedup check, or (consistent with `setChunkSerializer`'s
  "idempotent, volatile-backed" framing, `:164`) the registration
  block only runs on a fresh install, gated on whether `install()`
  (not the `AlreadyInstalledException` path) actually constructed a
  new host this call. Called out explicitly: it is the single most
  likely correctness bug in the B1.1/B2.x implementation — a naive
  port of §4.2's block passes every fresh-boot test and silently
  double-ticks every subsystem on the second `GameTestServer` instance
  in a shared-JVM run.

---

## 5. New `TicketType.DRAGON` schema

### 5.1 Schema

`net.mf.rt.chunk.TicketType` is a record — `(String name, int
defaultDistance, int timeoutTicks)` (`TicketType.java:24`) — with six
existing constants (`PLAYER`, `FORCED`, `START`, `PLUGIN`,
`POST_TELEPORT`, `ENDER_PEARL`, `TicketType.java:36-58`). B2.7 adds a
seventh:

```java
// End-dragon-fight arena pin: keeps the end-podium and dragon
// phase-relevant chunks loaded for the duration of an active fight,
// independent of player presence. Matches vanilla's own
// net.minecraft.server.level.TicketType<Unit> DRAGON
// (TicketType.create("dragon", (a, b) -> 0), timeout 0 — vendored copy
// at upstream/neoforge-1.21.1/.../server/level/TicketType.java:12),
// translated into this module's (name, distance, timeoutTicks) shape.
public static final TicketType DRAGON =
        new TicketType("dragon", ChunkLoadLevel.TICKING.distance(), 0);
```

`ChunkLoadLevel.TICKING.distance()` is `32` (`ChunkLoadLevel.java:19`)
— block/fluid ticks fire (crystal explosions, portal-chunk fluid
physics, obsidian-pillar updates) without requiring the arena's chunks
to reach `ENTITY_TICKING` from this ticket alone; a player standing in
the arena still contributes their own `PLAYER` ticket
(`ChunkLoadLevel.ENTITY_TICKING.distance()` = `31`,
`TicketType.java:37`), which is what promotes nearby chunks to
entity-ticking. This mirrors vanilla's own layering.

### 5.2 Timeout semantics: `timeoutTicks = 0` = perpetual until unregistered

`Ticket.isExpiredAt(long now)` (`Ticket.java:66-69`):

```java
public boolean isExpiredAt(long now) {
    if (createdAtTick < 0 || type.timeoutTicks() == 0) return false;
    return now >= (createdAtTick + type.timeoutTicks());
}
```

`timeoutTicks() == 0` short-circuits to `false` unconditionally — a
`DRAGON` ticket, once added, is **never** swept by
`TicketExpiryTicker.runOnce` (`TicketExpiryTicker.java:44-56`, which
loops every holder and calls `collectExpired` → `Ticket.isExpiredAt`).
This is the same permanence contract `PLAYER`, `FORCED`, `START`, and
`PLUGIN` already have (`TicketType.java:36-46`, all constructed with
the two-arg `of`/direct-constant form that defaults `timeoutTicks` to
`0`) — `DRAGON` is not a new *kind* of permanence, just a new named
instance of the existing "permanent until explicit removal" contract.

The corollary: **`DragonFightSystem` (B2.7) is solely responsible for
calling `ChunkHolderManager.removeTicket(owner, pos, dragonTicket)`**
(`ChunkHolderManager.java:187`) when a fight ends (dragon killed, or —
vanilla parity — the `EndDragonFight` is discarded because the
dimension unloads). A `DRAGON` ticket leaked (added but never removed)
pins its chunks at `TICKING` forever, for the life of the world save —
this is the failure mode `X.3`'s regression test (§8.2, integration
test 3) exists to catch.

### 5.3 Usage sketch in `DragonFightSystem` (B2.7)

Not part of the frozen contract (Track B implements the actual class),
illustrative of how §3's interface and §5.1's ticket type compose:

```java
// Fight start (or boot with a fight resumed from NBT):
Ticket dragonTicket = new Ticket(TicketType.DRAGON, TicketType.DRAGON.defaultDistance(),
        /* key */ endWorldRef.dimensionId(), /* createdAtTick */ globalTick);
for (ChunkPos p : endPodiumAndArenaChunks) chunkHolderManager.addTicket(endRegionOwner, p, dragonTicket);

// Fight end (dragon death confirmed):
for (ChunkPos p : endPodiumAndArenaChunks) chunkHolderManager.removeTicket(endRegionOwner, p, dragonTicket);
```

`key` follows `Ticket`'s existing `Object key` field (`Ticket.java:27`)
precedent — `ENDER_PEARL` tickets are keyed by entity id to avoid
ticket-count blowup with many simultaneous pearls (`TicketType.java:52-53`);
`DRAGON` has at most one active fight per End dimension, so keying by
`WorldRef.dimensionId()` makes repeated `addTicket` calls for the same
fight idempotent under `PerChunkTickets`'s dedup — Track B must verify
this rather than assuming it.

### 5.4 Interaction with `TicketExpiryTicker`

`TicketExpiryTicker` already runs "as a scheduled task on the global
region" per its own javadoc (`TicketExpiryTicker.java:14`) — unchanged
by this document; `TicketExpiryTicker.runOnce` is a natural fit for
`GlobalDomain.runAtFixedRate` (§1.4), not a `GlobalSystem` (engine
bookkeeping with no meaningful read/write-set of its own, and it
already routes mutation through the region-safe
`ChunkHolderManager.removeTicket`). `DRAGON`'s `timeoutTicks = 0`
means the sweep never touches it (§5.2) — the two mechanisms simply
don't interact for this ticket type, which is the point.

---

## 6. Migration path — retiring `vanillaBody.run()`

### 6.1 Drift note: the plan says three calls, the code has four

The landing plan states `RegionizedTickCoordinator.dispatchLevelTick`
"still runs Vanilla globals inline via three `vanillaBody.run()`
calls (lines 114, 123, 141)". Reading the actual file
(`RegionizedTickCoordinator.java`) at freeze time:

```
$ grep -n "vanillaBody.run()" RegionizedTickCoordinator.java
114:            vanillaBody.run();
123:            vanillaBody.run();
141:            vanillaBody.run();
161:        vanillaBody.run();
```

There are **four** call sites, not three. Lines 114/123/141 are the
ones the plan named; line 161 — the unconditional trailing call after
a successful dispatch — is not mentioned but is the *primary* one this
milestone's `08-globals` work targets, since it runs on every
successful tick (114/123/141 are early-return fallback paths that only
fire in degraded states). This document treats all four as in-scope
for "zero `vanillaBody.run()` calls" — the plan's exit-gate check
greps the literal token, which also matches the `vanillaBody`
parameter name itself, so the parameter must be deleted entirely, not
just its call sites emptied out.

### 6.2 Call-site table and final-state fate

| Line | Guard | Today's behavior | Final-state fate |
|---|---|---|---|
| 114 | `host == null` (`:111-116`) | Runtime not installed yet — run the full vanilla per-level tick inline. | **Unreachable in steady state** once `handleServerAboutToStart` always installs before any level loads (§4.2's guard). Becomes a should-never-happen `ViolationLogger.warn` + skip-this-level's-tick (auto-reroute+warn, CLAUDE.md rule 5) rather than a silent full-vanilla fallback that would race the region workers this milestone introduces. |
| 123 | `regionizer == null` (`:118-125`) | No regionizer materialised for this world yet — run inline. | Same fate as 114: once A2's `Entity.setPosRaw`/`ChunkEvent.Load` wiring (already installed via `RegionizedChunkLifecycle.installOnEventBus()`, `ServerLifecycleHooks.java:179`) is live for every loaded level, this branch is should-never-happen. Warn + skip, not full-vanilla fallback. |
| 141 | `tickAll` throws (`:129-143`) | Dispatch-side bug — warn + probe bump + run inline as a safety net. | **Kept**, but "run inline" can no longer mean "run the full vanilla body" once that body no longer exists (§6.4) — becomes warn + probe bump + **skip this level's tick this cycle**. A deliberate risk-trade change from today's "degrade to safe-but-slow" to "skip and warn, correctness over liveness for one tick" — consistent with CLAUDE.md rule 4, which a full inline vanilla-tick fallback would risk once real per-region bodies exist. |
| 161 | none (unconditional) | "Global portion still runs inline on the main thread (weather, time, wandering-trader spawner, etc.)" per the method's own javadoc (`:36-39`, `:158-161`). | **Deleted.** Replaced by the phase-4 `GlobalSystems.tickAll` dispatch (§2.3), running once per game tick (not once per level) on the dedicated global region, not inline on the caller thread. |

### 6.3 Vanilla-source → destination mapping

The trailing call at line 161 is vanilla's per-level tick body — the
same `Runnable` the patched call site hands in today (M8 sub-step 6b
already fans out the *region* portion before it, `:127-156`; only the
"vanilla global portion" comment at `:158-161` remains unmigrated).
Per the landing plan's B2low/B2high breakdown, each constituent piece
maps to exactly one of three destinations:

| Vanilla source | Destination | Landing task |
|---|---|---|
| `ServerLevel.advanceWeatherCycle` | Global subsystem: `WeatherSystem` | B2.1 |
| `ServerLevel.tickTime`, `MinecraftServer.synchronizeTime` | Global subsystem: `TimeSystem` | B2.2 |
| `WorldBorder.tick` | Global subsystem: `WorldBorderSystem` | B2.3 |
| `ServerScoreboard.onScoreChanged` (+ objective display cycling) | Global subsystem: `ScoreboardSystem` | B2.4 |
| `CustomBossEvents.tick` (wither bossbar bookkeeping) | Global subsystem: `BossEventSystem` | B2.5 |
| `Raids.tick` (wave scheduling / spawn decisions) | Global subsystem: `RaidsSystem`, spawns via `crossRegionEffect` into the raiders' destination region | B2.6 |
| `EndDragonFight.tick` (phase state machine, ticket pin) | Global subsystem: `DragonFightSystem`, `TicketType.DRAGON` (§5), phase transitions fan out via `crossRegionEffect` | B2.7 |
| `Commands.performPrefixedCommand`, `ServerFunctionManager` | Global subsystem: `CommandDispatchSystem` for cross-region command escalation; single-region commands execute directly in the caller's region (not global) | B2.8 |
| Entity AI/movement for any entity in the level (mobs, the dragon entity itself, raiders, wandering trader, players) | **Entity-tick regions** — `Phase.ENTITY_AI`, already the per-region dispatch target of M8's `TickRegionScheduler.tickAll` fan-out (`RegionizedTickCoordinator.java:127-130`), not the global region at all | already wired (M8), no B2.x task needed |
| Block/fluid random ticks, redstone | **Chunk-tick regions** — `Phase.BLOCK_FLUID_TICKS`, same per-region fan-out | already wired (M8), no B2.x task needed |

The last two rows are why this document's title says "global-region
contract" and not "vanilla-tick migration contract" — most of
vanilla's per-level tick body was never headed for the global region;
it goes to whichever entity-tick or chunk-tick region owns the
relevant chunk, via the *existing* M8 per-region dispatch. Only the
rows above the split are genuinely global (single logical state per
world or per-server, independent of any one chunk's owning region).

### 6.4 Final `dispatchLevelTick` shape

Once B3.1 lands (all of B2low + B2high + A4's entity-tick
`vanillaBody.run()` deletion), `dispatchLevelTick` no longer takes a
`Runnable vanillaBody` parameter at all — every constituent of the
vanilla per-level tick has a real destination (§6.3), so there is
nothing left to fall back to:

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

The exit gate's own check (`git grep vanillaBody
upstream/neoforge-1.21.1/src/main/java/net/multiforge/` returns
empty) passes because the parameter, field, and every reference are
gone — not merely because its four call sites became unreachable.

---

## 7. Failure semantics

### 7.1 Contract

**A `GlobalSystem` throwing from `tick()` must never propagate past
`GlobalSystems.tickAll`, must never stop later-registered subsystems
in the same pass from running, and must never crash the global
region's tick.** On catch: bump a per-subsystem probe counter, emit a
rate-limited violation warning, and continue to the next registered
subsystem. This is the same "auto-reroute + warn" default CLAUDE.md
rule 5 mandates for mod-triggered unsafe calls, applied here to
subsystem-internal bugs instead of mod code.

```java
public void tickAll(GlobalTickContext ctx) { // GlobalSystems, B1.1 revision
    for (Registered r : tickers) {
        try {
            r.tick(ctx);
        } catch (Throwable t) {
            ProbeRegistry.bump("global.system.failure." + r.name());
            ViolationLogger.warn("global.system." + r.name(),
                    "tick() threw: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
```

### 7.2 Comparison to today's `GlobalSystems.tickAll()` catch block

Today's implementation (`GlobalSystems.java:58-62`) already catches
`Throwable` per-ticker and never lets one failing ticker stop the
loop — the isolation half of this contract is already correct and
does not need to change. What it does on catch, however, differs from
the contract this document freezes:

```java
} catch (Throwable t) {
    Thread.currentThread().getUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), t);
}
```

This routes to the thread's uncaught-exception handler — the same
generic sink `PhasedRegionTickBody.tickOnce`'s per-phase catch uses
(`PhasedRegionTickBody.java:62-64`). Reasonable generic default, but
it does **not** bump a named probe or go through `ViolationLogger`'s
rate-limited, keyed warning path — an operator watching
`ProbeRegistry.snapshot()` or the M6 debug HUD's violation stream
would see nothing for a repeatedly-failing subsystem today. B1.1/B2.x's
revision (§7.1) is a strict upgrade: keeps the existing per-ticker
isolation guarantee and adds the probe/log observability every other
failure path in this codebase already has (compare
`RegionizedTickCoordinator.java:136-140`'s
`ProbeRegistry.bump("region-tick.dispatch.failure")` +
`ViolationLogger.warn(...)` pair for the dispatch-failure case §6.4
keeps).

### 7.3 Probe / log key convention

- Probe key: `"global.system.failure." + name()` — one counter per
  subsystem, matching `ProbeRegistry`'s existing flat namespaced-string
  convention (`region-tick.dispatch.failure`, `.overrun`,
  `RegionizedTickCoordinator.java:136, 146`).
- Violation site: `"global.system." + name()` — passed as `site` to
  `ViolationLogger.warn(String site, String detail)`
  (`ViolationLogger.java:49-51`), giving each subsystem its own
  rate-limit bucket (5/minute default) so one chronically-broken
  subsystem cannot crowd out warnings from an unrelated one.
- No `modId`-scoped overload is required for the eight B2.x core
  subsystems (not mod code); if a future milestone opens
  `GlobalSystems.register` to mods, `ViolationLogger`'s existing
  per-mod-bucketed overload is the natural extension point.

---

## 8. Test invariants

### 8.1 Unit tests (`multiforge-runtime`, no MC dependency)

1. `GlobalSystems.tickAll(ctx)` invokes every registered system's
   `tick(ctx)` exactly once per call, in registration order — extends
   `GlobalSystemsTest` to the new context-carrying signature.
2. A subsystem that throws inside `tick()` does not prevent
   later-registered subsystems in the same pass from running
   (isolation, §7.1) — fixture list `[throwing, recording]`, assert
   the recording one still saw the call.
3. A throwing subsystem bumps `ProbeRegistry.get("global.system.failure.<name>")`
   by exactly one per throw, and `tickAll()` never propagates the
   exception to its caller.
4. `GlobalSystems.currentTick()` increments by exactly one per
   `tickAll()` call, regardless of how many subsystems are registered
   or throw.
5. `unregister(Registered)` removes a system such that a subsequent
   `tickAll()` no longer invokes it, even mid-registration-order
   (unregistering the 2nd of 3 doesn't skip the 3rd).
6. `crossRegionEffect(dest, task)` resolves a live `RegionId` to the
   correct `(WorldRef, chunkX, chunkZ)` anchor per §3.6, verified
   against a stub `ThreadedRegionizer` + `RegionizedTaskQueue` pair
   (`RegionizedTaskQueue.of(ThreadedRegionizer)`,
   `RegionizedTaskQueue.java:90-92`) — task lands in the destination
   region's inbox, not a different region's.
7. `crossRegionEffect` against a died `RegionId` (merged away)
   degrades to the orphan-queue reroute path rather than throwing —
   matches `RegionizedTaskQueue`'s resolve-to-null-owner contract
   (`:130-134`).
8. `readSet()`/`writeSet()` are **not** consulted by `tickAll` to
   serialize or reorder subsystems in M5 — register two systems with
   overlapping `writeSet()`s and assert both still ran, unblocked.
   Exists so a future PR adding real conflict enforcement has to
   consciously invert this assertion rather than silently regressing
   M5's documented non-enforcement.
9. `TicketType.DRAGON` matches §5.1 exactly: `name() == "dragon"`,
   `defaultDistance() == ChunkLoadLevel.TICKING.distance()` (32),
   `timeoutTicks() == 0`.
10. `new Ticket(TicketType.DRAGON, ..., createdAtTick).isExpiredAt(now)`
    returns `false` for every `now >= createdAtTick`, including far in
    the future — the "perpetual" half of §5.2.
11. Concurrent `register`/`unregister`/`tickAll` from multiple threads
    do not corrupt `GlobalSystems`'s state — registration happens once
    at boot in production, but the API must not silently assume
    single-threaded access.

### 8.2 Integration tests (`upstream/neoforge-1.21.1` fork module, MC dependency)

1. **Phase ordering.** A probe `GlobalSystem` observes a
   `NewChunkHolder`'s load-level state changed via a ticket add/remove
   earlier in the *same* tick; assert the observed level already
   reflects that change — phase 4 genuinely runs after phase 1's
   `phasePollFullLoadUpdate` has drained
   (`MultiThreadedSchedulerHost.java:506-524`), not merely "usually
   does" by scheduling luck.
2. **Once per game tick, not once per level.** With N > 1 loaded
   dimensions, a probe `GlobalSystem`'s `tick()` fires exactly once
   per game tick (monotonic invocation counter vs. the server's own
   tick counter), closing §1.5/§2.3's tick-multiplication gap.
3. **Dragon-fight regression (plan's X.3).** Full kill cycle on a
   fixed seed: `TicketType.DRAGON` added on fight start, removed
   exactly once on confirmed death (never leaked, never
   double-removed); the end-podium chunk set is byte-identical (via
   `WorldDiff.DiffMode.SEMANTIC`, reused from M9) to an
   unpatched-NeoForge baseline after chunks demote back to pre-fight
   load level.
4. **Global-region tick budget.** A 30–60 minute headless swarm run
   under strict mode (`-Dmultiforge.regiontick.strict=on`) with all
   eight B2.x subsystems registered produces zero
   `RegionTickOverrunException`/`RegionDispatchOverrunException`
   instances attributable to the global region specifically.
5. **Vanilla parity per migrated subsystem.** For weather, time,
   world border, and scoreboard state (B2low), a fixed-seed
   determinism run (`:multiforge-bench:determinism`, CLAUDE.md rule 3)
   produces a byte-identical world save between the `GlobalSystem`-
   routed path and an unpatched-NeoForge baseline — confirms §6.3's
   migration changed *where* the logic runs, not *what* it computes.
6. **Cross-region raid spawn safety.** `RaidsSystem.tick()` never
   inserts a raider directly into a destination region's entity list
   from the global-region thread — every spawn goes through
   `crossRegionEffect` → `EntityMigrationCoordinator.spawnInDestRegion`
   (`docs/design/entity-migration.md`) — zero `OwnershipEnforcer`
   reroute/violation hits during the plan's X.2 stress scenario.
7. **Zero-`vanillaBody` exit gate.** `git grep vanillaBody
   upstream/neoforge-1.21.1/src/main/java/net/multiforge/` returns
   empty once B3.1 lands — the plan's own exit-gate check, restated
   as the terminal invariant this document exists to unblock.
