# MultiForgeChunkMap — Fork Facade Design

**Status:** Phase 4 task 4.1a of the M9 landing plan
(`plans/bubbly-jumping-comet.md`). Locks the design that Phase 4.1b's
~1200-LOC implementation will follow.
**Verdict source:** `docs/design/m9-patch-strategy.md` §ChunkMap — REPLACE.
**API surface source:** `docs/design/m9-contracts.md` §5 MultiForgeChunkMap.
**Non-goals:** DistanceManager design (4.2a), light engine design (4.5a),
ServerChunkCache patch (4.3a-e), holder shadow attachment (4.4a).

Cite conventions: `ChunkMap.java:<line>` is the Vanilla source at
`upstream/neoforge-1.21.1/projects/neoforge/src/main/java/net/minecraft/server/level/ChunkMap.java`.
Runtime file:line refers to a snippet in
`multiforge-runtime/src/main/java/net/multiforge/runtime/**`.

---

## 1. Purpose

Frozen from the m9-patch-strategy verdict (§ChunkMap): **REPLACE**.
`MultiForgeChunkMap` owns three responsibilities that Vanilla `ChunkMap`
centralises today and that Folia/Moonrise splits per-region:

- **Holder table** — Vanilla's `updatingChunkMap` / `visibleChunkMap`
  (`ChunkMap.java:116-117`) become a view backed by
  `ChunkHolderManager.byChunk` (`ChunkHolderManager.java:44`), per-world
  via `MultiThreadedSchedulerHost.chunkManagerFor(world)`
  (`MultiThreadedSchedulerHost.java:195`).
- **Worldgen dispatch** — the `worldgenMailbox` / `mainThreadMailbox`
  wiring around `WorldGenContext` (`ChunkMap.java:184-204`) becomes
  per-region priority routing through `ChunkTaskScheduler.scheduleChunkTask`
  (`ChunkTaskScheduler.java:75`).
- **Player tracking** — the `playerMap` + `entityMap` (`ChunkMap.java:137-138`)
  become per-holder `playersWatching` (`NewChunkHolder.java:157`) plus an
  entity tracker that stays main-thread driven in Phase 4.1b.

The facade implements `GeneratingChunkMap` and `ChunkHolder.PlayerProvider`
so `ChunkGenerationTask.create(this, …)` (`ChunkMap.java:656`) and
`ChunkHolder`'s player-lookup callback compile unchanged. It extends
`ChunkStorage` so `read`/`write` resolve to the region file — until Phase
3's `RegionFileCache` swaps those out.

---

## 2. Class shape (Java signature)

```java
package net.multiforge.neoforge.chunk;

@org.jetbrains.annotations.ApiStatus.Internal
public final class MultiForgeChunkMap
        extends net.minecraft.world.level.chunk.storage.ChunkStorage
        implements net.minecraft.server.level.ChunkHolder.PlayerProvider,
                   net.minecraft.world.level.chunk.status.GeneratingChunkMap {

    // === §7 carried over unchanged ===
    final ServerLevel level;                                    // ChunkMap.java:120
    private final PoiManager poiManager;                        // ChunkMap.java:126
    private final ChunkProgressListener progressListener;       // ChunkMap.java:132
    private final ChunkStatusUpdateListener chunkStatusListener;// ChunkMap.java:133
    private final ChunkGenerator generator;                     // WorldGenContext arg
    private final RandomState randomState;                      // ChunkMap.java:123
    private final ChunkGeneratorStructureState chunkGeneratorState; // :124
    private final Supplier<DimensionDataStorage> overworldDataStorage; // :125
    private final String storageName;                           // ChunkMap.java:136
    private final WorldGenContext worldGenContext;              // ChunkMap.java:143
    private final AtomicInteger tickingGenerated = new AtomicInteger(); // :135

    // === §4 backing-state bindings ===
    private final MultiThreadedSchedulerHost host;
    private final ChunkHolderManager holders;    // = host.chunkManagerFor(worldRef)
    private final ChunkTaskScheduler tasks;      // = host.chunkTaskScheduler()
    private final MultiForgeDistanceManager distanceManager;

    // === player tracking (main-thread in 4.1b; per-region in a later pass) ===
    private final PlayerMap playerMap = new PlayerMap();        // ChunkMap.java:137
    private final Int2ObjectMap<TrackedEntity> entityMap = new Int2ObjectOpenHashMap<>();
    private volatile int serverViewDistance;                    // ChunkMap.java:142

    // === thin light delegate ===
    private final ThreadedLevelLightEngine lightEngine;         // Phase 4.5 replaces internals
}
```

Every field is required by either an interface, a KEPT Vanilla method,
or a §4 binding. Fields **not** replicated: `updatingChunkMap`,
`visibleChunkMap`, `pendingUnloads`, `toDrop`, `unloadQueue`,
`queueSorter`, `worldgenMailbox`, `mainThreadMailbox`, `chunkTypeCache`,
`chunkSaveCooldowns`, `pendingGenerationTasks`, `modified`,
`mainThreadExecutor`. See §8.

---

## 3. Public/protected API surface

Every Vanilla `ChunkMap` public/protected method is classified KEPT
(same-shape delegate), REPLACED (behaviour changes), or NEW (added by
MultiForge). `docs/design/m9-contracts.md` §5 is the signature source of
truth; this section maps behaviour.

### 3.1 Holder table

| Method (`ChunkMap.java:line`) | Verdict | Behaviour |
|---|---|---|
| `getVisibleChunkIfPresent(long)` (`:258`), `getUpdatingChunkIfPresent(long)` (`:253`) | REPLACED | Both read `holders.holderAt(new ChunkPos(pos))` (`ChunkHolderManager.java:56`) and wrap the returned `NewChunkHolder` in a `ChunkHolderShim` that projects the shadow's `currentChunk`, `fullChunkFuture`, `tickingChunkFuture`, `entityTickingChunkFuture` onto Vanilla's `ChunkHolder` getters. Vanilla's staged updating/visible split is deleted — every read sees live state. |
| `getChunkQueueLevel(long)` (`:262`) | REPLACED | `IntSupplier` reading the holder's `level()` field (`NewChunkHolder.java` volatile). Never touches `PRIORITY_LEVEL_COUNT` gate math. |
| `size()` (`:845`), `getChunks()` (`:853`) | REPLACED | Read `holders.size()` / iterate `holders.snapshotAll()`. Iteration order unspecified. |
| `promoteChunkMap()` (`:543`) | REPLACED | Returns `false` unconditionally — `byChunk` is a `ConcurrentHashMap`, no two-map staging. |
| `getChunkDebugData(ChunkPos)` (`:271`) | KEPT | Format-preserving; reads shim state. |

### 3.2 Generation (implements `GeneratingChunkMap`)

| Method (`ChunkMap.java:line`) | Verdict | Behaviour |
|---|---|---|
| `acquireGeneration(long)` (`:611`), `releaseGeneration(GenerationChunkHolder)` (`:618`) | KEPT | Delegate `.increaseGenerationRefCount` / `.decreaseGenerationRefCount` on the shim. |
| `applyStep(GenerationChunkHolder, ChunkStep, StaticCache2D)` (`:623`) | REPLACED | Body reused, but `progressListener.onStatusChange` (`:635`) fires on the region worker owning the target chunk. Continuation runs via `tasks.scheduleChunkTask(worldRef, pos, r, ChunkTaskPriority.BLOCKING)` for the terminal step, IDLE/LOW for early ladder steps. |
| `scheduleGenerationTask(ChunkStatus, ChunkPos)` (`:655`) | REPLACED | `ChunkGenerationTask.create(this, status, pos)` unchanged, then handed to `tasks.scheduleChunkTask(worldRef, pos, task::runUntilWait, priorityForStatus(status))`. `pendingGenerationTasks` list is deleted (Phase 4.7 patches `ChunkGenerationTask` to retain its own state). |
| `runGenerationTasks()` (`:671`) | REPLACED | No-op — tasks already dispatched at `scheduleGenerationTask` time. Kept for API compat with strict-mode warn if called. |
| `prepareTickingChunk` (`:676`), `prepareAccessibleChunk` (`:709`), `prepareEntityTickingChunk` (`:364`) | REPLACED | Return the shadow's `tickingChunkFuture` / `fullChunkFuture` / `entityTickingChunkFuture` directly. Does **not** allocate a fresh future chain — Vanilla's `thenApplyAsync(..., this.mainThreadExecutor)` is the exact `MainThreadExecutor.join` anti-pattern the plan mandates removing. |
| `getTickingGenerated()` (`:717`) | KEPT | Same `AtomicInteger`; incremented by region worker at ENTITY_TICKING promotion. |

### 3.3 Player tracking

| Method (`ChunkMap.java:line`) | Verdict | Behaviour |
|---|---|---|
| `move(ServerPlayer)` (`:1002`), `addEntity(Entity)` (`:1085`), `removeEntity(Entity)` (`:1111`) | KEPT | Bodies reused; the `markChunkPendingToSend` / `dropChunk` sub-calls (`:1064`) additionally write `holder.addPlayerWatching(player)` / `removePlayerWatching` (`NewChunkHolder.java:420`). |
| `broadcast(Entity, Packet)` (`:1160`), `broadcastAndSend(Entity, Packet)` (`:1167`) | KEPT | Delegate to `TrackedEntity.broadcast`; `seenBy` (`:1260`) drives fan-out — no per-broadcast player scan. |
| `setServerViewDistance(int)` (`:806`), `updateChunkTracking(ServerPlayer)` (`:1039`), `updatePlayerStatus(ServerPlayer, boolean)` (`:974`), `getPlayerViewDistance(ServerPlayer)` (`:818`), `isChunkTracked` (`:227`), `getPlayers(ChunkPos, boolean)` (`:1071`) | KEPT | Same body; ticket writes go through `distanceManager.addPlayer/removePlayer` (Phase 4.2b `MultiForgeDistanceManager`). |

### 3.4 Ticket-level

| Method (`ChunkMap.java:line`) | Verdict | Behaviour |
|---|---|---|
| `updateChunkScheduling(long, int, ChunkHolder?, int)` (`:370`) | REPLACED | Pure delegate: calls `holders.createHolder(pos, ownerRegion)` (`ChunkHolderManager.java:60`), then `holders.addTicket`/`removeTicket` (`:82, :109`) via `MultiForgeDistanceManager`. `pendingUnloads`/`toDrop`/`modified` writes deleted. Fires `ChunkTicketLevelUpdatedEvent` here (§6). The current Phase-M9-substep-1 `ChunkHolderManagerBridge.onTicketLevelUpdated` shadow call at `ChunkMap.java:403` is **deleted** — do NOT re-add. |

### 3.5 Save / load

| Method (`ChunkMap.java:line`) | Verdict | Behaviour |
|---|---|---|
| `saveAllChunks(boolean flush)` (`:419`) | REPLACED | If `flush`: walk every region via `host.regionizerFor(worldRef).forEachRegion(r -> autoSaveRunner.runAllForRegion(r.id()))` — no `mainThreadExecutor.managedBlock` (`:433`), which would deadlock a region worker. Else: enqueue via `holders.markDirty(ownerRegion, pos)` (`ChunkHolderManager.java:120`) which populates `HolderManagerRegionData.autoSaveQueue` (`HolderManagerRegionData.java:36`); Phase 5.3's `Phase.FLUSH_OUTBOUND` drains. |
| `read(ChunkPos)`, `write(ChunkPos, CompoundTag)` (inherited from `ChunkStorage`) | KEPT | Vanilla `ChunkStorage` resolves to the region file. Phase 3 later swaps the resolver to `RegionFileCache`. |
| `scheduleChunkLoad(ChunkPos)` (`:553`) | REPLACED | Runs on the target region's worker via `tasks.scheduleChunkTask(..., BLOCKING)`; the `thenApplyAsync(..., mainThreadExecutor)` chain (`:561, :570`) is replaced with a single completion on that worker. |
| `scheduleUnload(long, ChunkHolder)` (`:511`) | REPLACED | Fires when a holder crosses INACCESSIBLE. Runs on the owning region worker; no `unloadQueue::add` (`:536`) bounce — the shadow's `saveSyncFuture` (`NewChunkHolder.java:217`) provides the "save has committed" gate. Preserves the NeoForge event fires (§6). |
| `save(ChunkAccess)` (`:747`), `saveChunkIfNeeded(ChunkHolder)` (`:721`) | KEPT | Bodies reused; `ChunkDataEvent.Save` (`:769`) fires unchanged. `chunkSaveCooldowns` moves per-region (§8). |

### 3.6 Misc

| Method (`ChunkMap.java:line`) | Verdict | Behaviour |
|---|---|---|
| `getPoiManager` (`:1194`), `getStorageName` (`:1198`), `chunkScanner` (inherited), `getPlayersCloseForSpawning` (`:944`), `anyPlayerCloseEnoughForSpawning` (`:930`), `resendBiomesForChunks` (`:1174`), `onFullChunkStatusChange` (`:1202`), `dumpChunks` (`:857`), `debugFuturesAndCreateReportedException` (`:336`) | KEPT | Behaviour reused; iterations that used `visibleChunkMap.values()` iterate `holders.snapshotAll()` instead. |
| `hasWork()` (`:460`) | REPLACED | `!holders.isEmpty() || tasks.hasWork(regionId) || distanceManager.hasTickets()`. Deletes `pendingUnloads`/`toDrop`/`unloadQueue`/`queueSorter.hasWork` checks. |
| `isOldChunkAround(ChunkPos, int)` | N/A | Not present in 1.21.1 NeoForge ChunkMap; verify at 4.1b implement time. |
| `waitForLightBeforeSending(ChunkPos, int)` (`:1206`) | KEPT | Delegates through the light engine; `addSendDependency` (`:1211`) writes into `holder.sendSyncFuture` (`NewChunkHolder.java:223`). |
| `scheduleOnMainThreadMailbox(Message<Runnable>)` (`:1359`) | REPLACED | Routes into `tasks.scheduleChunkTask(worldRef, msg.chunkPos(), msg.runnable(), NORMAL)`. Preserves NeoForge `GenerationTask.enqueueChunks` API. |
| `close()` (`:410`) | REPLACED | `RegionShutdownCoordinator.awaitAll` (Phase 5.5), then `poiManager.close()`, then `super.close()`. `queueSorter.close()` (`:412`) deleted. |
| `tick(BooleanSupplier)` (`:448`) | REPLACED | POI tick fires as before; `processUnloads` (`:454`) deleted — unloads happen per-region via the autosave queue in `Phase.FLUSH_OUTBOUND`. |
| `tick()` (`:1126`, entity tracker) | KEPT | Body reused; still main-thread in 4.1b. |

### 3.7 NEW methods (added by MultiForge)

| Method | Rationale |
|---|---|
| `public ChunkHolderManager holders()` | Observability seam for `/multiforge chunks` and diagnostics. |
| `public ChunkTaskScheduler tasks()` | Test seam. |
| `RegionId regionIdFor(ChunkPos)` | Resolves owner via `host.regionizerFor(worldRef).regionAtChunk(pos)`. Used by every ticket write and every scheduled task. Any thread; caller must hold the regionizer read lock or accept eventual-consistency reroute. |
| `void onHolderCrossedThreshold(NewChunkHolder, FullChunkStatus)` (package-private) | Invoked by the shadow's future-gate completion path. Fires `onFullChunkStatusChange` and `ChunkEvent.Load/Unload` (§6). |

---

## 4. Backing state — bindings to MultiForge runtime

- **Holder table** → `ChunkHolderManager` per world, retrieved once at
  construction via `host.chunkManagerFor(worldRef)`
  (`MultiThreadedSchedulerHost.java:195`). All reads through
  `holders.holderAt(pos)`, all creations through
  `holders.createHolder(pos, ownerRegion)`
  (`ChunkHolderManager.java:56, :60`). The `byChunk` `ConcurrentHashMap`
  (`ChunkHolderManager.java:44`) is the single truth.
- **Ticket-level → holder-level mapping** → driven by
  `MultiForgeDistanceManager` (Phase 4.2b). Every level transition routes
  through `holders.addTicket(ownerRegion, pos, ticket)` /
  `holders.removeTicket` (`ChunkHolderManager.java:82, :109`). The facade
  never touches `PerRegionTicketMap` directly.
- **Worldgen dispatch** → `ChunkTaskScheduler.scheduleChunkTask(worldRef,
  chunkX, chunkZ, task, priority)` (`ChunkTaskScheduler.java:75`).
  Priority translation: `EMPTY..STRUCTURE_STARTS → LOW`, `..NOISE →
  NORMAL`, `..SURFACE..CARVERS → HIGH`, `..FEATURES..LIGHT → HIGHEST`,
  `..FULL → BLOCKING`. Lives as a `private static ChunkTaskPriority
  priorityForStatus(ChunkStatus)` helper.
- **Player tracking** → `NewChunkHolder.playersWatching`
  (`NewChunkHolder.java:157`, `ConcurrentHashMap.newKeySet()`).
  `markChunkPendingToSend` writes `holder.addPlayerWatching(player)`;
  `dropChunk` writes `removePlayerWatching`. Iteration in `broadcast`
  tolerates concurrent adds.
- **Save** → per-region `AutoSaveRunner` populates against
  `HolderManagerRegionData.autoSaveQueue`
  (`HolderManagerRegionData.java:36`). Enqueue via
  `holders.markDirty(ownerRegion, pos)`
  (`ChunkHolderManager.java:120`), which calls
  `regionData(owner).enqueueAutoSave(holder)`
  (`HolderManagerRegionData.java:55`). Drain in `Phase.FLUSH_OUTBOUND`
  per Phase 5.3.

---

## 5. Threading contract

**Any-thread safe reads:** `getVisibleChunkIfPresent`,
`getUpdatingChunkIfPresent`, `getChunks`, `size`, `getPoiManager`,
`getStorageName`, `getPlayersCloseForSpawning`,
`anyPlayerCloseEnoughForSpawning`, `getPlayers`, `getChunkToSend`,
`hasWork`, `getTickingGenerated`, `getChunkDebugData`.

**Any-thread safe writes:** `holders.addTicket` / `holders.removeTicket`
call-sites are safe (holder creation is `computeIfAbsent`); caller must
resolve the owning region under the regionizer read lock (or accept
eventual-consistency reroute, m9-contracts.md §2.2). The facade holds
the read lock inside `regionIdFor(ChunkPos)` just long enough to enqueue.

**Owning region worker ONLY:** `applyStep` continuation, `scheduleUnload`
body, `save(ChunkAccess)`, `scheduleChunkLoad` body, entity-tracker
updates for entities owned by the region, and every `NewChunkHolder`
mutation per m9-contracts.md §1.3.

**Main thread ONLY (transitional, 4.1b):** `tick()` entity-tracker loop,
`move`, `addEntity`, `removeEntity`, `updatePlayerStatus`,
`setServerViewDistance`. Per-region sharding is a follow-up cleanup
once the holder path is quiet.

**Prohibited threading anti-patterns** (M9-blocking bugs):

1. NO `.join()` / `.get()` on any `CompletableFuture` from any facade
   method. Consumers of the three future gates chain via `thenAccept`.
2. NO `synchronized (obj)` on any monitor that another region's worker
   could also take. Permitted monitors: (a) `NewChunkHolder.blocksToBroadcast`,
   (b) `sectionLightChanged` (both single-writer + drainer swap per
   m9-contracts.md §1.1), (c) private facade-local `Object` monitors on
   main-thread-only state.
3. NO `mainThreadExecutor.managedBlock` — the field itself is deleted (§8).
4. NO `Thread.sleep`. Ever.

---

## 6. NeoForge event fires to preserve

| Event | Vanilla site | Fire moment under MultiForge |
|---|---|---|
| `EventHooks.fireChunkTicketLevelUpdated(level, pos, oldLevel, newLevel, holder)` | `ChunkMap.java:398` | In `updateChunkScheduling`, **after** `holders.createHolder` and `addTicket/removeTicket` return but **before** the holder's `pendingFullLoadUpdate` drains. Preserves Vanilla's "level transition committed, promotion not yet observable" ordering. |
| `ChunkEvent.Unload` | `ChunkMap.java:522` | In the region-worker path invoked by `onHolderCrossedThreshold(holder, INACCESSIBLE)`. Fires **after** `LevelChunk.setLoaded(false)` (`:521`) and **before** `save(chunkaccess)` (`:525`). |
| `ChunkEvent.Load` | Vanilla fires in `LevelChunk.setLoaded(true)`, not in ChunkMap directly | Ensured by keeping `LevelChunk.setLoaded(true)` invocation in the region-worker's promotion path when crossing to BORDER. |
| `ChunkDataEvent.Save` | `ChunkMap.java:769` | Inside `save(ChunkAccess)`, unchanged. Runs on region worker after `ChunkSerializer.write`. |
| `EventHooks.fireChunkWatch(player, chunk, level)` | `ChunkMap.java:831` | Inside `markChunkPendingToSend(player, chunk)`. Unchanged fire site; also writes `holder.addPlayerWatching(player)` in the same call. |
| `EventHooks.fireChunkUnWatch(player, pos, level)` | `ChunkMap.java:835` | Inside `dropChunk(player, pos)`. Unchanged fire site; also `holder.removePlayerWatching(player)`. |
| `CommonHooks.onChunkUnload(poiManager, chunkAccess)` | `ChunkMap.java:518` | In `scheduleUnload`, **before** `LevelChunk.setLoaded(false)`. Runs on region worker. |

The fire order relative to ticket transitions is part of the facade's
public contract — mods observe `ChunkTicketLevelUpdatedEvent` before
`ChunkEvent.Unload` for a demote to INACCESSIBLE, same as Vanilla.

---

## 7. Fields carried over unchanged

| Field (`ChunkMap.java:line`) | Justification |
|---|---|
| `level` (`:120`) | Every method needs it; `PlayerProvider` and `GeneratingChunkMap` callers reference it. |
| `poiManager` (`:126`) | Delegated by `getPoiManager()`. |
| `progressListener` (`:132`), `chunkStatusListener` (`:133`) | Fire from `applyStep` / `onFullChunkStatusChange`. |
| `randomState` (`:123`), `chunkGeneratorState` (`:124`) | Passed to worldgen; behaviour-transparent. |
| `overworldDataStorage` (`:125`) | Read by `upgradeChunkTag`. |
| `worldGenContext` (`:143`) | Passed to `ChunkStep.apply` from `applyStep`. Vanilla constructs with `mainThreadMailbox`; **replace with** a `ProcessorHandle` shim that routes into `tasks.scheduleChunkTask(worldRef, pos, r, NORMAL)`. |
| `storageName` (`:136`), `tickingGenerated` (`:135`) | Debug output / promotion counter. |

---

## 8. Fields DELETED and their replacements

| Vanilla field (`ChunkMap.java:line`) | Replaced by |
|---|---|
| `updatingChunkMap` / `visibleChunkMap` (`:116-117`) | `ChunkHolderManager.byChunk` (`ChunkHolderManager.java:44`), viewed through `ChunkHolderShim`. |
| `pendingUnloads` (`:118`), `unloadQueue` (`:141`) | Deleted. Unload runs synchronously on the region worker inside `scheduleUnload`; `saveSyncFuture` (`NewChunkHolder.java:217`) is the gate. |
| `toDrop` (`:127`) | Per-region `HolderManagerRegionData.autoSaveQueue` (`HolderManagerRegionData.java:36`) for the demote path. |
| `modified` (`:128`) | Deleted — no `promoteChunkMap` staging. |
| `queueSorter` (`:129`), `worldgenMailbox` / `mainThreadMailbox` (`:130-131`) | `ChunkTaskScheduler` per-region priority routing (`ChunkTaskScheduler.java:75`); `scheduleOnMainThreadMailbox` routes into the same call at NORMAL priority. |
| `mainThreadExecutor` (`:122`) | Deleted from field set. Constructor param stays for API compat (`ChunkMap.java:145-159`) but the stored field is unused; the `WorldGenContext` construction that consumed it is replaced by the region-routing `ProcessorHandle` shim. |
| `chunkTypeCache` (`:139`) | Deleted; type derived from `holder.currentChunk` at query time. |
| `chunkSaveCooldowns` (`:140`) | Per-region cooldown map added to `HolderManagerRegionData` (additive; no contract change). |
| `pendingGenerationTasks` (`:119`) | Deleted; tasks dispatched into `ChunkTaskScheduler` at `scheduleGenerationTask` time. |

---

## 9. Migration order for Phase 4.1b

Ordered list of methods to implement first through last. Each step is
independently testable.

1. **Constructor + carried-over fields (§7).** Wire `holders`, `tasks`,
   `host`, `distanceManager` (fake DistanceManager permitted; real 4.2b
   slots in).
2. **`getVisibleChunkIfPresent` / `getUpdatingChunkIfPresent` / `size` /
   `getChunks`.** Establishes the `ChunkHolderShim` shape.
3. **`ChunkHolderShim`** (nested static class). Delegates every state
   getter to `NewChunkHolder` fields per §3.1.
4. **`updateChunkScheduling`.** Single mutation choke point. All
   ticket-transition tests hang off this.
5. **`getChunkQueueLevel`, `getChunkDebugData`, `hasWork`, `dumpChunks`,
   `promoteChunkMap`.** Observability + no-op cleanup.
6. **`prepareTickingChunk`, `prepareAccessibleChunk`,
   `prepareEntityTickingChunk`.** Return shadow futures. First
   integration-level test: a holder crossing BORDER completes
   `fullChunkFuture`.
7. **`acquireGeneration`, `releaseGeneration`, `applyStep`,
   `scheduleGenerationTask`, `runGenerationTasks`, `scheduleChunkLoad`.**
   Full generation pipeline. Blocks on Phase 4.7 (ChunkGenerationTask
   patch) — implement stubs first, wire real once 4.7 lands.
8. **Player + entity tracker (`move`, `addEntity`, `removeEntity`,
   `broadcast`, `broadcastAndSend`, `setServerViewDistance`,
   `updateChunkTracking`, `updatePlayerStatus`, `TrackedEntity`).**
   Main-thread body reused; new `holder.playersWatching` writes threaded
   through `markChunkPendingToSend` / `dropChunk`.
9. **Save path (`saveAllChunks`, `save`, `saveChunkIfNeeded`,
   `scheduleUnload`).** Blocks on Phase 3 (MCA I/O) for the write side
   to be non-blocking on the region worker.
10. **`tick(BooleanSupplier)`, `tick()`, `close()`.** Wire POI tick and
    entity-tracker tick; delete `processUnloads`; `close` walks
    `RegionShutdownCoordinator`.
11. **NEW methods (`holders()`, `tasks()`, `regionIdFor`,
    `onHolderCrossedThreshold`).** Small; land alongside step 3.

Test coverage target: 90%+ line coverage per plan task 4.1d.

---

## 10. Rebase-drift risk assessment

Sections of Vanilla ChunkMap most at risk in a 1.21.x point release,
ordered by frequency:

- **Player-tracking math** (`ChunkMap.java:961-1068`,
  `updatePlayerStatus`, `updatePlayerPos`, `updateChunkTracking`,
  `applyChunkTrackingView`). Vanilla frequently tweaks the tracking-view
  algorithm; NeoForge occasionally adds hunks (see the "PATCH 1.20.2"
  comment at `:1034`). A signature change in `ChunkTrackingView.difference`
  is a fast-signal breakage.
- **Save/unload ordering** (`ChunkMap.java:419-541`). Vanilla shifts
  `wasAccessibleSinceLastSave` / `refreshAccessibility` / `isReadyForSaving`
  order between releases; the facade's `AutoSaveRunner` binding must
  preserve "save-before-unload" on every rebase.
- **`applyStep` / `WorldGenContext`** (`ChunkMap.java:204, 623-652`).
  Vanilla changes `WorldGenContext`'s constructor between releases (new
  fields for structure lookup, etc.). Keep the `tasks.scheduleChunkTask`
  bridge outside `WorldGenContext` itself so a Vanilla constructor change
  breaks only one line.
- **NeoForge event fire sites** (§6). NeoForge occasionally reorders
  fires within a method. The facade's Javadoc lists the seven fire sites
  by line number so drift is auditable.
- **`prepareTickingChunk` / `prepareAccessibleChunk` /
  `prepareEntityTickingChunk`** (`:676, :709, :364`). The facade returns
  the shadow's future directly, so a Vanilla body change is invisible —
  but if Vanilla adds a new preparation step, the equivalent must land
  on `NewChunkHolder`'s promotion path (Phase 2 additive field).
  Cheap-detect via compile-error on the shadow contract.

**Stable seams** (safe to bind against): `GeneratingChunkMap` interface,
`ChunkHolder.PlayerProvider` interface, `ChunkStorage` superclass,
`ChunkStatus` / `ChunkStep` / `ChunkPyramid` (reused verbatim),
`FullChunkStatus` (UNCHANGED), `ChunkLevel.byStatus` (UNCHANGED).

**Rebase cadence:** expect a half-day per NeoForge tag bump for the
facade, front-loaded on player-tracking and save/unload. Heavy MultiForge
logic sits behind the facade in `ChunkHolderManager` / `ChunkTaskScheduler`
/ `AutoSaveRunner` — those never touch `net.minecraft.*` and therefore
never rebase-drift.
