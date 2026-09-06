# M12-live.4 — `-Dmultiforge.event-dispatch=off` safety valve

## Goal

Confirm the documented rollback path works: booting with `-Dmultiforge.event-dispatch=off` skips the dispatcher entirely — `NeoForge.EVENT_BUS` reads and behaves as the raw Vanilla NeoForge bus. This is the operator's escape hatch if the dispatcher ever causes a live-server incident.

## Procedure

1. Boot the fork with the safety valve enabled:
   ```
   cd upstream/neoforge-1.21.1
   ./gradlew :neoforge:runServer -Dmultiforge.event-dispatch=off
   ```
2. Wait for `Done`.
3. Grep the boot log:
   ```
   grep -iE "event-dispatch|dispatchingeventbus|LazyDispatchingEventBus" logs/latest.log
   ```
   Expected: a log line indicating "MultiForge event dispatch disabled (multiforge.event-dispatch=off)" or similar — check the fork bridge's `EventBusBridge.wrap` for the exact message.
4. Load the test mod from M12-live.2 (with the REGION-annotated `LevelTickEvent` listener).
5. Watch stderr for `[m12test]` lines. Expected: they still print (listener still receives events — the bus works), but the thread name is the main server thread (`Server thread`), not a region worker, and `OwnerToken.current().domain()` is `UNKNOWN` (because the dispatcher isn't hopping).
6. Snapshot:
   ```
   /multiforge probe event.dispatch.region
   /multiforge probe event.dispatch.global
   /multiforge probe event.dispatch.inline
   /multiforge probe event.dispatch.legacy
   ```
7. Wait 10 seconds; walk around; break blocks. All counters should stay at **zero** — the dispatcher isn't intercepting anything.

## Expected result

- Boot log confirms dispatch is disabled.
- `[m12test]` listener still fires (proves bus is functional, just not routed).
- Thread in listener is `Server thread`; domain is `UNKNOWN`.
- All `event.dispatch.*` probes stay at 0 after 10s of activity.
- No performance regression vs stock NeoForge (this mode = same as stock).

## Actual result

<!-- Fill in on live-smoke pass. Example:

Date: 2026-MM-DD
Commit: b0aaf45
Verdict: PASS / FAIL
Notes:
  - Boot log: "[multiforge] event-dispatch=off — using raw NeoForge.EVENT_BUS".
  - LevelTickEvent listener fired on "Server thread"; domain=UNKNOWN.
  - All event.dispatch.* probes stayed at 0.
  - Bus functionality intact.
Evidence: evidence/m12-live-4/{boot.log.grep, probe-final.txt, m12test-observations.md}
-->

## Failure modes

- **Log still shows LazyDispatchingEventBus attached:** the sysprop wasn't respected. Check `EventBusBridge.wrap` reads `System.getProperty("multiforge.event-dispatch")` correctly and honors `"off"` (case-sensitive?).
- **Listener doesn't fire at all:** you accidentally broke the bus entirely. `NeoForge.EVENT_BUS` should be the raw Vanilla bus in this mode, not null.
- **`event.dispatch.*` counters increment:** the dispatcher wasn't fully disabled. Some code path still routes.

## Why this matters

If B3 or M12 ever cause a production incident, the operator needs a documented, well-tested rollback that doesn't require reverting the install. `-Dmultiforge.event-dispatch=off` is that valve for M12. There's a similar one for B3 (`-Dmultiforge.regiontick.strict=off`, the default) and for ownership (`-Dmultiforge.ownership.mode=off`). Documenting them here means the operator has receipts they work.
