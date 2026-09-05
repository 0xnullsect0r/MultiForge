# Semantic NBT Diff — Design (M8 sub-step 8b)

**Status:** design-frozen (Phase 0 task 0.3 of the M9 landing plan).
**Implementer target:** Phase 7 task 7.1 —
`multiforge-bench/src/main/java/net/multiforge/bench/determinism/WorldDiff.java`.
**Blueprint:** `docs/blueprint.md:531-537` (M8 sub-step 8b + exit gate).

Once the M9 per-region tick body lands (Phase 1 task 1.5, Phase 5),
parallel region ticks reorder writes within a save cycle without
changing gameplay semantics. Byte-identical MCA is therefore impossible
under N>1 workers. This doc freezes what "two NBT payloads are
semantically equal" so the acceptance harness has a build target.

## 1. Scope

- **Byte-identical mode:** default under `--workers=1`. Implemented
  today by `WorldDiff.canonicalMcaHash`, which strips MCA header
  timestamps + sector reorder (`WorldDiff.java:141-189`).
- **Semantic mode:** new; activated when `N>1` or the caller passes
  `WorldDiff.compare(..., Mode.SEMANTIC)`. Fails closed on unknown
  paths (reports MISMATCH, never silently equates).
- **In scope:** `.mca` files under `region/`, `entities/`, `poi/`. Each
  chunk slot is parsed to a `CompoundTag` via zlib-decompressed NBT,
  normalized, then diffed recursively.
- **Out of scope (still byte-hashed):** `level.dat`, `playerdata/*.dat`,
  `data/*.dat` (except `random_sequences.dat`, §5), and the existing
  skips (`WorldDiff.java:36`).

## 2. Per-tag-type equality

Reference: `upstream/neoforge-1.21.1/projects/base/src/main/java/net/minecraft/nbt/`.

| Tag type | Rule |
|---|---|
| `CompoundTag` | Same key-set + per-key values semantically-equal; key order irrelevant (backed by `Map`). |
| `ListTag` | **Context-dependent** — see §3. Default: order-preserving element-wise. |
| `IntArrayTag`, `LongArrayTag`, `ByteArrayTag` | Order-preserving element-wise. Exception: palette-`data` LongArray under `sections[*].block_states` / `.biomes` (§3). |
| `StringTag`, `IntTag`, `LongTag`, `ShortTag`, `ByteTag`, `FloatTag`, `DoubleTag` | Bit-equal. |
| `EndTag` | Always equal to `EndTag`. |
| Unknown tag id | MISMATCH with reason `"unknown tag id"`. Never silently equated. |

## 3. Order-invariant list contexts

Save-order determines list order for these paths but does not change
gameplay state. Semantic diff canonicalises by sorting on the listed
key before element-wise compare. All citations to `ChunkSerializer.java`
unless noted.

- **`<region-chunk>.entities`** (`ListTag<CompoundTag>`) —
  read `ChunkSerializer.java:247-251`, write `:363-365`. Sort by
  `UUID`. Modern UUID is a 4-int `IntArrayTag` at key
  `Entity.UUID_TAG = "UUID"` (`Entity.java:150,1666`; encode via
  `NbtUtils.java:120-134`). Legacy `UUIDMost` + `UUIDLeast` compose to
  the same UUID before compare.
- **`<region-chunk>.block_entities`** — `ChunkSerializer.java:351-360`.
  Sort by `(x, y, z)` int triple (`BlockEntity.java:148-150`).
- **`<region-chunk>.block_ticks` and `.fluid_ticks`** —
  `ChunkSerializer.java:378-400`; per-tick shape at `SavedTick.java:14-18,45-61`.
  Keys: `"i"` (block/fluid id), `"x"/"y"/"z"` (pos ints), `"t"`
  (delay int), `"p"` (priority int). Sort key: full tuple
  `(i, x, y, z, t, p)` — stable when two ticks target the same block
  at the same delay.
- **`<region-chunk>.sections[*].block_states.palette` and
  `.biomes.palette`** with their optional `.data` LongArray. Codec at
  `PalettedContainer.java:63-65` (`"palette"` field via
  `PalettedContainerRO.PackedData::paletteEntries` + optional `"data"`
  `LONG_STREAM`). The palette may reorder between saves if `data`
  indices are remapped consistently. Compare as a **multiset of
  `(BlockState, count)`** (resp. `(Biome, count)`) after decoding:
  1. reconstruct the flat index array via the same bit-storage rules
     (`PalettedContainer.pack`, `:196-255`);
  2. map each index back through the palette to a global-registry id;
  3. produce a canonical `int[4096]` (blocks) or `int[64]` (biomes);
  4. compare those int arrays element-wise.
  Missing `data` means single-value; palette is `[value]` and the sole
  registry id compares trivially. **This is the load-bearing rule** —
  wrong here means every parallel save falsely fails.
- **`<region-chunk>.sections`** outer list is **order-significant** —
  keyed by signed `"Y"` byte per element (`ChunkSerializer.java:106,340`).
  Diff element-wise; do not sort. Apply the palette rule to each
  section's inner containers.
- **`<region-chunk>.PostProcessing`** — `ListTag<ListTag<ShortTag>>`,
  outer index = section-Y (`:233-240,515-529`); outer order is
  intrinsic. Inner list is a light-neighbour worklist; drain order is
  gameplay-idempotent. Sort inner lists by short value.
- **`<entities-chunk>.Entities`** — `EntityStorage.java:65,102`
  (`ENTITIES_TAG = "Entities"`). Sort by UUID per the entities rule.
- **`<poi-chunk>.Sections[<sectionY>].Records`** —
  `SectionStorage.java:36` (`SECTIONS_TAG = "Sections"`),
  `PoiSection.java:40` (records `Codec` at `"Records"`), records at
  `PoiRecord.java:22-33`. Sort by `(packedPos, type)` where
  `packedPos` is the long-packed `BlockPos` from `"pos"` and `type` is
  the `ResourceLocation` string of `"type"`.

Any list path not enumerated here is **order-significant**. Adding a
new order-invariant path is a diff change requiring an accompanying
test case per §10.

## 4. Fields to IGNORE

Ignored per-chunk fields (paths under the `<region-chunk>` compound
unless noted). Stripped before recursive compare.

| Field | Why |
|---|---|
| `LastUpdate` (`ChunkSerializer.java:287`) | `getGameTime()` at save. Under parallelism two chunks in one save cycle drain on different worker ticks; ±1-tick drift expected. |
| `isLightOn` (`:347-348`) | Set only when `ChunkAccess.isLightCorrect()` at save. Parallel light propagation slides the observation window; the *effect* — `BlockLight` / `SkyLight` byte-arrays — is what's diffed. |
| MCA header bytes 0–8191 | Sector-offset + timestamp tables. Already stripped by `canonicalMcaHash`. |
| `structures.starts` (`:451`) and `structures.References` (`:461`) | Structure-generation bookkeeping. **Requires investigation** — worldgen is single-threaded per chunk but M9 lets neighbour reads land on different workers. Land as IGNORE with a tracking issue; downgrade to compared once verified deterministic. |

**Not ignored — inspection may tempt you to but don't:**

- `InhabitedTime` (`:288`) — cumulative player-nearby ticks;
  deterministic under fixed seed + fixed input trace. Drift under N>1
  is a real region-scheduling bug — do not paper over.
- `Status` (`:289`) — chunk generation status. Never ignore; mismatch
  means one run reached full-load and the other didn't.
- `Item.Age` / `Item.PickupDelay` — deterministic countdowns.
- `sections[*].BlockLight` / `.SkyLight` — deterministic byte-for-byte
  under fixed seed even with parallel light.

## 5. Fields to NORMALIZE

Deterministic-under-canonical-form fields — differ across runs but
have a canonicalisation function.

- **UUID composition** — legacy `UUIDMost` + `UUIDLeast` longs are
  rewritten to the modern 4-int `IntArrayTag "UUID"`
  (`NbtUtils.java:120-134`) before compare. Every entity + block-entity
  path.
- **Palette + packed-data pair** — see §3
  (`sections[*].block_states` and `.biomes`). Decode-to-int-array is
  the normalize step.
- **`data/random_sequences.dat`** (`RandomSequences.java`) — per-feature
  RNG seed state. Re-serialise both sides with a canonical writer that
  sorts `CompoundTag` keys and stringifies sequence ids in
  `ResourceLocation` order, then byte-compare.

**Flagged, not implemented:** the M9 brief mentioned
`LevelChunkSection.legacy_random`. Grep of `LevelChunkSection.java`
returns no such field in 1.21.1 — no normalize hook needed today. Add
one only if a future NeoForge tag introduces it.

## 6. Diff output format

Text report to stderr (and inside `WorldDiff.Result`). One issue per
line. Match sample:

```
=== World diff: <baseline> vs <candidate> ===
Byte-identical hash: DIFFER    Semantic hash: MATCH
  Ignored (per §4): 4712 LastUpdate deltas, 217 sector-reorder deltas
```

Mismatch sample:

```
Semantic hash: DIFFER
  region/r.0.0.mca chunk (5, -3):
    entities: 3 in baseline, 4 in candidate
      + candidate: uuid=[I;1,2,3,4] type=minecraft:zombie
    block_entities[x=80,y=64,z=-42]: 'Lock' differs: <missing> vs ""
    sections[2].block_states canonical int-array differs at index 137:
      baseline=minecraft:stone  candidate=minecraft:cobblestone
```

First 20 chunk-level mismatches printed in full; remainder counted.
Every ignore rule that fired at least once is summarised so the reader
can distinguish "true match after normalization" from "nothing was
different anyway".

## 7. Algorithm outline

```
diff(baseline, candidate, Mode mode):
  for each rel path in baseline ∪ candidate:
    if mode == BYTE or path ∉ semantic-scope (§1):
      hash both via canonicalMcaHash | sha256; compare hex.
    else if path ends .mca:
      for slot in 0..1023:
        A = decompressSlot(baselineBytes, slot)   # null if absent
        B = decompressSlot(candidateBytes, slot)
        if (A == null) ⊕ (B == null): record slot-presence mismatch
        else if A != null:
          tagA = NbtIo.readCompressed(A); tagB = NbtIo.readCompressed(B)
          strip(tagA, IGNORE); strip(tagB, IGNORE)
          normalize(tagA, RULES); normalize(tagB, RULES)
          walk(tagA, tagB, path + "#chunk(x,z)", ORDER_INVARIANT_PATHS)

walk(a, b, path, orderRules):
  dispatch on tag type per §2. CompoundTag: compare key-sets, recurse.
  ListTag: if orderRules.contains(path) canonicalise both, then
  element-wise recurse. Palette paths: decode to canonical int[]
  before compare.
```

Reuse `net.minecraft.nbt.NbtIo` for parse — the multiforge-bench module
already depends on the vanilla jar. Strip is O(tree). Palette decode is
~4096 ops per section × ~24 sections × 500 chunks ≈ 50M ops per region
(~1s); cache the decoded int[] keyed on `(chunkPos, sectionY)` for one
diff run.

## 8. `entities/` and `poi/` MCA

- `entities/` slot payload — CompoundTag holding an `"Entities"`
  ListTag (`EntityStorage.java:65,102`). Apply the entities
  order-invariance rule (§3). Outer `"Position"` `IntArrayTag` and
  `"DataVersion"` are order-preserving.
- `poi/` slot payload — `SectionStorage` payload with a `"Sections"`
  CompoundTag keyed by section-Y string (`SectionStorage.java:36`).
  Each section's `"Records"` list is order-invariant per §3.
  `"Valid"` + `"DataVersion"` compare as-is.

## 9. Non-goals

- **`level.dat`**: game rules, spawn point, seed, border. Byte-identical
  or fail — divergence is a real bug.
- **`playerdata/*.dat`**: deterministic under fixed seed + input trace;
  do not normalize.
- **`advancements/`, `stats/`**: already skipped whole
  (`WorldDiff.java:36`).
- **`data/*.dat` except `random_sequences.dat`**: raids, scoreboards,
  maps — byte-identical or fail.
- **Not a general-purpose NBT diff.** Coupled to the fields Minecraft
  1.21.1 emits; revisit on every NeoForge tag bump.

## 10. Acceptance test cases

Implementer (Phase 7 task 7.1) must add these to a peer suite
`WorldDiffSemanticTest.java`; the existing `WorldDiffTest` byte-hash
cases stay unchanged.

1. `entityListReorderedIsSemanticMatch` — 3 entities in order
   `[A,B,C]` vs `[C,A,B]`. Byte-differ, semantic-match.
2. `entityAddedIsSemanticDiffer` — baseline `[A,B]`, candidate
   `[A,B,C]`. Both fail.
3. `blockEntityListReorderedIsSemanticMatch` — sort by `(x,y,z)`.
4. `tickListReorderedSameTupleIsSemanticMatch` — two ticks with
   identical `(i,x,y,z,t,p)` swapped in the queue.
5. `paletteReorderedWithDataRemappedIsSemanticMatch` — palette
   permuted, `data` remapped consistently → semantic-match.
6. `paletteReorderedWithoutDataRemapIsSemanticDiffer` — the *bug case*:
   palette permuted, `data` untouched → effective blocks differ → MUST
   report DIFFER. Guards against naive "just sort the palette".
7. `lastUpdateDifferByOneTickIsSemanticMatch` — only `LastUpdate`
   changed.
8. `sectionYOrderChangedIsSemanticDiffer` — a diff walker that sorts
   `sections` would silently equate a corrupted chunk. Guards §3's
   "do not sort outer sections".
9. `poiRecordsReorderedIsSemanticMatch` — one POI section with 4
   records swapped.
10. `legacyUuidPairMatchesModernIntArray` — one entity uses
    `UUIDMost`+`UUIDLeast`, the other `IntArrayTag "UUID"`; composed
    UUID identical → semantic-match.

Plus one negative-invariant test: `unknownTagIdIsSemanticDiffer` —
planted tag id 99 must return MISMATCH with reason `"unknown tag id"`
(fail-closed §2).

---
*Cross-references:* `docs/blueprint.md:531-537` (M8 sub-step 8b + exit
gate), `docs/design/m9-contracts.md` (Phase 0.1),
`docs/design/mca-format.md` (Phase 0.4), Phase 7 task 7.1 in
`~/.claude/plans/bubbly-jumping-comet.md`.
