# NeoForge Multithreaded Server (Folia-Inspired) — Comprehensive Architecture & Implementation Blueprint

**Direct answer:** A Folia-style multithreaded NeoForge server is feasible only as a **new execution model** with strict ownership semantics, domain-aware APIs, and a robust legacy compatibility lane.  
It cannot safely run “any mod” at full parallel speed without isolation/serialization for legacy behavior.

---

## Table of Contents

1. [Executive Summary](#executive-summary)  
2. [Goals, Non-Goals, and Constraints](#goals-non-goals-and-constraints)  
3. [Terminology and Mental Model](#terminology-and-mental-model)  
4. [Current NeoForge Reality vs Target Model](#current-neoforge-reality-vs-target-model)  
5. [System Architecture Overview](#system-architecture-overview)  
6. [Concurrency Contract (The New Law)](#concurrency-contract-the-new-law)  
7. [Regionization Model](#regionization-model)  
8. [Tick Pipeline and Scheduling](#tick-pipeline-and-scheduling)  
9. [Cross-Region Communication Protocols](#cross-region-communication-protocols)  
10. [Entity Ownership and Migration](#entity-ownership-and-migration)  
11. [Event Bus Redesign for Multithreading](#event-bus-redesign-for-multithreading)  
12. [Registries, Capabilities, Attachments, and Shared State](#registries-capabilities-attachments-and-shared-state)  
13. [Task Scheduling API Redesign](#task-scheduling-api-redesign)  
14. [Compatibility with “Any NeoForge Mod”](#compatibility-with-any-neoforge-mod)  
15. [Legacy Compatibility Lane (Serialized Facade)](#legacy-compatibility-lane-serialized-facade)  
16. [Networking Model](#networking-model)  
17. [Persistence, Autosave, and Crash Safety](#ersistence-autosave-and-crash-safety)  
18. [Determinism, Testing, and Debuggability](#determinism-testing-and-debuggability)  
19. [Security and Failure Containment](#security-and-failure-containment)  
20. [Performance Model and Expected Gains](#performance-model-and-expected-gains)  
21. [Implementation Plan (Milestones)](#implementation-plan-milestones)  
22. [Detailed Engineering Work Breakdown](#detailed-engineering-work-breakdown)  
23. [API Surface Proposal](#api-surface-proposal)  
24. [Compatibility Classification for Mods](#compatibility-classification-for-mods)  
25. [Operational Modes and Server Config](#operational-modes-and-server-config)  
26. [Migration Guidance for Mod Authors](#migration-guidance-for-mod-authors)  
27. [Risk Register and Mitigations](#risk-register-and-mitigations)  
28. [Acceptance Criteria and Exit Gates](#acceptance-criteria-and-exit-gates)  
29. [Example Runtime Flows](#example-runtime-flows)  
30. [Recommended Docs to Publish](#recommended-docs-to-publish)  
31. [Final Recommendation](#final-recommendation)  

---

## Executive Summary

A NeoForge multithreaded server should be designed as a **hybrid execution platform**:

- **Parallel region execution** for engine subsystems and thread-safe mods.
- **Serialized compatibility execution** for legacy mods that rely on classic single-thread assumptions.

This avoids ecosystem collapse while allowing meaningful scalability. The main architectural pivot is replacing “main-thread safety” with **ownership safety**:

- World/chunk/entity mutation is legal only in the owner domain.
- Cross-domain interactions use messaging, continuations, or controlled coordination.
- Event dispatch, schedulers, capabilities, and networking must all be domain-aware.

---

## Goals, Non-Goals, and Constraints

## Goals
- Increase server throughput and reduce tick lag in spread-out workloads.
- Preserve broad modpack bootability.
- Provide deterministic, diagnosable behavior under concurrency.
- Offer clear migration path for mod authors.

## Non-Goals
- Full drop-in behavioral identity for all existing mods without adaptation.
- Immediate parallelization of every global system.
- Zero overhead compatibility mode.

## Constraints
- NeoForge ecosystem heavily depends on event ordering and thread affinity assumptions.
- Many mods use shared mutable singleton state.
- Mixed environments (safe + unsafe mods) must remain operable.

---

## Terminology and Mental Model

- **Domain**: execution ownership context (`REGION`, `ENTITY`, `GLOBAL`, `ASYNC`, `LEGACY_SERIAL`).
- **Owner thread**: currently authorized executor for a mutable object.
- **Region**: spatial simulation unit containing chunk groups and associated entities.
- **Mailbox**: per-region queue for inbound cross-region operations.
- **Epoch**: global tick index used for coherence/ordering.
- **Compat lane**: serialized execution environment emulating legacy behavior.

---

## Current NeoForge Reality vs Target Model

## Current
- Gameplay mutation mostly assumes one authoritative server thread.
- Async exists for IO and specific subsystems, not general world mutation.
- Event handlers and mod code often assume global order.

## Target
- Multiple region executors run simulation in parallel.
- Most mutable objects are protected by ownership checks.
- Event system advertises dispatch domain/ordering contracts.
- Legacy mods run through serialized compatibility lane.

---

## System Architecture Overview

Core components:

1. **Region Manager**
   - region graph, partitioning, merge/split logic.
2. **Ownership Registry**
   - maps objects (chunks/entities/block entities) to owner domain.
3. **Tick Orchestrator**
   - executes per-epoch region pipeline + global barriers.
4. **Domain Scheduler Suite**
   - region/entity/global/async/legacy schedulers.
5. **Cross-Domain Message Bus**
   - request/response, fire-and-forget, bounded mailboxes.
6. **Compatibility Runtime**
   - serialized facade + proxying for unsafe access.
7. **Diagnostics & Enforcement**
   - thread checks, race traps, trace IDs, deterministic mode.

---

## Concurrency Contract (The New Law)

Every mutable API operation must declare one of:

- **Owner-required** (must run in object owner domain).
- **Global-only** (must run in global domain).
- **Async-safe** (pure/immutable/side-effect constrained).

Violations trigger:
- dev mode: hard fail + stacktrace + owner metadata.
- prod hybrid mode: reroute via safe proxy (configurable).

No silent unsafe access.

---

## Regionization Model

## Partitioning
- Start with fixed chunk tile size (e.g., 8x8 or 16x16).
- Maintain adjacency graph and interaction counters.
- Auto-merge hotspots with high cross-boundary traffic.
- Auto-split cold oversized regions.

## Region metadata
- region id
- owned chunk set
- entity set/index
- inbox/outbox queues
- performance counters (tick cost, queue depth)
- last active tick, thermal score

## Thermal policy
- **Hot regions**: stable ownership, avoid churn.
- **Cold regions**: candidates for split/park.
- **Boundary pressure**: merge if inter-region chatter exceeds threshold.

---

## Tick Pipeline and Scheduling

Per epoch `T`:

1. **Build runnable set**
2. **Dispatch region tasks** to worker pool
3. **Execute deterministic region phases**
4. **Run global phase/barriers**
5. **Commit state and metrics**
6. **Advance to epoch `T+1`**

## Region local phase ordering
1. apply inbound mailbox
2. scheduled block/fluid updates
3. entity AI/physics
4. block entities
5. region events/tasks
6. flush outbound

This preserves stable local determinism.

## Worker model
- fixed-size core pool + work-stealing
- NUMA-aware pinning (optional advanced)
- backpressure when global barriers dominate

---

## Cross-Region Communication Protocols

Three primitives:

1. **Cast (one-way)**  
   enqueue operation to owner region
2. **Call (request/response)**  
   returns future/continuation
3. **Txn (coordinated)**  
   rare, controlled multi-owner atomic workflow

## Requirements
- bounded queues
- timeout/cancellation semantics
- idempotency metadata for retry paths
- correlation IDs for tracing

## Anti-pattern
- blocking waits on region thread (deadlock/jitter risk)

---

## Entity Ownership and Migration

## Rules
- exactly one owner at a time
- all mutations owner-bound
- references use indirection table (`EntityRef -> current owner`)

## Migration protocol
1. mark entity `MIGRATING`
2. snapshot authoritative mutable state
3. transfer capsule to new owner inbox
4. new owner attach + publish mapping
5. old owner finalize detach

## Guarantees
- no dual-writer windows
- consistent UUID/global lookup
- deferred events rebased to new owner

---

## Event Bus Redesign for Multithreading

Each event type declares:

- `dispatch_domain`: REGION | GLOBAL | LEGACY_SERIAL
- `ordering_contract`: PER_REGION | GLOBAL_TOTAL | BEST_EFFORT
- `thread_affinity_notes`

## Dispatch behavior
- Region events execute on owner region.
- Global events execute on global scheduler.
- Legacy events serialized for compatibility mods.

## Listener registration extensions
- listener declares safe domains supported
- optional strict validation at mod load

---

## Registries, Capabilities, Attachments, and Shared State

## Registries
- mutable during bootstrap phases only
- immutable runtime views after freeze

## Capabilities/attachments
- entity/chunk/block-entity attached data => owner-bound
- global attachments => concurrent container + consistency doc

## Shared state patterns
Preferred:
- immutable snapshots
- actor/message queues
- concurrent maps with explicit update discipline
Avoid:
- unsynchronized mutable singletons
- incidental global caches with object mutation

---

## Task Scheduling API Redesign

Replace ambiguous “sync task” with domain-explicit APIs:

- `runRegion(level, chunkPos, Runnable)`
- `runEntity(entityId, Runnable)`
- `runGlobal(Runnable)`
- `runAsync(Supplier<T>)`
- `thenRunRegion(...)`, `thenRunEntity(...)`

## Contracts
- scheduled tasks inherit trace context
- cancellation tokens required for long pipelines
- future continuation must not capture invalidated mutable refs

---

## Compatibility with Any NeoForge Mod

You can approach “any mod runs” only with **hybrid execution**:

- legacy mods in serialized lane
- safe mods in parallel lane
- cross-lane proxy boundaries

Result:
- high boot compatibility,
- mixed runtime performance,
- some behavior/timing differences possible.

---

## Legacy Compatibility Lane (Serialized Facade)

## Purpose
Preserve main-thread assumptions for unported mods.

## Mechanics
- dedicated single-thread executor (`LEGACY_SERIAL`)
- all legacy event callbacks run there
- sensitive world access proxied to owner domain via RPC/continuations
- configurable strictness:
  - reroute + warn
  - reroute + rate-limit warnings
  - hard-fail (strict test mode)

## Cost
- potential bottleneck under heavy legacy mod activity
- marshalling overhead
- partial reduction of parallel gains

---

## Networking Model

- packet decode can remain async
- gameplay-affecting handlers must enqueue to owner/global domain
- outbound responses sent from safe context or via domain-safe sender proxy
- per-player ownership usually follows current region/domain

## Threats
- packet storms amplifying mailbox pressure
- abusive mods doing blocking call chains in packet handlers

Mitigate via quotas, bounded mailboxes, and latency watchdogs.

---

## Persistence, Autosave, and Crash Safety

## Save model
- per-region dirty tracking
- snapshot buffers at safe points
- async disk flush coordinator
- epoch-consistent savepoint metadata

## Crash safety
- write-ahead journal for in-flight critical changes
- recovery replays committed journal segments
- bounded flush windows to prevent unbounded memory growth

---

## Determinism, Testing, and Debuggability

You need a first-class debugging stack:

1. owner assertion framework
2. deterministic scheduler mode (fixed ordering seed)
3. event/message trace graph
4. race violation telemetry
5. replay harness for reported crashes/desyncs

## Test matrix
- single-thread baseline parity
- hybrid mode with mixed mods
- strict parallel mode with safe test mods
- randomized stress for border migrations

---

## Security and Failure Containment

## Safety objectives
- prevent unauthorized cross-domain mutation
- avoid deadlocks in multi-domain operations
- bound queues and memory
- isolate misbehaving legacy mods from collapsing server loop

## Controls
- domain ACL checks
- timeout guards
- watchdog for blocked executors
- circuit-breakers on noisy mods/subsystems

---

## Performance Model and Expected Gains

## Gains likely when
- players geographically distributed
- multiple independent farms/bases
- low cross-region coupling

## Limited gains when
- single mega-base hotspot
- many global-state-heavy mods
- large proportion of logic in legacy serial lane

## Key metrics
- TPS p50/p95
- tick jitter percentiles
- region queue depth
- global barrier stall time
- % work in legacy lane
- migration churn rate

---

## Implementation Plan (Milestones)

## M0 — Instrumentation First
- add owner-check framework (disabled by default)
- telemetry for unsafe access hotspots
- baseline perf/profile capture

## M1 — Core Domain/Scheduler Scaffolding
- introduce domain schedulers
- add API prototypes
- no major behavior changes yet

## M2 — Region Tick MVP
- region manager + partitioning
- region tick phases for core world loops
- basic cross-region mailbox

## M3 — Compatibility Lane
- legacy serialized executor
- event virtualization
- proxy/reroute world access

## M4 — Entity Migration + Border Correctness
- robust transfer protocol
- conflict/tie-break policies
- teleport/cross-dimension correctness

## M5 — Subsystem Expansion
- progressively parallelize safe global-ish systems
- keep high-risk systems serialized until proven

## M6 — Tooling + Ecosystem Rollout
- mod safety scanner
- docs, warnings, certifications
- operational hardening

## M7 — Ownership Enforcement in Real Source
- wire OwnerToken/ViolationLogger/reroute logic (multiforge-runtime) into real
  net.minecraft.* mutation sites via multiforge-patches/01-ownership/
- connective tissue between M0/M1 scaffolding (already built, pure-Java) and
  M2's region-tick MVP
- exit gate: 01-ownership patches apply cleanly (via the new
  `applyMultiforgePatches` Gradle task) against a fresh `:setup` inside
  `upstream/neoforge-1.21.1`; full fork `./gradlew build` succeeds with
  patches applied; NeoForge GameTest fixture (see
  `upstream/neoforge-1.21.1/tests/src/main/java/net/multiforge/testfixtures/`)
  proves off-thread mutation is detected, warn-logged, rerouted, and
  eventually applied without crashing the tick loop; `multiforge-bench`
  determinism harness (`WorldDiff` — pure-Java file diff, tolerates
  known-nondeterministic paths) is available for the manual byte-identical
  world-save comparison against a baseline unpatched fork on a fixed seed.
  Automating that comparison end-to-end (server launch + baseline vs.
  patched run + diff) is future work per the harness's own scope note —
  M8 will need to replace byte-identical comparison with a
  canonicalized/semantic NBT diff anyway once real parallel scheduling
  makes chunk-save order legitimately non-deterministic.

## M8 — Region Tick Loop
Broken into 8 landable sub-steps, sized similarly to M7's sub-steps:
- **Sub-step 1 (DONE):** `RegionizedData<T>` pure-Java slot API +
  `RegionListener` callback interface, wired into `ThreadedRegionizer`
  so subsystems can fold/peel per-region state atomically with
  merge/split.
- **Sub-step 2 (DONE):** `PhasedRegionTickBody` — 6-phase composition
  (INBOUND_MAILBOX, BLOCK_FLUID_TICKS, ENTITY_AI, BLOCK_ENTITIES,
  REGION_EVENTS, FLUSH_OUTBOUND) matching blueprint.md §"Region local
  phase ordering". Auto-wires `TickRegionScheduler` and
  `RegionizedTaskQueue` as `RegionListener`s in
  `MultiThreadedSchedulerHost.regionizerFor(...)` so region death
  automatically deregisters and merges move inboxes.
- **Sub-step 3 (DONE):** `MultiForgeRegionizedRuntime` static holder
  the fork patches call at bootstrap and shutdown; `ServerDomains`
  gains `uninstall()` so shutdown properly unbinds the API-side host.
- **Sub-step 4 (DONE):** fork's `ServerLifecycleHooks` calls
  `MultiForgeRegionizedRuntime.install(defaults, no-op body)` at
  `handleServerAboutToStart` and `.shutdown()` at
  `handleServerStopped`. GameTest fixture at
  `net/multiforge/testfixtures/RegionizedRuntimeTests.java` asserts
  the host is reachable during real server runtime.
- **Sub-step 5 (DONE, facade-first):** patched vanilla
  `MinecraftServer.tickChildren` routes each `serverlevel.tick(p)` call
  through a new fork-local `RegionizedTickCoordinator` facade
  (`upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/`),
  mirroring the OwnershipGuard pattern M7 established. Currently a
  pass-through — no behavior change — so the vanilla patch can stay
  stable while future sub-steps swap the facade to real per-region
  dispatch. Second file in `multiforge-patches/02-region-tick/`.
- **Sub-step 6a (DONE):** `ChunkEvent.Load`/`ChunkEvent.Unload`
  handlers auto-register/deregister loaded chunks with the world's
  regionizer via new `registerChunk`/`unregisterChunk` primitives on
  `MultiThreadedSchedulerHost` — deliberately without dropping a
  keep-loaded ticket (Vanilla's own tickets already keep the chunk
  loaded; adding one would leak). Region workers now have real regions
  to tick against, though the body remains no-op from sub-step 4 until
  6b/c wire real per-region tick work. GameTest fixture at
  `RegionizedRuntimeTests.loadedChunksHaveRegions` asserts a region
  exists for the GameTest's own structure chunk.
- **Sub-step 6b (PARTIAL — see docs/design/m13-b3-region-tick.md — M9
  Phase 1.5, `ab5c185`):** swapped `RegionizedTickCoordinator.
  dispatchLevelTick` from pass-through to real per-region dispatch of
  the *scheduling* barrier — `TickRegionScheduler.tickAll(...)` fans
  out to every live region and synchronises against the worker pool
  before the per-level tick proceeds. It did **not** decompose
  `ServerLevel.tick`'s per-chunk work itself: block/fluid ticks,
  entity iteration, and block-entity iteration all still run inline
  on the main thread via the trailing `vanillaBody.run()` call
  (`RegionizedTickCoordinator.java:193`), because
  `MultiThreadedSchedulerHost#installM9WiredTickBody`'s
  `BLOCK_FLUID_TICKS` and `ENTITY_AI` phase slots have no production
  wiring, and `BLOCK_ENTITIES` is wired only for the synthetic global
  region. Completing the actual per-chunk decomposition — per-region
  `BLOCK_FLUID_TICKS`, `ENTITY_AI`, and per-region `BLOCK_ENTITIES` —
  is tracked as "Full B3" and specified in
  `docs/design/m13-b3-region-tick.md`.
- Sub-step 7 (pending, `multiforge-patches/03-world-data/`): convert
  per-world mutable `ServerLevel` fields (block-tick list, fluid-tick
  list, block-event queue, entity iterator caches) to `RegionizedData<T>`
  slots.
- **Sub-step 8a (DONE):** `RegionTickWatchdog` — records per-region
  tick duration via `TickRegionScheduler.runWorker`'s enter/exit
  hooks; when duration exceeds the threshold
  (`-Dmultiforge.watchdog.warn-ms=500` default) fires a rate-limited
  warn + bumps `ProbeRegistry` key `region-tick.overrun` for CI hard
  regression prevention. `-Dmultiforge.regiontick.strict=on` upgrades
  the warn to a thrown `RegionTickOverrunException`, used by the
  regression harness. Catches blocking-wait violations that would
  otherwise silently degrade tick throughput, and gives operators a
  first-class signal that a region is too hot to fit in one tick and
  should split.
- Sub-step 8b (pending): upgrade `WorldDiff` (multiforge-bench) to
  canonicalized/semantic NBT diff so parallel-scheduled chunk save
  order stays testable once sub-step 6b/c actually parallelizes work.

Exit gate for the milestone: deterministic-mode regression on real
region-tick dispatch; no blocking calls on a region worker thread
(verified via strict-mode regression run).

### Known deferred races (documented, unfixed)

Four rounds of /67 review surfaced concurrency bugs that admit no
correct local fix without larger design work. Each is documented
inline in the affected class's javadoc; enumerated here for
future-session visibility. Nearly all have since landed real fixes
during M9 Phase 1 (see "Resolved in M9 Phase 1" below); the remaining
open item is the sole `checkOnly` semantics entry.

- ~~**Merge-during-tick race**~~ FIXED (commit `fb8e99e`, M9 Phase 1
  task 1.1) — `RegionizedData.onRegionsMerging` /
  `ThreadedRegionizer.mergeInto` now quiesce the surviving region via
  a FOLDING state on the write path before firing merge listeners,
  matching Folia's ThreadedRegionizer shape.
- ~~**`queueChunkTask` merge race**~~ FIXED (commit `daf3d3e`, M9
  Phase 1 task 1.2) — producer now takes the regionizer's read lock
  around the `ownerLookup → inboxFor(owner).add(task)` enqueue, so a
  concurrent merge cannot drop the task via `inboxes.remove(dying)`.
- ~~**`WorldGenLevel`-guard bypass**~~ FIXED — new discriminator
  `WorldGenLevel && !(this instanceof ServerLevel)` correctly bypasses
  only WorldGenRegion (verified as the only non-ServerLevel
  WorldGenLevel implementer in the vanilla source).
- **`checkOnly` semantics for return-value patches** (attempted for
  Level.setBlock / ServerLevel.addFreshEntity): trading dishonest
  sentinel return for real concurrent corruption is not net-safer.
  Currently patches keep the reroute+return-false shape from M7 —
  callers see false (dishonest, but callers branching on it don't
  cause races), rerouted mutation runs later on the main executor.
  Proper fix requires a synchronous-round-trip mechanism that does
  not block on a region worker thread.
- ~~**`WorldDiff` MCA location table not stripped**~~ FIXED —
  canonical per-slot MCA hash now walks the location table, reads
  each chunk's own declared length, and hashes payloads in fixed
  slot-id order. Stable across timestamp + sector-reorder variation;
  still catches payload and presence differences. Supersedes the
  prior byte-range stripping.
- ~~**`OwnershipEnforcer.unbindTickThreadAndRerouteTarget` race**~~
  FIXED (via commit 97ef3f9) — wrapped `server::execute` bind in a
  lambda that catches `RejectedExecutionException` and logs a
  rate-limited warn. The fundamental race (cached local reference
  after volatile swap) is unclosable via any swap protocol, but
  making the target no-throw makes the race benign.
- ~~**`splitIfDisconnected` publishes empty section→region mapping
  mid-split**~~ FIXED (commit `3eb777b`, M9 Phase 1 batch 1) —
  `ThreadedRegionizer.splitIfDisconnected` now stages new regions
  off-map and swaps them into `sectionToRegion` atomically under the
  write lock, closing the empty-window that let a concurrent
  `regionAtChunk` see a spurious `null` and drop an observation.
- ~~**`ServerLifecycleHooks` swallows `IllegalStateException` on
  install failure**~~ FIXED (commit `3eb777b`, M9 Phase 1 batch 1) —
  the two `IllegalStateException` sources are now discriminated by a
  dedicated subclass so the hooks swallow only the "same instance
  installed" case; a rogue ServiceLoader-bound host is now logged and
  rethrown instead of silently disabling regionization forever.

### Resolved in M9 Phase 1

M9 Phase 1 (commits `3eb777b`, `7d06465`, `82c8857`, `fb8e99e`,
`daf3d3e`, `ab5c185`) closed the load-bearing scheduler races that had
been documented-and-deferred through M8:

- `3eb777b` — Phase 1 batch 1: `splitIfDisconnected` empty-window
  race; `ServerLifecycleHooks` `IllegalStateException` dedup;
  `ChunkHolderManagerBridge` no-world-regionizer path;
  `TicketExpiryTicker` scheduling.
- `7d06465` — Phase 1 batch 2: `ChunkTaskScheduler.onRegionSplit`
  peel semantics (correct per-region ticket handoff on split).
- `82c8857` — Phase 1 batch 3: `DistanceManager` shadow bridge for
  forced-chunk and player tickets (they now flow through the
  MultiForge ticket map).
- `fb8e99e` — Phase 1 task 1.1: `RegionizedData` merger race —
  FOLDING quiescence on the write path.
- `daf3d3e` — Phase 1 task 1.2: `queueChunkTask` merge race — read
  lock around the enqueue.
- `ab5c185` — Phase 1 task 1.5: M8 sub-step 6b real per-region
  dispatch (`RegionizedTickCoordinator.dispatchLevelTick`).

## M9 — Chunk System Port
- Moonrise-equivalent port: binds already-built
  NewChunkHolder/ChunkHolderManager to real
  ChunkMap/DistanceManager/ServerChunkCache
  (multiforge-patches/04-chunk-system/)
- largest and riskiest patch group; own milestone(s), sequenced as multiple
  landable sub-steps like M7
- exit gate: chunk loading/unloading/ticket lifecycle
  deterministic-regression-verified region-by-region

### Status: Landed (pending Phase 7 verification runs)

M9 code has landed on `develop` across Phases 0–6. Structural exit-gate
work is complete; the milestone closes once the Phase 7 wall-clock
verification runs (below) are executed and their outputs archived.

Key commits by phase:

- **Phase 1 — deferred-race fixes:** `3eb777b`, `7d06465`, `82c8857`,
  `fb8e99e`, `daf3d3e`, `ab5c185` (see "Resolved in M9 Phase 1"
  above).
- **Phase 2 — NewChunkHolder full state:** `d8b761a`.
- **Phase 3 — MCA I/O:** `e96f7cf`, `743759d`, `ff412bc`, `1925c5f`,
  `089940d`, `63efccd`, `6fab940`.
- **Phase 4.1 — MultiForgeChunkMap:** `128d248`, `11472c8`.
- **Phase 4.2 — MultiForgeDistanceManager:** `322a71c`, `2078327`.
- **Phase 4.4 — ChunkHolder shadow attachment:** `593b62f`.
- **Phase 4.5 — MultiForgeLightEngine:** `f129970`, `c555837`.
- **Phase 4.6 — ChunkTaskPriorityQueueSorter replacement:** `567e242`.
- **Phase 4.8 — Ticket schema:** `9fa7e9e`.
- **Phase 4 remaining (delegation patches + tests):** `bdd3c2c`,
  `7d11a9b`, `bba1cc4`, `c725a7c`, `b9a70e4`, `8109609`, `617483d`,
  `a3274ce`, `13943e5`.
- **Phase 5 wave A** (tick body wiring, journal lifecycle, shutdown
  coordinator): `66df523`.
- **Phase 5 wave B** (real ticket writes, bridge deletion,
  integration test): `c8bef8a`.
- **Phase 6A — server-side audit:** `1f4c7b8`.
- **Phase 6B — client + events audit:** `3dbc79b`.
- **Phase 8 — conventions + PR template:** `43cd791`.

### Phase 7 pending — verification runbook (user-triggered)

Runbook items, not committed test output. Each is expected to be run
manually against a real workload on the target host, its output
archived under `docs/verification/m9/`, and cross-referenced from the
milestone-close PR body.

1. **Deterministic-mode regression, single worker** — fixed seed, 1
   worker, 20 min; assert byte-identical world save vs upstream
   NeoForge. Command: `./gradlew :multiforge-bench:determinism`.
2. **Deterministic-mode regression, N=cores/2 workers** — same seed,
   parallel dispatch; assert semantic-NBT parity (via M8 sub-step 8b
   `WorldDiff` upgrade in Phase 7.1).
3. **Bench harness — headless swarm** — 20 / 100 / 500 player bot
   swarms; capture TPS p50/p95 baseline for M9 exit numbers. Command:
   `./gradlew :multiforge-bench:atm10`.
4. **Strict-mode watchdog** — 30 min under bench load with
   `-Dmultiforge.regiontick.strict=on`; assert zero
   `RegionTickOverrunException` and zero rate-limited warns on
   `region-tick.overrun`.

Only after all four runs are green does the milestone close and
`develop` merge into `main` per the release-branch discipline in
CLAUDE.md.

### M9 sub-step 1 (DONE)

- **Read-only ChunkMap → ChunkHolderManager shadow bridge**
  (`multiforge-patches/04-chunk-system/net/minecraft/server/level/ChunkMap.java.patch`).
  Single-line patch in `ChunkMap.updateChunkScheduling`, co-located
  with NeoForge's existing `fireChunkTicketLevelUpdated` event hook,
  calls `net.multiforge.neoforge.ChunkHolderManagerBridge.onTicketLevelUpdated`.
  Bridge translates Vanilla coordinates + level to MultiForge equivalents
  and populates `ChunkHolderManager` (which was already unit-tested but
  had no Vanilla feed). Zero behavior change to Vanilla ticket-level
  transitions.

  Deliverables:
  - `net.multiforge.neoforge.ChunkHolderManagerBridge` — fork facade
  - `multiforge-patches/04-chunk-system/ChunkMap.java.patch` — 6-line
    hunk right after the NeoForge event hook
  - `/multiforge chunks <world>` command dumps per-level holder counts
    from the shadow
  - GameTest `chunkBridgeShadowsRealChunks` asserts the shadow has
    ≥1 holder at ≥BORDER level after the test's own chunks load

  Scope note: this is deliberately the smallest foundational M9 slice.
  Real M9 requires REPLACING (not shadowing) Vanilla ChunkMap /
  DistanceManager / ServerChunkCache with the per-region model —
  months of work, ~30K lines in Folia's Moonrise. This bridge
  establishes the coordinate + level translation infrastructure that
  future substantive M9 sub-steps will build on.

### M9 sub-step 1 hardening (DONE — /67 round-4)

Six-agent /67 review across the M8+M9 landing surface (commits
e3878b3..54b4ad7) surfaced 27 raw findings; 13 real after dedup, 10
of them fixed in commit ee136a7:

- **Central M9 correctness bug** (4-agent convergence): pre-fix
  `ChunkHolderManager` and `ChunkTaskScheduler` defined
  `onRegionMerged(RegionId, RegionId)` / `onRegionSplit(RegionId,
  RegionId, Predicate)` methods that never fired — the
  `RegionListener` interface requires `Region`-typed signatures. Fix:
  both classes now `implements RegionListener` with the correct
  signatures (existing methods retained as internal delegates); wired
  into `MultiThreadedSchedulerHost.regionizerFor` alongside the
  existing scheduler + taskQueue listeners. Every merge/split now
  correctly folds per-region tickets and holder ownership.
- **Bridge drops pre-Load transitions** (3-agent convergence):
  Vanilla's initial `updateChunkScheduling` fires BEFORE
  `ChunkEvent.Load` creates the region. Fix: seed shadow holder at
  BORDER on Load via new `ChunkHolderManagerBridge.onChunkLoaded`.
- **Bridge leaks holders forever**: no cleanup on INACCESSIBLE
  transitions or unload. Fix: bridge drops holder when
  `newLevel > 33`; new `onChunkUnloaded` handler symmetric with
  Load.
- **O(N × K) per-boot reroute** (2-agent convergence):
  `RegionizedTaskQueue.orphaned` was a flat queue scanned in full on
  every chunk load. Fix: partitioned into
  `ConcurrentMap<OrphanBucket, Queue>` keyed by section, new
  `rerouteAtChunk(world, x, z)` touches only the matching bucket.
- **Always-green watchdog test** (2-agent convergence):
  `exitAfterThrowClearsStateWithoutFiring` passed regardless of
  behavior under `warnMs=0`. Split into two proper phase assertions.
- **`RegionizedData` threw in production**: violated CLAUDE.md rule 5.
  Fix: gate throw on `DomainAssertions.enabled()`, degrade to
  warn+probe otherwise.
- **Auto-creating lookups leaked maps on typos**: added
  `chunkManagerForOrNull` and wired into observability paths.
- **`PhasedRegionTickBody` had no per-phase exception isolation**:
  fixed with try/catch inside phase iteration.
- **`ServerDomains.uninstall` was public without `@ApiStatus.Internal`**:
  annotated.
- **Test-quality fixes**: `mcaCorruptSectorOffsetDoesNotCrash` used
  byte pattern that didn't trigger the wrap it guarded against;
  `shutdownUnbindsOwnershipEnforcerBindings` only asserted the
  tick-thread half of the unbind.

~~Deferred (require load-bearing scheduler changes): `RegionizedData`
merger race, `queueChunkTask` merge race, `splitIfDisconnected` empty
window, `ServerLifecycleHooks` `IllegalStateException` dedup~~ — all
four landed real fixes in M9 Phase 1; see "Resolved in M9 Phase 1"
above.

## M10 — Entity Migration + Networking
- Entity#teleportAsync binds to EntityMigrationCoordinator
  (multiforge-patches/05-entity-migration/); gameplay packet handlers in
  ServerGamePacketListenerImpl hop to sender's owner region via
  RegionizedTaskQueue (multiforge-patches/06-networking/)
- exit gate: cross-region entity migration correctness test (no dual-writer
  windows, no lost UUID references); packet-handler region-hop regression

## M11 — Persistence + Globals
- wires AutoSaveRunner/RegionJournal into the chunk pipeline
  (multiforge-patches/07-persistence/); moves
  weather/time/border/dragon/wither/raids/scoreboards/command dispatch to a
  dedicated global-region tick (multiforge-patches/08-globals/)
- exit gate: crash-safety replay test (WAL journal recovery); global-tick
  systems verified to not require per-region ownership

## M12 — Event Routing
- IEventBus.post honors @DispatchDomain annotations (already defined in
  multiforge-api/, currently ignored at post time)
  (multiforge-patches/09-events/)
- exit gate: event dispatch domain/ordering contract regression
  (REGION/GLOBAL/LEGACY_SERIAL routing verified per-listener)

Each milestone from M7 onward touching net.minecraft.* repeats the same
discipline: deterministic-mode regression before landing, patches grouped
per-directory (multiforge-patches/<NN-name>/) for rebase scoping, and
auto-reroute+warn as the default with strict-mode only behind an explicit
boot flag.

---

## Detailed Engineering Work Breakdown

## A. Kernel & Runtime
- Domain enum/types
- Owner token propagation
- Fast thread-owner checks (hot-path optimized)
- Trace context propagation

## B. Region Engine
- chunk-to-region mapping
- merge/split heuristics
- hot/cold detection
- region lifecycle state machine

## C. Scheduler Layer
- region scheduler
- entity scheduler
- global scheduler
- compat scheduler
- async bridge utilities

## D. Event System
- event metadata annotations
- dispatcher routing table
- listener compatibility flags
- ordering policy enforcement

## E. Compat Runtime
- mod classification loader
- serialized callback execution
- unsafe API interception and reroute
- warning/error policy framework

## F. Data & State
- attachment access guards
- concurrent-safe global stores
- immutable snapshot helpers

## G. IO/Save
- region dirty set tracking
- journal and snapshot pipeline
- save coordinator and backpressure

## H. Tooling
- debug UI/commands for region ownership
- violation reports
- deterministic replay mode
- profiling dashboards

---

## API Surface Proposal

Example conceptual API (names illustrative):

- `ServerDomains.region(level, chunkPos).execute(task)`
- `ServerDomains.entity(entity).execute(task)`
- `ServerDomains.global().execute(task)`
- `ServerDomains.async().supply(work).thenRegion(level, chunkPos, cont)`

Event annotations:
- `@DispatchDomain(REGION)`
- `@Ordering(PER_REGION)`

Mod manifest hint:
- `multithreadSafety = LEGACY | HYBRID_SAFE | STRICT_SAFE`

---

## Compatibility Classification for Mods

## Levels
1. **LEGACY**
   - runs in serialized lane only
2. **HYBRID_SAFE**
   - mostly safe, specific callbacks pinned to legacy/global
3. **STRICT_SAFE**
   - full domain-aware operation, parallel eligible

## Classification inputs
- manifest declaration
- static bytecode heuristics
- runtime violation sampling
- optional certification test suite

---

## Operational Modes and Server Config

- `mtserver=off`  
  classic behavior
- `mtserver=hybrid`  
  default recommended; any-mod viability
- `mtserver=strict`  
  maximum performance, safe mods only

Supporting knobs:
- region tile size
- merge/split thresholds
- mailbox limits
- violation policy (`warn|reroute|fail`)
- compat lane watchdog thresholds

---

## Migration Guidance for Mod Authors

1. Replace global-thread assumptions with domain scheduling.
2. Treat entity/chunk access as owner-affine.
3. Convert blocking sync patterns to async continuation flows.
4. Protect global mutable state (or eliminate it).
5. Annotate event handlers with expected affinity.
6. Test in strict diagnostics mode early.

---

## Risk Register and Mitigations

1. **Ecosystem breakage**  
   Mitigate via hybrid default + compat lane.
2. **Performance regression from compat overhead**  
   Mitigate via profiling and targeted porting guidance.
3. **Deadlocks in cross-region coordination**  
   Mitigate via no-blocking policy + lock ordering.
4. **Nondeterministic bug reports**  
   Mitigate via deterministic replay mode.
5. **Operational complexity**  
   Mitigate with clear mode presets and diagnostics UX.

---

## Acceptance Criteria and Exit Gates

## Functional
- mixed legacy/safe modpacks run in hybrid mode without widespread crashes
- owner violations detectable and actionable
- core gameplay invariants preserved (duping/corruption avoided)

## Performance
- measurable TPS/jitter improvement in spread-player benchmarks
- bounded global barrier time
- legacy lane utilization visible and optimizable

## Developer Experience
- mod authors can identify safety issues with clear tooling
- migration docs and examples reduce port friction

---

## Example Runtime Flows

## Example 1: Player breaks block
1. packet decoded async
2. enqueue to player owner region
3. region validates tool/state and mutates block
4. neighbor updates local or cross-region cast
5. result packets emitted safely

## Example 2: Legacy mod event handler mutates world
1. event dispatched in `LEGACY_SERIAL`
2. mod calls world mutation API
3. API intercept detects non-owner domain
4. reroute call to target owner region
5. continuation returns outcome (non-blocking preferred)

## Example 3: Entity crossing border
1. old region marks `MIGRATING`
2. transfer capsule sent
3. new region claims and publishes ownership
4. pending references resolve via indirection map
5. old owner finalizes detach

---

## Recommended Docs to Publish

1. **Concurrency Contract Spec**
2. **Scheduler API Guide**
3. **Event Domain Reference**
4. **Legacy Compatibility Behavior**
5. **Mod Porting Cookbook**
6. **Debugging Violations Manual**
7. **Performance Tuning Guide**
8. **Certification Checklist for STRICT_SAFE mods**

---

## Final Recommendation

Do **not** attempt a literal Folia code transplant into NeoForge.  
Instead, build a **NeoForge-native multithreaded server mode** with:

- region ownership execution,
- domain-aware scheduler/event APIs,
- strict runtime checks,
- and a serialized compatibility lane for legacy mods.

That is the only path that balances **performance, correctness, and ecosystem survivability**.