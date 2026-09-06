# X.3 — Dragon fight regression

Script: `multiforge-bench/verification/m456/x3-dragon-fight-regression.sh`
Gradle task: `:multiforge-bench:x3DragonFight`

## Goal

Confirm `DragonFightSystem`'s death-handling code path (end-podium spawn,
exit-portal/gateway placement, `TicketType.DRAGON` unpin — see
`docs/design/global-region.md` §6.3 and `DragonFightSystem.java`)
produces a byte/semantic-identical End structure on a fixed seed
regardless of whether it ran under one worker (one region) or four
(the dragon fight straddles the global region and whichever region owns
`minecraft:the_end`).

## ADAPTATION NOTE (read this before running)

There is no automated combat bot in this repo — `multiforge-bench` stays
MC-free by design (see `multiforge-bench/README.md`), and no such client
exists elsewhere in the tree. This script force-summons the dragon (if
none exists yet) and `/kill`s it over RCON instead of simulating a fight.
This is a legitimate substitute for what this regression actually cares
about: `EndDragonFight`'s health-reaches-zero handling runs identically
whether the dragon died from combat damage or a `/kill` — the regression
is about the *code path after death*, not the fight itself.

## Procedure

1. Capture **baseline**: `-Dmultiforge.workers=1`, seed `1985` (matches
   the plan's `-Ptest.seed=1985`), `tick freeze`.
2. Mid-capture: `/execute unless entity @e[type=minecraft:ender_dragon]
   in minecraft:the_end run summon minecraft:ender_dragon 0 128 0` (only
   summons if absent — idempotent), then `/execute in minecraft:the_end
   run kill @e[type=minecraft:ender_dragon]`.
3. `tick sprint 6000` (5 game-minutes — lets `DragonFightSystem.tick`
   fully process the death phase transition), `save-all flush`, `stop`.
4. Repeat steps 1-3 with `-Dmultiforge.workers=4` for **patched**.
5. `./gradlew :multiforge-bench:determinism -PdiffMode=SEMANTIC` over the
   two captured `world/` directories.
6. Grep the patched boot log for `global.system.dragon_fight.*failure`
   and `.no-region` probe hits.

## Expected result

- The SEMANTIC diff reports no divergence between baseline and patched
  end-podium/gateway/dimension state.
- Zero `dragon_fight` failure/no-region probe hits in the patched boot
  log (the dragon-fight system never fell back to a degraded path).
- Both conditions true → `PASS`. Either false → `FAIL`.

## Actual

*(placeholder — fill in after a real run per `docs/verification/m456/
README.md` "How to run". This authoring pass only smoke-tested
`--dry-run`; see "Expected commands" below.)*

## NBT / probe evidence

Real-run evidence lands under `docs/verification/m456/evidence/x3/`:

- `baseline-1w/world/`, `patched-4w/world/` — the two captured saves,
  each including `DIM1` (the End).
- `baseline-1w/boot.log`, `patched-4w/boot.log` — full server stdout.
- `probes-dragon-fight.txt` — `/multiforge probes
  global.system.dragon_fight`, captured from each run before shutdown
  (overwritten between baseline and patched — a real run should copy it
  out or rename per-capture if both snapshots are wanted).
- `world-diff-semantic.log` — the `:multiforge-bench:determinism
  -PdiffMode=SEMANTIC` output.

## Known gaps

- See the ADAPTATION NOTE above — this is a forced-kill regression, not
  a simulated fight. It does not exercise `DragonFightSystem`'s
  in-combat tick behavior (phase transitions while the dragon is alive),
  only the death transition.
- `probes-dragon-fight.txt` is captured once per capture and not
  separated by baseline/patched in the evidence layout as written — see
  the note above.

## Expected commands

Captured from `bash multiforge-bench/verification/m456/
x3-dragon-fight-regression.sh --dry-run` during this authoring pass —
exit `0`, ends with `PASS`. Nothing elided.

```
[04:42:10] x3-dragon-fight-regression: seed=1985 ticks/capture=6000
[04:42:10] x3: capturing baseline (1 worker)
DRY-RUN would run (cwd=<repo-root>/upstream/neoforge-1.21.1): <repo-root>/gradlew :neoforge:runServer -Dmultiforge.workers=1 > <repo-root>/docs/verification/m456/evidence/x3/boot-baseline-1w.log 2>&1
DRY-RUN would: poll boot log for RCON, then rcon "tick freeze"
DRY-RUN would: invoke mid-run callback function: x3_dragon_kill_callback
DRY-RUN would: rcon "tick sprint 6000"
DRY-RUN would: rcon "save-all flush" + rcon "stop"; copy world/ + logs/latest.log + boot log into <repo-root>/docs/verification/m456/evidence/x3/baseline-1w
[04:42:10] x3: ensuring an ender dragon exists in the_end, then killing it
DRY-RUN would rcon: execute unless entity @e[type=minecraft:ender_dragon] in minecraft:the_end run summon minecraft:ender_dragon 0 128 0
DRY-RUN would rcon: execute in minecraft:the_end run kill @e[type=minecraft:ender_dragon]
[04:42:10] x3: capturing patched (4 workers)
DRY-RUN would run (cwd=<repo-root>/upstream/neoforge-1.21.1): <repo-root>/gradlew :neoforge:runServer -Dmultiforge.workers=4 > <repo-root>/docs/verification/m456/evidence/x3/boot-patched-4w.log 2>&1
DRY-RUN would: poll boot log for RCON, then rcon "tick freeze"
DRY-RUN would: invoke mid-run callback function: x3_dragon_kill_callback
DRY-RUN would: rcon "tick sprint 6000"
DRY-RUN would: rcon "save-all flush" + rcon "stop"; copy world/ + logs/latest.log + boot log into <repo-root>/docs/verification/m456/evidence/x3/patched-4w
[04:42:10] x3: ensuring an ender dragon exists in the_end, then killing it
DRY-RUN would rcon: execute unless entity @e[type=minecraft:ender_dragon] in minecraft:the_end run summon minecraft:ender_dragon 0 128 0
DRY-RUN would rcon: execute in minecraft:the_end run kill @e[type=minecraft:ender_dragon]
[04:42:10] x3: SEMANTIC diff baseline-1w vs patched-4w (end-podium + gateway parity)
DRY-RUN would run: <repo-root>/gradlew :multiforge-bench:determinism -PdiffMode=SEMANTIC -Pseed=1985 --args="<repo-root>/docs/verification/m456/evidence/x3/baseline-1w/world <repo-root>/docs/verification/m456/evidence/x3/patched-4w/world" > <repo-root>/docs/verification/m456/evidence/x3/world-diff-semantic.log
PASS
```
