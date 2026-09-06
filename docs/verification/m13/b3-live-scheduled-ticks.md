# B3-live.3 — Scheduled block/fluid tick

## Goal

Verify that scheduled block and fluid ticks (redstone repeaters, water flow, sapling growth, snow melt) fire on region workers. Pre-B3 `Level.getBlockTicks().tick(...)` and `getFluidTicks().tick(...)` ran on the main thread via `vanillaBody.run()`; post-B3 the phase body drains each owned chunk's `LevelChunkTicks` container via `ServerLevel.mfTickBlockFluidTicksForChunk(holder)` in the `BLOCK_FLUID_TICKS` phase.

## Procedure

1. Boot the fork.
2. Wait for `Done`.
3. Snapshot probe state:
   ```
   /multiforge probe block-fluid
   /multiforge probe region-tick.block-fluid.failure
   ```
4. **Redstone repeater test:** place a repeater + button chain:
   ```
   /setblock ~ ~ ~   minecraft:redstone_block
   /setblock ~ ~ ~1  minecraft:repeater[facing=south,delay=4]
   /setblock ~ ~ ~2  minecraft:redstone_lamp
   ```
   Verify the lamp lights up within 4-8 ticks (~500ms). If the repeater doesn't schedule its tick, the lamp stays dark.
5. **Water flow test:**
   ```
   /setblock ~ 100 ~   minecraft:water
   /setblock ~ 100 ~1  minecraft:air
   /setblock ~ 100 ~2  minecraft:air
   ```
   Wait ~5s; water should visibly flow into the adjacent air blocks (scheduled fluid tick).
6. **Sapling growth test** (long-running; scheduled tick with random delay):
   ```
   /gamerule randomTickSpeed 100        # accelerate growth
   /setblock ~ ~ ~   minecraft:grass_block
   /setblock ~ ~1 ~  minecraft:oak_sapling
   ```
   Wait ~30-60s; sapling should grow into a tree.
7. Snapshot probe state again.

## Expected result

- Redstone repeater's downstream lamp lights up within ~500ms.
- Water flows into adjacent empty blocks.
- Sapling grows within ~60s at `randomTickSpeed 100`.
- `region-tick.block-fluid.failure` counter stays **zero**.
- No `[multiforge.violation/block-fluid.…]` warns.
- Probe delta shows `phaseBlockFluidTicksTick` invoked ~20x per second (once per tick per region).

## Actual result

<!-- Fill in on live-smoke pass. Example:

Date: 2026-MM-DD
Commit: b0aaf45
Verdict: PASS / FAIL
Notes:
  - Repeater fired at expected 4-tick delay.
  - Water flowed 2 blocks in ~3s.
  - Sapling grew into an oak tree in ~45s at randomTickSpeed 100.
  - Probe counters clean.
Evidence: evidence/b3-live-3/{probe-before.txt, probe-after.txt, multiforge.log.tail, screenshot.png}
-->

## Failure modes to watch for

- **Repeater doesn't fire:** `LevelTicks.mfContainerForChunk(ChunkPos)` returns null or the container drain doesn't happen. Check the `02-region-tick/.../LevelTicks.java.patch` applied and `ScheduledTickRunnerBridge.runBlockFluidTicks` walks the region's chunks correctly.
- **Water stays static:** fluid ticks aren't scheduled or aren't drained. Same failure mode; different `LevelTicks<Fluid>` container.
- **Sapling never grows:** random ticks (`ServerLevel.tickChunk`) aren't invoked. This is a separate code path from scheduled ticks — random ticks are still driven by `ServerChunkCache.tick`, which is NOT part of B3's per-region migration. If saplings don't grow but repeaters + water do, that's expected and documents the deferred M14-material item.
