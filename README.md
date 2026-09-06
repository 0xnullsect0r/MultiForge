# MultiForge

**MultiForge is a free-software, Folia-style regionized multithreaded drop-in replacement for the NeoForge dedicated Minecraft server, released under the GNU General Public License v3.0.**

Swap your `neoforge-<version>-server.jar` for MultiForge's. Your existing world, mods, configs, and launch command keep working. Under the hood, MultiForge partitions the world into ownership regions that tick in parallel on a worker pool sized by `cores × threads-per-core`, with automatic reroute-and-warn for mods that assume single-thread access.

---

## Status

Pre-alpha. In active development. See [`docs/blueprint.md`](docs/blueprint.md) for the design and the roadmap.

---

## Install

Two ways to install, both under GPL-3 — no token, no activation, no phone-home. Full details in [docs/install.md](docs/install.md).

### 1. Fresh installer JAR (bare-metal / systemd)

```
curl -LO https://github.com/0xnullsect0r/MultiForge/releases/latest/download/multiforge-installer.jar
mkdir my-server && cd my-server
java -jar ../multiforge-installer.jar install --install-dir .
sed -i 's/eula=false/eula=true/' eula.txt
./run.sh
```

### 2. Drop-in replacement ZIP (overlay an existing NeoForge 1.21.1 server)

Your world, mods, configs, and `server.properties` stay in place. Requires Minecraft NeoForge 1.21.1.

```
# Stop your existing server; back up world/ + mods/ + config/ first.
curl -LO https://github.com/0xnullsect0r/MultiForge/releases/latest/download/multiforge-replacement.zip
cd /path/to/your/server
unzip /path/to/multiforge-replacement.zip
mv run.sh run.neoforge.sh.bak
mv run.multiforge.sh run.sh && chmod +x run.sh
mv config/multiforge-server.toml.example config/multiforge-server.toml
./run.sh
```

Rollback is documented in [docs/install.md § Rolling back](docs/install.md#rolling-back) — MultiForge's world data is a purely additive `world/multiforge/` subdirectory; your Vanilla world stays byte-compatible with upstream NeoForge.

### In-game commands (op-only, once running)

```
/multiforge config cores 8               # change worker-pool sizing
/multiforge region mode player-only      # switch region partitioning
/multiforge region list                  # see live regions
/multiforge probe tps                    # TPS/MSPT histogram
/multiforge probe event.dispatch         # event-routing counters (M12)
```

---

## Design

MultiForge is inspired by [Folia](https://github.com/PaperMC/Folia) but re-implements Folia's design against Vanilla Minecraft + NeoForge patches rather than porting Folia's Paper patch set. Key ideas:

- **Regions own chunks.** Each region has one owning thread. All mutation of chunks / entities / block entities within a region happens on that thread.
- **Regions form around players.** By default regions grow around clusters of players; hot regions split, cold regions merge.
- **Cross-region access is automatic.** Any unaudited mod call that reaches into another region's data is silently rerouted to the owner thread via a task queue, with a rate-limited warn log. No mod needs to opt in.
- **A dedicated `global region`** handles weather, time, world border, gamerules, ender dragon, wither, raids, scoreboards, and command dispatch.
- **Per-region autosave + write-ahead journal.** No global save-all stall; crashes replay from the journal.
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
├── multiforge-bench/        Headless bot-swarm TPS harness
├── docs/                    Design docs
└── upstream/neoforge-1.21.1 Vendored NeoForge source (patched)
```

---

## Building from source

```
git clone https://github.com/0xnullsect0r/MultiForge.git
cd MultiForge
./gradlew :setup                # vendor NeoForge 1.21.1
./gradlew build                 # build all pure-Java artifacts
```

Requires JDK 21 and ~20 GB free disk for the NeoForge workspace.

---

## License

MultiForge is licensed under the [GNU General Public License v3.0](LICENSE) — see [`LICENSE`](LICENSE) for the full text.

You are free to run, study, share, and modify this software. If you distribute a modified version, you must do so under the same license and provide the source. This applies to the entire combined work when linking against MultiForge's runtime — mods that link against MultiForge's runtime API are subject to GPL-3's copyleft.

The vendored NeoForge submodule under `upstream/neoforge-1.21.1/` retains its own LGPL-2.1 license; only the MultiForge additions (`multiforge-*/` modules, `multiforge-patches/`, and the fork-side glue under `upstream/neoforge-1.21.1/src/main/java/net/multiforge/`) are GPL-3.

---

## Support

- Issues: https://github.com/0xnullsect0r/MultiForge/issues
- Security disclosures: file a private security advisory via the GitHub UI.
