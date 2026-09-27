# MultiForge event dispatch

How MultiForge runs NeoForge event listeners when regions tick in parallel,
and how a mod controls it. The mechanism is `DispatchingEventBus`, which
MultiForge installs as `NeoForge.EVENT_BUS` (`multiforge-patches/09-events/`);
the tick model it serves is `docs/design/barrier-tick-model.md`.

## Where a listener runs

What matters is the thread that **posts** the event.

**Posted from a region worker** (anything a region ticks: scheduled ticks,
entities, block entities, random ticks, spawning), a listener runs according
to its effective domain:

| Domain | Where it runs | Cancel / result visible to the poster? |
|---|---|---|
| `REGION` | On the posting worker, in parallel with other regions. | Yes |
| `GLOBAL` | On the **serial lane**: the server thread, one listener at a time, while the worker waits. | Yes |
| `LEGACY_SERIAL` | On the serial lane, like `GLOBAL`. | Yes |
| `ASYNC` | On the shared async pool; the worker does not wait. | **No** |

`@Ordering(GLOBAL_TOTAL)` sends a listener to the serial lane whatever its domain.

**Posted from any other thread** — the server thread (commands, player
actions, the level tick's own work), world generation, network, a mod's own
thread — every listener runs on the posting thread, exactly as in NeoForge.
(An `ASYNC`-pool task posting an event sends `GLOBAL` listeners to the global
region instead, since it cannot wait for the server thread.)

The serial lane is the server thread while it waits at the tick barrier: a
worker hands the listener over and parks; the server thread runs it under the
worker's owner token, so world writes it makes are checked and rerouted
exactly as the worker's own would be (`docs/concurrency-contract.md`). Two
listeners on the lane never overlap, which is what code with unsynchronised
static state needs. The lane does not stop the other regions, though: it runs
while their workers tick, so state that region code also touches is not
protected by it (see *Deferred events* below for work that must not overlap
any region).

A worker hands over **whole events**, not single listeners: when an event it
posts reaches at least one serial-lane listener, the entire `post` runs on the
lane — one hand-off per event, however many listeners it has — and each
listener is still routed as above from there (a `REGION` listener runs on the
lane too, an `ASYNC` one still goes to the pool). Before v1.8 each serial
listener was its own hand-off, which on a large modpack meant tens of
thousands of thread round trips per tick. `-Dmultiforge.event-dispatch.batch=off`
restores the old behaviour.

A region that ticks on the server thread itself (see
[`perf-tuning.md`](perf-tuning.md#tick-placement)) runs its serial listeners
directly, with no hand-off at all.

Probe counters: `event.dispatch.{inline,serial,async,global,serial-post}`,
`serial-lane.handoff` (a worker waited for the lane), `serial-lane.inline`
(an event ran on the lane from a region ticking on the server thread), and the
breakdown `event.dispatch.serial.event.<class>`, `.mod.<module>` and
`.world.<dimension>` — `/multiforge probes top event.dispatch.serial` lists
the heaviest.

## A listener's effective domain

1. `@DispatchDomain` on the method, else on its class — always wins.
2. Otherwise the mod's safety classification decides:
   - `strict-safe`: `REGION` (runs on the posting thread).
   - `legacy`: `LEGACY_SERIAL`.
   - `hybrid-safe` (default): the event type's default from the table below,
     else `LEGACY_SERIAL`.

This applies equally to `@SubscribeEvent` methods registered with
`register(...)` and to lambdas and method references registered with
`addListener(...)`; a listener's mod is the mod whose jar defines its class.

```java
@DispatchDomain(DispatchDomainKind.REGION)
@SubscribeEvent
public static void onBlockBreak(BlockEvent.BreakEvent event) {
    // Runs on the region worker that broke the block.
}
```

### Classifying a mod

A mod declares itself in `neoforge.mods.toml`:

```toml
[modproperties.examplemod]
multiforge_safety = "strict-safe"   # legacy | hybrid-safe | strict-safe
```

A server operator overrides that in `config/multiforge-mods.toml`
(written with comments on first start):

```toml
[mods]
examplemod = "legacy"
```

The same file sets an event type's default domain (see *Event-type defaults*
below) for listeners of `hybrid-safe` mods, by the event's class name:

```toml
[events]
"com.example.SomeEntityEvent" = "region"   # region | serial | deferred
```

It is read at server start and applies to listeners registered before it.

Use `legacy` for a mod whose listeners share unsynchronised state (a static
`HashMap` updated from entity events) and misbehave under `hybrid-safe`.
Declare `strict-safe` only for code that is thread-safe; its listeners then
run in parallel on every region worker.

## Event-type defaults

Unannotated listeners of a `hybrid-safe` mod for these events take the listed
domain (`EventTypeDomainMap`); a subclass inherits its superclass's entry.
All other events default to `LEGACY_SERIAL`. `REGION` entries are events local
to one block, chunk or entity; `GLOBAL` ones are server-wide. The per-entity
entries from `EnteringSection` to `VanillaGameEvent` were added in v1.8: they
fire from each entity's own tick, and on an ATM10 bench they were the most
frequent serial-lane events (`LivingBreatheEvent` and `MobDespawnEvent` fire
for every living entity or mob, every tick). `EntityInvulnerabilityCheckEvent`,
`LevelEvent.PotentialSpawns` and `GetEnchantmentLevelEvent` followed in v1.9,
the most frequent ones left on an ATM10 server, after reading every listener
for them in that pack. `LivingHealEvent` stays serial: two Relics listeners do
nothing unless `MinecraftServer.isSameThread()`, which is false on a region
worker. A mod whose listener for one of them is not thread-safe should be
classified `legacy`, or the event set back to `serial` under `[events]`.

| Event (`net.neoforged.neoforge.event.…`) | Default |
|---|---|
| `tick.LevelTickEvent.Pre` | `REGION` |
| `tick.LevelTickEvent.Post` | `REGION` |
| `tick.ServerTickEvent.Pre` | `GLOBAL` |
| `tick.ServerTickEvent.Post` | `GLOBAL` |
| `tick.EntityTickEvent.Pre` | `REGION` |
| `tick.EntityTickEvent.Post` | `REGION` |
| `tick.PlayerTickEvent.Pre` | `REGION` |
| `tick.PlayerTickEvent.Post` | `REGION` |
| `entity.EntityJoinLevelEvent` | `REGION` |
| `entity.EntityLeaveLevelEvent` | `REGION` |
| `entity.living.LivingDeathEvent` | `REGION` |
| `entity.living.LivingIncomingDamageEvent` | `REGION` |
| `entity.living.LivingDamageEvent.Pre` | `REGION` |
| `entity.living.LivingDamageEvent.Post` | `REGION` |
| `entity.living.LivingDropsEvent` | `REGION` |
| `entity.living.MobSpawnEvent.PositionCheck` | `REGION` |
| `entity.living.MobSpawnEvent.SpawnPlacementCheck` | `REGION` |
| `entity.living.FinalizeSpawnEvent` | `REGION` |
| `entity.EntityEvent.Size` | `REGION` |
| `entity.EntityEvent.EnteringSection` | `REGION` |
| `entity.EntityMobGriefingEvent` | `REGION` |
| `entity.living.LivingBreatheEvent` | `REGION` |
| `entity.living.MobDespawnEvent` | `REGION` |
| `entity.living.LivingChangeTargetEvent` | `REGION` |
| `entity.living.LivingEvent.LivingVisibilityEvent` | `REGION` |
| `entity.living.LivingFallEvent` | `REGION` |
| `entity.living.LivingEvent.LivingJumpEvent` | `REGION` |
| `VanillaGameEvent` | `REGION` |
| `entity.EntityInvulnerabilityCheckEvent` | `REGION` |
| `level.LevelEvent.PotentialSpawns` | `REGION` |
| `enchanting.GetEnchantmentLevelEvent` | `REGION` |
| `entity.player.PlayerEvent.PlayerLoggedInEvent` | `GLOBAL` |
| `entity.player.PlayerEvent.PlayerLoggedOutEvent` | `GLOBAL` |
| `entity.player.PlayerInteractEvent.LeftClickBlock` | `REGION` |
| `entity.player.PlayerInteractEvent.RightClickBlock` | `REGION` |
| `entity.player.PlayerInteractEvent.RightClickItem` | `REGION` |
| `ServerChatEvent` | `GLOBAL` |
| `CommandEvent` | `GLOBAL` |
| `level.ChunkEvent.Load` | `REGION` |
| `level.ChunkEvent.Unload` | `REGION` |
| `level.LevelEvent.Load` | `GLOBAL` |
| `level.LevelEvent.Unload` | `GLOBAL` |
| `level.ExplosionEvent.Start` | `REGION` |
| `level.ExplosionEvent.Detonate` | `REGION` |
| `level.BlockEvent.PortalSpawnEvent` | `REGION` |
| `level.BlockEvent.FarmlandTrampleEvent` | `REGION` |
| `level.BlockEvent.NeighborNotifyEvent` | `REGION` |
| `level.BlockEvent.BreakEvent` | `REGION` |
| `level.BlockEvent.EntityPlaceEvent` | `REGION` |
| `level.BlockEvent.EntityMultiPlaceEvent` | `REGION` |
| `level.block.CropGrowEvent.Pre` | `REGION` |
| `level.block.CropGrowEvent.Post` | `REGION` |
| `server.ServerAboutToStartEvent` | `GLOBAL` |
| `server.ServerStartedEvent` | `GLOBAL` |
| `server.ServerStoppingEvent` | `GLOBAL` |

### Audited mod events

Some mods define their own per-entity events and post them from an entity's
movement, for every entity, every tick. v1.10 runs these on the region, but
only for the listeners that were read (bytecode of the named versions); a
listener of another class — another mod, or a class a mod update added — keeps
the serial lane and is logged once (`EventTypeDomainMap: listener … was not
audited`). Every listed listener reads the posting entity (flags, Curios items,
item components, attachments) and the blocks under its own box, and writes only
the event.

| Event | Listeners that run on the region |
|---|---|
| relics `api.events.utility.FluidCollisionEvent` | relics `CutGlassBootItem$CommonEvents`; reliquified_artifacts `AquaDashersItem$CommonEvents`, `StriderShoesItem$CommonEvents` |
| relics `api.events.utility.LivingSlippingEvent` | relics `RollerSkateItem$Events`; reliquified_artifacts `SteadfastSpikesItem$SteadfastSpikesEvent` |
| relics `api.events.utility.EntityBlockSpeedFactorEvent` | relics `RollerSkateItem$Events` |
| relics `api.events.relic.GatherRelicTemplateCacheKeyEvent` | relics `RankHandler` |
| expandability `api.forge.LivingFluidCollisionEvent` | artifacts `ArtifactHooksNeoForge` |
| lionfishapi `server.event.StandOnFluidEvent` | cataclysm `ServerEventHandler` |

Audited against relics 0.12.8, reliquified_artifacts 1.0.8, artifacts 13.2.3
(expandability 12.0.0), lionfishapi 3.1 and cataclysm 3.33. `[events] "<class>"
= "serial"` puts one back on the lane for every listener; `[mods] relics =
"legacy"` does it for all of a mod's listeners.

### Listeners pinned to the lane

At server start MultiForge reads listener bytecode (the listener method and
the methods of its own class it calls, up to three calls deep). A listener
without an explicit `@DispatchDomain`, of a mod not declared `strict-safe`,
stays on the serial lane even where its event's default is `REGION` when:

- it calls `isSameThread` or `getRunningThread` — on a region worker its
  answer changes, so its effect would silently stop (Relics' Jellyfish Necklace
  and Midnight Mantle heal listeners do this); or
- it is on the region only because an audited entry lists it, and it writes a
  static field — the audited version did not, so the mod has changed.

Each pinned listener is logged once and counted in `event.affinity.pinned`;
class files that cannot be read pin nothing (`event.affinity.unscanned`).

### Deferred events

A **deferred** event posted on a region worker does not wait for anything: the
whole post runs on the server thread after that level's regions finished
ticking, when no region worker runs. Only for events nobody reads back — the
poster discards the result and the event cannot be cancelled (a cancellable
event is posted as usual and logged once). An event about an entity that was
removed before its turn is dropped (`event.dispatch.deferred.dropped`).

| Event | Why |
|---|---|
| xycraft `core.event.ItemEntityTickEvent` | Posted for every item entity every tick; the poster discards the result. Its listener, `CollectorBlockEntity.absorbItem`, reads a per-level volume map and inserts into a collector that may sit in another region, so it must not overlap any region. |

`[events] "<class>" = "deferred"` defers another event (probes
`event.dispatch.deferred`, `event.dispatch.deferred.event.<class>`).

## What `ASYNC` is for

Mod-defined events whose listeners do not touch game state (a config-reload
notification, a metrics sample). The poster continues immediately and the
listener sees the event object concurrently, so an `ASYNC` listener must not
cancel it, set a result, or rely on its fields staying unchanged.
