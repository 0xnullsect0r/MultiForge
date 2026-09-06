# M12-live.2 — REGION-annotated listener routing

## Goal

Confirm a listener method annotated `@DispatchDomain(REGION)` actually runs on the owning region's worker thread when its event fires — not on the caller's thread, not inline on the main server thread.

## Procedure

1. Build a tiny test mod (place under `upstream/neoforge-1.21.1/projects/neoforge/run/mods/m12test.jar`):

   ```java
   @Mod("m12test")
   public class M12TestMod {
       @SubscribeEvent
       @DispatchDomain(DispatchDomainKind.REGION)
       public void onLevelTick(LevelTickEvent.Pre e) {
           OwnerToken tok = OwnerToken.current();
           System.err.printf("[m12test] LevelTickEvent thread=%s domain=%s region=%d%n",
               Thread.currentThread().getName(), tok.domain(), tok.regionId());
       }
   }
   ```

   (Or if a proper mod jar is friction: add the class to `upstream/neoforge-1.21.1/tests/src/main/java/net/multiforge/testfixtures/` and register via the existing test infrastructure.)

2. Boot the fork with the test mod loaded.
3. Wait for `Done`.
4. Watch stderr for `[m12test]` lines. Expected: every `LevelTickEvent.Pre` (fires once per level per tick, ~20/sec per loaded level) prints a line with `domain=REGION` and a non-zero `region=...`.
5. `/tp` yourself to a different chunk far away (triggers a new region). Verify subsequent `[m12test]` lines show a different `region=` value.
6. Snapshot probes:
   ```
   /multiforge probe event.dispatch.region
   /multiforge probe event.dispatch.inline
   ```

## Expected result

- `[m12test]` lines print continuously.
- Every line shows `domain=REGION`, never `domain=GLOBAL`, `domain=UNKNOWN`, or missing.
- Thread name in the log is a region worker (typically `region-worker-N` per the scheduler naming), not `Server thread`.
- After `/tp`, the reported `region=` value changes (proves the routing tracks region ownership).
- `event.dispatch.region` counter increments; `event.dispatch.legacy` counter does NOT.

## Actual result

<!-- Fill in on live-smoke pass. Example:

Date: 2026-MM-DD
Commit: b0aaf45
Verdict: PASS / FAIL
Notes:
  - Test mod loaded cleanly.
  - Log line: "[m12test] LevelTickEvent thread=region-worker-3 domain=REGION region=42".
  - /tp'd from spawn to (5000, 100, 5000); region changed from 42 to 78 within one tick.
  - event.dispatch.region: 0 → 400 in 20s (20 tps × 2 loaded levels × 10s roughly).
  - No legacy fallback.
Evidence: evidence/m12-live-2/{m12test-mod.jar, boot.log.grep-m12test, probe-region-delta.txt}
-->

## Failure modes

- **`domain=UNKNOWN`:** the OwnerToken ThreadLocal isn't set on the region worker. Likely a scheduler bug — the region-worker thread pool isn't binding OwnerToken.forRegion on task enter.
- **`domain=GLOBAL`:** the listener was routed to global not region. Check `EventTypeDomainMap`'s entry for `LevelTickEvent.Pre` (should be REGION) or the method-level annotation being read by AnnotationScanner.
- **Thread name is `Server thread`:** the wrapper isn't hopping. Either it decided inline dispatch (caller was on the same region worker somehow), or the wrapper isn't installed at all — cross-check M12-live.1.
- **No `[m12test]` lines at all:** the mod didn't load, or `@SubscribeEvent` scan didn't find the method. Check the boot log for mod-load errors.
