# m13 (B3) Verification Artifacts

This tree holds live-smoke artifacts for the "full B3" landing — per-region wiring of entity AI, block-entity, and scheduled block/fluid ticks (see [`docs/design/m13-b3-region-tick.md`](../../design/m13-b3-region-tick.md) for the design spec, [CHANGELOG.md](../../../CHANGELOG.md#v130--full-b3-per-region-entityblock-tick--m12-event-routing) for the shipped scope).

Automated tests + fork compile prove the code is well-formed. What's still needed is empirical proof that a real running server produces the correct in-game behaviour when the trailing `vanillaBody.run()` is gone.

## What "verified" means for B3

The B3 removal is dangerous — v1.2.0's first attempt (commit `a4c6bd9`) silently disabled entity/block-entity/block-fluid ticking on the MultiForge-installed path and was reverted (`7b68c27`, /67 round-6 fork B F1 CRITICAL). Full B3 is safe only if:

1. **Mobs actually AI-tick** — walk, path, decay, breed on the region worker.
2. **Block entities actually tick** — hoppers move items, furnaces smelt, brewing stands brew.
3. **Scheduled block/fluid ticks fire** — redstone repeaters tick, water flows, sapling grows.
4. **`OwnerToken` guards fire correctly** — the `ProbeRegistry` `entity-ai.wrong-owner` / `block-entity.wrong-owner` counters stay at zero under normal load.
5. **No `[multiforge.violation/…]` spam** — auto-reroute-and-warn count stays low.

Unit tests (`PhasedRegionTickBody_BlockFluidTicksTest`, `PhasedRegionTickBody_EntityAiTest`, `PhasedRegionTickBody_BlockEntitiesPerRegionTest`) exercise the wiring with fake runners; they cannot substitute for a real server run because they don't drive the fork-side bridge that resolves the `WorldRef → ServerLevel` path or the `TickingBlockEntityRef` → Vanilla `TickingBlockEntity` adapter chain.

## Reports (drop-in scaffolds — populate on your live-smoke pass)

| Task | Report | What to observe |
|------|--------|-----------------|
| B3-live.1 | [`b3-live-mob-ai.md`](b3-live-mob-ai.md) | Spawn 50 mobs, verify they AI-tick |
| B3-live.2 | [`b3-live-block-entities.md`](b3-live-block-entities.md) | Place hopper + furnace, verify they tick |
| B3-live.3 | [`b3-live-scheduled-ticks.md`](b3-live-scheduled-ticks.md) | Place redstone repeater + water source + sapling, verify scheduled ticks fire |
| B3-live.4 | [`b3-live-strict-mode.md`](b3-live-strict-mode.md) | 5-min soak under `-Dmultiforge.regiontick.strict=on`; zero `RegionTickOverrunException`, zero wrong-owner probes |

## How to run

```
# Build + publish current tip
export JAVA_HOME=/path/to/jdk-21
./gradlew :multiforge-api:publishToMavenLocal :multiforge-runtime:publishToMavenLocal

# Build + boot the fork (interactive server)
cd upstream/neoforge-1.21.1
./gradlew :neoforge:applyMultiforgePatches      # separate invocation
./gradlew :neoforge:compileJava                 # separate invocation
./gradlew :neoforge:runServer                   # foreground server

# Once "Done" appears in the log, follow the per-report procedure:
#   - B3-live.1: /summon minecraft:cow ~ ~ ~   (loop 50 times)
#   - B3-live.2: /setblock ~ ~ ~ minecraft:hopper
#   - B3-live.3: /setblock ~ ~ ~ minecraft:redstone_wall_torch
#   - B3-live.4: reboot with -Dmultiforge.regiontick.strict=on, run for 5 min

# For each report, capture:
#   - The relevant /multiforge probe counter snapshots (before + after)
#   - The last 100 lines of logs/multiforge-*.log
#   - Server-side observations (mob position moved? hopper moved item? etc.)
```

## Evidence layout

Drop artifacts under `evidence/b3-live-N/`:

```
evidence/
├── b3-live-1/
│   ├── probe-before.txt
│   ├── probe-after.txt
│   ├── multiforge.log.tail
│   └── observations.md
├── b3-live-2/
└── ...
```

Each report page has an "Actual result" section to fill in with a PASS/FAIL verdict + a brief narrative. Committing filled-in reports here (with the `evidence/` files) is the definitive B3 verification record.
