# MultiForge Concurrency Contract

## Summary

Every thread MultiForge runs work on carries an **owner token**: a domain
tag plus, for a region worker, the id of the region it is ticking. The
patched mutation sites read that token to decide whether a write may run
inline or must be handed to the owner of the chunk it touches. This page
documents that mechanism as implemented in
`multiforge-runtime/src/main/java/net/multiforge/runtime/ownership/` and the
fork glue `upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/OwnershipGuard.java`.

The tick model the contract serves is
[`design/barrier-tick-model.md`](design/barrier-tick-model.md): the server
thread runs Vanilla's loop, and each level's regions tick in parallel
between two barriers. Region work and server-thread work never overlap;
the only concurrency is between regions of the same level.

## Domain

`net.multiforge.runtime.ownership.Domain` has seven values. Which of them
the running server actually installs:

| Value           | Set by | Meaning in the barrier model |
|-----------------|--------|------------------------------|
| `REGION`        | `TickRegionScheduler`, around every region tick (including the synthetic global region's) | A region worker. May mutate only chunks its own region owns. |
| `ASYNC`         | `MultiThreadedSchedulerHost`, around every `ServerDomains.async()` body | A shared async-pool thread. Must not touch game state. |
| `UNKNOWN`       | Default for any thread without a token | The server thread, world generation, Netty threads, a mod's own threads. The server thread is recognised separately (the bound tick thread, below). |
| `GLOBAL`        | Only `SingleThreadedSchedulerHost` (the MC-free reference host used in tests) | Not installed on a server. The server thread is Vanilla's single owner of world-wide state. |
| `ENTITY`        | Only `SingleThreadedSchedulerHost` | Not installed on a server; entity work runs as `REGION`. |
| `LEGACY_SERIAL` | Nothing | Declared constant. The serial event lane runs listeners under the *posting worker's* token, not this one (`docs/events.md`). |
| `NETWORK`       | Nothing | Declared constant. Netty threads carry no token and read as `UNKNOWN`. |

The enum's own javadoc for `GLOBAL` ("the dedicated global-region thread.
Owns weather, time, …") describes the retired M5 design; in the barrier
model weather, time, world border, raids and the dragon fight run in
Vanilla's `ServerLevel.tick` on the server thread.

## OwnerToken

`OwnerToken` is a record `(Domain domain, long regionId)` held in a
`ThreadLocal`.

```java
public record OwnerToken(Domain domain, long regionId) {
    public static final long NO_REGION = Long.MIN_VALUE;
    public static final OwnerToken GLOBAL = ...;
    public static final OwnerToken ASYNC = ...;
    public static final OwnerToken NETWORK = ...;
    public static final OwnerToken LEGACY_SERIAL = ...;

    public static OwnerToken forRegion(long regionId) { ... }
    public static OwnerToken current() { ... }
    public static void runAs(OwnerToken token, Runnable work) { ... }
}
```

- **`current()`** never returns `null`; a thread with no token gets
  `(UNKNOWN, NO_REGION)`.
- **`runAs(token, work)`** installs `token` for the duration of `work` and
  restores the previous token (or none) afterwards. Nesting is supported.
- `TickRegionScheduler.tickClaimed` wraps each region tick — mailbox drain,
  tick body, second mailbox drain — in
  `OwnerToken.runAs(OwnerToken.forRegion(region.id().value()), …)`. Mod
  code never calls `forRegion`.
- `SerialLane.drain` runs a handed-off listener under the token of the
  worker that handed it off, so a listener's world writes on the server
  thread are checked exactly as the worker's own would be.

## OwnershipEnforcer and OwnershipGuard

`OwnershipEnforcer` (runtime, MC-free) holds the decision logic.
`OwnershipGuard` (fork, `net.multiforge.neoforge`) is the thin adapter the
patched `net.minecraft.*` sites call; it converts a `Level` to its
`WorldRef` (`Level.mfWorldRef()`, added by `01-ownership`) and skips client
levels.

### Modes

```java
public enum Mode {
    OFF,      // Every check passes; every call runs inline.
    REROUTE,  // Default. A violation is warned about and rerouted.
    STRICT,   // A violation throws OwnershipViolationException. For testing.
}
```

The initial value comes from `-Dmultiforge.ownership.mode` (default
`reroute`; an unrecognised value falls back to `REROUTE`). On a server,
`MultiForgeServerState.applyConfig` then sets it from
`config/multiforge-server.toml` at startup and on every config change:
`STRICT` when `[mtserver] mode = "strict"` or `[violations] policy = "fail"`,
otherwise `REROUTE`. `applyConfig` never selects `OFF`; `mode = "off"`
instead skips installing the regionized runtime, so no region workers
exist and every guard falls through.

### Positional check: `canMutateAt` / `rerouteAt`

Every patched mutation site uses the positional pair:

```java
if (!OwnershipGuard.canMutateAt("Level.setBlock", level, pos)) {
    return OwnershipGuard.rerouteSetBlock(level, pos, state, () -> this.setBlock(...));
}
```

`OwnershipEnforcer.canMutateAt(site, world, chunkX, chunkZ)`:

1. `Mode.OFF` → `true`.
2. Caller is not a region worker (`domain != REGION`) → falls back to the
   thread check `canMutate(site)` below.
3. No position router bound (runtime not installed) → `true`.
4. The caller's region owns the chunk → `true`.
5. Otherwise the probe `<site>:cross-region` is bumped, a rate-limited
   warning is logged (`ViolationLogger`), and in `STRICT` mode
   `OwnershipViolationException` is thrown; in `REROUTE` mode it returns
   `false`.

`rerouteAt(site, world, chunkX, chunkZ, mutation)` queues `mutation` on the
owning region's mailbox with `RegionizedTaskQueue.queueChunkTask`, where it
runs at that region's next drain. If no region owns the chunk, it goes to
the server-thread reroute target instead.

The patched sites (`multiforge-patches/01-ownership/`, `05-entity-migration/`):

| Site id | Where |
|---|---|
| `Level.setBlock` | `Level.setBlock` |
| `LevelAccessor.scheduleTick` | the `scheduleTick` overloads |
| `ServerLevel.addFreshEntity`, `ServerLevel.addEntity` | entity add |
| `Entity.remove` | entity removal |
| `BlockEntity.setChanged` | block-entity dirty marking |
| `LevelChunk.addAndRegisterBlockEntity` | block-entity registration |

### Predicted return values

Some callers use Vanilla's return value (consume an item if `setBlock`
returned `true`, count a spawned entity). A rerouted call has not run yet,
so `OwnershipGuard` returns a prediction:

- `rerouteSetBlock` predicts `true` if the target block state differs from
  the requested one (or the chunk is not loaded), `false` otherwise.
- `rerouteAddFreshEntity` predicts `true` unless the entity is already
  removed or its UUID is already in the level.

When the owner applies the write and gets a different result, the probe
`reroute.<site>.mismatch` is bumped and a warning is logged under site
`<site>.mismatch`.

### Thread check: `canMutate`

`canMutate(site)` handles callers that are not region workers:

1. `Mode.OFF` → `true`.
2. Domain `REGION` or `GLOBAL` → `true`.
3. The calling thread is the bound tick thread (the server thread) → `true`.
4. Otherwise (async pool, Netty, world-gen, a mod's own thread) the probe
   `<site>:off-thread` is bumped, a warning logged, and `STRICT` throws;
   `REROUTE` returns `false` and the call site reroutes.

`reroute(site, mutation)` hands `mutation` to the bound `RerouteTarget`
(the server thread).

### Deferral to the server thread

Operations whose effects are not confined to one region are deferred when
a region worker starts them. They run on the server thread after the
barrier, while no region runs. These are not violations; each bumps a
probe but logs nothing.

| Helper | Used by | Probe |
|---|---|---|
| `OwnershipGuard.deferCrossRegionMove` | `Entity.teleportTo`, `Entity.changeDimension` (same level), `ServerPlayer.teleportTo`, `teleportRelative`, `changeDimension` | `<site>:deferred-cross-region`, `<site>:deferred-player-dimension-change` |
| `OwnershipGuard.deferToServerThread` | `Commands.performPrefixedCommand`, `Commands.performCommand`, `ServerFunctionManager.execute` | `<site>:deferred-to-server-thread` |

A move within the caller's region, and any call from a thread that is not
a region worker, runs inline. A non-player entity changing dimension runs
inline (only this level's regions are ticking). `OwnershipEnforcer.isCrossRegionFromWorker`
is the probe-free query behind `deferCrossRegionMove`.

### Bootstrap bindings

Wired in the fork's `ServerLifecycleHooks.handleServerAboutToStart`, on the
server thread:

- `bindTickThread(Thread)` — records the server thread.
- `bindRerouteTarget(RerouteTarget)` — `server::execute`, wrapped so a
  `RejectedExecutionException` after shutdown becomes a warning instead of
  an exception in the caller. If never bound, a reroute runs inline and
  logs that it did (`runInlineUnconfigured`).
- `bindPositionRouter(PositionRouter)` — done by
  `MultiThreadedSchedulerHost.install()`; resolves chunk owners from the
  level's regionizer and queues on the owner's mailbox.
- `MultiForgeRegionizedRuntime.shutdown()` calls
  `unbindTickThreadAndRerouteTarget()`, and the host's `close()` calls
  `unbindPositionRouter()`, so a GameTest JVM that starts several servers
  never routes into a dead one.

### OwnershipViolationException

Thrown only in `STRICT` mode, from `canMutate` or `canMutateAt`. Exposes
`site()` and `actualDomain()`. Like any exception in a region tick, it is
rethrown on the server thread once the barrier completes, where Vanilla's
"Exception ticking world" handling stops the server. Strict mode is for
test and regression runs, never production.

## The global region

The runtime keeps a synthetic **global region** (world
`multiforge:global`, chunk 0,0). `RegionizedTickCoordinator` drives it once
per server tick on the worker pool, before the first level's regions. It
runs `ServerDomains.global()` tasks and GLOBAL-domain event listeners that
an async-pool task posted. It runs under a `REGION` token carrying the
global region's id, so a world write made from it is not owned by that
region: `canMutateAt` treats it as cross-region, warns, and reroutes it to
the chunk's owner (or throws in strict mode). World writes belong in
`ServerDomains.region(...)` tasks.

## DomainAssertions

`DomainAssertions` is a passive checker, gated by `-Dmultiforge.assert=on`
(default off). It never throws. `assertGlobal(site)`,
`assertRegion(site, regionId)` and `assertTickThread(site)` bump
`<site>:not-global` / `:wrong-region` / `:not-tick-thread` and log a
warning. No patched site calls these methods today; only
`DomainAssertions.enabled()` is read, by `RegionizedData` (a runtime
utility the server does not currently use).

## Who may write what

| Thread | May mutate | Notes |
|---|---|---|
| Region worker (`REGION`) | Chunks, entities, block entities and scheduled ticks in chunks its region owns | An entity belongs to the region owning the chunk it is in. Anything else is rerouted, or deferred to the server thread for teleports, dimension changes and commands. |
| Server thread | Everything, as in Vanilla | Runs only while no region runs. Also runs serial-lane listeners, under the posting worker's token. |
| Global region | Only through the mailbox of the owning region | See above. |
| Async pool (`ASYNC`) | Nothing live | A write is an `:off-thread` violation and is rerouted. |
| Any other thread (`UNKNOWN`, not the server thread) | Nothing directly | Violation → rerouted to the owner, or to the server thread if no region owns the chunk. The read side is not protected. |

Shared Vanilla state that region workers touch concurrently (entity
storage, `LevelTicks`, POI manager, scoreboard, random and neighbour
updater, …) is made safe with leaf locks or per-thread instances; the
table is in [`design/barrier-tick-model.md`](design/barrier-tick-model.md#shared-vanilla-state-made-safe).

The policy, per CLAUDE.md rules 4 and 5:

- **No blocking calls on a region worker.** A `Thread.sleep`, a
  `Future.get()`/`join()`, or a lock that could be held while foreign code
  runs are bugs. The two designed waits — a worker waiting for the server
  thread to load a chunk (`MainThreadHandoff`) and a worker waiting for its
  serial-lane listener (`SerialLane`) — are bracketed with
  `RegionTickWatchdog.beginWait()/endWait(kind)`, totalled in
  `region-tick.wait-ms.<kind>`, and excluded from the overrun check.
- **Auto-reroute + warn is the default.** MultiForge never refuses to load
  a mod and never throws from a mod's code path in the default mode.

## Test coverage

- `net.multiforge.runtime.ownership.OwnershipEnforcerTest` — thread check:
  off mode, `REGION`/`GLOBAL` pass-through, bound tick thread, off-thread
  violation in reroute and strict mode, reroute target delegation,
  unconfigured target, unrecognised mode property.
- `net.multiforge.runtime.ownership.OwnershipEnforcerPositionalTest` —
  `canMutateAt`: own region, foreign region, unowned chunk, strict mode,
  off mode, `rerouteAt` to owner or server target, no router bound.
- `net.multiforge.runtime.ownership.OwnerTokenTest` — `current()` default,
  `runAs` apply/restore and nesting.
- `net.multiforge.runtime.event.SerialLaneTest` — lane jobs run under the
  worker's token, one at a time; exceptions reach the worker.
- `RegionTickBehaviourTests` (fork GameTests under
  `upstream/neoforge-1.21.1/tests/`) — cross-region reroute on a live
  server.

There is no dedicated `DomainAssertionsTest`.
