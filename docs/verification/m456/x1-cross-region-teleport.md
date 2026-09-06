# X.1 — Cross-region entity teleport regression

Script: `multiforge-bench/verification/m456/x1-cross-region-teleport.sh`
Gradle task: `:multiforge-bench:x1CrossRegionTeleport`

## Goal

Prove that a fixed seed produces the same *semantic* world state whether
it's ticked with one worker (one region — a `/tp` can never cross a
region boundary, so `EntityMigrationCoordinator` never fires) or four
workers (four regions — the same `/tp` calls now migrate mobs across
region boundaries). A `WorldDiff.DiffMode.SEMANTIC` mismatch between the
two captures means the migration hop (A2's `Entity.setPosRaw`/`teleportTo`
patches, A1's `MigratingEntityRef` CAS state machine) introduced a
divergence from what a single-region tick would have produced. This is
the same trick M9 used for its own N-worker regression — see
`docs/verification/m9/7.3/README.md`.

## Procedure

1. Capture **baseline**: `-Dmultiforge.workers=1`, fixed seed, `tick
   freeze` → `tick sprint 6000` (5 game-minutes) → `save-all flush` →
   `stop`.
2. Capture **patched**: same seed, `-Dmultiforge.workers=4`. Mid-capture
   (after `tick freeze`, before `tick sprint`): summon 10 tagged test
   mobs, then rapid-fire 100 RCON `/tp` calls cycling them through 4 map
   quadrants ~5000 blocks apart (well past any sane region-size
   configuration, so every `/tp` after the first is guaranteed to cross a
   region boundary). Snapshot `/multiforge probes entity-migration`
   before `save-all flush`/`stop`.
3. `./gradlew :multiforge-bench:determinism -PdiffMode=SEMANTIC` over the
   two captured `world/` directories.
4. Parse the `entity-migration.total` counter out of the probe snapshot.

## Expected result

- The SEMANTIC diff reports no divergence (`WorldDiff.Result` empty diff
  set, `determinism` task exit `0`).
- `entity-migration.total` is greater than zero — this is the guard
  against "the diff passed only because the mobs never actually left
  their starting region" (e.g. a region-size misconfiguration on the
  test workstation, or a callback that silently no-oped).
- Both conditions true → `PASS`. Either false → `FAIL`.

## Actual

*(placeholder — fill in after a real run per `docs/verification/m456/
README.md` "How to run". This authoring pass only smoke-tested
`--dry-run`; see "Expected commands" below.)*

## NBT / probe evidence

Real-run evidence lands under `docs/verification/m456/evidence/x1/`:

- `baseline-1w/world/`, `patched-4w/world/` — the two captured saves.
- `baseline-1w/boot.log`, `patched-4w/boot.log` — full server stdout for
  each capture (carries any `multiforge.violation` lines too).
- `probes-entity-migration.txt` — the `/multiforge probes
  entity-migration` RCON reply, captured from the patched run before
  shutdown.
- `world-diff-semantic.log` — the `:multiforge-bench:determinism
  -PdiffMode=SEMANTIC` output (pass/fail summary, and on failure the
  diverging-chunk detail).

## Known gaps

None specific to X.1 — see `docs/verification/m456/README.md` "Known
gaps" for the cross-task list (x2, x3, x8).

## Expected commands

Captured from `bash multiforge-bench/verification/m456/
x1-cross-region-teleport.sh --dry-run` during this authoring pass — exit
`0`, ends with `PASS`. The 100-call `/tp` loop is condensed here (full
transcript cycles the same 4 quadrants 25 times); nothing else is
elided.

```
[04:42:03] x1-cross-region-teleport: seed=1234567890 ticks/capture=6000 tp-count=100
[04:42:03] x1: capturing baseline (1 worker, no migration possible — single region)
DRY-RUN would run (cwd=<repo-root>/upstream/neoforge-1.21.1): <repo-root>/gradlew :neoforge:runServer -Dmultiforge.workers=1 > <repo-root>/docs/verification/m456/evidence/x1/boot-baseline-1w.log 2>&1
DRY-RUN would: poll boot log for RCON, then rcon "tick freeze"
DRY-RUN would: rcon "tick sprint 6000"
DRY-RUN would: rcon "save-all flush" + rcon "stop"; copy world/ + logs/latest.log + boot log into <repo-root>/docs/verification/m456/evidence/x1/baseline-1w
[04:42:03] x1: capturing patched (4 workers, teleport callback drives cross-region migration)
DRY-RUN would run (cwd=<repo-root>/upstream/neoforge-1.21.1): <repo-root>/gradlew :neoforge:runServer -Dmultiforge.workers=4 > <repo-root>/docs/verification/m456/evidence/x1/boot-patched-4w.log 2>&1
DRY-RUN would: poll boot log for RCON, then rcon "tick freeze"
DRY-RUN would: invoke mid-run callback function: x1_teleport_callback
DRY-RUN would: rcon "tick sprint 6000"
DRY-RUN would: rcon "save-all flush" + rcon "stop"; copy world/ + logs/latest.log + boot log into <repo-root>/docs/verification/m456/evidence/x1/patched-4w
[04:42:03] x1: summoning 10 cross-region test mobs
DRY-RUN would rcon: summon minecraft:pig 100 -60 100 {Tags:["mf_x1_test"]}
DRY-RUN would rcon: summon minecraft:pig 100 -60 100 {Tags:["mf_x1_test"]}
DRY-RUN would rcon: summon minecraft:pig 100 -60 100 {Tags:["mf_x1_test"]}
DRY-RUN would rcon: summon minecraft:pig 100 -60 100 {Tags:["mf_x1_test"]}
DRY-RUN would rcon: summon minecraft:pig 100 -60 100 {Tags:["mf_x1_test"]}
DRY-RUN would rcon: summon minecraft:pig 100 -60 100 {Tags:["mf_x1_test"]}
DRY-RUN would rcon: summon minecraft:pig 100 -60 100 {Tags:["mf_x1_test"]}
DRY-RUN would rcon: summon minecraft:pig 100 -60 100 {Tags:["mf_x1_test"]}
DRY-RUN would rcon: summon minecraft:pig 100 -60 100 {Tags:["mf_x1_test"]}
DRY-RUN would rcon: summon minecraft:pig 100 -60 100 {Tags:["mf_x1_test"]}
[04:42:03] x1: rapid-teleporting test mobs across 4 quadrants (100 total /tp calls)
DRY-RUN would rcon: execute as @e[tag=mf_x1_test] run tp @s 100 -60 100
... (96 more /tp calls omitted — cycles the same 4 quadrants) ...
DRY-RUN would rcon: execute as @e[tag=mf_x1_test] run tp @s 100 -60 5100
DRY-RUN would rcon: execute as @e[tag=mf_x1_test] run tp @s 5100 -60 5100
[04:42:03] x1: SEMANTIC diff baseline-1w vs patched-4w
DRY-RUN would run: <repo-root>/gradlew :multiforge-bench:determinism -PdiffMode=SEMANTIC -Pseed=1234567890 --args="<repo-root>/docs/verification/m456/evidence/x1/baseline-1w/world <repo-root>/docs/verification/m456/evidence/x1/patched-4w/world" > <repo-root>/docs/verification/m456/evidence/x1/world-diff-semantic.log
PASS
```
