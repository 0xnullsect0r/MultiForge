# MultiForge Chunk System (M3)

## What it replaces

Vanilla NeoForge uses `ChunkHolder` (one per loaded chunk position),
`ChunkMap` (the flat holder table), `DistanceManager` (ticket-driven
level assignment), and `ServerChunkCache.mainThreadProcessor` (a
BlockableEventLoop that drains chunk tasks).

MultiForge replaces this with a **per-region** chunk holder pipeline,
mirroring Folia's rewrite. Every chunk belongs to exactly one region;
tickets, load-level state, and the autosave queue live under that
region and move atomically when regions merge or split.

## Types

| Type | Role |
|---|---|
| `ChunkLoadLevel` | Enum ladder: INACCESSIBLE → BORDER → TICKING → ENTITY_TICKING. Lower numeric `distance()` = more loaded. Matches Vanilla's `FullChunkStatus`. |
| `TicketType` | Named kind of ticket with a default distance. Constants: PLAYER (31), FORCED (33), START (32), PLUGIN (33), POST_TELEPORT (31), ENDER_PEARL (32). |
| `Ticket` | `(type, distance, key)` triple. Two tickets equal iff all three match, so the `key` field de-duplicates same-kind holds. |
| `PerChunkTickets` | Set of tickets on one chunk; `minDistance()` gives the effective load distance. |
| `PerRegionTicketMap` | `chunkPos → PerChunkTickets` for one region, with `merge` and `split` for topology transitions. |
| `NewChunkHolder` | Per-chunk holder: world+pos, owning region id, current level, dirty flag, pending-full-load flag. |
| `HolderManagerRegionData` | Per-region state carried alongside a `Region` — `pendingFullLoadUpdate` deque + `autoSaveQueue`. |
| `ChunkHolderManager` | Per-world map of holders + per-region data. Ticket writes here promote/demote the holder's level and enqueue full-load updates. |
| `ChunkTaskPriority` | BLOCKING > HIGHEST > HIGH > NORMAL > LOW > LOWEST > IDLE. |
| `ChunkTaskScheduler` | `scheduleChunkTask(world, x, z, run, priority)` — puts the task in a per-region priority deque, wakes the owner via `RegionizedTaskQueue`. |

## Load-level flow

1. Something adds a ticket (`ChunkHolderManager.addTicket`).
2. The ticket lands in the owning region's `PerRegionTicketMap`.
3. `PerChunkTickets.minDistance()` returns the smallest distance across
   the chunk's tickets. `ChunkLoadLevel.forDistance(d)` maps that to a
   level.
4. If the level rose (or fell) since the last update, the holder's
   level is set and it lands on `HolderManagerRegionData.pendingFullLoadUpdate`.
5. The region worker drains the queue on its next tick and asks the
   binding (Vanilla in M3 patches; a stub in M3 pure-Java) to resolve
   the chunk's futures for the new level.

Removing a ticket does the reverse.

## Ender-pearl fix

Folia observed that Vanilla's global ticket counter can be exhausted
by many ender pearls in flight. MultiForge takes the same fix: use a
per-entity key.

```java
Ticket t = Ticket.of(TicketType.ENDER_PEARL, entityUuid);
```

Two pearls belonging to different entities count as two distinct
tickets on the same chunk. Only removing the specific one is
idempotent — no shared global counter.

## Region merges and splits

Every `ChunkHolderManager` gets a `onRegionMerged(target, source)` /
`onRegionSplit(source, target, shouldLeave)` pair. Both operations:

- Reassign holder `owningRegion` for the affected chunks.
- Fold or peel off `HolderManagerRegionData` (autosave queue,
  pending-full-load queue).
- Fold or peel off `PerRegionTicketMap` entries.

`ChunkTaskScheduler.onRegionMerged` moves priority deques between
regions. Splits re-schedule tasks lazily — the new owner's next tick
re-populates its deques via `scheduleChunkTask`.

## Priority routing

`ChunkTaskScheduler` maintains an `EnumMap<ChunkTaskPriority,
ConcurrentLinkedDeque<Runnable>>` per region. On `scheduleChunkTask`:

1. Enqueue the runnable in the appropriate priority deque.
2. Wake the region by adding a small "drain trampoline" runnable to
   the region's inbox. When the region worker ticks and drains its
   inbox, the trampoline runs and calls `drainInto(region)` — which
   pops runnables in strict BLOCKING → IDLE order.

This composes with the M2 region scheduler: the same
`RegionizedTaskQueue` and `TickRegionScheduler` deliver both scheduler
API tasks and chunk-system tasks; priority ordering is an added filter
on the chunk-system side.

## What M3 pure-Java gives up

- No worldgen. The chunk-system layer models the ticket + holder
  bookkeeping only; actually producing chunk NBT / running world
  generation belongs in M3's Vanilla patches under
  `multiforge-patches/04-chunk-system/`.
- No lighting engine port. The `ThreadedLevelLightEngine` from
  NeoForge stays; the M3 patches add ownership guards around its
  calls so cross-region light propagation goes via the task queue.
- No IO. Save/load calls stay on Vanilla until M6 adds the WAL
  journal.
- Split callback on `ChunkTaskScheduler` is a no-op — priority-queue
  contents don't need to be handed over because the new owner's
  ticking rebuilds them from the holder set.

## Test coverage

- `ChunkLoadLevelTest` — `forDistance` mapping and `isAtLeast`
  monotonicity.
- `PerChunkTicketsTest` — min-distance across a mixed ticket set,
  dedup on same-key, ender-pearl per-entityId isolation.
- `ChunkHolderManagerTest` — PLAYER ticket promotes holder to
  ENTITY_TICKING, last-ticket removal demotes to INACCESSIBLE, merge
  reassigns ownership + folds autosave queue, split peels off
  matching chunks into a fresh region.
- `ChunkTaskSchedulerTest` — strict priority order across BLOCKING /
  HIGH / NORMAL / LOW, `pending(region, priority)` counters.
