# Phase X (M4–M6) verification

The shell scripts and report templates this directory held were replaced:
they carried a stale proprietary header, required a license key that no
longer exists, read probes of the retired M4 entity migration, and diffed
whole world saves that stock NeoForge does not reproduce. Each check now
runs on stock NeoForge and on MultiForge and compares the outcomes.
Results: [`../README.md`](../README.md).

| Check | Where it runs now |
|---|---|
| X.1 — cross-region teleport | `:multiforge-bench:scenario -Pscenario=x1` |
| X.2 — raid | `:multiforge-bench:scenario -Pscenario=x2` (a real Bad Omen raid, started by a protocol bot) |
| X.3 — dragon fight | `:multiforge-bench:scenario -Pscenario=x3` (killed through damage attributed to a protocol bot; exit portal, egg and gateway checked) |
| X.4 — client debug HUD | needs a graphical client; runbook in [`../README.md`](../README.md) |
| X.5 — scanner rules | `./gradlew :multiforge-scanner:test :multiforge-scanner:scanCorpus` (CI gate) |
| X.8 — strict-mode swarm | `:multiforge-bench:x8StrictSwarm` (100 bots, 60 min by default) |
| — fixture mods | `:multiforge-bench:scenario -Pscenario=x4` (`multiforge-testmods`) |
