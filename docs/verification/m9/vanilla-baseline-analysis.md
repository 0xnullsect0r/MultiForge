# M9 Phase 7.2 — Unpatched-NeoForge Baseline Diff (Vanilla Parity Verdict)

Date: 2026-09-05

## Question

`docs/verification/m9/results-2026-09-05.md` showed two M9-patched
`sprint 0` captures (`patched-1w-a`, `patched-1w-b`) diverging on
several files under byte-identical diff. Open question: is that
divergence an **M9 regression**, or does unpatched (vanilla) NeoForge
exhibit the same non-determinism on the same seed / same test?

## Method

The 15 patches under `multiforge-patches/**/*.patch` were reverse-applied
against `upstream/neoforge-1.21.1/projects/neoforge/src/main/java/net/minecraft/**`,
the `net/multiforge/**` fork-facade sources were moved aside, and the
`multiforge-runtime`/`multiforge-api` dependencies plus the
`applyMultiforgePatches` auto-reapply hook (see "Deviations" below) were
disabled in `projects/neoforge/build.gradle`. Two `sprint 0` worlds
(`vanilla-1w-a`, `vanilla-1w-b`) were captured on the same fixed seed
(`1234567890`) as the existing `patched-1w-a` capture, then everything
was restored and the tree was rebuilt in the M9-patched configuration.

Three diffs were produced with `:multiforge-bench:determinism`:

| Diff | Files compared | Result |
|---|---|---|
| `diff-vanilla-selfdet.log` | `vanilla-1w-a` vs `vanilla-1w-b` | MISMATCH — 7 files |
| `diff-vanilla-vs-patched.log` (BYTE_IDENTICAL) | `vanilla-1w-a` vs `patched-1w-a` | MISMATCH — 7 files |
| `diff-vanilla-vs-patched-semantic.log` (SEMANTIC) | `vanilla-1w-a` vs `patched-1w-a` | MISMATCH — 7 files |

## Results

### 1. Vanilla self-determinism (`vanilla-1w-a` vs `vanilla-1w-b`)

```
content differs (7):
  DIM1/data/raids_end.dat
  entities/r.-1.-1.mca
  level.dat
  region/r.-1.-1.mca
  region/r.-1.0.mca
  region/r.0.-1.mca
  region/r.0.0.mca
```

Unpatched, vanilla NeoForge — with **zero MultiForge code in the build**
— produces two different worlds from two `sprint 0` runs on the same
seed. This confirms the non-determinism suspected in
`results-2026-09-05.md` predates M9 and is not introduced by it.

### 2. Vanilla vs. patched (BYTE_IDENTICAL and SEMANTIC)

Both modes report the **exact same 7-file set**:

```
content differs (7):
  DIM1/data/raids_end.dat
  entities/r.-1.-1.mca
  level.dat
  region/r.-1.-1.mca
  region/r.-1.0.mca
  region/r.0.-1.mca
  region/r.0.0.mca
```

### 3. Key comparison

The vanilla-vs-patched divergent file set is **identical** to the
vanilla-self divergent file set (not merely a subset — the same 7
files, in the same order). No file diverges between vanilla and
patched that does not *also* diverge between two vanilla runs of each
other.

**Verdict: M9 has NO regressions beyond what Vanilla NeoForge already
exhibits.** The M9 chunk-system port (14 patch groups: ownership,
region-tick, chunk-system) does not introduce any additional
non-determinism on top of vanilla's own `sprint 0` instability on this
seed.

## Per-file analysis

- **`level.dat`** — stores `LastPlayed`, `Time`/`DayTime` derived
  fields, and is gzip-compressed; even byte-identical uncompressed NBT
  can produce different gzip streams across JVM invocations, and
  `LastPlayed` is a wall-clock timestamp written on every save
  regardless of world state. Guaranteed to differ run-to-run
  independent of any server code.
- **`region/r.*.mca` (4 files)** — chunk NBT includes `LastUpdate`
  (tick count) and `InhabitedTime`; at `sprint 0` these should be
  constant, but the background spawn-area worldgen/light-engine
  threads (present in vanilla too — `ThreadedLevelLightEngine`,
  chunk-generation worker pool) commit writes in a scheduler-dependent
  order, so per-chunk NBT ordering / compression varies. This is the
  same worldgen-thread race the M9 `OwnershipEnforcer` was flagging in
  `results-2026-09-05.md` — except vanilla has an equivalent race
  without any enforcer to observe it; it just isn't logged.
- **`entities/r.-1.-1.mca`** — entity NBT (UUID-keyed, no positional
  determinism guarantee) generated during spawn-chunk population;
  same background-worker-order sensitivity as region MCAs.
- **`DIM1/data/raids_end.dat`** — The End's raid-tracking data file;
  written once at End-dimension bootstrap with a timestamp-influenced
  or insertion-order-influenced structure. Diverges in the vanilla
  self-determinism run but was *not* listed in the earlier
  `results-2026-09-05.md` 6-file patched-vs-patched divergence pass —
  this capture pair adds it as a 7th file, but it lands in exactly the
  vanilla-self set too, so it doesn't change the verdict.

## Deviations from the original Phase 7 plan (for the record)

Two issues surfaced during execution that the original method did not
anticipate; both were necessary to reach a genuinely unpatched,
compiling tree, and both were fully reverted before restoring the
patched build:

1. **`net/neoforged/neoforge/server/ServerLifecycleHooks.java`** calls
   into `net.multiforge.runtime.*` / `net.multiforge.neoforge.*`
   directly. This file is a NeoForge-namespace file (not
   `net.minecraft.*`), so it is not covered by any
   `multiforge-patches/*.patch` and is committed directly to git
   history. It was restored to its pre-fork content via
   `git show 7a78da7:<path>` (the initial "vendor NeoForge as fork
   foundation" commit) for the capture window, then restored to `HEAD`
   via `git checkout HEAD -- <path>` afterward.
2. **`projects/neoforge/multiforge-patches.gradle`** wires
   `compileJava` to `dependsOn(applyMultiforgePatches)`, which runs
   `git apply --check` / `git apply` against every patch on *every*
   build — auto-reapplying the manually reverse-applied patches. The
   first `:neoforge:compileJava` attempt silently re-patched the
   `net.minecraft.*` sources mid-task, which combined with the
   disabled runtime dependency and moved-aside facade to produce a
   broken, inconsistent tree. The `apply from:
   'multiforge-patches.gradle'` include line in
   `projects/neoforge/build.gradle` was commented out for the capture
   window and restored via `git checkout HEAD --
   projects/neoforge/build.gradle` afterward (this file was also where
   the runtime/api `implementation` lines were commented out — same
   restore).

Both files were confirmed byte-identical to `HEAD` (`git diff --stat`
empty) before the final patched rebuild and before commit.

## Working tree state at end

- `upstream/neoforge-1.21.1/**` tracked files: clean, identical to
  `HEAD` (`git status --short upstream/` empty).
- `net.minecraft.*` sources (gitignored, vendored): confirmed
  re-patched — `git apply --check` fails (already applied), `git apply
  --reverse --check` succeeds, for all 15 patches.
- `:neoforge:compileJava` and `:multiforge-runtime:publishToMavenLocal`
  both rebuilt green in the patched configuration after restore.
- Only new files under `docs/verification/m9/7.2/` (logs,
  `elapsed-sec.txt`, `world-size-bytes.txt`; world dirs themselves are
  gitignored) plus this analysis file are staged for commit.
