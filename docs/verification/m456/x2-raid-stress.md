# X.2 — Cross-region raid stress

Script: `multiforge-bench/verification/m456/x2-raid-stress.sh`
Gradle task: `:multiforge-bench:x2RaidStress`

## Goal

Stress `RaidsSystem`'s cross-region raider spawn — per its own class
doc, "the one place this class differs from Vanilla" — with raids
seeded across all 4 region-boundary quadrants at once, and confirm no
raider spawn is silently lost in flight.

## ADAPTATION NOTE (read this before running)

The Phase X plan's literal recipe is RCON `/summon raid` +
`/execute at @e[type=raid] run summon zombie ~5000 ~ ~`, asserted via
`/multiforge probe entity-registry.orphans`. Neither exists in this
codebase — `/summon raid` isn't a real Vanilla or MultiForge command
(grepped `multiforge-runtime/`, the (nonexistent) `multiforge-testmods/`,
and the neoforge fork), and `MultiForgeCommandDispatcher`'s real
subcommand set is `config|region|probes|chunks|warn|certify` (see
`multiforge-runtime/src/main/java/net/multiforge/runtime/commands/
MultiForgeCommandDispatcher.java`) — there is no `probe` (singular)
subcommand and no `entity-registry.orphans` counter.

This script substitutes the closest real equivalents:

- 20 `minecraft:pillager` raid-capture seeds (`PatrolLeader:1b`) spread
  across the same 4 map quadrants X.1 uses, standing in for the literal
  `/summon raid`. A genuine Vanilla raid additionally needs a real
  player with Bad Omen inside a village's bounding box for `Raids.tick`'s
  own wave-spawn logic (and therefore `RaidsSystem.spawnRaider`'s
  cross-region hop) to fire — get one in-region before sprinting if the
  real run needs that.
- `/multiforge probes global.system.raids` (real — see
  `RaidsSystem.java`) in place of `entity-registry.orphans`. Its
  `raider-spawn-failure` counter is the closest real "did a cross-region
  raider spawn get lost" signal: `RaidsSystem.spawnRaider`'s try/catch is
  the only place a raider spawn can be dropped without a matching
  `raider-spawn-routed` bump (see `RaidsSystemTest
  .raiderSpawnFailureIsIsolatedAndNeverThrows`).

A ProbeRegistry counter for literal orphan-entity counting (the raid
equivalent of `EntityRegistry.retiredRefs` for migration — see
`docs/design/entity-migration.md` §"No lost UUID refs") does not exist
yet. See "Known gaps" below.

## Procedure

1. Capture with `-Dmultiforge.workers=4`, fixed seed, `tick freeze`.
2. Seed 20 raid captures: for each, `/summon minecraft:pillager <pos>
   {Tags:[...],PatrolLeader:1b}` at one of 4 quadrant anchors, then
   `/execute at @e[tag=...] run summon minecraft:zombie ~5000 ~ ~` to
   force a straddling-region wave spawn even without a live raid.
3. Snapshot `/multiforge probes global.system.raids` mid-run.
4. `tick sprint 6000` (5 game-minutes, letting `Raids.tick`/`RaidsSystem`
   process), `save-all flush`, `stop`.
5. Snapshot probes again post-sprint; grep the boot log for ownership
   violations.

## Expected result

- `global.system.raids.raider-spawn-failure` == 0 (no cross-region
  raider spawn silently dropped).
- Zero `RegionTickOverrunException` / `OwnershipEnforcer ... REROUTE` /
  `region-tick.overrun` hits in the capture's boot log.
- Both conditions true → `PASS`. Either false → `FAIL`.

## Actual

*(placeholder — fill in after a real run per `docs/verification/m456/
README.md` "How to run". This authoring pass only smoke-tested
`--dry-run`; see "Expected commands" below.)*

## NBT / probe evidence

Real-run evidence lands under `docs/verification/m456/evidence/x2/`:

- `patched-4w/world/`, `patched-4w/boot.log` — the capture.
- `probes-raids-mid.txt` — `/multiforge probes global.system.raids`
  captured right after the 20 raid seeds, before the sprint.
- `probes-raids-final.txt` — the same probe, captured post-sprint before
  shutdown.

## Known gaps

- No dedicated orphan-raider probe exists (see the ADAPTATION NOTE
  above). `raider-spawn-failure` is a real but narrower signal — it
  catches a spawn that *failed to route*, not a raider that routed fine
  but was later abandoned by `RaidStateSnapshot` bookkeeping. Adding a
  proper orphan-raider counter (mirroring `EntityRegistry.retiredRefs`'s
  TTL-cache pattern) is follow-up work, not part of this authoring pass.
- Triggering a *genuine* Vanilla raid (Bad Omen + a real player inside a
  village) isn't scripted here — the pillager seeds exercise the
  cross-region spawn-routing code path directly instead. If a real run
  needs the full `Raids.tick` wave-spawn lifecycle, get a test player
  into each quadrant with Bad Omen before sprinting.

## Expected commands

Captured from `bash multiforge-bench/verification/m456/x2-raid-stress.sh
--dry-run` during this authoring pass — exit `0`, ends with `PASS`. The
20-seed loop is condensed here (full transcript repeats the same
summon+wave pair, cycling 4 quadrants); nothing else is elided.

```
[04:42:09] x2-raid-stress: seed=1234567890 ticks=6000 raid-seeds=20
[04:42:09] x2: capturing 4-worker raid stress run
DRY-RUN would run (cwd=<repo-root>/upstream/neoforge-1.21.1): <repo-root>/gradlew :neoforge:runServer -Dmultiforge.workers=4 > <repo-root>/docs/verification/m456/evidence/x2/boot-patched-4w.log 2>&1
DRY-RUN would: poll boot log for RCON, then rcon "tick freeze"
DRY-RUN would: invoke mid-run callback function: x2_raid_callback
DRY-RUN would: rcon "tick sprint 6000"
DRY-RUN would: rcon "save-all flush" + rcon "stop"; copy world/ + logs/latest.log + boot log into <repo-root>/docs/verification/m456/evidence/x2/patched-4w
[04:42:09] x2: seeding 20 raid captures across 4 quadrants
DRY-RUN would rcon: summon minecraft:pillager 100 64 100 {Tags:["mf_x2_raid_0"],PatrolLeader:1b}
DRY-RUN would rcon: execute at @e[tag=mf_x2_raid_0,limit=1] run summon minecraft:zombie ~5000 ~ ~ {Tags:["mf_x2_wave_0"]}
DRY-RUN would rcon: summon minecraft:pillager 5100 64 100 {Tags:["mf_x2_raid_1"],PatrolLeader:1b}
DRY-RUN would rcon: execute at @e[tag=mf_x2_raid_1,limit=1] run summon minecraft:zombie ~5000 ~ ~ {Tags:["mf_x2_wave_1"]}
DRY-RUN would rcon: summon minecraft:pillager 100 64 5100 {Tags:["mf_x2_raid_2"],PatrolLeader:1b}
... (17 more raid seeds omitted — cycles the same 4 quadrants) ...
DRY-RUN would rcon: execute at @e[tag=mf_x2_raid_19,limit=1] run summon minecraft:zombie ~5000 ~ ~ {Tags:["mf_x2_wave_19"]}
DRY-RUN would: rcon "multiforge probes global.system.raids" > <repo-root>/docs/verification/m456/evidence/x2/probes-raids-final.txt (post-sprint, before stop)
DRY-RUN would: grep boot log for RegionTickOverrunException / OwnershipEnforcer REROUTE hits
PASS
```
