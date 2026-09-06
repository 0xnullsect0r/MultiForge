# MultiForge Docs

- [`blueprint.md`](blueprint.md) — the original design document that drives
  the project.
- [`license.md`](license.md) — license token format and verification flow.
- [`api.md`](api.md) — public scheduler API (M1) with usage examples.
- [`regions.md`](regions.md) — region logic (M2): section→region→worker
  mapping, adaptive sizing, cross-region routing, operator config.
- [`chunks.md`](chunks.md) — chunk system (M3): NewChunkHolder + ticket
  types + priority-routed ChunkTaskScheduler + per-region merge/split.
- [`migration.md`](migration.md) — entity migration (M4): two-phase
  remove/add protocol, MigrationState machine, passenger-tree
  atomicity, cross-dimension, player-join flow.
- [`global-network.md`](global-network.md) — global systems + network
  routing + operator commands (M5): GlobalSystems tickers,
  NetworkPacketRouter, RegionPinManager, /multiforge command tree,
  per-mod warn budget.
- [`persistence.md`](persistence.md) — per-region autosave + WAL journal
  (M6): fsync-on-append, CRC-guarded frames, JournalReplayHarness for
  boot recovery, RegionShutdownCoordinator phase machine.
- [`downloads-repo-setup.md`](downloads-repo-setup.md) — one-time setup
  for the public `multiforge-releases` sibling repo + GHCR visibility,
  so all download URLs work anonymously.
- [`install.md`](install.md) — install guide (Docker + manual + systemd
  + Windows), migration from upstream NeoForge, rollback flow.
- [`operator-handbook.md`](operator-handbook.md) — day-to-day operator
  guide: config, commands, debug client, shutdown, crash recovery.
- [`website-install-copy.md`](website-install-copy.md) — drop-in copy
  for the purchase site's Installation page.

## Concurrency + Ops

- [`concurrency-contract.md`](concurrency-contract.md) — Domain enum,
  OwnerToken semantics, DomainAssertions (dev/CI), OwnershipEnforcer
  modes (OFF/REROUTE/STRICT), per-domain read/write rules.
- [`scheduler-api.md`](scheduler-api.md) — runtime-internal scheduler
  guide: MultiForgeRegionizedRuntime, RegionizedTaskQueue.queueChunkTask,
  ChunkHolderManager, the four SchedulerHost domains, do's/don'ts,
  cross-region recipes.
- [`events.md`](events.md) — @DispatchDomain contract + a ~30-event
  domain reference table (target mapping pending M12 enforcement).
- [`legacy-compat.md`](legacy-compat.md) — what the LEGACY_SERIAL lane
  does today vs. what the blueprint designs, mod-pattern compatibility
  table, migration path for mods hitting REROUTE warnings.

## Mod Safety + Performance

- [`mod-porting.md`](mod-porting.md) — before/after recipes for
  porting an existing NeoForge mod: chunk access, entity teleport,
  static caches, off-thread block/block-entity mutation, config
  reload, world-wide broadcasts, packet handling.
- [`debugging-violations.md`](debugging-violations.md) — reading
  `ViolationLogger` output, common violation types + fixes, using
  `-Dmultiforge.ownership.mode=strict` for regression, the client
  debug mod HUD, correlating stack traces to source lines.
- [`perf-tuning.md`](perf-tuning.md) — `multiforge.toml` tunables,
  region-count/memory and autosave-frequency tradeoffs, per-region vs
  server-average TPS, worker-count guidance, bench harness usage.
- [`certification.md`](certification.md) — MultiForge-certified
  criteria and process, `/multiforge warn` and `/multiforge certify`
  command reference.
