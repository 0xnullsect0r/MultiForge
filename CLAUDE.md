# CLAUDE.md — MultiForge Project Conventions

This file gives Claude Code (and any other agent) the ambient context for
working in this repository. Read it before starting non-trivial work.

## What this project is

MultiForge is a Folia-inspired multithreaded drop-in replacement for the
NeoForge dedicated Minecraft server, licensed under the GNU General Public
License v3.0. Full design in `docs/blueprint.md`.

## Ground rules

1. **License is GPL-3.0-only.** Every source file carries the GPL-3
   header (see `buildSrc/src/main/resources/license-header.txt`). Any
   new dependency must be GPL-3-compatible: MIT, BSD, Apache-2.0, LGPL,
   MPL-2.0 are fine; AGPL is not (adds obligations); GPL-2-only is not
   (incompatible). The vendored NeoForge submodule keeps its own LGPL
   license. Never re-license the project under anything else without
   explicit approval.
2. **No signing, no license gating.** Ed25519 signing infrastructure
   was removed on 2026-09-06: no installer signature check, no bundled
   `.sig` resources, no license-token verification, no
   `multiforge-license*` modules. The installer just installs; the
   runtime just runs. Anyone can build and distribute their own jar.
3. **Vanilla parity first.** Every patch to `net.minecraft.*` must have a
   deterministic-mode regression run demonstrating byte-identical world save
   vs upstream NeoForge on a fixed seed. Break this at your peril.
4. **No blocking calls on a region worker thread.** Cross-region work
   uses `RegionizedTaskQueue.queueChunkTask(...)`. A `Thread.sleep`, a
   `.get()` on a `CompletableFuture`, or a lock that could be held while
   foreign code runs — all bugs. Two bounded exceptions exist, both in
   `docs/design/barrier-tick-model.md`: leaf locks around single Vanilla
   structure updates, and a worker waiting for the server thread to load a
   chunk (`MainThreadHandoff`, serviced while the server thread waits at
   the barrier).
5. **Auto-reroute + warn is the default.** Never make MultiForge refuse to
   load a mod, and never throw from a mod's code path just because it did
   something unsafe — reroute the call and log a rate-limited warning.
6. **NeoForge upstream drift is a maintenance cost.** Keep the patch set
   thin and grouped into `01-ownership/` .. `09-events/` so rebasing onto
   newer NeoForge tags is scoped per group.

## Region-tick conventions

The server runs the barrier tick model (`docs/design/barrier-tick-model.md`):
the server thread runs Vanilla's loop, and each level's regions tick in
parallel between two barriers. New code must respect it.

1. **Chunk loading, tickets, lighting and saving are Vanilla's**, on the
   server thread. Do not add a parallel chunk system. The runtime's
   `ChunkHolderManager` only indexes loaded chunks by owning region (fed by
   `RegionizedChunkLifecycle` from `ChunkEvent.Load/Unload`).
2. **Ownership is positional.** A region owns the chunks of its sections;
   an entity, block entity or scheduled tick belongs to the region owning
   its chunk. Mutation sites check `OwnershipGuard.canMutateAt` and reroute
   with `OwnershipGuard.rerouteAt` (or `rerouteSetBlock` /
   `rerouteAddFreshEntity` where the caller needs Vanilla's return value).
3. **Cross-region work** goes through `RegionizedTaskQueue.queueChunkTask`
   to the owner's mailbox. Work whose effects span regions (cross-region
   teleports, player dimension changes, command execution) is deferred to
   the server thread with `OwnershipGuard.deferToServerThread` /
   `deferCrossRegionMove`.
4. **Shared Vanilla state touched by region workers** needs a leaf lock or
   a per-thread instance (see the table in the design doc). A leaf lock is
   one under which no foreign code runs and nothing waits on another thread.
5. **A designed wait on a region worker** (the two bounded exceptions to
   rule 4: `MainThreadHandoff`, `SerialLane`) is bracketed with
   `RegionTickWatchdog.beginWait()/endWait(kind)`, so the watchdog and the
   barrier deadline do not count it as the region's own time. Do not use it
   to hide any other wait.
6. **`InstanceRegistry<K, V>`** (in `net.multiforge.runtime.chunk`) is the
   standard weak-keyed per-server/per-level lookup. Use it, not a raw
   `WeakHashMap`.

## Repository layout

See top-level `README.md`. Key directories:

- `buildSrc/` — Gradle convention plugins (Java version, Spotless config,
  GPL-3 license-header check).
- `multiforge-api/` — public API surface (`net.multiforge.api.*`), no MC
  dependency.
- `multiforge-runtime/` — internal runtime (`net.multiforge.runtime.*`),
  everything except the patches.
- `multiforge-patches/` — patches against `upstream/neoforge-1.21.1/`.
  Grouped `01-*` .. `09-*`.
- `multiforge-installer/` — repackages patched NeoForge + runtime into
  installer jar.
- `multiforge-client/` — client-side debug mod, ordinary NeoForge mod.
- `multiforge-scanner/` — ASM mod-jar safety scanner (`/multiforge certify`).
- `multiforge-testmods/` — fixture mods (cross-region writer, legacy listener)
  used by the bench scenarios and the scanner corpus.
- `multiforge-bench/` — installed-server harness: parity gate, scenarios,
  protocol-bot swarm (MCProtocolLib), TPS/MSPT benches.
- `docs/` — design docs.

## Build

- Requires **JDK 21**, ~20 GB free disk for the NeoForge workspace.
- Outer modules: `./gradlew build` (api, runtime, scanner, installer, bench,
  client, testmods). Spotless runs automatically.
- The fork (`upstream/neoforge-1.21.1`, NeoForge 21.1.251, NeoDev build)
  consumes api + runtime from mavenLocal. In order:
  1. `./gradlew :multiforge-api:publishToMavenLocal :multiforge-runtime:publishToMavenLocal`
  2. in `upstream/neoforge-1.21.1`: `./gradlew setup` (decompile + NeoForge
     patches; the MultiForge patches are applied automatically before compile)
  3. then `:neoforge:compileJava`, `:tests:runGameTestServer` (GameTests),
     `:neoforge:installerJar` (installer in `projects/neoforge/build/libs`),
     `immaculateCheck licenseCheck` (formatting, headers).
- Patch work: `scripts/mf-patches.py` (see `multiforge-patches/README.md`).

## Code style

- Java 21, records where sensible, `sealed` where hierarchy is closed.
- 4-space indent, 140-char line limit (see `.editorconfig`).
- Spotless (Google Java Format Palantir dialect) enforced by CI.
- GPL-3 license header on every source file, generated from
  `buildSrc/src/main/resources/license-header.txt`.
- Package names: `net.multiforge.api.*` (public), `net.multiforge.runtime.*`
  (internal, subject to change without notice).
- `@ApiStatus.Internal` on every non-API type until we ship v1.0.

## Testing

- Unit tests: JUnit 5 + AssertJ + jqwik. Live under `src/test/java`.
- CI runs `./gradlew spotlessCheck` + the module builds, and the fork job
  runs every GameTest (NeoForge's suite + `net.multiforge.testfixtures`).
  Spotless enforces the GPL-3 header in the outer modules;
  `immaculateCheck`/`licenseCheck` in the fork.
- Live-server checks (`multiforge-bench`, need a built installer; see
  `docs/verification/README.md`):
  - `./gradlew :multiforge-bench:determinism -Pworkers=1,4` — the vanilla
    parity gate: one fixed-seed world ticked on stock NeoForge and on
    MultiForge, terrain compared chunk by chunk.
  - `./gradlew :multiforge-bench:scenario` — Phase X behaviour checks on
    stock vs MultiForge (teleport, raid, dragon, fixture mods).
  - `:multiforge-bench:vanilla|swarm|atm10|x8StrictSwarm` — benches with
    real protocol bots; `nightly.yml` runs a short set every night.
- Scanner: `./gradlew :multiforge-scanner:scanCorpus` (gated in CI).

## Git workflow

- **Day-to-day development happens on `develop`.** All feature work,
  Claude-authored commits, and milestone-in-progress work land there.
- **`main` is the release branch.** When a milestone (M0, M1, …) is
  complete and its exit gate is met, `develop` is merged into `main` and
  a version tag is cut. Nothing lands directly on `main`.
- Long-running feature branches (when needed) are named `feat/<topic>` or
  `fix/<topic>` and rebased into `develop`.
- Commits: Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`,
  `refactor:`, `test:`, `ci:`, `perf:`).
- **Every commit ends with** the Claude co-author trailer required by the
  Claude Code session (see `.github/PULL_REQUEST_TEMPLATE.md`).
- Both `main` and `develop` are branch-protected. All merges via PR +
  green CI + at least one review.
- Never `git push --force` to `main` or `develop`. Never `--no-verify` a
  hook.

## When you don't know

Ask. Especially before:
- Changing the LICENSE file or the license-header template.
- Touching the tick loop in `MinecraftServer.runServer`.
- Adding a new dependency (any new dep needs a GPL-3-compat check and a
  binary-size review).
- Adding an outbound network call from the runtime.
