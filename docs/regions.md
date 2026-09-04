# MultiForge Region Logic (M2)

## Executive summary

The world is partitioned into **regions**. Every chunk belongs to
exactly one region; every region is owned by exactly one worker
thread. Cross-region access is illegal from a region worker and must
be routed through a per-region mailbox — this is how MultiForge
achieves parallel ticking without breaking mod code that assumes the
old single-thread model.

## Vocabulary

| Term      | Meaning                                                                                  |
|-----------|------------------------------------------------------------------------------------------|
| Chunk     | The 16×16 blocks Vanilla already tracks.                                                 |
| Section   | A coarse tile of {@code 2^regionSize} chunks per side (default `regionSize=4` → 16×16).  |
| Region    | The transitive-closure of adjacent occupied sections; the tick-scheduling unit.          |
| Global region | A synthetic region for weather, time, world border, dragon fight, wither, raids, scoreboards, command dispatch. |

## Section → region → worker

1. Chunks map to sections deterministically:
   `sectionX = chunkX >> regionSize`.
2. Occupied sections form regions via 8-neighbour adjacency:
   `ThreadedRegionizer.addChunk(pos)` either extends the neighbouring
   region or creates a new one, and merges adjacent regions if the new
   section bridges them.
3. Removing a section can split a region if it was the only bridge —
   the regionizer runs a flood-fill and peels off any orphan
   components into fresh regions.
4. The `TickRegionScheduler` picks the region with the earliest
   next-fire deadline and ticks it on a worker (EDF).

Each region carries an atomic state — `TRANSIENT`, `READY`, `TICKING`,
`DEAD`. A worker claims a region via `Region.tryMarkTicking()`
(CAS `READY→TICKING`), runs its tick body, then `markNotTicking()`
(CAS `TICKING→READY`). Dead regions are dropped from the schedule.

## Adaptive sizing

`AdaptiveSizingDriver` runs off the tick loop on a low-priority
thread. For each region it examines:

- `p95Mspt` — 95th-percentile milliseconds per tick over the last ~5 s.
- `meanMspt` — rolling mean over the same window.
- `sectionCount` — how big the region is.
- `nearbyPlayerCount` — how many players see chunks it owns.

It emits a `Recommendation` per region:

| Signal                                                                    | Recommendation |
|---------------------------------------------------------------------------|----------------|
| `p95Mspt ≥ region.msptSplitThreshold` AND `sectionCount ≥ 4`              | `SPLIT`        |
| `region.mode = player-only` AND no nearby players                         | `PARK`         |
| `meanMspt ≤ region.msptMergeThreshold` AND no nearby players              | `MERGE`        |
| otherwise                                                                 | `HOLD`         |

The M2.5 patch wires these recommendations into the regionizer's
concrete split/merge primitives; until then the driver is
observation-only.

## The global region

A single dedicated region ticks state that has no natural spatial
owner: weather, time, world border, gamerules, ender-dragon fight,
wither, raid manager, scoreboards, and command dispatch. It runs on
its own worker at 20 TPS, alongside the region workers.

## Cross-region routing

Every task that targets a chunk-scoped location goes through
`RegionizedTaskQueue.queueChunkTask(world, chunkX, chunkZ, task)`. At
enqueue time we look up the owner region; at drain time (start and
end of every tick) the owning region's worker runs its inbox.

If the chunk is not currently loaded, the task lands in the orphan
queue and is re-routed on the next `reroute()` pass. This is how
network packets, entity teleports, and cross-region events safely
land on their target.

## Configuration

The operator-visible knobs live in `multiforge-server.toml`:

```toml
[mtserver]
cores = 8               # cores × threadsPerCore = tick worker count
threadsPerCore = 2
mode = "hybrid"         # off | hybrid | strict

[region]
size = 4                # 2^size chunks per section side (4 → 16×16, Folia default)
mode = "player-only"    # player-only | full-world
msptSplitThreshold = 35.0
msptMergeThreshold = 5.0

[violations]
policy = "warn"         # warn | reroute-only | fail
warnPerMin = 5
```

In-game (op only):

```
/multiforge config cores 8
/multiforge config threads 2
/multiforge region size 4
/multiforge region mode player-only
/multiforge region pin -128 -128 128 128
/multiforge region list
```

Changes rewrite `multiforge-server.toml` atomically and publish a new
snapshot to `MultiForgeConfigStore` subscribers, so pool sizing and
threshold updates take effect immediately.

## What M2 gives up

- `AdaptiveSizingDriver` observes but does not yet act — the concrete
  split/merge primitives it recommends will be wired in M2.5 once the
  chunk-system patch (M3) provides the per-region chunk-load ticket
  bookkeeping the primitives need.
- `MinecraftServer.runServer` is not yet replaced — that patch lives
  under `multiforge-patches/02-region-tick/` and applies against the
  vendored NeoForge tree (produced by `./gradlew :setup -Pmc=true`).
  The M2 slice ships the runtime, the config, and the parallel
  `SchedulerHost` so the tick body is ready to bind when the vendored
  workspace exists.
- Per-region autosave and the WAL journal are M6 work; M2 relies on
  Vanilla's global save-all.

## Test coverage

- `ThreadedRegionizerTest` — merge on adjacency, split on bridge
  removal, section coalescing.
- `RegionizedTaskQueueTest` — FIFO ordering, orphan reroute,
  exception isolation.
- `AdaptiveSizingDriverTest` — SPLIT / MERGE / PARK / HOLD decision
  matrix.
- `ConfigCodecTest` — TOML round-trip, partial-key overlay,
  invalid-enum fail-fast.
- `MultiThreadedSchedulerHostTest` — end-to-end region/global/async
  scheduling and repeating tasks against the parallel host.
- `RegionThroughputBenchTest` — smoke check that both 1-worker and
  many-worker runs produce positive throughput.

For a full scaling curve, run
`./gradlew :multiforge-runtime:test --tests RegionThroughputBench` and
look at the console output.
