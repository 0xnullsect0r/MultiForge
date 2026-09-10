# Mod and modpack compatibility

What runs on MultiForge today, what does not, and why.

---

## 1. The short version

MultiForge is a fork of **NeoForge 21.1.1** — the first 1.21.1 release, from August 2024. Upstream NeoForge is now at **21.1.234**.

A mod declares the NeoForge version it needs. MultiForge reports `21.1.1-multiforge-<version>`, so any mod requiring more than 21.1.1 is refused at load with:

```
Mod <id> requires neoforge <version> or above
Currently, neoforge is 21.1.1-multiforge-1.5.1
```

There is a second, subtler class of incompatibility unique to MultiForge: mixins that target vanilla methods MultiForge has restructured. See [§4](#4-known-errors-and-what-they-mean) under *Critical injection failure*.

**This is not a bug, and raising the reported version would not fix it.** The APIs those mods call genuinely are not present in this tree. Claiming a higher version would let them load and then fail on `NoSuchMethodError` somewhere in gameplay — a far worse failure than a clean refusal at startup.

The fix is a rebase onto current NeoForge. See [§5](#5-the-rebase).

---

## 2. Modpack status

| Pack | Status | Detail |
|---|---|---|
| **All the Mods 10** (ServerFiles-7.1) | ❌ Does not boot | 192 mods declare a `neoforge` requirement; **75 satisfied, 117 refused**. 84 of the refusals need 21.1.151+. |
| **FTB packs** (1.21.1 line) | ❌ Expected not to boot | Untested, but `ftblibrary`, `ftbquests`, `ftbteams`, `ftbchunks`, `ftbessentials` all appear in ATM10 requiring 21.1.0 — those pass. `ftbxmodcompat` needs 21.1.0, also passes. The blockers in an FTB pack will be its content mods, same as ATM10. |
| **Create-based packs** | ❌ Does not boot | `create` requires 21.1.219. Everything downstream of it (`createaddition` 21.1.219, `create_enchantment_industry`, `create_dragons_plus`) fails with it. |
| **Vanilla + NeoForge, no mods** | ✅ Boots | Verified: converts and reaches the EULA gate, then runs. |
| **Individual mods within the base** | ✅ Verified | See [§2.1](#21-individually-verified-mods). |

### 2.1 Individually verified mods

Booted on a v1.5.1 dedicated server reporting `21.1.1-multiforge-1.5.1`, reaching `Done`:

| Mod | Requires | Result |
|---|---|---|
| AppleSkin | `[21.0.0-beta,)` | ✅ loads |
| Architectury API | `[21.0.110-beta,)` | ✅ loads |
| Citadel | `[4,)` | ✅ loads |
| Cloth Config API | `[21.0.110-beta,)` | ✅ loads |
| Corail Tombstone | `[21.0.0-beta,)` | ✅ loads |
| Jade | `[21.0.143,)` | ✅ loads |
| JourneyMap | `[1.0.0,)` | ✅ loads |
| Waystones | `[21-beta,)` | ✅ range OK — needs `balm` alongside it |
| Curios | `[21.1.60,)` | ❌ refused |
| GeckoLib | `[21.1.150,)` | ❌ refused |
| FastWorkbench (`fastbench`) | `[21.1.187,)` | ❌ refused |
| Create | `[21.1.219,)` | ❌ refused |
| Sophisticated Core | `[21.1.229,)` | ❌ refused |
| Supplementaries | `[21.1.247,]` | ❌ refused |

Of that sample, **8 of 14 load today**; at a rebased 21.1.234, **13 of 14** would.

### 2.2a Parity against stock NeoForge

To separate "MultiForge broke it" from "this mod needs a newer NeoForge", results are checked against a **stock NeoForge 21.1.1** control running the identical mod set.

AppleSkin + Architectury API + Cloth Config + Jade + JourneyMap:

| Server | Result |
|---|---|
| MultiForge `21.1.1-multiforge-1.5.1` | ✅ `Done (0.676s)` |
| Stock NeoForge `21.1.1` | ✅ `Done (2.540s)` |

Same set, same outcome. Two mods excluded from that run fail on **both**: Citadel (see *Critical injection failure* in §4) and Corail Tombstone (mixin into `supportsEnchantment`, absent in 21.1.1). Neither is a MultiForge regression.

Run the control yourself before reporting a compatibility bug:

```bash
curl -sfL -o nf.jar https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.1/neoforge-21.1.1-installer.jar
java -jar nf.jar --installServer stock-control
# same mods/, same JDK 21, then compare
```

The Sophisticated Core result is the *intended* behaviour, not a defect — a clean refusal at load rather than a crash in gameplay.

Note that its Modrinth build requires 21.1.229 while the copy bundled in ATM10 requires only 21.1.0. **A mod's requirement varies by build**, so check the jar you actually have:

```bash
scripts/check-mod-compat.py mods/*.jar
```

### 2.2 Checking your own mods before you boot

`scripts/check-mod-compat.py` reports which jars a MultiForge server can load, so you find out in a second rather than after a ten-minute boot ending in a wall of dependency failures:

```
$ scripts/check-mod-compat.py mods/*.jar
Loadable on 21.1.1 (8):
  appleskin.jar                          [21.0.0-beta,)
  architectury-api.jar                   [21.0.110-beta,)
  ...
Refused — needs a newer NeoForge (6):
  create.jar                             needs 21.1.219      [21.1.219,)
  geckolib.jar                           needs 21.1.150      [21.1.150,)
  ...
```

Pass `--base 21.1.234` to model what a rebase would unlock.

**Do not do this with `grep`.** A `neoforge.mods.toml` carries a dependency block *per mod*, and one jar often bundles several via jarjar — grepping the first `modId = "neoforge"` block reports one mod's requirement and silently misses the rest. That is how `fastworkbench.jar` first appeared unconstrained here when the `fastbench` mod inside it needs 21.1.187. The script walks every nested toml and every dependency block and reports the highest floor.

It does not check dependencies *between* mods. A jar it lists as loadable can still fail because a sibling it needs is absent — `waystones` needs `balm`, `fastbench` needs `placebo`.


Numbers are from a real ATM10 boot on v1.5.0 plus the dependency analysis in [§3](#3-what-atm10-actually-needs).

---

## 3. What ATM10 actually needs

Distribution of the 117 refused mods by the NeoForge version they require:

| Required | Count |
|---|---|
| 21.1.2 – 21.1.50 | 15 |
| 21.1.51 – 21.1.100 | 11 |
| 21.1.101 – 21.1.150 | 7 |
| 21.1.151 – 21.1.200 | 47 |
| 21.1.201+ | 37 |

Highest requirement in the pack: **21.1.233** (`enchdesc`).

The distribution matters: there is no useful intermediate target. Rebasing to 21.1.150 would still leave 84 mods refused. Only ~21.1.234 makes a modern pack whole.

---

## 4. Known errors and what they mean

### `Critical injection failure: … failed injection check, (0/1) succeeded. Scanned 0 target(s)`

A mod's mixin cannot find the code it wants to inject into. **This is the one failure class where MultiForge genuinely differs from stock NeoForge**, and it is worth understanding.

To drive vanilla logic from the regionized scheduler, MultiForge's `08-globals` patches *hollow out* certain vanilla methods: the original body moves into a new `mf…Body()` method, and the original becomes a dispatcher that either delegates to a global-region system or calls the extracted body.

```java
protected void tickTime() {
    if (GlobalSystemsBridge.timeHandled(this)) return;   // handled by the global region
    this.mfTickTimeBody();                                // else: the original body
}

public void mfTickTimeBody() {
    if (this.tickTime) { ... }                            // everything that used to be in tickTime()
}
```

A mixin targeting an instruction *inside* the original method now finds nothing there — it moved. The mixin still applies to stock NeoForge; it finds zero targets on MultiForge.

Vanilla methods currently hollowed out this way, all by `08-globals`:

| Class | Method | Body moved to |
|---|---|---|
| `ServerLevel` | `tickTime` | `mfTickTimeBody` |
| `ServerLevel` | `advanceWeatherCycle` | `mfAdvanceWeatherCycleBody` |
| `MinecraftServer` | time synchronisation | `mfSynchronizeTimeBody` |
| `WorldBorder` | `tick` | `mfTickBody` |
| `Raids` | `tick` | `mfTickBody` |
| `EndDragonFight` | `tick` | `mfTickBody` |
| `Commands` | `performPrefixedCommand` | `mfPerformPrefixedCommandBody` |
| `ServerFunctionManager` | `execute` | `mfExecuteBody` |
| `ServerScoreboard` | `onPlayerRemoved` | `mfOnPlayerRemovedBody` |
| `ServerScoreboard` | `onScoreChanged` | `mfOnScoreChangedBody` |

Mods that mixin into day/night cycle, weather, world border, raids, the dragon fight, command dispatch, functions, or scoreboards are the ones at risk. A mod injecting at the *head* or *return* of these methods usually still works; one targeting a specific call or variable inside the body will not.

Worked example — Citadel 2.7.1's `ServerLevelMixin` injects around the `setDayTime` call, which lives inside `tickTime()`. On MultiForge that call is in `mfTickTimeBody()`, so injection reports `Scanned 0 target(s)`. (Citadel also cannot run on NeoForge 21.1.1 at all — it calls `DeferredRegister.createDataComponents`, which does not exist there — so on stock it gets past mixins and dies at mod construction instead.)

There is no workaround from the server side today. Report it against MultiForge with the mixin name and target class.

### `Mod X requires neoforge <n> or above / Currently, neoforge is 21.1.1-multiforge-…`

Expected. The mod needs an API added after 21.1.1. Nothing to do but remove the mod or wait for the rebase.

### `Currently, neoforge is 1.21.1-v1.5.0.0-beta`

You are on **v1.5.0 or earlier**, which reported a version string that is not a NeoForge version at all — Maven placed it below every possible floor, so *every* mod was refused, including ones needing only `[21.1.0,)`. Upgrade to v1.5.1 or newer.

### `Unsupported class file major version 70` (or 69, 68, 67, 66) at mod-scan

Wrong JDK. NeoForge 1.21.1 needs **JDK 21 exactly**; SpongeMixin, bundled by most kitchen-sink packs, cannot parse Java 22+ bytecode. 70 = JDK 26, 69 = 25, 68 = 24, 67 = 23, 66 = 22. Install Temurin 21 and set `JAVA_HOME`. From v1.5.0 the launcher and converter both preflight this and refuse with the detected version named.

### `bind(..) failed: Address already in use` → endless restart loop

Not MultiForge. A previous server is still holding port 25565 — commonly a leftover whose parent script was killed but whose `java` child survived. Its command line is `java @user_jvm_args.txt @libraries/...` with no path in it, so `pkill -f "$PWD"` does not match it. Find it with `ss -ltnp | grep 25565` and kill it, or `pkill -f 'unix_args.txt'`.

### `Could not find or load main class net.multiforge.runtime.bootstrap.Main`

You have a drop-in replacement ZIP from **v1.4.1 or earlier**. That archive shipped a launcher for a class that never existed. Get v1.5.0+ and use `install-multiforge.sh`. See [`install.md` § Method 2](install.md#method-2--drop-in-replacement-zip).

### `NoSuchMethodError` on a NeoForge class at mod construction

The mod calls a NeoForge API that does not exist in 21.1.1 — e.g. `DeferredRegister.createDataComponents`, added later. It slipped past the dependency check because the mod either declares no `neoforge` range or declares one lower than what it actually uses.

Identical on stock NeoForge 21.1.1. Not a MultiForge issue; the mod needs a newer NeoForge than this fork is based on.

### `Error loading class: net/minecraft/client/...` at mixin apply, on a dedicated server

Harmless noise. Client-only classes are absent on a dedicated server and mixins targeting them are skipped. Present on stock NeoForge too.

### `zip END header not found` on a file in `mods/`

That file is not a valid jar. Usually a truncated download or a placeholder. Not MultiForge-specific.

---

## 5. The rebase

Rebasing onto NeoForge ~21.1.234 is the single change that unblocks modpack support. Measured scope:

| | Count |
|---|---|
| NeoForge source files to replace | 905 |
| NeoForge's own vanilla patches to update | 728 |
| MultiForge vanilla patches to re-apply | 35 |
| Files patched by **both** (conflict surface) | 17 |
| Lines of MultiForge fork-side glue written against 21.1.1 internals | 7,920 |

The 17-file conflict surface is the hard part — `ServerLevel`, `MinecraftServer`, `ChunkMap`, `DistanceManager`, `Entity`, `Level`, `LevelChunk`, `PlayerList`, `Connection` and friends are patched by NeoForge *and* by MultiForge's ownership, region-tick, chunk-system and globals groups.

Two things work in our favour.

**The NeoForm version is identical between 21.1.1 and 21.1.234** (`1.21.1-20240808.144430`). The decompiled vanilla sources do not move at all, so this is NeoForge-patch and glue work, not a Minecraft mappings migration.

**Every input is publicly downloadable.** NeoForge does not tag releases in git, which initially looked like a blocker for obtaining a specific version's patch set. It is not — the published artifacts carry everything:

| Need | Source |
|---|---|
| `src/main/java/net/neoforged/**` (1,069 files) | `neoforge-<v>-sources.jar` |
| `patches/**` (763 vanilla patches) | `neoforge-<v>-userdev.jar` |
| Dependency pins | `neoforge-<v>.pom` |

```bash
V=21.1.234
B=https://maven.neoforged.net/releases/net/neoforged/neoforge/$V/neoforge-$V
curl -sfLO $B-sources.jar    # net/neoforged sources
curl -sfLO $B-userdev.jar    # patches/ + ats/
curl -sfLO $B.pom            # fml, modlauncher, eventbus, … versions
```

So the mechanical part — replacing the NeoForge sources, the vanilla patch set, and the dependency pins — is scriptable. The work that remains is genuine: re-applying MultiForge's 35 patches across the 17-file conflict surface, and fixing the fork-side glue against 233 releases of NeoForge API drift.

---

## 6. How these numbers were established

Not from the declared metadata, which was wrong. `gradle.properties` claimed `neoForgeVersion=21.1.90`; its `coremods` and `mergetool` pins match no published 21.1.x release.

The base version was established by downloading NeoForge's published sources jars and diffing them file-by-file against `upstream/neoforge-1.21.1/src/main/java/net/neoforged`:

| Compared against | Identical (of 905) |
|---|---|
| **21.1.1** | **902** |
| 21.1.9 | 893 |
| 21.1.20 | 881 |
| 21.1.90 | 841 |
| 21.1.234 | 762 |

The three files differing from 21.1.1 are MultiForge's own edits (`NeoForge.java`, `NeoForgeMod.java`, `ServerLifecycleHooks.java`).

The mod-requirement counts come from a real ATM10 boot log, parsing every `requires neoforge <version>` line out of the `ModLoadingException`.

To re-run the analysis after a rebase:

```bash
# base version check
curl -sfL -o nf.jar https://maven.neoforged.net/releases/net/neoforged/neoforge/<V>/neoforge-<V>-sources.jar
mkdir x && (cd x && unzip -q ../nf.jar 'net/neoforged/*')
# then diff x/net/neoforged against upstream/neoforge-1.21.1/src/main/java/net/neoforged

# pack requirement histogram, from a failed boot
grep -oE "requires neoforge 21\.1\.[0-9]+" crash-reports/*.txt \
  | grep -oE "[0-9]+$" | sort -n | uniq -c
```
