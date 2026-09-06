# M12 Design — Transparent Event-Bus Routing

Status: **design, not yet implemented.** This document is the definitive
design for blueprint milestone M12 — "`IEventBus.post` honors
`@DispatchDomain` annotations" (`docs/blueprint.md:779-784`). It supersedes
the "Future: full M12 enforcement" section of `docs/events.md` as the
detailed spec; `docs/events.md` remains the source of truth for the
per-event target-domain table.

## 1. Context and status

### 1.1 What exists today

`multiforge-api/src/main/java/net/multiforge/api/event/` defines the full
annotation contract:

- `DispatchDomain.java` — `@DispatchDomain(DispatchDomainKind)`, targets
  `METHOD` or `TYPE` (an `@EventBusSubscriber` class).
- `DispatchDomainKind.java` — four values: `REGION`, `GLOBAL`, `ASYNC`,
  `LEGACY_SERIAL` (default).
- `Ordering.java` — `@Ordering(OrderingContract)`, same targets.
- `OrderingContract.java` — `PER_REGION` (default), `GLOBAL_TOTAL`,
  `BEST_EFFORT`.

All four types are fully defined, documented for mod authors
(`docs/api.md:132-168`, the "Event dispatch annotations" walkthrough), and
**completely inert**. Nothing in `multiforge-runtime` or
`multiforge-patches` reads them. Every real NeoForge event fires exactly
where NeoForge's own event bus fires it — inline, on whatever thread called
`post`.

`docs/events.md:134` records this plainly:

> Status: **not started.** The annotation types exist in `multiforge-api`
> and are documented for mod authors (`docs/api.md`), but no listener
> registration path in `multiforge-runtime` or the `multiforge-patches`
> tree reads them yet.

This document changes that line to "in progress; see
`docs/design/m12-event-routing.md`" (see §13) and lays out exactly what
"started" needs to build.

`docs/concurrency-contract.md:248-251` lists the same gap under "What's
not built yet":

> Enforcement of `@DispatchDomain` at event-dispatch time (blueprint M12).
> The annotation and its four-value `DispatchDomainKind` enum are defined
> today; no listener registration path reads them yet.

And `docs/legacy-compat.md:22-77` documents the fallback every unannotated
handler rides today — `LEGACY_SERIAL`, backed by
`OwnershipEnforcer.Mode.REROUTE` reactively catching cross-region
mutations after the fact, not proactive dispatch placement. M12 does not
replace that mechanism; see §11.

### 1.2 Why this matters

Today, `@DispatchDomain(DispatchDomainKind.REGION)` on a handler is
documentation, not code. A mod author who follows `docs/api.md`'s
walkthrough and annotates a `BlockEvent.BreakEvent` handler `REGION` gets
exactly the same runtime behavior as if they'd left it unannotated: the
handler runs inline on whatever thread NeoForge's `EventBus.post` is
called from, and `OwnershipEnforcer` reactively reroutes if that turns out
to be wrong. M12 is what makes the annotation *proactive*: the dispatcher
places the handler invocation on the correct thread before it runs, so the
reroute path is a safety net instead of the only mechanism.

## 2. User-confirmed design decision

**Wrap `NeoForge.EVENT_BUS` transparently at `ServerAboutToStart`. No mod
opt-in required.**

Every mod that calls `NeoForge.EVENT_BUS.register(...)` or fires an event
via `NeoForge.EVENT_BUS.post(...)` continues to write exactly the code it
writes today. `DispatchDomain`/`Ordering` annotations, when present, are
honored automatically; when absent, the handler rides `LEGACY_SERIAL` as
it does today. There is no new API surface a mod author has to touch to
get correct dispatch — the existing `docs/api.md:132-168` walkthrough is
already the complete mod-author-facing contract, and M12 is purely a
runtime-side change to make it real.

### 2.1 Preferred implementation: patch the `NeoForge.java` initializer

`upstream/neoforge-1.21.1/src/main/java/net/neoforged/neoforge/common/NeoForge.java:17`
constructs the static bus:

```java
public static final IEventBus EVENT_BUS = BusBuilder.builder().startShutdown().classChecker(eventType -> {
    ...
}).build();
```

The M12 patch (`multiforge-patches/09-events/`, see §12) replaces this
initializer to wrap the built bus:

```java
public static final IEventBus EVENT_BUS =
        net.multiforge.neoforge.event.EventBusBridge.wrap(BusBuilder.builder()
                .startShutdown()
                .classChecker(eventType -> { ... })
                .build());
```

`EventBusBridge.wrap(IEventBus real)` returns `real` unchanged when
`-Dmultiforge.event-dispatch=off` is set (§8), otherwise returns a new
`DispatchingEventBus` wrapping it. This keeps the vendored-tree diff to a
single line-level change inside one file — thin per CLAUDE.md rule 6 (keep
the patch set thin, scoped per `NN-group` for rebase-ability) — while all
real logic (`DispatchingEventBus`, `AnnotationScanner`,
`RoutingListenerWrapper`, `DomainDispatcher`) lives in
`multiforge-runtime` and the fork bridge class, both of which rebase for
free across NeoForge point releases.

`ServerAboutToStart` is not actually where the wrapping *happens* — the
`EVENT_BUS` field initializes at class-load time, before any lifecycle
event fires — but it's the effective observable boundary: every mod's
`FMLJavaModLoadingContext`/`@Mod` constructor and `FMLCommonSetupEvent`
handler that grabs `NeoForge.EVENT_BUS` and calls `.register(...)` on it
does so before `ServerAboutToStart`, and by the time `ServerAboutToStart`
fires, every mod-registered listener has already gone through
`DispatchingEventBus.register`. The "at `ServerAboutToStart`" framing in
the task/blueprint language describes *when the wrapping is guaranteed to
be fully in effect for all subsequently-fired events*, not a hook
attached to that specific event.

### 2.2 Reflection alternative — documented but rejected

An alternative considered and rejected: leave `NeoForge.java` untouched,
and instead use reflection at runtime bootstrap
(`Field.setAccessible(true)` on the `static final EVENT_BUS` field,
swapping in a wrapped instance) to avoid touching vendored NeoForge source
at all.

Rejected because:

- It requires deep reflection into a `static final` field, which needs
  either `--add-opens` JVM flags or `Unsafe`-based final-field mutation —
  fragile across JDK versions and exactly the kind of "reflection instead
  of a real seam" pattern CLAUDE.md warns against implicitly through its
  patch-group discipline (rule 6): a reflective monkey-patch has no patch
  file to review, no diff to rebase, and breaks silently if NeoForge
  renames or refactors the field with no compile error to catch it.
- The class-loading timing is genuinely fragile: if any static initializer
  elsewhere in the classloading graph captures a reference to
  `NeoForge.EVENT_BUS` before our reflective swap runs (plausible — mod
  static initializers can run early), that captured reference is the
  *original*, un-wrapped bus, and our swap silently does nothing for that
  consumer.
- A one-line patch in `multiforge-patches/09-events/` is auditable,
  diffable, and shows up in code review exactly like every other M7-M11
  patch. It is the pattern this project has used for every prior
  interception point (`01-ownership/` through `08-globals/`) — reflection
  would be the odd one out.

## 3. Interception architecture

```
                    ┌─────────────────────────────┐
mod code  ────────► │      DispatchingEventBus      │
 .register(obj)      │  (implements IEventBus)       │
 .post(event)        └───────────────┬───────────────┘
                                      │
                     scan target class for @SubscribeEvent
                     methods; for each, resolve
                     AnnotationScanner.scan(method)
                     → (Domain, OrderingContract)
                                      │
                                      ▼
                     wrap each method's dispatch in a
                     RoutingListenerWrapper, cache
                     (Method → MetadataEntry)
                                      │
                                      ▼
                    ┌─────────────────────────────┐
                    │   inner IEventBus (real,      │
                    │   net.neoforged.bus.EventBus) │
                    └───────────────┬───────────────┘
                                      │ post() drives real
                                      │ dispatch, priority-ordered
                                      ▼
                     RoutingListenerWrapper.invoke(evt)
                     consults cached metadata, calls
                     DomainDispatcher.dispatch(...)
                                      │
                     ┌────────────────┼────────────────┐
                     ▼                ▼                 ▼
               inline call    queueChunkTask(...)  async pool /
                                (REGION/GLOBAL)      LEGACY_SERIAL
```

### 3.1 `DispatchingEventBus`

`net.multiforge.neoforge.event.DispatchingEventBus` implements
`net.neoforged.bus.api.IEventBus` — the interface shipped in the compiled
`net.neoforged:bus:8.0.1` jar dependency (declared as
`eventbus_version=8.0.1` in `upstream/neoforge-1.21.1/gradle.properties:28`
and consumed via `upstream/neoforge-1.21.1/projects/neoforge/build.gradle:145`).
Sources for this artifact are **not vendored** — MultiForge compiles
against the published jar
(`net.neoforged/bus/8.0.1/bus-8.0.1.jar` in the Gradle module cache), so
`DispatchingEventBus` is a from-scratch implementation of the public
interface, not a subclass of the concrete `net.neoforged.bus.EventBus`.

Confirmed interface shape (decompiled from the 8.0.1 jar via `javap`,
since sources aren't vendored):

```java
public interface IEventBus {
    void register(Object);
    <T extends Event> void addListener(Consumer<T>);
    <T extends Event> void addListener(Class<T>, Consumer<T>);
    <T extends Event> void addListener(EventPriority, Consumer<T>);
    <T extends Event> void addListener(EventPriority, Class<T>, Consumer<T>);
    <T extends Event> void addListener(EventPriority, boolean, Consumer<T>);
    <T extends Event> void addListener(EventPriority, boolean, Class<T>, Consumer<T>);
    <T extends Event> void addListener(boolean, Consumer<T>);
    <T extends Event> void addListener(boolean, Class<T>, Consumer<T>);
    void unregister(Object);
    <T extends Event> T post(T);
    <T extends Event> T post(EventPriority, T);
    void start();
}
```

`DispatchingEventBus` delegates every method to an inner `IEventBus real`
except `register(Object)` and the `addListener(...)` family — the entry
points where a listener becomes observable to the bus. `post(...)`,
`unregister(...)`, and `start()` pass straight through to `real`; the
routing work all happens at registration time by wrapping what gets
handed to `real`, not by intercepting `post`.

**Correction versus the shorthand "wraps every `IEventListener`" framing
used in early planning notes:** the 8.0.1 API has no public
`IEventListener` type. The closest type is the abstract class
`net.neoforged.bus.api.EventListener` (single abstract method
`invoke(Event)`), which is constructed internally by the real `EventBus`
implementation and never exposed to callers of `register`/`addListener`.
Concretely, `DispatchingEventBus` cannot "wrap the `EventListener` NeoForge
was about to create and hand it back" — that object doesn't exist yet at
the point `register(Object)` is called, and the concrete `EventBus`'s
`registerListener` (see §4.1) that creates it is `private`. Instead,
`DispatchingEventBus` wraps at the `Consumer<T>` level, which is the one
public seam `IEventBus` actually exposes:

- For `register(Object target)` (the `@SubscribeEvent`-annotated-methods
  path, used by every `@EventBusSubscriber` class and every mod that
  calls `bus.register(this)` or `bus.register(SomeClass.class)`),
  `DispatchingEventBus` does its own reflective scan of `target`'s methods
  for `@SubscribeEvent` (mirroring exactly what NeoForge's own `EventBus`
  does internally — see §4.1), and for each discovered method registers a
  `RoutingListenerWrapper`-backed `Consumer<T>` on `real` via
  `real.addListener(priority, eventClass, wrappedConsumer)` — **not** by
  calling `real.register(target)` and hoping to intercept the result.
- For the `addListener(...)` family (used by mods that skip
  `@SubscribeEvent` entirely and pass a `Consumer<T>` lambda/method
  reference directly), there is no `Method`/`Class` to read annotations
  off — a bare lambda carries no annotation metadata. These calls pass
  straight through to `real.addListener(...)` unwrapped and are treated as
  `LEGACY_SERIAL` (indistinguishable from an unannotated handler; see §11
  for the "verify in impl" note this raises for cancellable-event
  ordering).

### 3.2 `RoutingListenerWrapper`

```java
final class RoutingListenerWrapper<T extends Event> implements Consumer<T> {
    private final Consumer<T> realInvoke;      // the mod's actual handler call
    private final MetadataEntry metadata;      // cached (Domain, OrderingContract)

    @Override
    public void accept(T event) {
        DomainDispatcher.dispatch(metadata, event, () -> realInvoke.accept(event));
    }
}
```

`realInvoke` is the same `Consumer<T>` NeoForge's own reflective
`registerListener` would have built (a `LambdaMetafactory`-generated
call into the target method) — `DispatchingEventBus` builds an equivalent
one via `MethodHandles`/reflection when scanning `@SubscribeEvent`
methods itself, since it cannot obtain NeoForge's private one.

### 3.3 `DomainDispatcher`

Pure routing logic, no bus-specific types — see §5 for the full decision
table it implements. Signature:

```java
final class DomainDispatcher {
    static void dispatch(MetadataEntry metadata, Event event, Runnable invoke);
}
```

## 4. Listener-metadata scan

### 4.1 Hook point

The real `net.neoforged.bus.EventBus` (compiled from
`net/neoforged/bus/EventBus.java` in the 8.0.1 jar; decompiled signature
via `javap -p`) has this shape:

```java
public void register(Object target);                                    // public entry
private void registerListener(Object target, Method real, Method factory); // private impl
```

`registerListener` is where NeoForge's own bus reads `@SubscribeEvent`
(including its `priority` and `receiveCanceled` attributes) off each
method of `target`'s class and builds the dispatch `Consumer`. This
confirms the shape referenced in the original explore pass, but — because
`registerListener` is `private` on a concrete class MultiForge does not
subclass (`DispatchingEventBus` implements the *interface*, wrapping the
real bus by composition, not inheritance) — MultiForge cannot call or
override it directly. `DispatchingEventBus.register(Object target)`
instead performs an equivalent scan independently:

1. Reflectively enumerate `target.getClass()`'s methods (walking to the
   class if `target` is itself a `Class<?>`, matching NeoForge's own
   static-subscriber convention for `@EventBusSubscriber` classes).
2. For each method carrying `@SubscribeEvent`, resolve
   `AnnotationScanner.scan(method)` (§4.2).
3. Build a `MethodHandle`/reflective `Consumer<T>` for the method (the
   `realInvoke` in §3.2).
4. Register `new RoutingListenerWrapper<>(realInvoke, metadata)` on
   `real` via `real.addListener(subscribeEvent.priority(), eventClass,
   wrapperAsConsumer)`.

### 4.2 `AnnotationScanner`

```java
final class AnnotationScanner {
    record MetadataEntry(DispatchDomainKind domain, OrderingContract ordering) {}

    private static final Map<Method, MetadataEntry> CACHE = new ConcurrentHashMap<>();

    static MetadataEntry scan(Method method) {
        return CACHE.computeIfAbsent(method, AnnotationScanner::resolve);
    }

    private static MetadataEntry resolve(Method method) {
        DispatchDomain domainAnn = method.getAnnotation(DispatchDomain.class);
        if (domainAnn == null) {
            domainAnn = method.getDeclaringClass().getAnnotation(DispatchDomain.class);
        }
        DispatchDomainKind domain = domainAnn != null ? domainAnn.value() : DispatchDomainKind.LEGACY_SERIAL;

        Ordering orderingAnn = method.getAnnotation(Ordering.class);
        if (orderingAnn == null) {
            orderingAnn = method.getDeclaringClass().getAnnotation(Ordering.class);
        }
        OrderingContract ordering = orderingAnn != null ? orderingAnn.value() : OrderingContract.PER_REGION;

        return new MetadataEntry(domain, ordering);
    }
}
```

- Method-level annotation wins over class-level (`@EventBusSubscriber`
  class annotated `@DispatchDomain(GLOBAL)`, one method inside it
  overridden to `@DispatchDomain(REGION)` — the method wins).
- Cache is keyed by `Method` identity (reflective `Method` objects are
  stable/interned per-declaring-class per JVM run, so identity-keying via
  a plain `Map<Method, MetadataEntry>` — not a `WeakHashMap`, since
  `Method` objects for loaded classes live for the JVM's lifetime anyway —
  is correct and avoids a `Class`+`String` composite-key allocation on
  every scan). Scan happens once per method, at `register` time, not per
  `post`.

### 4.3 Priority preservation

`@SubscribeEvent(priority = ...)` (`EventPriority`: `HIGHEST`, `HIGH`,
`NORMAL`, `LOW`, `LOWEST`) is read alongside `@DispatchDomain`/`@Ordering`
in the same scan pass and passed straight through to
`real.addListener(priority, eventClass, wrapper)` unchanged. The real bus
still owns priority-ordered dispatch within a single `post()` call — M12
does not reimplement that. See §7 for what happens to priority order when
a batch gets deferred to a region worker.

## 5. Dispatch decision tree

`DomainDispatcher.dispatch(metadata, event, invoke)` reads the calling
thread's `OwnerToken.current()` (`OwnerToken.current().domain()`,
`multiforge-runtime/src/main/java/net/multiforge/runtime/ownership/OwnerToken.java:42`)
as the **caller domain**, and `metadata.domain()` as the **listener
domain**, then applies:

| Caller domain | Listener domain | Action |
|---|---|---|
| `REGION` | `REGION`, same region as caller | Inline — `invoke.run()` directly, no hop. |
| `REGION` | `REGION`, different region than caller (or than the event's own location; see §5.1) | Enqueue on the listener's target region via `RegionizedTaskQueue.queueChunkTask(WorldRef, chunkX, chunkZ, Runnable)` (`multiforge-runtime/src/main/java/net/multiforge/runtime/region/RegionizedTaskQueue.java:132`). |
| `REGION` | `GLOBAL` | Enqueue on the global region's inbox — same `queueChunkTask` call routed at `(0, 0)` against the global regionizer's `WorldRef`, mirroring `MultiThreadedSchedulerHost.enqueueOnGlobal` (`multiforge-runtime/src/main/java/net/multiforge/runtime/scheduler/MultiThreadedSchedulerHost.java:1073-1075`). |
| `GLOBAL` | `REGION` | Enqueue on the region derived from the event's location (§5.1), via `queueChunkTask`. If the event has no derivable location, treat as `GLOBAL`→`GLOBAL` (inline) and log once per event type via `ViolationLogger.warn` — this is a "verify in impl" edge case (§11). |
| `GLOBAL` | `GLOBAL` | Inline. |
| any (`REGION`/`GLOBAL`/`LEGACY_SERIAL`/`UNKNOWN`) | `ASYNC` | Enqueue on the shared async pool. Pool location: reuse the existing async executor backing `ServerDomains.async()` (`multiforge-api`'s async domain, backed today by `MultiThreadedSchedulerHost`'s async pool) — M12 does not stand up a new pool. |
| any | `LEGACY_SERIAL` | Inline on the calling thread, **plus** a rate-limited warn via `ViolationLogger.warn` — Vanilla semantics preserved for un-migrated code exactly as `docs/legacy-compat.md:70-78` describes for the reactive reroute path; M12 makes this proactive (the dispatcher already knows it's `LEGACY_SERIAL` before invoking) but does not change the underlying "runs wherever it's called, warned" contract, since a dedicated `LEGACY_SERIAL` executor per `docs/legacy-compat.md:44-51` is still "designed but not yet built" and out of scope for M12. |
| `UNKNOWN` (main thread, pre-boot, or any thread with no bound `OwnerToken` — `OwnerToken.current()` defaults to `Domain.UNKNOWN`, `OwnerToken.java:42-45`) | any | Inline. Before the scheduler is up (mod construction, `FMLCommonSetupEvent`, and other early lifecycle events all fire with no region worker running yet), there is nowhere to defer *to* — every listener registered this early runs inline regardless of its declared domain. This matches today's behavior for those lifecycle stages and does not regress anything. |

### 5.1 "The event's location," concretely

For the `GLOBAL`→`REGION` row, and generally for resolving *which* region
a `REGION`-domain listener's target is, `DomainDispatcher` needs a
chunk-position (or entity/level) to hand to `queueChunkTask`. Cross-
referencing `docs/events.md:59-94`'s ~30-event table, "the event's
location" resolves as:

| Event shape | Location source |
|---|---|
| `level.BlockEvent.*`, `level.PistonEvent.*`, `level.NoteBlockEvent.*`, `level.AlterGroundEvent`, `level.ExplosionEvent.*`, `level.ExplosionKnockbackEvent` | `event.getPos()` (or the explosion's origin `BlockPos` for `ExplosionEvent`) → `SectionPos.blockToSectionCoord(...)` → chunk X/Z. |
| `level.ChunkEvent.*`, `level.ChunkDataEvent.*`, `level.ChunkWatchEvent.*`, `level.ChunkTicketLevelUpdatedEvent` | `event.getChunk().getPos()` / `event.getChunkPos()` directly — already chunk-scoped. |
| `entity.EntityJoinLevelEvent`, `entity.EntityLeaveLevelEvent`, `entity.EntityMountEvent`, `entity.EntityStruckByLightningEvent`, `entity.living.LivingHurtEvent`, `entity.living.LivingDeathEvent` | `event.getEntity().chunkPosition()` (Vanilla `Entity#chunkPosition()`, the entity's currently-tracked chunk). |
| `entity.EntityTeleportEvent` (and subtypes), `entity.EntityTravelToDimensionEvent` | Source-side region: `event.getEntity().chunkPosition()` **before** the migration completes — per `docs/events.md:83-84`, both source and destination owners get fired, so this table only governs the *first* (source) dispatch; the destination-side fire happens naturally once the entity's `OwnerToken` context has moved, per `docs/migration.md`. |
| `tick.LevelTickEvent.*`, `tick.PlayerTickEvent.*`, `tick.EntityTickEvent.*` | Already fired from inside a region-owning tick context — the caller domain is already `REGION` matching the target, so this table's `GLOBAL`→`REGION` row does not apply; these fall under the `REGION`→`REGION` same-region row in the overwhelming common case. |
| `tick.ServerTickEvent.*`, `level.ModifyCustomSpawnersEvent`, `level.SleepFinishedTimeEvent`, `entity.player.PlayerEvent.PlayerLoggedIn/OutEvent`, `CommandEvent`, `RegisterCommandsEvent`, `ServerChatEvent`, `server.ServerAboutToStartEvent` (and `Starting`/`Started`/`Stopping`/`Stopped`) | No spatial location — these are `GLOBAL`-domain listeners per `docs/events.md`'s table, so the `GLOBAL`→`REGION` row never triggers for them; they're `GLOBAL`→`GLOBAL`, inline. |

Events with no plausible location resolver that are nonetheless annotated
`REGION` are a mod-author error (annotating a `GLOBAL`-shaped event
`REGION`) — `DomainDispatcher` treats this defensively: fall back to the
global region and warn once per event-type via `ViolationLogger.warn`,
never throw (CLAUDE.md rule 5).

## 6. `@Ordering` semantics

- **`PER_REGION`** (default). Two handlers subscribed to the same event
  type, both routed to the same region, fire in registration order within
  that region (registration order is the priority-then-registration order
  the real bus already establishes — see §7). Two separate firings of the
  same event sent to the same region (e.g. two `BlockEvent.BreakEvent`s in
  the same tick, same region) fire in send order, since both land in the
  same region worker's single-threaded inbox drain
  (`RegionizedTaskQueue`'s per-region inbox is FIFO). This is the
  guarantee already implicit in "one thread per region" — `PER_REGION`
  does not add machinery beyond routing correctly.
- **`GLOBAL_TOTAL`**. Every listener carrying this ordering — regardless
  of its *own* `@DispatchDomain`, and regardless of the caller's domain —
  is routed to the global region. This is a strict total order across the
  whole server: since the global region has exactly one worker thread,
  routing every `GLOBAL_TOTAL` listener there, in send order, is
  sufficient to guarantee it. Expensive (defeats regionization for that
  listener's traffic) — reserve for genuine cross-region invariants
  (auditing, global economy plugins, that kind of thing), matching the
  `OrderingContract` javadoc's own guidance
  (`multiforge-api/.../OrderingContract.java:27-31`).
- **`BEST_EFFORT`**. No ordering guarantee at all; `DomainDispatcher`
  applies the normal §5 routing table with no extra serialization. Two
  `BEST_EFFORT` listeners on the same `ASYNC`-domain event may run
  concurrently on different async-pool threads.
- **Mixed `@Ordering` on one event type**: when multiple listeners
  subscribed to the same event class carry different `OrderingContract`
  values, *the strongest wins for the batch* — i.e. if any listener on
  `BlockEvent.BreakEvent` is `GLOBAL_TOTAL`, every listener on that event
  (for that `post()` call) is routed through the global region for that
  firing, even ones individually marked `PER_REGION`/`BEST_EFFORT`.
  Rationale: ordering is a property of the *event's delivery*, not of an
  individual listener in isolation — a `GLOBAL_TOTAL` listener that reads
  "did the `PER_REGION` listener already run" needs that listener to have
  actually run in the same total order, which is only guaranteed if both
  are on the same thread for that firing. This is computed once per event
  *type* at first-post time (strongest-seen ordering cached alongside the
  per-type `ListenerList` the real bus already builds), not per individual
  `post()` call, since the registered-listener set for a given event type
  is effectively static after mod loading completes.

## 7. Priority preservation under deferral

Within one `post()` call, the real bus dispatches its `ListenerList` for
that event type strictly in `EventPriority` order
(`HIGHEST → HIGH → NORMAL → LOW → LOWEST`), same-priority listeners in
registration order. Each `RoutingListenerWrapper.invoke` is called in that
order by the real bus — but when the wrapper's routing decision (§5) is
"enqueue," the *enqueue call itself* happens inline, in priority order,
even though the *execution* of the enqueued `Runnable` happens later on
the target region worker's inbox drain.

Because `RegionizedTaskQueue`'s per-region inbox is a FIFO
(`multiforge-runtime/.../RegionizedTaskQueue.java:143`, `inboxFor(owner).add(task)`),
enqueuing in priority order at `post()` time is sufficient to preserve
priority order at execution time — the inbox drains in the order tasks
were added, so `HIGHEST`-priority handlers that got deferred are added to
the inbox before `NORMAL`-priority ones, and drain in that same order.
This holds even when several *different* events, each triggering their
own deferred handlers, target the same region in the same tick: as long
as every deferral for a given `post()` call happens before the next
`post()` call begins (true — `post()` is synchronous on the calling
thread), cross-event ordering degrades gracefully to "whichever `post()`
call happened first, in full," which matches `PER_REGION`'s "send order"
guarantee from §6.

## 8. Safety valve: `-Dmultiforge.event-dispatch=off`

```
-Dmultiforge.event-dispatch=off
```

Skips `EventBusBridge.wrap(...)` entirely — `NeoForge.EVENT_BUS` stays the
original, unwrapped `net.neoforged.bus.EventBus` instance, and every
handler runs exactly as it does today (inline, `LEGACY_SERIAL`-equivalent
for everything, `OwnershipEnforcer`'s reactive reroute as the only safety
net). This is the documented rollback lever for a production incident
traced to M12 routing — matching the shape of the existing
`-Dmultiforge.ownership.mode` flag family
(`docs/legacy-compat.md:91-95`). Default is **on** (routing active);
operators opt out, not in.

This flag is checked once, at the `EVENT_BUS` static initializer (i.e.
class-load time, effectively JVM-boot time) — it is not a live-toggleable
runtime knob. Flipping it requires a server restart, same as
`-Dmultiforge.ownership.mode`.

## 9. Reused runtime plumbing

No new external dependencies. M12 is entirely composition of existing
pieces:

- **`OwnerToken.current()`** —
  `multiforge-runtime/src/main/java/net/multiforge/runtime/ownership/OwnerToken.java:42`.
  Supplies the caller-domain half of every `DomainDispatcher` decision.
- **`RegionizedTaskQueue.queueChunkTask(WorldRef, chunkX, chunkZ, Runnable)`** —
  `multiforge-runtime/src/main/java/net/multiforge/runtime/region/RegionizedTaskQueue.java:132`.
  The sole cross-region enqueue primitive; M12 does not add a parallel
  mailbox.
- **Global-region enqueue pattern** —
  `multiforge-runtime/src/main/java/net/multiforge/runtime/scheduler/MultiThreadedSchedulerHost.java:1073-1075`
  (`enqueueOnGlobal`, itself a thin call into `queueChunkTask` against the
  global regionizer's `WorldRef` at `(0, 0)`). `DomainDispatcher` mirrors
  this exact call shape for `GLOBAL`-domain routing rather than
  reimplementing it.
- **`ViolationLogger.warn(String site, String detail)`** —
  `multiforge-runtime/src/main/java/net/multiforge/runtime/diagnostics/ViolationLogger.java:83`
  (and the per-mod overload at line 93) — used for `LEGACY_SERIAL`
  rate-limited warnings and the defensive `REGION`-with-no-location
  fallback warning (§5.1).
- **`ProbeRegistry.bump(String name)`** —
  `multiforge-runtime/src/main/java/net/multiforge/runtime/diagnostics/ProbeRegistry.java:47`.
  `DomainDispatcher` calls `ProbeRegistry.bump("event.dispatch." + outcome)`
  for each of `region`, `global`, `async`, `legacy`, `inline`, giving
  operators `/multiforge probe event.dispatch.*` counters broken down by
  routing outcome — useful both for verifying M12 is doing what's
  expected on a live server and for capacity-planning how much traffic is
  landing on `LEGACY_SERIAL` vs. properly annotated.

## 10. Test strategy

New package: `multiforge-runtime/src/test/java/net/multiforge/runtime/event/`.

- **`DispatchingEventBusTest.java`** — 10-15 cases, one per row (or
  meaningful sub-case) of the §5 decision table:
  1. `REGION` caller → `REGION` listener, same region → inline, no
     `queueChunkTask` call observed.
  2. `REGION` caller → `REGION` listener, different region → enqueued via
     `queueChunkTask` against the listener's region.
  3. `REGION` caller → `GLOBAL` listener → enqueued at the global
     region's `(0,0)`.
  4. `GLOBAL` caller → `REGION` listener, event has a resolvable location
     → enqueued at the resolved region.
  5. `GLOBAL` caller → `REGION` listener, event has **no** resolvable
     location → falls back to inline + one `ViolationLogger.warn` call.
  6. `GLOBAL` caller → `GLOBAL` listener → inline.
  7. Any caller → `ASYNC` listener → dispatched on the async pool (assert
     via a `CountDownLatch` released from a different thread than the
     test thread).
  8. Any caller → `LEGACY_SERIAL` listener → inline + rate-limited warn;
     second immediate firing within the warn window does not re-warn.
  9. `UNKNOWN` caller (no `OwnerToken` bound) → any listener domain →
     inline.
  10. `PER_REGION` ordering — two listeners on one event, same region,
      registered in order A then B, both fire in A-then-B order.
  11. `GLOBAL_TOTAL` ordering — a `PER_REGION` listener and a
      `GLOBAL_TOTAL` listener on the same event type; assert *both* route
      to the global region for that firing (strongest-wins, §6).
  12. `BEST_EFFORT` — no ordering assertion possible by construction;
      assert only that dispatch completes and both listeners eventually
      ran (via latches), not their relative order.
  13. `EventPriority` preservation across a deferral — `HIGHEST` and
      `LOWEST` listeners both routed `REGION` to the same *different*
      region as the caller; assert the target region's inbox contains
      them in `HIGHEST`-then-`LOWEST` order.
  14. `-Dmultiforge.event-dispatch=off` — `EventBusBridge.wrap` returns
      the input bus unchanged (identity check), no `DispatchingEventBus`
      involved.
  15. `ProbeRegistry` counter bump per outcome — one assertion per §9's
      five outcome buckets.

- **`AnnotationScannerTest.java`** — cache correctness (`scan()` called
  twice on the same `Method` returns the cached instance, not a
  freshly-`resolve`d one — verifiable by identity or by a call-counter
  wrapped around `resolve`), and class-vs-method precedence (method
  `@DispatchDomain` overrides a class-level `@DispatchDomain` on the same
  method's declaring class; a method with no annotation of its own picks
  up the class-level one).

- **`RoutingListenerWrapperTest.java`** — round-trips against a **real**
  `net.neoforged.bus.api.IEventBus` instance (the compiled 8.0.1 jar is
  already on the test classpath as a transitive dependency of whatever
  brings in the NeoForge event API — no new test dependency needed),
  registering a `RoutingListenerWrapper`-backed consumer via
  `bus.addListener(...)` and posting a real event through `bus.post(...)`
  to confirm the wrapper actually receives `invoke`/`accept` calls from
  genuine bus dispatch machinery, not just from a hand-rolled test harness
  that assumes the bus's calling convention.

- **Fixture pattern**: `OwnerToken.runAs(OwnerToken, Runnable)`
  (`OwnerToken.java:52`) to fake being on a region/global/async worker
  thread inside a single-threaded test, combined with
  `CountDownLatch`/`AtomicReference` for asserting what happened on a
  *different* thread when a case actually crosses threads (the `ASYNC`
  and cross-region `REGION` cases). Precedent for this exact pattern —
  simulating "already on the target worker" via a thread marker and
  asserting synchronous-vs-deferred behavior — is
  `multiforge-runtime/src/test/java/net/multiforge/runtime/globals/BossEventSystemReentrancyTest.java:63`:

  ```java
  boolean accepted = GlobalRegionThreadMarker.runMarked(() -> sys.tryRoute(() -> ran.add("mutated")));
  ```

  `DispatchingEventBusTest` uses the equivalent `OwnerToken.runAs(...)`
  wrapper (the general-purpose version of the same idea — `OwnerToken` is
  the M9/M10-era generalization of the older per-system thread markers
  like `GlobalRegionThreadMarker`) around each caller-domain case.

## 11. Deferred / non-goals

- **Cancellable events + result-value semantics.** NeoForge's
  `ICancellableEvent` and `Event` result values (`Event.Result` on events
  that support it) are orthogonal to *where* a handler runs, but M12
  needs to verify this holds when a handler is deferred: if handler A
  (priority `HIGH`, routed `REGION`) cancels an event, and handler B
  (priority `NORMAL`, `receiveCanceled = false`) is skipped by the real
  bus's own cancellation check — does that check still work correctly
  when A's invocation was deferred via `queueChunkTask` and hasn't
  actually run by the time B's `RoutingListenerWrapper.invoke` would fire?
  **This is a "verify in impl" item, not resolved by this design.** The
  likely answer is that cross-region deferral of a cancellable event's
  earlier-priority handler breaks same-`post()`-call cancellation
  semantics for later handlers entirely (B's wrapper runs, sees the event
  not-yet-cancelled, and dispatches — possibly wrongly). The pragmatic
  mitigation, to be confirmed during implementation: any event
  implementing `ICancellableEvent` with mixed-domain listeners
  automatically upgrades to `GLOBAL_TOTAL`-equivalent routing (all
  listeners inline, real bus's own cancellation check works exactly as
  before) unless every listener on that event type is provably in the
  same domain. Flagged here so M12 implementation work does not silently
  ship a cancellation-ordering bug.
- **Enumerating and annotating all NeoForge events.** `docs/events.md`'s
  ~30-event table is a representative target set for M12.4 (a follow-up
  task under this milestone, not this design doc's scope) — it is not
  exhaustive of every NeoForge event class. Unannotated events fall
  through to `LEGACY_SERIAL` exactly as they do today; this is by design
  (CLAUDE.md rule 5 — never refuse, always have a safe default), not a
  gap to close before M12 can ship.
- **Async events with return values.** An `ASYNC`-domain listener that
  needs to hand a computed value back into game state (rather than
  firing-and-forgetting) needs a continuation/future-based API this
  design does not define. Deferred to M14+.

## 12. The `09-events/` patch group

`multiforge-patches/09-events/` currently contains only `.gitkeep` — no
patches land in it yet. M12 implementation is expected to add exactly
**one** patch file:

```
multiforge-patches/09-events/net/neoforged/neoforge/common/NeoForge.java.patch
```

changing the `EVENT_BUS` field initializer
(`upstream/neoforge-1.21.1/src/main/java/net/neoforged/neoforge/common/NeoForge.java:17`)
to route through `EventBusBridge.wrap(...)` per §2.1. Every other type
referenced in this design —

- `net.multiforge.neoforge.event.EventBusBridge` (fork bridge, lives
  under `upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/event/`,
  same convention as the other `net.multiforge.neoforge.*` facades
  referenced in this repo's M9 conventions, e.g. `MultiForgeChunkMap`)
- `DispatchingEventBus`, `RoutingListenerWrapper`, `AnnotationScanner`,
  `DomainDispatcher` (all `multiforge-runtime`, package
  `net.multiforge.runtime.event` or similar, mirroring
  `net.multiforge.runtime.ownership`/`net.multiforge.runtime.region`
  conventions)

— lives outside the patch tree, in either the fork-bridge glue class or
plain `multiforge-runtime` source, and therefore rebases across NeoForge
point releases with zero patch-file churn. This is the same
group-scoping discipline `docs/blueprint.md`'s M7-M11 entries already
follow (`multiforge-patches/05-entity-migration/`,
`multiforge-patches/06-networking/`, `multiforge-patches/07-persistence/`,
`multiforge-patches/08-globals/` each hold a handful of thin patches with
the heavy logic living in `multiforge-runtime`).

## 13. `docs/events.md` status update

This design doc supersedes the "not started" status previously recorded
at `docs/events.md:134`. See the accompanying edit to that file (status
line now reads "in progress; see docs/design/m12-event-routing.md",
dated 2026-09-06) — the per-event target-domain table in that document
(`docs/events.md:59-94`) remains the source of truth for M12.4's
annotation work and is referenced throughout this design (§5.1) rather
than duplicated.
