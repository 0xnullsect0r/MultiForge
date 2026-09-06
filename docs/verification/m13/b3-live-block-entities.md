# B3-live.2 — Block entity tick

## Goal

Verify that per-region block-entity tickers (hoppers, furnaces, brewing stands, etc.) actually tick. Pre-B3 `Level.tickBlockEntities()` ran on the main server thread via `vanillaBody.run()`; post-B3 the block-entity list is split per-region into `HolderManagerRegionData.blockEntityTickers` and each region worker ticks its own slice in the `BLOCK_ENTITIES` phase (layered after `phaseGlobalSystemsTick`).

## Procedure

1. Boot the fork with default config.
2. Wait for `Done`.
3. Prep a simple hopper chain to observe:
   ```
   /setblock ~ 100 ~ minecraft:chest
   /setblock ~ 101 ~ minecraft:hopper[facing=down]
   /setblock ~ 102 ~ minecraft:chest
   /item replace block ~ 102 ~ container.0 with minecraft:cobblestone 64
   ```
4. Snapshot probe state:
   ```
   /multiforge probe block-entities
   /multiforge probe block-entities.wrong-owner
   ```
5. Wait ~20 seconds. The hopper should transfer cobblestone from the upper chest to the lower chest.
6. Verify:
   ```
   /data get block ~ 100 ~ Items                 # should have some cobble
   /data get block ~ 102 ~ Items                 # should have less
   ```
7. Add a furnace:
   ```
   /setblock ~ 100 ~1 minecraft:furnace
   /item replace block ~ 100 ~1 container.0 with minecraft:iron_ore 8
   /item replace block ~ 100 ~1 container.1 with minecraft:coal 1
   ```
8. Wait ~90 seconds; verify smelting output:
   ```
   /data get block ~ 100 ~1 Items                # slot 2 should have iron ingots
   ```
9. Snapshot probe state again.

## Expected result

- Hopper transfer visibly progresses (item count in lower chest increases).
- Furnace smelts iron ore to ingots.
- `block-entities.wrong-owner` counter stays **zero**.
- No `[multiforge.violation/block-entity.…]` warns.
- Both `phaseGlobalSystemsTick` (global-region-only) and `phaseBlockEntitiesTickPerRegion` (per-region) coexist correctly — verify via probe deltas that the per-region body ticked ≥ 1 time per game tick on the region owning the hopper/furnace.

## Actual result

<!-- Fill in on live-smoke pass. Example:

Date: 2026-MM-DD
Commit: b0aaf45
Verdict: PASS / FAIL
Notes:
  - Hopper transferred 64 cobble in ~11s (default hopper cooldown is 8 ticks/item).
  - Furnace smelted 8 iron ingots in ~80s.
  - Probe counters clean.
Evidence: evidence/b3-live-2/{probe-before.txt, probe-after.txt, multiforge.log.tail, screenshot.png}
-->

## Failure modes to watch for

- **Hopper doesn't transfer:** `BlockEntityTickerBridge.onTickerAdded` isn't routing the ticker to its owning region — the ticker isn't in `HolderManagerRegionData.blockEntityTickers`. Check `Level.updateBlockEntityTicker` patch (Hunk A of `02-region-tick/net/minecraft/world/level/Level.java.patch`) is applied.
- **Furnace doesn't smelt:** furnace's block-entity `serverTick` isn't invoked; check `VanillaTickingBlockEntityAdapter.tick()` delegates correctly.
- **`block-entities.wrong-owner` bumps:** ticker's `pos()` resolves to a different region than the one iterating. Likely a split/merge redistribution bug — check `HolderManagerRegionData.split()` on ChunkPos predicate.
