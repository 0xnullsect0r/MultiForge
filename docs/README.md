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
- More coming (Operator Handbook, Mod Porting Cookbook, Debugging
  Manual, Performance Tuning) as milestones land.
