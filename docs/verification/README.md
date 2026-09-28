# Verification

What proves MultiForge works, how to rerun it, and the latest results. Every
number here comes from a run against a server installed from the repository's
own installer; the raw results are the JSON files under
[`results/`](results/). What has not been run yet is listed at the end.

## What gates every change

| Check | Where | What it proves |
|---|---|---|
| Unit tests, Spotless, GPL header | `ci.yml` → `build`, `client-build` | Runtime, API, scanner, installer and bench logic; jqwik properties for the regionizer's merge/split |
| Fork build + GameTests | `ci.yml` → `fork-build` | The patches apply to NeoForge 21.1.251, the fork compiles, and the 169 required GameTests pass on a server with regions ticking. That is NeoForge's own suite, which exercises Vanilla and NeoForge behaviour on the patched server, plus MultiForge's: scheduled block ticks, entity, block-entity and random ticks inside regions, rerouted cross-region and off-thread writes and their return values, event dispatch domains and the legacy serial lane, and region indexing |
| Mixin-target parity | `ci.yml` → `fork-build` (`:neoforge:checkMixinTargets`) | Every method and lambda of each Vanilla class MultiForge patches still exists with the same descriptor, so mods' mixins find their targets (see `multiforge-patches/README.md`) |
| Scanner corpus | `scanner.yml` | Every scanner finding on the fixture mods and the client mod matches `multiforge-scanner/corpus/expected.txt` |
| Vanilla parity, scenarios, strict swarm | `nightly.yml` | The live checks below, each night, on the installer built from that commit |

## Live checks

All of these boot real servers through `multiforge-bench`: the MultiForge
server from `:neoforge:installerJar`, and stock NeoForge 21.1.251 from its
official installer (`-PstockInstaller=<jar>`, or downloaded from
maven.neoforged.net). The bots are [MCProtocolLib](https://github.com/GeyserMC/MCProtocolLib)
clients: they log in, walk, place and break blocks over the real protocol.

| Check | Command |
|---|---|
| Vanilla-parity gate | `./gradlew :multiforge-bench:determinism -Pworkers=1,4` |
| Phase X scenarios (x1–x4) | `./gradlew :multiforge-bench:scenario [-Pscenario=x1]` |
| Entity-limbo and watchdog stress (MultiForge only) | `./gradlew :multiforge-bench:stress [-Pstress=limbo\|slowtick] [-PlimboSeconds=180]` |
| Water-mob crowd (the v1.11 incident's load) | `./gradlew :multiforge-bench:swarm -Pplayers=1 -Pspread=0 -PwaterMobs=15000` |
| No-mods MSPT (sprint) | `./gradlew :multiforge-bench:vanilla [-Pserver=stock] [-Pworkers=4]` |
| Modpack MSPT (sprint) | `./gradlew :multiforge-bench:atm10 -PmodpackDir=<dir>` or `-PmodpackUrl=<zip> -PmodpackSha256=<hex>` |
| Player swarm (real time) | `./gradlew :multiforge-bench:swarm -Pplayers=20 [-Pspread=512] [-Pticks=6000] [-Pserver=stock]` |
| X.8 strict-mode swarm | `./gradlew :multiforge-bench:x8StrictSwarm [-Pplayers=100] [-Pticks=72000]` |
| Interactive server | `./gradlew :multiforge-bench:console` (stdin lines go to RCON) |

Every bench task takes `-PoutputFile=<json>` and `-PbootLog=<log>`.

## Results — 2026-09-26, indexed entity tracking (v1.7.0)

Same 32-thread machine and flags as below, MultiForge with 24 workers and
`-Xmx16G` (`-Xmx24G`/`-Xmx28G` for 200/500 bots). These runs shared the machine
with the 24-hour soak. Raw results: [`results/2026-09-26-tracking/`](results/2026-09-26-tracking/).

Vanilla re-checks every tracked entity in the level against a player on each of
its movement packets (`ChunkMap.move`), which stopped both stock NeoForge and
MultiForge with 100 players spread far apart (see below). v1.7.0 re-checks only
the trackers a move can change: those that show the player their entity now, and
those whose entity is in a chunk the player's view reaches. It also updates a
tracker only for the players whose view reaches it. The result is Vanilla's; see
`multiforge-patches/README.md` and the `ChunkMap` patch.

| Run | Result | file |
|---|---|---|
| Vanilla parity, workers 1/4/8/16; scenarios x1–x4 | PASS, PASS | — |
| 100 bots, rings to 4000 blocks, 10 min | **15.9 TPS**, mean tick 42.4 ms, p99 92.8 ms; 8 regions; 0 violations; all 100 connected (stock NeoForge: crashed) | [`swarm100-spread4000-multiforge-w24.json`](results/2026-09-26-tracking/swarm100-spread4000-multiforge-w24.json) |
| The same with `-Dmultiforge.tracking.verify=50` (one update in 50 checked against Vanilla's full pass) | **0 mismatches**; 15.4 TPS | [`swarm100-spread4000-multiforge-verify50.json`](results/2026-09-26-tracking/swarm100-spread4000-multiforge-verify50.json) |
| **X.8 strict mode, 100 bots, rings to 4000, 60 minutes**, ZGC | **0 ownership violations, 0 region overruns**; 15.9 TPS, mean 44.3 ms, p99 61.2 ms; all 100 connected for the hour; 60,030 blocks placed, 60,000 broken; clean stop | [`x8-strict-swarm100-60min.json`](results/2026-09-26-tracking/x8-strict-swarm100-60min.json) |
| 200 bots, rings to 6000 | crashed: mean tick 194 ms, then the watchdog | [`swarm200-spread6000-multiforge-w24.json`](results/2026-09-26-tracking/swarm200-spread6000-multiforge-w24.json) |
| 500 bots, rings to 8000 | crashed during placement (184 of 500 placed) | [`swarm500-spread8000-multiforge-w24.json`](results/2026-09-26-tracking/swarm500-spread8000-multiforge-w24.json) |

Checking every update (`verify=true`) at 100 bots costs as much as the scan it
replaces, so that run stopped on the watchdog; it logged no mismatch before it
did ([`swarm100-spread4000-multiforge-verify.json`](results/2026-09-26-tracking/swarm100-spread4000-multiforge-verify.json)).
An earlier strict run with the default G1 collector stopped when a 630 ms full GC
paused every region at once; see [`../perf-tuning.md`](../perf-tuning.md) on
the collector.

**The next limit** is still the server thread: at 200 and 500 players it is
saturated by movement packets, each re-checking the entities near its player.
Packets are handled on the server thread in both stock and MultiForge. Raising
the limit further means handling a player's movement and tracking in the
region that owns the player, as Folia does.

## Results — 2026-09-26, 32-core workstation

Build: branch `claude/epic-archimedes-ndcba6` at the fixes below, NeoForge
21.1.251. Machine: 32 threads, 61 GB. The bots run in the bench JVM on the same
machine. Server heap `-Xmx16G` for the swarms, `-Xmx12G` for ATM10; MultiForge
with 24 workers. Raw results: [`results/2026-09-26-32core/`](results/2026-09-26-32core/).

### Parity and scenarios

- **Vanilla-parity gate: PASS** at 1, 4, 8 and 16 workers (196 chunks, the same
  digest as stock).
- **Phase X scenarios x1–x4: PASS** at 16 workers.

### Real modpacks

| Pack | Stock NeoForge | MultiForge | result |
|---|---|---|---|
| **All the Mods 10 8.2** (464 mods, server files from CurseForge) | boots; sprint 12,000 ticks: mean 0.32 ms, p99 15.0 ms | boots; sprint 12,000 ticks: mean 0.46 ms, p99 1.97 ms, max 322 ms; clean stop | [`atm10-stock.json`](results/2026-09-26-32core/atm10-stock.json), [`atm10-multiforge-w24.json`](results/2026-09-26-32core/atm10-multiforge-w24.json) |
| **Top 20 NeoForge 1.21.1 mods on Modrinth** (Lithium, ModernFix, FerriteCore, JEI, Jade, GeckoLib, Simple Voice Chat, Xaero's maps, … 21 jars with dependencies) | boots; mean 0.02 ms | boots; mean 0.13 ms, p99 0.28 ms; clean stop | [`top20-stock.json`](results/2026-09-26-32core/top20-stock.json), [`top20-multiforge-w24.json`](results/2026-09-26-32core/top20-multiforge-w24.json) |

Before the fixes below, neither pack loaded on MultiForge: Ad Astra (ATM10) and
Lithium failed to apply their mixins, and with Lithium the server then hung on
its first tick.

### Player swarms

| Run | Stock NeoForge | MultiForge (24 workers) | result |
|---|---|---|---|
| 50 bots, rings to 4000 blocks, 10 min | **crashed**: mean tick 121 ms, then the watchdog stopped it | **19.0 TPS**, mean 25.7 ms, p99 42.3 ms, max 227 ms; 14 regions; 0 violations, 0 overruns; all 50 connected; clean stop | [`swarm50-spread4000-stock.json`](results/2026-09-26-32core/swarm50-spread4000-stock.json), [`swarm50-spread4000-multiforge-w24.json`](results/2026-09-26-32core/swarm50-spread4000-multiforge-w24.json) |
| 100 bots, rings to 512 blocks (one region), 10 min | 6.3 TPS, mean 55.6 ms, p99 337 ms, max 42 s | **10.9 TPS**, mean 41.2 ms, p99 53.2 ms, max 1.3 s; 0 violations, 0 overruns | [`swarm100-spread512-stock.json`](results/2026-09-26-32core/swarm100-spread512-stock.json), [`swarm100-spread512-multiforge-w24.json`](results/2026-09-26-32core/swarm100-spread512-multiforge-w24.json) |
| 100 bots, rings to 4000 blocks | **crashed** (watchdog: one tick over 60 s) | **crashed** the same way | [`swarm100-spread4000-stock.json`](results/2026-09-26-32core/swarm100-spread4000-stock.json); MultiForge left no result file (the bench fix for that came after) |

**X.8 strict mode, 50 bots, rings to 4000 blocks, 60 minutes: 0 ownership
violations, 0 region overruns**, 15 regions, 18.6 TPS, mean tick 25.2 ms, p99
51.2 ms, all 50 bots connected for the hour, 30,070 blocks placed and 30,050
broken ([`x8-strict-swarm50-60min.json`](results/2026-09-26-32core/x8-strict-swarm50-60min.json)).
The file says `clean_stop: false`. That is the bench, not the server: its
`save-all flush` of the explored world took 40 s, past the RCON timeout, so it
never sent `stop`. The bench now stops with `stop` alone.

#### Where 100 far-apart players stopped both servers (fixed in v1.7.0)

Both servers die in the same Vanilla code, on the server thread:
`ServerGamePacketListenerImpl.handleMovePlayer` → `ChunkMap.move`. For every
movement packet, Vanilla re-checks **every tracked entity in the level**
against that player (`TrackedEntity.updatePlayer`). With 100 players each
loading their own terrain and spawning their own mobs, that is millions of
checks a second, and one tick's backlog passes the watchdog's 60 s. It is
independent of regions: the packets are handled on the server thread in both
servers. Clustered players load fewer chunks and fewer entities, which is why
100 bots within 512 blocks survive. Removing this limit means indexing tracked
entities by chunk, so that a move only checks the entities near the player
(Paper does this). That changes a Vanilla hot path under the parity rule, so it
is left for a decision rather than made here. The 500-bot runs were not
repeated: they stop at the same point.

### Found by these runs, and fixed

- **Mixin targets.** Patches renumbered or moved Vanilla's lambda methods in
  nine classes, so Lithium (`LevelChunk.lambda$updateBlockEntityTicker$6`)
  and Ad Astra (`Level.lambda$getEntities$1`) failed to load. Fixed, and
  enforced by `:neoforge:checkMixinTargets` in CI.
- **Lithium deadlock.** Lithium replaces `ServerChunkCache.getChunk`, so a
  region waiting for a chunk load never told the barrier, and the barrier
  never ran the load. The chunk executor now tracks every task a region
  worker submits.
- **Chunk ticks after a region split.** A chunk queued for its region's random
  ticks could pass to a split-off region before it ran; strict mode caught a
  kelp head growing from the wrong region. A region now leaves such chunks to
  the server thread.
- **The bench:** bots now stand still until placed and send positions like a
  vanilla client; each bot is dropped to the surface as soon as its area
  loads; a server that dies mid-run is recorded; big worlds stop cleanly; the
  fork resolves `net.multiforge` only from mavenLocal (a 502 from NeoForge's
  Maven had failed CI).

## Results — 2026-09-26, 4-vCPU container

Build: branch `claude/epic-archimedes-ndcba6`, NeoForge 21.1.251, with the
entity-phase change in `perf(region-tick): tick each region's share of
entityTickList`. Machine: a 4-vCPU, 15 GB development container. The bots run
in the same container as the server, and the server JVM uses the default heap
(a quarter of RAM, about 4 GB). Treat the throughput figures as a comparison
between stock and MultiForge on the same box, not as capacity numbers.

**How timing is measured.** On MultiForge, `/multiforge tickstats` records
every tick in the measured window (Vanilla's own per-tick time), so mean,
percentiles and the true maximum are exact. Stock NeoForge has no such
command; its figures come from `/tick query` (the last 100 ticks, sampled
every 10 s), and its "max" is the larger of the sampled p99 and the longest
"Can't keep up" stall in the log. TPS is ticks completed over wall time for
both.

### Vanilla parity

One frozen seed world (four forceloaded 7×7-chunk squares 48 chunks apart,
so MultiForge ticks them as four regions) is ticked 1200 ticks on stock
NeoForge, then on MultiForge at 1 and 4 workers, and the terrain of every full
chunk is hashed. **PASS**: all three digests are identical.

```
DeterminismRun: stock-w1 — 196 full chunks, digest 29f88749c75fa0b3e523a023eea0d44f42a7fc67220850c1b22d05e6bff432d3
DeterminismRun: multiforge-w1 — 196 full chunks, digest 29f88749c75fa0b3e523a023eea0d44f42a7fc67220850c1b22d05e6bff432d3
DeterminismRun: PASS — MultiForge at 1 worker(s) matches stock (196 chunks, digest 29f88749c75fa0b3e523a023eea0d44f42a7fc67220850c1b22d05e6bff432d3)
DeterminismRun: multiforge-w4 — 196 full chunks, digest 29f88749c75fa0b3e523a023eea0d44f42a7fc67220850c1b22d05e6bff432d3
DeterminismRun: PASS — MultiForge at 4 worker(s) matches stock (196 chunks, digest 29f88749c75fa0b3e523a023eea0d44f42a7fc67220850c1b22d05e6bff432d3)
```

The gate compares decoded block palettes and ignores a vine head's random
`age`. Stock NeoForge does not reproduce its own world generation (see
[`m9/vanilla-baseline-analysis.md`](m9/vanilla-baseline-analysis.md)), so
the world is generated once on stock and frozen, and entities are removed
because their AI draws on the level's unseeded random.

### Phase X scenarios

Each scenario runs on stock and on MultiForge (4 workers) from the same seed;
every observation must match. **All four PASS.**

- **x1**: ten mobs bounced 100 times between four spots 2000 blocks apart
  (separate regions); they keep their UUIDs, end at the last spot, and
  survive a save and restart.
- **x2**: a raid starts in a village and raiders spawn.
- **x3**: a real dragon fight. The dragon is killed by damage attributed to a
  bot, and its death sequence lights the exit portal, places the egg and
  opens a gateway.
- **x4**: the fixture mods. A cross-region writer's 20 writes are all
  rerouted and all report `true`, and the `legacy` mod is only ever called
  on the server thread.

```
=== x1
  [stock] summoned = 10
  [stock] min_count_during_hops = 10
  [stock] at_final_spot = 10
  [stock] uuids_kept = true
  [stock] after_restart = 10
  [stock] uuids_kept_after_restart = true
  [multiforge] summoned = 10
  [multiforge] min_count_during_hops = 10
  [multiforge] at_final_spot = 10
  [multiforge] uuids_kept = true
  [multiforge] after_restart = 10
  [multiforge] uuids_kept_after_restart = true
  [multiforge] info.regions = minecraft:overworld: 4 region(s)
  [multiforge] info.reroutes = 0
  [multiforge] info.reroute_mismatches = 0
  [multiforge] info.violations = 0
  [multiforge] info.overruns = 0
ScenarioRun: x1 PASS
=== x2
  [stock] raid_omen_applied = true
  [stock] raiders_spawned = true
  [multiforge] raid_omen_applied = true
  [multiforge] raiders_spawned = true
  [multiforge] info.reroutes = 0
  [multiforge] info.reroute_mismatches = 0
  [multiforge] info.violations = 0
  [multiforge] info.overruns = 0
ScenarioRun: x2 PASS
=== x3
  [stock] dragon_spawned = true
  [stock] dragon_alive = false
  [stock] egg_on_podium = true
  [stock] exit_portal_lit = true
  [stock] gateways = 1
  [multiforge] dragon_spawned = true
  [multiforge] dragon_alive = false
  [multiforge] egg_on_podium = true
  [multiforge] exit_portal_lit = true
  [multiforge] gateways = 1
  [multiforge] info.reroutes = 3
  [multiforge] info.reroute_mismatches = 0
  [multiforge] info.violations = 0
  [multiforge] info.overruns = 0
ScenarioRun: x3 PASS
=== x4
  [stock] target_is_wool = true
  [stock] writes_at_least_15 = true
  [stock] writer_false_returns = 0
  [stock] legacy_counted_ticks = true
  [stock] legacy_off_server_thread = 0
  [stock] legacy_consistent = true
  [stock] info.writes = 20
  [stock] info.legacy_ticks = 7701
  [multiforge] target_is_wool = true
  [multiforge] writes_at_least_15 = true
  [multiforge] writer_false_returns = 0
  [multiforge] legacy_counted_ticks = true
  [multiforge] legacy_off_server_thread = 0
  [multiforge] legacy_consistent = true
  [multiforge] info.regions = minecraft:overworld: 2 region(s)
  [multiforge] info.writes = 20
  [multiforge] info.legacy_ticks = 7708
  [multiforge] info.reroutes = 20
  [multiforge] info.reroute_mismatches = 0
  [multiforge] info.violations = 0
  [multiforge] info.overruns = 0
ScenarioRun: x4 PASS
```

### MSPT with no load (sprint)

`/tick sprint 6000` on a fresh world with no players.

| Server | mean MSPT | p99 MSPT | max MSPT | sprint TPS | RSS MB | result |
|---|---|---|---|---|---|---|
| stock NeoForge 21.1.251 | 0.06 | 13.40 | 13.40 | 7223 | 885 | [`vanilla-stock-w1.json`](results/2026-09-26/vanilla-stock-w1.json) |
| MultiForge, 1 worker | 0.11 | 0.51 | 15.67 | 6238 | 851 | [`vanilla-multiforge-w1.json`](results/2026-09-26/vanilla-multiforge-w1.json) |
| MultiForge, 4 workers | 0.13 | 0.69 | 21.07 | 4966 | 838 | [`vanilla-multiforge-w4.json`](results/2026-09-26/vanilla-multiforge-w4.json) |

With nothing to tick, the fixed cost of the barrier is all there is to see:
0.05–0.07 ms per tick to hand the regions to the workers and wait for them.
Against a 50 ms tick it is noise once there is real work (see the swarm).
More workers cost slightly more here because there is only one region to
run. MultiForge's worst ticks are lower than stock's p99 in both runs.

### MSPT with 17 mods (sprint)

Create, Supplementaries, Waystones, JourneyMap, Jade, Sophisticated Core,
Tombstone, Curios, GeckoLib, Citadel, Architectury, Cloth Config, Balm,
Moonlight, Placebo, FastWorkbench and AppleSkin (current NeoForge 1.21.1
releases from Modrinth, fetched with `scripts/fetch-modrinth-mods.py`). Both
servers boot with all 17 and sprint 6000 ticks; both logs show only the same
optional-integration mixin warnings.

| Server | mean MSPT | p99 MSPT | max MSPT | sprint TPS | RSS MB | result |
|---|---|---|---|---|---|---|
| stock NeoForge 21.1.251 | 0.06 | 11.80 | 11.80 | 9898 | 1536 | [`modpack17-stock.json`](results/2026-09-26/modpack17-stock.json) |
| MultiForge, 4 workers | 0.18 | 1.27 | 35.94 | 4294 | 1482 | [`modpack17-multiforge-w4.json`](results/2026-09-26/modpack17-multiforge-w4.json) |

### Player swarm (real time)

Protocol bots placed on rings up to `spread` blocks from spawn, one at a time:
each is teleported high, the harness waits until the chunks around it load,
then drops it to the surface ("on surface" counts the drops that succeeded;
the rest stay airborne and still load and tick terrain). Each bot then walks
and places or breaks a block every few seconds for the measured window.

| Run | TPS | mean MSPT | p95 | p99 | max | on surface | connected (min) | placed/broken | regions | violations / overruns | result |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 20 bots, rings to 512 — stock | 16.78 | 46.24 | 108.00 | 1562.90 | 6806.00 | 20/20 | 20/20 | 1009/1000 | — | — | [`swarm20-stock.json`](results/2026-09-26/swarm20-stock.json) |
| 20 bots, rings to 512 — MultiForge | 15.56 | 50.18 | 64.35 | 84.47 | 1623.92 | 20/20 | 20/20 | 1019/1000 | 1 | 0 / 0 | [`swarm20-multiforge-w4.json`](results/2026-09-26/swarm20-multiforge-w4.json) |
| 50 bots, rings to 512 — stock | crashed | 64.13 | 87.40 | 105.70 | 47204.00 | 48/50 | 0/50 | 389/350 | — | — | [`swarm50-stock.json`](results/2026-09-26/swarm50-stock.json) |
| 50 bots, rings to 512 — MultiForge | 9.10 | 58.17 | 72.34 | 91.62 | 1991.59 | 49/50 | 50/50 | 2550/2544 | 1 | 0 / 0 | [`swarm50-multiforge-w4.json`](results/2026-09-26/swarm50-multiforge-w4.json) |
| 20 bots, rings to 4000 — stock | 18.86 | 35.24 | 63.50 | 855.30 | 36542.00 | 8/20 | 20/20 | 420/401 | — | — | [`swarm20-spread4000-stock.json`](results/2026-09-26/swarm20-spread4000-stock.json) |
| 20 bots, rings to 4000 — MultiForge | 19.99 | 36.08 | 57.38 | 90.38 | 813.08 | 5/20 | 20/20 | 408/400 | 19 | 0 / 259 | [`swarm20-spread4000-multiforge-w4.json`](results/2026-09-26/swarm20-spread4000-multiforge-w4.json) |

- **Rings to 512 blocks.** The bots' view areas overlap into one region, so
  MultiForge runs everything on one worker and can only match stock, not
  beat it. At 20 bots it runs within 8% of stock's TPS with a better p95/p99.
  At 50 bots stock crashed during the run. Its watchdog killed it after a
  single tick took over 60 s, with the server thread in Vanilla's movement
  packet handling (`handleMovePlayer` → `ChunkMap.move`). MultiForge
  completed the run. In both, a large share of each tick's wall time is
  Vanilla's between-tick packet handling for 50 bots, which is why TPS sits
  below 1000/mean MSPT.
- **Rings to 4000 blocks.** The bots are far apart and MultiForge splits the
  world into 19 regions: 20 TPS and a mean MSPT equal to stock, with a much
  lower worst tick. The overruns (region ticks over 500 ms) arrive in bursts
  where every region is slow at the same moment. A repeat run with
  `-Xlog:gc` showed why: each burst is a full GC of 520–670 ms. The default
  heap is about 3.4 GB here, and the terrain the far-apart bots generate fills
  it. The live heap is Vanilla chunk data (about 630,000 chunk sections), and
  MultiForge's own objects take under 1 MB of it. Give a server like this a
  bigger heap. No ownership violation was recorded.
- `position_corrections` in the JSON count the server correcting a bot's
  position; the bots have no collision physics, so both servers correct them
  constantly. It costs both servers the same.

### X.8 strict-mode swarm

20 bots, 5 minutes of game time, `-Dmultiforge.mode=strict`: **0 ownership violations, 0 region overruns**, 20/20 bots connected throughout, 1005 placements and 1000 breaks, TPS 14.02, mean MSPT 55.67, clean stop: true ([`x8-strict-swarm20.json`](results/2026-09-26/x8-strict-swarm20.json)).

X.8 fails the run on any ownership violation or region overrun. The nightly
workflow runs this preset at 20 bots for 5 minutes.

### Found by these runs, and fixed

Live servers surfaced defects that unit tests and GameTests had not:

- A region lost a map section when any one of its chunks unloaded, even while
  other chunks in that section were still loaded.
- The debug channel was sent to vanilla clients, which disconnected them.
- `/multiforge` commands rejected namespaced world ids (`minecraft:overworld`).
- A deadlock when chunks loaded or unloaded during a region tick triggered a
  region merge. Chunk structure changes are now applied after the regions
  finish.
- Writes from the global region (weather, raids) were treated as
  cross-region and rerouted.
- Queued chunk tasks stayed with the old region after a split.
- Installing the runtime threw if an early caller had already used the
  fallback `ServerDomains` host.
- Exceptions from async tasks were dropped silently.
- `/multiforge certify` blocked the server thread while scanning.
- The config file overrode `-Dmultiforge.*` system properties, and config
  writes were not atomic.
- Two regions breaking blocks at once crashed the server through Vanilla's
  static `Block.capturedDrops`, which is now per thread.
- Strict mode counted designed waits (a main-thread chunk load, the serial
  event lane) as region overruns and stopped the server.
- The entity phase queried the entity section tree once per owned chunk per
  tick. It now ticks each region's share of Vanilla's entity list, which
  raised the 20-bot swarm from 11.8 to 15.6 TPS (one region) and from 10.5 to
  20 TPS (19 regions).

## Not yet run

- **A 24-hour soak.** Running: 50 bots, rings to 4000 blocks, strict mode
  (`./gradlew :multiforge-bench:x8StrictSwarm -Pplayers=50 -Pspread=4000
  -Pticks=1728000 -PextraJvmArgs=-Xmx16G`).
- **200+ far-apart players**: movement and tracking in the player's region (see the v1.7.0 results).
- **X.4 client HUD**: join with the `multiforge-client` debug mod and check
  the region overlay against `/multiforge region list`. This needs a person
  at a graphical client.
- **A modded swarm.** The protocol bots are vanilla clients and ATM10 requires
  client mods, so a modded soak would use `-PswarmMode=armor-stand` with
  `-PmodpackDir=<pack>`.

## Older material

`m9/` holds the 2026-09-05 runs of the retired M9 chunk-system fork, kept for
its finding that stock NeoForge does not reproduce its own world saves.
`m12/`, `m13/` and `m456/` point here.
