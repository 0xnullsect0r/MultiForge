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
| **Small mods targeting `[21.1.0,)`** | ✅ Expected to work | 75 of ATM10's mods fall in this class. Not yet verified in isolation. |

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

One thing works in our favour: **the NeoForm version is identical between 21.1.1 and 21.1.234** (`1.21.1-20240808.144430`). The decompiled vanilla sources do not move at all, so this is NeoForge-patch and glue work — not a Minecraft mappings migration.

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
