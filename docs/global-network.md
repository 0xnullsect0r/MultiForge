# MultiForge Global Systems, Networking, Commands (M5)

## Global systems

Vanilla systems with no natural spatial owner — weather, time-of-day,
world border, gamerules, ender-dragon fight state, wither battles,
raid manager, scoreboards, command dispatch — live on the dedicated
**global region** thread. Every subsystem registers a
`GlobalTicker` with `GlobalSystems`; the scheduler's global-region
tick body calls `GlobalSystems.tickAll()` once per epoch.

```java
GlobalSystems gs = ...;
gs.register("weather", tick -> weatherManager.advance(tick));
gs.register("time", tick -> for (world in worlds) world.advanceTime());
```

Tickers fire in registration order. A ticker that throws never stops
peers — the exception is delivered to the current thread's
uncaught-exception handler and the loop continues.

## Network packet routing

`NetworkPacketRouter` is the equivalent of Vanilla's
`PacketUtils.ensureRunningOnSameThread(this)` hop:

```java
router.routeToPlayer(playerRef, () -> handlePlayerMove(packet));
router.routeToGlobal(() -> commandDispatcher.dispatch(cmd));
router.routeToChunk(world, cx, cz, () -> handleBlockPacket(pos));
```

- `routeToPlayer` — enqueues on the region worker owning the player's
  current chunk. If the player crosses a border between decode and
  dispatch, the packet still lands on the new owner. This is what
  makes Folia's guarantee "player-scoped work never runs on the wrong
  region" hold under MultiForge.
- `routeToGlobal` — used for chat, commands, gamerule changes, world
  border resize.
- `routeToChunk` — used for block-scoped mod packets that don't have
  a natural entity owner.

`ModPacketContext.of(router, senderRef).enqueueWork(...)` is the
runtime backing for NeoForge's `IPayloadContext#enqueueWork`. The M5
patch replaces NeoForge's default body with this routing call — so
mods that already use `enqueueWork` get correct per-region dispatch
without recompilation.

## Operator commands

```
/multiforge config cores <n>
/multiforge config threads <n>
/multiforge region size <chunks>            # power of 2, 1..256
/multiforge region mode player-only|full-world
/multiforge region pin <id> <world> <fromCX> <fromCZ> <toCX> <toCZ>
/multiforge region unpin <id>
/multiforge region list
```

`MultiForgeCommandDispatcher` parses these off a plain `String[]` so
the parsing is unit-testable. The M5 patch wires it to NeoForge's
Brigadier tree at server startup; every command is `op` gated.

### Region pins

`RegionPinManager` maintains an operator-created set of pinned
rectangles that override the adaptive sizer — chunks inside a pin
never merge with adjacent regions, never split. Use to keep two
nearby bases from stealing each other's tick budget.

Pins are persisted in `multiforge-regions.toml` next to the server
config:

```toml
[[pins]]
id = "base-north"
world = "minecraft:overworld"
from = [ -128, -128 ]
to   = [ 128, 128 ]

[[pins]]
id = "base-south"
world = "minecraft:overworld"
from = [ 512, -128 ]
to   = [ 768, 128 ]
```

The file is rewritten atomically on every `pin` / `unpin` command; an
operator can also edit it directly while the server is stopped — the
next boot picks it up. During a live pin add, the adaptive sizer
consults `RegionPinManager.pinContaining(world, chunkPos)` before
merge/split.

## Per-mod violation warn budget

The M5 change to `ViolationLogger.warn(modId, site, detail)` gives
each `modId` its own token bucket. A chatty mod that trips one
violation site 1000/sec no longer drowns out warnings from other
mods — only its own bucket runs dry. Passing `modId = null`
preserves the M0 site-only bucketing for internal sites that aren't
mod-attributable.

## Test coverage

- `GlobalSystemsTest` — tickers fire per tick, in registration order,
  one thrower doesn't halt peers.
- `NetworkPacketRouterTest` — gameplay packet lands on player's
  current region, next packet after a border cross lands on the new
  region, global packet lands on global region.
- `RegionPinManagerTest` — inclusive rectangle contains, corner
  normalization, `pinContaining` finds match, duplicate-id rejection,
  save/load round-trip through disk.
- `MultiForgeCommandDispatcherTest` — config cores updates the
  store, region size rejects non-power-of-2, pin + list + unpin round
  trip, unknown subcommand fails.
