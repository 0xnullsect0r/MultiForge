# M12-live.1 — EVENT_BUS wrapper installed at boot

## Goal

Confirm the `09-events/net/neoforged/neoforge/common/NeoForge.java.patch` actually applies and `NeoForge.EVENT_BUS` is a `LazyDispatchingEventBus` at runtime, and that `MultiForgeGlobalSystemsInit.install` attaches the `SchedulerBackedDispatchExecutor` on `ServerAboutToStart`.

## Procedure

1. Boot the fork:
   ```
   cd upstream/neoforge-1.21.1
   ./gradlew :neoforge:runServer
   ```
2. Wait for `Done`.
3. Search the boot log for MultiForge event-bus install lines:
   ```
   grep -E "multiforge.*event|EventBusBridge|LazyDispatchingEventBus|attach" logs/latest.log
   ```
   Expected: an "attached SchedulerBackedDispatchExecutor to LazyDispatchingEventBus" line (or similar; check `MultiForgeGlobalSystemsInit.install` for the exact message).
4. From an op console or RCON, verify probes exist:
   ```
   /multiforge probe event.dispatch.inline
   /multiforge probe event.dispatch.region
   /multiforge probe event.dispatch.global
   /multiforge probe event.dispatch.async
   /multiforge probe event.dispatch.legacy
   ```
   Each should return a numeric value (may be 0 initially — that's fine; the probes existing at all confirms the runtime module is on the classpath and initialised).
5. Trigger any event (walk one block to fire `PlayerTickEvent`, break one block to fire `BlockEvent`). Re-check counters:
   ```
   /multiforge probe event.dispatch.inline
   /multiforge probe event.dispatch.region
   ```
   At least one should have incremented.

## Expected result

- Log contains `LazyDispatchingEventBus` reference during boot.
- Log contains `MultiForge event bus attached` (or equivalent) at `ServerAboutToStart`.
- All five `event.dispatch.*` probes exist and return numeric values.
- After triggering an event, at least one dispatch counter has incremented — proves the wrapper's `post()` path executed.

## Actual result

<!-- Fill in on live-smoke pass. Example:

Date: 2026-MM-DD
Commit: b0aaf45
Verdict: PASS / FAIL
Notes:
  - Boot log line: "[multiforge] Attached SchedulerBackedDispatchExecutor to NeoForge.EVENT_BUS".
  - Walked one block. inline: 0 → 2, region: 0 → 4, global: 0 → 1.
  - No legacy fallback bumps (all events had EventTypeDomainMap defaults or explicit @DispatchDomain).
Evidence: evidence/m12-live-1/{boot.log.grep, probe-before.txt, probe-after-walk.txt}
-->

## Failure modes

- **No `LazyDispatchingEventBus` in boot log:** the `09-events/NeoForge.java.patch` didn't apply. Check `./gradlew :neoforge:applyMultiforgePatches` output for the "applied" line.
- **`event.dispatch.*` probes return `unknown probe`:** runtime jar isn't on the classpath, or the `ProbeRegistry` isn't initialised. Check `libraries/multiforge/multiforge-runtime.jar` exists.
- **Probes exist but never increment:** the wrapper's `post()` isn't intercepting. Check `EventBusBridge.attach` fired and `LazyDispatchingEventBus.attachExecutor` was called with a non-null executor.
