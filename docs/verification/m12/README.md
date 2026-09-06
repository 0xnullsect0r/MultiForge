# m12 Verification Artifacts

Live-smoke evidence for M12 — transparent event-bus routing via `LazyDispatchingEventBus` wrapping `NeoForge.EVENT_BUS`. See [`docs/design/m12-event-routing.md`](../../design/m12-event-routing.md) for the design.

## What "verified" means for M12

Unit tests (34 in `multiforge-runtime/src/test/java/net/multiforge/runtime/event/`) cover the dispatcher decision tree with fake buses + fake owner tokens. What they cannot cover:

1. The `LazyDispatchingEventBus` actually replaces `NeoForge.EVENT_BUS` at boot (the `09-events/NeoForge.java.patch` applied and the initializer swap happened).
2. The `SchedulerBackedDispatchExecutor` actually attaches on `ServerAboutToStart` (`MultiForgeGlobalSystemsInit.install` fires).
3. A real `@SubscribeEvent`-registered listener routes correctly under a real NeoForge boot.
4. The `-Dmultiforge.event-dispatch=off` safety valve actually returns Vanilla bus behavior when set.

Those four things need a real server run.

## Reports

| Task | Report | What to observe |
|------|--------|-----------------|
| M12-live.1 | [`m12-live-bus-wrap.md`](m12-live-bus-wrap.md) | `NeoForge.EVENT_BUS.getClass()` is `LazyDispatchingEventBus` after `Done`; attach-executor bridge fired |
| M12-live.2 | [`m12-live-region-listener.md`](m12-live-region-listener.md) | A `@DispatchDomain(REGION)` listener actually runs on the owning region worker |
| M12-live.3 | [`m12-live-global-listener.md`](m12-live-global-listener.md) | A `@DispatchDomain(GLOBAL)` listener runs on the global region worker |
| M12-live.4 | [`m12-live-safety-valve.md`](m12-live-safety-valve.md) | `-Dmultiforge.event-dispatch=off` restores raw Vanilla bus behavior |

## How to run

Each report has its own detailed procedure. Common setup:

```
export JAVA_HOME=/path/to/jdk-21
./gradlew :multiforge-api:publishToMavenLocal :multiforge-runtime:publishToMavenLocal
cd upstream/neoforge-1.21.1
./gradlew :neoforge:applyMultiforgePatches
./gradlew :neoforge:compileJava
./gradlew :neoforge:runServer                   # or with -Dmultiforge.event-dispatch=off for M12-live.4
```

The four listener-side verifications (M12-live.2, .3) need a small test mod jar built ad-hoc:

```java
@Mod("m12test")
public class M12TestMod {
    @SubscribeEvent
    @DispatchDomain(DispatchDomainKind.REGION)
    public void onLevelTick(LevelTickEvent.Pre e) {
        // Verify OwnerToken.current().domain() == REGION at fire time.
        System.err.println("[m12test] LevelTickEvent fired on: " +
            Thread.currentThread().getName() + " domain=" +
            OwnerToken.current().domain());
    }
}
```

Drop that into `upstream/neoforge-1.21.1/projects/neoforge/run/mods/` before `:runServer`. Observe the boot log lines to verify dispatch.

## Evidence layout

```
evidence/
├── m12-live-1/
│   ├── boot.log.grep-eventbus
│   ├── multiforge.log.tail
│   └── probe-dispatch-counters.txt
├── m12-live-2/
│   ├── m12test-mod.jar
│   └── observations.md
└── ...
```

The `/multiforge probe event.dispatch.*` counters are the primary evidence:

- `event.dispatch.inline` — listener ran inline (caller domain matched listener domain)
- `event.dispatch.region` — routed to a region worker
- `event.dispatch.global` — routed to the global region
- `event.dispatch.async` — routed to the ASYNC pool
- `event.dispatch.legacy` — LEGACY_SERIAL fallback + rate-limited warn
- `event.dispatch.async.overflow` — ASYNC pool saturated + dropped (should be zero under normal load)
