# Website install copy — three install paths

Drop-in reference for the MultiForge purchase site's Installation page.
Written to read cold — a customer arriving from checkout can follow it
without touching the docs repo. Paste as-is; the site owns final
formatting.

There are **three ways to install MultiForge**, pick whichever fits
where the customer already runs their server:

1. [Fresh installer JAR](#1-fresh-installer-jar) — like Fabric's
   installer. `java -jar multiforge-installer.jar install`.
2. [Docker Compose](#2-docker-compose) — one image swap from
   `itzg/minecraft-server`.
3. [Replacement ZIP](#3-replacement-zip) — overlay onto an existing
   NeoForge 1.21.1 server directory.

---

## Public download URLs

All three artifacts are attached to every GitHub Release. Two URL
shapes for each — a version-pinned one and a `latest` alias the site
can hard-code without churn:

| Artifact | Version-pinned | Always-latest |
|----------|----------------|---------------|
| Fresh installer JAR | `https://github.com/0xnullsect0r/multiforge-releases/releases/download/v1.0.0/multiforge-installer-1.0.0.jar` | `https://github.com/0xnullsect0r/multiforge-releases/releases/latest/download/multiforge-installer.jar` |
| Replacement ZIP | `https://github.com/0xnullsect0r/multiforge-releases/releases/download/v1.0.0/multiforge-1.0.0-replacement.zip` | `https://github.com/0xnullsect0r/multiforge-releases/releases/latest/download/multiforge-replacement.zip` |
| Operator install bundle (docs + compose + install.sh) | `https://github.com/0xnullsect0r/multiforge-releases/releases/download/v1.0.0/multiforge-1.0.0-install.tar.gz` | — |
| Docker image (GHCR) | `docker pull ghcr.io/0xnullsect0r/multiforge-server:1.0.0` | `docker pull ghcr.io/0xnullsect0r/multiforge-server:latest` |

> **First-time GHCR setup** (one-time, has to be done by the
> repository owner after the first release cuts): open
> <https://github.com/users/0xnullsect0r/packages/container/multiforge-server/settings>,
> scroll to "Danger Zone" → **Change package visibility** → **Public**.
> Until this is toggled the `docker pull` returns `unauthorized`.

---

## 1. Fresh installer JAR

**Best for:** clean box, first-time install, no existing server yet.

### Download

<https://github.com/0xnullsect0r/multiforge-releases/releases/latest/download/multiforge-installer.jar>

### Prerequisites

- **Java 21** (Temurin recommended). Check with `java -version`.
- 200 MB free disk for the base install; more for the world + mods.

### Install

```bash
mkdir multiforge-server && cd multiforge-server
java -jar multiforge-installer.jar install
```

The installer writes into the current directory:

```
libraries/multiforge/multiforge-runtime.jar
libraries/multiforge/multiforge-license.jar
run.sh              # Linux/macOS launcher
run.bat             # Windows launcher
config/multiforge-server.toml
eula.txt            # currently eula=false — edit to accept
license.key         # placeholder; paste your token here
```

### Provide your license one of these three ways

Whichever the installer sees first wins:

1. Environment variable `MULTIFORGE_LICENSE=<token>`
2. JVM arg `-Dmultiforge.license=<token>`
3. Line in `license.key`

Fastest at install time:

```bash
java -jar multiforge-installer.jar install --license "$MULTIFORGE_LICENSE"
```

or from a file:

```bash
java -jar multiforge-installer.jar install --license @/path/to/license.txt
```

Either form writes `license.key` with mode 600.

### Start

```bash
# accept the EULA:
sed -i 's/eula=false/eula=true/' eula.txt

# drop your mods into ./mods and world into ./world (optional — a fresh
# world generates on first boot)

./run.sh        # Linux / macOS
run.bat         # Windows
```

Watch for `Done!` in the log — usually about 30 seconds.

### Installer subcommands

```
java -jar multiforge-installer.jar install    [--install-dir DIR] [--license TOKEN|@FILE]
java -jar multiforge-installer.jar build-zip  --out multiforge-replacement.zip
java -jar multiforge-installer.jar version
java -jar multiforge-installer.jar help
```

---

## 2. Docker Compose

**Best for:** anyone already running the server in a container. One
image swap from `itzg/minecraft-server` and you're done.

### Public image

```
ghcr.io/0xnullsect0r/multiforge-server:1.0.0
```

(also `:1.0` and `:latest`; same digest.)

### `docker-compose.yml`

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
      MULTIFORGE_LICENSE: "${MULTIFORGE_LICENSE:?paste your token in .env}"
      MULTIFORGE_MODE: "hybrid"
      MULTIFORGE_CORES: "8"
      MULTIFORGE_THREADS_PER_CORE: "2"
    volumes:
      - ./data:/data
```

### `.env` (never commit this)

```
MULTIFORGE_LICENSE=eyJhbGciOi...paste the whole token here...
```

Add `.env` to `.gitignore` right now.

### Start

```bash
docker compose up -d
docker compose logs -f multiforge
```

### Migrating from `itzg/minecraft-server` or the NeoForge image

One line + one env var:

```diff
-    image: itzg/minecraft-server:latest
+    image: ghcr.io/0xnullsect0r/multiforge-server:1.0.0
     environment:
       EULA: "TRUE"
+      MULTIFORGE_LICENSE: "${MULTIFORGE_LICENSE}"
```

Keep your existing `volumes:` and `ports:`. First boot picks up your
world in place — no world conversion.

### If `docker pull` says `unauthorized`

The first time the container is published to GHCR it lands **private**.
The repo owner has to flip the package to public one time at
<https://github.com/users/0xnullsect0r/packages/container/multiforge-server/settings>
("Danger Zone" → "Change package visibility" → Public). After that the
pull is anonymous forever.

---

## 3. Replacement ZIP

**Best for:** you already have a running NeoForge 1.21.1 server and
want to keep every existing file (mods, world, configs, launch script)
in place. Just overlay the MultiForge jars on top.

### Download

<https://github.com/0xnullsect0r/multiforge-releases/releases/latest/download/multiforge-replacement.zip>

Contents:

```
libraries/multiforge/multiforge-runtime.jar
libraries/multiforge/multiforge-license.jar
run.multiforge.sh                          # example launcher, not run.sh
run.multiforge.bat                         # same, Windows
config/multiforge-server.toml.example      # example config, not activated
README-MULTIFORGE.txt                      # this same set of steps
```

The `.example` / `.multiforge.*` names are deliberate — the archive
never overwrites your existing `run.sh` or `config/multiforge-server.toml`.
You compare and rename when ready.

### Migration steps

1. Stop the NeoForge server (`/stop`, wait for `Saving...` to finish).
2. Back up your world:
   ```bash
   tar czf multiforge-backup-$(date +%F).tgz world/ mods/ config/
   ```
3. Overlay the archive:
   ```bash
   cd /path/to/your/neoforge/server
   unzip /path/to/multiforge-1.0.0-replacement.zip
   ```
4. Put your license token in `license.key` (one line, `chmod 600`),
   or export `MULTIFORGE_LICENSE`.
5. Rename the launcher script:
   ```bash
   mv run.sh run.neoforge.sh.bak
   mv run.multiforge.sh run.sh
   ```
6. Activate the config:
   ```bash
   mv config/multiforge-server.toml.example config/multiforge-server.toml
   # edit cores / threads-per-core to match your machine
   ```
7. Start:
   ```bash
   ./run.sh
   ```

### Rolling back

MultiForge writes only to `world/multiforge/`,
`config/multiforge-server.toml`, and its own `logs/multiforge-*.log`.
To go back to upstream NeoForge:

```bash
# stop the server, then:
rm -rf world/multiforge config/multiforge-server.toml logs/multiforge-*
mv run.sh run.multiforge.sh.bak
mv run.neoforge.sh.bak run.sh
```

The world itself is byte-compatible with upstream NeoForge — no
conversion required either way.

---

## License notes (applies to all three paths)

- Verification is **fully offline**. The token is checked against a
  public key baked into the server jar. No phone-home.
- The token is tied to your purchase — don't share it publicly.
- Store `license.key` (or your `.env`) with `chmod 600` and keep it
  out of git.
- **Server exits with code 78 and `[MultiForge] Invalid or missing
  license` on stderr** if the token is missing, malformed, or expired.

Three places the server looks for your token, in order:

1. `MULTIFORGE_LICENSE` environment variable
2. `-Dmultiforge.license=<token>` JVM argument
3. `license.key` file in the server root

---

## Support

- Docs: <https://github.com/0xnullsect0r/multiforge/tree/main/docs>
- Issues: <https://github.com/0xnullsect0r/multiforge/issues>
- Email: support@multiforge.example (include your customer id — the
  `sub` field printed by `multiforge-license-cli verify`)
