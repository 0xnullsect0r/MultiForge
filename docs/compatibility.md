# Mod and modpack compatibility

What runs on MultiForge today, what does not, and why.

---

## 1. The short version

MultiForge is a fork of **NeoForge 21.1.251**, the latest 1.21.1 release (NeoForge's `1.21.1` branch at `6c69a6559`). It reports `21.1.251-multiforge-<version>` as its `neoforge` version, and every mod's `neoforge` dependency range is checked against that.

Through v1.5.x the fork was based on 21.1.1, the first 1.21.1 release, and refused any mod needing a newer NeoForge — 117 of ATM10's 192 mods. The rebase removed that limit: every 1.21.1 mod requirement seen so far (ATM10's highest is 21.1.233) is below the base.

MultiForge keeps Vanilla's and NeoForge's methods intact, so mixins into them apply exactly as on stock NeoForge. The one difference is *where* some per-chunk work runs; see [§4](#4-known-errors-and-what-they-mean) under *A mixin applies but never fires*.

---

## 2. Modpack status

| Pack | Status | Detail |
|---|---|---|
| **Mod sample** (17 jars, below) | ✅ Boots, ticks at 20 TPS | Same result as stock NeoForge 21.1.251 on the same jars. |
| **All the Mods 10** | ⏳ Not re-run since the rebase | The v1.5.0 boot failed only on dependency ranges (117 refused). All 192 declared ranges are satisfied by 21.1.251. A full boot needs the pack's server files, which this project's CI cannot download (CurseForge blocks automated access); run it with `./gradlew :multiforge-bench:atm10 -Pmodpack=<zip>`. |
| **Create-based packs** | ✅ Create loads | `create` 6.0.10 (needs 21.1.219) is in the sample below. |
| **Vanilla + NeoForge, no mods** | ✅ Boots | Installer-built server reaches `Done` and ticks at 20 TPS. |

### 2.1 Verified mod sample

Every mod from the pre-rebase sample, latest NeoForge 1.21.1 release from Modrinth, plus the libraries they require. Installed with the fork's own installer (`:neoforge:installerJar`), booted on a fresh world (seed 12345), left running 2.5 minutes, then gametime sampled 20 s apart:

| Mod | Requires | Result |
|---|---|---|
| AppleSkin 3.0.9 | `[21.0.0-beta,)` | ✅ loads |
| Architectury API 13.0.11 | `[21.0.110-beta,)` | ✅ loads |
| Balm 21.0.66 | `[21.0.82-beta,)` | ✅ loads |
| Citadel 2.7.1 | `[4,)` | ✅ loads, mixins apply |
| Cloth Config API 15.0.140 | `[21.0.110-beta,)` | ✅ loads |
| Corail Tombstone 9.5.6 | `[21.0.0-beta,)` | ✅ loads |
| Create 6.0.10 | `[21.1.219,)` | ✅ loads |
| Curios 9.5.1 | `[21.1.60,)` | ✅ loads |
| FastWorkbench 9.1.3 | `[21.1.187,)` | ✅ loads |
| GeckoLib 4.9.3 | `[21.1.150,)` | ✅ loads |
| Jade 15.10.6 | `[21.0.143,)` | ✅ loads |
| JourneyMap 6.0.9 | `[1.0.0,)` | ✅ loads |
| Moonlight 3.6.9 | none declared | ✅ loads |
| Placebo 9.9.2 | `[21.1.187,)` | ✅ loads |
| Sophisticated Core 1.5.1 | `[21.1.229,)` | ✅ loads |
| Supplementaries 3.9.9 | `[21.1.247,]` | ✅ loads |
| Waystones 21.1.46 | `[21-beta,)` | ✅ loads |

| Server | `Done` | Gametime over 20 s | Injection failures |
|---|---|---|---|
| MultiForge `21.1.251-multiforge-1.5.1` | ✅ 8.8 s | 400 ticks (20 TPS) | 0 |
| Stock NeoForge `21.1.251` | ✅ 9.4 s | 400 ticks (20 TPS) | 0 |

Before the rebase, 6 of these were refused and Citadel's `ServerLevelMixin` failed with `Scanned 0 target(s)` because `08-globals` had moved `tickTime`'s body into another method. Both problems are gone.

### 2.2 Parity against stock NeoForge

To separate "MultiForge broke it" from "this mod is broken", check the same mods on stock NeoForge of the same version:

```bash
curl -sfL -o nf.jar https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.251/neoforge-21.1.251-installer.jar
java -jar nf.jar --installServer stock-control
# same mods/, same JDK 21, then compare
```

### 2.3 Checking your own mods before you boot

`scripts/check-mod-compat.py` reports which jars a MultiForge server can load, so you find out in a second rather than after a boot ending in dependency failures:

```
$ scripts/check-mod-compat.py mods/*.jar
Loadable on 21.1.251 (17):
  FastWorkbench-1.21.1-9.1.3.jar         [21.1.187,)
  ...
17 loadable, 0 refused, 0 unreadable.
```

**Do not do this with `grep`.** A `neoforge.mods.toml` carries a dependency block *per mod*, and one jar often bundles several via jarjar — grepping the first `modId = "neoforge"` block reports one mod's requirement and silently misses the rest. The script walks every nested toml and every dependency block and reports the highest floor.

It does not check dependencies *between* mods. A jar it lists as loadable can still fail because a sibling it needs is absent — `waystones` needs `balm`, `fastbench` needs `placebo`.

---

## 3. Mods that run their own threads

MultiForge ticks regions in parallel (see `docs/design/barrier-tick-model.md`). A mod that mutates the world from its *own* thread, or reaches into a chunk another region owns, has the mutation rerouted to the owning region with a rate-limited warning — never refused. `/multiforge warn list` shows what was rerouted and from where; `mode = "strict"` in `config/multiforge-server.toml` turns the same events into errors for debugging.

### 3.1 Entity activation range and the push cap (behaviour change)

Two settings in `[entities]` of `config/multiforge-server.toml` change Vanilla behaviour on purpose, to keep large mob crowds from dominating a region's tick. Both are **on by default**, both are off in `mode = "off"`, and the vanilla-parity gate (`:multiforge-bench:determinism`) runs with both off.

**Activation range** (`activation = true`). A mob further than its category's range from every player of its level (a square of that half-width around the player, 256 blocks vertically; spectators do not count) is *inactive*: it runs its full tick only once every `wakeInterval` ticks (20; staggered by entity id), and on the other ticks only ages — `tickCount`, `noActionTime` and a baby's growth advance, so it despawns and grows up at Vanilla's pace. Its despawn check still runs every tick. Ranges, in blocks:

| Key | Default | Mobs |
|---|---|---|
| `monsterRange` | 32 | monsters |
| `animalRange` | 32 | animals and every other creature |
| `villagerRange` | 32 | villagers, wandering traders |
| `flyingRange` | 32 | flying monsters (ghasts, phantoms) |
| `raiderRange` | 48 | raiders (pillagers, vindicators, evokers, witches, ravagers) |
| `waterRange` | 16 | squid, fish, dolphins, axolotls |
| `ambientRange` | 16 | bats |

A range of `0` turns throttling off for that category. A region whose last tick took more than 40 ms doubles the wake interval of its inactive mobs (80 ms: doubles again), up to 80 ticks; mobs near players are never slowed.

These always tick, near a player or not:

- anything that is not a mob — items, XP orbs, projectiles, minecarts, boats, armor stands, Create contraptions and other mod entities that do not extend `Mob`;
- mobs riding or ridden, leashed, with an attack target, hurt in the last 100 ticks, in love, or less than 20 ticks old;
- bosses (`#c:bosses`), multipart mobs, and mobs whose `isAlwaysTicking()` is true;
- farm cases: a land mob with AI that is falling (drop chutes) or in water (water streams);
- entity types in the `#multiforge:activation_exempt` tag (ships with `#c:bosses`, the warden and the elder guardian; a datapack can add to it) or in `activationExempt` (ids like `"mymod:golem"` or tags like `"#mymod:machines"`).

What this changes: far from players, mob AI, pathfinding, breeding cooldowns, villager work and trading restock, and mob-driven farms not covered above run at a twentieth of the speed. `EntityTickEvent.Pre/Post` fire only on full ticks. If a farm or a mod depends on distant mobs acting normally, add the type to `activationExempt`, raise the range, or set `activation = false` (also `-Dmultiforge.entities.activation=false`). `/multiforge probes` counts inactive ticks (`entity.activation.skipped`).

**Push cap** (`maxEntityCollisions = 8`). A living entity pushes at most 8 of the entities overlapping it each tick; Vanilla pushes all of them, which is quadratic in a crowd. Entity cramming is unchanged: the cramming check still counts every overlapping entity (`maxEntityCramming`), with Vanilla's random roll. `0` restores Vanilla's pushing (also `-Dmultiforge.entities.maxEntityCollisions=0`).

Every key can be overridden with `-Dmultiforge.entities.<key>=<value>`.

---

## 4. Known errors and what they mean

### A mixin applies but never fires

When regions are active, some per-chunk work that Vanilla runs inside one big level-wide loop runs per region instead:

| Vanilla loop | Where it runs on MultiForge |
|---|---|
| `ServerLevel.tick`'s entity loop (`entityTickList.forEach(...)`) | `ServerLevel.mfTickEntitiesForRegion`, per region: the region's share of `entityTickList`, in list order, through the same `tickNonPassenger` |
| `Level.tickBlockEntities`' ticker loop | the region's block-entity phase — it calls the same `TickingBlockEntity.tick` |
| `LevelTicks.tick` for block and fluid ticks | `LevelTicks.mfTickRegion`, per region — same collect-then-run order |
| `ServerLevel.runBlockEvents` | per region, right after its scheduled ticks |

A mixin that targets the *per-item* method (`tickNonPassenger`, `Entity.tick`, a block entity's `tick`, `Block.tick`) fires as on stock. One that targets an instruction in the *loop itself* applies cleanly but only sees the work still done on the server thread. If a mod depends on that, run with `mode = "off"` to confirm, and report it with the mixin name.

### `Mod X requires neoforge <n> or above / Currently, neoforge is 21.1.251-multiforge-…`

The mod needs a NeoForge newer than 21.1.251, i.e. newer than any 1.21.1 release at the time of the rebase. Stock NeoForge 21.1.251 refuses it too. Rebase MultiForge onto the newer release (§5).

### `Currently, neoforge is 1.21.1-v1.5.0.0-beta`

You are on **v1.5.0 or earlier**, which reported a version string that is not a NeoForge version at all — Maven placed it below every possible floor, so *every* mod was refused, including ones needing only `[21.1.0,)`. Upgrade to v1.5.1 or newer.

### `Unsupported class file major version 70` (or 69, 68, 67, 66) at mod-scan

Wrong JDK. NeoForge 1.21.1 needs **JDK 21 exactly**; SpongeMixin, bundled by most kitchen-sink packs, cannot parse Java 22+ bytecode. 70 = JDK 26, 69 = 25, 68 = 24, 67 = 23, 66 = 22. Install Temurin 21 and set `JAVA_HOME`. From v1.5.0 the launcher and converter both preflight this and refuse with the detected version named.

### `bind(..) failed: Address already in use` → endless restart loop

Not MultiForge. A previous server is still holding port 25565 — commonly a leftover whose parent script was killed but whose `java` child survived. Its command line is `java @user_jvm_args.txt @libraries/...` with no path in it, so `pkill -f "$PWD"` does not match it. Find it with `ss -ltnp | grep 25565` and kill it, or `pkill -f 'unix_args.txt'`.

### `Could not find or load main class net.multiforge.runtime.bootstrap.Main`

You have a drop-in replacement ZIP from **v1.4.1 or earlier**. That archive shipped a launcher for a class that never existed. Get v1.5.0+ and use `install-multiforge.sh`. See [`install.md` § Method 2](install.md#method-2--drop-in-replacement-zip).

### `NoSuchMethodError` on a NeoForge class at mod construction

The mod calls a NeoForge API that does not exist in 21.1.251. It slipped past the dependency check because the mod either declares no `neoforge` range or declares one lower than what it actually uses. Identical on stock NeoForge 21.1.251; not a MultiForge issue.

### `Error loading class: net/minecraft/client/...` at mixin apply, on a dedicated server

Harmless noise. Client-only classes are absent on a dedicated server and mixins targeting them are skipped. Present on stock NeoForge too.

### `[mekanism.common.content.miner.MinerRegionCache/]: Failed to load chunk for searcher cache: X, Z`

Logged by Mekanism when a Digital Miner starts a search and a chunk in its
search area comes back empty. With an Anchor upgrade it asks for each chunk
with `ServerChunkCache.getChunkFuture(x, z, FULL, true).get()` and logs when
the result is empty or the future fails; without one it uses `getChunkNow`,
which is `null` for any chunk that is not loaded. Seen on a MultiForge 1.8.0
server on the server thread, where MultiForge does not change either call:
its `getChunk`/`getChunkNow` changes apply only off the server thread, and the
server-thread wait it adds in 1.8.1 only brackets the wait for the watchdog.
The miner treats a missing chunk as empty and searches the rest. If it recurs on the same
coordinates, check whether they are loaded (or reachable by the miner's
anchor) on stock NeoForge.

### `zip END header not found` on a file in `mods/`

That file is not a valid jar. Usually a truncated download or a placeholder. Not MultiForge-specific.

---

## 5. The rebase

`upstream/neoforge-1.21.1` is a flat copy of NeoForge's `1.21.1` branch plus MultiForge's fork-side files. Moving it to a newer NeoForge commit:

1. **Three-way merge the tree.** Clone NeoForge, branch from the current base commit (`6c69a6559`, recorded in `gradle.properties`), replace the tree with `upstream/neoforge-1.21.1`, commit, and `git merge` the newer commit. Only files MultiForge edits can conflict: `gradle.properties`, `build.gradle`, `settings.gradle`, `projects/neoforge/build.gradle`, `tests/build.gradle`, `ServerLifecycleHooks.java`, `NeoForge.java`, `NeoForgeMod.java`. Copy the merged tree back.
2. **Set `neoforge_base_version`** in `upstream/neoforge-1.21.1/gradle.properties` and `neoForgeVersion` in the root `gradle.properties` to the new release. Check that `src/main/java/net/neoforged` matches the published `neoforge-<V>-sources.jar` apart from the three edited files.
3. **Re-fit the MultiForge patches.** Run `./gradlew setup` in the fork. Then, in the `scripts/mf-patches.py` work repo (`build/mf-patches/work`), commit the new pristine sources of every tracked file as a new base. Rebase the group commits onto it with `git rebase --onto <new-base> <old-base> master`, and resolve conflicts group by group. Rename the new base commit to `pristine` and run `scripts/mf-patches.py export`.
4. **Review what upstream changed in the patched files** for new shared state that region workers can reach. The 21.1.251 rebase found one: `ChunkMap.getPlayersWatching` read `entityMap` without its lock.
5. **Verify**: `:neoforge:compileJava immaculateCheck licenseCheck`, `:tests:runGameTestServer`, then an installer-built server with the §2.1 mod sample.

The 21.1.1 → 21.1.251 rebase took one conflict in MultiForge's patch set (`Level.tickBlockEntities`, where upstream added an `onLoad` guard) and three in the tree (the build files and `ServerLifecycleHooks`). NeoForge also moved from NeoGradle to its in-repo NeoDev build between those releases; the fork's build hooks (`multiforge-patches.gradle`, runtime dependency, JarJar embedding) were ported onto it.
