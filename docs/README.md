# MultiForge Docs

## Getting started

- [`install.md`](install.md) — install guide covering all three methods (Docker image, fresh installer JAR, drop-in replacement ZIP for existing NeoForge servers). EULA, config, systemd, rollback, troubleshooting.

## Design

- [`blueprint.md`](blueprint.md) — the original design document that drives the project. Covers every milestone M0–M12.
- [`api.md`](api.md) — public scheduler API (M1) with usage examples.
- [`regions.md`](regions.md) — region logic (M2): section→region→worker mapping, adaptive sizing, cross-region routing, operator config.
- [`chunks.md`](chunks.md) — chunk system (M9): NewChunkHolder + ticket types + priority-routed ChunkTaskScheduler + per-region merge/split.
- [`migration.md`](migration.md) — entity migration (M4): two-phase remove/add protocol, MigrationState machine, passenger-tree atomicity, cross-dimension, player-join flow.
- [`global-network.md`](global-network.md) — global systems + network routing + operator commands (M5): GlobalSystems tickers, NetworkPacketRouter, RegionPinManager, `/multiforge` command tree, per-mod warn budget.
- [`persistence.md`](persistence.md) — per-region autosave + WAL journal (M9 persistence half): fsync-on-append, CRC-guarded frames, JournalReplayHarness for boot recovery, RegionShutdownCoordinator phase machine.
- [`events.md`](events.md) — `@DispatchDomain` contract + a ~30-event domain reference table (M12 wired the enforcement).

## Design docs — deep dives

- [`design/entity-migration.md`](design/entity-migration.md) — CAS state machine, passenger-tree atomicity, border-mid-tick rule, lost-UUID-ref prevention (M4 spec).
- [`design/global-region.md`](design/global-region.md) — synthetic global region + `GlobalTicker` + `crossRegionEffect` + `TicketType.DRAGON` (M5 spec).
- [`design/client-debug-protocol.md`](design/client-debug-protocol.md) — v1 wire codec for the debug HUD (M6 spec).
- [`design/scanner-rules.md`](design/scanner-rules.md) — 12-rule mod-safety scanner spec (M6).
- [`design/m12-event-routing.md`](design/m12-event-routing.md) — transparent NeoForge.EVENT_BUS wrapping, dispatch decision tree, ordering semantics (M12 spec).
- [`design/m13-b3-region-tick.md`](design/m13-b3-region-tick.md) — per-region entity/block-entity/block-fluid tick body wiring (B3 spec).
- Plus M9 chunk-system port docs under `design/m9-*.md` (contracts, patch strategy, phase 6 audit, phase 7 runbook, /67 round-5 review, MCA format, NBT semantic diff).

## Concurrency + Ops

- [`concurrency-contract.md`](concurrency-contract.md) — Domain enum, OwnerToken semantics, DomainAssertions (dev/CI), OwnershipEnforcer modes (OFF/REROUTE/STRICT), per-domain read/write rules.
- [`scheduler-api.md`](scheduler-api.md) — runtime-internal scheduler guide: MultiForgeRegionizedRuntime, RegionizedTaskQueue.queueChunkTask, ChunkHolderManager, the four SchedulerHost domains, do's/don'ts, cross-region recipes.
- [`legacy-compat.md`](legacy-compat.md) — what the LEGACY_SERIAL lane does today vs. what the blueprint designs, mod-pattern compatibility table, migration path for mods hitting REROUTE warnings.

## Mod Safety + Performance

- [`mod-porting.md`](mod-porting.md) — before/after recipes for porting an existing NeoForge mod: chunk access, entity teleport, static caches, off-thread block/block-entity mutation, config reload, world-wide broadcasts, packet handling.
- [`debugging-violations.md`](debugging-violations.md) — reading `ViolationLogger` output, common violation types + fixes, using `-Dmultiforge.ownership.mode=strict` for regression, the client debug mod HUD, correlating stack traces to source lines.
- [`perf-tuning.md`](perf-tuning.md) — `multiforge.toml` tunables, region-count/memory and autosave-frequency tradeoffs, per-region vs server-average TPS, worker-count guidance, bench harness usage.
- [`certification.md`](certification.md) — MultiForge-certified criteria and process, `/multiforge warn` and `/multiforge certify` command reference.

## Verification

- [`verification/m9/`](verification/m9/) — M9 chunk-system-port Phase 7 wall-clock verification results (world hash parity vs upstream, MCA I/O round-trip, autosave/journal replay).
- [`verification/m456/`](verification/m456/) — M4/M5/M6 bench-verification harness (X.1 cross-region teleport, X.2 raid stress, X.3 dragon fight regression, X.8 60-min strict-mode swarm). Scripts landed; runs are operator work.
