# B3-live.1 — Mob AI tick

## Goal

Verify that entities on region workers actually tick (walk, path, decay, breed). Pre-B3 the entity iteration ran on the main server thread via `vanillaBody.run()`; post-B3 it runs in the `ENTITY_AI` phase of the owning region's `PhasedRegionTickBody`. If B3's wiring is broken, entities will visibly freeze in place.

## Procedure

1. Boot the fork with default config (`./gradlew :neoforge:runServer` from `upstream/neoforge-1.21.1/`).
2. Wait for `Done` in the boot log.
3. From the server console:
   ```
   /gamerule doDaylightCycle false
   /time set day
   /weather clear
   /setblock ~ 100 ~ minecraft:air
   /tp @s ~ 100 ~
   ```
4. Spawn 50 cows in a 20-block radius:
   ```
   /execute at @s run summon minecraft:cow ~ ~ ~
   ```
   Loop 50 times (paste-and-repeat, or bind to a repeating command block).
5. Snapshot probe state:
   ```
   /multiforge probe entity-ai
   /multiforge probe entity-ai.wrong-owner
   /multiforge probe region-tick.dispatch
   ```
6. Wait 60 seconds. Cows should visibly walk, breed if you feed them wheat, and their timers should tick (via `/data get entity @e[type=cow,limit=1]`, look for `Air`, `HurtTime`, `Motion` fields changing).
7. Snapshot probe state again.
8. Kill the entities: `/kill @e[type=cow]` — verify the death-drops fire (LivingDeathEvent path).

## Expected result

- Cows visibly move (not frozen).
- Fed cows breed within ~30s.
- `entity-ai.wrong-owner` counter stays **zero** across the observation window.
- `region-tick.dispatch.failure` counter stays **zero**.
- No `[multiforge.violation/entity-ai.wrong-owner]` warns in `logs/multiforge-*.log`.
- All 50 cows are ticked per tick (verify via a `RegionizedRuntimeTests`-style probe if the operator has it enabled, or by counting movement).

## Actual result

<!-- Fill in on the live-smoke pass. Example: -->

<!--
Date: 2026-MM-DD
Commit: b0aaf45 (or whatever tip you tested)
Verdict: PASS / FAIL
Notes:
  - Spawned 50 cows at (0, 100, 0).
  - All visibly walked within 5s.
  - Fed 3 pairs wheat; 3 calves spawned within ~35s.
  - Probe counters unchanged across observation window (0 wrong-owner, 0 failures).
  - No violations in logs/multiforge-*.log.
Evidence: evidence/b3-live-1/{probe-before.txt, probe-after.txt, multiforge.log.tail, observations.md, screenshot.png}
-->

## Failure modes to watch for

- **Cows frozen in place:** ENTITY_AI phase isn't being invoked. Check `MultiThreadedSchedulerHost.phaseEntityAiTick` fires (add a temporary log line, or watch `/multiforge probe region-tick.entity-ai.invocations` if that probe exists).
- **`entity-ai.wrong-owner` bumps:** an entity's owning region doesn't match the caller. Likely a stale `OwnerToken` — check the fork bridge's `EntityTickRunnerBridge.tickEntitiesForRegion` OwnerToken setup.
- **`region-tick.dispatch.failure` bumps:** the whole region-tick fan-out is throwing. Look at the exception in the log; likely a fork-bridge NPE.
