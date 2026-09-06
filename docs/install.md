# Installing MultiForge

MultiForge is a **drop-in replacement for the NeoForge dedicated Minecraft
server**. Swap your server jar (or Docker image) for MultiForge's and your
world, mods, configs, and launch command keep working — the difference is
that ticks now run in parallel across regions.

Two supported install paths:

- **[Docker](#docker-install)** — recommended for new installs and any operator
  already running the server in a container. One-line image change from
  `itzg/minecraft-server` compose files.
- **[Manual install](#manual-install)** — for bare-metal installs, systemd
  services, and Windows Server hosts.

Both paths need the same two things:

1. A **MultiForge license token** (Ed25519-signed, purchased from the
   MultiForge website; see [License](#license) below).
2. **Java 21** — MultiForge is compiled for JDK 21. Older JVMs will refuse
   to boot.

---

## Docker install

### Quick start (single container)

```bash
docker run -d --name multiforge \
  -p 25565:25565/tcp -p 25565:25565/udp \
  -e EULA=TRUE \
  -e MEMORY=8G \
  -e MULTIFORGE_LICENSE="paste-your-token-here" \
  -v "$PWD/data:/data" \
  ghcr.io/0xnullsect0r/multiforge-server:1.0.0
```

- `MULTIFORGE_LICENSE` is the full token you got from the purchase site
  (usually of the form `eyJhbGciOi...` — one long line).
- `data/` is a bind-mount for the world, mods, configs, and logs.

### docker-compose (recommended)

Save this as `docker-compose.yml` next to your world:

```yaml
services:
  multiforge:
    image: ghcr.io/0xnullsect0r/multiforge-server:1.0.0
    container_name: multiforge
    restart: unless-stopped
    ports:
      - "25565:25565/tcp"
      - "25565:25565/udp"
    environment:
      EULA: "TRUE"
      MEMORY: "8G"
      MOTD: "A MultiForge server"
      MULTIFORGE_LICENSE: "${MULTIFORGE_LICENSE:?set MULTIFORGE_LICENSE in your environment or .env}"
      MULTIFORGE_MODE: "hybrid"
      MULTIFORGE_CORES: "8"
      MULTIFORGE_THREADS_PER_CORE: "2"
    volumes:
      - ./data:/data
```

Create a sibling `.env` file so the token stays out of your compose file:

```dotenv
MULTIFORGE_LICENSE=eyJhbGciOi...   # the full token, one line, no quotes
```

Then:

```bash
docker compose up -d
docker compose logs -f multiforge   # watch for "Done!" — takes ~30s
```

### Migrating from `itzg/minecraft-server` or vanilla NeoForge Docker

MultiForge honours the same env-var contract used by
`itzg/minecraft-server` (`EULA`, `MEMORY`, `MOTD`, `MODS`, `TZ`), so the
migration is almost always a one-line change:

```diff
 services:
   minecraft:
-    image: itzg/minecraft-server:latest
+    image: ghcr.io/0xnullsect0r/multiforge-server:1.0.0
     environment:
       EULA: "TRUE"
+      MULTIFORGE_LICENSE: "${MULTIFORGE_LICENSE}"
```

Keep your existing `volumes:` and `ports:`. First boot picks up your
world in place — no world conversion.

### Verify the install

```bash
docker exec multiforge mc-send-to-console "/multiforge region list"
```

That should print one line per live region with MSPT stats. If instead
you see `Unknown command`, MultiForge isn't running — check the boot
log for a license error.

---

## Manual install

For bare-metal, Windows Server, or systemd deployments.

### Prerequisites

- **Java 21** (Temurin 21 recommended). Check with `java -version`.
- **~1 GB free disk** for the base install; more for the world.
- **Linux x86_64 or arm64**, **Windows Server 2019+**, or **macOS 12+**.
  (Only Linux is tested against every modpack we ship benchmarks for.)

### Fresh install

1. Download the release artifacts from
   <https://github.com/0xnullsect0r/multiforge-releases/releases/tag/v1.0.0>:
   - `multiforge-installer-1.0.0.jar` — one-shot installer.
   - `multiforge-1.0.0-server.jar` — the server jar (drop-in for
     `neoforge-1.21.1-server.jar`).
   - `multiforge-license-cli-1.0.0.tar` — optional, for verifying tokens
     offline before pasting them.

2. Put them in an empty directory and run the installer:

    ```bash
    mkdir multiforge-server && cd multiforge-server
    java -jar multiforge-installer-1.0.0.jar install --install-dir .
    ```

    The installer writes a `run.sh` (or `run.bat` on Windows), an empty
    `server.properties`, and a `config/multiforge-server.toml` with
    sensible defaults.

    By default the installer **refuses to install a bundled jar whose
    signature it can't verify** (`--require-signed` defaults to `true`):
    a release installer jar that's had its `.sig` stripped, or that
    fails Ed25519 verification against the embedded signing key, aborts
    with nothing written to `libraries/multiforge/`. Official release
    installer jars are always signed, so this should never trigger on a
    genuine download from the releases page — if it does, don't rerun
    with the override below; treat the jar as tampered and re-download
    from the official source.

    Local/CI builds you compiled yourself (`./gradlew build`) don't run
    the release-signing pipeline, so their bundled jars are unsigned.
    For those — **never** for a downloaded release artifact — pass:

    ```bash
    java -jar multiforge-installer-1.0.0.jar install --install-dir . --require-signed=false
    ```

3. Accept the EULA:

    ```bash
    echo "eula=true" > eula.txt
    ```

4. Drop your license token into `license.key` (one line, no
   surrounding whitespace):

    ```bash
    cat > license.key <<'EOF'
    eyJhbGciOi...
    EOF
    chmod 600 license.key   # keep it out of the world-readable set
    ```

5. Add your mods to `mods/` and world to `world/` (or let MultiForge
   generate a fresh one).

6. Start the server:

    ```bash
    ./run.sh
    ```

    Watch for `Done!` in the log — expect ~30s on modern hardware.

### Migrating from an existing NeoForge server

Point-for-point replacement:

1. Stop the running NeoForge server (`/stop`, wait for `Saving...`
   to finish).
2. Back up the whole install directory. Really. It's a two-line command
   away and it means "one command to roll back" if something surprises
   you:

    ```bash
    tar czf multiforge-backup-$(date +%F).tgz world/ mods/ config/ server.properties
    ```

3. Replace the server jar:

    ```bash
    # example — your existing NeoForge jar name will differ
    mv neoforge-1.21.1-server.jar neoforge-1.21.1-server.jar.old
    cp /path/to/multiforge-1.0.0-server.jar .
    ```

4. Edit your existing launcher script (usually `run.sh`, `start.sh`, or
   the ProcessID file `user_jvm_args.txt`) to point at the new jar
   name. If you're using the NeoForge args file, only the `-jar` line
   changes.
5. Add the license one of three ways (checked in order):
   - `MULTIFORGE_LICENSE` env var,
   - `-Dmultiforge.license=<token>` JVM arg, or
   - `license.key` file in the server root.
6. Start the server. Your world, mods, and config load in place.

### systemd service (Linux)

```ini
# /etc/systemd/system/multiforge.service
[Unit]
Description=MultiForge dedicated server
After=network.target

[Service]
Type=simple
User=minecraft
WorkingDirectory=/srv/multiforge
ExecStart=/srv/multiforge/run.sh
Restart=on-failure
RestartSec=15s
KillSignal=SIGTERM
TimeoutStopSec=45s
# The 45s stop timeout gives the RegionShutdownCoordinator a full drain
# window before systemd falls back to SIGKILL.

# Read the license from a protected environment file — never inline it
# in the unit file.
EnvironmentFile=/etc/multiforge/env
# /etc/multiforge/env contains one line:
#   MULTIFORGE_LICENSE=eyJhbGciOi...

[Install]
WantedBy=multi-user.target
```

Then:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now multiforge
sudo journalctl -u multiforge -f
```

### Windows Server

Run `run.bat` from an elevated PowerShell in the install directory.
Set the license in a **user** environment variable rather than a system
variable so it doesn't leak into other services:

```powershell
[Environment]::SetEnvironmentVariable("MULTIFORGE_LICENSE", "eyJhbGciOi...", "User")
```

Then start with `.\run.bat` from the same PowerShell session.

To run as a service, wrap `run.bat` in NSSM
(<https://nssm.cc>) and set the same env var on the service.

---

## License

MultiForge boots gated on an Ed25519-signed license token. The gate
runs before Vanilla init — an invalid or missing token exits with code
78 and `[MultiForge] Invalid or missing license` on stderr.

**Verification is fully offline.** The token is checked against a public
key baked into the server jar. There is **no phone-home**, no
activation server, and nothing to time-limit unless your token has an
`exp` claim.

**Token lookup order** (first hit wins):

1. Env var `MULTIFORGE_LICENSE` (docker-compose friendly)
2. JVM arg `-Dmultiforge.license=<token>`
3. File `license.key` in the server root

**Where to get one:** buy at <https://multiforge.example>. You'll get a
one-line token you can paste directly into any of the three slots
above.

**Safe handling:**

- The token is not a password — losing it doesn't expose your world.
  It is, however, tied to your purchase, so **don't share it publicly**.
- The `license.key` file should be `chmod 600` on Linux — same
  posture as an SSH key.
- **Never commit the token or `license.key` to git.**

**Verify a token offline** (before pasting into production):

```bash
tar xf multiforge-license-cli-1.0.0.tar
./multiforge-license-cli-1.0.0/bin/multiforge-license-cli verify --token "$MULTIFORGE_LICENSE"
```

The CLI prints the decoded payload (customer id, issued-at, features,
expiry) and exits 0 on a valid token, non-zero otherwise.

---

## First-boot smoke test

```bash
docker compose exec multiforge mc-send-to-console "/multiforge region list"
# — or, for a manual install —
# echo "/multiforge region list" > /path/to/server/server.stdin
```

Expected output (formatting varies with region count):

```
region-0  sections=1  mspt=0.4/1.1  owned=0
region-1  sections=4  mspt=8.2/12.4 owned=27
```

If that works, you're good. Add your first player and start watching
the numbers per [`docs/operator-handbook.md`](operator-handbook.md).

---

## Uninstall / rollback

MultiForge writes only to:

- `world/multiforge/` — journal + pins (per-world)
- `config/multiforge-server.toml` — runtime config
- `logs/multiforge-*.log` — its own log lines

To roll back to upstream NeoForge:

1. Stop MultiForge (`/stop`, wait for `STOPPED` in the log).
2. Restore the NeoForge server jar you renamed during install.
3. Delete `world/multiforge/`, `config/multiforge-server.toml`, and
   the `logs/multiforge-*` files. The world itself is byte-compatible
   with upstream NeoForge — the journal is only used for MultiForge's
   own recovery.
4. Start the NeoForge server as normal.

---

## Getting help

- **Docs index:** [`docs/README.md`](README.md)
- **Design blueprint:** [`docs/blueprint.md`](blueprint.md)
- **Runtime concepts:** [regions](regions.md), [chunks](chunks.md),
  [entity migration](migration.md), [globals + network](global-network.md),
  [persistence](persistence.md)
- **Day-to-day operations:** [operator handbook](operator-handbook.md)
- **License payload format:** [license.md](license.md)
- **Issue tracker:** <https://github.com/0xnullsect0r/multiforge/issues>
