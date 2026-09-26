# Chunks

MultiForge does not have its own chunk system. Chunk loading, tickets, load
levels, generation, lighting, unloading and saving are Vanilla's (with
NeoForge's changes) and run on the server thread, unchanged. The runtime only
records which region owns each loaded chunk.

## The ownership index

- `RegionizedChunkLifecycle` listens to NeoForge's `ChunkEvent.Load` and
  `ChunkEvent.Unload`. On load it adds the chunk to the world's
  `ThreadedRegionizer` (which may create, extend or merge regions, see
  [`regions.md`](regions.md)), records it in the world's `ChunkHolderManager`
  under the owning region, and delivers any tasks that were queued for that
  chunk while it had no owner. On unload it removes the chunk from both.
- `ChunkHolderManager` (one per world) maps each loaded chunk to a
  `NewChunkHolder` (position and owning region) and each region to the chunks
  it owns. It also keeps per-region `HolderManagerRegionData`: the region's
  share of the level's block-entity tickers. It is a `RegionListener`, so
  ownership and tickers follow every merge and split.

Nothing here decides what loads. A chunk is in a region exactly while Vanilla
has it loaded as a full chunk.

`/multiforge chunks <world>` prints the loaded chunk count per region, for
example `/multiforge chunks minecraft:overworld`.

## Reading chunks from a region worker

A region worker reads chunks that are already loaded directly: a chunk that
has reached the requested status is served from the visible chunk holder
without going through the server thread.

If a worker needs a chunk that is not loaded yet, Vanilla hands the load to the
server thread and waits for it. The server thread is at that point waiting at
the tick barrier, so `MainThreadHandoff` counts the waiting workers and the
barrier's pump runs the chunk source's pending tasks while at least one worker
waits. This is one of the two designed waits on a region worker (the other is
the serial event lane):

- it is excluded from the `RegionTickWatchdog` overrun check (default 500 ms,
  `-Dmultiforge.watchdog.warn-ms`);
- its time is totalled in the probe `region-tick.wait-ms.main-thread-chunk-load`;
- each hand-off bumps `region.main-thread-chunk-load`.

A steadily rising `region.main-thread-chunk-load` means some region code keeps
reaching into unloaded chunks, which slows that region down. See
[`perf-tuning.md`](perf-tuning.md#probes).

## Ender pearls and other tickets

Tickets are Vanilla's. MultiForge adds no ticket types and does not change how
ender pearls keep chunks loaded.

## What this replaced

Earlier milestones (M3, M9) built a shadow chunk system: per-region ticket
maps, chunk holders with load levels, a chunk task priority scheduler,
facades for `ChunkMap`, `DistanceManager` and the light engine, and a
per-region save journal. It was fed by hooks in Vanilla's chunk code but
never drove loading or saving. It was removed; the ownership index above is
the part that remained in use. See
[`design/barrier-tick-model.md`](design/barrier-tick-model.md#what-this-replaced).
