# MultiForge Event Domain Reference

## Executive summary

MultiForge defines a contract for pinning NeoForge event handlers to a
specific execution domain — `@DispatchDomain` — and, as of M12.4,
dispatch-time enforcement of that contract is wired: `NeoForge.EVENT_BUS`
is transparently wrapped by `DispatchingEventBus`
(`docs/design/m12-event-routing.md`), which routes each listener according
to its method-level `@DispatchDomain`, its class-level `@DispatchDomain`,
or — for the ~30 events in the table below — a built-in default from
`EventTypeDomainMap`, in that order. This page documents the contract as
it exists in `multiforge-api`, the domain each of those ~30 common events
dispatches to by default, and how `LEGACY_SERIAL` rerouting behaves for
handlers that haven't opted in to anything stronger.

## The @DispatchDomain contract

`net.multiforge.api.event.DispatchDomain` marks a NeoForge event handler
(or an entire `@EventBusSubscriber` class) as safe to dispatch on a given
domain:

```java
@DispatchDomain(DispatchDomainKind.REGION)
@SubscribeEvent
public static void onBlockBreak(BlockEvent.BreakEvent event) {
    // Intended to run on the region worker that owns event.getPos().
}
```

`DispatchDomainKind` has four values — a narrower set than the seven-value
runtime `Domain` enum documented in `docs/concurrency-contract.md`,
because event dispatch only ever needs to pick among domains that make
sense as an event *target*:

| Value           | Meaning                                                                 |
|-----------------|---------------------------------------------------------------------------|
| `REGION`        | Runs on the region worker that currently owns the event's target.        |
| `GLOBAL`        | Runs on the dedicated global-region thread.                              |
| `ASYNC`         | Runs on the shared async pool; must not touch game state.                |
| `LEGACY_SERIAL` | **Default.** Runs on the per-mod serialised legacy executor.             |

Unannotated handlers default to `LEGACY_SERIAL` — safe, but slower, and
subject to the reroute-and-warn behavior described below. Both
`@DispatchDomain` and the companion `@Ordering`/`OrderingContract`
annotations (`PER_REGION` default / `GLOBAL_TOTAL` / `BEST_EFFORT`) may
target either a single listener method or a whole
`@EventBusSubscriber` class; see `docs/api.md` §Event dispatch annotations
for the mod-author-facing walkthrough with a full ordering-contract
explanation — this page focuses on the per-event mapping, not the
annotation mechanics.

## Per-event domain reference

The table below lists real NeoForge event classes present in the vendored
`upstream/neoforge-1.21.1` tree. The rows also present in
`EventTypeDomainMap`'s ~30-entry default map (`multiforge-runtime/src/main/
java/net/multiforge/runtime/event/EventTypeDomainMap.java`) are the ones an
unannotated listener actually dispatches to today; the remaining rows are
still a target for a mod author who wants to annotate a handler
explicitly, or for a future expansion of `EventTypeDomainMap` — an
unannotated listener for one of those events still falls through to
`LEGACY_SERIAL` (see below) until either happens.

| Event                                        | Target domain | Why |
|-----------------------------------------------|----------------|-----|
| `tick.ServerTickEvent.Pre` / `.Post`          | GLOBAL         | No single chunk owns "the server tick"; global-thread bookkeeping. |
| `tick.LevelTickEvent.Pre` / `.Post`           | REGION         | Fired per-level, per-tick; the region(s) ticking that level's loaded chunks own it. |
| `tick.PlayerTickEvent.Pre` / `.Post`          | REGION         | Keyed to the player entity's current chunk. |
| `tick.EntityTickEvent.Pre` / `.Post`          | REGION         | Keyed to the entity's current chunk. |
| `level.ChunkEvent.Load`                        | REGION         | Fires for a specific chunk; owning region handles it (also drives `RegionizedTaskQueue.rerouteAtChunk`, see `docs/scheduler-api.md`). |
| `level.ChunkEvent.Unload`                      | REGION         | Same as `Load`. |
| `level.ChunkDataEvent.Load` / `.Save`          | REGION         | Chunk-scoped NBT read/write. |
| `level.ChunkWatchEvent.Watch` / `.UnWatch`      | REGION         | Tied to a chunk position becoming visible/invisible to a player. |
| `level.ChunkTicketLevelUpdatedEvent`           | REGION         | Ticket/level changes are per-region bookkeeping (see `docs/chunks.md`). |
| `level.LevelEvent.Load`                        | GLOBAL         | Fires once at level lifecycle boundaries, before any region owns the level's chunks. |
| `level.LevelEvent.Unload`                      | GLOBAL         | Same as `Load`. |
| `level.BlockEvent.BreakEvent`                  | REGION         | Keyed to a block position. |
| `level.BlockEvent.EntityPlaceEvent`            | REGION         | Keyed to a block position. |
| `level.BlockEvent.PortalSpawnEvent`            | REGION         | Keyed to a block position. |
| `level.BlockEvent.FarmlandTrampleEvent`        | REGION         | Keyed to a block position. |
| `level.BlockEvent.NeighborNotifyEvent`         | REGION         | Keyed to a block position. |
| `level.BlockDropsEvent`                        | REGION         | Keyed to a block position. |
| `level.BlockGrowFeatureEvent`                  | REGION         | Keyed to a block position. |
| `level.ExplosionEvent.Start` / `.Detonate`      | REGION         | Explosions are chunk-local; cross-region blast falls back to task-queue routing per affected chunk. |
| `level.ExplosionKnockbackEvent`                | REGION         | Applies to entities in the blast's region. |
| `level.PistonEvent.Pre` / `.Post`              | REGION         | Keyed to a block position. |
| `level.NoteBlockEvent.Play` / `.Change`         | REGION         | Keyed to a block position. |
| `level.AlterGroundEvent`                       | REGION         | Structure/feature terrain edit, chunk-scoped. |
| `level.ModifyCustomSpawnersEvent`               | GLOBAL         | Registered once per level at load, not per-tick or per-chunk. |
| `level.SleepFinishedTimeEvent`                 | GLOBAL         | Time-of-day is global state. |
| `entity.EntityJoinLevelEvent`                  | REGION         | Keyed to the entity's spawn chunk. |
| `entity.EntityLeaveLevelEvent`                 | REGION         | Keyed to the entity's current chunk. |
| `entity.EntityTeleportEvent` (and subtypes)    | REGION         | Fired on both source and destination chunk owners as the entity migrates (see `docs/migration.md`). |
| `entity.EntityTravelToDimensionEvent`          | REGION         | Source-side region owns the pre-migration hook. |
| `entity.EntityMountEvent`                      | REGION         | Keyed to the entities' shared chunk. |
| `entity.EntityStruckByLightningEvent`          | REGION         | Keyed to a block/chunk position. |
| `entity.living.LivingHurtEvent`                | REGION         | Keyed to the entity's current chunk. |
| `entity.living.LivingDeathEvent`                | REGION         | Keyed to the entity's current chunk. |
| `entity.living.LivingSpawnEvent.CheckSpawn`     | REGION         | Keyed to the prospective spawn position's chunk. |
| `entity.player.PlayerEvent.PlayerLoggedInEvent`  | GLOBAL         | Login/logout affects server-wide player-list state, not one region. |
| `entity.player.PlayerEvent.PlayerLoggedOutEvent` | GLOBAL         | Same as above. |
| `entity.player.PlayerInteractEvent.LeftClickBlock` | REGION       | Keyed to the clicked block's position. |
| `entity.player.PlayerInteractEvent.RightClickBlock` | REGION      | Keyed to the clicked block's position. |
| `entity.player.PlayerInteractEvent.RightClickItem` | REGION       | Keyed to the interacting player's current chunk. |
| `CommandEvent`                                 | GLOBAL         | Command dispatch is global per `docs/regions.md` §The global region. |
| `RegisterCommandsEvent`                        | GLOBAL         | Registration-time, not per-tick; no spatial owner. |
| `ServerChatEvent`                              | GLOBAL         | Chat has no spatial owner today (proximity chat, if ever added, would move this to REGION). |
| `server.ServerAboutToStartEvent` / `Starting` / `Started` / `Stopping` / `Stopped` | GLOBAL | Server lifecycle, no spatial owner. |

`ASYNC` is deliberately under-populated in this table. NeoForge's built-in
vanilla event set is almost entirely tied to game state (a block, an
entity, a level, the server) — none of it is safe to dispatch purely
async by default. `ASYNC` dispatch is meant for **mod-defined** events
that are provably pure/immutable (e.g. a config-reload notification), not
for retargeting a vanilla event that happens to feel infrequent.

## LEGACY_SERIAL rerouting for unaudited handlers

Any handler without a `@DispatchDomain` annotation, and whose event type
has no entry in `EventTypeDomainMap`, is treated as `LEGACY_SERIAL`:

- The handler runs on a per-mod serialised executor (see
  `docs/legacy-compat.md` for the executor's design and current
  implementation status).
- If the handler touches state owned by a region other than the one it's
  currently allowed to mutate, `OwnershipEnforcer` catches it exactly the
  same way it catches any other off-thread mutation attempt — REROUTE
  mode by default (warn + hand off), STRICT only for regression testing.
  See `docs/concurrency-contract.md` for the full enforcer contract.
- This is intentionally the same mechanism, not a parallel one: an event
  handler is just another piece of mod code from `OwnershipEnforcer`'s
  point of view. `@DispatchDomain` only decides which thread the handler
  starts on; it doesn't grant any mutation rights the enforcer wouldn't
  otherwise check.

## M12 enforcement

"Enforced" means: listener registration reads `@DispatchDomain` off the
method (or its enclosing `@EventBusSubscriber` class) at
subscribe-time and dispatches through the matching domain —
`RegionizedTaskQueue.queueChunkTask` for `REGION`, the global-region inbox
for `GLOBAL`, the async pool for `ASYNC` — instead of always running
inline on whatever thread NeoForge's own event bus happens to fire on
today. `@Ordering`/`OrderingContract` governs the ordering guarantee
within that dispatch.

**Status:** wired as of M12.4. `@DispatchDomain` + `@Ordering` on listener
methods are honored at post-time via the `DispatchingEventBus` wrapper
(docs/design/m12-event-routing.md). Unannotated listeners for the ~30
highest-value NeoForge events get sensible defaults from
`EventTypeDomainMap`; everything else falls through to `LEGACY_SERIAL`
with a rate-limited warn.
