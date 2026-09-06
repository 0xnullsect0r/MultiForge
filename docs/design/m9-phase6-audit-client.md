# M9 Phase 6 wave B — client + events + sweep audit

**Scope:** cohorts 6.8–6.15 of the M9 Phase 6 "downstream caller migration"
task list. Sibling wave A (cohorts 6.1–6.7) covers server-side callers and
is documented separately in `m9-phase6-audit-server.md`.

**Method:**

1. Grep for call sites against the Vanilla ChunkMap / DistanceManager /
   ChunkSource / FullChunkStatus / TicketType surface.
2. Verify each call resolves against the MultiForge facade or the
   still-authoritative Vanilla class — MultiForge in this landing is an
   **observer** on Vanilla's writes (Phase 4.1c / 4.2c / 4.5c observer
   hunks + Phase 5 wave B real ticket writes), not a full replacement of
   the Vanilla classes.
3. Add runtime-module regression tests for non-trivial routing invariants.
4. Do not modify files under `upstream/neoforge-1.21.1/projects/**`
   directly — those are regenerated from patches. Any needed change goes
   into a new patch under `multiforge-patches/`.

**Overall verdict:** CLEAN — every audited caller resolves. Two regression
tests added under
`multiforge-runtime/src/test/java/net/multiforge/runtime/chunk/regression/`
covering the Vanilla-shape ticket routing invariant and the
ChunkTicketLevelUpdatedEvent level-boundary contract; one
integration-tagged test documents the Vanilla `Visibility` ↔ MultiForge
`ChunkLoadLevel` ladder-parity contract that the entity-section manager
depends on.

---

## Cohort 6.8 — Command callers (FillBiome, ForceLoad, Debug)

**Files audited:**

- `upstream/neoforge-1.21.1/projects/neoforge/src/main/java/net/minecraft/server/commands/ForceLoadCommand.java`
- `upstream/neoforge-1.21.1/projects/neoforge/src/main/java/net/minecraft/server/commands/FillBiomeCommand.java`
- `upstream/neoforge-1.21.1/projects/neoforge/src/main/java/net/minecraft/server/commands/DebugCommand.java`

**Findings:**

- `ForceLoadCommand.changeForceLoad` → `ServerLevel.setChunkForced` →
  `ServerChunkCache.updateChunkForced` → `DistanceManager.updateChunkForced`.
  The final hop is patched (`multiforge-patches/04-chunk-system/net/minecraft/server/level/DistanceManager.java.patch`)
  to fire `MultiForgeDistanceManager.observeChunkForced(this, pos, add)`
  after the Vanilla ticket write, and every `DistanceManager.addTicket` /
  `removeTicket` is additionally routed through
  `DistanceManagerBridge.onAddTicket` / `onRemoveTicket` by the Phase 4.1c
  inner-class overrides on `ChunkMap.DistanceManagerImpl`
  (`ChunkMap.java` lines 1244–1258 as patched). That mirrors the
  Vanilla-shape `Ticket(TicketType.FORCED, level=31, pos)` into a
  MultiForge `Ticket.at(mfType, 31, ...)` and lands it on the per-region
  ticket map via `ChunkHolderManager.addTicket`.
- `FillBiomeCommand` uses `serverlevel.getChunk(x, z, FULL, false)`
  (Vanilla `getChunk`, unchanged sync fetch), reads
  `getChunkSource().randomState().sampler()` (worldgen state on Vanilla
  `ServerChunkCache`, unchanged), and calls
  `getChunkSource().chunkMap.resendBiomesForChunks(list)` (direct field
  access to Vanilla `ChunkMap`). Vanilla `ChunkMap` remains the
  authoritative player-tracking source in this landing, so the biome
  resend still reaches the correct set of watching players.
- `DebugCommand` contains zero chunk/ticket/region symbol references.

**Verdict: CLEAN + TESTED.**

Regression test added: `Cohort68ForceloadTicketRoutingTest` — pins the
invariant that a Vanilla-shape forceload ticket (explicit level 31) routes
to MultiForge effective level `ENTITY_TICKING`, and that duplicate FORCED
writes are idempotent (the second `/forceload add` on the same chunk is a
no-op).

---

## Cohort 6.9 — `ChunkStatusUpdateListener` consumers under `client/multiplayer/*`

**Files audited:** every file under
`upstream/neoforge-1.21.1/projects/neoforge/src/main/java/net/minecraft/client/`
grepped for `ChunkStatusUpdateListener` and `onChunkStatusChange`.

**Findings:** the interface has **no client-side consumers.** Vanilla
declares one implementation only — `ServerLevel.entityManager::updateChunkStatus`,
passed to `ServerChunkCache`'s ctor. The client-side chunk lifecycle
(`ClientChunkCache`) uses its own storage and receives status through
network packets; it does not implement or consume
`ChunkStatusUpdateListener`. The task hint "client-side visibility"
turned out to be a misnomer for the entity-manager listener, which is
covered under cohort 6.12.

**Verdict: CLEAN** — nothing on the client side depends on the
server-side chunk-map internals through this interface. No test added
(nothing to regress against).

---

## Cohort 6.10 — `Visibility.fromFullChunkStatus`

**Files audited:**

- `upstream/neoforge-1.21.1/projects/neoforge/src/main/java/net/minecraft/world/level/entity/Visibility.java`
- All 10 callers of `Visibility.*` reachable from the neoforge project source
  set (grep hits in `PersistentEntitySectionManager`, `TransientEntitySectionManager`,
  `EntitySection`).

**Findings:** `Visibility.fromFullChunkStatus` is a pure static mapping —
`FullChunkStatus.ENTITY_TICKING` → `TICKING`; `FullChunkStatus.FULL` →
`TRACKED`; otherwise `HIDDEN`. Vanilla `FullChunkStatus` is on the
"reuse verbatim" list (see `docs/design/m9-contracts.md`); MultiForge's
`ChunkLoadLevel` ladder maps 1-1 with the same integer boundaries
(`INACCESSIBLE=34`, `BORDER=33`, `TICKING=32`, `ENTITY_TICKING=31`).

**Verdict: CLEAN + TESTED** — mapping is unchanged.

Regression test added:
`Cohort612EntityVisibilityRoutingTest.multiForgeLadderMatchesVanillaVisibilityBoundaries`
(shared with cohort 6.12) — pins the ladder-parity invariant that the
entity-section manager's gating depends on. Tagged `@Tag("integration")`
by class-level annotation for consistency with the entity-manager
integration path; the assertions themselves are runtime-only.

---

## Cohort 6.11 — `LevelChunk` full-status supplier + `EmptyLevelChunk`

**Files audited:**

- `upstream/neoforge-1.21.1/projects/neoforge/src/main/java/net/minecraft/world/level/chunk/LevelChunk.java`
- `upstream/neoforge-1.21.1/projects/neoforge/src/main/java/net/minecraft/world/level/chunk/EmptyLevelChunk.java`
- The one caller of `LevelChunk.setFullStatus` at
  `net/minecraft/world/level/chunk/status/ChunkStatusTasks.java:203`.

**Findings:**

- `LevelChunk.setFullStatus(Supplier<FullChunkStatus>)` binds the
  supplier to `generationchunkholder::getFullStatus` in
  `ChunkStatusTasks.upgradeToFullChunk`. `GenerationChunkHolder` (the
  Vanilla `ChunkHolder` base) still owns the ticket-level state that
  `getFullStatus` reads, and MultiForge attaches its `mfShadow`
  side-along (Phase 4.4). The supplier resolves against Vanilla state as
  before.
- `EmptyLevelChunk.getFullStatus()` unconditionally returns
  `FullChunkStatus.FULL`. Sentinel behaviour, no per-chunk routing.

**Verdict: CLEAN** — unchanged supplier binding; MultiForge does not yet
own the primary status source, and no downstream caller has been
migrated onto MultiForge's supplier in Phase 6. No test added (the
existing runtime `NewChunkHolderTest` covers the shadow-holder side; the
supplier binding is a Vanilla-side patch invariant already asserted by
the neoforge fork compile step).

---

## Cohort 6.12 — `PersistentEntitySectionManager`

**Files audited:**

- `upstream/neoforge-1.21.1/projects/neoforge/src/main/java/net/minecraft/world/level/entity/PersistentEntitySectionManager.java`
- Its construction site at `ServerLevel.java:250` and the listener
  binding at `ServerLevel.java:262` (`this.entityManager::updateChunkStatus`
  passed to `ServerChunkCache`).

**Findings:**

- `updateChunkStatus(ChunkPos, FullChunkStatus)` delegates to the
  `Visibility` overload after mapping through `Visibility.fromFullChunkStatus`.
  The Vanilla `ChunkMap.chunkStatusListener` fires the listener from
  `ChunkMap.updateChunkScheduling` when the full status changes;
  MultiForge does not yet substitute a listener source, so the callback
  reaches the entity manager on the Vanilla path.
- Ancillary usages (`ServerLevel.areEntitiesLoaded`,
  `ServerLevel.canPositionTick` etc.) all read the Vanilla entity
  manager. `ServerLevel.java:1692` cross-references
  `chunkSource.chunkMap.getDistanceManager().inEntityTickingRange(...)`
  — the Vanilla `DistanceManager` (via the `ChunkMap.DistanceManagerImpl`
  inner-class extending it) is still the source of truth for the
  entity-tick range predicate.

**Verdict: CLEAN + TESTED (integration).**

Regression test added: `Cohort612EntityVisibilityRoutingTest` — pins the
`ChunkLoadLevel` ↔ `Visibility` ladder parity so that MultiForge's own
`effectiveLevel` will produce the same gating decisions when the arrow
flips in a later phase. Tagged `@Tag("integration")` because the full
end-to-end assertion (feeding a real `PersistentEntitySectionManager` a
status transition) needs MC classes that the runtime module cannot host.

---

## Cohort 6.13 — Client-side chunk cache (renderer)

**Files audited:**

- `upstream/neoforge-1.21.1/projects/neoforge/src/main/java/net/minecraft/client/multiplayer/ClientLevel.java`
- `upstream/neoforge-1.21.1/projects/neoforge/src/main/java/net/minecraft/client/multiplayer/ClientChunkCache.java`

**Findings:**

- `ClientChunkCache extends ChunkSource` — fully client-side, receives
  `LevelChunk`s from network packets, no reference to server-side
  `ChunkMap` / `DistanceManager` / `FullChunkStatus` / `TicketType`
  internals. Fires its own `ChunkEvent.Load` / `Unload` on the client
  event bus, distinct from the server-side fires.
- `ClientLevel.getChunkSource()` returns the `ClientChunkCache`; no
  other chunk-system surface leaks in.

**Verdict: CLEAN** — client renderer state does not share threads with
the server and does not depend on server-side chunk-map internals through
any leaky API. No test added (nothing to regress; audit was compile-only
per plan).

---

## Cohort 6.14 — NeoForge event hooks (`ChunkEvent.Load/Unload`, `ChunkTicketLevelUpdatedEvent`)

**Files audited:**

- `upstream/neoforge-1.21.1/src/main/java/net/neoforged/neoforge/event/level/ChunkEvent.java`
- `upstream/neoforge-1.21.1/src/main/java/net/neoforged/neoforge/event/level/ChunkTicketLevelUpdatedEvent.java`
- `upstream/neoforge-1.21.1/src/main/java/net/neoforged/neoforge/event/EventHooks.java:769`
  (`fireChunkTicketLevelUpdated`) and its callers.

**Findings:**

- `fireChunkTicketLevelUpdated` fires from Vanilla
  `ChunkMap.updateChunkScheduling` (patched neoforge line 398). Vanilla
  remains the primary ticket-level driver in this landing; MultiForge
  observes ticket writes via the inner-class overrides on
  `ChunkMap.DistanceManagerImpl.addTicket/removeTicket` and does not
  drive its own event fire (yet — a later phase when MultiForge becomes
  the authoritative ticket source must add an equivalent fire on the
  MultiForge side).
- `ChunkEvent.Load` fires from `ChunkStatusTasks.upgradeToFullChunk`
  (line 215) — Vanilla worldgen path. MultiForge's
  `RegionizedChunkLifecycle.onChunkLoaded` listens to this event and
  writes a real `TicketType.START` ticket via
  `ChunkHolderManager.addTicket` (Phase 5.6 task).
- `ChunkEvent.Unload` fires from `ChunkMap` (line 516) plus the
  symmetric client-side fire. MultiForge's `onChunkUnloaded` symmetric
  release is landed under Phase 5.6.
- NeoForge's own `ChunkEvent.Unload` consumers
  (`CapabilityHooks.invalidateCapsOnChunkUnload`,
  `ModelDataManager.onChunkUnload`, `NeoForgeEventHandler.onChunkUnload`)
  are unaffected — they receive the same event payload as before.
- Custom mod ticket types created via `TicketType.create` (e.g.
  `ForcedChunkManager.BLOCK`, `NEOFORGE_GENERATE_FORCED`) route through
  the same `addTicket`/`removeTicket` choke points and are mirrored to
  MultiForge with their original ticket levels intact.

**Verdict: CLEAN + TESTED.**

Regression test added: `Cohort614EventLevelContractTest` — pins the four
Vanilla ticket-level integer boundaries (34/33/32/31) map to the
matching MultiForge `ChunkLoadLevel`, and that a mod-added ticket at
Vanilla `ENTITY_TICKING_LEVEL == 31` triggers the symmetric
`INACCESSIBLE ↔ ENTITY_TICKING` transition in MultiForge's holder that a
`ChunkTicketLevelUpdatedEvent` listener observing Vanilla's holder would
also see.

---

## Cohort 6.15 — Sweep grep

Grep across `upstream/neoforge-1.21.1/projects/neoforge/src/main/java/`
for `getChunkSource`, `chunkMap`, `distanceManager` — 127 / 36 / 22 hits
respectively. Cross-referenced against cohorts 6.1–6.14. Additional
NeoForge-side call sites not yet claimed:

- `neoforge/common/world/chunk/ForcedChunkManager.java` — uses
  `level.getChunkSource().addRegionTicket(type, pos, 2, owner, ticking)`
  with custom `TicketType`s (`BLOCK`, `BLOCK_TICKING`, `ENTITY`,
  `ENTITY_TICKING`). Routes through Vanilla `ServerChunkCache` →
  Vanilla `DistanceManager.addRegionTicket` → the patched
  `addTicket` override on `ChunkMap.DistanceManagerImpl`, which mirrors
  every mod-added ticket into MultiForge. **CLEAN.**
- `neoforge/server/command/generation/GenerationTask.java` — uses
  `chunkSource.chunkMap` + `holder.scheduleChunkGenerationTask` + a
  `.join()` on the completion future. Runs on the command thread (not a
  region worker), so the `.join()` does not violate CLAUDE.md rule 4.
  The direct field access resolves to Vanilla `ChunkMap` as before.
  **CLEAN.**
- `neoforge/network/PacketDistributor.java` — uses
  `getChunkSource().chunkMap.getPlayers(chunkPos, false)` and
  `chunkCache.broadcast(entity, packet)`. Vanilla `ChunkMap`'s player
  set is still authoritative for tracking; the broadcast reaches the
  correct set. **CLEAN.**
- `neoforge/common/world/LevelChunkAuxiliaryLightManager.java` — uses
  `getLightEngine().checkBlock(pos)`. Vanilla `ThreadedLevelLightEngine`
  is patched with observer hunks (`multiforge-patches/04-chunk-system/net/minecraft/server/level/ThreadedLevelLightEngine.java.patch`)
  that fire `MultiForgeLightEngine.observeCheckBlock` after every
  `checkBlock`, but the primary engine is unchanged. **CLEAN.**
- `neoforge/common/extensions/IBlockGetterExtension.java` — uses
  `getChunkSource().getChunkForLighting(...)`. Client/server-agnostic
  read path. **CLEAN.**

Server-side commands not in cohort 6.8 that touch the chunk source
(`LocateCommand`, `PlaceCommand`, `ExecuteCommand`) all use read-only
generator / random-state / `getChunkNow` fetches — unchanged Vanilla
paths. Delegated to cohort 6.2 / 6.4 (server-side) in sibling wave A.

**Verdict: CLEAN — no strays.**

---

## Surprises / notes

- **`MultiForgeChunkMap` extends `ChunkStorage`, not `ChunkMap`.** The
  task brief described "facades that extend Vanilla base classes," which
  is accurate for `MultiForgeDistanceManager` (`extends DistanceManager`)
  and `MultiForgeLightEngine` (`extends ThreadedLevelLightEngine`) but
  not for `MultiForgeChunkMap`. The chunk-map facade is a **sibling**
  storage class, and Vanilla `ChunkMap` remains the primary allocator of
  `ChunkHolder`. The Phase 4.1c observer hunks on Vanilla `ChunkMap`
  route into `MultiForgeChunkMap` via the static
  `InstanceRegistry<ServerLevel, MultiForgeChunkMap>` lookup.
  Downstream callers of `getChunkSource().chunkMap` still resolve to
  Vanilla `ChunkMap` and see unchanged behaviour, which is why the API
  contract holds.
- **`ChunkStatusUpdateListener` has zero client-side consumers.** The
  cohort-6.9 task hint pointed at `client/multiplayer/*`, but the
  interface is server-side only (see cohort 6.9 verdict). No drift to
  fix.
- **`TicketType.FORCED.defaultDistance == BORDER (33)` in MultiForge
  vs. Vanilla `FORCED_TICKET_LEVEL == ENTITY_TICKING (31)`.** Not a bug:
  `DistanceManagerBridge.toMultiForgeTicket` preserves the **explicit
  level** on the Vanilla ticket and passes it to `Ticket.at(mfType,
  level, key, createdAtTick)`, so a Vanilla-shape forceload ticket lands
  at level 31 in MultiForge and promotes the holder to `ENTITY_TICKING`
  — the same effective status Vanilla grants.
  `TicketType.FORCED.defaultDistance` only matters when a caller uses
  the runtime-native `Ticket.of(TicketType.FORCED, key)` factory, which
  the fork facade does not (it always translates from Vanilla tickets).
  The `Cohort68ForceloadTicketRoutingTest` pins the Vanilla-shape path.
- **When the arrow flips (MultiForge becomes the primary driver), event
  fires must move too.** `ChunkEvent.Load/Unload` and
  `ChunkTicketLevelUpdatedEvent` are currently fired from Vanilla
  ChunkMap/ChunkStatusTasks; downstream mod hooks depend on them. A
  future phase must fire equivalents from MultiForge's own
  `updateChunkScheduling`-analogue so mods do not see event loss. Not a
  drift today (Vanilla is still the primary fire site), but flagged for
  the Phase-7 exit gate.
