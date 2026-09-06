# MultiForge Legacy Compatibility Notes

## Executive summary

MultiForge's promise is "auto-reroute + warn, never refuse to load a mod"
(CLAUDE.md rule 5) — a mod written for the vanilla single-threaded model
should boot and run on MultiForge without modification, just not always at
full parallel speed. This page describes what actually backs that promise
**today** versus what the blueprint designs for `LEGACY_SERIAL` as a
first-class, isolated compatibility lane, which is **not yet built**. The
two halves are clearly separated below — don't take the "designed"
section as shipped behavior.

## What exists today

Three pieces of live infrastructure combine to give unported mod code a
working (if not maximally parallel) home:

1. **`Domain.LEGACY_SERIAL` / `OwnerToken.LEGACY_SERIAL`** — a domain tag
   in `net.multiforge.runtime.ownership`. See `docs/concurrency-contract.md`
   for the full `Domain` enum.
2. **`DispatchDomainKind.LEGACY_SERIAL`** — the default value for any
   event handler without a `@DispatchDomain` annotation. See
   `docs/events.md`.
3. **`OwnershipEnforcer` in `Mode.REROUTE`** (the default) — this is the
   thing that's actually *enforcing* anything today. Every patched
   `net.minecraft.*` call site under `multiforge-patches/01-ownership/`
   calls `OwnershipEnforcer.canMutate(site)` before mutating; off a
   legitimate mutation context, the call is warned about and handed to
   whatever `RerouteTarget` was bound at bootstrap.

**The reroute target bound today is the pre-existing single-threaded
main-server executor** — per the M7 scope note in `OwnershipEnforcer`'s
source: until `RegionizedTaskQueue` is wired to real `ServerLevel`s, that
single-threaded executor is the only real reroute target that exists.
Functionally, this **is** today's de facto legacy lane: unaudited mod
code that mutates state off its owning thread gets funneled back onto the
same thread Vanilla always ran everything on. It's just not yet isolated
per-mod, not yet on a dedicated `LEGACY_SERIAL`-named thread, and not yet
swappable for the real per-region mailbox without a code change (that
swap is exactly what the `RerouteTarget` interface is designed to make
painless later).

## What's designed but not yet built

The blueprint (`docs/blueprint.md` §Legacy Compatibility Lane) describes a
considerably more capable lane than what exists today:

- **A dedicated single-thread `LEGACY_SERIAL` executor**, separate from
  the main-server executor, with all legacy event callbacks pinned there
  — not sharing a thread with anything else.
- **RPC/continuation proxying** for sensitive world access from that
  executor into the owning domain, rather than the current blunt
  "reroute the whole mutation" approach.
- **A three-tier mod safety classification** — `multithreadSafety =
  LEGACY | HYBRID_SAFE | STRICT_SAFE` — read from mod manifest/annotation
  data, so the runtime can make smarter per-mod routing decisions instead
  of treating every unannotated call site identically.
- **Configurable strictness beyond today's single global knob**: the
  blueprint describes reroute+warn, reroute+rate-limited-warn, and
  hard-fail-in-strict-test-mode as independently tunable; today there's
  one `[violations].policy` knob (see Config below) that picks among a
  similar but coarser set of behaviors.

None of the above exists in `multiforge-runtime` source today — this was
confirmed by grepping for a dedicated executor class and per-mod
serialization wiring; neither turned up. Treat this section as roadmap,
not shipped capability.

## Mod patterns table

| Pattern | Works as-is? | Notes |
|---|---|---|
| `@SubscribeEvent` handler that only reads/writes the event's own target (block position, entity, chunk) | **Yes** | Rides the `LEGACY_SERIAL` default. `OwnershipEnforcer` reroutes transparently if the handler happens to run off the owning thread; correctness is preserved, just at reroute cost. |
| Handler that caches a `ServerLevel`/`BlockEntity`/entity reference across ticks and mutates it later from a different callback | **Needs porting** | Every off-owner-thread mutation gets warned + rerouted individually — works, but the per-call reroute cost adds up, and a rate-limited warn budget (`docs/global-network.md` §per-mod warn budget) can start suppressing the log noise before you notice how often it's firing. Move the hot path to `ServerDomains.region(world, pos)` or `ServerDomains.entity(ref)` so dispatch resolves ownership once per call instead of relying on reroute. |
| Handler doing a blocking `.get()`/`.join()` on a `CompletableFuture`, or a raw `Thread.sleep`, inside event-handling code | **Needs porting, unconditionally** | This violates CLAUDE.md rule 4 regardless of which domain the handler nominally runs in — `LEGACY_SERIAL` does not grant an exemption from the no-blocking-calls rule. Replace with a continuation (`ScheduledTask` callback, or a follow-up `queueChunkTask`/`ServerDomains` call). |
| Handler that mutates truly global state (weather, world border, scoreboards) | **Works as-is, but consider annotating** | Correct under `LEGACY_SERIAL` reroute, but annotating `@DispatchDomain(DispatchDomainKind.GLOBAL)` now positions the handler correctly for when M12 enforcement lands — see `docs/events.md`. |
| Mod that spins its own background thread and pokes world state directly (bypassing the event bus and `ServerDomains` entirely) | **Needs porting** | `OwnershipEnforcer` still catches the mutation (any thread without a bound token is `UNKNOWN`), but a mod-owned thread doing this pattern heavily is exactly the "abusive mod doing blocking call chains" threat the blueprint's Networking Model section calls out. Route through `ServerDomains.async()` for the background work and dispatch the actual mutation back through `region`/`entity`/`global`. |

## Config

The **real, implemented** operator-visible knob affecting reroute
behavior today is `[violations]` in `multiforge-server.toml`:

```toml
[violations]
policy = "warn"         # warn | reroute-only | fail
warnPerMin = 5
```

and the JVM property that selects `OwnershipEnforcer`'s mode:

```
-Dmultiforge.ownership.mode=reroute   # off | reroute (default) | strict
```

**`mtserver.legacy_serial_enabled` is not a real configuration key.**
This was checked directly against `MultiForgeConfig`, `ConfigCodec`, and
`MultiForgeConfigStore` in `multiforge-runtime/src/main/java/net/multiforge/runtime/config/`
— none reference a "legacy" key of any kind. If you've seen this name
referenced elsewhere (design notes, an earlier draft of this document,
etc.), treat it as aspirational until a config codec change actually adds
it. The closest real lever an operator has today for controlling
legacy-code behavior is `-Dmultiforge.ownership.mode`, which affects
**all** off-owner-thread mutations, not specifically ones from unannotated
event handlers.

## Migration path for mods hitting REROUTE warnings

1. **Read the warning.** `ViolationLogger.warn(site, message)` logs the
   symbolic call-site id, the actual domain observed, and the offending
   thread name — e.g. `"off-thread mutation attempt from
   'legacy-executor-1' (domain=LEGACY_SERIAL)"`. The `site` string maps
   directly to the patched call site in `multiforge-patches/01-ownership/`
   (e.g. `"Level.setBlock"`), so you can usually tell exactly which
   Vanilla API your mod called.
2. **Find your call site.** Match the warning back to the line in your
   mod that triggered it. It's almost always either an event handler
   doing delayed/cached work, or a background thread the mod spun up
   itself.
3. **Pick the right domain.** Chunk/entity/block-scoped work → `REGION`
   (via `ServerDomains.region(world, pos)`) or `ENTITY` (via
   `ServerDomains.entity(ref)`); truly global state → `GLOBAL`; pure
   computation with no game-state touch → `ASYNC`. See
   `docs/concurrency-contract.md` §Rules for the full per-domain
   read/write table.
4. **Rewrite the call through `ServerDomains`** (or the Folia-shaped
   mirror in `net.multiforge.api.folia`, if you're porting a Folia
   plugin) instead of relying on the reroute path to paper over an
   off-thread mutation. See `docs/scheduler-api.md` §Recipes for the
   common shapes.
5. **Regression-test with STRICT before shipping.** Run a throwaway/dev
   server with `-Dmultiforge.ownership.mode=strict` and exercise the
   changed code path. `STRICT` throws `OwnershipViolationException`
   instead of silently rerouting — exactly what you want to catch a
   remaining violation during testing. Per the mode's own javadoc, this
   is **regression-testing only**; never run production servers in
   `STRICT` mode, since a single missed edge case would crash the mod's
   code path instead of degrading gracefully.
6. **Confirm the warning is gone** under normal `REROUTE` mode, and that
   `ProbeRegistry` counters for that `site` have stopped incrementing
   (exposed via the operator diagnostics surface — see
   `docs/operator-handbook.md`).
