# MultiForge Scheduler API (M1)

The `multiforge-api` jar is what your mod compiles against. It has zero
runtime dependencies — no Minecraft, no NeoForge, no slf4j — so you can
add it as a normal `compileOnly` (or `implementation` if you shade its
few types) without dragging anything else in.

```gradle
repositories {
    maven { url = uri("https://maven.multiforge.example/releases") }
}
dependencies {
    compileOnly("net.multiforge:multiforge-api:0.1.0")
}
```

At runtime, MultiForge's server jar supplies the implementation. When
your mod runs on **vanilla NeoForge** (no MultiForge), the first
scheduler call throws `IllegalStateException` with an actionable
message — you can either treat MultiForge as required, or guard the
call with `try { … } catch (LinkageError|IllegalStateException e) { …
fall back to `event.enqueueWork` … }`.

---

## Two ways to call the same scheduler

### Native façade (recommended)

`net.multiforge.api.scheduler.ServerDomains` is one static entry point
for all four domains:

```java
import net.multiforge.api.mod.ModIdentifier;
import net.multiforge.api.scheduler.ServerDomains;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;

static final ModIdentifier MOD = ModIdentifier.of("my_mod");

void onBlockPlaced(WorldRef world, int cx, int cz) {
    ServerDomains.region(world, new ChunkPos(cx, cz))
        .execute(MOD, () -> {
            // Runs on the region worker that owns (cx, cz), even after
            // a merge/split. Safe to mutate world/entities in this
            // region.
        });
}
```

### Folia-shaped mirror

If you're porting a Folia plugin, `net.multiforge.api.folia.*` matches
Folia's signatures — the port is mostly a package rename:

```java
import net.multiforge.api.folia.RegionScheduler;

RegionScheduler.execute(MOD, world, cx, cz, () -> { /* … */ });
```

Both APIs delegate to the same host; there's no performance difference.

---

## Domains

| Domain           | Static entry                              | Runs on                                                                                                     |
|------------------|-------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| Region           | `ServerDomains.region(world, chunkPos)`   | The region worker that currently owns the chunk. Re-resolved at dispatch time.                              |
| Entity           | `ServerDomains.entity(entity)`            | The region worker owning the entity — even across border crossings. Fires `retired` callback on removal.    |
| Global           | `ServerDomains.global()`                  | The dedicated global-region thread (weather, time, world border, dragon fight, wither, raids, scoreboards). |
| Async            | `ServerDomains.async()`                   | Shared async pool. **Must not touch game state.**                                                           |

Every method returns a `ScheduledTask` handle with a state machine
matching Folia's:

```
IDLE ─┬─► EXECUTING ─┬─► FINISHED
      │              └─► CANCELLED_RUNNING
      └─► CANCELLED
```

`ScheduledTask.cancel()` is thread-safe. It returns `true` iff the call
transitioned to `CANCELLED` or `CANCELLED_RUNNING`; a task that's
already terminal returns `false`.

---

## Delays and repeats

All four domains expose the same shape:

```java
region.execute(mod, Runnable);                        // fire once, ASAP
region.run(mod, Consumer<ScheduledTask>);             // ASAP with a handle
region.runDelayed(mod, Consumer<ScheduledTask>, delayTicks);
region.runAtFixedRate(mod, Consumer<ScheduledTask>, initialTicks, periodTicks);
```

For async, the delay unit is a `TimeUnit`, not ticks:

```java
async.runDelayed(mod, task, 500, TimeUnit.MILLISECONDS);
```

Cancel by mod (async only — kill everything a mod has scheduled at
shutdown):

```java
int stopped = ServerDomains.async().cancelTasks(mod);
```

---

## Entity retirement

If the entity is removed (killed, unloaded, cross-dimension gone) before
your task can run, the `retired` runnable fires instead. Repeating tasks
stop firing after retirement.

```java
ServerDomains.entity(mob).runAtFixedRate(MOD,
    handle -> mob.hurt(0.5f),
    () -> log.info("mob {} died; task retired", mob.uuid()),
    /* initial */ 20L,
    /* period  */ 20L);
```

---

## Event dispatch annotations

Unannotated event handlers dispatch on `LEGACY_SERIAL` — MultiForge
routes them through a per-mod serialised executor and auto-reroutes any
cross-region access with a warn. Fast enough for the 90 % case; opt in
to a stronger contract when you've audited your handler.

```java
import net.multiforge.api.event.DispatchDomain;
import net.multiforge.api.event.DispatchDomainKind;
import net.multiforge.api.event.Ordering;
import net.multiforge.api.event.OrderingContract;

@DispatchDomain(DispatchDomainKind.REGION)
@Ordering(OrderingContract.PER_REGION)
@SubscribeEvent
public static void onBlockBreak(BlockEvent.BreakEvent event) {
    // Runs on the region worker that owns event.getPos().
}
```

Both annotations may also target a whole `@EventBusSubscriber` class,
in which case every listener inherits the setting.

- `DispatchDomainKind.REGION` — dispatched on the region owner of the
  event's target (chunk / entity / block).
- `DispatchDomainKind.GLOBAL` — dispatched on the global thread. Use
  for events with no natural spatial owner (weather, gamerule change).
- `DispatchDomainKind.ASYNC` — dispatched on the async pool. Handler
  must not touch game state.
- `DispatchDomainKind.LEGACY_SERIAL` — default; per-mod serialised
  executor with reroute-and-warn on cross-region access.
- `OrderingContract.PER_REGION` — default; events for the same region
  observe program order.
- `OrderingContract.GLOBAL_TOTAL` — total order across the whole
  server. Expensive under parallel ticks.
- `OrderingContract.BEST_EFFORT` — no ordering promises. Cheapest.

---

## Getting handles at runtime

MultiForge injects the necessary marker types via the NeoForge event
bus (`ServerAboutToStartEvent`, etc.). The bindings ship in
`multiforge-runtime`; you get to them via NeoForge's normal accessors.
As a shortcut, the runtime also publishes helpers:

```java
// From a ServerLevel:
WorldRef world = MultiForge.worldOf(level);        // multiforge-runtime helper

// From a Vanilla Entity:
EntityRef ref = MultiForge.entityOf(entity);       // multiforge-runtime helper
```

(These helpers land in the M2 patch. In M1 the API surface exists; the
runtime binding forwards to the single-threaded reference host.)

---

## What M1 gives up

M1 ships the **API** and a **single-threaded reference implementation**
so mod authors can compile, unit-test, and integration-test against a
stable surface. Actual per-region parallelism arrives in M2 — the same
API, the same calls, but tasks land on separate worker threads instead
of all serialising through one. No source or binary changes required.

If you write your mod against the M1 API today, it will run on M2, M3,
and later without recompiling.

---

## FAQ

**Do I have to declare which mode I want?**  
No. Unannotated handlers are legal. The default behaviour is safe —
serialised per mod with auto-reroute.

**What if MultiForge isn't installed?**  
The first API call throws `IllegalStateException` with a clear message.
Detect at mod init if you care:

```java
try {
    ServerDomains.global();
    // MultiForge is present — use the fast paths.
} catch (Throwable t) {
    // Vanilla NeoForge — fall back to event.enqueueWork(...) etc.
}
```

**Can I mix the two APIs?**  
Yes, freely. They're thin wrappers over the same host.

**Is there a `runAsync` that gives me a `CompletableFuture`?**  
Not in M1. Wrap it yourself:

```java
CompletableFuture<Result> f = new CompletableFuture<>();
ServerDomains.async().runNow(mod, task -> {
    try {
        f.complete(compute());
    } catch (Throwable t) {
        f.completeExceptionally(t);
    }
});
```

Adding a first-class `CompletableFuture` shim is on the M5 backlog.
