# Shared-state audit (Minecraft 26.1.2)

Why this exists: a block change on a region worker reached
`ChunkMap.chunksToEagerlySave`, a plain set the server thread iterates, through
`LevelChunk.markUnsaved`'s listener. It crashed the strict swarm, and the first
audit had missed it. This audit goes field by field.

## Method and limits

- **Scope:** every field of `ServerLevel`, `ChunkMap`, `ServerChunkCache`,
  `LevelChunk`, `ChunkHolder`, `Level` and 50-odd direct helpers, listed from the
  compiled classes, keeping non-final fields and mutable collections:
  **313 fields**.
- **Classification:** five independent code reviews, each tracing reads and writes
  from region-phase block, entity and block-entity ticking. Every `unsafe` entry was
  then checked against the code before it was fixed.
- **Limits:** this is code reading, not proof. It covers these classes and not all
  of Minecraft or any mod. `owned`, `locked` and `deferred` entries were not each
  re-verified by hand. Tests pin the fixes listed below, not every row.

Classes: **owned** (one thread or region, or immutable), **locked** (a lock or
concurrent structure), **deferred** (rerouted to the owner or the barrier),
**benign-race** (a race whose worst outcome is stated as harmless), **unsafe**.

## Result

| | owned | locked | deferred | benign-race | unsafe |
|---|---|---|---|---|---|
| As reviewed | 176 | 54 | 23 | 18 | 42 |
| After the fixes | 182 | 59 | 50 | 20 | 2 |

40 unsafe fields were resolved; 2 remain, listed below.

## Root causes fixed

| Cause | Fix | Regression test |
|---|---|---|
| A worker's `markUnsaved` wrote the eager-save set | Handoff queue | `workerUnsavedMarkGoesThroughTheHandoff` |
| The barrier pump promoted a chunk to ticking while its owner ticked it | Promotion waits for the barrier | None dedicated; covered by the suite, stress and swarm |
| A foreign `getBlockEntity` removed, promoted or created block entities | Foreign reads only read | `foreignBlockEntityReadDoesNotMutate` |
| A spectator's tick ran `ChunkMap.move` on a worker | Deferred to the barrier | `workerPlayerChunkMoveWaitsForTheBarrier` |
| A kick during the region phase rewrote player and tracker maps | Deferred to the barrier | None dedicated |
| Portal destinations built platforms in an idle level from several regions | Deferred to the barrier | None dedicated |
| `subTickCount` lost updates | Atomic | `subTickOrdersAreUniqueAcrossThreads` |
| `isHandlingTick` was false in a region's block phase (changes piston behaviour) | Per-thread flag | `regionBlockTickCountsAsHandlingATick` |
| Random sequences were shared without a lock | Leaf lock | `randomSequenceDrawsAreNotLostOrRepeated` |
| Block-snapshot capture was level-wide | Per worker | `blockCaptureStaysOnItsWorker` |
| A POI filter could wait on a chunk inside the POI monitor (deadlock) | Filter runs outside it | `poiFilterRunsOutsideTheMonitor` |
| Dragon crystal callbacks ran on the crystal's worker | Deferred to the barrier | None dedicated; scenario x3 |
| Capability listeners were unsynchronized | Locked; listeners run outside | None dedicated |
| Effects changed on an entity in another region (ATM 10 crash) | `CrossRegionEffects` | `effectsOnAnEntityInAnotherRegionAreDeferred` |
| Health, absorption, XP and exhaustion on a foreign player | `CrossRegionEffects` | `playerStateChangesFromAnotherRegionAreDeferred` |

## Still unsafe

- `BlockEntity.customPersistentData` and `attachmentTypesToSync`: only a mod reaching
  into another region's block entity.
- A mod writing the public `Level.captureBlockSnapshots` field directly on a worker:
  that capture is not seen, since workers use their own. NeoForge's own path is fixed.
- `PoiManager.take` still runs its filter inside the monitor, because the take must be
  atomic. Vanilla's filter builds a path without waiting on a chunk load, but it can
  hold the monitor for longer than a leaf lock should.
- Foreign reads of `PalettedContainer` are unsynchronized: fine on x86, not verified on ARM.
- Game events assume regions are at least one empty section apart. A mod's very large
  event radius, or a very small `region.size`, can cross that.

## Mutators a block entity calls on a foreign entity

This is the class behind the ATM 10 crash, where Pylons changed a player's effects
from another region.

| Mutator | Across regions | Status |
|---|---|---|
| `addEffect`, `forceAddEffect`, `removeEffect`, `removeEffectNoUpdate`, `removeAllEffects` | Corrupts effect and attribute maps | **Fixed**, tested |
| `heal`, `setHealth`, `setAbsorptionAmount` | Lost updates | **Fixed**, tested |
| `giveExperiencePoints`, `giveExperienceLevels`, `causeFoodExhaustion` | Lost updates | **Fixed**, tested |
| `teleportTo`, dimension changes | Handled | Already deferred |
| `hurtServer` | **Unsafe**: runs combat, death and drops on the wrong worker | Not fixed: overrides mutate before `super`, and callers use the result |
| `Inventory.add`, `setItem`, `removeItem`, `Player.addItem` | **Unsafe**: races the owner's inventory | Not fixed: callers need the result to avoid losing or duplicating items |
| `AttributeInstance` modifiers, `setBaseValue` | **Unsafe**: the same dirty-set corruption as the ATM 10 crash | Not fixed: the instance does not know its entity, so this needs a design |
| `FoodData` direct setters | **Unsafe**: lost updates | Not fixed: `FoodData` does not know its player |
| `Abilities` fields | **Unsafe**: plain public fields, stale reads | Not fixed: direct field writes cannot be guarded |
| `setItemSlot` | **Unsafe**: equipment and attribute changes | Not fixed |
| `knockback`, `setDeltaMovement` | Race on movement | Not fixed: worst case is a lost push |

## Every field

| Field | Reviewed | Now | Evidence or fix |
|---|---|---|---|
| `server.level.ServerLevel#players` | unsafe | deferred | disconnect deferred to the barrier; regions only read |
| `server.level.ServerLevel#chunkSource` | owned | owned | Final reference, assigned once in constructor: ServerLevel.java:303. |
| `server.level.ServerLevel#entityTickList` | locked | locked | All methods synchronized on EntityTickList: EntityTickList.java:33-46. |
| `server.level.ServerLevel#environmentAttributes` | benign-race | benign-race | Workers refill cache after invalidate ServerLevel.java:530; worst: next tick's server-thread reads are one tick stale. |
| `server.level.ServerLevel#noSave` | owned | owned | Written MinecraftServer.java:2176, read ChunkMap.java:480; no worker access found. |
| `server.level.ServerLevel#emptyTime` | benign-race | benign-race | Workers only write 0 (Entity.java:3046); read and incremented after barrier. Worst: none. |
| `server.level.ServerLevel#pathTypesByPosCache` | benign-race | benign-race | Immutable entries, single reference writes: PathTypeCache.java:33-42. Worst: colliding slot dropped, path type recomputed. |
| `server.level.ServerLevel#navigatingMobs` | locked | locked | ConcurrentHashMap key set: ServerLevel.java:215. |
| `server.level.ServerLevel#isUpdatingNavigations` | owned | owned | Written only when on server thread, never read: ServerLevel.java:1459-1469. |
| `server.level.ServerLevel#mfNavigatingIndexed` | owned | owned | Volatile, server thread writes around the barrier: $A/glue/multiforge/neoforge/RegionizedTickCoordinator.java:143-147; workers only read. |
| `server.level.ServerLevel#mfRegionNavigatingMobs` | owned | owned | Built, then volatile-published before workers start; read-only during phase: ServerLevel.java:593,614. |
| `server.level.ServerLevel#mfEntityTick` | owned | owned | Volatile; set before phase, cleared after barrier: ServerLevel.java:541,668. |
| `server.level.ServerLevel#mfPartSnapshot` | locked | locked | Volatile copy-on-write, rebuilt under dragonParts monitor: ServerLevel.java:684-685. |
| `server.level.ServerLevel#mfRegionRunsBlockTicks` | owned | owned | Written before pool submission, workers only read: ServerLevel.java:432,691. |
| `server.level.ServerLevel#mfRegionRunsBlockEvents` | owned | owned | Written before pool submission, workers only read: ServerLevel.java:433,715. |
| `server.level.ServerLevel#mfRegionRunsEntities` | owned | owned | Written before pool submission, workers only read: ServerLevel.java:467,642. |
| `server.level.ServerLevel#mfRegionizerCache` | benign-race | benign-race | Volatile immutable record, generation-checked: $A/glue/multiforge/neoforge/RegionizedTickCoordinator.java:210-215. Worst: one redundant regionizer lookup. |
| `server.level.ServerLevel#blockEvents` | deferred | deferred | REGION callers rerouted to per-region queues before the shared add: ServerLevel.java:1566-1576. |
| `server.level.ServerLevel#blockEventsToReschedule` | owned | owned | Used only in runBlockEvents, called from server-thread tick: ServerLevel.java:453,1580-1609. |
| `server.level.ServerLevel#handlingTick` | unsafe | owned | per-thread flag for the region block phase (`mfSetRegionHandlingTick`) |
| `server.level.ServerLevel#customSpawners` | owned | owned | Set in constructor, iterated from server-thread tickChunks: ServerChunkCache.java:468. |
| `server.level.ServerLevel#dragonFight` | unsafe | deferred | crystal callback deferred to the barrier |
| `server.level.ServerLevel#dragonParts` | locked | locked | Int2ObjectMaps.synchronize; iteration holds its monitor: ServerLevel.java:254,684. |
| `server.level.ServerLevel#capListenerHolder` | unsafe | locked | collections locked; listeners run outside the lock |
| `server.level.ServerChunkCache$MainThreadExecutor#this$0` | owned | owned | Synthetic final outer reference, immutable after construction: ServerChunkCache.java:804. |
| `server.level.ServerChunkCache$MfRegionChunkTicks#spawning` | owned | owned | Filled pre-phase, handed to one worker by ConcurrentHashMap.remove: ServerChunkCache.java:539,606-609. |
| `server.level.ServerChunkCache$MfRegionChunkTicks#ticking` | owned | owned | Filled pre-phase, handed to one worker by ConcurrentHashMap.remove: ServerChunkCache.java:585,606,616. |
| `server.level.ServerChunkCache$MfRegionChunkTicks#spawnChunks` | owned | owned | Written and read only on server thread before phase: ServerChunkCache.java:566-568. |
| `server.level.ServerChunkCache$MfRegionChunkTicks#spawnState` | owned | owned | One per region; its worker, then server thread after barrier: ServerChunkCache.java:567,590. |
| `server.level.ServerChunkCache$MfRegionChunkTicks#categories` | owned | owned | Written pre-phase, read by the owning worker: ServerChunkCache.java:569,590. |
| `server.level.ServerChunkCache#mainThreadProcessor` | locked | locked | Final reference; worker submissions use ConcurrentLinkedQueue: BlockableEventLoop.java:32,105. |
| `server.level.ServerChunkCache#chunkMap` | owned | owned | Final reference assigned in constructor: ServerChunkCache.java:106. |
| `server.level.ServerChunkCache#lastInhabitedUpdate` | owned | owned | Only server-thread tickChunks touches it: ServerChunkCache.java:388-389. |
| `server.level.ServerChunkCache#spawnEnemies` | owned | owned | Set via MinecraftServer.java:1355; read only in server-thread tickChunks. |
| `server.level.ServerChunkCache#lastChunkPos` | owned | owned | Worker getChunk branch bypasses it; accesses on server thread: ServerChunkCache.java:149,182,260. |
| `server.level.ServerChunkCache#lastChunkStatus` | owned | owned | Worker getChunk branch bypasses it; accesses on server thread: ServerChunkCache.java:149,182,261. |
| `server.level.ServerChunkCache#lastChunk` | owned | owned | Worker getChunk branch bypasses it; accesses on server thread: ServerChunkCache.java:149,183,262. |
| `server.level.ServerChunkCache#spawningChunks` | owned | owned | Filled and cleared inside server-thread tickChunks: ServerChunkCache.java:437-455. |
| `server.level.ServerChunkCache#chunkHoldersToBroadcast` | locked | locked | synchronizedSet for worker adds; unlocked iteration runs only outside worker window: ServerChunkCache.java:76,407,676. |
| `server.level.ServerChunkCache#lastSpawnState` | owned | owned | Written in tickChunks, read by debug report: ServerChunkCache.java:426; no worker reader found. |
| `server.level.ServerChunkCache#mfSpawnChunks` | owned | owned | Passed only to server-thread credit computation: ServerChunkCache.java:543. |
| `server.level.ServerChunkCache#mfSpawnChunkCredits` | owned | owned | Server-thread use; accessor has no snapshot callers: ServerChunkCache.java:543-575. |
| `server.level.ServerChunkCache#mfRandomTickSpeed` | owned | owned | Written pre-phase, workers only read: ServerChunkCache.java:462,596. |
| `server.level.ServerChunkCache#mfInhabitedDelta` | owned | owned | Written pre-phase, workers only read: ServerChunkCache.java:532,590. |
| `world.level.Level#DEFAULT_EXPLOSION_BLOCK_PARTICLES` | owned | owned | Static final, built once, only passed as an argument: Level.java:104. |
| `world.level.Level#blockEntityTickers` | benign-race | benign-race | Writes locked; watchdog iterates unlocked ServerLevel.java:2037. Worst: blank or wrong hang-report statistic (exception caught :2060). |
| `world.level.Level#pendingBlockEntityTickers` | locked | locked | freshBlockEntities monitor; unlocked branch runs only without regions: Level.java:518-519,588-592,604-606. |
| `world.level.Level#tickingBlockEntities` | locked | locked | Every read and write holds freshBlockEntities monitor: Level.java:519,554,569,593,622. |
| `world.level.Level#skyDarken` | owned | owned | Written from server-thread tick, workers only read: Level.java:812. |
| `world.level.Level#randValue` | owned | owned | Only the level thread touches it; others use per-thread random: Level.java:1153-1157. |
| `world.level.Level#oRainLevel` | owned | owned | Written in server-thread weather step, workers read: ServerLevel.java:1035. |
| `world.level.Level#rainLevel` | owned | owned | Written in server-thread weather step, workers read: ServerLevel.java:1035-1042. |
| `world.level.Level#oThunderLevel` | owned | owned | Written in server-thread weather step, workers read: ServerLevel.java:1027. |
| `world.level.Level#thunderLevel` | owned | owned | Written in server-thread weather step, workers read: ServerLevel.java:1027-1033. |
| `world.level.Level#subTickCount` | unsafe | locked | atomic `VarHandle` increment |
| `world.level.Level#restoringBlockSnapshots` | unsafe | owned | per-worker block capture (`Level.mfCapturingBlocks`) |
| `world.level.Level#captureBlockSnapshots` | unsafe | owned | per-worker block capture (`Level.mfCapturingBlocks`) |
| `world.level.Level#capturedBlockSnapshots` | unsafe | owned | per-worker block capture (`Level.mfCapturingBlocks`) |
| `world.level.Level#freshBlockEntities` | locked | locked | All accesses hold its own monitor: Level.java:531-533,553-557,564-571. |
| `world.level.Level#pendingFreshBlockEntities` | locked | locked | All accesses hold freshBlockEntities monitor: Level.java:533,555,565-567. |
| `world.level.Level#mfWorldRef` | benign-race | benign-race | Unsynchronized lazy init, compared by equals: Level.java:501-504. Worst: duplicate WorldRef; class source absent. |
| `world.level.Level#NEIGHBOR_UPDATE_LIST` | owned | owned | Private static array, initialized once, only iterated: Level.java:1085,1089. |
| `world.level.Level#maxEntityRadius` | unsafe | locked | volatile, with a synchronized raise |
| `server.level.ChunkMap$DistanceManager#this$0` | owned | owned | Final outer reference set at construction, ChunkMap.java:1553. |
| `server.level.ChunkMap$TrackedEntity#lastSectionPos` | owned | owned | Written only in ChunkMap.tick:1284, which runs in ServerLevel.tick before driveRegions (RegionizedTickCoordinator:125,145). |
| `server.level.ChunkMap$TrackedEntity#seenBy` | unsafe | deferred | spectator `ServerChunkCache.move` deferred to the barrier; disconnect deferred to the barrier |
| `server.level.ChunkMap$TrackedEntity#this$0` | owned | owned | Final outer reference set at construction, ChunkMap.java:1581. |
| `server.level.ChunkMap#UNLOADED_CHUNK_LIST_RESULT` | owned | owned | Immutable static error result, ChunkMap.java:112. |
| `server.level.ChunkMap#UNLOADED_CHUNK_LIST_FUTURE` | owned | owned | Static already-completed future, never reassigned, ChunkMap.java:113. |
| `server.level.ChunkMap#updatingChunkMap` | owned | owned | Server/pump only; worker ticket path skips LoadingChunkTracker because PLAYER_SIMULATION lacks FLAG_LOADING (TicketType:25, TicketStorage:172). |
| `server.level.ChunkMap#visibleChunkMap` | locked | locked | Volatile copy-on-write: clone published at ChunkMap:582; workers only call get at :283. |
| `server.level.ChunkMap#pendingUnloads` | owned | owned | Touched by updateChunkScheduling:408 and processUnloads:505 on the server thread; no worker path. |
| `server.level.ChunkMap#pendingGenerationTasks` | owned | owned | Added at ChunkMap:690 and drained at :706 from server-thread distance updates; worker getChunk hops via ServerChunkCache:172. |
| `server.level.ChunkMap#toDrop` | owned | owned | Server/pump only (ChunkMap:401,500); isChunkToRemove:1559 is reached only through LoadingChunkTracker on that thread. |
| `server.level.ChunkMap#modified` | owned | owned | Written at ChunkMap:416,506,583, all in server-thread distance updates or unload processing. |
| `server.level.ChunkMap#chunkStatusListener` | owned | owned | Final, assigned in constructor at ChunkMap.java:211. |
| `server.level.ChunkMap#distanceManager` | owned | owned | Final reference assigned at ChunkMap.java:216; the object's own fields are classified separately. |
| `server.level.ChunkMap#playerMap` | unsafe | deferred | disconnect deferred to the barrier |
| `server.level.ChunkMap#entityMap` | unsafe | deferred | disconnect deferred to the barrier |
| `server.level.ChunkMap#mfVerifyCounter` | benign-race | benign-race | Unsynchronized ++ at ChunkMap:1374 via spectator move, only with verify enabled. Worst: a diagnostic check runs at the wrong cadence. |
| `server.level.ChunkMap#mfReconcileCursor` | owned | owned | Only mfReconcileNext:1410-1411, called from ChunkMap.tick:1302 before the region phase. |
| `server.level.ChunkMap#chunkTypeCache` | owned | owned | Accessed from main-executor load callbacks (ChunkMap:601,612), save:817 and unload:564, all on the server thread. |
| `server.level.ChunkMap#nextChunkSaveTime` | owned | owned | Accessed at ChunkMap:466,568,767,774 from server-thread save and unload paths only. |
| `server.level.ChunkMap#chunksToEagerlySave` | unsafe | deferred | workers queue through `mfUnsavedFromWorkers` (the reviewer read a snapshot without this fix) |
| `server.level.ChunkMap#mfUnsavedFromWorkers` | locked | locked | ConcurrentLinkedQueue (ChunkMap:169), but nothing ever adds to it; only contains:239 and poll:526 exist. |
| `server.level.ChunkMap#unloadQueue` | locked | locked | ConcurrentLinkedQueue at ChunkMap:170; added by future callback :571, polled on server thread :514. |
| `server.level.ChunkMap#serverViewDistance` | owned | owned | Workers only read (ChunkMap:862); writes at :852 come from server-thread commands or RPC submit (Connection:227), not pumped. |
| `server.level.ChunkHolder#fullChunkFuture` | locked | locked | Volatile field holding CompletableFuture (ChunkHolder:36); pump writes :281,288, workers read through :83. |
| `server.level.ChunkHolder#tickingChunkFuture` | locked | locked | Volatile field holding CompletableFuture (ChunkHolder:37); pump writes :294,301, workers read through :75,87. |
| `server.level.ChunkHolder#entityTickingChunkFuture` | locked | locked | Volatile field holding CompletableFuture (ChunkHolder:38); written at :311,318 on server/pump. |
| `server.level.ChunkHolder#oldTicketLevel` | owned | owned | Only updateFutures uses it after construction (ChunkHolder:275,326), on server/pump. |
| `server.level.ChunkHolder#ticketLevel` | benign-race | benign-race | Pump writes plain int (ChunkHolder:256); workers read via hasChunk (ServerChunkCache:306). Worst: chunk status stale across one promotion or demotion. |
| `server.level.ChunkHolder#queueLevel` | benign-race | benign-race | Plain int set from worldgen and light dispatchers (ChunkHolder:252, ChunkTaskDispatcher:44). Worst: a chunk task runs at stale priority. |
| `server.level.ChunkHolder#hasChangedSections` | owned | owned | Per-chunk: owner writes at ChunkHolder:132 behind Level.setBlock guard; broadcast clears :215 only outside workers (ServerChunkCache:626). |
| `server.level.ChunkHolder#changedBlocksPerSection` | owned | owned | Per-chunk: owning region writes at ChunkHolder:134-137; broadcast reads :194 pre-phase or at barrier. |
| `server.level.ChunkHolder#blockChangedLightSectionFilter` | owned | owned | Written through onLightUpdate's main-executor task (ServerChunkCache:682, ChunkHolder:158); read at broadcast on server thread. |
| `server.level.ChunkHolder#skyChangedLightSectionFilter` | owned | owned | Written through onLightUpdate's main-executor task (ServerChunkCache:682, ChunkHolder:158); read at broadcast on server thread. |
| `server.level.ChunkHolder#onLevelChange` | owned | owned | Final, assigned in constructor at ChunkHolder.java:65. |
| `server.level.ChunkHolder#wasAccessibleSinceLastSave` | owned | owned | Accessed by updateFutures:279 and server-thread save paths (ChunkMap:445,759,772). |
| `server.level.ChunkHolder#pendingFullStateConfirmation` | owned | owned | Only scheduleFullChunkPromotion and demoteFullChunk (ChunkHolder:262-270), called from updateFutures on server/pump. |
| `server.level.ChunkHolder#sendSync` | benign-race | benign-race | EnderDragon:532 -> setDragonKilled:404 -> waitForLightBeforeSending:1523 -> ChunkHolder:100 unsynchronized. Worst: portal chunk sent before light settles, corrected later. |
| `server.level.ChunkHolder#saveSync` | owned | owned | Written via updateFutures and acquireGeneration on server thread (ChunkHolder:117, ChunkMap:651); no worker path. |
| `server.level.GenerationChunkHolder#CHUNK_STATUSES` | owned | owned | Static final, built once at GenerationChunkHolder:24 and only read afterwards. |
| `server.level.GenerationChunkHolder#highestAllowedStatus` | locked | locked | Volatile (GenerationChunkHolder:29); server/pump writes :82, workers read :229. |
| `server.level.GenerationChunkHolder#generationSaveSyncFuture` | locked | locked | Volatile (GenerationChunkHolder:34); written :237 on server, read :243 on worldgen; no worker path. |
| `server.level.GenerationChunkHolder#currentlyLoading` | owned | owned | Written on main executor (ChunkStatusTasks:193-206); read only in server-thread branches (ServerChunkCache:191,243); worker branch skips it. |
| `server.level.DistanceManager$FixedPlayerDistanceChunkTracker#chunks` | unsafe | deferred | spectator `ServerChunkCache.move` deferred to the barrier |
| `server.level.DistanceManager$PlayerTicketTracker#viewDistance` | owned | owned | Written at DistanceManager:275 from setServerViewDistance on server thread; read during server/pump updates. |
| `server.level.DistanceManager$PlayerTicketTracker#queueLevels` | locked | locked | Long2IntMaps.synchronize wrapper, DistanceManager.java:257. |
| `server.level.DistanceManager$PlayerTicketTracker#toUpdate` | owned | owned | Written by setLevel callback :265 and drained :304-323, reached only by DistanceManager.runAllUpdates on server/pump. |
| `server.level.DistanceManager#playersPerChunk` | unsafe | deferred | spectator `ServerChunkCache.move` deferred to the barrier |
| `server.level.DistanceManager#chunksToUpdateFutures` | owned | owned | Added by LoadingChunkTracker.setLevel:40 and drained at DistanceManager:76-84, both in server/pump runAllUpdates. |
| `server.level.DistanceManager#ticketsToRelease` | owned | owned | Added in main-executor task :285; drained in runAllUpdates :87-103 on server/pump. |
| `server.level.DistanceManager#simulationDistance` | owned | owned | Workers only read at :133; write :159 comes from server-thread settings path (ServerChunkCache:765). |
| `world.level.TicketStorage#tickets` | unsafe | deferred | spectator `ServerChunkCache.move` deferred to the barrier |
| `world.level.TicketStorage#deactivatedTickets` | owned | owned | Used at startup (MinecraftServer:565), shutdown (:682) and server-thread save encoding; no region path. |
| `world.level.TicketStorage#chunksWithForcedTickets` | deferred | deferred | FORCED changes only via setChunkForced, deferred by OwnershipGuard.deferSetChunkForced (ServerLevel:1829); worker read at OwnershipGuard:389 has no concurrent writer. |
| `world.level.TicketStorage#loadingChunkUpdatedListener` | owned | owned | Set once by LoadingChunkTracker constructor :14, then read only. |
| `world.level.TicketStorage#simulationChunkUpdatedListener` | owned | owned | Set once by SimulationChunkTracker constructor :16, then read only. |
| `world.level.TicketStorage#chunksWithForceNaturalSpawning` | unsafe | deferred | forced-chunk changes go through `OwnershipGuard.deferForcedChunk` |
| `server.level.ThreadedLevelLightEngine#lightTasks` | deferred | deferred | Worker checkBlock enqueues through ChunkTaskDispatcher.submit (:125); list mutated only on the light ConsecutiveExecutor (:126,:208). |
| `server.level.ThreadedLevelLightEngine#chunkMap` | owned | owned | Final, assigned in constructor at ThreadedLevelLightEngine.java:40. |
| `server.level.ServerEntity#lastSentYRot` | owned | owned | Post-construction writes only in sendChanges path (ServerEntity:125,201,250); sole caller ChunkMap.tick:1288 precedes regions. |
| `server.level.ServerEntity#lastSentXRot` | owned | owned | Post-construction writes only in sendChanges path (ServerEntity:126,202,251); sole caller ChunkMap.tick:1288 precedes regions. |
| `server.level.ServerEntity#lastSentYHeadRot` | owned | owned | Post-construction write only at ServerEntity:211 in sendChanges; sole caller ChunkMap.tick:1288 precedes regions. |
| `server.level.ServerEntity#lastSentMovement` | owned | owned | Post-construction write only at ServerEntity:174 in sendChanges; pairing reads on workers have no concurrent writer. |
| `server.level.ServerEntity#tickCount` | owned | owned | Only sendChanges and its minecart helper use it (ServerEntity:99,118,217,231), from ChunkMap.tick:1288. |
| `server.level.ServerEntity#teleportDelay` | owned | owned | Only sendChanges uses it (ServerEntity:135,148,152), from ChunkMap.tick:1288. |
| `server.level.ServerEntity#lastPassengers` | owned | owned | Only sendChanges uses it (ServerEntity:91-96), from ChunkMap.tick:1288. |
| `server.level.ServerEntity#wasRiding` | owned | owned | Only sendChanges uses it (ServerEntity:131,149,205), from ChunkMap.tick:1288. |
| `server.level.ServerEntity#wasOnGround` | owned | owned | After constructor, only sendChanges uses it (ServerEntity:150-151), from ChunkMap.tick:1288. |
| `server.level.ServerEntity#trackedDataValues` | owned | owned | Written at ServerEntity:343 from sendChanges pre-phase; worker pairing read at :278 has no concurrent writer. |
| `server.network.PlayerChunkSender#pendingChunks` | unsafe | deferred | spectator `ServerChunkCache.move` deferred to the barrier |
| `server.network.PlayerChunkSender#desiredChunksPerTick` | owned | owned | sendNextChunks runs after level ticks (MinecraftServer:1190); packet handler uses ensureRunningOnSameThread (ServerGamePacketListenerImpl:2178). |
| `server.network.PlayerChunkSender#batchQuota` | owned | owned | sendNextChunks runs after level ticks (MinecraftServer:1190); packet handler uses ensureRunningOnSameThread (ServerGamePacketListenerImpl:2178). |
| `server.network.PlayerChunkSender#unacknowledgedBatches` | owned | owned | sendNextChunks runs after level ticks (MinecraftServer:1190); packet handler uses ensureRunningOnSameThread (ServerGamePacketListenerImpl:2178). |
| `server.network.PlayerChunkSender#maxUnacknowledgedBatches` | owned | owned | sendNextChunks runs after level ticks (MinecraftServer:1190); packet handler uses ensureRunningOnSameThread (ServerGamePacketListenerImpl:2178). |
| `world.level.chunk.LevelChunk$BoundTickingBlockEntity#loggedInvalidBlockState` | owned | owned | Per-ticker flag, touched only inside tick() on the owning region: LevelChunk.java:786-788. |
| `world.level.chunk.LevelChunk$RebindableTickingBlockEntityWrapper#ticker` | benign-race | benign-race | Pump rebind LevelChunk.java:732 races tick:861. Worst: one stale tick, gated by isRemoved:776. |
| `world.level.chunk.LevelChunk#tickersInLevel` | unsafe | deferred | promotion at the barrier (`ChunkMap.prepareTickingChunk`) |
| `world.level.chunk.LevelChunk#loaded` | owned | owned | Set before FULL publishes (ChunkStatusTasks.java:198); cleared in unload queue, drained outside phase (ChunkMap.java:514,554). |
| `world.level.chunk.LevelChunk#fullStatus` | benign-race | benign-race | Supplier reads plain ChunkHolder.ticketLevel:256 while pump writes. Worst: block entity ticks or skips once (LevelChunk.java:429). |
| `world.level.chunk.LevelChunk#postLoad` | owned | owned | Run and cleared on server thread before FULL publishes: ChunkStatusTasks.java:194, LevelChunk.java:529-531. |
| `world.level.chunk.LevelChunk#gameEventListenerRegistrySections` | unsafe | deferred | promotion at the barrier (`ChunkMap.prepareTickingChunk`); regions are at least one empty section apart |
| `world.level.chunk.LevelChunk#unsavedListener` | unsafe | deferred | its callback now queues through `mfUnsavedFromWorkers` |
| `world.level.chunk.ChunkAccess$PackedTicks#blocks` | owned | owned | Fresh list per save (LevelChunkTicks.java:92), built by ChunkMap.save outside the region phase. |
| `world.level.chunk.ChunkAccess$PackedTicks#fluids` | owned | owned | Fresh list per save (LevelChunkTicks.java:92), built by ChunkMap.save outside the region phase. |
| `world.level.chunk.ChunkAccess#EMPTY_REFERENCE_SET` | owned | owned | Never mutated: returned read-only at ChunkAccess.java:223; writes use a new set at :228. |
| `world.level.chunk.ChunkAccess#postProcessing` | owned | owned | Only server thread reads or clears it (LevelChunk.java:583-603); LevelChunk inherits log-only markPosForPostprocessing (ChunkAccess.java:294). |
| `world.level.chunk.ChunkAccess#unsaved` | locked | locked | Volatile; check-then-clear tryMarkSaved:267 runs only in ChunkMap.save, outside phase. Duplicate listener firing is idempotent. |
| `world.level.chunk.ChunkAccess#isLightCorrect` | locked | locked | Volatile single-value flag: ChunkAccess.java:66,400; light thread writes at ThreadedLevelLightEngine.java:173. |
| `world.level.chunk.ChunkAccess#inhabitedTime` | benign-race | benign-race | Owner writes ServerChunkCache.java:476; foreign read ServerLevel.java:961. Worst: slightly stale local difficulty. |
| `world.level.chunk.ChunkAccess#carverBiomeSettings` | owned | owned | Worldgen only, on the chunk being generated: NoiseBasedChunkGenerator.java:318. |
| `world.level.chunk.ChunkAccess#noiseChunk` | owned | owned | Worldgen only, on the chunk being generated: NoiseBasedChunkGenerator.java:92,290,307,376. |
| `world.level.chunk.ChunkAccess#heightmaps` | benign-race | benign-race | EnumMap filled in constructor (LevelChunk.java:118-122); only lazy priming writes (ChunkAccess.java:189). Worst: duplicate Heightmap discarded. |
| `world.level.chunk.ChunkAccess#skyLightSources` | unsafe | deferred | promotion at the barrier (`ChunkMap.prepareTickingChunk`) |
| `world.level.chunk.ChunkAccess#structureStarts` | owned | owned | Written in worldgen and promotion (LevelChunk.java:155); read-only on a live LevelChunk. |
| `world.level.chunk.ChunkAccess#structuresRefences` | owned | owned | Written in worldgen and promotion (LevelChunk.java:156); read-only on a live LevelChunk. |
| `world.level.chunk.ChunkAccess#pendingBlockEntities` | unsafe | owned | foreign reads no longer touch it; promotion at the barrier (`ChunkMap.prepareTickingChunk`) |
| `world.level.chunk.ChunkAccess#blockEntities` | unsafe | locked | `ConcurrentHashMap`; foreign reads do not mutate |
| `world.level.chunk.ChunkAccess#sections` | owned | owned | Array filled in constructor, elements never reassigned: ChunkAccess.java:95-108. Section contents are audited separately. |
| `world.ticks.LevelTicks$MfRegionBatch#toRun` | owned | owned | Allocated per drain and confined to that worker: LevelTicks.java:249,278. |
| `world.ticks.LevelTicks$MfRegionBatch#toRunSet` | owned | owned | Allocated per drain; read through ThreadLocal mfActiveBatch: LevelTicks.java:277,435-437. |
| `world.ticks.LevelTicks#allContainers` | locked | locked | Every access holds mfLock: LevelTicks.java:52,67,84,214,234,305,510. |
| `world.ticks.LevelTicks#nextTickForContainer` | locked | locked | Every access holds mfLock: LevelTicks.java:52,67,148,234,305,373,476. |
| `world.ticks.LevelTicks#containersToTick` | owned | owned | Only Vanilla tick(), on server thread before regions run: LevelTicks.java:302-325,351,422. |
| `world.ticks.LevelTicks#toRunThisTick` | owned | owned | Written only in server-thread tick(); workers' willTickThisTick:444 sees it empty. |
| `world.ticks.LevelTicks#alreadyRunThisTick` | owned | owned | Only server-thread tick, gametest clearArea, and deferred clone command: LevelTicks.java:415,423,482,493. |
| `world.ticks.LevelTicks#toRunThisTickSet` | owned | owned | Filled only while toRunThisTick is nonempty (:444-445); workers read an empty set at :440. |
| `world.ticks.LevelTicks#mfDueTick` | locked | locked | Read and written under mfLock: LevelTicks.java:133,155,192. |
| `world.ticks.LevelTicks#mfDueByRegion` | locked | locked | Map accessed under mfLock (LevelTicks.java:156,173,192-193); bucket lists are read-only after publication. |
| `world.ticks.LevelTicks#mfLateDue` | locked | locked | Accessed under mfLock; workers copy it: LevelTicks.java:133,157,194. |
| `world.ticks.LevelTicks#mfRegionOf` | locked | locked | Written and read under mfLock: LevelTicks.java:172,195. |
| `world.ticks.LevelChunkTicks#tickQueue` | unsafe | deferred | promotion at the barrier (`ChunkMap.prepareTickingChunk`) |
| `world.ticks.LevelChunkTicks#pendingTicks` | owned | owned | Only unpack, pack and count, all on server thread: LevelChunkTicks.java:87-113. |
| `world.ticks.LevelChunkTicks#ticksPerPosition` | unsafe | deferred | promotion at the barrier (`ChunkMap.prepareTickingChunk`) |
| `world.ticks.LevelChunkTicks#onTickAdded` | owned | owned | Set before FULL publishes (LevelTicks.java:61); cleared at unload, outside phase (:72). |
| `world.level.redstone.CollectingNeighborUpdater$MultiNeighborUpdate#orientation` | owned | owned | Per-update object inside one thread's chain: CollectingNeighborUpdater.java:56,143. |
| `world.level.redstone.CollectingNeighborUpdater$MultiNeighborUpdate#idx` | owned | owned | Per-update object inside one thread's chain: CollectingNeighborUpdater.java:124,138. |
| `world.level.redstone.CollectingNeighborUpdater#stack` | owned | owned | Per-thread instance: PerThreadNeighborUpdater.java:48,64-82. |
| `world.level.redstone.CollectingNeighborUpdater#addedThisLayer` | owned | owned | Per-thread instance: PerThreadNeighborUpdater.java:48,64-82. |
| `world.level.redstone.CollectingNeighborUpdater#count` | owned | owned | Per-thread instance: PerThreadNeighborUpdater.java:48,64-82. |
| `world.level.redstone.CollectingNeighborUpdater#debugListener` | owned | owned | Set by server thread on its own chain (ServerLevel.java:522-525); worker instances keep null. |
| `world.level.chunk.LevelChunkSection$1BlockCounter#nonEmptyBlockCount` | owned | owned | Method-local counter: LevelChunkSection.java:156-158. |
| `world.level.chunk.LevelChunkSection$1BlockCounter#fluidCount` | owned | owned | Method-local counter: LevelChunkSection.java:156-159. |
| `world.level.chunk.LevelChunkSection$1BlockCounter#tickingBlockCount` | owned | owned | Method-local counter: LevelChunkSection.java:156-160. |
| `world.level.chunk.LevelChunkSection$1BlockCounter#tickingFluidCount` | owned | owned | Method-local counter: LevelChunkSection.java:156-161. |
| `world.level.chunk.LevelChunkSection#nonEmptyBlockCount` | unsafe | deferred | promotion at the barrier (`ChunkMap.prepareTickingChunk`); portal builds deferred |
| `world.level.chunk.LevelChunkSection#fluidCount` | unsafe | deferred | promotion at the barrier (`ChunkMap.prepareTickingChunk`); portal builds deferred |
| `world.level.chunk.LevelChunkSection#tickingBlockCount` | unsafe | deferred | promotion at the barrier (`ChunkMap.prepareTickingChunk`); portal builds deferred |
| `world.level.chunk.LevelChunkSection#tickingFluidCount` | unsafe | deferred | promotion at the barrier (`ChunkMap.prepareTickingChunk`); portal builds deferred |
| `world.level.chunk.LevelChunkSection#biomes` | owned | owned | Replaced only by worldgen or deferred fillbiome command: LevelChunkSection.java:218, FillBiomeCommand.java:140. |
| `world.level.chunk.PalettedContainer$Data#storage` | unsafe | benign-race | writes: promotion at the barrier (`ChunkMap.prepareTickingChunk`), portal builds deferred. A foreign read is unsynchronized: fine on x86, not verified on ARM |
| `world.level.chunk.PalettedContainer#data` | unsafe | benign-race | writes: promotion at the barrier (`ChunkMap.prepareTickingChunk`), portal builds deferred. A foreign read is unsynchronized: fine on x86, not verified on ARM |
| `world.level.levelgen.Heightmap#data` | unsafe | deferred | promotion at the barrier (`ChunkMap.prepareTickingChunk`); portal builds deferred |
| `world.level.gameevent.EuclideanGameEventListenerRegistry#listeners` | unsafe | deferred | promotion at the barrier (`ChunkMap.prepareTickingChunk`); regions are at least one empty section apart |
| `world.level.gameevent.EuclideanGameEventListenerRegistry#listenersToRemove` | unsafe | deferred | promotion at the barrier (`ChunkMap.prepareTickingChunk`); regions are at least one empty section apart |
| `world.level.gameevent.EuclideanGameEventListenerRegistry#listenersToAdd` | unsafe | deferred | promotion at the barrier (`ChunkMap.prepareTickingChunk`); regions are at least one empty section apart |
| `world.level.gameevent.EuclideanGameEventListenerRegistry#processing` | unsafe | deferred | promotion at the barrier (`ChunkMap.prepareTickingChunk`); regions are at least one empty section apart |
| `world.level.gameevent.EuclideanGameEventListenerRegistry#onEmptyAction` | owned | owned | Final, set in constructor (:29). Its callback's map is the gameEventListenerRegistrySections entry. |
| `world.level.block.entity.BlockEntity#level` | owned | owned | Set before publication or rewritten with the same Level: LevelChunk.java:454,630. |
| `world.level.block.entity.BlockEntity#remove` | unsafe | owned | foreign reads no longer act on it |
| `world.level.block.entity.BlockEntity#blockState` | benign-race | benign-race | Atomic reference write at BlockEntity.java:349; foreign read may be stale. Worst: one stale state. |
| `world.level.block.entity.BlockEntity#components` | owned | owned | Immutable map swapped by owner or deferred commands: BlockEntity.java:108,382,404; no foreign reader in Vanilla. |
| `world.level.block.entity.BlockEntity#customPersistentData` | unsafe | unsafe | only a mod reaching into another region's block entity; no Vanilla path |
| `world.level.block.entity.BlockEntity#attachmentTypesToSync` | unsafe | unsafe | only a mod reaching into another region's block entity; no Vanilla path |
| `world.level.entity.PersistentEntitySectionManager$Callback#currentSectionKey` | owned | owned | Per-entity; written unlocked at PersistentEntitySectionManager:601 by owner's onMove; cross-region remove and teleport rerouted (Entity:431, 2986, 3277). |
| `world.level.entity.PersistentEntitySectionManager$Callback#currentSection` | owned | owned | Per-entity; written at PersistentEntitySectionManager:600 by owner only; section contents separately guarded by mfLock (:586, :641). |
| `world.level.entity.PersistentEntitySectionManager#knownUuids` | locked | locked | mfLock at every access: PersistentEntitySectionManager:79, :368, :463, :516, :664. |
| `world.level.entity.PersistentEntitySectionManager#chunkVisibility` | locked | locked | mfLock: writes PersistentEntitySectionManager:202-205, reads :138, :403; EntitySectionStorage:103 reached only under mfLock. |
| `world.level.entity.PersistentEntitySectionManager#chunkLoadStatuses` | locked | locked | mfLock in phase (:415); unlocked :305 only via waitForEntities on packet path, MinecraftServer:1037, outside region phase. |
| `world.level.entity.PersistentEntitySectionManager#chunksToUnload` | locked | locked | mfLock: PersistentEntitySectionManager:203, :206 in updateChunkStatus; :295 under tick's lock at :312. |
| `world.level.entity.PersistentEntitySectionManager#loadingInbox` | locked | locked | ConcurrentLinkedQueue (PersistentEntitySectionManager:44); IO completion adds at :273, server thread polls at :300. |
| `world.level.entity.PersistentEntitySectionManager#mfTransitions` | locked | locked | Read and written only inside mfLock block, PersistentEntitySectionManager:198-231. |
| `world.level.entity.PersistentEntitySectionManager#mfTestHook` | locked | locked | Volatile reference (PersistentEntitySectionManager:57), read once at :232; GameTest hook only. |
| `world.level.entity.EntitySectionStorage#intialSectionVisibility` | owned | owned | Final, assigned at EntitySectionStorage:34; the map it references is read at :103 only under mfLock. |
| `world.level.entity.EntitySectionStorage#sections` | locked | locked | All manager calls hold mfLock; queries use LockingEntityGetter:167-175 locked path; streams terminate inside lock (PersistentEntitySectionManager:210, :249). |
| `world.level.entity.EntitySectionStorage#sectionIds` | locked | locked | Same mfLock coverage; subSet iterators at EntitySectionStorage:48, :74 are consumed before lock release. |
| `world.level.entity.EntitySection#storage` | locked | locked | Only reached under mfLock; matches buffered, consumers replay outside lock (LockingEntityGetter:150-156); getEntities streams terminate inside lock. |
| `world.level.entity.EntitySection#chunkStatus` | locked | locked | Written at EntitySection:70 via PersistentEntitySectionManager:211 under mfLock; reads at :587, :643 locked; transitions deferred in phase (:183). |
| `world.level.entity.EntityLookup#byId` | locked | locked | Writes under mfLock (PersistentEntitySectionManager:166, :174); worker getAll copies under lock (LockingEntityGetter:128-132); live view server-thread-only outside phase. |
| `world.level.entity.EntityLookup#byUuid` | locked | locked | Writes under mfLock (PersistentEntitySectionManager:166, :174); reads through LockingEntityGetter:119-122 locked path. |
| `server.level.ServerPlayerGameMode#level` | owned | owned | Written at ServerPlayerGameMode:433 via ServerPlayer:1175; worker teleports deferred at ServerPlayer:1137; otherwise read only. |
| `server.level.ServerPlayerGameMode#gameModeForPlayer` | owned | owned | Writes (ServerPlayerGameMode:81) from packets, RPC, or deferred commands on server thread; workers only read during phase. |
| `server.level.ServerPlayerGameMode#previousGameModeForPlayer` | owned | owned | Written at ServerPlayerGameMode:80 only on the same server-thread paths outside region phase. |
| `server.level.ServerPlayerGameMode#isDestroyingBlock` | owned | owned | Per-player; owner worker tick (:128, via ServerPlayer:604) and packets (:215) at MinecraftServer:1037 never overlap; pump excludes packets. |
| `server.level.ServerPlayerGameMode#destroyProgressStart` | owned | owned | Per-player; written by packet handler at ServerPlayerGameMode:188, read by owner tick at :130, separated by barrier. |
| `server.level.ServerPlayerGameMode#destroyPos` | owned | owned | Per-player; written by packet handler at ServerPlayerGameMode:216, read by owner tick at :124, separated by barrier. |
| `server.level.ServerPlayerGameMode#gameTicks` | owned | owned | Per-player; incremented in owner tick at ServerPlayerGameMode:111; packet reads at :188 occur outside region phase. |
| `server.level.ServerPlayerGameMode#hasDelayedDestroy` | owned | owned | Per-player; owner tick (:115, :119) and packet handler (:237) separated by barrier. |
| `server.level.ServerPlayerGameMode#delayedDestroyPos` | owned | owned | Per-player; written by packet handler at ServerPlayerGameMode:238, read by owner tick at :113, separated by barrier. |
| `server.level.ServerPlayerGameMode#delayedTickStart` | owned | owned | Per-player; written by packet handler at ServerPlayerGameMode:239, read by owner tick at :117, separated by barrier. |
| `server.level.ServerPlayerGameMode#lastSentState` | owned | owned | Per-player; owner tick (:127, :141) and packet handler (:220) separated by barrier. |
| `world.level.entity.EntityTickList#active` | locked | locked | EntityTickList monitor on add, remove, contains, forEach (:33-47); iteration only on server thread before workers (ServerLevel:508, :544). |
| `world.level.entity.EntityTickList#passive` | locked | locked | Touched only by ensureActiveIsNotIterated (EntityTickList:19-30), called from synchronized add and remove. |
| `world.level.entity.EntityTickList#iterated` | locked | locked | Read and written only inside synchronized methods, EntityTickList:20, :48-58. |
| `world.entity.raid.Raids#raidMap` | owned | owned | Writes at Raids:86, :134 on server thread (ServerLevel:446; doTick via ServerGamePacketListenerImpl:314); workers only read (Raids:164). |
| `world.entity.raid.Raids#nextId` | owned | owned | Incremented at Raids:157 only from createOrExtendRaid: player doTick on server thread, or deferred RaidCommand. |
| `world.entity.raid.Raids#tick` | owned | owned | Written at Raids:76 from ServerLevel:446 on server thread, before regions run. |
| `world.entity.ai.village.poi.PoiManager$DistanceTracker#levels` | locked | locked | PoiManager monitor: all update and runUpdates entries synchronized (PoiManager:196, :208-221). See deadlock note below. |
| `world.entity.ai.village.poi.PoiManager#loadedChunks` | locked | locked | Only access is PoiManager:267, inside synchronized block at :260. |
| `world.entity.ai.village.poi.PoiSection$Packed#records` | owned | owned | Immutable record component; built by PoiSection:46, consumed at :159 during unpack. |
| `world.entity.ai.village.poi.PoiSection#records` | locked | locked | PoiManager monitor; query streams materialized inside it (PoiManager:96, :111). See deadlock note below. |
| `world.entity.ai.village.poi.PoiSection#byType` | locked | locked | PoiManager monitor; lazy getRecords stream (PoiSection:50) terminates at PoiManager:111 or inside synchronized isVillageCenter callers. |
| `world.entity.ai.village.poi.PoiSection#isValid` | locked | locked | Read at PoiManager:265 inside synchronized block; written by refresh via synchronized checkConsistencyWithBlocks (PoiManager:224). |
| `world.level.chunk.storage.SectionStorage$PackedChunk#sectionsByY` | owned | owned | Built on parse thread (SectionStorage:331-339), published through CompletableFuture, then read only. |
| `world.level.chunk.storage.SectionStorage#storage` | locked | locked | Storage monitor: SectionStorage:123, :139, :165, :223-232, :262, :291, :317 all reached synchronized. |
| `world.level.chunk.storage.SectionStorage#dirtyChunks` | locked | locked | Storage monitor at every access: SectionStorage:83, :112-119, :293, :300. |
| `world.level.chunk.storage.SectionStorage#loadedChunks` | locked | locked | loadLock at every access: SectionStorage:105, :174, :184, :319. |
| `world.level.chunk.storage.SectionStorage#pendingLoads` | locked | locked | loadLock at every access: SectionStorage:96, :176, :188, :193. |
| `world.level.LocalMobCapCalculator$MobCounts#counts` | owned | owned | Per calculator; one calculator per region spawn state (ServerChunkCache:568); work claimed atomically at :606. |
| `world.level.LocalMobCapCalculator#playersNearChunk` | owned | owned | Per-region instance from ServerChunkCache:568; used by that region, leftovers run after barrier (:627-634). |
| `world.level.LocalMobCapCalculator#playerMobCounts` | owned | owned | Per-region instance from ServerChunkCache:568; level-wide instance (:424) used only on server thread. |
| `world.level.LocalMobCapCalculator#chunkMap` | owned | owned | Final, assigned in constructor at LocalMobCapCalculator:20. |
| `world.level.NaturalSpawner$SpawnState#mobCategoryCounts` | owned | owned | Per-region state built at ServerChunkCache:567; mutated at NaturalSpawner:513 by the region that claimed it (:606). |
| `world.level.NaturalSpawner$SpawnState#unmodifiableMobCategoryCounts` | owned | owned | Final view assigned at NaturalSpawner:478; backing map is per-region; debug reader uses level-wide state (ServerLevel:1904). |
| `world.level.NaturalSpawner$SpawnState#lastCheckedPos` | owned | owned | Per-region scratch, NaturalSpawner:482, :500; state claimed by one region at ServerChunkCache:606. |
| `world.level.NaturalSpawner$SpawnState#lastCheckedType` | owned | owned | Per-region scratch, NaturalSpawner:483, :500; state claimed by one region at ServerChunkCache:606. |
| `world.level.NaturalSpawner$SpawnState#lastCharge` | owned | owned | Per-region scratch, NaturalSpawner:486, :490, :501; state claimed by one region at ServerChunkCache:606. |
| `world.level.NaturalSpawner#SPAWNING_CATEGORIES` | owned | owned | Static final array built at NaturalSpawner:62; only read afterward (:104, :106). |
| `world.level.pathfinder.PathTypeCache#entries` | benign-race | benign-race | Unsynchronized slots (PathTypeCache:33, :42), immutable entries. Worst: long cross-region path caches one stale PathType until next invalidate, or evicts an entry. |
| `world.level.ServerExplosion$StackCollector#stack` | owned | owned | Local to one explosion's finalize pass (ServerExplosion:221-229, :333), confined to calling thread. |
| `world.level.ServerExplosion#hitPlayers` | owned | owned | Per-explosion instance created at ServerLevel:1544, written at ServerExplosion:210, read at ServerLevel:1551 on same thread. |
| `world.level.chunk.status.WorldGenContext#unsavedListener` | unsafe | deferred | its callback now queues through `mfUnsavedFromWorkers` |
| `world.level.saveddata.SavedData#dirty` | benign-race | benign-race | Workers only store true; clear at SavedDataStorage.java:218 runs outside phase. Worst outcome: none. |
| `world.level.storage.SavedDataStorage#cache` | locked | locked | All accesses hold the storage monitor: SavedDataStorage.java:77,88,116,213. |
| `world.level.storage.SavedDataStorage#pendingWriteFuture` | locked | locked | Worker write at SavedDataStorage.java:145 is only reached under get's monitor; unsynchronized scheduleSave runs outside phase. |
| `world.level.storage.SavedDataStorage#closed` | owned | owned | Written only by close at SavedDataStorage.java:256, during server-thread shutdown. |
| `world.level.border.WorldBorder$MovingBorderExtent#lerpProgress` | owned | owned | Mutated at WorldBorder.java:437 only via ServerLevel.java:401, before workers; workers only read. |
| `world.level.border.WorldBorder$MovingBorderExtent#size` | owned | owned | Mutated at WorldBorder.java:439 only via ServerLevel.java:401, before workers; workers only read. |
| `world.level.border.WorldBorder$MovingBorderExtent#previousSize` | owned | owned | Mutated at WorldBorder.java:438 only via ServerLevel.java:401, before workers; workers only read. |
| `world.level.border.WorldBorder$Settings#DEFAULT` | owned | owned | Immutable static record: WorldBorder.java:476. |
| `world.level.border.WorldBorder$Settings#CODEC` | owned | owned | Immutable static codec: WorldBorder.java:477. |
| `world.level.border.WorldBorder$StaticBorderExtent#minX` | deferred | deferred | updateBox at WorldBorder.java:568 runs from setCenter; worker commands rerouted by OwnershipGuard at Commands.java:316. |
| `world.level.border.WorldBorder$StaticBorderExtent#minZ` | deferred | deferred | updateBox at WorldBorder.java:571 runs from setCenter; worker commands rerouted by OwnershipGuard at Commands.java:316. |
| `world.level.border.WorldBorder$StaticBorderExtent#maxX` | deferred | deferred | updateBox at WorldBorder.java:574 runs from setCenter; worker commands rerouted by OwnershipGuard at Commands.java:316. |
| `world.level.border.WorldBorder$StaticBorderExtent#maxZ` | deferred | deferred | updateBox at WorldBorder.java:577 runs from setCenter; worker commands rerouted by OwnershipGuard at Commands.java:316. |
| `world.level.border.WorldBorder$StaticBorderExtent#shape` | deferred | deferred | updateBox at WorldBorder.java:580 runs from setCenter; worker commands rerouted by OwnershipGuard at Commands.java:316. |
| `world.level.border.WorldBorder#settings` | owned | owned | Final immutable record assigned in constructor: WorldBorder.java:46. |
| `world.level.border.WorldBorder#initialized` | owned | owned | Set at WorldBorder.java:301 by first getWorldBorder, during level creation (MinecraftServer.java:489); workers only read. |
| `world.level.border.WorldBorder#listeners` | owned | owned | Added only during level creation via PlayerList.java:250; iterated only by setters, which are deferred. |
| `world.level.border.WorldBorder#damagePerBlock` | deferred | deferred | Write at WorldBorder.java:246 comes from WorldBorderCommand:145; worker commands rerouted at Commands.java:316. |
| `world.level.border.WorldBorder#safeZone` | deferred | deferred | Write at WorldBorder.java:233 comes from WorldBorderCommand:134; worker commands rerouted at Commands.java:316. |
| `world.level.border.WorldBorder#warningTime` | deferred | deferred | Write at WorldBorder.java:263 comes from WorldBorderCommand:158; worker commands rerouted at Commands.java:316. |
| `world.level.border.WorldBorder#warningBlocks` | deferred | deferred | Write at WorldBorder.java:276 comes from WorldBorderCommand:169; worker commands rerouted at Commands.java:316. |
| `world.level.border.WorldBorder#centerX` | deferred | deferred | Write at WorldBorder.java:165 comes from WorldBorderCommand:186; worker commands rerouted at Commands.java:316. |
| `world.level.border.WorldBorder#centerZ` | deferred | deferred | Write at WorldBorder.java:166 comes from WorldBorderCommand:186; worker commands rerouted at Commands.java:316. |
| `world.level.border.WorldBorder#absoluteMaxSize` | owned | owned | Written at WorldBorder.java:220 only from startup call MinecraftServer.java:489. |
| `world.level.border.WorldBorder#extent` | deferred | deferred | tick write :285 runs pre-workers (ServerLevel.java:401); command writes WorldBorder.java:188,197 rerouted at Commands.java:316. |
| `world.level.gamerules.GameRules#rules` | deferred | deferred | Set at GameRules.java:137 by commands (rerouted, Commands.java:316), or packets/RPC on unpumped server queue. |
| `server.waypoints.ServerWaypointManager#waypoints` | deferred | deferred | Every mutator calls RegionPhase.deferUntilWorkersStop, replayed at barrier: ServerWaypointManager.java:24,49; transmitters() reader is command-only. |
| `server.waypoints.ServerWaypointManager#players` | deferred | deferred | Every mutator calls RegionPhase.deferUntilWorkersStop, replayed at barrier: ServerWaypointManager.java:56,83. |
| `world.attribute.EnvironmentAttributeSystem$Builder#layersByAttribute` | owned | owned | Used only while building, from ServerLevel constructor: EnvironmentAttributeSystem.java:181; not retained by the built system. |
| `world.attribute.EnvironmentAttributeSystem$ValueSampler#layers` | owned | owned | Immutable List.copyOf: EnvironmentAttributeSystem.java:55. |
| `world.attribute.EnvironmentAttributeSystem$ValueSampler#cachedTickValue` | benign-race | benign-race | Volatile, racing workers store equal values (EnvironmentAttributeSystem.java:233). Worst outcome: server reads value one tick stale until ServerLevel.java:530. |
| `world.attribute.EnvironmentAttributeSystem$ValueSampler#cacheTickId` | owned | owned | Incremented at EnvironmentAttributeSystem.java:224 only from ServerLevel.java:530 or clock changes (commands rerouted); workers only read. |
| `world.attribute.EnvironmentAttributeSystem#attributeSamplers` | owned | owned | Populated in constructor only, then read-only: EnvironmentAttributeSystem.java:31. |
| `server.level.WorldGenRegion#cache` | owned | owned | Final, per-instance, assigned at WorldGenRegion.java:81; regions are local to chunk generation tasks, not region ticks. |
| `server.level.WorldGenRegion#currentlyGenerating` | owned | owned | Per-instance diagnostic set at WorldGenRegion.java:101 from ChunkGenerator generation only; not reachable from region ticks. |
| `world.level.storage.PrimaryLevelData#settings` | deferred | deferred | Writes at PrimaryLevelData.java:230,260,270,301 come from startup, rerouted commands, or packets/RPC on unpumped server queue. |
| `world.level.storage.PrimaryLevelData#respawnData` | deferred | deferred | Write at PrimaryLevelData.java:210 reached from startup or SetWorldSpawnCommand:39; worker commands rerouted at Commands.java:316. |
| `world.level.storage.PrimaryLevelData#gameTime` | owned | owned | Written at PrimaryLevelData.java:205 only from ServerLevel.tickTime:750, before workers start. |
| `world.level.storage.PrimaryLevelData#initialized` | owned | owned | Written at PrimaryLevelData.java:250 only during startup (MinecraftServer.java:453,468). |
| `world.level.storage.PrimaryLevelData#knownServerBrands` | owned | owned | Added at PrimaryLevelData.java:306 only from startup call MinecraftServer.java:411. |
| `world.level.storage.PrimaryLevelData#wasModded` | owned | owned | Written at PrimaryLevelData.java:307 only from startup call MinecraftServer.java:411. |
| `world.level.storage.PrimaryLevelData#removedFeatureFlags` | owned | owned | Final set populated at construction, read-only afterward: PrimaryLevelData.java:66. |
| `world.level.storage.PrimaryLevelData#confirmedExperimentalWarning` | owned | owned | Written at PrimaryLevelData.java:340, called only during parse (:107). |
| `world.level.storage.PrimaryLevelData#dayTimeFraction` | owned | owned | Only setter caller in snapshot is parse: PrimaryLevelData.java:109; no region-tick writer. |
| `world.level.storage.PrimaryLevelData#dayTimePerTick` | owned | owned | Only setter caller in snapshot is parse: PrimaryLevelData.java:110; no region-tick writer. |
| `world.RandomSequences#salt` | deferred | deferred | Written at RandomSequences.java:64 only from RandomCommand:189; worker commands rerouted at Commands.java:316. Workers only read. |
| `world.RandomSequences#includeWorldSeed` | deferred | deferred | Written at RandomSequences.java:65 only from RandomCommand:189; worker commands rerouted at Commands.java:316. Workers only read. |
| `world.RandomSequences#includeSequenceId` | deferred | deferred | Written at RandomSequences.java:66 only from RandomCommand:189; worker commands rerouted at Commands.java:316. Workers only read. |
| `world.RandomSequences#sequences` | unsafe | locked | map and every draw hold the `RandomSequences` monitor |
