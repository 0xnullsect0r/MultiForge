# MultiForge

**MultiForge is a free-software, Folia-style regionized multithreaded drop-in replacement for the NeoForge dedicated Minecraft server, released under the GNU General Public License v3.0.**

Swap your `neoforge-<version>-server.jar` for MultiForge's. Your existing world, mods, configs, and launch command keep working. Under the hood, MultiForge partitions the world into ownership regions that tick in parallel on a worker pool sized by `cores × threads-per-core`, with automatic reroute-and-warn for mods that assume single-thread access.

---

## Status

Pre-release, based on NeoForge **21.1.251** (Minecraft 1.21.1). A MultiForge server boots real modpacks, ticks its regions in parallel, and passes:

- every NeoForge GameTest plus MultiForge's own region-tick tests;
- a **vanilla-parity gate**: one fixed-seed world ticked on stock NeoForge and on MultiForge (1 and 4 workers) comes out identical, chunk for chunk;
- live scenarios against stock NeoForge (cross-region teleports, a raid, the dragon fight, fixture mods that write across regions or assume a single thread);
- a strict-mode swarm of real protocol clients with zero ownership violations.

Numbers, and the runs that still need bigger hardware, are in [`docs/verification/README.md`](docs/verification/README.md). The design is [`docs/design/barrier-tick-model.md`](docs/design/barrier-tick-model.md); the history and roadmap are in [`docs/blueprint.md`](docs/blueprint.md).

---

## Install

All under GPL-3 — no token, no activation, no phone-home. Full details in [docs/install.md](docs/install.md).

### Install or update in one line

From the server directory (stop the server first; needs JDK 21):

```
curl -fsSL https://github.com/0xnullsect0r/MultiForge/releases/latest/download/multiforge-update.sh | sh
java -Xms4G -Xmx20G -jar server.jar nogui
```

Run it again for each new release. `server.jar` is replaced every time and always launches the installed version; `config/` (including `multiforge-server.toml`), `mods/`, the world and `server.properties` are never touched; the previous version is kept for `... | sh -s -- --rollback`. See [docs/install.md § Updating](docs/install.md#updating).

The three manual methods:

### 1. Fresh installer JAR (bare-metal / systemd)

```
curl -LO https://github.com/0xnullsect0r/MultiForge/releases/latest/download/multiforge-installer.jar
mkdir my-server && cd my-server
java -jar ../multiforge-installer.jar --installServer .
echo "eula=true" > eula.txt
./run.sh
```

### 2. Drop-in replacement ZIP (overlay an existing NeoForge 1.21.1 server)

Your world, mods, configs, and `server.properties` stay in place. Requires Minecraft NeoForge 1.21.1.

```
# Stop your existing server; back up world/ + mods/ + config/ first.
cd /path/to/your/server
curl -LO https://github.com/0xnullsect0r/MultiForge/releases/latest/download/multiforge-replacement.zip
unzip multiforge-replacement.zip
chmod +x install-multiforge.sh
./install-multiforge.sh          # needs JDK 21; refuses on anything else
./run.sh
```

The converter backs up your launcher, runs the MultiForge installer against the
directory, and leaves a `run.sh` that preflights the JDK. It needs internet on
first run — the Minecraft server jar comes from Mojang and the patches are
applied locally, since nothing derived from that jar may be redistributed.

Rollback is documented in [docs/install.md § Rolling back](docs/install.md#rolling-back) — MultiForge saves the world through Vanilla's own code and adds nothing to it, so the world stays readable by upstream NeoForge; its settings live in `config/multiforge-*.toml`.

### 3. Pelican Panel / Pterodactyl egg

Panel hosts (Pelican Panel, Pterodactyl, forks) import `pelican-egg.json` from the [latest release](https://github.com/0xnullsect0r/MultiForge/releases/latest) and create servers via their normal UI. See [docs/install.md § Method 3](docs/install.md#method-3--pelican-panel--pterodactyl-egg).

### In-game commands (op-only, once running)

```
/multiforge config cores 8               # resize the worker pool, live
/multiforge region size 16               # region section size in chunks, live
/multiforge region list                  # live regions per world
/multiforge tickstats                    # mean/p50/p95/p99/max MSPT, 10-minute TPS
/multiforge probes event.dispatch        # event-routing counters
/multiforge help                         # everything else
```

---

## Design

MultiForge is inspired by [Folia](https://github.com/PaperMC/Folia) but re-implements Folia's design against Vanilla Minecraft + NeoForge patches rather than porting Folia's Paper patch set. Key ideas:

- **Regions own chunks.** Loaded chunks group into regions (connected 16×16-chunk sections, so separate player bases are separate regions). Each tick, every region runs its chunks' random ticks and spawning, scheduled ticks, block events, entities and block entities on a worker, in parallel.
- **Vanilla stays in charge of the rest.** The server thread runs Vanilla's loop — chunk loading and saving, lighting, weather, time, raids, the dragon fight, commands — and waits at a barrier while the regions tick. Vanilla's own code runs unchanged there, which keeps mixins that target it working.
- **Cross-region access is automatic.** A mod call that reaches into another region is rerouted to the owner, still returning what Vanilla would, with a rate-limited warning. No mod needs to opt in.
- **Mods that assume one thread keep working.** Event listeners of mods classified `legacy` (per mod, in `config/multiforge-mods.toml` or the mod's own metadata) run one at a time on the server thread.
- **A companion client mod** visualizes region boundaries, MSPT, and live cross-region hops.

Read the full design in [`docs/blueprint.md`](docs/blueprint.md).

---

## Repository layout

```
multiforge/
├── buildSrc/                Gradle convention plugins
├── multiforge-api/          Public API surface (net.multiforge.api.*)
├── multiforge-runtime/      Region manager, schedulers, mailboxes, diagnostics
├── multiforge-scanner/      ASM-based mod-safety scanner (12 rules)
├── multiforge-patches/      Patches applied to vendored NeoForge 1.21.1
├── multiforge-installer/    Repackages patched NeoForge + runtime
├── multiforge-client/       Client-side debug mod (F3 overlay, region renderer)
├── multiforge-bench/        Parity gate, scenarios, protocol-bot swarm, TPS benches
├── multiforge-testmods/     Fixture mods for the scenarios and the scanner corpus
├── docs/                    Design docs
└── upstream/neoforge-1.21.1 Vendored NeoForge source (patched)
```

---

## Building from source

```
git clone https://github.com/0xnullsect0r/MultiForge.git
cd MultiForge
./gradlew build                                   # outer modules
./gradlew :multiforge-api:publishToMavenLocal :multiforge-runtime:publishToMavenLocal
cd upstream/neoforge-1.21.1
./gradlew setup                                   # decompile + NeoForge patches
./gradlew :neoforge:installerJar                  # MultiForge patches apply automatically
ls projects/neoforge/build/libs/*-installer.jar
```

Requires JDK 21 and ~20 GB free disk for the NeoForge workspace. `./gradlew :tests:runGameTestServer` runs the GameTests; the live checks are in [`docs/verification/README.md`](docs/verification/README.md).

---

## License

MultiForge is licensed under the [GNU General Public License v3.0](LICENSE) — see [`LICENSE`](LICENSE) for the full text.

You are free to run, study, share, and modify this software. If you distribute a modified version, you must do so under the same license and provide the source. This applies to the entire combined work when linking against MultiForge's runtime — mods that link against MultiForge's runtime API are subject to GPL-3's copyleft.

The vendored NeoForge submodule under `upstream/neoforge-1.21.1/` retains its own LGPL-2.1 license; only the MultiForge additions (`multiforge-*/` modules, `multiforge-patches/`, and the fork-side glue under `upstream/neoforge-1.21.1/src/main/java/net/multiforge/`) are GPL-3.

---

## Support

- Issues: https://github.com/0xnullsect0r/MultiForge/issues
- Security disclosures: file a private security advisory via the GitHub UI.
