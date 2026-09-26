# M13 verification — per-region entity, block-entity and scheduled ticks

The four live checks this directory used to scaffold as unfilled report
templates are now automated. Results: [`../README.md`](../README.md).

| Check | Where it runs now |
|---|---|
| B3-live.1 — mobs tick (AI) in regions | GameTest `RegionTickBehaviourTests.entityTicks`; live in scenario `x1` and the swarm benches |
| B3-live.2 — block entities tick in regions | GameTest `RegionTickBehaviourTests.blockEntityTicks` |
| B3-live.3 — scheduled block/fluid ticks fire | GameTest `RegionTickBehaviourTests.scheduledBlockTickFires`; the vanilla-parity gate (`:multiforge-bench:determinism`) runs the fluid ticks world generation queues across four regions and compares the result with stock NeoForge |
| B3-live.4 — strict-mode soak: no overruns, no wrong-owner probes | `:multiforge-bench:x8StrictSwarm` (strict mode, protocol bots, fails on any violation or overrun) |

The GameTests run in CI's `fork-build` job on every change; the live checks
run nightly (`.github/workflows/nightly.yml`).
