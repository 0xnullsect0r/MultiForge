# MultiForgeLightEngine — Design

**Status:** Phase 4 task 4.5a of the M9 landing plan (`plans/bubbly-jumping-comet.md`).
**Verdict driver:** `docs/design/m9-patch-strategy.md` §ThreadedLevelLightEngine — REPLACE.
**Non-goals:** implementing the class (task 4.5b), redesigning `LevelLightEngine`
(superclass reused verbatim), reworking block/sky `LayerLightEngine`s.

---

## 1. Purpose

Replace Vanilla `net.minecraft.server.level.ThreadedLevelLightEngine` with
`net.multiforge.neoforge.chunk.MultiForgeLightEngine`, which owns per-region
light propagation instead of the single-mailbox model at
`ThreadedLevelLightEngine.java:29-34`. Cross-region propagation routes
through `RegionizedTaskQueue.queueChunkTask` on the neighbour's owning
worker. The `LevelLightEngine` public API is preserved so mods calling
`getLightEngine()` still get a valid `ThreadedLevelLightEngine` reference.

---

## 2. Vanilla ThreadedLevelLightEngine architecture

- Extends `LevelLightEngine`; implements `AutoCloseable`
  (`ThreadedLevelLightEngine.java:26`).
- Every mutating call (`checkBlock`, `updateSectionStatus`,
  `propagateLightSources`, `setLightEnabled`, `queueSectionData`,
  `retainData`, `initializeLight`, `lightChunk`, `updateChunkStatus`)
  enqueues a `Runnable` on `sorterMailbox` (`:132-138`) via
  `ChunkTaskPriorityQueueSorter.message(...)`. The sorter pushes onto
  `lightTasks` (`:30, 133`), and when the list crosses
  `taskPerBatch == 1000` (`:33, 134`) fires `runUpdate()` inline.
- `runLightUpdates()` on the caller thread is explicitly forbidden
  (`:54-56` — throws `UnsupportedOperationException` via
  `Util.pauseInIde`). Actual propagation runs inside `runUpdate()`
  (`:194-217`), driven by `tryScheduleUpdate()` (`:185-192`) posting
  onto `taskMailbox`.
- Reads MAY happen off-thread: superclass storage (`BlockLightEngine`,
  `SkyLightEngine` + their `DataLayerStorageMap`s) is thread-safe by
  Vanilla's single-writer/many-reader mailbox construction.
- Writes are serialised onto one `"light"` `ProcessorMailbox<Runnable>`
  (`:29`), batched up to 1000 tasks per `runUpdate()` (`:195, 33`).
- Task ordering interleaves `TaskType.PRE_UPDATE` / `POST_UPDATE`
  (`:224-227`): pre seeds the propagator, `super.runLightUpdates()`
  (`:207`) propagates, post signals `CompletableFuture` completion for
  `initializeLight`, `lightChunk`, `waitForPendingTasks`.

---

## 3. Class shape

```java
package net.multiforge.neoforge.chunk;

@ApiStatus.Internal
public final class MultiForgeLightEngine
        extends net.minecraft.server.level.ThreadedLevelLightEngine {

    private final MultiThreadedSchedulerHost host;
    private final WorldRef worldRef;
    // Superclass fields (taskMailbox, sorterMailbox, lightTasks, scheduled)
    // are inherited but never used — see §7.

    public MultiForgeLightEngine(
        LightChunkGetter lightChunkGetter,
        ChunkMap chunkMap,
        boolean skyLight,
        MultiThreadedSchedulerHost host,
        WorldRef worldRef) {
        // No-op mailbox handles up; reflective mods still resolve the fields.
        super(lightChunkGetter, chunkMap, skyLight, NO_OP_MAILBOX, NO_OP_SORTER);
        this.host = host;
        this.worldRef = worldRef;
    }
}
```

Extending Vanilla's `ThreadedLevelLightEngine` (not `LevelLightEngine`
directly) preserves the type identity `ChunkMap` construction and mods
reflecting on `getLightEngine()` expect.

---

## 4. Delegation strategy per method

Every mutating override routes to the target chunk's owning region worker
via `host.taskQueue().queueChunkTask(worldRef, chunkX, chunkZ, task)`.
That entry point pins section → region under the regionizer read lock
(`RegionizedTaskQueue.java:121`), so merges/splits mid-enqueue are safe.
The task body calls `super.<method>(...)` — block/sky storage updates
happen in the region worker's serial context, matching Vanilla's
per-chunk mailbox invariant.

### 4.1 `checkBlock(BlockPos pos)` — `:59-67`

```java
@Override
public void checkBlock(BlockPos pos) {
    BlockPos immutable = pos.immutable();
    int chunkX = SectionPos.blockToSectionCoord(immutable.getX());
    int chunkZ = SectionPos.blockToSectionCoord(immutable.getZ());
    host.taskQueue().queueChunkTask(worldRef, chunkX, chunkZ,
        () -> super.checkBlock(immutable));
}
```

### 4.2 `updateSectionStatus(SectionPos, boolean)` — `:86`
Route to `(pos.x(), pos.z())`; body `super.updateSectionStatus(pos, isEmpty)`.

### 4.3 `propagateLightSources(ChunkPos pos)` — `:97`
Route to `(pos.x, pos.z)`; body `super.propagateLightSources(pos)`.

### 4.4 `setLightEnabled(ChunkPos pos, boolean enabled)` — `:107`
Route to `(pos.x, pos.z)`; body `super.setLightEnabled(pos, enabled)`.

### 4.5 `queueSectionData(LightLayer, SectionPos, @Nullable DataLayer)` — `:117`
Route to `(pos.x(), pos.z())`; body `super.queueSectionData(...)`.

### 4.6 `retainData(ChunkPos pos, boolean retain)` — `:141`
Route to `(pos.x, pos.z)`; body `super.retainData(pos, retain)`.

### 4.7 `initializeLight(ChunkAccess chunk, boolean lit)` — `:151`

Vanilla returns `CompletableFuture<ChunkAccess>` completing after the
PRE-pass (updateSectionStatus loop) and POST-pass (`setLightEnabled` +
`retainData`). MultiForge collapses both into one region task:

```java
@Override
public CompletableFuture<ChunkAccess> initializeLight(ChunkAccess chunk, boolean lit) {
    ChunkPos pos = chunk.getPos();
    CompletableFuture<ChunkAccess> future = new CompletableFuture<>();
    host.taskQueue().queueChunkTask(worldRef, pos.x, pos.z, () -> {
        try {
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < chunk.getSectionsCount(); i++) {
                if (!sections[i].hasOnlyAir()) {
                    int sy = levelHeightAccessor.getSectionYFromSectionIndex(i);
                    super.updateSectionStatus(SectionPos.of(pos, sy), false);
                }
            }
            super.setLightEnabled(pos, lit);
            super.retainData(pos, false);
            future.complete(chunk);
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
    });
    return future;
}
```

Vanilla's PRE/POST split collapses because the region worker is already
single-threaded per chunk — no batch-flush interleave needed.

### 4.8 `lightChunk(ChunkAccess chunk, boolean lit)` — `:171`

Same shape as `initializeLight`. `chunk.setLightCorrect(false)` runs on
the caller thread (Vanilla already does; `:173`); the propagate +
`setLightCorrect(true)` flip runs on the region worker.

### 4.9 `updateChunkStatus(ChunkPos pos)` — `:69`

Route to `(pos.x, pos.z)`; body performs Vanilla's clear-and-reset dance
— `retainData(pos, false)`, `setLightEnabled(pos, false)`, all-section
`queueSectionData(BLOCK/SKY, null)`, all-section
`updateSectionStatus(true)`.

### 4.10 `runLightUpdates()` — `:54`

Stays a hard `UnsupportedOperationException` on the caller thread —
Vanilla contract preserved. Phase 5 drives per-region drain from
`PhasedRegionTickBody.Phase.REGION_EVENTS`; no caller-side scheduling.

### 4.11 `tryScheduleUpdate()` — `:185`

No-op. Region workers invoke `super.runLightUpdates()` inline at the
tail of each per-chunk task body — no batch-flush to schedule.

### 4.12 `waitForPendingTasks(int chunkX, int chunkZ)` — `:219`

Route to `(chunkX, chunkZ)`; empty body. FIFO ordering means the future
completes after every previously-queued light task on that chunk —
preserving Vanilla's happens-before for chunk-send.

---

## 5. Reads (unchanged)

Every read inherits verbatim from `LevelLightEngine`:

- `getLightData(LightLayer, SectionPos)` — reads block/sky
  `DataLayerStorageMap`.
- `getRawBrightness(BlockPos, int)`.
- `getBlockLight(BlockPos)` / `getSkyLight(BlockPos)`.

Safe from any thread: Vanilla's superclass storage is publish-once
(`AtomicReferenceArray<DataLayerStorageMap>` inside `LayerLightEngine`),
and MultiForge's per-chunk write serialisation strengthens that. No
override.

---

## 6. Cross-region light propagation

Vanilla's `BlockLightEngine.checkNeighborsAfterUpdate` may propagate into
a section whose chunk sits in a *different* MultiForge region. Under
Vanilla that's in-mailbox recursion; under MultiForge it's a
cross-region hop.

**Detection:** region A's worker, inside `super.checkBlock`, fires a
neighbour update that lands back in the MultiForge override (§4.1). The
override re-resolves the neighbour's owning region via
`host.taskQueue().queueChunkTask(worldRef, nx, nz, ...)`; the regionizer
routes it — same-region hops run inline, cross-region hops go to B's
worker.

**Ordering guarantee:** per-chunk light updates are FIFO within a
region. Across regions there is no total order, but light propagation is
idempotent per position (re-running `checkBlock(pos)` on a
correct-value position is a no-op inside
`BlockLightEngine.computeLevelFromNeighbor`). No correctness issue.

**Merge/split safety:** `queueChunkTask` takes the regionizer read lock
around resolve+enqueue (Phase 1 task 1.2) — a merge/split mid-hop
cannot orphan the queued task.

---

## 7. Fields DELETED

The facade inherits these fields but never uses them; the constructor
forwards no-op handles so reflective mods resolve without NPE:

- `taskMailbox: ProcessorMailbox<Runnable>` (`:29`) — replaced by
  `RegionizedTaskQueue` per-region routing.
- `sorterMailbox: ProcessorHandle<...>` (`:32`) — routing goes through
  `ChunkTaskScheduler` (via `RegionizedTaskQueue`) directly.
- `lightTasks: ObjectList<Pair<TaskType, Runnable>>` (`:30`) — the
  PRE/POST batching queue is unused; each region task runs inline.
- `scheduled: AtomicBoolean` (`:34`) — `tryScheduleUpdate` is a no-op.
- `taskPerBatch: int` (`:33`) — no batching under MultiForge.

A conceptual analogue of Vanilla `ChunkMap.updatingChunks: AtomicLong`
(not on `ThreadedLevelLightEngine` itself) moves to per-region counters
inside `HolderManagerRegionData`.

---

## 8. Fields KEPT

- The whole superclass `LevelLightEngine` state — block-light engine,
  sky-light engine, `DataLayerStorageMap` arrays, `levelHeightAccessor`.
  Reused verbatim; writes stay serial per chunk via region routing.
- `chunkMap: ChunkMap` (`:31`). Referenced by `MultiForgeChunkMap`
  construction wiring and by `updateChunkStatus`'s section-clear loop.

---

## 9. Threading contract

- **Read methods**: safe from any thread (§5).
- **Write methods**: safe from any thread — every override delegates to
  the region worker owning the target chunk before touching superclass
  state. Off-owner callers get a queued hop, not a direct write.
- **`runLightUpdates()`** on the caller thread: forbidden — throws, same
  as Vanilla. Phase 5 wiring
  (`ChunkTaskScheduler.drainInto(regionId)` on
  `PhasedRegionTickBody.Phase.REGION_EVENTS`) drives completion, but
  that call path lands on a region worker, never a mod thread.
- **Single-writer per chunk** guaranteed by `RegionizedTaskQueue`
  serialising every chunk task for `(worldRef, chunkX, chunkZ)` onto
  that chunk's region owner.

---

## 10. NeoForge patches to preserve

`diff` of `projects/base/.../ThreadedLevelLightEngine.java` against
`projects/neoforge/.../ThreadedLevelLightEngine.java` produces zero
output — NeoForge has no delta. Only obligation: keep the constructor
signature (`LightChunkGetter, ChunkMap, boolean, ProcessorMailbox<Runnable>,
ProcessorHandle<...>`) so `ChunkMap` construction still compiles.

---

## 11. Migration order for Phase 4.5b

1. **Constructor + no-op mailbox forwarding** — proves the type resolves
   from `ChunkMap`.
2. **`checkBlock(BlockPos)`** — hottest and simplest mutator; unit-test
   one-chunk propagation first.
3. **`propagateLightSources(ChunkPos)`** and
   **`updateSectionStatus(SectionPos, boolean)`** — direct region hops.
4. **`setLightEnabled` / `retainData` / `queueSectionData`** — same
   shape as #3.
5. **`updateChunkStatus(ChunkPos)`** — multi-step body inside one region
   task; guards §4.9's clear-and-reset semantics.
6. **`initializeLight` / `lightChunk`** — future-returning; wire
   `CompletableFuture` completion off the region worker.
7. **`waitForPendingTasks`** — FIFO barrier; needed for
   `PlayerChunkSender` correctness.
8. **`runLightUpdates` = throw**, **`tryScheduleUpdate` = no-op** —
   trivial; land last so integration tests exercise the real routing
   first.
9. **Cross-region propagation smoke test** — two-region world (100k-block
   gap), fire a light source on the shared border, assert propagation on
   both sides. Ties §6 to CI.
