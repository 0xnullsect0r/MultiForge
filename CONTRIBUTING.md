# Contributing to MultiForge

Thanks for taking a look. MultiForge is GPL-3.0-only free software and welcomes contributions.

## Ways to contribute

- **File a bug or a feature request** — [github.com/0xnullsect0r/MultiForge/issues](https://github.com/0xnullsect0r/MultiForge/issues). Include the MultiForge version (`java -jar multiforge-installer.jar version`), the boot log (or the last ~100 lines of `logs/multiforge-*.log`), and the shape of the repro (mods installed, config, action taken).
- **Port a mod** — [`docs/mod-porting.md`](docs/mod-porting.md) has before/after recipes for common patterns. If you hit a MultiForge violation the recipes don't cover, that itself is worth an issue.
- **Publish compatibility results** — running MultiForge against a modpack and reporting whether it survived 24 hours is genuinely useful data. Attach the server log + a description of what did/didn't work.
- **Improve the docs** — the design docs under `docs/design/` are intentionally verbose so new contributors can build a mental model. Clarifications welcome.
- **Land code** — patches to the runtime, the fork bridges, the patches themselves, or the scanner rules. See § Development workflow below.

## Development workflow

### Requirements

- **JDK 21** (the toolchain expects Temurin; other builds work but aren't tested).
- **~20 GB free disk** for the vendored NeoForge workspace under `upstream/neoforge-1.21.1/`.
- **Docker with buildx** — only needed if you're building the Docker image (`:multiforge-installer:dockerBuild`).
- **git** (obviously).

### First-time setup

```
git clone https://github.com/0xnullsect0r/MultiForge.git
cd MultiForge
./gradlew :setup                                        # vendors NeoForge 1.21.1 workspace; ~5-10 min
./gradlew :multiforge-api:publishToMavenLocal :multiforge-runtime:publishToMavenLocal
```

### Building

```
./gradlew build spotlessCheck                           # pure-Java modules; ~30 sec on a warm cache
cd upstream/neoforge-1.21.1
./gradlew :neoforge:applyMultiforgePatches              # apply MultiForge patches to vendored source
./gradlew :neoforge:compileJava                         # compile the fork (SEPARATE invocation — see note below)
```

**Gradle quirk:** running `applyMultiforgePatches` and `compileJava` in one invocation can wipe patches back to pristine before compile runs. Always run them as two separate `./gradlew` calls.

### Working on a change

1. **Branch off `develop`** — day-to-day work lives on `develop`; `main` is the release branch. Feature branches: `feat/short-name` or `fix/short-name`.
2. **Follow CLAUDE.md's ground rules** — GPL-3 header on every new source file (spotless enforces via `buildSrc/src/main/resources/license-header.txt`); no blocking calls on region worker threads; auto-reroute + warn is the default (never throw from a mod's code path); vanilla-parity via semantic NBT diff.
3. **Grouped patches** — new patches to `net.minecraft.*` or `net.neoforged.*` go under `multiforge-patches/NN-name/` (see the existing groups 01-ownership through 09-events). Keep hunks thin — heavy logic in `net.multiforge.neoforge.*` fork bridges or `net.multiforge.runtime.*` pure-Java.
4. **Tests** — JUnit 5 + AssertJ + jqwik. Live under `src/test/java`. Every new API needs coverage; every bug fix needs a regression test that would have caught the bug.
5. **spotlessApply** before you commit if you added any files — the outer template lives in `buildSrc/src/main/resources/license-header.txt` and matches the fork's own spotless config (blank-line-free header). Both will fight you if you write your own header shape.
6. **Push + open a PR** against `develop`. CI runs on push (build+test, dockerfile lint, secret scan, fork compile, mod-safety scanner). Fork compile + scanner are advisory (`continue-on-error: true`); the other three are required.

### Commit conventions

- Conventional Commits: `feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`, `ci:`, `perf:`, `style:`.
- Milestone tag optional but appreciated: `feat(m12): …`, `fix(m13): …`, etc.
- If AI-authored, keep the co-author trailer.
- Prefer NEW commits over `--amend`. Never `git push --force` to `main` or `develop`. Never `--no-verify` a hook.

### Testing

- **Unit tests:** `./gradlew test` — pure-Java modules only, ~30 sec.
- **Determinism regression** (when touching NMS patches): `./gradlew :multiforge-bench:determinism` — fixed seed, single worker, 20 min, world hash asserted vs unpatched-fork baseline.
- **Bench harness:** `./gradlew :multiforge-bench:vanilla`, `:swarm`, `:atm10`. See `multiforge-bench/README.md`.
- **Live smoke** for anything touching the tick pipeline: boot the fork via `cd upstream/neoforge-1.21.1 && ./gradlew :neoforge:runServer`, verify mobs AI-tick, hoppers tick, redstone schedules; watch for `[multiforge.violation/]` warns in the log.

### Working on the fork bridge (`upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/`)

This tree contains fork-side glue that references `net.minecraft.*` and `net.neoforged.*`. Every file here is GPL-3. The vendored NeoForge submodule itself remains under its own LGPL — do not modify files under `projects/base/` or `projects/neoforge/src/main/java/net/minecraft/` directly (patch them via `multiforge-patches/`).

### Working on a scanner rule (`multiforge-scanner/`)

12 rules exist today (R01-R12, see [`docs/design/scanner-rules.md`](docs/design/scanner-rules.md)). A new rule follows the pattern in `multiforge-scanner/src/main/java/net/multiforge/scanner/rules/`: implement the `Rule` interface, add fixture jars under `src/test/resources/`, register in `Main.ACTIVE_RULES`. Rule severity is `WARN` or `ERROR`; `ERROR` fails the scanner exit code, `WARN` does not.

## Design and architecture

Before making non-trivial changes:

- **[docs/blueprint.md](docs/blueprint.md)** — the original design document. Covers every milestone M0–M12.
- **[docs/concurrency-contract.md](docs/concurrency-contract.md)** — Domain enum, OwnerToken semantics, DomainAssertions, OwnershipEnforcer modes.
- **[docs/scheduler-api.md](docs/scheduler-api.md)** — MultiForgeRegionizedRuntime, RegionizedTaskQueue, ChunkHolderManager.
- **[docs/design/](docs/design/)** — per-milestone deep-dive specs. Read the relevant one before patching that milestone's surface.

## Code of Conduct

This project follows the [Contributor Covenant v2.1](CODE_OF_CONDUCT.md). Report violations to the maintainer via a private GitHub security advisory (same channel as security reports).

## License

MultiForge is [GPL-3.0-only](LICENSE). By contributing you agree that your contribution will be released under GPL-3.0-only as well. The vendored NeoForge submodule keeps its own LGPL-2.1 license — additions under `upstream/neoforge-1.21.1/src/main/java/net/multiforge/**` and `multiforge-patches/**` are GPL-3.
