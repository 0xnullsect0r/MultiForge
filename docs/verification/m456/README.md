# m456 (Phase X) Verification Artifacts

This tree holds the wall-clock verification artifacts for Phase X's
bench-regression tasks — the joint verification gate for the M4 (entity
migration), M5 (global subsystems), and M6 (tooling) tracks that land
`v1.2.0`. It mirrors the layout `docs/verification/m9/` used for M9's
Phase 7 runbook: one subdirectory per task, each the drop point for that
task's world captures, probe snapshots, and pass/fail evidence.

Of the eight Phase X tasks, X.5 (scanner rule tests, `./gradlew
:multiforge-scanner:test`) and X.6 (doc-build check) are already
CI-checkable and need no wall-clock artifact tree. X.4 (client debug HUD
sanity) and X.7 (the `/67` round-6 review) aren't bench-shaped either.
This tree covers the four that are: X.1, X.2, X.3, and X.8.

| Task | Report | Gradle task | Script |
|------|--------|-------------|--------|
| X.1 — cross-region entity teleport | [`x1-cross-region-teleport.md`](x1-cross-region-teleport.md) | `:multiforge-bench:x1CrossRegionTeleport` | `multiforge-bench/verification/m456/x1-cross-region-teleport.sh` |
| X.2 — cross-region raid stress | [`x2-raid-stress.md`](x2-raid-stress.md) | `:multiforge-bench:x2RaidStress` | `multiforge-bench/verification/m456/x2-raid-stress.sh` |
| X.3 — dragon fight regression | [`x3-dragon-fight-regression.md`](x3-dragon-fight-regression.md) | `:multiforge-bench:x3DragonFight` | `multiforge-bench/verification/m456/x3-dragon-fight-regression.sh` |
| X.8 — strict-mode 60-min swarm | [`x8-strict-mode-swarm.md`](x8-strict-mode-swarm.md) | `:multiforge-bench:x8StrictSwarm` | `multiforge-bench/verification/m456/x8-strict-mode-swarm.sh` |

Nothing under this tree is produced by CI — every real-run artifact here
comes from a manual wall-clock run on a workstation with the vendored
NeoForge workspace, same as `docs/verification/m9/`. What CI (and this
authoring pass) *can* verify is that the harness itself is wired
correctly — see "Dry-run smoke" below.

## How to run

### Prerequisites (real runs only)

1. JDK 21 on `PATH`/`JAVA_HOME` (the repo's dev JDK, per
   `scratchpad/capture.sh`'s precedent, lives at
   `/home/aric/.local/jdk/jdk-21.0.12.1+1` on the reference workstation).
2. `./gradlew :setup` — vendors NeoForge 1.21.1 into `upstream/`.
3. RCON port `25575` free on `127.0.0.1`; `python3` on `PATH` (used by
   `multiforge-bench/verification/m456/lib/rcon.py`).
4. Free disk/wall-clock: X.1/X.2/X.3 are ~15 min each (two 6000-tick
   sprint captures + a SEMANTIC diff); X.8 is ~65 min (a real-time
   60-minute swarm run, not sprinted — see
   `multiforge-bench/README.md` "Why sprint for vanilla/atm10 but
   real-time for swarm").

### Dry-run smoke (no server, no prerequisites beyond bash + shellcheck)

Every script accepts `--dry-run`, which prints the exact shell, Gradle,
and RCON commands it would issue and exits `0` without starting a
server. This is what "Smoke each script's --dry-run mode" in the Phase X
plan asked for, and what CI can safely run on every PR:

```
bash multiforge-bench/verification/m456/x1-cross-region-teleport.sh --dry-run
bash multiforge-bench/verification/m456/x2-raid-stress.sh --dry-run
bash multiforge-bench/verification/m456/x3-dragon-fight-regression.sh --dry-run
bash multiforge-bench/verification/m456/x8-strict-mode-swarm.sh --dry-run
```

Each of the four report pages below has an "Expected commands" section
with the actual output of that invocation, captured during this
authoring pass. All four exit `0` and end with `PASS` as the last stdout
line — the same PASS/FAIL contract a real run uses (see below), so a
dry-run is also a smoke test of the pass/fail wiring itself, not just the
command construction.

The `:multiforge-bench:x{1,2,3,8}...` Gradle tasks default to `--dry-run`
for the same reason CI can invoke them safely; running
`./gradlew :multiforge-bench:tasks --group verification` lists all four
alongside the existing `determinism`/`vanilla`/`swarm`/`atm10` tasks.

### Real runs

Pass `-PrealRun` to the Gradle task, or drop `--dry-run` when invoking the
script directly:

```
./gradlew :multiforge-bench:x1CrossRegionTeleport -PrealRun
./gradlew :multiforge-bench:x2RaidStress -PrealRun
./gradlew :multiforge-bench:x3DragonFight -PrealRun
./gradlew :multiforge-bench:x8StrictSwarm -PrealRun    # ~65 min — matches the task timeout
```

or equivalently:

```
bash multiforge-bench/verification/m456/x1-cross-region-teleport.sh
bash multiforge-bench/verification/m456/x2-raid-stress.sh
bash multiforge-bench/verification/m456/x3-dragon-fight-regression.sh
bash multiforge-bench/verification/m456/x8-strict-mode-swarm.sh
```

Each writes its evidence under `docs/verification/m456/evidence/x{N}/`
(world captures, boot logs, `/multiforge probes` snapshots, and — for
X.1/X.3 — the `WorldDiff.DiffMode.SEMANTIC` report from
`:multiforge-bench:determinism`) and ends with a machine-parseable
`PASS` or `FAIL` as the **last line of stdout**, exit code `0`/`1` to
match. Once a real run produces evidence, copy the relevant excerpts into
that task's report page's "Actual" section (the placeholder is there
today) — that's the human step this tree exists to make repeatable.

### Environment variable overrides

Every script reads its seed/tick-count/player-count from an environment
variable with a sane M9-precedent default, so a re-run with a different
seed doesn't need a script edit:

| Script | Variable | Default | Meaning |
|--------|----------|---------|---------|
| x1 | `MF_X1_SEED` | `1234567890` | level-seed for both captures |
| x1 | `MF_X1_TICKS` | `6000` | sprint tick count per capture (5 game-min) |
| x2 | `MF_X2_SEED` | `1234567890` | level-seed |
| x2 | `MF_X2_TICKS` | `6000` | sprint tick count |
| x3 | `MF_X3_SEED` | `1985` | level-seed — matches the plan's `-Ptest.seed=1985` |
| x3 | `MF_X3_TICKS` | `6000` | sprint tick count per capture |
| x8 | `MF_X8_PLAYERS` | `100` | `-Pplayers` passed to `:multiforge-bench:swarm` |
| x8 | `MF_X8_TICKS` | `72000` | `-Pticks` — `ticks/20` = 3600s = 60 real-time minutes |
| x8 | `MF_X8_PROBE_DELAY_SEC` | `3480` | how long to wait before the mid-run `/multiforge probes` RCON snapshot (58 min, leaving margin before teardown) |

### RCON commands used

All four scripts drive a running server the same way M9's
`scratchpad/capture.sh` did: `tick freeze`, scripted `/summon`/`/tp`/
`/execute`/`/kill` calls over RCON, `tick sprint <n>`, `save-all flush`,
`stop`. The committed
`multiforge-bench/verification/m456/lib/rcon.py` is a checked-in copy of
that same minimal RCON client (no new dependency — stdlib `socket` +
`struct` only) so these scripts don't depend on an operator's scratch
directory to run. `/multiforge probes <prefix>` (the real
`MultiForgeCommandDispatcher` subcommand — see
`multiforge-runtime/src/main/java/net/multiforge/runtime/commands/
MultiForgeCommandDispatcher.java`) is how each script pulls
`ProbeRegistry` counters mid-run.

### PASS/FAIL contract

The last line of stdout is exactly `PASS` or `FAIL`; the process exit
code is `0` for PASS and `1` for FAIL. A caller (CI, a wrapper script, a
human) should read only that last line to decide pass/fail — everything
above it is progress logging plus (in `--dry-run`) the transcript of
commands that would have run. `--dry-run` runs always print `PASS` and
exit `0`, since no real assertion is evaluated (see "Dry-run smoke"
above) — that line asserts the harness itself is wired correctly, not
that the underlying bench passed.

### Known gaps

- **X.2's "orphan raider" assertion.** The Phase X plan's literal recipe
  (`/multiforge probe entity-registry.orphans`) names a probe command
  that doesn't exist yet — `EntityRegistry` has no orphan-tracking
  surface analogous to its migration `retiredRefs` cache (see
  `docs/design/entity-migration.md` §"No lost UUID refs" for the pattern
  a raid-orphan equivalent would follow). `x2-raid-stress.sh` uses
  `RaidsSystem`'s real `raider-spawn-failure` counter as the closest
  available proxy — see the ADAPTATION NOTE at the top of that script and
  the "Known gaps" section of `x2-raid-stress.md`. Adding a dedicated
  orphan-raider probe is follow-up work, not part of this authoring pass.
- **X.3's "automated bot".** There is no real dragon-combat client in
  this repo (`multiforge-bench` stays MC-free by design — see
  `multiforge-bench/README.md`). `x3-dragon-fight-regression.sh`
  force-kills the dragon over RCON instead of simulating a fight — see
  the ADAPTATION NOTE in that script for why this still exercises the
  code path the regression cares about.
- **X.8's ProbeRegistry snapshot timing.** Because the whole 60-minute
  swarm run is one blocking `:multiforge-bench:swarm` Gradle invocation,
  the script backgrounds it and polls/sleeps to grab the mid-run RCON
  snapshot — see the script's own header comment. A future pass could
  add a proper mid-run hook to `SwarmBench`/`HeadlessServerRunner`
  instead.
