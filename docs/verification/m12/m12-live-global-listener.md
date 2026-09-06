# M12-live.3 — GLOBAL-annotated listener routing

## Goal

Confirm a listener method annotated `@DispatchDomain(GLOBAL)` runs on the global-region worker thread. This is the symmetric case to M12-live.2.

## Procedure

1. Extend the test mod from M12-live.2 with a second listener:

   ```java
   @SubscribeEvent
   @DispatchDomain(DispatchDomainKind.GLOBAL)
   public void onServerTick(ServerTickEvent.Pre e) {
       OwnerToken tok = OwnerToken.current();
       System.err.printf("[m12test] ServerTickEvent thread=%s domain=%s%n",
           Thread.currentThread().getName(), tok.domain());
   }
   ```

   `ServerTickEvent` naturally fires once per server tick (~20/sec, not per-level), and `EventTypeDomainMap` maps it to `GLOBAL` by default — the explicit annotation here is belt-and-suspenders.

2. Boot the fork with the test mod loaded.
3. Wait for `Done`.
4. Watch stderr for `[m12test] ServerTickEvent` lines. Expected: 20/sec, all with `domain=GLOBAL`, thread is the global-region worker.
5. Snapshot:
   ```
   /multiforge probe event.dispatch.global
   /multiforge probe event.dispatch.inline
   ```
6. Wait 10 seconds, snapshot again. `global` should have incremented by ~200 (20 tps × 10s).

## Expected result

- `[m12test] ServerTickEvent` lines print ~20/sec.
- Every line shows `domain=GLOBAL`.
- Thread name is the global-region worker (typically `region-worker-global` or similar).
- `event.dispatch.global` counter increments at ~20/sec.
- `event.dispatch.legacy` counter does NOT.

## Actual result

<!-- Fill in on live-smoke pass. Example:

Date: 2026-MM-DD
Commit: b0aaf45
Verdict: PASS / FAIL
Notes:
  - Log line: "[m12test] ServerTickEvent thread=region-worker-global domain=GLOBAL".
  - event.dispatch.global: 0 → 200 in 10s.
  - Zero legacy bumps.
Evidence: evidence/m12-live-3/{boot.log.grep-servertick, probe-global-delta.txt}
-->

## Failure modes

- Same as M12-live.2 but for the GLOBAL branch. `domain=REGION` would mean the dispatcher misrouted (check GlobalRegionThreadMarker + phaseGlobalSystemsTick).
- If `ServerTickEvent` never fires: the global-region tick body isn't invoking it. This is separate from M12 — it's M5 territory. Check `GlobalSystems.tickAll` is wired.
