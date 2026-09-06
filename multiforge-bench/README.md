# multiforge-bench

Benchmarking and determinism-regression harness for MultiForge. Two
things live here:

- **`determinism`** — byte-identical / semantic world-save diff over two
  pre-captured world directories (Phase 7.2 / 7.3). See
  `net.multiforge.bench.determinism.DeterminismHarness`.
- **`vanilla` / `swarm` / `atm10`** — the Phase 7.4 TPS/MSPT bench
  profiles. See `net.multiforge.bench.harness.*` and
  `docs/design/m9-phase7-runbook.md` §5 for the full scope and pass
  criteria.

This module stays MC-free: it never imports `net.minecraft.*`. It drives
a real server (launched via the vendored `upstream/neoforge-1.21.1`
workspace's own `gradlew :neoforge:runServer`) purely over TCP — RCON for
commands, plus tailing its log file — never by linking against Minecraft
classes.

## The bench harness (`net.multiforge.bench.harness`)

| Class                    | Role                                                                 |
|--------------------------|-----------------------------------------------------------------------|
| `RconClient`             | Minimal Source-RCON client (auth + single-packet command/response).  |
| `MetricsCollector`       | Parses `/tick query` replies + `Sprint completed`/lag log lines into MSPT samples; computes avg/p50/p95/p99/max. |
| `HeadlessServerRunner`   | Boots one dev server (prepares `run/server/`, launches `gradlew :neoforge:runServer -Dmultiforge.workers=N`, waits for RCON, hosts the RSS sampler + log-tail threads, flushes+stops on teardown). |
| `BenchResult`            | The result record + hand-rolled JSON writer (see below for why not Gson). |
| `VanillaBench`           | `:vanilla` task entry point.                                          |
| `SwarmBench`             | `:swarm` task entry point.                                            |
| `Atm10Bench`             | `:atm10` task entry point.                                            |

### Why sprint for vanilla/atm10 but real-time for swarm

`VanillaBench` and `Atm10Bench` freeze the tick loop and then run the
requested tick count via `/tick sprint <n>`, which disables the normal
50ms-per-tick pacing and runs ticks back-to-back. On an idle world this
finishes a 12000-tick (10-game-minute) run in well under a second of
wall-clock time. The MSPT numbers it reports are still genuine — the
server times each tick's computation the same way whether or not it then
sleeps to pace to 20 TPS — so sprinting is a fast, repeatable way to
gather the actual per-tick compute-cost distribution (including for a
mod-loaded ATM10 world) without waiting out 10 real minutes per run.

`SwarmBench` instead runs the server at its natural pace for a real-time
duration (`ticks / 20` seconds), because a swarm bench's entire point is
whether *sustained concurrent load holds 20 TPS over real time* —
something an unthrottled sprint cannot measure.

`tps_sustained_last_10min` in every result JSON is therefore a
projection derived from the measured average MSPT (`min(20,
1000/avg_mspt)`), not a literal 10-real-minute TPS trace, except for the
swarm profile where the churn loop genuinely does run for real time.

### The swarm profile is a simplified fallback

The Phase 7.4a task brief allowed for either a real embedded Minecraft
protocol client (handshake + login + play-packet loop) or a simpler RCON
`/summon`-based fallback if the former was too large a scope addition for
one pass — and to document the choice clearly rather than ship a stub.

**This lands the fallback.** `SwarmBench` never opens a socket against
the server's actual game port; there are no real player connections, no
handshake, no login, and nothing exercises the packet-flush /
`ServerGamePacketListenerImpl` paths a real client would. What it does do,
entirely over the same RCON channel the other two profiles use:

1. `/summon minecraft:armor_stand` once per `-Pplayers=<n>`, scattered
   across a footprint sized to the player count, tagged `mfbench_swarm`.
2. `/forceload add` over that footprint, so the swarm's area stays
   chunk-loaded the way a real player's render distance would keep it.
3. A real-time churn loop: each round runs
   `execute as @e[tag=mfbench_swarm] at @s run tp @s ~dx ~ ~dz` — a
   genuine independent random-walk step *per entity*, in one RCON
   round-trip regardless of whether there are 20 or 500 bots — plus an
   occasional `particle` burst per bot standing in for "dig" activity.
   `/tick query` is polled once per round.
4. `/kill @e[tag=mfbench_swarm]` + `/forceload remove all`, then the
   normal `save-all flush` / `stop` shutdown.

This is a real, working exercise of the chunk-load, chunk-tracking, and
per-entity-tick paths under N-way concurrent load — just not the
networking paths. A real protocol client (rewritten by hand from the
protocol wiki, or via a license-compatible library vetted through the
CLAUDE.md dependency-review process) would be a reasonable follow-up if
Phase 7.6's strict-mode watchdog run ever needs to specifically stress
`ServerGamePacketListenerImpl` region-hop behavior — see
`docs/design/m9-phase7-runbook.md` §4.

### Why hand-rolled JSON instead of Gson

`BenchResult.toJson()` is a ~40-line hand-written writer, not Gson.
`multiforge-bench/build.gradle.kts` had no JSON library on its classpath
before Phase 7.4a, and CLAUDE.md requires a license-compat + binary-size
review before adding any new dependency. For a flat record of a dozen
scalar fields plus a small extras map, writing the ~40 lines was cheaper
than that review and keeps the "no new external deps" constraint from
the Phase 7.4a task brief intact.

## Running the bench tasks

```
./gradlew :multiforge-bench:vanilla                       # workers=1, no mods
./gradlew :multiforge-bench:swarm -Pplayers=20             # armor-stand swarm fallback
./gradlew :multiforge-bench:atm10 -PmodpackDir=/path/to/atm10-server
```

All three accept `-Pticks=<n>` (default 12000 = 10 game-minutes). Every
run needs a MultiForge license key at `~/.multiforge/license.key` — same
requirement as the vendored NeoForge workspace's own `runServer` task —
and `./gradlew :setup` to have vendored `upstream/neoforge-1.21.1/` at
least once.

Results land at `docs/verification/m9/7.4/<profile>/patched.json`. See
`docs/design/m9-phase7-runbook.md` §5 for pass criteria (sustained TPS
>= 19.5, MSPT p99 < 45ms, zero OOM/crashes).
