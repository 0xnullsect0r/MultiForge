# MultiForge Scheduler Internals

[`api.md`](api.md) documents the mod-facing surface (`ServerDomains` and
the Folia-shaped wrappers). This page documents the runtime machinery
under it, for people writing runtime or fork code: how the runtime is
installed, how the server thread drives region ticks, how
`RegionizedTaskQueue.queueChunkTask` delivers cross-region work, and what
`ChunkHolderManager` still does. The tick model itself is
[`design/barrier-tick-model.md`](design/barrier-tick-model.md).

## MultiForgeRegionizedRuntime

`net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime` holds the
process-wide `MultiThreadedSchedulerHost`.

```java
public static MultiThreadedSchedulerHost install(MultiForgeConfig config, RegionTickBody body);          // FREE_RUNNING
public static MultiThreadedSchedulerHost install(MultiForgeConfig config, RegionTickBody body,
                                                 TickRegionScheduler.Mode mode);
public static MultiThreadedSchedulerHost current();
public static void shutdown();
```

- **`install`** constructs the host, binds it as the `ServerDomains`
  host and as `OwnershipEnforcer`'s position router, and records it as
  current. The two-argument form uses `TickRegionScheduler.Mode.FREE_RUNNING`
  (regions tick on their own 20 TPS cadence), which only MC-free runtime
  code and tests use. A server installs with `Mode.BARRIER`.
- If a host is already installed, `install` throws
  `AlreadyInstalledException` (a subclass of `IllegalStateException`); the
  fork's lifecycle hook swallows exactly that case, for a GameTest JVM that
  starts several servers. If a different `SchedulerHost` is already bound
  to `ServerDomains` (for example the `ServiceLoader`-found
  `SingleThreadedSchedulerHost`, bound because a mod called
  `ServerDomains` before server start), `install` rolls back and throws a
  plain `IllegalStateException`, which the hook logs and rethrows.
- `install` also starts `OtelExporter` when `-Dmultiforge.otel.endpoint`
  is set (off by default).
- **`current()`** returns the host, or `null` (not installed, or
  `mode = "off"`).
- **`shutdown()`** closes the host (which also unbinds the position
  router), clears it, unbinds `ServerDomains`, unbinds `OwnershipEnforcer`'s
  tick thread and reroute target, and stops `OtelExporter`. Idempotent;
  the unbinding runs in a `finally`.

### Server bootstrap

`ServerLifecycleHooks.handleServerAboutToStart` (fork), on the server
thread:

1. `OwnershipEnforcer.bindTickThread`, `SerialLane.bind`,
   `ModSafetyClassifier.bind(server)`, `OwnershipEnforcer.bindRerouteTarget(server::execute)`.
2. Load `config/multiforge-server.toml` (`MultiForgeServerState.loadConfig`)
   and apply it (`applyConfig`: ownership mode, watchdog mode, warning
   budget).
3. If the effective mode is `off`, stop here: no runtime, Vanilla tick.
4. `MultiForgeRegionizedRuntime.install(config, region -> {}, BARRIER)`.
5. `RegionizedChunkLifecycle.installOnEventBus()` — `ChunkEvent.Load` adds
   the chunk to the regionizer and `ChunkHolderManager`, and re-delivers
   tasks waiting for that chunk; `ChunkEvent.Unload` removes it.
6. On a fresh install: `RegionRuntimeInit.install` (materialise
   regionizers, bind the scheduled-tick, entity and block-entity runners,
   attach the event-bus dispatch executor),
   `installRegionTickBody(PhasedRegionTickBody.builder())`, and
   `bindPins` for `config/multiforge-region-pins.toml`.
7. Subscribe to config changes so `/multiforge config …` applies live
   (`applyConfig` on both `MultiForgeServerState` and the host: pool
   size, region size).

`handleServerStopping` runs every task still waiting in a region mailbox
on the server thread (`drainMailboxesOnCaller`), after the tick loop has
ended and before the world is saved.

## Driving a tick (barrier mode)

The patched `MinecraftServer.tickChildren` calls
`RegionizedTickCoordinator.dispatchLevelTick(level, haveTime)` instead of
`level.tick(haveTime)`:

1. For the overworld only: `host.driveGlobalTick(deadline, pump)` runs the
   synthetic global region once.
2. `level.tick(haveTime)` — Vanilla's level tick, on the server thread,
   minus the work regions own.
3. If the level has no regionizer yet (no chunk loaded), everything ran
   inline; the probe `region-tick.no-regionizer-inline` counts it.
4. Otherwise `host.driveRegions(world, deadline, pump)` →
   `TickRegionScheduler.driveTick`: every live region of the level is
   submitted to the worker pool; the server thread runs `pump` while it
   waits and returns when all have finished.
5. `ServerChunkCache.mfAfterRegions()` — chunk work a region could not
   take, then the block-change broadcast.

`pump` is `MainThreadHandoff.pumpFor(server)`: it drains the `SerialLane`
(event listeners handed over by workers) and, only while a worker is
blocked in `MainThreadHandoff`, runs one pending chunk-source task per
level.

Each region tick (`TickRegionScheduler.tickClaimed`) runs under
`OwnerToken.forRegion(id)`: drain up to 128 mailbox tasks, run the tick
body, drain up to 128 more. `RegionTickWatchdog.enterTick/exitTick`
bracket it; designed waits (`beginWait`/`endWait`) are subtracted before
comparing with the 500 ms threshold. A region still running after the
dispatch deadline (500 ms, `-Dmultiforge.regiontick.dispatch-ms`, designed
waits excluded) is reported as `region-tick.dispatch.overrun`; the server
thread still waits for it. An exception in a region's tick is rethrown on
the server thread after the barrier.

## RegionizedTaskQueue.queueChunkTask

`net.multiforge.runtime.region.RegionizedTaskQueue` holds one mailbox per
region. Every cross-region hand-off goes through it: `ServerDomains`
region, entity and global tasks, and writes rerouted by
`OwnershipEnforcer.rerouteAt`.

```java
public void queueChunkTask(WorldRef world, int chunkX, int chunkZ, Runnable task);
public void queueChunkTask(WorldRef world, ChunkPos pos, Runnable task);
```

- **The owner is resolved at enqueue time**, under the world
  regionizer's read lock, so the region cannot merge away or die between
  the lookup and the enqueue. The task is added to that region's inbox.
- **Merges.** `onRegionsMerging` (under the regionizer's write lock) moves
  the dying region's inbox to the survivor.
- **Splits.** Tasks already queued stay in the source region's inbox,
  including tasks for chunks that moved to the new region; they run on
  the source region's worker. Guarded writes they make are checked again
  by `canMutateAt` and rerouted if needed.
- **Unloaded chunks.** If no region owns the chunk, the task goes to an
  orphan bucket keyed by world and 16×16-chunk section. `RegionizedChunkLifecycle`
  calls `rerouteAtChunk(world, x, z)` on every `ChunkEvent.Load`, which
  re-delivers that bucket's tasks whose chunk now has an owner. A task
  whose chunk never loads never runs. (`reroute()`, which retries every
  bucket, has no production caller.)
- **Draining.** `drain(region, max)` runs up to `max` tasks on the calling
  thread (the region's worker). A task that throws is passed to the
  thread's uncaught-exception handler; draining continues.

`RegionizedTaskQueue.of(ThreadedRegionizer)` builds a queue over one
regionizer, for tests and single-world callers.

## ChunkHolderManager

`net.multiforge.runtime.chunk.ChunkHolderManager` is one world's index of
**loaded chunks by owning region**, plus each region's
`HolderManagerRegionData` (its block-entity tickers). It does not load,
ticket, light or save chunks; Vanilla does all of that on the server
thread.

```java
public NewChunkHolder holderAt(ChunkPos pos);                 // loaded chunk, or null
public NewChunkHolder createHolder(ChunkPos pos, RegionId owner);
public NewChunkHolder dropHolder(ChunkPos pos);
public Collection<NewChunkHolder> holders();
public int holderCount();
public List<NewChunkHolder> holdersOwnedBy(RegionId region);
public HolderManagerRegionData regionData(RegionId region);
```

It is a `RegionListener` on the world's regionizer, so holders and
block-entity tickers follow every merge and split.
`RegionizedChunkLifecycle` feeds it from `ChunkEvent.Load/Unload`;
`/multiforge chunks <world>` prints it. Get one with
`host.chunkManagerFor(world)` or `chunkManagerForOrNull(world)`.

CLAUDE.md names `net.multiforge.runtime.chunk.InstanceRegistry<K, V>`
(weak-keyed) as the standard per-server/per-level lookup for new code. No
production code uses it yet; `MultiForgeServerState` still keeps its
per-server config and pin stores in raw `WeakHashMap`s.

## The four domains

`MultiThreadedSchedulerHost implements SchedulerHost`:

| Domain | Method | Mechanism |
|---|---|---|
| Region | `region(WorldRef, ChunkPos)` | `taskQueue.queueChunkTask(world, x, z, …)`. Delayed and repeating tasks re-queue (and so re-resolve the owner) on every fire. |
| Entity | `entity(EntityRef)` | As region, at `entity.chunkPos()` read on each queueing; `isRetired()` checked on the worker before the body, which then runs the `retired` callback and cancels the task instead. |
| Global | `global()` | A private `enqueueOnGlobal` → `queueChunkTask(GLOBAL_WORLD, 0, 0, …)`. The global region is a real `Region` in the synthetic world `multiforge:global`, created with the host and driven once per server tick by `driveGlobalTick`. It runs under a `REGION` token, so world writes from it are cross-region (see `concurrency-contract.md`). |
| Async | `async()` | A `ScheduledExecutorService` of `max(2, cores / 2)` threads; bodies run under `OwnerToken.ASYNC`. `cancelTasks(mod)` cancels a mod's async tasks. |

Delays for region, entity and global tasks are timed on a single
`multiforge-delayed` thread at 50 ms per tick; each fire only enqueues, so
the body always runs on the owning worker.

`MultiThreadedSchedulerHost.scheduleGlobal(Runnable, long periodMillis)`
is unrelated to the global domain: it schedules a fixed-rate job on a
separate diagnostics executor, used only by the debug-channel emitters
(`net.multiforge.runtime.diagnostics.emitters`). Gameplay code must not use
it.

## Do's and don'ts

- **Do** route cross-region work through `RegionizedTaskQueue.queueChunkTask`
  or the `ServerDomains` wrappers. Don't read or write another region's
  chunks, entities or block entities directly.
- **Don't** block on a region worker: no `Future.get()`/`join()`, no
  `Thread.sleep`, no lock held while foreign code runs (CLAUDE.md rule 4).
  Use a continuation (a follow-up `queueChunkTask` or scheduled task). The
  two designed exceptions — a worker waiting on `MainThreadHandoff` for a
  chunk load, and on `SerialLane` for a listener — are bracketed with
  `RegionTickWatchdog.beginWait()/endWait(kind)`. Any new designed wait
  must be bracketed the same way and serviced by the barrier's pump.
- **Do** use a leaf lock or a per-thread instance for shared Vanilla state
  a region worker touches (the table in the barrier-tick-model doc). A leaf
  lock is one under which no foreign code runs and nothing waits on
  another thread.
- **Don't** assume a queued task runs promptly: it runs at the owner's
  next drain, or, for an unloaded chunk, when the chunk loads. If the work
  must happen now or not at all, check ownership first
  (`regionizerForOrNull(world).regionAtChunk(x, z)`).
- **Do** defer work whose effects span regions (teleports, player
  dimension changes, commands) to the server thread with
  `OwnershipGuard.deferToServerThread` / `deferCrossRegionMove`.

## Recipes

**Run this on chunk (cx, cz):**

```java
ServerDomains.region(world, new ChunkPos(cx, cz))
    .execute(MOD, () -> {
        // On the region worker owning (cx, cz).
    });
```

**Run once, N ticks from now, on the global region:**

```java
ServerDomains.global()
    .runDelayed(MOD, handle -> {
        // On the global region, about N × 50 ms from now. No world writes here.
    }, /* delayTicks */ N);
```

**Every 20 ticks on the region owning `pos`:**

```java
ServerDomains.region(world, pos)
    .runAtFixedRate(MOD, handle -> {
        // handle.cancel() stops future iterations.
    }, /* initialTicks */ 20L, /* periodTicks */ 20L);
```

**Stop when an entity is gone:**

```java
ServerDomains.entity(ref).runAtFixedRate(MOD,
    handle -> mob.heal(1.0f),
    () -> LOGGER.info("mob {} retired; task stopped", ref.uuid()),
    /* initial */ 20L,
    /* period  */ 20L);
```

All of these can be called from any thread; the body runs only once the
task reaches its owner.

## Test coverage

- `scheduler/MultiForgeRegionizedRuntimeTest` — install/current/shutdown,
  `AlreadyInstalledException` vs. foreign-host `IllegalStateException`.
- `scheduler/RuntimeLifecycleReviewFixesTest` — install rollback,
  shutdown unbinding `OwnershipEnforcer`, robust parsing of the watchdog
  and warning-budget properties.
- `scheduler/MultiThreadedSchedulerHostTest` — region, entity, global and
  async scheduling and repeating tasks on the parallel host.
- `scheduler/SingleThreadedSchedulerHostTest` — the reference host.
- `scheduler/LiveConfigTest` — pool resize and region-size repartition.
- `scheduler/DispatchLevelTickTest` — inline ticks counted per world.
- `region/TickRegionSchedulerBarrierModeTest` — `driveTick`: every region
  once and in parallel, pump on the caller, designed waits excluded from
  the deadline, overruns reported, mailboxes drained.
- `region/RegionizedTaskQueueTest` — FIFO order, orphan re-delivery,
  exception isolation.
- `chunk/ChunkHolderManagerTest` — holder create/drop, merge and split
  moving chunks and tickers.

(All under `multiforge-runtime/src/test/java/net/multiforge/runtime/`.)
