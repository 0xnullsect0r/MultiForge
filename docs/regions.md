# Regions

MultiForge splits each loaded world into **regions** and ticks the regions of
a level in parallel on a worker pool, between two barriers on the server
thread. The tick model itself (what runs where, the barrier, ownership) is
described in [`design/barrier-tick-model.md`](design/barrier-tick-model.md);
this page covers how regions are formed and how an operator configures them.

## Vocabulary

| Term | Meaning |
|---|---|
| Chunk | The 16×16-block column Vanilla already tracks. |
| Section | A square of `2^size` chunks per side (`[region] size`, default 4 → 16×16 chunks). |
| Region | An 8-connected group of occupied sections; the unit that one worker ticks. |
| Global region | A synthetic region, ticked once per server tick before the overworld's level tick. It runs work queued on the global domain (`ServerDomains.global()` tasks and GLOBAL-domain event listeners). |

A section is **occupied** while at least one of its chunks is loaded.

## How regions form

`ThreadedRegionizer` (one per world) keeps the loaded chunks of each section
and the section → region map. It is fed by `RegionizedChunkLifecycle`, which
listens to NeoForge's `ChunkEvent.Load` / `ChunkEvent.Unload`. Vanilla decides
what loads and when; the regionizer only follows.

1. Chunk → section: `sectionX = chunkX >> size`, `sectionZ = chunkZ >> size`.
2. **Load.** When a chunk loads in an unoccupied section, the section joins
   the region of any of its 8 neighbouring sections. If it touches several
   regions, they are merged into the largest one. If it touches none, a new
   region is created.
3. **Unload.** A section leaves its region only when its last loaded chunk
   unloads. If that disconnects the region, each connected component becomes
   its own region (split). A region with no sections left dies.

Consequences:

- One contiguous loaded area is always one region, however large.
- Two regions always have at least one fully unloaded section between them.
  That gap is what makes ticking them in parallel safe.
- Anything an entity or block can reach in one tick is in its own region or
  in an unloaded chunk.

Each region has a state (`RegionState`): `TRANSIENT` → `READY` ⇄ `TICKING`,
`FOLDING` while another region is being merged into it, and `DEAD` (terminal)
after it was merged away or lost its last section.

## Ownership and cross-region work

Ownership is positional: a region owns the chunks of its sections, and an
entity, block entity or scheduled tick belongs to the region owning its chunk.
Nothing is migrated when an entity walks; the region that owns its chunk at the
start of its tick ticks it.

- A mutation from a region worker at a chunk its region does not own is
  rerouted to the owner's mailbox (`RegionizedTaskQueue.queueChunkTask`) and
  runs at that region's next drain. If no region owns the chunk, it goes to
  the server thread.
- A task queued for a chunk that is not loaded waits in an orphan queue and is
  delivered when that chunk loads.
- Cross-region teleports, player dimension changes and command execution
  started on a region worker are deferred to the server thread and run after
  the barrier (`OwnershipGuard.deferCrossRegionMove` / `deferToServerThread`).

## Region pins

A pin is an operator-defined rectangle of chunks whose loaded chunks always
tick in one region, even when they are not adjacent. The regionizer merges
every region holding a section of the pin and does not split the pinned area.
Use it for a build that spans an unloaded gap, such as two farms joined by an
item line, or a base and its chunk loaders.

A pin cannot separate its area from loaded chunks next to it: regions whose
sections touch always merge.

Pins are stored in `config/multiforge-region-pins.toml`:

```toml
[[pins]]
id = "base-north"
world = "minecraft:overworld"
from = [ -128, -128 ]   # chunk coordinates, inclusive
to   = [ 128, 128 ]
```

The file is rewritten by `/multiforge region pin` and `/multiforge region
unpin`, and the change applies immediately. Manual edits are read at the next
server start.

## Configuration

`config/multiforge-server.toml`. A missing file or key means the default; the
file is written the first time a `/multiforge config` or `/multiforge region
size` command changes something. Defaults shown:

```toml
[mtserver]
cores = 8               # default: the JVM's available processors
threadsPerCore = 1      # worker pool = cores × threadsPerCore (at least 1)
mode = "hybrid"         # off | hybrid | strict

[region]
size = 4                # sections are 2^size chunks per side (4 → 16)

[violations]
policy = "warn"         # warn | reroute-only | fail
warnPerMin = 5          # rate-limited warnings per minute per site
```

`policy` controls what a cross-region mutation does: `warn` reroutes it and
logs a rate-limited warning, `reroute-only` reroutes it without logging (the
probe counter still counts it), `fail` throws, as `mode = "strict"` does.

JVM overrides: `-Dmultiforge.workers=N` replaces the computed pool size, and
`-Dmultiforge.mode=off|hybrid|strict` replaces `mode`.

In-game (op only):

```
/multiforge config show | reload
/multiforge config cores <n>
/multiforge config threads <n>
/multiforge config mode <hybrid|strict|off>
/multiforge config policy <warn|reroute-only|fail>
/multiforge config warnPerMin <n>
/multiforge region size <chunks>          # power of 2, 1..256
/multiforge region pin <id> <world> <fromCX> <fromCZ> <toCX> <toCZ>
/multiforge region unpin <id>
/multiforge region list
```

Commands update `multiforge-server.toml` and apply live:

- A changed pool size resizes the worker pool (`TickRegionScheduler.resize`).
- A changed region size re-partitions every world: mailboxes are drained,
  each world's regions are dissolved, the loaded chunks are re-added with the
  new section size, and block-entity tickers are moved to their new owners.
- Switching between `hybrid` and `strict` is immediate. Switching to or from
  `off` takes effect at the next server start, because `off` means the
  regionized runtime is not installed at all.

`/multiforge region list` prints, per world, each region's id, section count,
the maximum number of chunks those sections can hold, and its state, followed
by the pins. `/multiforge chunks <world>` prints the loaded chunk count per
region. See [`multiforge-command.md`](multiforge-command.md) for the full
command reference.

## Tests

- `ThreadedRegionizerTest`: merge on adjacency, split when a bridging
  section unloads, per-section chunk counting.
- `RegionizedTaskQueueTest`: FIFO ordering, orphan reroute, exception
  isolation.
- `ConfigCodecTest`: TOML round-trip, partial files, invalid values.
- `MultiThreadedSchedulerHostTest`: region, global and async scheduling
  against the parallel host.
