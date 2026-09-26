# MultiForge Public API

`multiforge-api` is the jar a mod compiles against to run work on the
right thread under MultiForge, and to tell MultiForge how its event
listeners may be dispatched. It has no Minecraft, NeoForge or logging
dependency (only JetBrains annotations, `compileOnly`). Everything in it
lives under `net.multiforge.api`:

| Package | Contents |
|---|---|
| `net.multiforge.api.scheduler` | `ServerDomains`, the four domain interfaces, `ScheduledTask`, `TaskState` |
| `net.multiforge.api.folia` | Folia-shaped static wrappers over the same domains |
| `net.multiforge.api.event` | `@DispatchDomain`, `DispatchDomainKind`, `@Ordering`, `OrderingContract` |
| `net.multiforge.api.world` | `WorldRef`, `ChunkPos`, `BlockPos` |
| `net.multiforge.api.entity` | `EntityRef` |
| `net.multiforge.api.mod` | `ModIdentifier` |
| `net.multiforge.api` | `@RegionThread` (scanner marker), `MultiForgeApi.VERSION` |
| `net.multiforge.api.spi` | `SchedulerHost` (implemented by the runtime; not for mods) |

## Getting the jar

The Gradle project publishes `net.multiforge:multiforge-api` with
`maven-publish` (`./gradlew :multiforge-api:publishToMavenLocal` for a
local copy); the release job configures the remote repository. Depend on
it `compileOnly`, since the MultiForge server provides it at run time:

```gradle
dependencies {
    compileOnly("net.multiforge:multiforge-api:<version>")
}
```

`MultiForgeApi.VERSION` reports the API version baked into the jar.

## When the API works

`ServerDomains` is bound to the live scheduler in
`ServerLifecycleHooks.handleServerAboutToStart`, before
`ServerAboutToStartEvent` is posted. Call it from `ServerAboutToStartEvent`
onwards.

- **Vanilla NeoForge** (no MultiForge runtime on the classpath): the first
  `ServerDomains` call throws `IllegalStateException` ("No SchedulerHost is
  installed…").
- **MultiForge before server start, or with `mode = "off"`**: no host is
  bound yet, so the first call binds whatever `SchedulerHost` the
  `ServiceLoader` finds, which is the runtime's
  `SingleThreadedSchedulerHost` reference implementation. It runs region,
  entity and global tasks on its own `multiforge-tick-worker` thread, not on
  the server thread. A binding made before server start also makes the
  runtime's own install fail. Don't call `ServerDomains` from mod
  construction or setup events.

To detect MultiForge without calling the scheduler:

```java
boolean multiforge;
try {
    Class.forName("net.multiforge.api.scheduler.ServerDomains");
    multiforge = true;
} catch (ClassNotFoundException e) {
    multiforge = false;
}
```

## Domains

`net.multiforge.api.scheduler.ServerDomains` is the entry point:

| Domain | Entry | Where the task runs |
|---|---|---|
| Region | `ServerDomains.region(WorldRef, ChunkPos)` | On the region worker owning that chunk, at the region's next mailbox drain. |
| Entity | `ServerDomains.entity(EntityRef)` | On the region owning the entity's chunk; skipped, with a `retired` callback, if the entity is gone. |
| Global | `ServerDomains.global()` | On the synthetic global region, ticked once per server tick on the worker pool before any level's regions. |
| Async | `ServerDomains.async()` | On a shared pool of `max(2, cores / 2)` threads. Must not touch game state. |

Details that follow from the implementation
(`MultiThreadedSchedulerHost`):

- **Region tasks.** The owning region is looked up when the task is
  queued (and again for each run of a delayed or repeating task). If the
  regions merge before it runs, the task moves with the mailbox. If no
  region owns the chunk (it is not loaded), the task waits until that
  chunk loads; if it never loads, the task never runs. A region drains up
  to 128 mailbox tasks at the start of its tick and again at the end.
  An exception thrown by a task goes to the worker thread's
  uncaught-exception handler and does not stop the region.
- **Entity tasks.** The entity's chunk is read from `EntityRef.chunkPos()`
  when the task is queued; the retirement check (`EntityRef.isRetired()`)
  runs on the region worker immediately before the body. If the entity
  crossed into another region between queueing and running, the body runs
  on the region it was queued for.
- **Global tasks.** The global region is not the server thread. It owns
  no chunk of any real level, so a world write from a global task is a cross-region write:
  rerouted to the owner with a warning, or an exception in strict mode.
  Use a region task to change the world.
- **Async tasks.** The body runs under the `ASYNC` owner token; any world
  write from it is an ownership violation and is rerouted. An exception
  thrown by an async body is not logged, and a repeating async task that
  throws stops repeating.
- **Delays.** `delayTicks`/`periodTicks` are converted to wall-clock time
  at 50 ms per tick on a timer thread; when the timer fires, the task is
  queued and runs at the owning region's next drain. They are not counted
  in server ticks, so a lagging server does not stretch them.

## Calling a domain

```java
import net.multiforge.api.mod.ModIdentifier;
import net.multiforge.api.scheduler.ServerDomains;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;

static final ModIdentifier MOD = ModIdentifier.of("my_mod");

void refreshMachine(ServerLevel level, BlockPos pos) {
    WorldRef world = WorldRef.of(level.dimension().location().toString());
    ServerDomains.region(world, ChunkPos.ofBlock(pos.getX(), pos.getZ()))
        .execute(MOD, () -> {
            // Runs on the region worker that owns this chunk.
        });
}
```

`WorldRef` identifies a level by its dimension id; build it exactly as
above (`WorldRef.of("minecraft:overworld")` for the overworld). The API has
no helper that converts a `Level`, `ChunkPos` or `Entity` for you.

`ModIdentifier.of(id)` requires your mod id (`[a-z][a-z0-9_-]{1,63}`).

Region, entity and global domains share one shape:

```java
region.execute(mod, Runnable);                                   // once, as soon as possible
region.run(mod, Consumer<ScheduledTask>);                        // once, with a handle
region.runDelayed(mod, Consumer<ScheduledTask>, delayTicks);
region.runAtFixedRate(mod, Consumer<ScheduledTask>, initialTicks, periodTicks);
```

The entity domain takes an extra `Runnable retired` before the delays:

```java
entity.execute(mod, Runnable task, Runnable retired);
entity.runAtFixedRate(mod, Consumer<ScheduledTask>, Runnable retired, initialTicks, periodTicks);
```

The async domain uses a `TimeUnit`:

```java
async.runNow(mod, Consumer<ScheduledTask>);
async.runDelayed(mod, Consumer<ScheduledTask>, delay, TimeUnit);
async.runAtFixedRate(mod, Consumer<ScheduledTask>, initial, period, TimeUnit);
int stopped = async.cancelTasks(mod);   // cancel everything this mod scheduled on the async pool
```

### ScheduledTask

Every call returns a `ScheduledTask` handle: `state()`, `owner()`,
`isRepeating()` and `cancel()`.

```
IDLE ─┬─► EXECUTING ─┬─► FINISHED
      │              └─► CANCELLED_RUNNING
      └─► CANCELLED
```

`cancel()` is thread-safe and returns `true` iff it moved the task to
`CANCELLED` or `CANCELLED_RUNNING`; a task already in a terminal state
returns `false`. Cancelling a repeating task between runs stops it.

### EntityRef

The runtime does not provide an `EntityRef` for Vanilla entities; a mod
implements the four methods itself:

```java
record MobRef(Entity entity) implements EntityRef {
    public UUID uuid() { return entity.getUUID(); }
    public WorldRef world() { return WorldRef.of(entity.level().dimension().location().toString()); }
    public ChunkPos chunkPos() {
        var c = entity.chunkPosition();
        return new ChunkPos(c.x, c.z);
    }
    public boolean isRetired() { return entity.isRemoved(); }
}

ServerDomains.entity(new MobRef(mob)).runAtFixedRate(MOD,
    handle -> mob.heal(1.0f),
    () -> LOGGER.info("mob {} is gone; task retired", mob.getUUID()),
    /* initial */ 20L,
    /* period  */ 20L);
```

### Folia-shaped wrappers

`net.multiforge.api.folia.{RegionScheduler, EntityScheduler,
GlobalRegionScheduler, AsyncScheduler}` are static methods that forward to
`ServerDomains`, with Folia-like argument order (chunk coordinates as
`int`s, the entity as a parameter). There is no behavioural difference.

```java
RegionScheduler.execute(MOD, world, chunkX, chunkZ, () -> { /* … */ });
```

## Event dispatch annotations

`@DispatchDomain` and `@Ordering` may be placed on a listener method or on
its class (the method's annotation wins). MultiForge reads them when the
listener is registered with `NeoForge.EVENT_BUS`, through `register(...)`
or `addListener(...)`.

```java
import net.multiforge.api.event.DispatchDomain;
import net.multiforge.api.event.DispatchDomainKind;

@DispatchDomain(DispatchDomainKind.REGION)
@SubscribeEvent
public static void onBlockBreak(BlockEvent.BreakEvent event) {
    // Runs on the region worker that posted the event.
}
```

For an event posted on a region worker:

- `REGION` — runs on the posting worker, in parallel with other regions.
- `GLOBAL` and `LEGACY_SERIAL` — run on the serial lane: the server
  thread, one listener at a time, while the worker waits. Cancellation and
  results reach the poster.
- `ASYNC` — runs on the async event pool; the poster does not wait, so
  the listener cannot cancel the event or set a result, and must not touch
  game state.
- `@Ordering(OrderingContract.GLOBAL_TOTAL)` sends the listener to the
  serial lane whatever its domain. `PER_REGION` (the default) and
  `BEST_EFFORT` do not change dispatch.

An event posted on any other thread runs every listener on the posting
thread, as NeoForge does. A listener without `@DispatchDomain` gets its
domain from its mod's safety classification (`legacy`, `hybrid-safe`,
`strict-safe`) and the event type. The full rules and the per-event
default table are in [`events.md`](events.md).

## @RegionThread

`net.multiforge.api.RegionThread` marks a method (the annotation also
targets types) as running on a region worker. It has no effect at run
time. The scanner (`multiforge-scanner`) treats an annotated method as a
root of its tick-reachability analysis and reports blocking calls inside
it (rule R03); see [`certification.md`](certification.md). The scanner
reads the annotation on methods only; on a type it is currently ignored.
