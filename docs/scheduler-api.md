# MultiForge Scheduler API Guide (runtime internals)

## Executive summary

`docs/api.md` documents the **mod-facing** surface — `ServerDomains` and
the Folia-shaped mirror. This page documents the **engine-facing**
machinery underneath it: how `MultiForgeRegionizedRuntime` boots and holds
the live scheduler, how `RegionizedTaskQueue.queueChunkTask` actually
delivers cross-region work, how `ChunkHolderManager` intersects with
scheduling, and the do's/don'ts that keep the tick pipeline honest. If
you're writing a mod, read `docs/api.md`. If you're writing runtime or
patch code that calls into the scheduler directly, read this.

## MultiForgeRegionizedRuntime

`net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime` is the
process-wide singleton holder for the live `MultiThreadedSchedulerHost`.
It's deliberately thin — it doesn't know how a host is constructed or how
its worker pool is configured, so the M8 fork patches (hand-written glue
under `upstream/neoforge-1.21.1/src/main/java/net/multiforge/`) never need
to know either.

```java
public static MultiThreadedSchedulerHost install(MultiForgeConfig config, RegionTickBody body);
public static MultiThreadedSchedulerHost current();
public static void shutdown();
```

- **`install`** constructs a `MultiThreadedSchedulerHost`, installs it as
  the `ServerDomains` binding, and records it as the process-wide current
  runtime. Called once from an early server-lifecycle hook. Not
  thread-safe against concurrent `install` calls — the intended pattern is
  one call per JVM lifetime from the server's own bootstrap thread, before
  any region worker starts.
- If a MultiForge runtime is already installed, `install` throws
  `AlreadyInstalledException` — a subclass of `IllegalStateException`
  specifically so the fork's `ServerLifecycleHooks` handler can swallow
  *this* case silently (idempotent bootstrap on a dedi GameTestServer
  reusing one JVM across successive servers) without also swallowing a
  **foreign** `SchedulerHost` bound to `ServerDomains` — that case
  propagates as a raw `IllegalStateException` and must not be masked.
- **`current()`** returns the installed host, or `null` if nothing has
  been installed yet.
- **`shutdown()`** closes the host, clears the holder, unbinds
  `ServerDomains`, and unbinds `OwnershipEnforcer`'s tick-thread/reroute
  bindings (see `docs/concurrency-contract.md`) so a reroute target bound
  to a specific server executor doesn't outlive the server that captured
  it. Idempotent — a second call is a no-op. Uses try/finally so the
  unbind still happens even if `host.close()` throws.

## RegionizedTaskQueue.queueChunkTask — cross-region delivery

`net.multiforge.runtime.region.RegionizedTaskQueue` is the mailbox
primitive every cross-region handoff in MultiForge goes through — mod
scheduler calls, network packets landing on their target, entity
teleports, event-bus fan-out.

```java
public void queueChunkTask(WorldRef world, int chunkX, int chunkZ, Runnable task);
public void queueChunkTask(WorldRef world, ChunkPos pos, Runnable task);
```

Key properties:

- **Enqueue-time vs. drain-time.** The task is delivered to the region
  owning `(chunkX, chunkZ)` **at drain time**, not at the moment you call
  `queueChunkTask`. The owning region's worker drains its inbox at the
  start and end of every tick. This is what lets a task survive a
  merge/split that happens between enqueue and drain — see below.
- **Unloaded chunks land in the orphan queue.** If the chunk isn't
  currently loaded, `ownerLookup` returns `null` and the task is bucketed
  by section (`ORPHAN_SECTION_SHIFT = 4`, matching the regionizer's
  default section size) rather than dropped. It's re-delivered the next
  time `reroute()` (whole-queue) or `rerouteAtChunk(world, x, z)`
  (single-bucket, called once per `ChunkEvent.Load`) runs.
- **Resolve-then-enqueue is lock-protected.** `queueChunkTask` takes the
  regionizer's read lock (via the `ReadLockLookup` the queue was
  constructed with) for the duration of the *(look up owner) → (add to
  that owner's inbox)* pair. This blocks any concurrent `mergeInto` or
  last-chunk-removal for that span, so the region you resolved cannot die
  or fold into another region before your enqueue commits. On the merge
  side, `onRegionsMerging` runs under the same regionizer's *write* lock
  and moves any inbox entries the dying region had just received into the
  survivor — no task is ever silently lost to a race with a merge.
  Legacy/test construction (`new RegionizedTaskQueue(ownerLookup)` with no
  `ReadLockLookup`) degrades to the lock-free shape; production wiring in
  `MultiThreadedSchedulerHost` always supplies a real lock.
- **Draining.** `drain(Region region, int max)` pops up to `max` runnables
  from one region's inbox and runs them in caller context (i.e., on that
  region's worker thread). A task that throws is never allowed to drop
  the tick pipeline — the exception goes to the current thread's uncaught
  exception handler instead of propagating.

Convenience constructor for callers already holding a `ThreadedRegionizer`
directly:

```java
RegionizedTaskQueue.of(ThreadedRegionizer regionizer);
```

## ChunkHolderManager — the scheduling-relevant subset

`net.multiforge.runtime.chunk.ChunkHolderManager` owns per-chunk holders
and per-region ticket state. The full ticket/load-level model (ticket
types, `ChunkLoadLevel` ladder, merge/split folding) is documented in
`docs/chunks.md`; the methods that matter for scheduling are:

```java
public NewChunkHolder holderAt(ChunkPos pos);
public boolean addTicket(RegionId owner, ChunkPos pos, Ticket ticket);
public boolean removeTicket(RegionId owner, ChunkPos pos, Ticket ticket);
```

- `holderAt(pos)` — look up the holder for a chunk position, or `null` if
  none exists yet. Cheap, read-only; safe from any thread that's allowed
  to read the manager (in practice: the owning region worker, or a
  diagnostics/observability call site).
- `addTicket` / `removeTicket` — the ticket-write path. Like
  `RegionizedTaskQueue.queueChunkTask`, these pin the section→region
  mapping across their resolve→write pair against the world's regionizer
  (the manager is constructed with a `Supplier<ThreadedRegionizer>`
  accessor for exactly this reason), so a ticket write can't land on a
  region id that dies mid-call. A successful `addTicket`/`removeTicket`
  may promote or demote the holder's `ChunkLoadLevel` and enqueue it on
  `HolderManagerRegionData.pendingFullLoadUpdate` for the owning region's
  next tick to drain.

`ChunkTaskScheduler.scheduleChunkTask(world, x, z, run, priority)` is the
priority-aware sibling of `queueChunkTask` — see `docs/chunks.md` §Priority
routing for the full BLOCKING→IDLE deque model. It shares the same
underlying `RegionizedTaskQueue` for wake-up delivery.

## The four domains today

`MultiThreadedSchedulerHost implements SchedulerHost` and exposes exactly
the four domains `docs/api.md` documents for mods:

| Domain   | `SchedulerHost` method            | Dispatch mechanism                                                                 |
|----------|------------------------------------|--------------------------------------------------------------------------------------|
| Region   | `region(WorldRef, ChunkPos)`       | `taskQueue.queueChunkTask(world, pos.x(), pos.z(), …)` — re-resolved on every fire.  |
| Entity   | `entity(EntityRef)`                | Same as region, but re-reads `entity.chunkPos()` on every fire — safe across border crossings. Retirement checked before running the body. |
| Global   | `global()`                         | `taskQueue.queueChunkTask(globalWorld, 0, 0, …)` via a private `enqueueOnGlobal` helper — the "global region" is a synthetic region pinned at `(0,0)` in a synthetic world, ticked by the pool exactly like any other region. |
| Async    | `async()`                          | A separate `ScheduledExecutorService` pool, sized `max(2, cores/2)`. Every body runs wrapped in `OwnerToken.runAs(OwnerToken.ASYNC, …)`. |

> **There is no public `scheduleGlobal` method.** The global path is
> `ServerDomains.global().execute(...)` /
> `.run(...)` / `.runDelayed(...)` / `.runAtFixedRate(...)`, same shape as
> every other domain. Internally, `MultiThreadedSchedulerHost` implements
> this via a private `enqueueOnGlobal(Runnable)` helper that calls
> `taskQueue.queueChunkTask(globalRegionizer.world(), 0, 0, r)` — worth
> knowing if you're reading the source, but not something patch code
> should call directly; go through `ServerDomains.global()` like any mod
> would.

Delayed and repeating tasks (`runDelayed`, `runAtFixedRate`) never run
their body directly on the `delayedExec` scheduling thread. Each fire
re-enqueues through the same `queueChunkTask`/`enqueueOnGlobal` path used
for immediate dispatch — this is what makes a repeating region task safe
across a merge or split that happens between iterations: ownership is
re-resolved every time, never captured once and reused.

## Do's and don'ts

- **Do** always route cross-region work through
  `RegionizedTaskQueue.queueChunkTask` (or the `ServerDomains`/`ScheduledTask`
  wrappers built on top of it). Never reach into another region's holder
  map, entity list, or block state directly, even for a read — a
  concurrent merge/split can invalidate what you're holding mid-read.
- **Don't** call `.get()` or `.join()` on a `CompletableFuture` from a
  region worker thread. CLAUDE.md rule 4 is absolute here: no blocking
  calls on a region worker, ever. If you need a result from cross-region
  or async work, structure it as a continuation (`ScheduledTask` callback
  or a follow-up `queueChunkTask` call), not a blocking wait.
- **Don't** hold a `synchronized` block on a region worker that could
  contend with a foreign region's worker. Region-to-region coordination
  goes through the task queue, not shared locks.
- **Do** treat every `ScheduledTask` as re-resolving its target on each
  fire, not capturing it once. This is true of `region`, `entity`, and
  `global` dispatch; only `async` bodies genuinely run on a fixed pool
  thread with no re-resolution concept (because async work isn't supposed
  to touch region-owned state at all).
- **Don't** assume a chunk task landing in the orphan queue is lost. It
  isn't — but it also isn't instantaneous. If your logic needs
  "guaranteed eventually delivered, once the chunk loads," that's exactly
  what the orphan-bucket + `rerouteAtChunk` path gives you; if it needs
  "delivered now or not at all," check `holderAt`/ownership first and
  branch instead of enqueueing blindly.

## Recipes

**Run this on chunk X:**

```java
ServerDomains.region(world, new ChunkPos(cx, cz))
    .execute(MOD, () -> {
        // Runs on whichever region worker currently owns (cx, cz).
    });
```

**Run this once at global tick N (a fixed number of ticks from now):**

```java
ServerDomains.global()
    .runDelayed(MOD, handle -> {
        // Runs on the global-region thread, N ticks from now.
    }, /* delayTicks */ N);
```

**Poll every 20 ticks:**

```java
ServerDomains.region(world, pos)
    .runAtFixedRate(MOD, handle -> {
        // Runs on the region owning `pos`, every 20 ticks, starting
        // 20 ticks from now. `handle.cancel()` stops future iterations.
    }, /* initialTicks */ 20L, /* periodTicks */ 20L);
```

**React once a specific entity is retired (killed/unloaded/gone
cross-dimension) instead of firing again:**

```java
ServerDomains.entity(mob).runAtFixedRate(MOD,
    handle -> mob.hurt(0.5f),
    () -> log.info("mob {} retired; task stopped", mob.uuid()),
    /* initial */ 20L,
    /* period  */ 20L);
```

All four recipes above are safe to call from any thread — the actual
mutation body only ever runs once dispatch has resolved (or re-resolved)
the correct owning domain.

## Test coverage

- `net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntimeTest` —
  install/current/shutdown lifecycle, `AlreadyInstalledException` vs.
  foreign-host `IllegalStateException`.
- `net.multiforge.runtime.scheduler.MultiThreadedSchedulerHostTest` —
  end-to-end region/global/async/entity scheduling and repeating tasks
  against the parallel host (also referenced from `docs/regions.md`).
- `net.multiforge.runtime.scheduler.SingleThreadedSchedulerHostTest` —
  the M1 reference implementation mods can compile/test against before
  parallel dispatch exists.
- `net.multiforge.runtime.scheduler.RuntimeLifecycleReviewFixesTest` —
  regression coverage for lifecycle edge cases found in review passes
  (reroute-target rebinding across shutdown/restart, journal lifecycle
  interaction).
- `net.multiforge.runtime.region.RegionizedTaskQueueTest` — FIFO
  ordering, orphan reroute, exception isolation (also referenced from
  `docs/regions.md`).
- `net.multiforge.runtime.chunk.ChunkHolderManagerTest` — ticket
  promotion/demotion, merge/split folding (also referenced from
  `docs/chunks.md`).
