# Installing MultiForge

MultiForge ships two install artifacts, each suited to a different starting point.

| Method | Best for | Starts from | Migration effort |
|---|---|---|---|
| **[Fresh installer JAR](#method-1--fresh-installer-jar)** | New MultiForge servers on bare-metal Linux/Windows/macOS, systemd deployments | Empty directory | ~10 min |
| **[Drop-in replacement ZIP](#method-2--drop-in-replacement-zip)** | Existing NeoForge 1.21.1 servers with an already-loved world, mods, configs | Working NeoForge server | ~5 min (overlay) + your usual restart |

Both land the same runtime + patched NeoForge fork. Post-install steps (EULA, `multiforge-server.toml`, mods, world) are the same regardless of how you installed.

**Requirements** — Java 21, 8 GB RAM per typical server (adjust via `-Xmx`), MC 1.21.1 server directory shape (`world/`, `mods/`, `config/`, `eula.txt`).

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
   java -jar ../multiforge-installer.jar install --install-dir .
   ```

   This drops:

   ```
   my-mf-server/
   ├── libraries/multiforge/
   │   └── multiforge-runtime.jar   # the runtime library the launcher classpaths
   ├── run.sh                        # Linux/macOS launcher
   ├── run.bat                       # Windows launcher
   ├── config/
   │   └── multiforge-server.toml    # default MultiForge config (edit cores/threads for your box)
   └── eula.txt                      # eula=false — you must set eula=true before boot
   ```

3. **Accept the EULA:**

   ```
   sed -i 's/eula=false/eula=true/' eula.txt
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

```
java -jar multiforge-installer.jar <command>

Commands:
  install [--install-dir DIR]     Lay out a fresh MultiForge server in DIR (default: cwd)
  build-zip --out ZIP             Write the drop-in replacement archive (see Method 2)
  version                         Print the installer version
  help                            This screen
```

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

Overlay MultiForge onto an existing NeoForge 1.21.1 server directory without touching your world, mods, or configs. Best for servers already running vanilla NeoForge that want to try MultiForge without rebuilding from scratch.

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

3. **Download the drop-in ZIP** from the [latest release](https://github.com/0xnullsect0r/MultiForge/releases/latest):

   ```
   curl -LO https://github.com/0xnullsect0r/MultiForge/releases/latest/download/multiforge-replacement.zip
   ```

   Or build it yourself from the installer JAR (Method 1's `build-zip` subcommand):

   ```
   java -jar multiforge-installer.jar build-zip --out multiforge-replacement.zip
   ```

4. **Overlay onto your server directory:**

   ```
   unzip multiforge-replacement.zip
   ```

   Adds:

   ```
   libraries/multiforge/multiforge-runtime.jar
   run.multiforge.sh                             # rename to run.sh after backing up yours
   run.multiforge.bat                            # rename to run.bat after backing up yours
   config/multiforge-server.toml.example         # rename to .toml
   README-MULTIFORGE.txt
   ```

   Nothing under `world/`, `mods/`, `config/` (other than the new example file), `server.properties`, or `eula.txt` is touched.

5. **Swap the launcher:**

   ```
   mv run.sh run.neoforge.sh.bak                 # keep the old one aside
   mv run.multiforge.sh run.sh
   chmod +x run.sh
   ```

   (On Windows: `run.bat` / `run.neoforge.bat.bak` / `run.multiforge.bat`.)

6. **Configure MultiForge** (default is 8 cores × 2 threads-per-core; adjust to your box):

   ```
   mv config/multiforge-server.toml.example config/multiforge-server.toml
   $EDITOR config/multiforge-server.toml
   ```

7. **Start the server:**

   ```
   ./run.sh
   ```

### Rolling back

MultiForge writes only to:
- `libraries/multiforge/*.jar`
- `world/multiforge/` (per-region journal WAL, autosave metadata)
- `config/multiforge-server.toml`
- `logs/multiforge-*.log`

Your Vanilla world files, mod configs, and NeoForge configs are untouched — MultiForge's world data is a purely additive `world/multiforge/` subdirectory. To roll back:

```
./run.sh                            # if running, /stop
mv run.sh run.multiforge.sh.bak
mv run.neoforge.sh.bak run.sh
rm -rf libraries/multiforge/ world/multiforge/ config/multiforge-server.toml
./run.sh                            # back on stock NeoForge
```

Your existing `world/` remains byte-compatible with upstream NeoForge (this is a MultiForge invariant — see [docs/design/entity-migration.md](design/entity-migration.md) and the M9 vanilla-parity verdict at [docs/verification/m9/](verification/m9/)).

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

For deep observability (region borders, MSPT heatmap, live pin selection) install the [MultiForge client debug mod](../multiforge-client/) on your Minecraft client — connects automatically to any MultiForge server.

---

## Troubleshooting

**Server won't boot: `error: cannot find symbol` / `NoClassDefFoundError`.** The runtime jar didn't land on the classpath. Verify `libraries/multiforge/multiforge-runtime.jar` exists and is non-empty (`ls -la libraries/multiforge/`).

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
