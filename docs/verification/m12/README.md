# M12 verification — event routing

The four live checks this directory used to scaffold as unfilled report
templates are now automated. Results: [`../README.md`](../README.md).

| Check | Where it runs now |
|---|---|
| M12-live.1 — `NeoForge.EVENT_BUS` is the dispatching bus, executor attached after boot | GameTest `EventBusBridgeTests.eventBusIsLazyDispatchingAndAttachedAfterBoot` |
| M12-live.2 — a REGION listener runs on the owning region's worker | GameTest `EventBusBridgeTests.regionAnnotatedListenerFiresAndIsProbeRecorded` |
| M12-live.3 — serialised listeners run one at a time on the server thread | GameTest `EventBusBridgeTests.legacyListenerRunsOnTheSerialLaneAndItsCancellationIsKept`; live with a real mod jar in scenario `x4` (`mftest_legacy`) |
| M12-live.4 — `-Dmultiforge.event-dispatch=off` restores Vanilla dispatch | GameTest `EventBusBridgeTests.disableFlagLeavesTheBusUnattachedAndDispatchInline` |

The GameTests run in CI's `fork-build` job on every change.
