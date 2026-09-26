# Global systems, networking and commands

This page covers the parts of the server that have no single region owner.
In the barrier tick model ([`design/barrier-tick-model.md`](design/barrier-tick-model.md))
the answer for almost all of them is the same: they run on the server thread,
in Vanilla's own code, and never at the same time as region work.

## Global systems

Weather, time of day, the world border, game rules, raids, the ender dragon
fight, scoreboards, boss bars, sleeping, custom spawners (phantoms, patrols,
cats, wandering traders) and chunk loading are ticked by Vanilla's
`ServerLevel.tick` and `MinecraftServer.tickServer` on the server thread,
unchanged. There is no separate subsystem registry and no global-system
thread.

The **global region** is a synthetic region driven once per server tick,
before the overworld's level tick, while no other region runs. It executes
work queued on the global domain: `ServerDomains.global()` tasks (the
`GlobalRegionScheduler` of the Folia-style API) and event listeners assigned
to the GLOBAL dispatch domain. See [`scheduler-api.md`](scheduler-api.md) and
[`events.md`](events.md).

Shared Vanilla state that region workers do touch (scoreboard, POI manager,
entity storage, random, neighbour updates) is protected by leaf locks or
per-thread instances; the table is in the barrier design doc.

## Network packets

MultiForge does not change packet handling. Vanilla hands every game packet
to the server thread (`PacketUtils.ensureRunningOnSameThread`), and NeoForge's
`IPayloadContext.enqueueWork` also runs on the server thread. The server
thread handles them in its main loop, outside the region barrier, so a packet
handler never runs concurrently with a region tick and can touch any chunk.

Block-change broadcasts to clients are sent after the barrier, as Vanilla
sends them right after its chunk loop.

## Commands

Commands typed by a player or on the console run on the server thread, as in
Vanilla. Command and function execution started on a region worker (for
example by a command block, which ticks as a block entity in its region) is
deferred to the server thread and runs after the barrier, because a command
like `/fill` or `/tp @e` can reach any chunk.

## `/multiforge` operator commands

All op only. Full reference with output examples:
[`multiforge-command.md`](multiforge-command.md).

```
/multiforge help
/multiforge config show | reload
/multiforge config cores <n> | threads <n>
/multiforge config mode <hybrid|strict|off>
/multiforge config policy <warn|reroute-only|fail>
/multiforge config warnPerMin <n>
/multiforge region list
/multiforge region size <chunks>            # power of 2, 1..256
/multiforge region pin <id> <world> <fromCX> <fromCZ> <toCX> <toCZ>
/multiforge region unpin <id>
/multiforge tickstats [reset]
/multiforge probes [prefix]
/multiforge chunks <world>
/multiforge warn list | clear
/multiforge certify <modId> | all
```

`MultiForgeCommandDispatcher` parses these from a plain `String[]`, so the
parsing is unit-testable; `MultiForgeCommandBinder` registers the tree on the
server's Brigadier dispatcher at server start. Configuration changes apply
live and are written to `config/multiforge-server.toml`; see
[`regions.md`](regions.md#configuration).

### Region pins

`RegionPinManager` holds the pins, persisted in
`config/multiforge-region-pins.toml`. A pin keeps the loaded chunks of a
rectangle in one region; it cannot keep them apart from loaded chunks next
to it. Details and file format: [`regions.md`](regions.md#region-pins).

## Violation warnings

`ViolationLogger` rate-limits warnings with a token bucket per key:
`warnPerMin` messages per minute (default 5) for each site, and per mod and
site when a caller passes a mod id. `policy = "reroute-only"` sets the budget
to zero; the probes still count. `/multiforge warn list` shows the recent
warnings that were logged, and `/multiforge warn clear` empties that history
without resetting the budgets.

## Tests

- `RegionPinManagerTest`: rectangle containment, corner normalisation,
  `pinContaining`, duplicate ids, save/load round trip.
- `MultiForgeCommandDispatcherTest`: config updates, region size validation,
  pin / list / unpin round trip, unknown subcommands.
