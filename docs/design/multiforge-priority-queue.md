# MultiForge — ChunkTaskPriorityQueue{,Sorter} disposition

**Status:** Phase 4 task 4.6 of the M9 landing plan
(`plans/bubbly-jumping-comet.md`).
**Verdict:** NO PATCH. Vanilla source remains verbatim.

## Why no patch

Vanilla `ChunkTaskPriorityQueueSorter` and `ChunkTaskPriorityQueue`
(`net.minecraft.server.level.*`) sort chunk tasks by ticket level for the
Vanilla mailbox-per-executor dispatch. MultiForge's per-region equivalent
already exists in `net.multiforge.runtime.chunk.ChunkTaskScheduler`
(per-region priority deque, sorted by `ChunkTaskPriority`). Task 4.6 asks
whether we PATCH Vanilla's sorter to shim into `ChunkTaskScheduler`, or
whether we can leave Vanilla source alone.

Verdict: leave alone. The class is inert once its instantiation sites are
replaced.

## Every instantiation site is already replaced

Grep in `projects/base` for `ChunkTaskPriorityQueueSorter` finds five
callers, each handled by another Phase 4 subagent:

| Caller | Phase 4 disposition |
|---|---|
| `ChunkMap.java:184` (`new ChunkTaskPriorityQueueSorter(...)`) | Deleted from `MultiForgeChunkMap` field set (`multiforge-chunkmap.md` §8: `queueSorter` REPLACED by `ChunkTaskScheduler`). Task 4.1b. |
| `DistanceManager.java:59` (`new ChunkTaskPriorityQueueSorter(...)`) | Deleted; `MultiForgeDistanceManager` routes ticket writes through `ChunkHolderManager.addTicket/removeTicket`. Task 4.2b. |
| `ThreadedLevelLightEngine.java:32` (`sorterMailbox` field) | Deleted; `MultiForgeLightEngine` fans out via `RegionizedTaskQueue.queueChunkTask`. Task 4.5b. |
| `WorldGenContext.java:15` (`mainThreadMailBox` typed as `ProcessorHandle<Message<Runnable>>`) | The `Message<Runnable>` envelope type stays; `MultiForgeChunkMap` supplies a `ProcessorHandle` that routes into `ChunkTaskScheduler.scheduleChunkTask(..., NORMAL)` instead of a real sorter. Task 4.1b. |
| `ChunkStatusTasks.java:211` (`ChunkTaskPriorityQueueSorter.message(...)`) | The static `message(...)` factory stays; the envelope is delivered via the region-routed `ProcessorHandle` from `WorldGenContext`. Reused verbatim. |

Nothing instantiates `ChunkTaskPriorityQueueSorter` after Phase 4 lands.
The class compiles, the static inner `Message`/`Release` types stay
type-visible for `ProcessorHandle<Message<Runnable>>` references, and the
static factories `message(...)` / `release(...)` stay callable as pure
envelope constructors. But no executor thread ever drains an instance,
because no instance exists.

## Why not the PATCH (thin shim) verdict from `m9-patch-strategy.md`

`m9-patch-strategy.md` §ChunkTaskPriorityQueueSorter proposed PATCH (thin
shim) preserving three API seams:

1. `implements ChunkHolder.LevelChangeListener` — used by `ChunkMap` to
   pass the sorter as the listener when constructing `ChunkHolder`s.
2. `Message<T>` / `Release` static inner classes.
3. `getProcessor` / `getReleaseProcessor` returning `ProcessorHandle`s.

All three seams survive under NO PATCH:

1. `MultiForgeChunkMap` (Task 4.1b) constructs `ChunkHolder`s with a
   different `LevelChangeListener` implementation — a lambda that pushes
   the new level into the shim's `NewChunkHolder`. Vanilla's sorter is
   never used as a listener because Vanilla's sorter is never
   instantiated.
2. The static inner classes remain compilable and referenceable.
3. `getProcessor` / `getReleaseProcessor` are dead-code-in-source but
   compile. If a foreign mod reflectively instantiates the sorter and
   calls these, it gets a working sorter with its own mailbox executor —
   isolated from MultiForge's per-region path — which is the safest
   possible fallback: the mod's private sorter drives its own tasks,
   MultiForge is unaware.

## Downgrade path

Because Vanilla source is untouched, a `git checkout -- multiforge-patches`
plus a boot without the MultiForge fork rehydrates a stock NeoForge that
uses the sorter normally. No patch to revert.

## Rebase-drift risk

ZERO. New Vanilla methods on `ChunkTaskPriorityQueueSorter` land as
untouched source; MultiForge doesn't care because MultiForge never
instantiates the class.

## Verification

- `grep -rn "new ChunkTaskPriorityQueueSorter" projects/` returns hits
  only in classes handled by Phases 4.1, 4.2, 4.5 (each of which deletes
  its call site).
- `find multiforge-patches/04-chunk-system -name 'ChunkTaskPriorityQueueSorter*'`
  is empty (this note is the only artifact 4.6 produces).
- Fork `compileJava` remains green — the class compiles as vendored
  Vanilla source with no MultiForge touch.
