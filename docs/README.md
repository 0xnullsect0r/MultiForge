# MultiForge Docs

**Start here:** [`design/barrier-tick-model.md`](design/barrier-tick-model.md)
is the authoritative description of how MultiForge runs today: the server
thread runs Vanilla's loop, and each level's regions tick in parallel
between two barriers. Where another page disagrees with it, it wins.

**Verification:** [`verification/README.md`](verification/README.md) — the
verification results and the runbook for reproducing them.

## Running a server

- [`install.md`](install.md) — installing MultiForge: installer jar, drop-in replacement zip for an existing NeoForge server, Pelican/Pterodactyl egg; EULA, config, systemd, rollback, troubleshooting.
- [`compatibility.md`](compatibility.md) — which mods and modpacks run, the NeoForge base version, mods that run their own threads, known errors and their causes.
- [`multiforge-command.md`](multiforge-command.md) — `/multiforge` operator command reference: `config`, `region`, `probes`, `tickstats`, `chunks`, `warn`, `certify`.
- [`regions.md`](regions.md) — how regions form from loaded chunks, region size, pins, and the region settings in `multiforge-server.toml`.
- [`perf-tuning.md`](perf-tuning.md) — when parallel region ticks help; worker pool, region size, pins and mode; measuring tick time, regions and probes; the bench harness.
- [`client-mod-guide.md`](client-mod-guide.md) — the optional client debug mod: region seams, tick-cost heatmap, MSPT/TPS panels, pins, violation feed.

## How it works

- [`design/barrier-tick-model.md`](design/barrier-tick-model.md) — the tick model: what the server thread runs, what regions run between the barriers, positional ownership, shared Vanilla state made safe, strict mode.
- [`concurrency-contract.md`](concurrency-contract.md) — owner tokens, `OwnershipEnforcer`/`OwnershipGuard` (`canMutateAt`, `rerouteAt`, predicted return values, deferral to the server thread), modes, who may write what.
- [`events.md`](events.md) — how NeoForge event listeners are dispatched: `@DispatchDomain`, the serial lane, per-mod safety classes, per-event default domains.
- [`legacy-compat.md`](legacy-compat.md) — how unported mods keep working: positional reroute, the serial lane, deferral, per-mod classification, operator settings.
- [`chunks.md`](chunks.md) — chunks are Vanilla's; the runtime only indexes loaded chunks by owning region.
- [`migration.md`](migration.md) — why entities are not migrated between regions: ownership follows position, cross-region moves are deferred.
- [`global-network.md`](global-network.md) — global systems, the synthetic global region, networking and command execution under the barrier model.
- [`persistence.md`](persistence.md) — saving is Vanilla's; what happens to queued work at server stop.
- [`blueprint.md`](blueprint.md) — the original design document and milestone history, with the status of each milestone (several replaced by the barrier model).

## Writing and porting mods

- [`api.md`](api.md) — the public `multiforge-api`: `ServerDomains` (region, entity, global, async), Folia-style wrappers, dispatch annotations, `@RegionThread`.
- [`mod-porting.md`](mod-porting.md) — before/after recipes: reaching across regions, moving entities, static state, work from foreign threads, packet handlers, config reload, broadcasts.
- [`debugging-violations.md`](debugging-violations.md) — reading reroute warnings and probe counters, strict mode, the client debug mod, tracing a warning to a source line.
- [`certification.md`](certification.md) — the mod-safety scanner (rules R01–R12, tick reachability, suppressions, CI corpus gate), certification criteria, `/multiforge certify` and `/multiforge warn`.
- [`scheduler-api.md`](scheduler-api.md) — runtime internals behind the API: runtime install, how the server thread drives region ticks, `RegionizedTaskQueue.queueChunkTask`, `ChunkHolderManager`.

## Design specifications (current)

- [`design/scanner-rules.md`](design/scanner-rules.md) — specification of the 12 scanner rules, suppression format, report formats, CLI and corpus gate. Some rule rationales still refer to retired M4/M9 components (see `certification.md`).
- [`design/client-debug-protocol.md`](design/client-debug-protocol.md) — the `multiforge:debug/v1` wire protocol between server and client debug mod.
- [`design/m12-event-routing.md`](design/m12-event-routing.md) — detailed design of the `NeoForge.EVENT_BUS` wrapping and dispatch decision tree; `events.md` is the current reference.
- [`design/nbt-semantic-diff.md`](design/nbt-semantic-diff.md) — what "semantically equal" means for two world saves, as used by the bench harness's `WorldDiff`.

## Historical design documents

Kept for the record; they describe machinery that was replaced or never used.

Marked **Historical — superseded** in the document:

- [`design/m13-b3-region-tick.md`](design/m13-b3-region-tick.md) — B3's free-running per-region entity/block-entity/scheduled-tick wiring, replaced by the barrier model.
- [`design/m9-contracts.md`](design/m9-contracts.md) — interface contracts of the M9 chunk-system fork.
- [`design/m9-patch-strategy.md`](design/m9-patch-strategy.md) — per-class patch topology for the M9 chunk-system fork.
- [`design/m9-phase6-audit-server.md`](design/m9-phase6-audit-server.md) — M9 downstream caller audit, server cohorts.
- [`design/m9-phase6-audit-client.md`](design/m9-phase6-audit-client.md) — M9 caller audit, client, events and sweep.
- [`design/m9-phase7-runbook.md`](design/m9-phase7-runbook.md) — M9 verification runbook.
- [`design/m9-review-round-5.md`](design/m9-review-round-5.md) — M9 review, round 5.
- [`design/mca-format.md`](design/mca-format.md) — the M9 region-file (MCA) reader/writer contract.
- [`design/multiforge-chunkmap.md`](design/multiforge-chunkmap.md) — the M9 `MultiForgeChunkMap` facade.
- [`design/multiforge-distancemanager.md`](design/multiforge-distancemanager.md) — the M9 `MultiForgeDistanceManager` facade.
- [`design/multiforge-lightengine.md`](design/multiforge-lightengine.md) — the M9 light-engine facade.
- [`design/multiforge-priority-queue.md`](design/multiforge-priority-queue.md) — disposition of Vanilla's chunk task priority queue under M9.

Describing retired machinery, without the banner:

- [`design/entity-migration.md`](design/entity-migration.md) — the M4 entity-migration protocol (serialise and re-create across regions), retired; see `migration.md`.
- [`design/global-region.md`](design/global-region.md) — the M5 global-region contract (Vanilla subsystems re-run on a global worker), retired; see `global-network.md`.

## Verification records

- [`verification/README.md`](verification/README.md) — verification results and runbook (start here).
- [`verification/results/`](verification/results/) — raw result files from verification runs.
- [`verification/m12/`](verification/m12/) — live-smoke evidence for M12 event-bus routing.
- [`verification/m13/`](verification/m13/) — live-smoke evidence for the B3 per-region tick wiring (pre-barrier model).
- [`verification/m456/`](verification/m456/) — M4/M5/M6 bench-verification scripts and records (cross-region teleport, raid stress, dragon fight, strict-mode swarm); M4/M5 have since been replaced.
- [`verification/m9/`](verification/m9/) — M9 chunk-system-port Phase 7 results (retired system).
