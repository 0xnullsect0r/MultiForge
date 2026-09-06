# CLAUDE.md — MultiForge Project Conventions

This file gives Claude Code (and any other agent) the ambient context for
working in this repository. Read it before starting non-trivial work.

## What this project is

MultiForge is a closed-source, license-gated, Folia-inspired multithreaded
drop-in replacement for the NeoForge dedicated Minecraft server. Full design
in `docs/blueprint.md`.

## Ground rules

1. **License is proprietary.** Never suggest re-licensing under an OSS
   license. Never add code that would force the project under GPL/AGPL
   (avoid GPL dependencies; LGPL is fine for the NeoForge submodule).
2. **Never commit or push a real license token.** Never commit an Ed25519
   private key. `.gitignore` already excludes `*.private.pem`, `*.priv`,
   `private-key.*`, `license.key`, `*.license`. Test tokens under
   `multiforge-license/src/test/resources/testkeys/` are permitted **only if
   the accompanying private key is a throwaway generated for tests and is
   also committed to make tests reproducible**; document that these keys are
   never used in production.
3. **Vanilla parity first.** Every patch to `net.minecraft.*` must have a
   deterministic-mode regression run demonstrating byte-identical world save
   vs upstream NeoForge on a fixed seed. Break this at your peril.
4. **No blocking calls on a region worker thread.** Ever. Cross-region work
   uses `RegionizedTaskQueue.queueChunkTask(...)`. A `Thread.sleep`, a
   `.get()` on a `CompletableFuture`, or a `synchronized` block that could
   contend with a foreign region — all bugs.
5. **Auto-reroute + warn is the default.** Never make MultiForge refuse to
   load a mod, and never throw from a mod's code path just because it did
   something unsafe — reroute the call and log a rate-limited warning.
6. **NeoForge upstream drift is a maintenance cost.** Keep the patch set
   thin and grouped into `01-ownership/` .. `09-events/` so rebasing onto
   newer NeoForge tags is scoped per group.

## M9 chunk-system conventions

The M9 chunk-system port replaces Vanilla's `ChunkMap`, `DistanceManager`,
`ThreadedLevelLightEngine`, and `RegionFile` layer with per-region forks.
New code that reads or writes chunk state must go through the fork surfaces,
not the Vanilla ones.

1. **Chunk work always goes through `ChunkHolderManager` (via the facade
   at `net.multiforge.neoforge.chunk.MultiForgeChunkMap`), never Vanilla
   `ChunkMap` directly.** Resolve a `NewChunkHolder` via
   `ChunkHolderManager.holderAt(...)` and mutate through that. Do not
   reach into `ServerLevel.getChunkSource().chunkMap`.
2. **Tickets flow through `MultiForgeDistanceManager.addTicket/removeTicket`**,
   which routes to per-region `PerRegionTicketMap` via
   `ChunkHolderManager`. `ServerLevel.getChunkSource().addRegionTicket(...)`
   still works for source compat, but bypasses per-region locality — do
   not use it in new code.
3. **Light updates flow through `MultiForgeLightEngine`** — the facade
   routes `checkBlock` / `updateChunkStatus` / `updateSectionStatus` to
   the region-owning worker via `RegionizedTaskQueue.queueChunkTask`.
4. **MCA I/O is `net.multiforge.runtime.io.RegionFileReader` /
   `RegionFileWriter` / `RegionFileCache`.** Do not call Vanilla
   `net.minecraft.world.level.chunk.storage.RegionFile` directly. Round-trip
   through the fork's `RegionChunkSerializer` if you need
   `CompoundTag` ↔ `LevelChunk` conversion.
5. **Every region worker has its own journal.** Chunk mutations queued
   for save land in `RegionJournal` (WAL), flushed by `AutoSaveRunner` in
   the `FLUSH_OUTBOUND` tick phase. Do not autosave synchronously from a
   mod hook — enqueue via `AutoSaveRunner.markDirty(region, chunkPos)`.
6. **`InstanceRegistry<K, V>`** (in `net.multiforge.runtime.chunk`) is the
   standard `ServerLevel → facade` lookup used by the M9 fork facades.
   Use it — not a raw `WeakHashMap` — when adding another facade.

## Repository layout

See top-level `README.md`. Key directories:

- `buildSrc/` — Gradle convention plugins (Java version, Spotless config,
  license-header check).
- `multiforge-license/` — Ed25519 verifier, no MC dependency.
- `multiforge-license-cli/` — signer CLI, no MC dependency.
- `multiforge-runtime/` — everything except the patches. Public API under
  `net.multiforge.api.*`, internal under `net.multiforge.runtime.*`.
- `multiforge-patches/` — patches against `upstream/neoforge-1.21.1/`.
  Grouped `01-*` .. `09-*`.
- `multiforge-installer/` — repackages patched NeoForge + runtime + license
  into installer jar and Docker image.
- `multiforge-client/` — client-side debug mod, ordinary NeoForge mod.
- `multiforge-testmods/` — fixture mods.
- `multiforge-bench/` — headless bot swarm + TPS harness.
- `docker/` — Dockerfile, entrypoint script, compose examples.
- `docs/` — design docs.

## Build

- Requires **JDK 21**, Docker with buildx, ~20 GB free disk for NeoForge
  workspace.
- `./gradlew :setup` vendors NeoForge 1.21.1 into `upstream/`.
- `./gradlew build` runs the full build. `spotless` and `licenseHeader` run
  automatically.
- `./gradlew :multiforge-installer:dockerBuild` builds the multi-arch
  Docker image.
- Sub-projects that do not depend on Minecraft (license, license-cli,
  runtime-diagnostics) build standalone without the `:setup` step.

## Code style

- Java 21, records where sensible, `sealed` where hierarchy is closed.
- 4-space indent, 140-char line limit (see `.editorconfig`).
- Spotless (Google Java Format Palantir dialect) enforced by CI.
- License header on every source file, generated by
  `buildSrc/src/main/resources/license-header.txt`.
- Package names: `net.multiforge.api.*` (public), `net.multiforge.runtime.*`
  (internal, subject to change without notice).
- `@ApiStatus.Internal` on every non-API type until we ship v1.0.

## Testing

- Unit tests: JUnit 5 + AssertJ + jqwik. Live under `src/test/java`.
- CI runs: `./gradlew build spotlessCheck licenseHeaderCheck test`.
- Deterministic-mode regression: `./gradlew :multiforge-bench:determinism`
  (fixed seed, single worker, 20 min, world hash asserted).
- Bench (nightly): `./gradlew :multiforge-bench:atm10`.

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
- Changing the license file, the license verifier, or anything in
  `multiforge-license/`.
- Touching the tick loop in `MinecraftServer.runServer`.
- Adding a new dependency (any new dep needs a license-compat check and a
  binary-size review).
- Adding an outbound network call from the runtime.
