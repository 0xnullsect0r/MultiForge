# X.8 — Strict-mode 60-minute headless swarm

Script: `multiforge-bench/verification/m456/x8-strict-mode-swarm.sh`
Gradle task: `:multiforge-bench:x8StrictSwarm`

## Goal

Run the real `:multiforge-bench:swarm` bench profile
(`net.multiforge.bench.harness.SwarmBench`) at 100 simulated players for
60 real-time minutes under `-Dmultiforge.regiontick.strict=on`, and
confirm zero `RegionTickOverrunException` throws and zero
`OwnershipEnforcer ... REROUTE` hits across the whole M4+M5+M6 landing
surface (entity migration, global systems, and every mod-facing code
path the scanner rules cover) under strict enforcement.

## What changed to make this wireable

Strict mode could not previously be threaded through the
`:multiforge-bench:swarm` Gradle task at all — `SwarmBench` hardcoded
`""` for `HeadlessServerRunner.Config#extraJvmArgs` (`VanillaBench`/
`Atm10Bench` still do). This task's authoring pass added:

- `SwarmBench` now reads a `bench.extraJvmArgs` system property (default
  `""`, so existing M9-era usage of `:swarm` is unaffected) and passes it
  through — see `multiforge-bench/src/main/java/net/multiforge/bench/
  harness/SwarmBench.java`.
- `multiforge-bench/build.gradle.kts`'s `swarm` task now also accepts
  `-PextraJvmArgs=`, `-PoutputFile=`, and `-PbootLog=` overrides (all
  optional; omitting them keeps the original `swarm-$players/` defaults
  under `docs/verification/m9/7.4/`), matching the comment already in
  that file anticipating "the carrier for Phase 7.6's strict-mode
  watchdog run ... once that carrier flag is wired through."

## Procedure

1. Launch `./gradlew :multiforge-bench:swarm -Pplayers=100 -Pticks=72000
   -PextraJvmArgs="-Dmultiforge.regiontick.strict=on"
   -PoutputFile=<evidence>/patched.json -PbootLog=<evidence>/boot.log` in
   the background (it's one blocking ~60-minute invocation —
   `ticks/20 = 3600s`, matching `multiforge-bench/README.md`'s "real-time
   for swarm" rationale).
2. Poll the boot log for `RCON running on` (server up).
3. Sleep 3480s (58 min — margin before the swarm's own teardown begins),
   then snapshot `/multiforge probes` over RCON while the server is
   still reachable.
4. `wait` on the backgrounded gradle process — it owns its own
   flush+stop teardown via `HeadlessServerRunner`.
5. Grep the full boot log for `RegionTickOverrunException` /
   `OwnershipEnforcer ... REROUTE` / `region-tick.overrun` hits.
6. Save the last 200 lines of the boot log as a violation-log tail (same
   evidence shape M9's 7.6 strict-mode watchdog run used).

## Expected result

- The gradle swarm task exits `0`.
- Zero `RegionTickOverrunException` / `OwnershipEnforcer ... REROUTE` /
  `region-tick.overrun` hits anywhere in the 60-minute boot log.
- Both conditions true → `PASS`. Either false → `FAIL`.

## Actual

*(placeholder — fill in after a real run per `docs/verification/m456/
README.md` "How to run". This authoring pass only smoke-tested
`--dry-run`; see "Expected commands" below. A real run takes ~65 minutes
of wall clock — matches the `:multiforge-bench:x8StrictSwarm` Gradle
task's Exec timeout.)*

## NBT / probe evidence

Real-run evidence lands under `docs/verification/m456/evidence/x8/`:

- `patched.json` — the `BenchResult` JSON `SwarmBench` writes (TPS/MSPT/
  heap numbers plus `boot_ok`).
- `boot.log` — the full nested `:neoforge:runServer` stdout/stderr for
  the whole 60-minute run (every `multiforge.violation` line and any
  `RegionTickOverrunException` stack trace lands here).
- `gradle-swarm.log` — the outer `./gradlew :multiforge-bench:swarm ...`
  invocation's own stdout/stderr (Gradle build lifecycle, not the
  server's).
- `probes-snapshot.txt` — the mid-run `/multiforge probes` RCON reply
  (full `ProbeRegistry` dump), captured at ~58 minutes.
- `violation-log-tail.txt` — the last 200 lines of `boot.log`, preserved
  verbatim per `docs/verification/m9/7.6/README.md`'s precedent (a
  failing run's stack trace + region-id/chunk-pos context is what a
  filed issue needs).

## Known gaps

- The mid-run probe snapshot's timing (fixed 3480s sleep) is an
  approximation, not a precise "just before teardown" hook — see the
  script's own header comment. A future pass could add a proper mid-run
  RCON hook to `SwarmBench`/`HeadlessServerRunner` instead of polling
  from outside.
- `SwarmBench`'s swarm is the documented RCON `/summon` armor-stand
  fallback (see `multiforge-bench/README.md`), not a real Minecraft
  protocol client — the same caveat that already applied to M9's 7.4/7.6
  swarm runs applies here.

## Expected commands

Captured from `bash multiforge-bench/verification/m456/
x8-strict-mode-swarm.sh --dry-run` during this authoring pass — exit
`0`, ends with `PASS`. Nothing elided (dry-run never actually launches
the ~65-minute background job).

```
[04:42:11] x8-strict-mode-swarm: players=100 ticks=72000 strict=-Dmultiforge.regiontick.strict=on
DRY-RUN would run (background, ~60min real time): <repo-root>/gradlew :multiforge-bench:swarm -Pplayers=100 -Pticks=72000 -PextraJvmArgs="-Dmultiforge.regiontick.strict=on" -PoutputFile=<repo-root>/docs/verification/m456/evidence/x8/patched.json -PbootLog=<repo-root>/docs/verification/m456/evidence/x8/boot.log > <repo-root>/docs/verification/m456/evidence/x8/gradle-swarm.log 2>&1
DRY-RUN would: poll <repo-root>/docs/verification/m456/evidence/x8/boot.log for "RCON running on" up to 180s
DRY-RUN would: sleep 3480s, then rcon "multiforge probes" > <repo-root>/docs/verification/m456/evidence/x8/probes-snapshot.txt
DRY-RUN would: wait for the gradle swarm task to finish its own flush+stop teardown
DRY-RUN would: grep <repo-root>/docs/verification/m456/evidence/x8/boot.log for RegionTickOverrunException / OwnershipEnforcer REROUTE / region-tick.overrun hits (expect 0)
DRY-RUN would: tail -n 200 <repo-root>/docs/verification/m456/evidence/x8/boot.log > <repo-root>/docs/verification/m456/evidence/x8/violation-log-tail.txt
PASS
```
