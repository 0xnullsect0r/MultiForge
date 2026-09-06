# MultiForge Concurrency Contract

## Executive summary

Every thread MultiForge schedules work on carries a **domain** — a tag
saying what it's allowed to touch right now. Two independent layers read
that tag: a passive, dev-only **assertion** layer that counts violations
without changing behavior, and an active, production **enforcement** layer
that decides whether a mutation runs inline or gets handed off elsewhere.
This document is the source of truth for both, plus the rules that decide
which domain may write what state. See `docs/blueprint.md` §Terminology
for the design rationale; this page documents what's actually implemented
under `multiforge-runtime/.../ownership/`.

## Domain

`net.multiforge.runtime.ownership.Domain` is a plain enum with seven
values:

| Value           | Meaning                                                                                          |
|-----------------|---------------------------------------------------------------------------------------------------|
| `REGION`        | A region worker thread. Owns some subset of the world's chunks and entities.                      |
| `ENTITY`        | A per-entity affine executor — typically an alias for the region that currently owns the entity.  |
| `GLOBAL`        | The dedicated global-region thread. Owns weather, time, world border, gamerules, ender dragon, wither, raids, scoreboards. |
| `ASYNC`         | A worker on the shared async pool. May touch pure/immutable data only.                            |
| `LEGACY_SERIAL` | Fallback single-threaded executor for legacy/unaudited mod callbacks.                             |
| `NETWORK`       | Netty IO thread; never mutates game state directly.                                               |
| `UNKNOWN`       | Anything else — main-thread bootstrap, shutdown, or a thread nobody tagged.                       |

This is the same seven-value vocabulary used by `DispatchDomainKind`
(events, four of the seven — see `docs/events.md`) and by the tick
pipeline's own worker classification. `ENTITY` and `NETWORK` don't get
their own worker pool; they're either an alias for a region worker
(`ENTITY`) or a classification applied to Netty's own IO threads
(`NETWORK`) so ownership checks can recognize them without pretending
they're safe to mutate state from.

## OwnerToken

`OwnerToken` is a record — `(Domain domain, long regionId)` — carried on a
`ThreadLocal` and read by every ownership check in the runtime.

```java
public record OwnerToken(Domain domain, long regionId) {
    public static final long NO_REGION = Long.MIN_VALUE;
    public static final OwnerToken GLOBAL = new OwnerToken(Domain.GLOBAL, NO_REGION);
    public static final OwnerToken ASYNC = new OwnerToken(Domain.ASYNC, NO_REGION);
    public static final OwnerToken NETWORK = new OwnerToken(Domain.NETWORK, NO_REGION);
    public static final OwnerToken LEGACY_SERIAL = new OwnerToken(Domain.LEGACY_SERIAL, NO_REGION);

    public static OwnerToken forRegion(long regionId) { ... }
    public static OwnerToken current() { ... }
    public static void runAs(OwnerToken token, Runnable work) { ... }
}
```

- **`current()`** never returns `null`. If nothing has set a token on this
  thread, it synthesizes `new OwnerToken(Domain.UNKNOWN, NO_REGION)`. Every
  assertion and enforcement check treats a bare, untagged thread as
  `UNKNOWN` rather than crashing on a missing token.
- **Region worker threads set their own token** when they start ticking a
  region (`OwnerToken.forRegion(regionId)`) and clear it when they finish.
  This is bookkeeping internal to `TickRegionScheduler`; mod code never
  calls `forRegion` directly.
- **`runAs(token, work)`** installs `token` for the duration of `work`,
  then restores whatever token was there before — including `null` if
  there wasn't one. Nesting is supported: an `ASYNC`-tagged callback that
  internally calls back into region code via `runAs(OwnerToken.forRegion(id),
  …)` restores the `ASYNC` token on the way out, not `UNKNOWN`.
  `MultiThreadedSchedulerHost`'s async domain implementation uses exactly
  this pattern — every async body runs wrapped in
  `OwnerToken.runAs(OwnerToken.ASYNC, () -> body.accept(handle))`.

## DomainAssertions (dev/CI only)

`DomainAssertions` is the **passive** half of the contract: a set of
assertion sites patched into Vanilla/NeoForge call sites purely for
telemetry. It is off by default and, critically, **never throws, even
when enabled** — it bumps a probe counter and logs a rate-limited warning.

- Gated by `-Dmultiforge.assert=on` (default `off`). Hot paths check
  `DomainAssertions.enabled()` first and skip the probe call entirely when
  disabled, so the assertion layer has effectively zero cost in
  production.
- Three assertion methods, each taking a short symbolic `site` id (e.g.
  `"ServerLevel.setBlock"`) used both as the probe key and in the log
  message:
  - `assertGlobal(site)` — current domain must be `GLOBAL`.
  - `assertRegion(site, regionId)` — current domain must be `REGION` *and*
    `OwnerToken.current().regionId()` must equal `regionId`. A region
    worker ticking the wrong region trips this just as hard as a
    non-region thread would.
  - `assertTickThread(site)` — current domain must be `REGION` or
    `GLOBAL`. Used at read-mostly sites where cross-region reads are
    tolerated but async/legacy reads are suspect.
- On failure, each bumps `ProbeRegistry.bump(site + ":" + reason)` (e.g.
  `"ServerLevel.setBlock:not-global"`, `"...:wrong-region"`,
  `"...:not-tick-thread"`) and logs via `ViolationLogger.warn(site, …)`.

**DomainAssertions is not the thing that decides whether a mutation is
safe to run.** It's a dev/CI instrument for finding domain-crossing bugs
before they ship; the M2 tick pipeline's auto-reroute path (below) is what
actually changes control flow in production. Today there is no dedicated
`DomainAssertionsTest` — coverage comes indirectly through
`OwnershipEnforcerTest`/`OwnerTokenTest` exercising the shared `OwnerToken`
plumbing both classes read.

## OwnershipEnforcer (production)

`OwnershipEnforcer` is the **active** half — the thing patched call sites
under `multiforge-patches/01-ownership/` actually call to decide whether
to run inline or hand off. Default-on, unlike `DomainAssertions`.

### Modes

```java
public enum Mode {
    OFF,      // Skip the check entirely; every call runs inline. Escape hatch for audited modpacks.
    REROUTE,  // Default. Off-owner-thread mutations are warned about and rerouted.
    STRICT,   // Off-owner-thread mutations throw OwnershipViolationException. Regression-testing only.
}
```

Selected via `-Dmultiforge.ownership.mode` (default `"reroute"`). An
unrecognized value also falls back to `REROUTE` — `parseMode` swallows the
`IllegalArgumentException` from `Mode.valueOf` and returns `Mode.REROUTE`
rather than failing boot over a typo'd system property.

### The decision: `canMutate` / `reroute`

```java
public static boolean canMutate(String site);       // may I run inline right now?
public static void reroute(String site, Runnable mutation);  // hand it off
```

`canMutate(site)`:

1. `Mode.OFF` → always `true`.
2. Current domain is `REGION` or `GLOBAL` → `true`. These are always
   legitimate mutation contexts regardless of which region/world they're
   ticking.
3. Current thread is the bound **legacy tick thread** (set once via
   `bindTickThread`, see below) → `true`. This is the pre-existing
   single-threaded main-server executor, still a legitimate mutation
   context during the transition to full regionization.
4. Otherwise: a genuine violation. Bumps
   `ProbeRegistry.bump(site + ":off-thread")`, logs via
   `ViolationLogger.warn`, and:
   - `Mode.REROUTE` → returns `false` (caller must hand off via `reroute`).
   - `Mode.STRICT` → throws `OwnershipViolationException(site, thread, domain)`.

`reroute(site, mutation)` hands `mutation` to the bound `RerouteTarget`.
Callers only call this after `canMutate` returned `false` for the same
site — never speculatively.

### Bootstrap bindings

- `bindTickThread(Thread)` — records the thread that is currently the
  legitimate single-threaded tick executor. Called once during bootstrap
  from an existing NeoForge lifecycle hook that already runs on that
  thread — **never** from a Vanilla patch site, and never from
  `MinecraftServer.runServer` itself.
- `bindRerouteTarget(RerouteTarget)` — binds where deferred mutations go.
  If never called, a misconfigured build still runs the mutation inline
  rather than crashing a mod's code path (`runInlineUnconfigured`), but
  logs loudly that this happened — consistent with CLAUDE.md rule 5
  ("never throw from a mod's code path").
- `unbindTickThreadAndRerouteTarget()` — called from
  `MultiForgeRegionizedRuntime.shutdown()` so a reroute target bound to a
  specific server executor doesn't outlive the server that captured it.
  Matters for the dedi GameTestServer, which reuses one JVM across
  successive servers — without this, the next off-thread mutation between
  server-stop and next-server-start would submit into a dead
  `MinecraftServer.execute` and throw `RejectedExecutionException`.

### M7 scope note

Until `RegionizedTaskQueue` is wired to real `ServerLevel`s, the only real
reroute target is the pre-existing single-threaded main-server executor
(bound via `bindRerouteTarget`, driven from `ServerLifecycleHooks`).
`reroute` is written against the `RerouteTarget` functional interface
specifically so a later milestone can swap that binding for a real
per-region mailbox (`RegionizedTaskQueue.queueChunkTask`) without touching
any patched call site again. See `docs/legacy-compat.md` for what this
means for mods hitting REROUTE warnings today.

### OwnershipViolationException

Thrown by `OwnershipEnforcer` **only** in `Mode.STRICT`. Never thrown in
the default `REROUTE` mode — see CLAUDE.md rule 5. Carries the call-site
id, the offending thread, and the actual domain observed, all exposed via
`site()` and `actualDomain()` accessors for test assertions.

## Rules: which domain may write what state

| Domain          | May mutate                                                                 | May read                              | Notes |
|-----------------|------------------------------------------------------------------------------|----------------------------------------|-------|
| `REGION`        | Chunks/entities/block state owned by its own region id                      | Its own region freely; cross-region reads should still go through the task queue | Never touch another region's state inline — even a read can race a concurrent merge/split. |
| `GLOBAL`        | Weather, time, world border, gamerules, ender dragon, wither, raids, scoreboards, command dispatch | Same as write scope | Runs on its own dedicated worker, ticked like any other region. |
| `ENTITY`        | Whatever the underlying region-alias may mutate                             | Same                                   | Not a separate thread pool — an affinity concept layered on `REGION`. |
| `ASYNC`         | Nothing that's live game state                                              | Immutable/pure data only               | CLAUDE.md rule 4: no blocking calls, and no mutation, ever. |
| `NETWORK`       | Nothing directly                                                            | Decode-only; handler must re-dispatch to the owning domain before mutating anything | Netty IO thread. |
| `LEGACY_SERIAL` | Whatever the bound legacy tick thread was already allowed to mutate (today: the single-threaded main-server executor) | Same | Default for unannotated event handlers and unported mod code; see `docs/legacy-compat.md`. |
| `UNKNOWN`       | Nothing, by default                                                         | Nothing guaranteed                     | Bootstrap/shutdown/untagged threads. Treated as a violation by every enforcement/assertion check unless it happens to be the bound tick thread. |

The overarching policy, per CLAUDE.md rules 4 and 5:

- **No blocking calls on a region worker thread, ever.** A `Thread.sleep`,
  a `.get()` on a `CompletableFuture`, or a `synchronized` block that
  could contend with a foreign region are all bugs regardless of which
  domain issued them.
- **Auto-reroute + warn is the default.** MultiForge never refuses to load
  a mod and never throws from a mod's code path just because it did
  something unsafe — `OwnershipEnforcer` reroutes the call and logs a
  rate-limited warning. `STRICT` mode (hard failure) exists purely for
  regression testing, never for production.

## Test coverage

- `net.multiforge.runtime.ownership.OwnershipEnforcerTest` — 11 cases:
  `offModeAlwaysAllowsInline`, `regionDomainPassesThroughEvenOffTickThread`,
  `globalDomainPassesThrough`, `boundTickThreadPassesThroughWithUnknownDomain`,
  `offThreadUnknownDomainIsViolationAndRerouteModeReturnsFalse`,
  `strictModeThrowsInsteadOfReturningFalse`,
  `strictModeNeverThrowsForTheBoundTickThread`,
  `rerouteDelegatesToBoundTarget`,
  `unconfiguredRerouteTargetRunsInlineRatherThanCrashing`,
  `unrecognizedModePropertyFallsBackToReroute`.
- `net.multiforge.runtime.ownership.OwnerTokenTest` — token
  construction, `current()`'s UNKNOWN default, and `runAs` nesting/restore
  behavior.

There is currently no dedicated `DomainAssertionsTest` — the class is
exercised indirectly wherever `OwnerToken.current()` is already under
test. Adding a focused unit test (mode-off no-op, mode-on probe-bump
per assertion method) is open work, not yet scheduled against a milestone.

## What's not built yet

- A dedicated `LEGACY_SERIAL` single-thread executor with per-mod
  isolation — today `LEGACY_SERIAL` is a domain *tag*, and the actual
  reroute target for `OwnershipEnforcer` violations is still the
  pre-existing single-threaded main-server executor. See
  `docs/legacy-compat.md`.
- `RegionizedTaskQueue` as the sole reroute target — the M7 scope note
  above still applies; the swap to a real per-region mailbox is scoped to
  a later milestone.
- Enforcement of `@DispatchDomain` at event-dispatch time (blueprint M12).
  The annotation and its four-value `DispatchDomainKind` enum are defined
  today; no listener registration path reads them yet. See
  `docs/events.md`.
