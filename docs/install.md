# Installing MultiForge

MultiForge ships three install methods, each suited to a different starting point.

| Method | Best for | Starts from | Migration effort |
|---|---|---|---|
| **[Fresh installer JAR](#method-1--fresh-installer-jar)** | New MultiForge servers on bare-metal Linux/Windows/macOS, systemd deployments | Empty directory | ~10 min |
| **[Drop-in replacement ZIP](#method-2--drop-in-replacement-zip)** | Existing NeoForge 1.21.1 servers with an already-loved world, mods, configs | Working NeoForge server | ~10 min (converts in place) + your usual restart |
| **[Pelican Panel / Pterodactyl egg](#method-3--pelican-panel--pterodactyl-egg)** | Anyone hosting via a panel — Pelican, Pterodactyl, or any fork | Panel install + egg import | ~2 min (import) + normal panel server-create flow |

All three land the same runtime + patched NeoForge fork. Post-install steps (EULA, `multiforge-server.toml`, mods, world) are the same regardless of how you installed.

**Requirements**

- **JDK 21 exactly.** MultiForge (like NeoForge 1.21.1 itself) does *not* run on JDK 22 or newer. Any 1.21.1 modpack that uses SpongeMixin (ATM10, ATM9, AllTheModsX, most kitchen-sink packs) crashes at mod-scan with `Unsupported class file major version 7X` on JDK 22+ because the bundled mixin transformer's class-file reader only understands Java 21 bytecode. Common trap: `java -version` shows JDK 24/25/26 because you installed the "latest" JDK from your distro. Install Temurin 21 (`brew install temurin@21` / `apt install temurin-21-jdk` / `pacman -S jdk21-temurin`) and either set `JAVA_HOME=/path/to/jdk-21` or invoke the launcher with an explicit path (`JAVA_HOME=/usr/lib/jvm/temurin-21-jdk ./run.sh`). As of v1.3.18 the launcher scripts refuse to run on the wrong JDK with a clear remediation message.
- **8 GB RAM** per typical server (adjust via `-Xmx`).
- **MC 1.21.1 server directory shape**: `world/`, `mods/`, `config/`, `eula.txt`.

**License** — MultiForge is [GPL-3.0-only](../LICENSE). No token, no activation, no phone-home.

---

## Method 1 — Fresh installer JAR

The Fabric-installer-shaped path: run a small JAR that lays out a fresh MultiForge server directory. Best for bare-metal deployments, systemd services, or operators who want direct control over the launch command.

### Steps

1. **Download the installer** from the [latest release](https://github.com/0xnullsect0r/MultiForge/releases/latest):

   ```
   curl -LO https://github.com/0xnullsect0r/MultiForge/releases/latest/download/multiforge-installer.jar
   ```

2. **Run the installer** into a fresh directory:

   ```
   mkdir my-mf-server && cd my-mf-server
   java -jar ../multiforge-installer.jar --installServer .
   ```

   The installer is standard NeoForge-format — `--installServer <dir>` writes a complete server layout. This drops (abridged):

   ```
   my-mf-server/
   ├── libraries/                              # Minecraft server + NeoForge + MultiForge libraries
   │   └── net/neoforged/neoforge/<v>/
   │       └── unix_args.txt                    # NeoForge's classpath / modulepath args
   ├── run.sh                                   # NeoForge launcher (calls `java @user_jvm_args.txt @…/unix_args.txt "$@"`)
   ├── run.bat                                  # Windows launcher
   ├── user_jvm_args.txt                        # edit -Xmx here (defaults to 2G)
   └── server.properties                        # created on first boot
   ```

   MultiForge's own `config/multiforge-server.toml` gets written by the runtime on first boot; edit it after the initial run to tune cores/threads/region-size.

3. **Accept the EULA** (the fork installer doesn't seed `eula.txt` — write it fresh):

   ```
   echo "eula=true" > eula.txt
   ```

4. **Configure** (optional — defaults work):

   ```
   $EDITOR config/multiforge-server.toml     # cores, threads-per-core, region size, autosave, etc.
   ```

5. **Copy your mods and world** (if migrating from a previous NeoForge install):

   ```
   cp -r /path/to/old-server/mods .
   cp -r /path/to/old-server/world .
   ```

6. **Start the server:**

   ```
   ./run.sh              # Linux/macOS
   run.bat               # Windows
   ```

### Installer CLI reference

MultiForge's installer is the standard NeoForge installer, patched to install the MultiForge fork. Full CLI options:

```
java -jar multiforge-installer.jar --installServer <dir>   # write a fresh server layout into <dir>
java -jar multiforge-installer.jar --installClient <dir>   # write a client layout (rarely used for MultiForge — server-side project)
java -jar multiforge-installer.jar --extract <dir>         # extract raw installer resources
java -jar multiforge-installer.jar --help                  # full CLI help
```

The `--installServer` flow needs internet on first run (downloads the Minecraft server jar + a handful of Java libraries from Mojang/NeoForge/Maven Central). Everything is cached under `libraries/`.

### Systemd unit (optional)

```ini
# /etc/systemd/system/multiforge.service
[Unit]
Description=MultiForge Minecraft server
After=network-online.target

[Service]
Type=simple
User=minecraft
WorkingDirectory=/opt/multiforge
ExecStart=/opt/multiforge/run.sh
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```
sudo systemctl daemon-reload
sudo systemctl enable --now multiforge
journalctl -u multiforge -f
```

---

## Method 2 — Drop-in replacement ZIP

Convert an existing NeoForge 1.21.1 server directory to MultiForge in place, without touching your world, mods, or configs. Best for servers already running stock NeoForge.

> **What this archive is.** It carries the MultiForge installer plus a converter script — not a finished server. A completed install is ~180 MB and includes Mojang's `server-1.21.1.jar` along with the patched derivatives built from it, and none of that may be redistributed. So the archive downloads from Mojang and applies MultiForge's patches on your machine, exactly as the NeoForge installer does. Expect ~10 MB down, ~180 MB in `libraries/` after.
>
> Versions before v1.5.0 shipped a ~320 KB archive containing only `multiforge-runtime.jar` and a launcher for `net.multiforge.runtime.bootstrap.Main` — a class that never existed. It failed at startup with `Could not find or load main class`. If you have one of those, discard it.

### Steps

1. **Stop your NeoForge server** cleanly and wait for it to finish saving:

   ```
   # In your server console (or via RCON):
   /save-all flush
   /stop
   ```

2. **Back up your world:**

   ```
   cd /path/to/your/server
   tar czf backup-$(date +%F).tgz world/ mods/ config/ server.properties eula.txt
   ```

3. **Download and unzip into the server directory:**

   ```
   curl -LO https://github.com/0xnullsect0r/MultiForge/releases/latest/download/multiforge-replacement.zip
   unzip multiforge-replacement.zip
   ```

   Adds:

   ```
   multiforge/multiforge-installer.jar     # the MultiForge installer
   install-multiforge.sh                   # Linux/macOS converter
   install-multiforge.bat                  # Windows converter
   config/multiforge-server.toml.example
   README-MULTIFORGE.txt
   ```

4. **Run the converter:**

   ```
   chmod +x install-multiforge.sh
   ./install-multiforge.sh
   ```

   (Windows: `install-multiforge.bat`.)

   It refuses to run on anything but JDK 21, naming the version it found. If your default JDK is wrong, point it at the right one:

   ```
   JAVA_HOME=/usr/lib/jvm/temurin-21-jdk ./install-multiforge.sh
   ```

   What it does, in order: preflights the JDK, backs up `run.sh` / `run.bat` / `user_jvm_args.txt` to `*.pre-multiforge-<timestamp>.bak`, runs the installer against the current directory, rewrites `run.sh` to carry the same JDK-21 preflight, and writes `config/multiforge-server.toml` and `eula.txt` if they are absent.

   Needs internet the first time — it downloads the Minecraft server jar and the NeoForge libraries.

5. **Configure MultiForge** (default is 8 cores × 2 threads-per-core; adjust to your box):

   ```
   $EDITOR config/multiforge-server.toml
   ```

6. **Start the server:**

   ```
   ./run.sh
   ```

### What changed on disk

| Path | Change |
|---|---|
| `libraries/` | NeoForge + MultiForge + Minecraft artifacts added |
| `run.sh`, `run.bat`, `user_jvm_args.txt` | regenerated; originals kept as `*.pre-multiforge-<timestamp>.bak` |
| `config/multiforge-server.toml` | written if absent |
| `eula.txt` | written if absent, as `eula=false` |

Nothing under `world/`, `mods/`, or the rest of `config/` is touched.

### Rolling back

Beyond the launcher and `libraries/`, MultiForge writes only to:
- `world/multiforge/` (per-region journal WAL, autosave metadata)
- `config/multiforge-server.toml`
- `logs/multiforge-*.log`

Your Vanilla world files, mod configs, and NeoForge configs are untouched — MultiForge's world data is a purely additive `world/multiforge/` subdirectory. To roll back:

```
# /stop the server first
mv run.sh.pre-multiforge-*.bak run.sh              # restore the stock launcher
mv user_jvm_args.txt.pre-multiforge-*.bak user_jvm_args.txt
rm -rf world/multiforge/ config/multiforge-server.toml
./run.sh                                           # back on stock NeoForge
```

The MultiForge artifacts left under `libraries/net/neoforged/neoforge/1.21.1-v*/` are inert once the launcher no longer points at them; delete that directory too if you want the space back.

Your existing `world/` remains byte-compatible with upstream NeoForge (this is a MultiForge invariant — see [docs/design/entity-migration.md](design/entity-migration.md) and the M9 vanilla-parity verdict at [docs/verification/m9/](verification/m9/)).

---

## Method 3 — Pelican Panel / Pterodactyl egg

If you host Minecraft servers through a panel (Pelican Panel, Pterodactyl, or any compatible fork), MultiForge ships a ready-to-import egg at [`pelican-egg.json`](https://github.com/0xnullsect0r/MultiForge/blob/main/pelican-egg.json). It's also attached to every GitHub Release as an asset.

### Import steps

1. **Download the egg** — grab `pelican-egg.json` from the [latest release](https://github.com/0xnullsect0r/MultiForge/releases/latest) or the repo root.
2. **Import into your panel:**
   - Pelican Panel: **Admin → Nests → select or create a nest → Import Egg** and upload the JSON.
   - Pterodactyl: same flow (**Nests → Import Egg**).
3. **Create a server** using the newly-imported MultiForge egg. On first boot the panel runs the egg's install script — which downloads the MultiForge fork installer from GitHub Releases and lays out a fresh NeoForge-with-MultiForge server directory.

### Egg variables

| Variable | Default | Meaning |
|---|---|---|
| `MULTIFORGE_VERSION` | `latest` | Which release to install. `latest` resolves via the GitHub API to the latest published release. Otherwise substitute into `DOWNLOAD_URL`'s `{VERSION}` placeholder (e.g. `v1.3.2`). |
| `DOWNLOAD_URL` | GitHub Releases template | Where to fetch the installer from. Only override for a mirror or a custom build. Not user-editable by default. |
| `MC_VERSION` | `1.21.1` | Display-only; used by the panel UI to show which MC version the server targets. |
| `SERVER_JARFILE` | `multiforge.jar` | Fallback jar name for the rare case the installer isn't detected (single runnable jar path). Normal flow doesn't use this. |

The startup command mirrors NeoForge's own `run.sh`:

```
java @user_jvm_args.txt -Xms128M -Xmx{{SERVER_MEMORY}}M -Dterminal.jline=false -Dterminal.ansi=true @unix_args.txt nogui
```

`SERVER_MEMORY` is a Pelican-provided variable derived from the container's memory allocation.

### What the install script does

1. Downloads `multiforge-<v>-installer.jar` from the release.
2. Runs `java -jar multiforge-installer.jar --installServer /mnt/server`.
3. Symlinks `libraries/net/neoforged/neoforge/<v>/unix_args.txt` to `/mnt/server/unix_args.txt` (the startup command's `@unix_args.txt` expects it at top level).
4. Writes `eula=true` to `eula.txt`.
5. Creates `mods/` and `config/` if not present.

The resulting layout is identical to Method 1's fresh-installer flow — MultiForge's own `config/multiforge-server.toml` is written by the installer, and mod installation, world backups, etc. work per your panel's normal UX.

### Requirements

- A panel supporting the Pelican-format egg schema (`PLCN_v1`) — Pelican Panel or Pterodactyl current versions.
- The container running the server needs Java 21 (any of the yolks images `ghcr.io/pterodactyl/yolks:java_21|java_22|java_25`).
- Sufficient memory allocation on the panel (default startup uses `-Xms128M -Xmx{{SERVER_MEMORY}}M` — allocate 4-8 GB for a normal MultiForge server).

### Troubleshooting

- **Install fails at "ERROR: DOWNLOAD_URL is not set":** the panel didn't pass the variable. Check the server's variable overrides in the panel UI.
- **Install downloads but "downloaded file is not a jar":** likely a 404 (the release doesn't have the expected asset for the specified version). Set `MULTIFORGE_VERSION=latest` to bypass; check the release page for the actual asset name.
- **Server boots but crashes at mod-loading:** same as any modded server — check the log for the offending mod's stack trace, remove it from `mods/`.

---

## Verify your install worked

Once the server is up, check the boot log for the MultiForge banner and the runtime version:

```
[main/INFO] [multiforge]: MultiForge 1.3.0 runtime installed
[main/INFO] [multiforge]: Region scheduler: 8 cores × 2 threads/core = 16 workers
[main/INFO] [multiforge]: Global-region tick body wired
[main/INFO] [multiforge]: Ownership enforcer: REROUTE (default)
```

Then, from an op-level in-game console or RCON:

```
/multiforge region list          # see materialized regions
/multiforge probe tps            # verify the TPS histogram is ticking
/multiforge probe event.dispatch  # verify M12 event routing is live
```

For deep observability (region borders, MSPT heatmap, live pin selection) download the [MultiForge client debug mod](https://github.com/0xnullsect0r/MultiForge/releases/latest/download/multiforge-client.jar) and drop it into your Minecraft client's `mods/` folder. It loads on any NeoForge 1.21.1 client and stays inert until you connect to a MultiForge server (which advertises the `multiforge:debug/v1` channel).

---

## Troubleshooting

**"Unsupported class file major version 7X" at mod scan.** Your `java` is a JDK newer than 21 (`70` = JDK 26, `69` = JDK 25, `68` = JDK 24, `67` = JDK 23, `66` = JDK 22). SpongeMixin — bundled by nearly every 1.21.1 mod, including everything in ATM10 / AllTheModsX / most kitchen-sink packs — ships a class-file reader that only understands Java 21 bytecode and rejects anything newer, which crashes mod-loading before MultiForge or NeoForge ever gets a chance to run. Fix: install Temurin 21 (`sudo pacman -S jdk21-temurin` / `apt install temurin-21-jdk` / `brew install temurin@21`), then re-invoke the launcher with an explicit JDK path — for example `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk ./run.sh`. As of v1.3.18 the installer-generated `run.sh` refuses to run on the wrong JDK with this message and a non-zero exit. See Requirements above.

**Mods refuse to load: "Mod X requires neoforge 21.1.NNN or above / Currently, neoforge is 21.1.1-multiforge-…".** MultiForge is forked from NeoForge 21.1.1, so mods needing APIs added after it are refused at load. This is expected and is not fixable by configuration — see [`compatibility.md`](compatibility.md) for which packs this affects and by how much. If instead the line reads `Currently, neoforge is 1.21.1-v…-beta`, you are on v1.5.0 or older, where the reported version was not a NeoForge version at all and *every* mod was refused; upgrade to v1.5.1+.

**Server won't boot: `error: cannot find symbol` / `NoClassDefFoundError`.** The runtime jar didn't land on the classpath. Verify the MultiForge artifacts exist under `libraries/net/neoforged/neoforge/<version>/` and that `run.sh` points at that version's `unix_args.txt`.

**"EULA not accepted" on first boot.** Edit `eula.txt` to `eula=true`.

**"MultiForge cannot start — no region-worker cores available."** Bad `multiforge-server.toml`: `cores` must be ≥ 1. Default is 8 — a value of 0 or a negative number rejects boot.

**Server boots but everything runs single-threaded.** Check the log for `[multiforge]: Region scheduler: N cores × M threads/core = W workers` — if W is 1, your config sets it low. `cores × threads-per-core` should be at most your physical core count.

**"Chunk system port failed — falling back to Vanilla ChunkMap."** MultiForge's M9 chunk-system port didn't initialize. Check earlier log lines for a stack trace, and file an issue at [github.com/0xnullsect0r/MultiForge/issues](https://github.com/0xnullsect0r/MultiForge/issues) with the boot log attached.

**Drop-in method: `world/multiforge/` grows unbounded.** Per-region WAL journal isn't rolling over. Set `journal-max-file-mb = 128` in `config/multiforge-server.toml` (default is 512).

---

## What's next

- **Configure for your workload** — [docs/perf-tuning.md](perf-tuning.md) walks through `cores`/`threads-per-core`/`region mode`/`autosave` tuning.
- **Understand the model** — [docs/blueprint.md](blueprint.md) is the full design; [docs/regions.md](regions.md) explains how regions form and migrate.
- **Port a mod** — [docs/mod-porting.md](mod-porting.md) covers common patterns for mods that assume single-threaded access.
- **Debug violations** — [docs/debugging-violations.md](debugging-violations.md) explains the `ProbeRegistry`/`ViolationLogger` output when the ownership enforcer fires.
