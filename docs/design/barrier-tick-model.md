# The barrier tick model

This is how MultiForge runs Minecraft's world tick on several threads, and why
it is safe. It replaces the free-running design sketched in
`docs/blueprint.md` §M8 and the entity-migration / global-subsystem machinery
of M4/M5 (see [What this replaced](#what-this-replaced)).

## One server tick

The server thread still runs Vanilla's main loop — network, logins, chunk
loading, commands typed by players, autosave. For each level it ticks,
`RegionizedTickCoordinator.dispatchLevelTick` (called from the patched
`MinecraftServer.tickChildren`) does:

1. **Global region** (first level only): drive the synthetic global region
   once. It runs work queued on the global domain — `ServerDomains.global()`
   tasks and GLOBAL-domain event listeners.
2. **Vanilla level tick**, on the server thread: `ServerLevel.tick`, unchanged
   except that it skips exactly the work regions own. Weather, time, world
   border, raids, the dragon fight, sleeping, chunk loading and unloading,
   custom spawners (phantoms, patrols, cats, wandering traders), block events
   queued outside a region, and entity section management all run here, as
   in Vanilla. `ServerChunkCache.tickChunks` still picks this tick's ticking
   chunks, but queues each one's random ticks and natural spawning for the
   region owning it.
3. **Regions**, in parallel: every live region of the level ticks once on the
   worker pool (`MultiThreadedSchedulerHost.driveRegions` →
   `TickRegionScheduler.driveTick`). A region's tick body runs, in order:
   its mailbox, its chunks' random ticks and natural spawning, scheduled
   block and fluid ticks (with Vanilla's collect-then-run semantics), the
   block events those ticks queued, its entities, and its block entities.
4. **Barrier**: once every region finished, the server thread runs any chunk
   work a region could not take (its region merged or split away mid-tick)
   and broadcasts the tick's block changes to clients, as Vanilla does right
   after its chunk loop. Then `dispatchLevelTick` returns.

Region work and server-thread work therefore **never overlap**. The only
concurrency is between regions of the same level, and regions are, by
construction, far apart: the regionizer merges any two regions whose
sections (16×16 chunks) touch, so two distinct regions always have at least
one whole unloaded section between them. Anything a block or a walking entity
can reach in one tick — a neighbour, a collision box, a path — is in its own
region or unloaded.

While waiting at the barrier the server thread services exactly one kind of
request: a region worker that touched a not-yet-loaded chunk. Vanilla hands
such a load to the server thread and waits; `MainThreadHandoff` counts the
waiting workers and the barrier's pump runs the chunk source's tasks only
while one is waiting.

A region alone in its level's batch, or one whose serial-lane hand-offs cost
it more than `serialLaneHotWaitMs` per tick for a second, is ticked on the
server thread itself (`[tick]` in `multiforge-server.toml`): a worker would
gain no parallelism and pay a hand-off per lane event and chunk load. It goes
back to a worker after 200 ticks under a quarter of that. On a worker the
hand-off cost is measured (the lane wait minus the listeners' run time); on
the server thread it is estimated from the region's posts and the measured
cost of one hand-off. A region's tick time counts the listeners the lane ran
for it and leaves out only the hand-off, so it is the same wherever the
region ticks. There
the server thread loads a missing chunk itself, in Vanilla's `managedBlock`;
`MainThreadHandoff.enterInline` brackets that as the same designed wait, so
the region's tick time and the heatmap still show only its own work.

The worker pool size is `cores × threadsPerCore` from
`config/multiforge-server.toml` (or `-Dmultiforge.workers=N`).

## Modes

`mode` in `multiforge-server.toml`, or `-Dmultiforge.mode=`:

| mode | behaviour |
|---|---|
| `off` | The regionized runtime is not installed. Every guard falls through and the server runs Vanilla's single-threaded tick. The kill switch, and the control for regression runs. |
| `hybrid` (default) | As above: regions in parallel; an ownership violation is rerouted to its owner with a rate-limited warning. |
| `strict` | As hybrid, but an ownership violation throws and a region overrunning the barrier deadline throws. For regression runs. |

## Natural spawning

Vanilla computes one `NaturalSpawner.SpawnState` per level per tick: entity
counts per mob category, and a cap of `category limit × spawnable chunks /
289`. The spawn state is mutable scratch (the chunk being evaluated, its
spawn potential), so regions cannot share it. Each region gets its own,
built on the server thread from the region's own entities and its own
spawnable chunks. The per-region caps therefore add up to the level-wide
cap. What changes is only where the headroom is: a crowded region no longer
suppresses spawning in a distant empty one. This is also how Folia spawns.
The level-wide state is still computed, for `getLastSpawnState()`.

## Ownership

A region owns the chunks of its sections. **An entity, block entity or
scheduled tick belongs to the region owning its chunk** — there is no separate
entity ownership table, and nothing needs to move when an entity walks: it is
ticked by whichever region owns the chunk it is in at the start of that
region's tick.

The patched mutation sites (`Level.setBlock`, `LevelAccessor.scheduleTick`,
`ServerLevel.addFreshEntity`/`addEntity`, `Entity.remove`,
`BlockEntity.setChanged`, `LevelChunk.addAndRegisterBlockEntity`) check
`OwnershipGuard.canMutateAt`: a region worker may only mutate a chunk its own
region owns. Anything else is rerouted — to the owning region's mailbox
(`RegionizedTaskQueue`), where it runs at that region's next drain, or to the
server thread when no region owns the chunk.

Operations whose effects are not confined to one region are **deferred to the
server thread** when a region worker starts them, and run after the barrier
while no region runs:

- an entity teleporting into another region's chunk (`Entity.teleportTo`,
  `changeDimension` within the same level, `ServerPlayer.teleportTo`,
  `teleportRelative`);
- a player changing dimension (removing a player from a level updates every
  tracked entity's viewer set);
- command and function execution (`Commands.performPrefixedCommand`/
  `performCommand`, `ServerFunctionManager.execute`) — a command block's
  `/fill` or `/tp @e` can reach anywhere.

A non-player entity changing dimension proceeds inline: only this level's
regions are running, so the destination level is idle.

## Shared Vanilla state made safe

Everything region workers touch that is not per-chunk:

| State | Treatment |
|---|---|
| `ServerChunkCache.getChunk`/`getChunkNow` | A chunk that already reached the requested status is read straight from the visible holder (a volatile copy-on-write map and atomic futures). Only a real load goes to the server thread, via `MainThreadHandoff`. Vanilla hopped to the server thread for every read and returned `null` from `getChunkNow`. |
| `Level.getBlockEntity` | Vanilla answers `null` off the level's thread; region workers and the global region are served. |
| `Level.neighborUpdater` | One `CollectingNeighborUpdater` per thread (`PerThreadNeighborUpdater`); the server thread keeps Vanilla's instance. |
| `Level.random` | One `LegacyRandomSource` per thread (`PerThreadRandomSource`); Vanilla's throws when two threads use it. |
| `Level.getProfiler` | Region workers get the inactive profiler. |
| `LevelTicks` | Level-wide maps under a leaf lock; each chunk's container is touched only by its region. Regions drain with `mfTickRegion` — Vanilla's collect-then-run, so `willTickThisTick` holds. |
| Block events | Queued per region by a region worker and run by that region right after its scheduled ticks; leftovers are absorbed by the next server-thread `runBlockEvents`. |
| Fresh block entities, ticker lists | Guarded; NeoForge's `onLoad` pass always runs on the server thread before regions tick. A ticker whose chunk is not regionized yet stays on the level and is routed on a later tick. |
| Entity storage (`PersistentEntitySectionManager`) | Every structure under a leaf lock. Callbacks (tracking/ticking transitions, NeoForge events, mod hooks) and getter consumers always run outside it, so no foreign code runs while it is held on a worker. `LockingEntityGetter` copies matches under the lock. |
| `EntityTickList`, `ChunkMap` tracker map, player list, navigating mobs, dragon parts | Synchronized / concurrent collections; `sendBlockUpdated` re-paths only the calling region's mobs and tracks its re-entrancy per thread. |
| `PathTypeCache` | One immutable entry per slot, replaced with a single reference write. |
| `Scoreboard` | Synchronized on the scoreboard; `getPlayersTeam` reads a concurrent mirror lock-free. |
| `PoiManager` (villager workstations, beds, bells, lightning rods, portals) | Every access holds the manager's monitor, shared with its `SectionStorage`; query streams are materialised inside it. A block change in one region updates it while a villager in another queries it. `ensureLoadedAndValid` loads chunks outside the lock. |

Every lock above is a **leaf**: nothing that could wait on another thread, and
no foreign code, runs while it is held. That is the exception to CLAUDE.md
rule 4 ("no `synchronized` block that could contend with a foreign region"):
these are microsecond critical sections around a single structure update, and
the alternative — unsynchronised writes to shared hash maps — corrupts them.

## Gates

Region phases follow the same conditions Vanilla applies to the inline work
they replace, recorded by `ServerLevel.tick` before the regions run:
scheduled ticks need `!isDebug()` and a normal tick rate; block events a
normal tick rate; entities and block entities that the level ticks entities
at all this tick (players present or recently occupied). Block entities also
honour `/tick freeze` and the block-ticking range.

## Failures

An exception in a region's tick is not swallowed. The region's remaining
phases still run (so its journal flushes), then the exception is rethrown on
the server thread once the barrier completes — where Vanilla's "Exception
ticking world" crash handling, and NeoForge's `removeErroringBlockEntities`/
`removeErroringEntities` options (applied inside Vanilla's own tick wrappers),
behave exactly as they do for an inline level tick.

## Watchdog

Vanilla's `ServerWatchdog` measures from `nextTickTime`, which advances 50 ms
per tick and catches up at most once per 15 s of scheduled time, so a server
running steady slow ticks reads its accumulated lag as one hung tick. The
patched watchdog measures from the later of `nextTickTime` and the start of
the tick in progress (`MinecraftServer.mfTickStartNanos`,
`TickHangDetector`): identical to Vanilla while the server keeps up, and a
tick or a stall between ticks longer than `max-tick-time` still stops the
server. `HangReporter` (its own daemon thread) logs the stacks of the server
thread and of every thread ticking a region, tagged with region and phase,
from 10 s into a stalled tick and every 5 s after (`-Dmultiforge.hangReport=false`
turns it off).

## Chunks

Chunk loading, tickets, load levels, generation, lighting and saving are
Vanilla's and run on the server thread, unchanged. The runtime only tracks
which region owns each loaded chunk: `RegionizedChunkLifecycle` adds a
chunk to the regionizer and to the world's `ChunkHolderManager` index on
`ChunkEvent.Load` and removes it on `ChunkEvent.Unload`. A region worker
reads loaded chunks directly and hands a real load to the server thread
(`MainThreadHandoff`, see *Shared Vanilla state* above).

## What this replaced

- **Free-running regions.** The scheduler ticked regions on their own 20 TPS
  cadence, concurrently with the server thread's main loop, and production
  installed an empty tick body; since B3.5 `ServerLevel.tick` did not run at
  all. `TickRegionScheduler` keeps `FREE_RUNNING` for MC-free runtime use;
  a NeoForge server uses `BARRIER`.
- **M4 entity migration.** Crossing into another region serialised the entity
  to NBT and re-created it in the destination, and a player's movement
  packets were held while a hop was in flight. With ownership following
  chunk position and cross-region jumps deferred to the server thread, none
  of that is needed; it broke references to the entity and rubber-banded
  players.
- **M9 chunk-system fork.** A shadow of Vanilla's chunk system (holders
  with load levels and futures, per-region ticket maps, facades for
  `ChunkMap`, `DistanceManager` and the light engine, a per-region
  chunk-save journal and an MCA reader/writer) was fed by observer hooks in
  the chunk code but never drove anything: Vanilla kept loading, lighting
  and saving every chunk, and the journal never received a write. It was
  removed; the chunk index above is what remained in use.
- **M5 global subsystems.** Ten Vanilla methods (weather, time, world border,
  raids, dragon fight, scoreboard and boss-bar callbacks, command dispatch)
  were hollowed out and re-run from a global-region worker. In the barrier
  model the server thread already is that single owner, so Vanilla's own code
  runs unchanged — which also restores compatibility with mixins that target
  those methods (`docs/compatibility.md`).

## Verification

- `upstream/neoforge-1.21.1/tests/.../net/multiforge/testfixtures/` —
  `RegionTickBehaviourTests` (scheduled ticks, entities, block entities,
  cross-region reroute) plus NeoForge's own GameTest suite, run by CI's
  `fork-build` job on the regionized runtime.
- `-Dmultiforge.mode=off` runs the same suite on Vanilla's tick as a control;
  `-Dmultiforge.mode=strict` fails on any ownership violation.
