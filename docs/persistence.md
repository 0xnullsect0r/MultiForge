# Persistence — per-region autosave + WAL journal

**Milestone:** M6.
**Runtime packages:** `net.multiforge.runtime.journal`, `net.multiforge.runtime.shutdown`.

## Why

Vanilla saves every dirty chunk on a single-threaded pass and stalls the
tick loop for the duration. On a large modded world that pause can hit
several seconds. MultiForge's regions tick in parallel — the persistence
layer has to match: each region autosaves its own chunks on its own
budget, and if the JVM dies mid-save, recovery has to be able to
replay whatever was committed to disk before the crash.

## Autosave

Per-region:

- `HolderManagerRegionData.autoSaveQueue` — the set of dirty
  `NewChunkHolder`s waiting to be persisted, owned by that region.
- `AutoSaveRunner` — drains the queue on a rolling budget:
  `maxChunksPerTick` chunks *or* `maxNanosPerTick` wall time,
  whichever comes first. Called from the region tick body once
  post-tick work is done.

Each saved chunk is journaled first, then the dirty flag is cleared
and the holder leaves the queue. Clean holders that got queued (a mod
called `markDirty` and then reverted) are silently removed without a
disk write.

## Write-ahead journal

`RegionJournal` is a per-region append-only log at
`world/multiforge/journal/region-<id>.mjl`. Frame layout:

```
int32   magic     = 0xF01A4A4C
int32   version   = 1
int64   regionId
int64   sequence
int8    kindOrdinal (JournalEntryKind)
int32   payloadLen
byte[]  payload
int32   crc32(regionId..payload)
```

- **Single writer per file** — the owning region worker. No locks.
- **Fsync-on-append.** `append()` calls `FileChannel.force(true)`
  before returning, so a `seq` handed back to the caller is durable.
- **CRC-guarded.** A torn tail (crash mid-write) is truncated on
  replay; a byte flip inside the entry surfaces as
  `IOException("journal CRC mismatch")` on `open()`.
- **Monotonic sequence.** Sequences continue across restarts — on
  reopen `RegionJournal` scans for the max sequence in the file and
  the next `append()` starts from `max + 1`.

Entry kinds (`JournalEntryKind`):

- `CHUNK_SAVE` — a chunk autosave snapshot.
- `ENTITY_MIGRATION` — a cross-region teleport, journaled by both
  source and destination so recovery can tell whether the transfer
  finished.
- `REGION_MERGE` / `REGION_SPLIT` — regionizer topology changes.
- `TICK_MARK` — periodic checkpoint so recovery knows how far the
  region got.

## Recovery on boot

`JournalReplayHarness` is the entry point:

```java
JournalReplayHarness harness = new JournalReplayHarness()
    .on(JournalEntryKind.CHUNK_SAVE,       chunkReplayer::apply)
    .on(JournalEntryKind.ENTITY_MIGRATION, migrationRecovery::apply)
    .on(JournalEntryKind.REGION_MERGE,     regionizer::replayMerge)
    .on(JournalEntryKind.REGION_SPLIT,     regionizer::replaySplit)
    .onUnknown(unknownEntry -> log.warn("skipping unknown journal kind {}", ...));

try (RegionJournal j = RegionJournal.open(regionId, dir)) {
    harness.replay(j);
}
```

Unknown kinds (a newer journal opened by an older build) are routed to
`onUnknown` — the caller decides whether to skip or bail.

## Shutdown protocol

`RegionShutdownCoordinator` sequences the shutdown across every region:

1. **ACCEPTING** → **DRAINING_INBOX.** The network layer stops
   accepting new packets; workers keep ticking so in-flight mailbox
   entries and migrations finish. Bounded by `drainDeadline` wall
   time — if the deadline is exceeded the coordinator advances anyway.
2. **FLUSHING_JOURNAL.** Every tracked `RegionJournal.close()` is
   called; `close()` is fsync-on-flush and idempotent. Errors are
   collected as `IOException.addSuppressed` so one failing region does
   not skip the others.
3. **STOPPING_WORKERS.** `TickRegionScheduler.close()` is called and
   the pool is shut down.
4. **STOPPED.** Terminal; safe to release the JVM.

Every phase transition fires `ProgressListener.onPhaseAdvanced` with the
inbox depth + migrations-in-flight at that moment, so `/stop` can log a
line like:

```
[MultiForge] shutdown ACCEPTING → DRAINING_INBOX (inbox=142, migrations=3)
[MultiForge] shutdown DRAINING_INBOX → FLUSHING_JOURNAL (inbox=0, migrations=0)
[MultiForge] shutdown FLUSHING_JOURNAL → STOPPING_WORKERS
[MultiForge] shutdown STOPPING_WORKERS → STOPPED
```

## Failure modes

- **`/stop` timeout with backlog.** The coordinator advances even
  though `drainedCompletely()` is false. Whatever was still in the
  inbox is lost; the operator sees the last phase transition's
  `inbox=N` count in the log.
- **Fsync fails mid-append.** The file is left in an
  inconsistent state; on next boot the torn tail is discarded (the
  short-trailing-bytes case is tolerated). Any prior committed
  entries replay correctly.
- **CRC mismatch inside an otherwise-valid entry.** Boot fails hard
  with a clear IOException so the operator knows to escalate rather
  than silently losing data.

## Files

Under `world/multiforge/`:

- `journal/region-<id>.mjl` — one per live region.
- `pins.dat` (from M5) — persisted operator pins.

Old journals for dead regions are cleaned up post-shutdown when the
patch layer runs the region GC — this pure-Java module doesn't touch
disk beyond the journal itself.
