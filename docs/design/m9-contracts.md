# M9 Interface Contracts

**Status:** frozen (Phase 0 task 0.1). Phase 1+ subagents implement against these
signatures; changes require a Phase 0 amendment.

Cite conventions: `file:line` refers to a snippet in the repo at the time
of freezing. `net.mf.rt.*` = `net.multiforge.runtime.*`; `net.mf.nf.*` =
`net.multiforge.neoforge.*` (fork facade, lives under
`upstream/neoforge-1.21.1/src/main/java/`).

---

## 1. `NewChunkHolder`

File: `multiforge-runtime/src/main/java/net/multiforge/runtime/chunk/NewChunkHolder.java`
(current shape: metadata bag,
`NewChunkHolder.java:27-96`).

### 1.1 Field list (post Phase 2)

| Field | Type | Storage | Writer | Reader | Mutated when |
|---|---|---|---|---|---|
| `world` | `final WorldRef` | final | ctor | any | never after ctor |
| `position` | `final ChunkPos` | final | ctor | any | never after ctor |
| `owningRegion` | `AtomicReference<RegionId>` | atomic ref | regionizer write-lock holder (via `setOwningRegion`) | any | region merge/split (`ChunkHolderManager.onRegionMerged/onRegionSplit`) |
| `level` | `volatile ChunkLoadLevel` | volatile | owning region worker only (via `ChunkHolderManager.addTicket/removeTicket`) | any | ticket add/remove |
| `dirty` | `volatile boolean` | volatile | owning region worker only | autosave drain thread + any | block/BE mutation, autosave completion |
| `pendingFullLoadUpdate` | `volatile boolean` | volatile | owning region worker only | tick body | ticket cross-threshold, drained in `pollFullLoadUpdate` |
| `currentChunk` (2.1) | `AtomicReference<LevelChunk>` | atomic ref | owning region worker only | any (read); Vanilla `ServerChunkCache` reads cross-region | payload attach/detach at BORDER load / INACCESSIBLE demote |
| `fullChunkFuture` (2.2) | `volatile CompletableFuture<ChunkResult<LevelChunk>>` | volatile | owning region worker only | any | cross-BORDER promotion/demotion |
| `tickingChunkFuture` (2.2) | `volatile CompletableFuture<ChunkResult<LevelChunk>>` | volatile | owning region worker only | any | cross-TICKING promotion/demotion |
| `entityTickingChunkFuture` (2.2) | `volatile CompletableFuture<ChunkResult<LevelChunk>>` | volatile | owning region worker only | any | cross-ENTITY_TICKING promotion/demotion |
| `statusFutures` (2.3) | `final AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>>` (size = `ChunkStatus.getStatusList().size()`) | array of atomic refs, `final` outer | owning region worker only | any | each ChunkStep completion |
| `playersWatching` (2.4) | `final Set<ServerPlayer>` backed by `ConcurrentHashMap.newKeySet()` | thread-safe set | `MultiForgeChunkMap.markChunkPendingToSend` / `dropChunk` (main thread today, per-region in M9) | any | player enters/leaves send radius |
| `entitiesInChunk` (2.5) | `volatile Iterable<Entity>` (per-tick cache) | volatile | owning region worker only | owning region worker only | recomputed at start of `Phase.ENTITY_AI` |
| `blockEntitiesInChunk` (2.6) | `volatile Iterable<BlockEntity>` (per-tick cache) | volatile | owning region worker only | owning region worker only | recomputed at start of `Phase.BLOCK_ENTITIES` |
| `blocksToBroadcast` (2.7) | `final ShortSet` guarded by synchronization on itself | single-writer + drainer swap | owning region worker only | drainer during `Phase.FLUSH_OUTBOUND` | on `LevelChunk.setBlockState` for a watched section |
| `broadcastPending` (2.7) | `AtomicInteger` | atomic | any (setBlockState) | drainer | any change accumulation |
| `sectionLightChanged` (2.8) | `final BitSet` guarded by synchronization on itself | single-writer + drainer swap | owning region worker only | drainer | light engine section update |
| `saveSyncFuture` (2.9) | `volatile CompletableFuture<Void>` | volatile | owning region worker only | any (as dependency) | chained on every autosave |
| `sendSyncFuture` (2.9) | `volatile CompletableFuture<Void>` | volatile | owning region worker only | any | chained on every outbound send |
| `levelChangeListeners` (2.10) | `final CopyOnWriteArrayList<LevelChangeListener>` | thread-safe list | ctor + registration | `onLevelCrossThreshold` | registration/unregistration |

Every non-final field is either `final`, `volatile`, or an `Atomic*` type;
mutable collections (`playersWatching`, `blocksToBroadcast`,
`sectionLightChanged`) document their guard on the class javadoc.

### 1.2 Public method surface

```java
public WorldRef world();
public ChunkPos position();

// --- ownership ---
public RegionId owningRegion();                   // atomic read, any thread
public boolean setOwningRegion(RegionId id);      // any thread; caller MUST hold regionizer write lock

// --- level ---
public ChunkLoadLevel level();                    // any thread
public void setLevel(ChunkLoadLevel level);       // owning region worker ONLY (contract)

// --- dirty / autosave ---
public boolean isDirty();                         // any thread
public void markDirty();                          // owning region worker ONLY
public void clearDirty();                         // autosave drain thread ONLY

// --- promotion pipeline ---
public boolean pendingFullLoadUpdate();           // owning region worker ONLY
public void setPendingFullLoadUpdate(boolean v);  // owning region worker ONLY

// --- payload (Phase 2.1) ---
public LevelChunk getCurrentChunk();              // any thread — nullable
public void setCurrentChunk(LevelChunk chunk);    // owning region worker ONLY

// --- future gates (Phase 2.2) ---
public CompletableFuture<ChunkResult<LevelChunk>> getFullChunkFuture();
public CompletableFuture<ChunkResult<LevelChunk>> getTickingChunkFuture();
public CompletableFuture<ChunkResult<LevelChunk>> getEntityTickingChunkFuture();
// setters are package-private; only ChunkHolderManager may call.

// --- generation ladder (Phase 2.3) ---
public CompletableFuture<ChunkResult<ChunkAccess>> getOrScheduleFuture(ChunkStatus status,
                                                                       ChunkMap chunkMap);
public CompletableFuture<ChunkResult<ChunkAccess>> getFutureIfPresent(ChunkStatus status);
public ChunkAccess getLastAvailable();            // any thread; scans ladder highest → lowest

// --- players (Phase 2.4) ---
public Set<ServerPlayer> playersWatching();       // thread-safe view; iteration must tolerate concurrent add
public void addPlayerWatching(ServerPlayer p);    // any thread (thread-safe set)
public void removePlayerWatching(ServerPlayer p); // any thread

// --- per-tick caches (Phase 2.5–2.6) ---
public Iterable<Entity> entitiesInChunk();        // owning region worker ONLY
public Iterable<BlockEntity> blockEntitiesInChunk(); // owning region worker ONLY
public void invalidatePerTickCaches();            // owning region worker ONLY

// --- broadcast accumulation (Phase 2.7–2.8) ---
public void recordBlockChange(short packedLocal); // any thread (guarded)
public void recordLightSectionChange(int sectionY); // any thread (guarded)
public ShortSet drainBlockChanges();              // drainer ONLY, returns a fresh copy
public BitSet drainLightChanges();                // drainer ONLY, returns a fresh copy

// --- save / send sync (Phase 2.9) ---
public CompletableFuture<Void> getSaveSyncFuture();
public CompletableFuture<Void> getSendSyncFuture();
public void addSendDependency(CompletableFuture<?> dep); // any thread; chains via thenCombine

// --- level-change listener (Phase 2.10) ---
public interface LevelChangeListener {
    void onLevelChange(ChunkPos pos, IntSupplier queueLevel, int newLevel, IntConsumer setter);
}
public void addLevelChangeListener(LevelChangeListener l);
public void removeLevelChangeListener(LevelChangeListener l);
```

### 1.3 Threading contract summary

- **Any-thread safe reads:** `world()`, `position()`, `owningRegion()`,
  `level()`, `isDirty()`, `pendingFullLoadUpdate()`, `getCurrentChunk()`,
  `getFullChunkFuture()`, `getTickingChunkFuture()`,
  `getEntityTickingChunkFuture()`, `getLastAvailable()`, `playersWatching()`,
  `getSaveSyncFuture()`, `getSendSyncFuture()`.
- **Any-thread safe writes:** `addPlayerWatching`, `removePlayerWatching`,
  `recordBlockChange`, `recordLightSectionChange`, `addSendDependency`,
  `addLevelChangeListener`, `removeLevelChangeListener`.
- **Owning region worker ONLY:** `setLevel`, `markDirty`,
  `setPendingFullLoadUpdate`, `setCurrentChunk`, `invalidatePerTickCaches`,
  `entitiesInChunk`, `blockEntitiesInChunk`, `getOrScheduleFuture`.
- **Regionizer write-lock holder ONLY:** `setOwningRegion` (called from
  `ChunkHolderManager.onRegionMerged/onRegionSplit`, which themselves fire
  under the write lock — see `ThreadedRegionizer.java:108, 142, 258-263`).
- **Autosave drainer ONLY:** `clearDirty`, `drainBlockChanges`,
  `drainLightChanges`.

### 1.4 `ChunkHolder` shadow attachment (Phase 4.4a)

After Phase 4.4a, every Vanilla `ChunkHolder` gains an added field
`private final NewChunkHolder mfShadow;` initialised by `ChunkMap`'s
`updateChunkScheduling` (`ChunkMap.java:1234`) via
`chunkManagerFor(level).createHolder(pos, region.id())`. "Attached shadow"
means:

- Vanilla `ChunkHolder`'s state-carrying getters (`getTickingChunk`,
  `getFullChunk`, `getFullChunkFuture`, `getLastAvailableStatus`,
  `getLastAvailable`) delegate to `mfShadow` first; the Vanilla backing
  field is only consulted if `mfShadow` returns null (transitional; deleted
  when Phase 5.7 lands).
- `ChunkHolder.setTicketLevel(int)` writes through to
  `ChunkHolderManager.addTicket/removeTicket` on the appropriate region.
- The shadow is created **exactly once** per `(level, chunkPos)` — the
  `computeIfAbsent` in `ChunkHolderManager.createHolder`
  (`ChunkHolderManager.java:60-66`) guarantees this.
- The shadow's `owningRegion` is set by `createHolder` at attach time and
  subsequently rewritten only under the regionizer write lock via
  `onRegionMerged`/`onRegionSplit`
  (`ChunkHolderManager.java:131-153, 181-198`).

---

## 2. `ChunkHolderManager`

File: `multiforge-runtime/src/main/java/net/multiforge/runtime/chunk/ChunkHolderManager.java`.

### 2.1 `RegionListener` timing (already implemented; frozen)

Fold/split/death fire order is set by `ThreadedRegionizer`'s
`fireRegions*` calls. All three fire **while the regionizer write lock is
held** (`ThreadedRegionizer.java:108 synchronized(writeLock)` wraps
`addChunk`, `removeChunk`, and by proxy `mergeInto`/`splitIfDisconnected`).

- `onRegionsMerging(surviving, dying)` — fires **inside** the write lock,
  **before** `mergeInto` moves `dying`'s sections to `surviving`
  (`ThreadedRegionizer.java:162`). Both regions are queryable; folding
  side state here is safe because no other thread can observe a half-fold.
- `onRegionSplit(source, child)` — fires **inside** the write lock,
  **after** `splitIfDisconnected` has already moved the child's sections
  into `sectionToRegion` (`ThreadedRegionizer.java:222-228`). At fire time
  `child` already owns its sections; the listener uses `child.sections()`
  to determine which holders migrate.
- `onRegionDied(region)` — fires **inside** the write lock, **after** the
  merge fold or the natural-death path (`ThreadedRegionizer.java:148, 169`).
  In the merge case `onRegionsMerging` has already run, so this callback
  only removes the now-empty side state; it never itself moves data.

Fixed in commit ee136a7 (/67 round-4): `onRegionsMerging` and
`onRegionSplit` now match `RegionListener`'s Region-typed signatures
(`ChunkHolderManager.java:181, 193`); prior to the fix a signature
mismatch silently dropped these callbacks and every merge leaked
per-region ticket state.

### 2.2 `addTicket` / `removeTicket` / `markDirty` / `dropHolder`

```java
public boolean addTicket(RegionId owner, ChunkPos pos, Ticket ticket);
public boolean removeTicket(RegionId owner, ChunkPos pos, Ticket ticket);
public void    markDirty(RegionId owner, ChunkPos pos);
public NewChunkHolder dropHolder(ChunkPos pos);
```

Preconditions:
- `owner` is the region currently owning `pos` — caller resolves it via
  `regionizerFor(world).regionAtChunk(pos)` **under the regionizer read
  lock** (or via a listener callback fired under the write lock). Passing
  a stale `owner` is a bug and results in tickets landing on the wrong
  per-region map.
- `pos` need not already have a holder; `addTicket` will `createHolder`
  lazily via `computeIfAbsent` (`ChunkHolderManager.java:82-86`).
- `markDirty` requires the holder to already exist; is a no-op otherwise
  (`ChunkHolderManager.java:120-125`).

Postconditions:
- `addTicket` returning `true` means the ticket was newly added. If the
  effective level crosses a threshold, `holder.setLevel(now)` is called
  **and** `regionData(owner).enqueueFullLoadUpdate(holder)` is scheduled
  for the next `Phase.INBOUND_MAILBOX` drain (Phase 5.1).
- `removeTicket` returning `true` means the ticket was removed; if the
  last ticket disappears, effective level rises to `INACCESSIBLE` and a
  full-load-update is enqueued. Note: the holder is **not** removed from
  `byChunk` — `dropHolder` is a separate operation.
- `dropHolder` returns the removed holder (or null); safe from any thread
  (concurrent map). Callers must not call this while a region worker
  might still be draining tasks against it — invoke only from the
  `INACCESSIBLE` transition path in the owning region.

### 2.3 New methods for Phase 4/5

```java
/**
 * Return the holder at {@code pos}, creating it if absent. Owner is
 * resolved from the world's regionizer at call time. Safe from any
 * thread; racy owner reads self-heal via later region-listener fire.
 */
public NewChunkHolder holderAtOrCreate(ChunkPos pos);

/**
 * Force-promote a holder to at least {@code target} by injecting a
 * synthetic PLUGIN ticket at the appropriate distance. Returns the
 * effective level after promotion. Used by Phase 4.1b (chunk map
 * scheduleGenerationTask) and Phase 4.3a (getChunk).
 */
public ChunkLoadLevel promoteToLevel(RegionId owner, ChunkPos pos,
                                     ChunkLoadLevel target);

/**
 * Drop every ticket on chunks owned by {@code region} — used by
 * {@link RegionShutdownCoordinator} in the CLOSING phase to prevent
 * further work from being scheduled. Must be called under the
 * regionizer write lock or with the region already marked DEAD.
 */
public void dropTicketsForClose(RegionId region);

/**
 * Snapshot of every holder owned by {@code region} at call time.
 * Returned list is a defensive copy; safe from any thread. Iteration
 * order is unspecified. Used by Phase 5.4 journal open/close and by
 * `/multiforge chunks` observability.
 */
public List<NewChunkHolder> snapshotHoldersInRegion(RegionId region);
```

---

## 3. `ChunkTaskScheduler`

File: `multiforge-runtime/src/main/java/net/multiforge/runtime/chunk/ChunkTaskScheduler.java`.

### 3.1 `scheduleChunkTask(world, x, z, task, priority)`

Signature and semantics (`ChunkTaskScheduler.java:60-73`):

```java
public void scheduleChunkTask(WorldRef world, int chunkX, int chunkZ,
                              Runnable task, ChunkTaskPriority priority);
```

- **Where it runs:** the owning region worker for `(world, chunkX, chunkZ)`
  at the time `drainInto` fires. Resolution is via `regionLookup.regionAtChunk`
  (in production this walks
  `regionizerFor(world).regionAtChunk(x, z)`).
- **Order:** strict priority within a region — BLOCKING before HIGHEST
  before HIGH before NORMAL before LOW before LOWEST before IDLE
  (`ChunkTaskPriority.java:11-19`, drain loops `ChunkTaskPriority.values()`
  in ordinal order at `ChunkTaskScheduler.java:93`). FIFO within a
  single priority (`ConcurrentLinkedDeque.addLast` / `pollFirst`).
- **Orphan chunks:** if `regionLookup` returns null (chunk has no owner),
  the task falls back to `taskQueue.queueChunkTask` (orphan list); it
  will retry via the standard reroute path.
- **Merge:** on `onRegionsMerging` the source region's per-priority deques
  are folded into the surviving region tail-appended per priority
  (`ChunkTaskScheduler.java:119-129`). Delivery is preserved with
  weak-FIFO — tasks that were queued into the dying region before the
  merge fire after any task already queued for the surviving region at
  the same priority. This is acceptable because both were destined for
  the same region worker after the merge; a stricter order would require
  a global sequence number.
- **Split:** currently a no-op; task 1.6 must implement.

### 3.2 `drainInto(RegionId, max)`

Signature (`ChunkTaskScheduler.java:85-109`):

```java
public int drainInto(RegionId region);
public int drainInto(RegionId region, int max);
```

- **Reentrant:** yes — a task executed inside `r.run()` may itself call
  `drainInto` on the same region. Behaviour is well-defined because both
  invocations poll from the same `ConcurrentLinkedDeque`s and neither
  holds a lock.
- **Thread:** must be called from the owning region worker only. Calling
  from a foreign thread violates the "no cross-region access without
  RegionizedTaskQueue" rule (CLAUDE.md §4).
- **Order:** strict priority; drains lower-priority queues only when the
  higher-priority queue is empty at that moment. Ties within a priority
  are FIFO.
- **Exception behaviour:** a thrown `Throwable` is caught and dispatched
  to the current thread's uncaught-exception handler
  (`ChunkTaskScheduler.java:100-103`); the drain continues. This is
  intentional — a single misbehaving task must not stall the region.
- **Max:** the drain stops as soon as `run >= max`. `max = Integer.MAX_VALUE`
  drains the region empty at call time (subsequent adds during drain are
  visible; drain will pick them up because it re-polls after each run).
- **Return value:** number of tasks executed (not including tasks that
  arrived after the drain started but weren't polled before the loop
  exited).

### 3.3 `onRegionSplit` correctness target (Phase 1.6)

Current implementation is a documented no-op
(`ChunkTaskScheduler.java:136-142, 162-169`). Task 1.6 must implement:

**Correct semantics:** for each queued task in the source region's
priority deque, decide whether it belongs to the child region or stays
with the source. Decision key is the chunk position the task was
enqueued against; because `scheduleChunkTask` doesn't retain that
metadata today, Phase 1.6 must:

1. Wrap the incoming `Runnable` at `scheduleChunkTask` time in a small
   record `ChunkPositionedTask(ChunkPos pos, Runnable delegate)` so the
   split path can inspect it.
2. On `onRegionSplit(source, child)`, iterate each priority deque of the
   source; for each `ChunkPositionedTask` whose `pos` falls in
   `child.sections()`, remove it from the source deque and add it to the
   child deque at the same priority; preserve FIFO within priority.
3. Fire under the regionizer write lock (same fire path as
   `ChunkHolderManager.onRegionSplit`, `ChunkHolderManager.java:193-198`)
   so no task can be dequeued mid-migration.

Split correctness is a functional requirement, not an optimisation: today
a task queued against a chunk that just split into a fresh region fires
on the source's next tick and no-ops (because the region-owner check
inside the task fails). That "no-op" is only correct because the
task's real work is idempotently retried by the caller
(`scheduleChunkTask` from the fresh owner). Under real load with
promotion-critical tasks (e.g. ChunkStep application), a silent drop
becomes a stuck holder.

---

## 4. `RegionListener` for M9 use

File: `multiforge-runtime/src/main/java/net/multiforge/runtime/region/RegionListener.java`.

### 4.1 Which callbacks fire under the write lock

All four fire under `ThreadedRegionizer`'s `writeLock` monitor
(`ThreadedRegionizer.java:46 private final Object writeLock`, taken at
lines 108, 142, and around `mergeInto` at 157-170 which callers invoke
inside the same `synchronized` block):

- `onRegionCreated` — `ThreadedRegionizer.java:129, 229` (inside `addChunk`
  synchronized block and split path).
- `onRegionsMerging` — `ThreadedRegionizer.java:162` (inside `mergeInto`
  called from `addChunk`'s synchronized block).
- `onRegionSplit` — `ThreadedRegionizer.java:228` (inside
  `splitIfDisconnected` called from `removeChunk`'s synchronized block).
- `onRegionDied` — `ThreadedRegionizer.java:148, 169` (inside
  `removeChunk` and `mergeInto` synchronized blocks).

### 4.2 What listeners must NOT do

- **No blocking.** No `Thread.sleep`, no `CompletableFuture.get`, no
  `synchronized` on any lock that a region worker might contend on. The
  regionizer write lock is held; any wait risks stalling every world's
  add/remove chunk path.
- **No reentrance.** Do not call back into `ThreadedRegionizer.addChunk`,
  `removeChunk`, or `mergeInto` from a listener — the write lock is
  reentrant only because the JVM monitor allows it, but the invariant
  "each fire completes before the next `addChunk` runs" is what makes
  side-state consistent. Reentering means the outer caller sees a state
  the listener already mutated.
- **No cross-region locks.** In particular, no calls into another
  region's inbox that itself takes a lock. Enqueuing tasks is fine
  (`RegionizedTaskQueue.queueChunkTask` is lock-free from the enqueue
  side); acquiring another region's per-region-data lock is not.
- **No unbounded iteration.** Listeners iterate over regionizer state
  (e.g. `ChunkHolderManager.onRegionMerged` scans every holder,
  `ChunkHolderManager.java:136-138`). Fold work must be O(holders in
  source region), not O(holders across all regions).

### 4.3 Listener fire order

Listeners fire in insertion order on a `CopyOnWriteArrayList`
(`ThreadedRegionizer.java:47, 254-268`). The production wiring order is
established in `MultiThreadedSchedulerHost`:

- Global regionizer (`MultiThreadedSchedulerHost.java:108-109`): first
  `scheduler` (TickRegionScheduler), then `taskQueue` (RegionizedTaskQueue).
- Per-world regionizer (`MultiThreadedSchedulerHost.java:151-161`, inside
  `regionizerFor(world)` `computeIfAbsent`): `scheduler`, `taskQueue`,
  `chunkManagerFor(world)`, `chunkTaskScheduler`.
- Phase 5.4 will insert `chunkJournalLifecycle` between `chunkManagerFor`
  and `chunkTaskScheduler` (so ticket state is folded before journal
  files are moved).

**Order rationale (frozen):**
1. `scheduler` — deregisters dead regions from the tick fan-out first so
   no worker is dispatched to a region whose side state is mid-fold.
2. `taskQueue` — folds inbound mailboxes so a queued task can find its
   new owner immediately.
3. `chunkManagerFor(world)` — folds tickets and holder ownership; must
   run after `taskQueue` so any task added during fold reaches the
   surviving region.
4. `chunkJournalLifecycle` (Phase 5.4) — closes/opens journal files;
   must run after ticket fold because journal open reads
   `snapshotHoldersInRegion`.
5. `chunkTaskScheduler` — folds per-priority chunk-task deques; runs
   last because it depends on ticket state to route tasks correctly on
   split.

Never reorder without an updated Phase 0 contract.

---

## 5. `MultiForgeChunkMap` public surface (Phase 4.1b target)

Not yet implemented. Phase 4.1b writes it at
`upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/chunk/MultiForgeChunkMap.java`.
Base surface mirrors Vanilla `ChunkMap`
(`upstream/neoforge-1.21.1/projects/neoforge/src/main/java/net/minecraft/server/level/ChunkMap.java`,
lines cited below).

```java
public final class MultiForgeChunkMap
    implements GeneratingChunkMap, ChunkHolder.PlayerProvider {

  // --- constants preserved for API compat (ChunkMap.java:113-115) ---
  public static final int MIN_VIEW_DISTANCE = 2;
  public static final int MAX_VIEW_DISTANCE = 32;
  public static final int FORCED_TICKET_LEVEL =
      ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);

  // --- ctor mirrors ChunkMap.java:145 (params compatible with Vanilla ChunkMap) ---
  public MultiForgeChunkMap(ServerLevel level,
                            LevelStorageSource.LevelStorageAccess storage,
                            DataFixer dataFixer,
                            StructureTemplateManager structures,
                            Executor bgExecutor, Executor mainExecutor,
                            LightChunkGetter lightChunkGetter,
                            ChunkGenerator generator,
                            ChunkProgressListener progressListener,
                            ChunkStatusUpdateListener statusListener,
                            Supplier<DimensionDataStorage> overworldData,
                            int viewDistance, boolean sync);

  // --- holder access (ChunkMap.java:253, 258, 262) ---
  protected ChunkHolder getUpdatingChunkIfPresent(long pos);
  public    ChunkHolder getVisibleChunkIfPresent(long pos);  // returns NewChunkHolder-backed shim
  protected IntSupplier getChunkQueueLevel(long pos);
  public    String      getChunkDebugData(ChunkPos pos);     // ChunkMap.java:271

  // --- generation (GeneratingChunkMap; ChunkMap.java:611-676) ---
  public GenerationChunkHolder acquireGeneration(long pos);
  public void                  releaseGeneration(GenerationChunkHolder h);
  public CompletableFuture<ChunkAccess> applyStep(GenerationChunkHolder h,
                                                  ChunkStep step,
                                                  StaticCache2D<GenerationChunkHolder> cache);
  public ChunkGenerationTask scheduleGenerationTask(ChunkStatus status, ChunkPos pos);
  public void                runGenerationTasks();
  public CompletableFuture<ChunkResult<LevelChunk>> prepareTickingChunk(ChunkHolder h);
  public CompletableFuture<ChunkResult<LevelChunk>> prepareAccessibleChunk(ChunkHolder h);
  public CompletableFuture<ChunkResult<LevelChunk>> prepareEntityTickingChunk(ChunkHolder h);
  public int  getTickingGenerated();

  // --- tick / save / close (ChunkMap.java:410, 419, 448, 460, 543) ---
  public    void    close() throws IOException;
  protected void    saveAllChunks(boolean flush);
  protected void    tick(BooleanSupplier hasTimeLeft);
  public    boolean hasWork();
  protected boolean promoteChunkMap();

  // --- view distance + storage (ChunkMap.java:806, 840, 845, 849, 853, 1198) ---
  protected void                setServerViewDistance(int viewDistance);
  public    LevelChunk          getChunkToSend(long pos);
  public    int                 size();
  public    DistanceManager     getDistanceManager();
  protected Iterable<ChunkHolder> getChunks();
  public    String              getStorageName();

  // --- players & entities (ChunkMap.java:944, 1002, 1071, 1085, 1111, 1126) ---
  public    List<ServerPlayer> getPlayersCloseForSpawning(ChunkPos pos);
  public    void               move(ServerPlayer player);
  public    List<ServerPlayer> getPlayers(ChunkPos pos, boolean boundaryOnly);
  protected void               addEntity(Entity e);
  protected void               removeEntity(Entity e);
  protected void               tick(); // entity-tracker tick; note overload

  // --- broadcasts (ChunkMap.java:1160, 1167) ---
  public    void broadcast(Entity e, Packet<?> pkt);
  protected void broadcastAndSend(Entity e, Packet<?> pkt);

  // --- misc (ChunkMap.java:1174, 1194, 1206, 1343) ---
  public    void        resendBiomesForChunks(List<ChunkAccess> chunks);
  protected PoiManager  getPoiManager();
  public    void        waitForLightBeforeSending(ChunkPos pos, int chunkRadius);
  public    void        scheduleOnMainThreadMailbox(
      ChunkTaskPriorityQueueSorter.Message<Runnable> msg);
  public    ReportedException debugFuturesAndCreateReportedException(
      IllegalStateException e, String context);   // ChunkMap.java:336
}
```

**Behavioural contract** (implementers of 4.1b MUST satisfy):
- Every method that mutates holder state routes through
  `ChunkHolderManager.holderAtOrCreate(pos)` on the owning region's data
  slot, never through a local map.
- `getVisibleChunkIfPresent(long)` returns a `ChunkHolder`-shaped shim
  backed by `NewChunkHolder`; the shim's `getTickingChunk()`,
  `getFullChunk()`, and future gates delegate to the shadow's
  `currentChunk` / `tickingChunkFuture` / etc.
- `prepareTickingChunk`, `prepareAccessibleChunk`,
  `prepareEntityTickingChunk` return the shadow's already-published
  future gates (do NOT allocate a new future chain — that recreates the
  Vanilla `MainThreadExecutor.join` anti-pattern the plan mandates
  removing).
- `tick(BooleanSupplier)` becomes a per-region drive: fan out over live
  regions via `TickRegionScheduler`; each worker calls `runGenerationTasks`
  on its own region's slice.
- `broadcast` reads `holder.playersWatching()` for the target chunk; no
  locking, no per-broadcast player scan.

---

## 6. `MultiForgeDistanceManager` public surface (Phase 4.2b target)

Base surface mirrors Vanilla `DistanceManager`
(`upstream/neoforge-1.21.1/projects/neoforge/src/main/java/net/minecraft/server/level/DistanceManager.java`).
NeoForge's `forceTicks` overloads MUST be preserved
(`DistanceManager.java:194, 204, 291`).

```java
public abstract class MultiForgeDistanceManager extends DistanceManager {

  // --- ctor mirrors DistanceManager.java:59 ---
  protected MultiForgeDistanceManager(Executor mainExecutor, Executor bgExecutor);

  // --- abstract override contracts (DistanceManager.java:100-106) ---
  protected abstract boolean      isChunkToRemove(long pos);
  protected abstract ChunkHolder  getChunk(long pos);
  protected abstract ChunkHolder  updateChunkScheduling(long pos, int newLevel,
                                                        @Nullable ChunkHolder oldHolder,
                                                        int oldLevel);

  // --- distance updates (DistanceManager.java:108) ---
  public boolean runAllUpdates(ChunkMap chunkMap);   // becomes a no-op in M9 (in-region)

  // --- tickets (DistanceManager.java:182-204) ---
  public <T> void addTicket   (TicketType<T> type, ChunkPos pos, int level, T key);
  public <T> void removeTicket(TicketType<T> type, ChunkPos pos, int level, T key);
  public <T> void addRegionTicket   (TicketType<T> type, ChunkPos pos, int level, T key);
  public <T> void addRegionTicket   (TicketType<T> type, ChunkPos pos, int level, T key,
                                     boolean forceTicks);          // NeoForge overload — MUST preserve
  public <T> void removeRegionTicket(TicketType<T> type, ChunkPos pos, int level, T key);
  public <T> void removeRegionTicket(TicketType<T> type, ChunkPos pos, int level, T key,
                                     boolean forceTicks);          // NeoForge overload — MUST preserve

  // --- forcedTickets accessor (DistanceManager.java:215) ---
  protected void updateChunkForced(ChunkPos pos, boolean add);

  // --- players (DistanceManager.java:227-236) ---
  public void addPlayer   (SectionPos section, ServerPlayer p);
  public void removePlayer(SectionPos section, ServerPlayer p);

  // --- range predicates (DistanceManager.java:253-257) ---
  public boolean inEntityTickingRange(long pos);
  public boolean inBlockTickingRange(long pos);

  // --- ticket debug + tick book-keeping (DistanceManager.java:261-291) ---
  protected String  getTicketDebugString(long pos);
  protected void    updatePlayerTickets(int viewDistance);
  public    void    updateSimulationDistance(int simulationDistance);
  public    int     getNaturalSpawnChunkCount();
  public    boolean hasPlayersNearby(long pos);
  public    String  getDebugStatus();
  public    boolean shouldForceTicks(long chunkPos);   // NeoForge addition — MUST preserve

  // --- shutdown (DistanceManager.java:317, 345) ---
  public void    removeTicketsOnClosing();
  public boolean hasTickets();
}
```

**Behavioural contract** (Phase 4.2b MUST satisfy):
- Every ticket write resolves the owning region for `pos`, then delegates
  to `chunkManagerFor(world).addTicket(regionId, pos, Ticket.of(type, key, level))`.
  The Vanilla `tickets` field becomes unused; the shim is stateless
  w.r.t. tickets.
- `runAllUpdates(ChunkMap)` returns `false` unconditionally after M9:
  ticket application now happens in `Phase.INBOUND_MAILBOX` per region.
  Preserving the method signature keeps `ChunkMap.tick`
  (`ChunkMap.java:448`) callers source-compatible.
- `updateChunkForced` writes into MultiForge's `TicketType.FORCED` via
  Phase 1.8 wiring; the Vanilla `forcedTickets` map is retained only for
  compat with mods that read it via reflection.
- `shouldForceTicks(long)` reads MultiForge's `forceTicks` flag on the
  per-chunk ticket (Phase 4.8a extends `Ticket` with this bit).
- `addPlayer`/`removePlayer` route through Phase 1.9's `TicketType.PLAYER`
  wiring; the internal `PlayerTicketTracker`
  (`DistanceManager.java:457-506`) is deleted.

---

## 7. Threading model summary

**Threads that exist in a running M9 server:**
- **Main thread.** Vanilla `MinecraftServer` thread. After Phase 5, its
  only chunk-system responsibilities are: (a) driving
  `TickRegionScheduler` fan-out, (b) running the global-region tick body
  (level metadata, weather, world border, packet input), (c) shutdown
  coordination.
- **Region workers.** N tick threads (`config.tickWorkerCount()`, default
  `cores / 2`). Each owns a slice of live regions; only the owning
  worker mutates a region's tick state, per-region ticket map,
  `HolderManagerRegionData`, and its holders' owning-region-worker-only
  fields (see §1.3).
- **Async pool.** `Executors.newScheduledThreadPool(max(2, cores/2))`
  (`MultiThreadedSchedulerHost.java:124-128`), thread name prefix
  `multiforge-async-`. Serves the `AsyncDomain` scheduler and non-chunk
  background work (metrics, license verify, log ship). Must never
  reach into region-worker-only state.
- **Delayed pool.** `Executors.newScheduledThreadPool(1)`
  (`MultiThreadedSchedulerHost.java:118-122`), thread name
  `multiforge-delayed-`. Delay dispatcher that re-hands work to a region
  worker via `taskQueue.queueChunkTask`.
- **Worldgen threads.** After Phase 4.5/4.7, chunk generation runs on
  region workers via `RegionizedTaskQueue`; Vanilla's `Util.backgroundExecutor()`
  is no longer used for chunk-status upgrades. It remains available for
  purely CPU-bound helpers (biome-lookup caches, structure lookups) that
  hold no chunk-system references.
- **I/O threads.** Region-file writes run on the async pool via
  `AutoSaveRunner`, drained from the owning region's writer path (Phase
  3.4 `RegionFileCache` per-file ReadWriteLock).

**What crosses thread boundaries:**
- Region worker → region worker: **only** via `RegionizedTaskQueue.queueChunkTask`.
  Never direct method call; never `synchronized` on a shared object; never
  a `CompletableFuture.get`.
- Main thread → region worker: `TickRegionScheduler` fan-out (owns the
  contract); ad-hoc: `queueChunkTask`.
- Region worker → main thread: never directly. Cross-region packets are
  gathered in `Phase.FLUSH_OUTBOUND` and sent from the outbound thread
  (Netty pipeline), not from the region worker's tick.
- Region worker → async: fire-and-forget executor submit is fine; the
  async task must not reach back into region-worker-only state without
  going through `queueChunkTask`.

**Write locks:**
- `ThreadedRegionizer.writeLock` (JVM monitor, `ThreadedRegionizer.java:46`).
  Held around `addChunk`, `removeChunk`, `mergeInto`,
  `splitIfDisconnected`. **All `RegionListener` callbacks fire under this
  lock** (§4.1). Held for O(regions modified) time — must never block on
  I/O or an off-thread response.
- Per-file `ReadWriteLock` (Phase 3.4) inside `RegionFileCache`. Readers
  from any region; writer exclusive. Never taken by a region worker
  synchronously — reads for chunk load go through `queueChunkTask` into
  the I/O pool.

**Golden rule (from `CLAUDE.md` §4, reproduced verbatim):** *"No blocking
calls on a region worker thread. Ever. Cross-region work uses
`RegionizedTaskQueue.queueChunkTask(...)`. A `Thread.sleep`, a `.get()` on
a `CompletableFuture`, or a `synchronized` block that could contend with
a foreign region — all bugs."*

Every contract above enforces this rule; a subagent adding a method that
violates it violates the M9 contract.
