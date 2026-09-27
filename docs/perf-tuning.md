# Performance tuning

MultiForge's gain comes from ticking separate regions of a level in parallel
(see [`regions.md`](regions.md) and
[`design/barrier-tick-model.md`](design/barrier-tick-model.md)). Everything
else in a server tick still runs on the server thread as in Vanilla: network,
chunk loading and generation, lighting, saving, weather, raids, commands and
entity section management. Keep that in mind before tuning anything:

- **Parallelism only helps when the load is spread over separate regions.**
  One contiguous loaded area is always one region and is ticked by one
  worker, however many workers there are. Ten players standing together are
  one region; ten players in ten distant bases can be ten.
- A slow server-thread phase (a big chunk generation burst, a heavy autosave,
  a mod doing expensive work in a server-thread event) is not helped by more
  workers.
- A tick lasts as long as the server-thread work plus the slowest region of
  each level, because of the barrier.

## Knobs

`config/multiforge-server.toml`:

```toml
[mtserver]
cores = 8               # default: available processors
threadsPerCore = 1      # worker pool = cores × threadsPerCore
mode = "hybrid"         # off | hybrid | strict

[region]
size = 4                # sections are 2^size chunks per side (4 → 16)

[violations]
policy = "warn"         # warn | reroute-only | fail
warnPerMin = 5

[tick]
inlineSingleRegion = true         # see "Tick placement"
serialLaneHotWaitMs = 5            # see "Tick placement"
```

There are no other performance keys: no tick budget, no split/merge
thresholds, no autosave settings. Saving is Vanilla's
([`persistence.md`](persistence.md)). A file written by an older version has
no `[tick]` section; the defaults apply and the file is not rewritten.

`/multiforge config cores|threads <n>` and `/multiforge region size <chunks>`
apply live. `-Dmultiforge.workers=N` on the JVM command line replaces
`cores × threadsPerCore` for that run, which is convenient for comparing
worker counts.

### Worker pool size

- The pool never needs more workers than there are regions that are busy at
  the same time. Check `/multiforge region list` before raising it.
- Leave headroom for the server thread, the network threads and GC. On a
  machine that runs nothing else, a pool of (physical cores − 1 or 2) is a
  reasonable start; `threadsPerCore = 2` only helps if the CPU has SMT and
  the regions are busy.
- Oversubscribing (more workers than free cores) makes every region slower,
  because workers are descheduled mid-tick and the barrier waits for the
  slowest one.

### Region size

`size` sets the section edge (`/multiforge region size` takes it in chunks,
a power of 2 from 1 to 256). Two loaded areas become separate regions only
when at least one whole unloaded section lies between them.

- **Smaller sections** (for example 8 or 4 chunks): nearby bases become
  separate regions sooner, so there is more to run in parallel. The price is
  more merge and split activity as players move and chunks load and unload,
  and regions that sit closer together.
- **Larger sections** (32 chunks and up): fewer, larger regions and less
  topology churn, but bases need to be further apart before they tick in
  parallel.

After changing it, compare `/multiforge tickstats` over similar play and
check the probes listed below.

### Pins

`/multiforge region pin` forces a rectangle's loaded chunks into one region.
It is a correctness and consistency tool (keep a build that spans an
unloaded gap on one thread), not a speed-up: a pin can only merge regions,
never split them. Remove pins you no longer need.

### Mode

- `hybrid` (default) is the production mode.
- `strict` throws on an ownership violation and when a region overruns the
  barrier deadline, which stops the server. Use it for testing only.
- `off` runs Vanilla's single-threaded tick. It is the baseline to compare
  against and a kill switch; it takes effect at the next start.

### Tick placement

A region ticks on a worker thread only when that can help:

- **A level with one region** ticks it on the server thread
  (`inlineSingleRegion = true`). With nothing to run beside it, a worker adds
  only cost: every serial-lane event and every chunk load it needs would be a
  round trip to the server thread. One player alone, or players close
  together, is this case.
- **A region whose serial-lane hand-offs cost it more than
  `serialLaneHotWaitMs` per tick** (default 5 ms) for 20 ticks in a row moves
  to the server thread, where those events run directly, and ticks there
  after the parallel regions finish. It goes back to a worker after 200 ticks
  under a quarter of that. The cost is measured on a worker (the time waiting
  for the lane minus the listeners' own run time) and, on the server thread,
  estimated from the region's posts and the measured cost of one hand-off, so
  the rule follows what the hand-offs cost on this machine rather than a
  count. `0` disables this. (v1.8's `serialLaneInlineThreshold` counted posts;
  an old file's `0` still disables it, any other value is ignored.)

A region's tick time, in `region list` and on the heatmap, counts the
listeners the serial lane ran for it and leaves out only the hand-off, so it
does not change when the region moves between a worker and the server thread.

`/multiforge region list` says, per world, where its regions ticked, and per
region which thread last ticked it and how many serial-lane events it posted.
`-Dmultiforge.inlineSingleRegion=false` and
`-Dmultiforge.serialLaneHotWaitMs=0` override the file for one run.

### Heap and garbage collector

Every region stops when the JVM pauses for garbage collection, so a long pause
shows up as all regions overrunning in the same second (`region-tick.overrun`
for every region, with nearly the same duration). Terrain generated by players
far apart fills the heap quickly: in the 100-player bench, full G1 collections
of a 16 GB heap paused the server for about 630 ms, and strict mode, which
treats any tick over 500 ms as a failure, stopped the server.

For many players, or players far apart:

```
-Xmx16G -XX:+UseZGC -XX:+ZGenerational
```

in `user_jvm_args.txt`. ZGC keeps pauses to a few milliseconds; with it the
same 100 players ran an hour in strict mode with no overrun. Size the heap for
the loaded terrain: about 16 GB for 100 players spread across 4000 blocks.

## Measuring

### Whole-server tick time

`/multiforge tickstats` reports every tick since the last
`/multiforge tickstats reset`: tick count, mean, p50/p95/p99, the true
maximum, and the TPS actually achieved over the last ten minutes. Reset it,
play or run the load for a while, then read it. This is the number to
compare between configurations.

### Regions

- `/multiforge region list`: regions per world, their section counts and
  states. If the busy part of the world is one region, more workers will
  not help.
- `/multiforge chunks <world>`: loaded chunks per region.
- The optional client debug mod (`multiforge-client`) shows per-region
  p50/p95 tick time in its HUD (`region-<id> mspt=p50/p95 owned=N
  sections=M`); see [`client-mod-guide.md`](client-mod-guide.md).

### Probes

`/multiforge probes [prefix]` dumps diagnostic counters; `/multiforge probes
top [prefix] [n]` lists the largest. The useful ones:

| Probe | Meaning |
|---|---|
| `region-tick.overrun` | A region's tick, minus designed waits, took longer than the watchdog threshold (default 500 ms, `-Dmultiforge.watchdog.warn-ms`). Usually a blocking call, a stuck mod handler, or one very busy region. A warning names the region. |
| `region-tick.dispatch.overrun` | A level's regions did not all finish within the barrier deadline (default 500 ms, `-Dmultiforge.regiontick.dispatch-ms`). |
| `region-tick.wait-ms.<kind>` | Total milliseconds region workers spent in designed waits: `main-thread-chunk-load` and `serial-lane`. These are excluded from the overrun check. `region-tick.wait-ns.<kind>` is the same in nanoseconds and `region-tick.waits.<kind>` counts the waits. (Before v1.8 each wait was rounded down to whole milliseconds on its own, so thousands of sub-millisecond serial-lane waits showed as 0.) |
| `region.main-thread-chunk-load` | Count of region workers that touched a chunk that was not loaded and had to wait for the server thread to load it. A steadily growing value means some region code (often a mod, or entities at the edge of loaded terrain) reaches into unloaded chunks every tick. |
| `serial-lane.handoff` | Events a region worker handed to the server thread to run on the serial lane (see [`events.md`](events.md)). `serial-lane.inline` counts the ones a region ticking on the server thread ran directly. |
| `event.dispatch.serial.{event,mod,world}.*` | Serial-lane listener runs by event class, by the listener's mod and by the posting region's dimension. `/multiforge probes top event.dispatch.serial` shows the heaviest. |
| `region-tick.inline.{single,hot}` | Region ticks run on the server thread (see *Tick placement*). |
| `ownership.pending-registration` | Writes to a chunk that loaded during the current region tick; it gets its owner when the tick ends, so the write went to the server thread. Expected, not a violation. |
| `<site>:cross-region` | Mutations rerouted to another region's mailbox, per mutation site (e.g. `Level.setBlock:cross-region`). Some are normal; a high rate means work keeps crossing region borders. |
| `reroute.<site>.mismatch` | A rerouted call returned Vanilla's predicted result to its caller, but the owner saw a different result when it applied it. |
| `event.dispatch.*` | How event listeners were dispatched: `inline`, `serial`, `global`, `async`. |

`/multiforge warn list` shows the recent rate-limited warnings behind these
counters. See [`debugging-violations.md`](debugging-violations.md).

## Bench harness

Under `multiforge-bench/` (details in its `README.md` and `build.gradle.kts`):

```
./gradlew :multiforge-bench:vanilla   [-Pticks=<n>] [-Pworkers=<n>] [-Pserver=stock]
./gradlew :multiforge-bench:swarm     [-Pplayers=<n>] [-Pticks=<n>] [-Pworkers=<n>] [-Pspread=<blocks>]
./gradlew :multiforge-bench:atm10     -PmodpackDir=<dir> [-Pticks=<n>] [-Pworkers=<n>]
./gradlew :multiforge-bench:determinism [-Pworkers=1[,4,...]]
```

- `vanilla`: no mods, `/tick sprint`; the baseline.
- `swarm`: protocol bots that walk, place and break blocks in real time;
  `-Pspread` controls how far apart they are, and therefore how many
  regions exist.
- `atm10`: a modpack profile; needs an unpacked pack (`-PmodpackDir`) or
  `-PmodpackUrl` with `-PmodpackSha256`.
- `determinism`: the Vanilla-parity gate. It ticks a fixed-seed world on
  stock NeoForge and on MultiForge and compares the result.

`-Pserver=stock` runs the same profile on plain NeoForge. Timing comes from
`/multiforge tickstats`; each run writes a JSON result (`-PoutputFile`) and a
boot log under `multiforge-bench/build/bench-logs/`. Compare JSON results
between runs rather than console TPS.
