# M9 Verification Artifacts

This tree holds the wall-clock verification artifacts required to close
M9 and cut `v0.9.0-m9`, per `docs/design/m9-phase7-runbook.md`. Each
subdirectory corresponds to one runbook task and is the drop point for
that task's world captures, diff output, or bench numbers.

- [`7.2/`](7.2/README.md) — byte-identical single-worker regression
  (baseline vs. patched world captures + diff).
- [`7.3/`](7.3/README.md) — N-worker semantic parity regression.
- [`7.4/`](7.4/README.md) — bench harness profiles (vanilla, atm10,
  swarm-20/100/500): TPS/MSPT/heap baselines and graphs.
- [`7.6/`](7.6/README.md) — 30-minute strict-mode watchdog logs.

See the runbook for prerequisites, pass criteria, and failure modes for
each task. Nothing under this tree is produced by CI — every artifact
here comes from a manual wall-clock run on a workstation with the
vendored NeoForge workspace, per runbook §1.
