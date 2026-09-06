# CHANGELOG

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
