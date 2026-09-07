# CHANGELOG

## v1.3.12 — bundle `multiforge-runtime` + `multiforge-api` classes into `multiforge-client.jar`

v1.3.11 got `multiforge-client.jar` past FML's mods.toml parse, but the mod then crashed at construct-time on a stock NeoForge 21.1.249 client with:

```
java.lang.NoClassDefFoundError: net/multiforge/runtime/diagnostics/wire/DebugPayload$PinList
  at net.multiforge.client.DebugHudState.<init>(DebugHudState.java:41)
```

Root cause: the client jar contained only `net.multiforge.client.*` classes. `DebugHudState`, `DebugPayloadRegistration`, and the four renderers reference wire-protocol types from `net.multiforge.runtime.diagnostics.wire.*` (`DebugPayload` + its inner records, `DebugPacketCodec`, `DebugPacketKind`) — those classes ship in `multiforge-runtime.jar`, which is embedded as `META-INF/jarjar/multiforge-runtime-*.jar` inside the fork's universal jar server-side, but has no path onto a stock NeoForge client. The `implementation(project(":multiforge-runtime"))` gradle dep resolved the classes at *compile* time but the shipped jar's runtime classpath had nothing.

- **multiforge-client/build.gradle.kts** — new `tasks.jar { from(zipTree(...)) }` block that merges `multiforge-runtime`'s and `multiforge-api`'s class files directly into the client jar. Server-only runtime classes come along as dead code (harmless — class-loading is lazy; the client execution path never touches region/scheduler/chunk classes). Explicit excludes:
  - `META-INF/services/**` — avoids a v1.3.4-style `securejarhandler` "Invalid service type name" crash from the runtime's `SchedulerHost` SPI file landing in the client jar's module descriptor.
  - `META-INF/MANIFEST.MF` — client keeps its own manifest, no duplicate error.
  - `META-INF/maven/**` — drops the mavenLocal-published pom debris from the merge.
  - `multiforge-runtime.properties*` — pre-existing runtime-jar debris (the template file that survives runtime's `processResources` when the `filesMatching` pattern doesn't match); belongs in the runtime jar cleanup, not the client jar.

Also (same release):
- **`/multiforge help`** — new subcommand printing an intuitive one-screen reference for every `/multiforge` subcommand (worker pool / region topology / diagnostics / scanner). Same output now fires on a bare `/multiforge` too (previously printed a terse `Usage: /multiforge <config|region|…>` line). Aliases: `help`, `?`, `--help`, `-h`. `Unknown subcommand` responses now direct the user to `/multiforge help`.
- **Tab-completion for `/multiforge`** — `MultiForgeCommandBinder` now builds a full Brigadier tree instead of a single greedy-string catchall. Ops get real Brigadier autocomplete on every subcommand, argument-type checking (integer args are bounded — e.g. `config cores 1..128`, `region size 1..256`), and typed suggestions (`region mode` offers `player-only` / `full-world`; `region pin <id> <world>` and `chunks <world>` suggest the loaded dimensions via `SharedSuggestionProvider.suggestResource`). Every terminal node still routes through the same `MultiForgeCommandDispatcher.dispatch(String[], Consumer<String>)` so subcommand behavior stays centralized in the runtime.

## v1.3.11 — expand `${version}` in `multiforge-client`'s `neoforge.mods.toml`

Every release from v1.3.7 through v1.3.10 shipped a `multiforge-client.jar` with a literal `version = "${version}"` in its bundled `META-INF/neoforge.mods.toml` — FML rejects it at scan with `Illegal version number specified version` and refuses to load the mod. Reported by a user who dropped `multiforge-client.jar` into a stock NeoForge 21.1.249 client's `mods/` folder:

```
Exception message: net.neoforged.neoforgespi.locating.InvalidModFileException:
  Illegal version number specified version (multiforge-client.jar)
  at net.neoforged.fml.loading.moddiscovery.ModInfo.<init>(ModInfo.java:77)
```

Bug was invisible in server-side testing (the mod jar is never loaded there) and only surfaces on a real NeoForge client's mod scanner.

- **multiforge-client/build.gradle.kts** — new `processResources { filesMatching("META-INF/neoforge.mods.toml") { expand(mapOf("version" to project.version.toString())) } }` block. Mirrors the pattern already in `multiforge-runtime/build.gradle.kts` (which templates its own `multiforge-runtime.properties.in`). Root cause: the `net.neoforged.moddev` 2.0.78 plugin does NOT auto-configure Groovy-template expansion on `neoforge.mods.toml`.

## v1.3.5 — ship `multiforge-client` as a real mod jar + wire `/multiforge` into Brigadier

Two user-visible correctness gaps closed in the same release.

### Gap A — `multiforge-client` is now a distributable NeoForge mod jar

Prior to v1.3.5 the client debug mod lived in the repo only as source: `multiforge-client/build.gradle.kts` carried a `compileOnly` on the NeoForge `-universal` classifier (only ~1.4k of the ~8k needed classes), six of the nine `.java` files failed to compile, and the built jar contained three classes and no `FMLModType` manifest — not a mod. `docs/install.md:288` pointed users at the source directory, which is a dead link from an install doc.

- **`settings.gradle.kts`** — added `maven("https://maven.neoforged.net/releases")` to `pluginManagement.repositories { }` so the `net.neoforged.moddev` plugin resolves, and added `mavenLocal()` to `dependencyResolutionManagement.repositories { }` so the vendored fork's mavenLocal-published multiforge-runtime + multiforge-api artifacts are visible outer-side.
- **`multiforge-client/build.gradle.kts`** — replaced the compileOnly-`:universal` workaround with the `net.neoforged.moddev` plugin (`version = "2.0.78"`), which supplies a real NeoForge compile classpath (NeoForm-produced vanilla + NeoForge patches, merged). Targets `neoForgeVersion=21.1.90` from the outer `gradle.properties`. Deleted the ~40-line comment block that documented the missing wiring.
- **`multiforge-client/src/main/resources/META-INF/neoforge.mods.toml`** — dropped the `logoFile = "logo.png"` reference (no `logo.png` on disk); corrected the license field from `"Proprietary — see LICENSE …"` (stale, from pre-v1.2.0) to `"GPL-3.0-only"`; fixed the issue-tracker URL casing.
- **`.github/workflows/release.yml`** — `build-jars` job now also runs `:multiforge-client:build`; the assemble step stages the built jar into `release/` under both `multiforge-client-<v>.jar` and stable-alias `multiforge-client.jar` (matches the fork installer's pattern so docs can link `.../releases/latest/download/multiforge-client.jar`). Release-body Downloads section describes the new asset. `GRADLE_OPTS=-Xmx6G` bumped to match `scanner.yml`'s fork-build (moddev's NeoForm decompile is the largest step).
- **`docs/install.md`** — client-mod link swapped from source-dir path to stable-alias release URL, with drop-into-`mods/` instructions.
- **`RELEASING.md`** — asset enumeration extended for `multiforge-client-*.jar` + `multiforge-client.jar` (and the previously-undocumented `multiforge-installer.jar` stable alias).

### Gap B — `/multiforge` command is registered on Brigadier

Live-verified on a v1.3.4 server: an op user typing `multiforge` in chat, and the server-console typing the same, both hit `Unknown or incomplete command … multiforge<--[HERE]`. `/neoforge` worked, so Brigadier was healthy. Root cause: `multiforge-runtime/src/main/java/net/multiforge/runtime/commands/MultiForgeCommandDispatcher.java` was a pure-Java parser (`Consumer<String>` output); no code anywhere in the runtime, fork, or patches wrapped it in a Brigadier tree, constructed it, or subscribed to `RegisterCommandsEvent`. The class shipped in the runtime jar but was unreachable. Every command documented in `README.md § In-game commands` was dead.

- **`upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/commands/MultiForgeCommandBinder.java`** — new file. Loads `MultiForgeConfigStore` from `<serverDir>/config/multiforge-server.toml` and `RegionPinManager` from `<serverDir>/config/multiforge-region-pins.json` (both files are created on first successful mutation; missing/malformed files log a `ViolationLogger.warn` and fall back to defaults so the command tree still installs). Constructs a `MultiForgeCommandDispatcher(configStore, pins)` and registers a `RegisterCommandsEvent` listener on `NeoForge.EVENT_BUS` that binds `/multiforge` to a Brigadier tree with a greedy-string args argument. `.requires(src -> src.hasPermission(2))` restricts to ops + server console per README's "op-only" language.
- **`upstream/neoforge-1.21.1/src/main/java/net/neoforged/neoforge/server/ServerLifecycleHooks.java`** — call `MultiForgeCommandBinder.register(server)` from `handleServerAboutToStart`, gated on the same `freshInstall` flag used by `MultiForgeGlobalSystemsInit.install(...)` so a re-used GameTestServer JVM doesn't double-register the command.

Follow-up gap not in v1.3.5 scope: `/multiforge config cores N` writes to disk but the live `MultiForgeConfig` inside `MultiThreadedSchedulerHost` isn't re-plumbed to the store's `subscribe(...)` hook, so worker-pool changes still require a restart. Same pre-existing limitation as M6; separate M-milestone.

## v1.3.4 — fix `processResources` rename bug that crashed boot + fix README EULA step

v1.3.3 got the fork installer down the wire correctly, but the resulting server crashed on boot with:

```
IllegalArgumentException: multiforge-runtime.properties: Invalid service type name: 'multiforge-runtime' is not a Java identifier
  at cpw.mods.securejarhandler/cpw.mods.jarhandling.impl.SimpleJarMetadata.computeDescriptor(SimpleJarMetadata.java:47)
```

- **multiforge-runtime/build.gradle.kts** — `processResources` was calling `rename { "multiforge-runtime.properties" }` from inside a `filesMatching { }` block. In Kotlin DSL, that `rename(Closure)` resolves to the outer `AbstractCopyTask.rename` method, which runs the closure against **every file in the copy** — the closure unconditionally returns `"multiforge-runtime.properties"`, so every file got renamed to that. `META-INF/services/net.multiforge.api.spi.SchedulerHost` ended up as `META-INF/services/multiforge-runtime.properties`, which broke the SchedulerHost SPI AND crashed `securejarhandler` on boot because "multiforge-runtime" is not a valid Java identifier. Fix: replace the outer `rename` call with `name = "multiforge-runtime.properties"` on the `FileCopyDetails` inside `filesMatching`, and widen the pattern to `**/multiforge-runtime.properties.in` so the template is actually matched and expanded.
- **README.md + docs/install.md Method 1** — swapped the EULA step from `sed -i 's/eula=false/eula=true/' eula.txt` to `echo "eula=true" > eula.txt`. The fork installer doesn't seed `eula.txt`, so `sed -i` on a nonexistent file failed silently; users had to know to write it manually. Also removed the phantom `eula.txt` entry from the installer's file-layout diagram.

## v1.3.3 — `multiforge-installer.jar` stable alias points at the fork installer

v1.3.2 shipped both the working fork installer (`multiforge-1.3.2-installer.jar`, produced by `build-fork-installer` CI job) AND the broken pure-Java installer (`multiforge-installer-1.3.2.jar` + `multiforge-installer.jar` stable alias, produced by `build-jars` job). The README's `curl` command downloaded the stable alias, which resolved to the broken pure-Java installer — every user who followed the README got `Error: Could not find or load main class net.multiforge.runtime.bootstrap.Main`.

- **release.yml** — moved the `multiforge-installer.jar` stable-alias `cp` from `build-jars` (pointing at the broken pure-Java jar) into `build-fork-installer` (pointing at the working fork jar). Extended the fork installer's upload-artifact path glob to include both filenames. Reworded the release-body Downloads section to describe the fork installer's `--installServer <dir>` CLI (not the pure-Java `install --install-dir` shape).
- **README.md** — `java -jar ../multiforge-installer.jar install --install-dir .` → `java -jar ../multiforge-installer.jar --installServer .` (NeoForge installer shape).
- **docs/install.md Method 1** — same CLI swap; rewrote the "This drops" file-layout block to reflect the fork installer's real output (`libraries/net/neoforged/neoforge/<v>/`, top-level `run.sh` + `user_jvm_args.txt`, no `libraries/multiforge/` at top of tree); rewrote the "Installer CLI reference" block to describe NeoForge's actual `--installServer`/`--installClient`/`--extract`/`--help` args.

The pure-Java `multiforge-installer/` module stays around because its `build-zip` subcommand still synthesizes the Method 2 drop-in replacement ZIP. Follow-up cleanup will delete it entirely once the drop-in-ZIP flow is either fixed or dropped.

## v1.3.2 — B2 binary-patch integration + regionizer noise + Pelican egg + CI

First release where a fresh `--installServer` produces a fully-functional MultiForge server. Prior to v1.3.2 the shipped installer wrote a broken layout; v1.3.1 (the M12 boot fix) got the server past mod-loading but B2 global subsystems still failed to register.

### P — B2 binary-patch integration (the real bug fix)

`binarypatcher` (net.minecraftforge:binarypatcher:1.1.1, invoked by NeoGradle's `generateServerBinaryPatches`) runs in **whitelist mode** when `--patches` is non-empty: only classes with a matching `.patch` file in the patch dir get a binary diff generated. NeoGradle wires `--patches` = `upstream/neoforge-1.21.1/patches/` (NeoForge's own tree only), so our `multiforge-patches/**/*.java.patch` never reached binarypatcher. Classes we seed-from-base and patch (WorldBorder, Raids, LevelTicks, ServerFunctionManager, ServerScoreboard, CustomBossEvents, ChunkGenerationTask, ChunkHolder, ThreadedLevelLightEngine, ProcessorMailbox, LevelAccessor) compiled correctly into the fork jar but the shipped installer's `server.lzma` had no binary diff for them. Runtime code hit `NoSuchMethodError: 'void net.minecraft.world.level.border.WorldBorder.mfTickBody()'` at boot.

- **P.1** — New `stageMultiforgePatchesForBinaryPatcher` Sync task in `multiforge-patches.gradle` flattens `multiforge-patches/**/net/minecraft/**/*.java.patch` into `build/multiforge-patches-staging/<target-path>.patch` (the layout binarypatcher expects). `net/neoforged/**` patches excluded — they target NeoForge's own hand-written source, not vanilla, and staging them causes JPMS split-package errors. Uses `Sync` (not `Copy`) so stale destination files get cleaned.
- **P.2** — Wires the staging dir into `generateServerBinaryPatches`/`generateClientBinaryPatches`/`generateJoinedBinaryPatches` via `.getPatches().from(...)` + `dependsOn`. Live verification: `mfTickBody` string count in `output.lzma` went from 0 → 3; fresh install + boot completes with `Done (2.803s)`, zero `NoSuchMethodError`, zero `failed to register B2low`, all 8 global subsystems bind cleanly.

### R — Regionizer noise (dimensions that never load chunks)

- **R.1** — New `RegionizerEagerInit.materialiseAll(server, host)` in the fork bridge iterates `server.getAllLevels()` on `ServerAboutToStartEvent` and calls `host.regionizerFor(...)` for each. Ensures dimensions like `the_end` and `the_nether` that never see `ChunkEvent.Load` at boot get a regionizer up front. `regionizerFor` is idempotent (`computeIfAbsent`), so lazily-loaded worlds remain safe.
- **R.2** — `LevelTickDispatchProbes.noRegionizerSkip` gains a per-world `ConcurrentHashMap` sentinel: probe counter still bumps unconditionally, but `ViolationLogger.warn` fires exactly once per world (site = `"region-tick.no-regionizer-skip::" + worldId`). Removes the pre-fix log spam where every unloaded dim warned every tick, competing for a single rate-limit bucket.

### E — Pelican Panel / Pterodactyl egg

- **E.1** — New `pelican-egg.json` at repo root. PLCN_v1 schema. Startup command mirrors NeoForge's own `run.sh` (`java @user_jvm_args.txt @unix_args.txt nogui`). Install script downloads the MultiForge fork installer JAR from the latest release, runs `--installServer`, and symlinks the generated `libraries/net/neoforged/neoforge/<v>/unix_args.txt` to `/mnt/server/unix_args.txt`. 4 variables: `MULTIFORGE_VERSION` (default `latest`, resolves via GitHub API), `DOWNLOAD_URL` (template with `{VERSION}` substitution), `MC_VERSION` (display only, default `1.21.1`), `SERVER_JARFILE` (fallback name). No `LICENSE_KEY` — GPL-3, no gating.
- **E.2** — `docs/install.md` gains "Method 3 — Pelican Panel / Pterodactyl egg" section; `README.md` gains third install bullet.
- **E.3** — Release workflow attaches `pelican-egg.json` as a release asset; release-body template mentions it with a Method 3 link.

### C — Release CI + fallback

- **C.1** — New `build-fork-installer` job in `.github/workflows/release.yml` runs `./gradlew :setup :neoforge:applyMultiforgePatches :neoforge:signInstallerJar` and stages the output as `release/multiforge-<v>-installer.jar`. `continue-on-error: true` (fork build has been GHA-preempted historically); `gh-release` still publishes from `build-jars` alone if the fork job fails, via `if: always() && needs.build-jars.result == 'success'`.
- **C.2** — New `RELEASING.md` at repo root documents the maintainer's release flow: standard (tag → CI → verify), fallback if `build-fork-installer` failed (local `./gradlew :setup :neoforge:signInstallerJar` + `gh release upload --clobber`), post-release verification (`curl` the download URL, install into a temp dir, grep boot log), version bumping (gradle.properties in both outer + fork), and rollback.

### Also

- Version bump: `gradle.properties` and `upstream/neoforge-1.21.1/gradle.properties` both from 1.3.0 → 1.3.2 (v1.3.1's tag was cut without a version bump; catching up in the same commit).
- Install docs and README's "two ways" language corrected to "three ways" now that the Pelican egg is a documented method.

### Deferred past v1.3.2

- MC 1.21.2+ / Fabric support
- Top-20 mod compat matrix + 24h ATM10 soak
- `multiforge-client` gradle wiring via NeoForge `moddev` plugin
- Fork-compile CI stability if GHA still preempts even on ubuntu-24.04-large
- v1.3.0 GHCR package deletion (still user's UI action)

## v1.3.1 — M12 live-boot fix + Docker/GHCR removal

- **fix(m12):** `DispatchingEventBus.addListener(Consumer)` family broke NeoForge's ASM consumer-type introspection ("Failed to resolve consumer event type: RoutingListenerWrapper@…") because a plain wrapper class doesn't carry the invokedynamic lambda bootstrap NeoForge inspects. Fix: pass the raw Consumer through unwrapped for the addListener family; `@DispatchDomain` routing still applies to `@SubscribeEvent` methods registered via `register(Object)`. Unit tests couldn't catch this — surfaced only in live server boot.
- **chore:** removed Docker + GHCR support entirely. The published image was never wired to actually boot a server (bundled the installer but no server main class). Users install via Method 1 (fresh installer JAR) or Method 2 (drop-in replacement ZIP).
- **docs(install):** new `docs/install.md` guide covering both install methods with EULA, config, systemd, rollback, troubleshooting.
- **chore:** removed the `sync-downloads-repo` workflow (obsolete post-GPL-3).

## v1.3.0 — Full B3 (per-region entity/block-tick) + M12 (event routing)

The final two blueprint milestones needed for a completely working product: the entity-AI hot loop finally runs on region workers (the whole point of parallelization), and event dispatch honors `@DispatchDomain` transparently for every listener.

### B3 (M13) — Per-region entity, block-entity, and scheduled block/fluid tick wiring

Prior state: v1.2.0's `RegionizedTickCoordinator.dispatchLevelTick` ran the residual per-level tick body (`entityTickList.forEach(::tickNonPassenger)`, `blockEntityTickers.tick()`, `blockTicks.tick` / `fluidTicks.tick`, `chunkSource.tick`) on the main server thread via a trailing `vanillaBody.run()`. Region workers only handled chunk loading + globals. Full B3 pulls the residual work into per-region phase bodies and removes the trailing call.

- **B3.0** — Design doc `docs/design/m13-b3-region-tick.md` (819 lines) + correction to `docs/blueprint.md:512-516` (§M8 sub-step 6b was incorrectly marked DONE — 6b landed only the fan-out barrier, not the actual per-chunk decomposition).
- **B3.1** — Runtime foundation: `ChunkHolderManager.holdersOwnedBy(RegionId)`, `HolderManagerRegionData.blockEntityTickers` per-region slice, `TickingBlockEntityRef` MC-free abstraction, `Region.ownedChunkSnapshot()` via `RegionChunkSource` functional interface (avoids region↔chunk package cycle), split/merge redistribution invariance.
- **B3.2** — `BLOCK_FLUID_TICKS` phase wired: `ScheduledTickRunner` interface + `ScheduledTickRunnerBridge`; thin patches for `ServerLevel.mfTickBlockFluidTicksForChunk` + `LevelTicks.mfContainerForChunk` (per-chunk drain never touches neighbor's entries).
- **B3.3** — `ENTITY_AI` phase wired: `EntityTickRunner` + `EntityTickRunnerBridge`; `ServerLevel.mfTickEntitiesForChunk` extracts the `entityTickList.forEach` iteration; `OwnerToken` correctness guard warns + skips on wrong-owner; `MIGRATING`-state entities skipped.
- **B3.4** — `BLOCK_ENTITIES` per-region phase wired **layered after** the existing global-only `phaseGlobalSystemsTick`: `BlockEntityTickRunner` + `VanillaTickingBlockEntityAdapter` + `BlockEntityTickerBridge`; `Level.updateBlockEntityTicker` routes into the owning region's slice; `Level.tickBlockEntities` guarded to skip when regions handle it.
- **B3.5** — Removed trailing `vanillaBody.run()`. `dispatchLevelTick` refactored to the frozen target shape from `docs/design/global-region.md:773-793`. Three fallbacks (bootstrap-skip, no-regionizer-skip, dispatch-failure) now `ProbeRegistry.bump` + `ViolationLogger.warn` — visible, not silent (v1.2.0's naive removal `a4c6bd9` was reverted precisely because it was silent). `git grep vanillaBody upstream/neoforge-1.21.1/src/main/java/net/multiforge/` returns zero.

### M12 — Transparent event-bus routing

Prior state: `@DispatchDomain` + `@Ordering` annotations existed in `multiforge-api/` but `IEventBus.post` ignored them.

- **M12.0** — Design doc `docs/design/m12-event-routing.md` (697 lines). Discovered via `javap` decompile of `net.neoforged:bus:8.0.1` that `EventBus.registerListener` is private — so `DispatchingEventBus` does its own `@SubscribeEvent` reflection scan + `addListener` rather than intercepting the internal path.
- **M12.1** — Runtime dispatcher: `AnnotationScanner` (3-tier: method → class → EventTypeDomainMap → LEGACY_SERIAL), `DomainDispatcher` (full decision tree), `DispatchExecutor` (MC-free interface), `RoutingListenerWrapper`, `DispatchingEventBus` (implements `IEventBus`), `AsyncEventPool` (bounded, `-Dmultiforge.event-async-pool.size` configurable). Added `net.neoforged:bus:8.0.1` as explicit runtime dep.
- **M12.2** — Fork bridge: `LazyDispatchingEventBus` (extends dispatcher, lazy `attachExecutor` via CAS) so `NeoForge.EVENT_BUS` can be initialized at class-load before MultiForge is installed; `SchedulerBackedDispatchExecutor` implements `DispatchExecutor` over `MultiThreadedSchedulerHost` (`enqueueRegion` via `RegionizedTaskQueue.queueChunkTask`, `enqueueGlobal` mirrors host's own pattern, `enqueueAsync` forwards to `AsyncEventPool`, `resolveEventLocation` pattern-matches ~14 event base classes). Patch: `multiforge-patches/09-events/net/neoforged/neoforge/common/NeoForge.java.patch` (first patch targeting NeoForge's own hand-written source; small `multiforge-patches.gradle` infra fix to resolve per-patch apply-target directory). `-Dmultiforge.event-dispatch=off` safety valve. `MultiForgeGlobalSystemsInit.install()` attaches the executor at `ServerAboutToStart`.
- **M12.4** — `EventTypeDomainMap` — 32 default entries covering the highest-value NeoForge events (tick/lifecycle/spawn/death/interaction/chat/command/server-lifecycle). Class-hierarchy walk in `lookup()` handles subclasses without explicit entries.

### /67 round-6 findings from v1.2.0 — all already fixed

CRITICAL F1 (silent tick disable from initial B3 attempt) reverted as `7b68c27`. Six HIGH findings (add-then-check races in `addSettledListener` + `enqueueOutbound`, `BossEvent`/`Scoreboard` global-worker reentry, installer fail-open, scanner R03/R09 gaps, `Entity.onPositionChanged` double-fire) all fixed inline before v1.2.0 shipped.

### v1.2.0 addendum — the license flip

The v1.2.0 shape shipped a re-license from proprietary to GPL-3.0-only (`f91b732`) — omitted from the v1.2.0 changelog entry below. Full removal of `multiforge-license/`, `multiforge-license-cli/`, Ed25519 signing infrastructure, and every `MULTIFORGE_LICENSE` env/token reference. `CLAUDE.md` rule #1 flipped. `README.md` rewritten. Repo public on GitHub.

### Deferred past v1.3.0

- **X.4** client HUD manual smoke on live NeoForge client (needs live client)
- **X.1/X.2/X.3/X.8** actual bench evidence collection — scripts landed under `docs/verification/m456/`, operator runs the benches
- B3 + M12 live smoke on the operator's workstation (unit tests + fork compile green; live server run recommended before production use)
- `multiforge-client` gradle wiring via NeoForge `moddev` plugin
- Fork-compile CI GHA-preemption (advisory, needs beefier runner)
- Scanner CI empty-SARIF-on-exit-1 anomaly triage
- Top-20 mod compatibility matrix
- 24-hour ATM10 soak test
- MC 1.21.2+ / Fabric support
- OSS onboarding polish (CONTRIBUTING.md, CODE_OF_CONDUCT.md, SECURITY.md)

## v1.2.0 — M4 + M5 + M6 landing

### M4 — Entity migration (`multiforge-patches/05-entity-migration/`, `06-networking/`)

- **A1** — Runtime hardening: `EntitySnapshot.payload` moved from `String`-stub to `CompoundTag` NBT + `passengers: List<EntitySnapshot>` for whole-tree capture; `EntityRegistry.retiredRefs` cache (ConcurrentHashMap + `ScheduledExecutorService` 200-tick TTL) so stale-UUID cross-region tasks resolve deterministically to `RETIRED`; `MigratingEntityRef.beginPassengerTreeSnapshot` DFS + single-pass CAS with abort-and-restore on any failure; `EntityMigrationCoordinator.completeAt` refuses destination insert until target holder ≥ `BORDER` via new `ChunkHolderManager.scheduleWhenHolderAt`; `ProbeRegistry.recordMigration` hooks + violation emit on abort.
- **A2** — Vanilla `Entity.setPosRaw` / `onMove` / `teleportTo` / `changeDimension` hop patches; `PersistentEntitySectionManager.onMove` swaps to `MigratingEntityRef.updateLocation`; `ServerLevel.addFreshEntity` funnels through `EntityRegistry.register`; 500-mob cross-region stampede + 5-deep passenger stack stress test.
- **A3** — Networking: `ServerGamePacketListenerImpl.handleMovePlayer` triggers `beginMigration` on cross-region move (blocks Vanilla `setPos` while `MIGRATING`); `Connection.send` queues packets to `MigratingEntityRef.pendingOutbound` during migration, drained in FIFO order via new `addSettledListener`; `PlayerList.placeNewPlayer` wires `PlayerJoinCoordinator` Netty→global→spawn-chunk-region hop end-to-end.
- Correctness fix: `MigratingEntityRef.forceTerminalFromMigrating` (single-CAS `MIGRATING`→`RETIRED`) replaces the old two-step `abortMigration()`-then-`retire()` that could fire settled-listeners twice.

### M5 — Global subsystems (`multiforge-patches/08-globals/`)

- **B1** — `GlobalSystems.tickAll` wired at phase 4 (`BLOCK_ENTITIES`) of the synthetic global region's tick body.
- **B2 low-risk (5)** — `WeatherSystem`, `TimeSystem`, `WorldBorderSystem`, `ScoreboardSystem`, `BossEventSystem`. Wrap-and-rename pattern on each Vanilla `tick()`; `GlobalSystemsBridge.xxxReady()` early-return guard.
- **B2 high-risk (3)** — `RaidsSystem` (raider spawn routes via `EntityMigrationCoordinator.spawnInDestRegion`, never direct entity-list touch); `DragonFightSystem` (new `TicketType.DRAGON` pins end-podium chunks; cross-region effects for portal/egg drops); `CommandDispatchSystem` (single-region commands → caller's region; multi-region → global; cross-region `/tp` → A3's networking hop).

### M6 — Tooling

- **C1** — Client debug mod (`multiforge-client/`): `@Mod("multiforge_debug")` scaffold + 4 renderers (HUD, chunk-border, heatmap, pin) + 5 server-side emitters (heartbeat, region-map, violation, pin-list, TPS histogram). See [docs/design/client-debug-protocol.md](docs/design/client-debug-protocol.md).
- **C2** — Mod-safety scanner (`multiforge-scanner/`): ASM 9.7 bytecode walker with 12 rules (R01-R12, see [docs/design/scanner-rules.md](docs/design/scanner-rules.md)). JSON + SARIF v2.1.0 emitters. `.multiforgeignore` fingerprint suppression.
- **C3** — 8 new operator docs: [concurrency-contract](docs/concurrency-contract.md), [scheduler-api](docs/scheduler-api.md), [events](docs/events.md), [legacy-compat](docs/legacy-compat.md), [mod-porting](docs/mod-porting.md), [debugging-violations](docs/debugging-violations.md), [perf-tuning](docs/perf-tuning.md), [certification](docs/certification.md). New `/warn` and `/certify` commands.
- **C4** — Ops hardening: Docker healthcheck real (mcstatus server-list-ping, no `|| exit 0`); real OTEL exporter honoring `-Dmultiforge.otel.endpoint` (hand-rolled OTLP-HTTP, non-blocking on region worker); installer Ed25519 signature check + `--require-signed` flag (default `true` for release builds, fails closed on missing `.sig`); bench `:atm10` fetch helper; scanner CI workflow (`.github/workflows/scanner.yml`).

### /67 round-6 review — CRITICAL + 6 HIGH findings, all fixed

- **CRITICAL (fork B F1)** — reverted the initial B3 commit (`a4c6bd9`). Removing the trailing `vanillaBody.run()` silently disabled `entityTickList.forEach(this::tickNonPassenger)`, `blockEntityTickers.tick()`, scheduled block/fluid ticks, and `serverChunkCache.tick()` on the MultiForge-installed path. Region workers' `BLOCK_FLUID_TICKS` and `ENTITY_AI` phases have no production wiring yet (only tests set them). Full B3 (per-region entity/block-tick wiring) is deferred to a follow-up milestone.
- **HIGH (fork A)** — `addSettledListener` + `enqueueOutbound` add-then-check races. Fixed with re-check-after-add + `FiredOnceListener` at-most-once wrapper.
- **HIGH (fork B F2)** — `BossEventSystem`/`ScoreboardSystem` `tryRoute` deferred same-thread mutations to next tick's mailbox drain when caller was already on the global-region worker; Vanilla's return-after-mutate contract broken. Fixed via `GlobalRegionThreadMarker` ThreadLocal; same-thread reentry runs inline.
- **HIGH (fork C, 3)** — installer fails-open when `.sig` resource missing (fixed with `--require-signed` flag + `SignatureCheck` fail-closed); scanner R03 missed polymorphic Future receivers (fixed with per-scan `TypeHierarchy` index); R09 disk-I/O allowlist missed common idioms (fixed by extending to `read*/write*` prefixes across `java/io/*Stream` + `java/nio/channels/*Channel` + wrapped-stream cases).
- **HIGH (fork D)** — `Entity.onPositionChanged` fired twice per move-tick (both `setPos` post-call and `setPosRaw` post-call hunks bound the hook); dropped the outer hunk, kept the inner funnel.

### Phase X

- **X.5** — `:multiforge-scanner:test` green (12/12 rules, R07-R12 tests + report emitters + ignore-file).
- **X.6** — 8 new C3 docs pages linked from `docs/README.md`.
- **X.7** — /67 round-6 (4 lenses in parallel forks); findings fixed as listed above.
- **X.1/X.2/X.3/X.8** — bench-verification harness scaffolded under [docs/verification/m456/](docs/verification/m456/) + [multiforge-bench/verification/m456/](multiforge-bench/verification/m456/) with `--dry-run` smoke passing; actual bench evidence collection deferred to the operator per that README's "How to run" runbook (needs a live MC server + physical infrastructure).
- **X.4** — client HUD sanity requires a live NeoForge client; pending manual operator test.

### Deferred past v1.2.0

- Full B3: per-region entity + block/fluid-tick wiring into the `ENTITY_AI` / `BLOCK_FLUID_TICKS` scheduler phases so the trailing `vanillaBody.run()` can be removed cleanly.
- Phase X.1/X.2/X.3/X.8 actual bench evidence collection (scripts + docs are landed; runs are the operator's).
- Phase X.4 manual client HUD smoke test.
- `multiforge-client` gradle wiring via the NeoForge `moddev` plugin so `./gradlew :multiforge-client:compileJava` passes through the ordinary build (currently verified only by direct javac against the fork's built classes, per the module's own build.gradle.kts commentary).

## v1.1.1 — M9 chunk-system port (CI stabilization)

Follow-up: fork-compile CI job continuation flag; race fixes in AutoSaveRunner + PhasedRegionTickBody tests.

## v1.1.0 — M9 chunk-system port

Per-region `ChunkMap` / `DistanceManager` / `ThreadedLevelLightEngine` / `RegionFile` facades; `ChunkHolderManager` + `NewChunkHolder`; `AutoSaveRunner` + `RegionJournal` WAL; MCA I/O; Vanilla-parity semantic NBT diff.

## v1.0.0 — M8 scheduler landing

Regionized tick pipeline; `ThreadedRegionizer` + `TickRegionScheduler` + `PhasedRegionTickBody` + `RegionizedTaskQueue`; `RegionListener` merge/split hooks.

## v0.9.0-m9 → v1.0.0

M0–M8 foundational milestones.
