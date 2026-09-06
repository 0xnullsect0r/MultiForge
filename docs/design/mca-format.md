# MultiForge RegionFile (MCA) Format Contract

*Phase 0 task 0.4 of the M9 landing plan. Freezes the byte-level contract
that Phase 3 (`RegionFileHeader`/`Reader`/`Writer`/`Cache`) must obey so
per-region autosave (Phase 5 task 5.3) can stop serializing on Vanilla's
`RegionFileStorage` mutex.*

## Decision: byte-identical to Vanilla, no superset

MultiForge's `.mca` output must be byte-for-byte substitutable with
Vanilla's under identical input. **Downgrade-friendly** is the reason of
record: uninstalling MultiForge (or a fallback path that lands on Vanilla
`RegionFileStorage`) must be able to open a world that MultiForge last
wrote. A superset — even with a graceful-fallback header — invalidates
the "revert by removing the jar" property.

There is no performance argument for a new format here. MultiForge's
throughput win comes from removing the `synchronized` mutex on
`RegionFile.write` (line 288) and Vanilla's global cache lock, not from
a new on-disk layout. A private format also blocks the byte-identical
determinism regression that Phase 3 task 3.7 depends on.

## 1. Format overview (Vanilla, verbatim)

All multi-byte integers are big-endian.

| Bytes | Purpose | Cite |
|---|---|---|
| 0–4095 | Location table: 1024 × 4-byte entries `(sectorOffset:24, sectorCount:8)` packed as `sectorOffset << 8 \| sectorCount` | `RegionFile.java:42, 60-62, 204-206` |
| 4096–8191 | Timestamp table: 1024 × 4-byte last-modified epoch seconds | `RegionFile.java:62-63, 156-158` |
| 8192+ | Chunk payloads, sector-aligned at `sectorOffset * 4096` | `RegionFile.java:288-309` |

Sector size is `SECTOR_BYTES = 4096` (`RegionFile.java:27`). Sectors 0
and 1 are the header and reserved via `usedSectors.force(0, 2)`
(`RegionFile.java:70`). File length is always padded up to a full sector
on close (`RegionFile.java:369-377`).

**Location index formula:** `chunk.regionLocalX + chunk.regionLocalZ * 32`
(`RegionFile.java:352-354`). Row-major, X-fast.

**Chunk payload layout** at each sector-aligned offset
(`RegionFile.java:113-154, 288-309`, `CHUNK_HEADER_SIZE = 5` at line 30):

| Bytes | Field |
|---|---|
| 0–3 | `length` (uint32 BE) — count of the following bytes *including* the compression byte |
| 4 | `compressionType` (uint8), high bit = external-file flag |
| 5..5+length-1 | Compressed payload |
| ...to next sector boundary | Undefined padding |

**Compression types:** 1=gzip, 2=deflate, 3=none, 127=custom-ident.
The MSB (`EXTERNAL_STREAM_FLAG = 128`, `RegionFile.java:34`) OR'd into
the type byte marks external-file spillover; strip with `& 0x7F`
(`RegionFile.java:160-166`).

**External `.mcc` files:** when a chunk compresses to `≥ 256` sectors
(`EXTERNAL_CHUNK_THRESHOLD`, `RegionFile.java:35`), Vanilla spills
payload to `c.<regionLocalX>.<regionLocalZ>.mcc` in the same directory
(`RegionFile.java:107-110`), writes a 5-byte stub `(length=1, type|0x80)`
into the region file at a freshly-allocated 1-sector slot
(`RegionFile.java:297-304, 320-326`), and reads it back via
`createExternalChunkInputStream` (`RegionFile.java:190-198`). Note the
actual naming — `c.X.Z.mcc`, not `<base>-X-Z.mcc` — matches Vanilla exactly.

## 2. Contract for MultiForge's implementation

Byte-for-byte match with Vanilla under all of:

- **Sector allocator:** first-fit with gap coalescing, matching Vanilla
  `RegionBitmap`. Header sectors 0–1 pinned. Free-on-relocate is deferred
  until after new sectors are written and the header is flushed
  (`RegionFile.java:288-318`) — never lose a chunk to a mid-write crash.
- **Compression:** default deflate (compressionType `= 2`), configured via
  the same `RegionFileVersion.getSelected()` system-property path so any
  operator override that Vanilla honors also flows through MultiForge.
- **External `.mcc` handling:** fully supported, threshold at 256 sectors,
  atomic temp-file + `Files.move(..., REPLACE_EXISTING)` write
  (`RegionFile.java:328-337`), stub layout as above.
- **Timestamp field:** `System.currentTimeMillis() / 1000L` on every
  write, matching `Util.getEpochMillis() / 1000L` (`RegionFile.java:156-158`).
  The semantic-diff mode from Phase 0.3 ignores this field; the
  byte-identical parity regression (§8) does not — capture the wall clock
  at the tick boundary before calling into the writer to keep runs
  reproducible.

## 3. Concurrency contract

Vanilla uses a single `synchronized` on both read and write
(`RegionFile.java:113, 288`). MultiForge writes from region worker
threads and cannot afford that mutex. One MCA covers 1024 chunks; regions
are smaller, so multiple regions can co-own a file. **Per-file
`ReadWriteLock` is mandatory.**

- **Read lock:** cross-region concurrent reads permitted.
- **Write lock:** exclusive. Sector alloc, header write, payload write,
  and `.mcc` swap are one atomic critical section. A mid-section crash
  leaves the old header pointing at the old sectors — matches Vanilla.
- **No blocking on the region worker** (CLAUDE.md item 4). If the write
  lock cannot be acquired without blocking, defer via
  `RegionizedTaskQueue.queueChunkTask` to the next tick.

## 4. `RegionFileCache` contract

- Open-file pool per world, LRU-evicted at `MAX_CACHE_SIZE = 256`
  (`RegionFileStorage.java:19`). Bump to 512 only if bench numbers show
  eviction thrash; default matches Vanilla for parity.
- File open is idempotent under a per-key striped lock: two concurrent
  openers of the same region key block briefly, then share the single
  instance returned by the winner.
- **Eviction is orderly:** flush pending writes, `FileChannel.force(true)`
  (matches `RegionFile.close()` at `RegionFile.java:357-367`), then close.
  Never evict a file with an outstanding write lock — that write completes
  first.
- **Deletion of empty files:** on close, if the entire location table is
  zero and no `.mcc` sidecars exist, delete the file. Vanilla does not
  do this — but Vanilla also never generates such files under normal
  play. It is a strict superset of Vanilla behavior at the *filesystem*
  level while remaining byte-identical at the *file* level.

## 5. `RegionFileWriter` sector allocation

Reproduce `RegionBitmap` semantics exactly:

- Bitmap over used sectors, initialized from a scan of the location table
  (`RegionFile.java:80-98`) at file open.
- `allocate(count)`: first-fit search for `count` contiguous free sectors;
  extend file if none found.
- `free(sector, count)`: mark sectors free; **do not truncate the file**.
  Vanilla never shrinks; matching that keeps allocator behavior on
  subsequent writes byte-identical.
- **Rewrite:** allocate new sectors and write payload *before* freeing
  the old ones (`RegionFile.java:311-317`). If the new size fits in the
  old sector range, in-place is a legal optimization but not required —
  it changes the sector layout vs. Vanilla only when the payload
  happens to shrink into place, and the byte-identical parity test (§8)
  will catch any drift.

## 6. `RegionFileReader` decompression

- Read `(length, compressionType, payload[length-1])`. **Bounds-check
  payload against file end BEFORE allocating.** `WorldDiff.canonicalMcaHash`
  (WorldDiff.java:141-189) already documents the two overflow sites: a
  24-bit `sectorOffset × 4096` and a `payloadStart + 4 + chunkLength`
  that both wrap negative on corrupt input. Use long arithmetic.
- Fail-soft: a corrupted length, a truncated payload, an out-of-bounds
  sector, or an unknown compression type returns `null` with a
  rate-limited warn — **never throws from a mod call path** (CLAUDE.md
  item 5). Match Vanilla's `LOGGER.error` + return-null shape
  (`RegionFile.java:125, 131, 141, 145, 182`).
- External `.mcc` payload: if `compressionType & 0x80` and `length == 1`,
  read `c.<x>.<z>.mcc` from the region's directory with the low-bit
  compression type (`RegionFile.java:135-146, 190-198`). Missing file =
  warn + return null.

## 7. `ChunkSerializer` split

Phase 3 task 3.5 **wraps** Vanilla `net.minecraft.world.level.chunk.storage.ChunkSerializer` — it does not reimplement `CompoundTag ↔ LevelChunk`. The wrapper's only job is thread routing:

1. `ChunkSerializer.write(ServerLevel, ChunkAccess) → CompoundTag` on
   the owning region's worker (Phase 5.3 guarantee).
2. `NbtIo.writeCompressed(CompoundTag, DataOutput)` same thread, into a
   `ByteArrayOutputStream`.
3. `byte[]` handed to `RegionFileWriter.write(chunkX, chunkZ, bytes)`,
   which takes the file write lock for alloc + header + `.mcc` spill.

No cross-region reference reads inside step 1. Neighbour requests route
through `RegionizedTaskQueue.queueChunkTask` (CLAUDE.md item 4).

## 8. Byte-identical parity test (Phase 3 task 3.7)

- Take the fixed-seed determinism world from `multiforge-bench`.
- For every `.mca`:
  1. Read via Vanilla `RegionFile`.
  2. Rewrite via `RegionFileWriter` with a stubbed clock returning the
     original file's timestamp field per slot.
  3. `assertArrayEquals(originalBytes, rewrittenBytes)`.
- Any drift fails the test. This proves Phase 3 doesn't accidentally
  change compression level, sector packing, header byte order, or `.mcc`
  spill boundaries.

## 9. Non-goals

- No new compression (deflate only, no zstd/lz4).
- No new file layout (4096-byte sectors only).
- No new index (`.mci` or similar).
- No version field on the MCA header — Vanilla has none, and adding one
  breaks downgrade.

## 10. Open questions

1. **fsync per write vs. batched.** Vanilla opens with `DSYNC` when the
   `sync` flag is on (`RegionFile.java:64-68`): every write forces to
   disk. Safe, throughput-hostile.
   **Recommendation:** open without `DSYNC`; `FileChannel.force(false)`
   once per region per tick at the end of Phase 5.3's autosave drain
   (metadata-force skipped — the file never shrinks). Trades a
   single-tick data-loss window on crash for the throughput win.
   Operators wanting Vanilla semantics get `-Dmultiforge.io.sync=on`.
2. **`.mcc` sidecar deletion on chunk clear.** Vanilla's `RegionFile.clear`
   (`RegionFile.java:276-286`) deletes unconditionally, which races
   readers.
   **Recommendation:** delete under the write lock; readers validate
   `Files.isRegularFile` under their read lock (`RegionFile.java:192`)
   so a lost race surfaces as warn-null, not a crash.
3. **Cache size 256 vs. 512 vs. adaptive.** Vanilla's 256 is
   single-thread-load-tuned. Parallel workers may thrash on large
   modpacks (ATM10, 20+ dimensions).
   **Recommendation:** keep 256 initially; raise to 512 if Phase 7.4
   bench shows eviction rate > 1/s under 100-player load.
