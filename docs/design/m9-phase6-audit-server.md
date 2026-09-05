# M9 Phase 6 wave A — downstream caller audit (server cohorts 6.1–6.7)

**Status:** complete. All seven cohorts audited against the Phase 4/5 M9
landing. Verdict: **all CLEAN**; five regression tests added under
`multiforge-runtime/src/test/java/net/multiforge/runtime/chunk/regression/`
to pin the per-type ticket routing invariant the observer bridge relies
on.

## Method

The M9 landing keeps the Vanilla `ChunkMap`, `DistanceManager`, and
`ThreadedLevelLightEngine` classes as the instantiated implementation and
layers observer seams on top; the parallel
`net.multiforge.neoforge.chunk.MultiForgeChunkMap` / `MultiForgeDistanceManager` /
`MultiForgeLightEngine` facades extend the same base classes so the
per-region view can build without replacing the Vanilla instance. Ticket
writes now flow

```
serverchunkcache.addRegionTicket(type, pos, distance, key)
  → DistanceManager.addTicket(chunkKey, ticket)
  → ChunkMap.DistanceManagerImpl.addTicket (override, from ChunkMap.java.patch)
  → DistanceManagerBridge.onAddTicket(level, chunkKey, vanillaTicket)
  → ChunkHolderManager.addTicket(regionId, mfPos, toMultiForgeTicket(vanillaTicket))
  → PerRegionTicketMap.addTicket → PerChunkTickets.add
```

with the symmetric `removeTicket` path. `START` tickets are emitted from
`RegionizedChunkLifecycle.onChunkLoaded/onChunkUnloaded` (Phase 5 task 5.6
— real ticket writes, no more shadow-only). Public Vanilla API surface
(`ServerChunkCache.addRegionTicket/removeRegionTicket`, `.chunkMap`,
`.distanceManager`, `.getChunkSource`, `.tick`, `.move`, `.pollTask`,
`.hasWork`, `.getStorageName`, `.updateChunkForced`, `.removeTicketsOnClosing`,
`ChunkMap.getPlayersCloseForSpawning`, `DistanceManager.inEntityTickingRange` /
`inBlockTickingRange`, `waitForLightBeforeSending`, `randomState`,
`isOldChunkAround`, `broadcastAndSend`, `addEntity`, `removeEntity`,
`blockChanged`, `getPoiManager`, `getPendingTasksCount`, `getGenerator`,
`getLastSpawnState`, `getDataStorage`) is unchanged by every patch under
`multiforge-patches/04-chunk-system/` — all four patch files add lines,
none delete or rename.

For each cohort:
1. `grep -n "getChunkSource\|chunkMap\|distanceManager\|DistanceManager\|ChunkMap\|ThreadedLevelLightEngine"`
   over the cohort files under
   `upstream/neoforge-1.21.1/projects/neoforge/src/main/java/net/minecraft/`.
2. Cross-reference every hit against the four
   `multiforge-patches/04-chunk-system/net/minecraft/server/level/*.patch`
   files and the observer-side facades.
3. Where a call site is a first-class ticket source for a non-default
   type (PORTAL, DRAGON, POST_TELEPORT, START, PLAYER), add a runtime-side
   regression test that pins the routing behavior the fork's
   `DistanceManagerBridge.onAddTicket` produces.

## Cohort 6.1 — Entity / EntityInLevelCallback (portal + teleport tickets)

Files scanned:
- `net/minecraft/world/entity/Entity.java`
- `net/minecraft/world/level/entity/EntityInLevelCallback.java`
- `net/minecraft/world/entity/PortalProcessor.java`

Call sites touching M9 API surface (1 total):

| Line | Site | Vanilla API used | Verdict |
|---|---|---|---|
| `Entity.java:2627` | `serverlevel.getChunkSource().addRegionTicket(TicketType.PORTAL, new ChunkPos(p_352083_), 3, p_352083_)` | `ServerChunkCache.addRegionTicket` (unchanged) | CLEAN — observer bridge translates PORTAL name via `TicketType.of("portal", level, 300)` |

`EntityInLevelCallback` and `PortalProcessor` have no chunk-source
touches; entity add/remove flows through
`ServerLevel.entityManager` and eventually
`ServerLevel$EntityCallbacks#onTrackingStart/Stop` which use
`serverLevel.getChunkSource().addEntity/removeEntity` — already covered
by 6.2.

Verdict: **CLEAN + TESTED**. Regression:
`Entity_portalTicketRoutingTest.java` — PORTAL type-name translation,
5-tick timeout parity with Vanilla (Vanilla `TicketType.PORTAL.timeout()`
is 300 ticks; the timeout carries through unchanged via
`DistanceManagerBridge.toMultiForgeTicket`).

## Cohort 6.2 — ServerLevel tick + chunk-source calls

File scanned:
- `net/minecraft/server/level/ServerLevel.java`

Call sites (~24 total, all preserved). Representative:

| Line | Site | Verdict |
|---|---|---|
| `323, 326` | `getChunkSource().getNoiseBiome(...)` / `randomState().sampler()` | CLEAN (read-only) |
| `379` | `getChunkSource().tick(...)` | CLEAN (observer seam only) |
| `408` | `chunkSource.chunkMap.getDistanceManager().inEntityTickingRange(...)` | CLEAN (Vanilla method preserved) |
| `438` | `chunkSource.chunkMap.getDistanceManager().inBlockTickingRange(...)` | CLEAN |
| `808–834` | `ServerChunkCache serverchunkcache = getChunkSource(); ... getDataStorage().save()` | CLEAN |
| `1073` | `getChunkSource().blockChanged(...)` | CLEAN |
| `1126, 1131, 1512, 1517, 1040` | `getChunkSource().broadcastAndSend(...)` | CLEAN |
| `1134` | `public ServerChunkCache getChunkSource()` (declaration) | CLEAN — return type unchanged |
| `1320, 1330, 1333` | `getChunkSource().findClosestBiome...` / `randomState().sampler()` | CLEAN |
| `1352, 1441` | `.getDataStorage()`, `.getPoiManager()` | CLEAN |
| `1380` | `getChunkSource().removeRegionTicket(TicketType.START, ...)` (spawn radius shrink) | CLEAN — flows through bridge |
| `1385` | `getChunkSource().addRegionTicket(TicketType.START, ...)` (spawn radius grow) | CLEAN — flows through bridge |
| `1412` | `getChunkSource().updateChunkForced(...)` | CLEAN (already covered by ForceloadIntegrationTest) |
| `1478–1494` | `chunkMap.getDistanceManager()`, `.getDebugStatus()`, `getLastSpawnState()`, `getPendingTasksCount()` | CLEAN (debug dump) |
| `1692` | `chunkSource.chunkMap.getDistanceManager().inEntityTickingRange(...)` | CLEAN |
| `1745, 1772` | `getChunkSource().addEntity/removeEntity` | CLEAN |

Verdict: **CLEAN + TESTED**. Regression:
`ServerLevel_startTicketRoutingTest.java` — pins the spawn-radius
ticket-add/remove round-trip driving line 1380/1385 (`TicketType.START`
routing, TICKING promotion at radius > 0).

## Cohort 6.3 — EndDragonFight (DRAGON ticket)

File scanned:
- `net/minecraft/world/level/dimension/end/EndDragonFight.java`

Call sites (5 total):

| Line | Site | Verdict |
|---|---|---|
| `161` | `level.getChunkSource().addRegionTicket(TicketType.DRAGON, new ChunkPos(0,0), 9, Unit.INSTANCE)` | CLEAN — bridge routes DRAGON name |
| `189` | `level.getChunkSource().removeRegionTicket(TicketType.DRAGON, ...)` | CLEAN — symmetric |
| `407, 421` | `level.getChunkSource().getGenerator()` (feature placement) | CLEAN (read-only) |
| `424` | `level.getChunkSource().chunkMap.waitForLightBeforeSending(new ChunkPos(portalLocation), i)` | CLEAN — Vanilla method preserved by patch (no signature change in `04-chunk-system/net/minecraft/server/level/ChunkMap.java.patch`) |

Verdict: **CLEAN + TESTED**. Regression:
`EndDragonFight_dragonTicketRoutingTest.java` — pins DRAGON ticket
add/remove pair at chunk (0,0), radius 9 (per-Vanilla-source distance),
and verifies the holder is BORDER-or-higher through the pair and drops
to INACCESSIBLE on remove.

## Cohort 6.4 — MinecraftServer boot / save / stop

File scanned:
- `net/minecraft/server/MinecraftServer.java`

Call sites (7 relevant, all reads / preserved-API writes):

| Line | Site | Verdict |
|---|---|---|
| `436` | `ServerChunkCache serverchunkcache = p_177897_.getChunkSource()` (boot spawn prep) | CLEAN |
| `495` | `ServerChunkCache serverchunkcache = serverlevel.getChunkSource()` (spawn chunks force) | CLEAN |
| `517` | `serverlevel1.getChunkSource().updateChunkForced(chunkpos, true)` (spawn chunks force) | CLEAN (Vanilla method preserved) |
| `562` | `serverlevel1.getChunkSource().chunkMap.getStorageName()` (save log line) | CLEAN (getter preserved) |
| `611` | `levels.values().stream().anyMatch(sl -> sl.getChunkSource().chunkMap.hasWork())` (stop-wait guard) | CLEAN |
| `615` | `serverlevel1.getChunkSource().removeTicketsOnClosing()` | CLEAN (Vanilla method preserved; ticket-mirror observer sees a batch remove that flows through the same DistanceManagerImpl override the patch adds) |
| `616` | `serverlevel1.getChunkSource().tick(() -> true, false)` (stop-tick drain) | CLEAN |
| `860` | `serverlevel.getChunkSource().pollTask()` (main-thread queue drain) | CLEAN |

**Escalation rule (CLAUDE.md):** `MinecraftServer.runServer` was
inspected only. No modification. The tick-body-side wiring MultiForge
requires (Phase 5.1–5.5 subsystem drains) is done inside
`MultiThreadedSchedulerHost.installM9WiredTickBody`, which is invoked
from the MultiForge runtime install path — not from `runServer`
directly.

Verdict: **CLEAN**. No new regression test — the boot/stop lifecycle is
covered by `RegionShutdownCoordinator` and `RegionJournalLifecycle` tests
under `multiforge-runtime/src/test/java/net/multiforge/runtime/`, and no
call site is a first-class ticket source.

## Cohort 6.5 — PlayerChunkSender / ServerPlayer

Files scanned:
- `net/minecraft/server/network/PlayerChunkSender.java` (moved from
  `net/minecraft/server/level/PlayerChunkSender.java` in 1.21.1 —
  task-brief path was stale)
- `net/minecraft/server/level/ServerPlayer.java`

Call sites (9 total):

| File / Line | Site | Verdict |
|---|---|---|
| `PlayerChunkSender.java:15` | `import ChunkMap` | CLEAN (import unchanged) |
| `PlayerChunkSender.java:57` | `ChunkMap chunkmap = serverlevel.getChunkSource().chunkMap` | CLEAN (field type unchanged — still Vanilla `ChunkMap`, not the facade) |
| `PlayerChunkSender.java:85` | `collectChunksToSend(ChunkMap p_296053_, ChunkPos p_295659_)` (signature) | CLEAN |
| `ServerPlayer.java:513, 1691` | `serverLevel().getChunkSource().move(this)` | CLEAN — flows through `DistanceManagerBridge.observeMove` observer |
| `ServerPlayer.java:1040, 1512, 1517` | `.broadcastAndSend(this, packet)` | CLEAN |
| `ServerPlayer.java:1488` | `p_265564_.getChunkSource().addRegionTicket(TicketType.POST_TELEPORT, chunkpos, 1, this.getId())` | CLEAN — bridge routes POST_TELEPORT (5-tick timeout carries through) |

Verdict: **CLEAN + TESTED**. Regression:
`ServerPlayer_postTeleportTicketRoutingTest.java` — pins the
POST_TELEPORT ticket with a 5-tick timeout, verifies that the runtime
`TicketExpiryTicker` would sweep it on the expiry clock the bridge
propagates from `Ticket.getCreatedTick()`.

## Cohort 6.6 — WorldGenRegion

File scanned:
- `net/minecraft/server/level/WorldGenRegion.java`

Call sites (3 total, all read-only):

| Line | Site | Verdict |
|---|---|---|
| `88` | `p_143484_.getChunkSource().randomState().getOrCreateRandomFactory(...)` | CLEAN |
| `94` | `level.getChunkSource().chunkMap.isOldChunkAround(...)` | CLEAN (Vanilla method preserved) |
| `379–380` | `public ChunkSource getChunkSource() { return level.getChunkSource(); }` (facade) | CLEAN |

Verdict: **CLEAN**. No new regression test — read-only cohort; the
worldgen-side promotion path is covered by the Phase 4 task 4.7
`ChunkGenerationTask` + `GenerationChunkHolder` observer tests.

## Cohort 6.7 — Mob-cap / spawning

Files scanned:
- `net/minecraft/world/level/LocalMobCapCalculator.java` (path
  correction — task-brief `world/level/entity/LocalMobCapCalculator.java`
  was stale)
- `net/minecraft/server/level/TickingTracker.java`
- `net/minecraft/world/level/NaturalSpawner.java`

Call sites:

| File / Line | Site | Verdict |
|---|---|---|
| `LocalMobCapCalculator.java:17,19,20,24` | Holds a `ChunkMap` field, calls `chunkMap.getPlayersCloseForSpawning(sectionPos)` | CLEAN — `ChunkMap` type unchanged, `getPlayersCloseForSpawning` preserved |
| `TickingTracker.java` (whole file) | No touches of `getChunkSource / chunkMap / distanceManager` — TickingTracker is a state helper for `DistanceManager`, and only the parent `DistanceManager` writes to it | CLEAN |
| `NaturalSpawner.java:146` | `p_47040_.getChunkSource().getGenerator()` | CLEAN (read-only) |

Verdict: **CLEAN + TESTED**. Regression:
`MobCap_tickingRangeRoutingTest.java` — pins the PLAYER ticket
promoting a chunk to `ENTITY_TICKING`, which is exactly the level
`DistanceManager.inEntityTickingRange` reads to authorise mob spawning
and per-tick entity ticks (the invariant `ServerLevel.java:408` / `1692`
relies on).

## Surprises

- The task brief listed three file paths that are stale in NeoForge 1.21.1:
  - `world/entity/EntityInLevelCallback.java` → actual
    `world/level/entity/EntityInLevelCallback.java`
  - `server/level/PlayerChunkSender.java` → actual
    `server/network/PlayerChunkSender.java`
  - `world/level/entity/LocalMobCapCalculator.java` → actual
    `world/level/LocalMobCapCalculator.java`

  All three were located and audited under their real paths — recorded
  here so future audit passes don't re-derive.

- Cohort 6.4 (`MinecraftServer`) touches the chunk-source through only
  Vanilla methods; the tick-body wiring the M9 landing needs lives
  inside `MultiThreadedSchedulerHost.installM9WiredTickBody` rather than
  in `runServer`. The CLAUDE.md escalation rule was honoured — no patch
  attempted.

- `Entity.java:2627` writes PORTAL tickets with radius 3; the runtime
  `PerChunkTickets.minDistance` interprets ticket `distance` on the
  Vanilla-DistanceManager scale (`level = 33 + 1 - radius = 31`,
  ENTITY_TICKING). `DistanceManagerBridge.toMultiForgeTicket` threads
  `Ticket.getTicketLevel()` through as the runtime `distance`, so the
  scale is preserved end-to-end.

## No patches added

All seven cohorts CLEAN — no new patches under
`multiforge-patches/04-chunk-system/` were required by this audit.

## Regression test index

| Test | Cohort | Type pinned |
|---|---|---|
| `Entity_portalTicketRoutingTest` | 6.1 | PORTAL (300-tick timeout, radius 3) |
| `ServerLevel_startTicketRoutingTest` | 6.2 | START (permanent, spawn radius) |
| `EndDragonFight_dragonTicketRoutingTest` | 6.3 | DRAGON (permanent, radius 9) |
| `ServerPlayer_postTeleportTicketRoutingTest` | 6.5 | POST_TELEPORT (5-tick timeout, radius 1) |
| `MobCap_tickingRangeRoutingTest` | 6.7 | PLAYER (permanent, ENTITY_TICKING promotion) |
