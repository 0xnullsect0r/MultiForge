# M9 Phase 7 — Verification Runbook

This runbook covers the four wall-clock verification runs required to
close M9 and cut `v0.9.0-m9`. Each run takes 15–45 min of real server
time; they cannot be executed inside a subagent budget and are
user-triggered from a workstation with the vendored NeoForge workspace.

Order-of-operations, pass criteria, and failure modes are captured
per-task. When you finish a run, drop its output under
`docs/verification/m9/<task>/` and cross-reference it from the
milestone-close PR body.

- **7.2** — byte-identical single-worker regression
- **7.3** — N-worker semantic parity regression
- **7.6** — 30-min strict-mode watchdog
- **7.4** — bench harness (atm10, vanilla, swarm)
- **7.5** — this runbook

Sibling `docs/design/m9-review-round-5.md` covers the /67 review pass
on the Phase 7.1 landing; it is orthogonal to this runbook.

## 1. Prerequisites

Everything below assumes the dev-box layout in `CLAUDE.md`.

- **JDK 21** on `PATH` or at `/home/aric/.local/jdk/jdk-21.0.12.1+1`.
- `./gradlew :setup` has run at least once — vendors NeoForge 1.21.1
  into `upstream/neoforge-1.21.1/`. ~20 min the first time.
- `./gradlew :multiforge-runtime:publishToMavenLocal` recent — the
  patched fork consumes the runtime from `~/.m2`. Re-run whenever a
  runtime commit lands.
- **Docker + buildx** — only for `./gradlew
  :multiforge-installer:dockerBuild` and the swarm profile. Not
  required for 7.2 / 7.3 / 7.6.
- **~20 GB free disk** — NeoForge workspace, two world captures per
  run, journal files.
- **16 GB RAM** — 4 GB heap headroom for the server + swarm clients.
- **Clean `develop` checkout** — `git status` must be clean before
  starting; the runs produce large artefacts under `docs/verification/`
  and it is easy to lose a diff otherwise.

Verify:

```
./gradlew :multiforge-runtime:test :multiforge-bench:test
```

Both must be green before any wall-clock run — the runs consume ~1 hr
each, so a JUnit failure noticed after the fact is a wasted afternoon.

## 2. Task 7.2 — Byte-identical single-worker regression

Fixed-seed 20-minute headless run under `--workers=1`; compares the
resulting world save against a baseline captured from an unpatched
NeoForge fork on the same seed. Byte-identical is a legitimate check
here — the 01-ownership patch group adds no parallelism, no reorderings,
and consumes no RNG (see `DeterminismHarness` scope note).

**Bench target (existing):**

```
./gradlew :multiforge-bench:determinism \
  --args='<baseline-world-dir> <patched-world-dir>'
```

The `:determinism` task is a `JavaExec` over
`net.multiforge.bench.determinism.DeterminismHarness`. It does **not**
launch the server itself — server launch and world capture are out of
band, so the harness stays MC-free and version-agnostic. See §7 for
blockers on the launch side.

**Expected duration:** 20 min server tick + a few seconds for the diff.

**Baseline strategy:**

1. Check out the last known-good pre-M9 tag (`git switch --detach
   v0.8.0-m8-tail`, adjust to the newest such tag on `main`).
2. Boot a headless server with `--seed=1234567890 --world-name
   baseline-1w`; tick for 20 min (1200 s * 20 TPS = 24000 ticks); issue
   `save-all flush` then `stop`.
3. Copy `run/world/` to `docs/verification/m9/7.2/baseline-1w/`.
4. Switch back to `develop`; repeat with `--world-name patched-1w`,
   copy to `docs/verification/m9/7.2/patched-1w/`.
5. Run the diff.

Once green, capture the canonical MCA hash per region file into
`multiforge-bench/src/test/resources/determinism-baseline.txt` — future
runs can then compare against the stored hash instead of a re-captured
baseline. The resource file does not exist yet; create it on the first
green run.

**Pass criteria:**

- `DeterminismHarness` exits 0 (`WorldDiff.Result.matches() == true`).
- Every region file in `world/region/` matches under
  `WorldDiff.DiffMode.BYTE_IDENTICAL`.
- Zero entries in `onlyInBaseline` / `onlyInPatched`.

**Failure mode:**

- Non-empty `mismatched` set → dump the diverging chunks. The
  `Result.summary()` output lists the first 20 mismatches with
  truncated hashes; drill in with:
  ```
  # per-region canonical hash under both modes:
  java -cp multiforge-bench/build/classes/java/main \
    net.multiforge.bench.determinism.WorldDiff \
      <baseline>/region/r.0.0.mca <patched>/region/r.0.0.mca
  ```
- If a chunk NBT dump is needed, add a `main` to `WorldDiff` that
  invokes `readNbtRoot` + `writeCanonicalRoot` and pretty-prints the
  result, or shell out to Vanilla's `nbtdump` on the raw chunk payload.
- File a HIGH-severity issue with the diverging chunk coordinates,
  region file, and truncated hash pair.

## 3. Task 7.3 — N-worker semantic parity regression

Same fixed-seed run with N=cores/2 region workers; byte-identical is
impossible with parallel dispatch (chunk save order is legitimately
non-deterministic across worker threads), so assert semantic NBT parity
via `WorldDiff.DiffMode.SEMANTIC`. The semantic normaliser (Phase 7.1)
strips `random_state`, canonicalises idle `Motion`, drops `Air /
HurtTime / DeathTime / PortalCooldown`, and sorts entity / block-entity
/ ticker lists — see `docs/design/nbt-semantic-diff.md`.

**Bench target (existing, args-based):**

The current `:determinism` task accepts only two positional world dirs.
Worker count is set on the server side via `mtserver.tick_workers` in
`multiforge.toml`; there is no `-Pworkers=8` / `-Dmultiforge.workers=8`
JVM flag today. The runbook shape below reflects that — do **not**
copy-paste the plan's `-Pworkers=8 -PdiffMode=SEMANTIC` invocation
without wiring the flags first (tracked below in §7).

```
# 1. On the server side, set multiforge.toml:
#      [region]
#      cores = 8
#      threads_per_core = 1
# 2. Boot with the same --seed as 7.2.
# 3. Capture world to docs/verification/m9/7.3/patched-Nw/.
# 4. Diff against the 7.2 baseline-1w capture:
./gradlew :multiforge-bench:determinism \
  --args='docs/verification/m9/7.2/baseline-1w \
          docs/verification/m9/7.3/patched-Nw'
```

The default `DeterminismHarness` main runs `WorldDiff.compare` which
dispatches per-file to `canonicalMcaHash(bytes)` — that overload uses
`BYTE_IDENTICAL`. A `SEMANTIC`-mode CLI switch is not wired to the
JavaExec entry point yet; see §7. Until it is, drive the semantic
comparison via a small ad-hoc `main` or a JUnit fixture that calls
`WorldDiff.canonicalMcaHash(file, DiffMode.SEMANTIC)` directly.

**Expected duration:** 20 min server tick + a few seconds for the diff.

**Baseline strategy:** the byte-identical single-worker capture from
§2 is the semantic baseline too — semantic is a strict superset of
byte-identical, so any pair that matches BYTE_IDENTICAL matches
SEMANTIC. Capture `baseline_semantic` = every region file's
`canonicalMcaHash(bytes, SEMANTIC)` from the 7.2 baseline once, then
compare future N-worker runs against those.

**Pass criteria:**

- Every region file's SEMANTIC hash equals its 7.2 baseline SEMANTIC
  hash.
- Zero unexplained deltas in `onlyInBaseline` / `onlyInPatched`
  (some legitimate diffs may appear — e.g. an extra region file if the
  N-worker run tripped chunk load slightly differently under load; the
  diff must be justified per-file before the run counts as green).

**Failure mode:**

- Semantic mismatch is a **genuine state-change bug**, not parallelism
  drift. The normalisation covers everything drift-adjacent that we
  know of. Attach both raw chunks, both canonical NBT dumps, and the
  region worker's tick trace to the HIGH-severity issue.

## 4. Task 7.6 — Strict-mode watchdog (30 min)

Runs the bench swarm for 30 min under
`-Dmultiforge.regiontick.strict=on`; asserts zero region-tick overruns
throw and zero ownership-guard reroute warns fire. Confirms every
caller under bench load routes through the M9 region facade rather
than reaching into another region's state.

**Bench target (stub — see §7):**

```
./gradlew :multiforge-bench:swarm -Pplayers=100 \
  -Dorg.gradle.jvmargs='-Dmultiforge.regiontick.strict=on'
```

Or, once the swarm launcher lands, whatever JVM-flag pass-through it
exposes for the child server process.

**Expected duration:** 30 min swarm + a few seconds for the log
scrape.

**Pass criteria:**

- Zero `RegionTickOverrunException` thrown during the run
  (`RegionTickWatchdog.Mode.STRICT` upgrades the warn to a throw).
- Zero rate-limited warns on `region-tick.overrun` (WARN mode's
  emitter still fires under STRICT mode).
- Zero `OwnershipEnforcer` REROUTE lines in the server log — under
  bench load every caller must have already been migrated to the M9
  facade; a REROUTE warn = a Phase 6 caller-migration miss.

**Failure mode:**

- Any throw indicates a caller not routing through the M9 facade —
  capture the full stack trace from the STRICT-mode exception. The
  stack points directly at the offending call site.
- File a HIGH-severity issue with the stack and the region-id +
  chunk-pos context from `RegionTickWatchdog.enterTick`.

## 5. Task 7.4 — Bench harness

Three bench profiles capture the M9 exit-gate performance numbers:
vanilla server as a control, ATM10 modpack for the realistic-mod-load
case, and headless bot swarms at 20/100/500 players.

**Bench targets (stubs — see §7):**

```
./gradlew :multiforge-bench:vanilla
./gradlew :multiforge-bench:atm10
./gradlew :multiforge-bench:swarm -Pplayers=20
./gradlew :multiforge-bench:swarm -Pplayers=100
./gradlew :multiforge-bench:swarm -Pplayers=500
```

**Expected duration:** 15–45 min per profile. Full sweep = ~2.5 hr.

**Baseline strategy:** capture TPS + MSPT + heap traces from the same
pre-M9 tag used in §2, at each profile, on the same hardware. Save
both `docs/verification/m9/7.4/<profile>/baseline.json` and
`docs/verification/m9/7.4/<profile>/patched.json`.

**Pass criteria:**

- Sustained TPS ≥ 19.5 over the last 10 minutes of each run.
- MSPT p99 < 45 ms.
- Zero OOM, zero JVM-fatal crashes.
- ≥ 95 % of the baseline TPS on the vanilla profile — a MultiForge
  regression on a plain vanilla server means the ownership guards
  themselves cost too much.

**Deliverable:** TPS / MSPT / heap graphs saved under
`docs/verification/m9/7.4/<profile>/`; summary table appended to
`docs/blueprint.md` M9 exit-gate section or a new
`docs/bench-baseline.md` if the numbers deserve their own home.

## 6. Order to run

1. **7.2** — byte-identical single-worker; fastest to validate and
   catches the widest class of regressions cheaply.
2. **7.3** — semantic N-worker; runs the same code path 7.2 does, so
   green 7.2 is a prerequisite.
3. **7.6** — 30-min strict-mode watchdog; a routing bug caught here
   would invalidate a 7.4 run captured on top of it.
4. **7.4** — bench harness; longest and most expensive, run last on
   a known-clean tree.

## 7. Blockers that must be lifted before running

Read from the current tree, not guessed:

- **`AutoSaveRunner` uses a stub serializer that writes empty payloads
  to the journal.**
  `multiforge-runtime/src/main/java/net/multiforge/runtime/scheduler/MultiThreadedSchedulerHost.java:507-515`
  registers each region's `AutoSaveRunner` with `(r, holder) -> new
  byte[0]`; the TODO on line 493 pins this on the Phase 6+ caller
  migration. Until a real `RegionChunkSerializer` is wired in, every
  autosave journal entry is content-free and no chunk state actually
  makes it to disk from the M9 code path. **7.2 and 7.3 cannot pass
  against an unpatched-NeoForge baseline until this is fixed** —
  the baseline writes real chunk NBT via Vanilla's serializer, the
  patched side writes 0-byte journal entries, so `world/region/*.mca`
  will diverge on every occupied chunk.

- **No headless-server launcher exists in `multiforge-bench/`.** The
  bench module today ships only the file-diff harness; the two world
  dirs it consumes must be produced out-of-band by launching a real
  MultiForge server (via `multiforge-installer` or the vendored
  NeoForge workspace's `runServer` task). Formalising a launcher
  wrapper would let 7.2 / 7.3 be a single Gradle invocation instead of
  the multi-step recipe in §2 / §3.

- **`:multiforge-bench:determinism` accepts only positional world dirs
  — no `--seed`, `-Pworkers=`, `-PdiffMode=` flags.** The plan's
  reference invocations
  (`./gradlew :multiforge-bench:determinism --seed=<pinned>`,
  `-Pworkers=8 -PdiffMode=SEMANTIC`) do not work as written today.
  Wire these into either `DeterminismHarness` (arg parser) or the
  Gradle task (property → JavaExec args) before shipping the runbook
  as a one-liner.

- **Worker count has no JVM-flag override.** N-worker parallelism is
  set via `mtserver.tick_workers` (`cores * threads_per_core`) in
  `multiforge.toml` — see `MultiForgeConfig.tickWorkerCount()`. There
  is no `-Dmultiforge.workers=N` short-circuit. 7.3 either configures
  via the TOML or a `System.getProperty("multiforge.workers")`
  override is added to `MultiForgeConfig.defaults()`.

- **`:multiforge-bench:atm10`, `:vanilla`, `:swarm` are stubs.** This
  runbook's Gradle-target changes register them so `./gradlew tasks`
  documents the shape, but invoking any of them throws a
  `GradleException` pointing back here. Implementing them (Phase 7.4a)
  is a separate work item.

- **No `docs/verification/m9/` directory yet.** Create the tree
  (`7.2/`, `7.3/`, `7.4/`, `7.6/`) on the first run; the runbook
  references it as if it already exists.

- **Baseline hash file
  `multiforge-bench/src/test/resources/determinism-baseline.txt` does
  not exist.** First green 7.2 run seeds it; every subsequent run
  compares against the stored hash instead of re-capturing.

Once the AutoSaveRunner serializer is real, the four remaining gaps
(launcher, CLI flags, worker flag, stub bench tasks) are mechanical.
The serializer is the only functional blocker.
