# B3-live.4 — 5-minute strict-mode soak

## Goal

With `-Dmultiforge.regiontick.strict=on`, the coordinator throws `RegionTickOverrunException` on any region-tick that exceeds the dispatch deadline, and `OwnershipEnforcer` throws instead of routing. Run 5 minutes of normal gameplay under strict mode; assert zero exceptions and zero probe bumps for wrong-owner conditions.

This is the "shakedown" test — it catches races that pass unit tests but only surface under real tick pressure.

## Procedure

1. Boot the fork with strict mode enabled:
   ```
   cd upstream/neoforge-1.21.1
   ./gradlew :neoforge:runServer -Dmultiforge.regiontick.strict=on -Dmultiforge.ownership.mode=strict
   ```
2. Wait for `Done`.
3. Spawn baseline load:
   ```
   /gamerule doDaylightCycle false
   /time set day
   /weather clear
   /execute at @s run summon minecraft:cow ~ ~ ~     # loop 20x
   /execute at @s run summon minecraft:chicken ~ ~ ~ # loop 20x
   /setblock ~ ~ ~5  minecraft:hopper[facing=down]   # loop 10x horizontally with cobble source above
   /setblock ~ ~ ~10 minecraft:redstone_wall_torch   # gives you a repeating scheduled-tick source
   ```
4. Snapshot probe state:
   ```
   /multiforge probe             # all probes
   ```
5. **Wait 5 minutes.** During the wait:
   - Walk around, mine some blocks, place torches (drives block-tick + entity-tick + chunk-load load).
   - Cross a region boundary a few times (drives A3 packet queue + M4 migration).
   - `/tp` to a distant unloaded chunk (drives M9 chunk load + region formation).
6. At the end, snapshot probes again:
   ```
   /multiforge probe
   ```
7. Grep `logs/multiforge-*.log` for any exception:
   ```
   grep -E "RegionTickOverrunException|OwnershipViolation|violation/" logs/multiforge-*.log | tail -50
   ```

## Expected result

- Server does not crash.
- `logs/multiforge-*.log` has **zero** `RegionTickOverrunException` or `OwnershipViolation` stack traces.
- Probe deltas:
  - `region-tick.dispatch.overrun` = 0
  - `region-tick.dispatch.failure` = 0
  - `entity-ai.wrong-owner` = 0
  - `block-entities.wrong-owner` = 0
  - `block-fluid.wrong-owner` = 0 (if exposed)
  - `region-tick.bootstrap-skip` = 0 (only fires pre-runtime-install)
- All 5 minutes of ticks completed within the dispatch deadline.

## Actual result

<!-- Fill in on live-smoke pass. Example:

Date: 2026-MM-DD
Commit: b0aaf45
Verdict: PASS / FAIL
Notes:
  - 5m elapsed; server responsive throughout.
  - Zero exceptions in logs/multiforge-*.log.
  - All wrong-owner probes stayed at 0.
  - dispatch.overrun: 3 events (all within one ~200ms window during a chunk-load burst — under WARN mode this would auto-reroute; under STRICT it threw). Investigating whether that's a real hot spot or acceptable jitter on this workstation.
Evidence: evidence/b3-live-4/{probe-before.txt, probe-after.txt, multiforge.log.grep, top-output.txt, screenshot.png}
-->

## Failure modes to watch for

- **`RegionTickOverrunException` under strict mode:** a region's tick took longer than the dispatch deadline (default 500ms). Could be a real perf hot spot (a mod doing something expensive in a hot handler) or the deadline is too tight for this workstation. Under WARN (default) this would auto-reroute + warn; under STRICT it throws so the operator sees it. Consider raising `-Dmultiforge.regiontick.dispatch-ms=1000` if it's this workstation's jitter, or file an issue if it's reproducible on a beefy box.
- **`OwnershipViolation` under strict-ownership mode:** a mod is touching foreign region state. Grep the trace for the offending caller; usually it's a mod bypassing `RegionizedTaskQueue`.
- **Server hangs / doesn't respond:** likely a deadlock. Take a thread dump (`jstack <pid>`) and file an issue.
