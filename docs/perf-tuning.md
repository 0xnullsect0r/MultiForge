# Performance Tuning Guide

MultiForge's throughput comes from splitting the world into regions
and ticking them on separate worker threads. Every knob in this guide
is either "how many workers" or "how the region topology reacts to
load" — there's no single global "make it faster" switch, because the
right answer depends on whether your bottleneck is CPU count, region
fragmentation, or IO.

## `multiforge.toml` tunables

The full operator-visible knob set (`config/multiforge-server.toml` on
a running server; see `docs/operator-handbook.md` for the install
layout):

```toml
# CPU
cores = 8                    # worker cores to dedicate
threads-per-core = 2         # SMT threads per core (worker pool = cores * threads-per-core)

[region]
size = 16                    # chunks per side of the base section
mode = "player-only"         # or "full-world"
mspt-split-threshold = 30.0  # ms — regions hotter than this get split
mspt-merge-threshold = 5.0   # ms — regions colder than this can be merged

[persistence]
autosave-per-tick-chunks = 8         # max chunks written per tick per region
autosave-per-tick-nanos  = 2000000   # 2 ms budget per tick per region
journal-fsync = true                 # fsync on every append (only turn off for benchmarks)

[violations]
warn-per-mod-per-second = 5          # rate limit for reroute warnings
```

There is deliberately **no separate "tick budget" key** — the closest
thing is `mspt-split-threshold`, which is the signal
`AdaptiveSizingDriver` (`docs/regions.md`) acts on to decide a region
is too hot and should be split. Treat it as the de facto per-region
tick budget: a region that consistently sits above this threshold is,
by definition, over budget.

`cores` / `threads-per-core` together set the tick worker pool size
(`MultiForgeConfig.tickWorkerCount()` = `cores * threadsPerCore`,
floored at 1). For flexing the worker count without editing the TOML —
useful when running the bench harness at several worker counts back to
back — pass `-Dmultiforge.workers=N` on the JVM command line; it
short-circuits the computed value entirely for that run.

## Region count vs memory tradeoff

More, smaller regions (lower `region.size`, or a hot area that's been
split by the adaptive sizer):

- Better isolation — one region's lag spike doesn't drag neighboring
  bases down.
- Finer-grained split targets, so the sizer has more opportunities to
  shed load from a genuinely hot area.
- More per-region bookkeeping overhead: each region carries its own
  ticket map (`PerRegionTicketMap`), holder table, autosave queue, and
  (if not parked) a dedicated worker context. At high region counts
  this bookkeeping — not raw tick work — becomes the memory cost
  center.

Fewer, larger regions (higher `region.size`, `mode = "full-world"`):

- Less bookkeeping overhead per chunk.
- A single hot chunk inside a large region drags the whole region's
  MSPT up, and the region can't shed just that one hot chunk — the
  sizer can only split along section boundaries, so a large region
  with one bad actor tile-entity stays slow until something changes
  the underlying load.

`region.mode = "player-only"` (the Folia default, and MultiForge's
default) avoids the memory cost of ticking regions with no players
nearby at all — regions with no `nearbyPlayerCount` get `PARK`ed by
the sizer rather than staying live. `full-world` trades that memory
saving for uniform, always-on simulation (needed for some redstone- or
farm-heavy modpacks that expect distant machines to keep running).

## Autosave frequency tradeoff

`autosave-per-tick-chunks` and `autosave-per-tick-nanos` both cap how
much IO a region's autosave queue can do in a single tick — whichever
limit is hit first wins for that tick. Raising either value:

- Shortens the data-loss window on a crash (more chunks get written
  more often).
- Steals more of the region's per-tick time budget from actual
  simulation, which can itself push the region toward the split
  threshold.

`journal-fsync = false` skips the fsync-on-append the WAL journal
normally does (`docs/persistence.md`) — this measurably speeds up
write-heavy benchmarks, but it means a crash can lose journal entries
the OS hadn't flushed to disk yet. Only turn it off for benchmarking;
leave it `true` for any server holding real player data.

## TPS interpretation: per-region vs server-average

A single server-average TPS number can hide a saturated region behind
several idle ones — the average is only useful as a first glance, not
as the diagnostic signal. Two places to look instead:

- **`/multiforge region list`** — one line per region with MSPT
  p50/p95. This is the ground truth: if one region sits above
  `mspt-split-threshold` for more than a few seconds and the sizer
  hasn't split it, either the region has a single very hot chunk that
  can't be split further, or something is blocking the region worker
  thread outright (check `docs/debugging-violations.md` first).
- **Client HUD F3 overlay** — `region-<id> mspt=X.X/Y.Y owned=N
  sections=M`, live per-region, from inside the game.

Rule of thumb: server-average TPS answers "is anything wrong
anywhere?"; per-region MSPT p95 answers "where, and how bad?" Always
check the latter before tuning worker count or region size in response
to a TPS complaint — raising worker count doesn't help if the problem
is one region with a single overloaded chunk.

## When to raise or lower worker count

- Raise `cores`/`threads-per-core` (or `-Dmultiforge.workers=N` for a
  quick test) when `/multiforge region list` shows multiple regions
  simultaneously near their split threshold and CPU headroom exists —
  more workers means more regions can tick in parallel instead of
  queueing for a worker.
- Adding workers stops helping once the number of *live* (non-parked)
  regions is smaller than the worker count — you can't parallelize
  past the number of things there are to parallelize. Check region
  count (`/multiforge region list`) before assuming more workers will
  help; a modpack with one enormous unsplit region gets zero benefit
  from extra workers.
- Lower worker count on hardware with fewer physical cores than
  `cores * threads-per-core` implies — oversubscription causes context
  switching overhead that shows up as elevated MSPT across every
  region, not just the hot one.

## Bench harness usage

All under `multiforge-bench/`, run via Gradle from the repo root:

```
./gradlew :multiforge-bench:vanilla   [-Pticks=<n>]
./gradlew :multiforge-bench:swarm     [-Pplayers=<n>] [-Pticks=<n>]
./gradlew :multiforge-bench:atm10     -PmodpackDir=/path/to/atm10-server [-Pticks=<n>] [-Pworkers=<n>]
./gradlew :multiforge-bench:determinism [-PdiffMode=BYTE_IDENTICAL|SEMANTIC] [-Pworkers=<n>]
```

- **`vanilla`** — baseline profile: `workers=1`, no mods. Establishes
  the floor everything else is compared against. Default 12000 ticks
  (10 game-minutes).
- **`swarm`** — headless bot swarm at a configurable player count;
  the profile CLAUDE.md/regression runs use to simulate real
  concurrent player load without needing real clients.
- **`atm10`** — All The Mods 10 modpack baseline. Requires a
  pre-fetched modpack directory (`-PmodpackDir=...`) — the task never
  downloads the ~500 MB pack itself.
- **`determinism`** — the parity regression CLAUDE.md rule 3 requires:
  byte-identical world save vs upstream NeoForge on a fixed seed
  (`BYTE_IDENTICAL`, single worker) or a semantic comparison across
  worker counts (`SEMANTIC`). This is the gate, not an optional
  benchmark — break it at your peril.

Each profile writes its result to
`build/bench-verification/<profile>/patched.json` and a boot log under
`build/bench-logs/<profile>-boot.log`; compare successive runs' JSON
output rather than eyeballing console TPS numbers when chasing a
regression.
