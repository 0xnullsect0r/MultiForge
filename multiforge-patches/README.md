# multiforge-patches

Git-format patches applied to a vendored NeoForge 1.21.1 tree at build
time. NeoForge itself uses the same approach against Vanilla Minecraft
via [NeoForm](https://github.com/neoforged/NeoForm); this module layers
on top.

## Patch groups

Patches are grouped by concern so upstream rebases can be scoped. Each
group is a numbered directory of `.patch` files, applied in order; several
groups patch the same file (`ServerLevel`, `Level`, `Entity`). The design
they implement is `docs/design/barrier-tick-model.md`.

| Group                  | Scope                                                                                                  |
|------------------------|--------------------------------------------------------------------------------------------------------|
| `01-ownership/`        | Positional ownership guards at the mutation sites (`Level.setBlock`, `scheduleTick`, `addFreshEntity`, `Entity.remove`, `BlockEntity.setChanged`, ...). |
| `02-region-tick/`      | Per-region scheduled ticks, block events, entities and block entities; per-thread random, neighbour updater and profiler; region gates in `ServerLevel.tick`; entity activation range (`tickNonPassenger`) and the push cap (`LivingEntity.pushEntities`); `MinecraftServer` passes `haveTime` to the coordinator. |
| `03-world-data/`       | Empty. Per-region tick state lives in `LevelTicks`' per-chunk containers and `ServerLevel`'s per-region block-event queues (02). |
| `04-chunk-system/`     | `ServerChunkCache.getChunk`/`getChunkNow`: region workers read loaded chunks directly and hand real loads to the server thread. |
| `05-entity-migration/` | Cross-region teleports and player dimension changes deferred to the server thread; leaf locks on entity storage, the entity tick list and the tracker map. (Named for the M4 design it replaced.) |
| `06-networking/`       | Empty. Packets are handled on the server thread, between barriers, as in Vanilla. |
| `07-persistence/`      | Empty. Chunks are saved by Vanilla's own `ChunkMap` save path on the server thread. |
| `08-globals/`          | Command and function execution started on a region worker is deferred to the server thread; `Scoreboard` synchronisation. |
| `09-events/`           | NeoForge `EVENT_BUS` replaced by MultiForge's dispatch-domain routing bus (committed `net/neoforged` tree). |

Empty groups keep a `.gitkeep` so the numbering stays stable.

## Building

The patches apply to the flat NeoForge tree committed at
`upstream/neoforge-1.21.1/` (NeoForge 21.1.251; see
`docs/compatibility.md` §5). From the repository root:

```
./gradlew :multiforge-api:publishToMavenLocal :multiforge-runtime:publishToMavenLocal
cd upstream/neoforge-1.21.1
./gradlew setup                    # decompile + NeoForge patches into projects/neoforge/src/main/java
./gradlew :neoforge:compileJava    # runs :neoforge:applyMultiforgePatches first
./gradlew :tests:runGameTestServer # NeoForge's GameTests + MultiForge's region-tick fixtures
./gradlew :neoforge:installerJar   # projects/neoforge/build/libs/neoforge-<v>-installer.jar
```

Run `setup` and the later tasks as separate invocations. The applier
(`projects/neoforge/multiforge-patches.gradle`) is idempotent: it keeps a
pristine copy of every patched file under
`projects/neoforge/build/multiforge-pristine/`, restores it before
applying, and restores every file if a patch fails, so it can run any
number of times.

## Editing a patch

Several groups touch the same files, so hand-editing a `.patch` means
keeping every later group's context valid. `scripts/mf-patches.py` turns the
patch set into a git history instead (one commit per group on a pristine
base) in `build/mf-patches/work`:

```
scripts/mf-patches.py init                 # after setup + applyMultiforgePatches
$EDITOR build/mf-patches/work/net/minecraft/...
scripts/mf-patches.py fixup 02-region-tick net/minecraft/server/level/ServerLevel.java
scripts/mf-patches.py export               # rewrite multiforge-patches/*
scripts/mf-patches.py add net/minecraft/...  # start tracking a file no patch touched yet
```

In the PR description, name the concurrency contract the patch defends.

### Keep Vanilla's members where mixins expect them

Mods' mixins inject into Vanilla methods by name, including the synthetic
methods javac generates for lambdas (`lambda$tick$2`), which are numbered in
source order across the whole class. So in a patched class:

- **Add no lambdas.** A new lambda renumbers every Vanilla lambda after it.
  Put the lambdas a patch needs in a nested `MfLambdas` class at the end of
  the file (`ServerLevel.MfLambdas.addEntity(this, entity)`), or use a method
  reference to a named method, which generates no synthetic method.
- **Leave Vanilla bodies in their methods.** Wrap a body in place (a
  `synchronized` block, an early return) rather than moving it to a new
  method; a moved body takes its lambdas with it, under a new name.
- **Don't change what a Vanilla lambda captures**; that changes its
  descriptor.

`./gradlew :neoforge:checkMixinTargets` (in `upstream/neoforge-1.21.1`, run by
CI) compiles the pristine copy of every patched file and fails if any method it
declares, lambdas included, is missing from MultiForge's class or has a
different descriptor. Lithium and Ad Astra (ATM10) both failed to load before
this rule existed.

## Upstream drift

Moving to a newer NeoForge is described in `docs/compatibility.md` §5:
merge the tree, rebase the work repo's group commits onto the new pristine
sources, export, and re-run the GameTests and the mod-sample boot.
