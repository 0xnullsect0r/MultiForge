# 7.4 — Bench harness profiles

This directory holds the M9 exit-gate performance artifacts, one
subdirectory per bench profile from runbook §5. Each profile directory
holds `baseline.json` (captured from the same pre-M9 tag used for 7.2,
on the same hardware) and `patched.json` (captured from `develop`), plus
the TPS/MSPT/heap graphs rendered from them. The `:multiforge-bench:vanilla`,
`:atm10`, and `:swarm` Gradle tasks that will eventually produce these
files are still stubs (see runbook §7 and §5) — this directory exists so
the artifact layout is settled ahead of that implementation work.

- **`vanilla/`** — plain NeoForge dedicated server, no mods. Control
  baseline: a MultiForge regression here means the ownership guards
  themselves cost too much even with nothing else running.
- **`atm10/`** — the All the Mods 10 modpack. Realistic mod-load case;
  the primary number the M9 exit gate cares about.
- **`swarm-20/`, `swarm-100/`, `swarm-500/`** — headless bot swarm at
  20/100/500 simulated players, no mods beyond MultiForge itself. Also
  the carrier for the 7.6 strict-mode watchdog at the 100-player tier
  (see `7.6/README.md`).
