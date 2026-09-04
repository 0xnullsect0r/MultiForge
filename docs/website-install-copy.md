# Website install copy

Drop-in text for the MultiForge purchase site's Installation page.
Written to read cold — a customer arriving from checkout can follow it
without touching the docs repo. Paste as-is; the site owns final
formatting.

---

## Getting started with MultiForge

MultiForge is a drop-in replacement for the NeoForge dedicated
Minecraft server. Your world, your mods, and your existing launch
command all keep working — ticks just run in parallel now.

**You'll need:**

- Your **MultiForge license token** (from the confirmation email
  after purchase — one long line starting with `eyJ...`).
- **Java 21** for a manual install, or **Docker 24+** for the
  container install.
- A machine running Linux, macOS 12+, or Windows Server 2019+.
  x86_64 or arm64.

Pick a path:

- **[Docker install](#docker-install)** — one-line image change from
  `itzg/minecraft-server`. Recommended if you already run a
  containerised server.
- **[Manual install](#manual-install)** — for bare-metal, systemd, and
  Windows.

---

## Docker install

### 1. Save your compose file

Save this as `docker-compose.yml` in an empty directory. The world,
mods, and configs will live next to it in `data/`.

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
      MULTIFORGE_CORES: "8"
      MULTIFORGE_THREADS_PER_CORE: "2"
    volumes:
      - ./data:/data
```

### 2. Save your token to a `.env` file

Same directory, filename `.env`, single line:

```
MULTIFORGE_LICENSE=eyJhbGciOi...paste the whole token here...
```

Never commit `.env` to git. If you use `git`, add `.env` to
`.gitignore` right now.

### 3. Start the server

```bash
docker compose up -d
docker compose logs -f multiforge
```

Watch for `Done!` in the log — usually about 30 seconds.

### Already running NeoForge in Docker?

If your compose file uses `itzg/minecraft-server` or the official
NeoForge image, the migration is one line + one env var. Change only:

```diff
-    image: itzg/minecraft-server:latest
+    image: ghcr.io/0xnullsect0r/multiforge-server:1.0.0
     environment:
       EULA: "TRUE"
+      MULTIFORGE_LICENSE: "${MULTIFORGE_LICENSE}"
```

Keep your existing `volumes:` and `ports:` — MultiForge picks up your
world in place. No world conversion.

---

## Manual install

### 1. Download the release artifacts

From <https://github.com/0xnullsect0r/multiforge/releases/tag/v1.0.0>,
download:

- `multiforge-installer-1.0.0.jar` — one-shot installer.
- `multiforge-1.0.0-server.jar` — the server jar (drop-in for
  `neoforge-1.21.1-server.jar`).

### 2. Run the installer

```bash
mkdir multiforge-server && cd multiforge-server
java -jar multiforge-installer-1.0.0.jar --install-dir .
```

The installer writes `run.sh` (or `run.bat` on Windows) and a default
`config/multiforge-server.toml`.

### 3. Accept the EULA and paste your license

```bash
echo "eula=true" > eula.txt
```

Save your token to `license.key` (one line, no leading/trailing
whitespace):

```
eyJhbGciOi...paste the whole token here...
```

Then tighten permissions:

```bash
chmod 600 license.key
```

### 4. Add your mods and world

- Copy your `mods/` folder into the install directory.
- Copy your `world/` folder if you have one — otherwise a fresh world
  generates on first boot.

### 5. Start the server

```bash
./run.sh          # Linux / macOS
run.bat           # Windows
```

Watch for `Done!` in the log.

### Already running NeoForge on bare-metal?

Same install directory, just swap the jar:

```bash
# stop the running server first — wait for "Saving..." to finish
mv neoforge-1.21.1-server.jar neoforge-1.21.1-server.jar.old
cp /path/to/multiforge-1.0.0-server.jar .
# edit user_jvm_args.txt (or your run.sh) to point at the new jar name
# put your token in license.key (one line, chmod 600)
./run.sh
```

Your world, mods, and configs are untouched — the world is
byte-compatible with upstream NeoForge.

---

## License notes

- Verification is **fully offline** — the token is checked against a
  public key baked into the server jar. No phone-home.
- Your token is tied to your purchase. Don't share it publicly.
- If you lose it, request a reissue from the same email address you
  bought with — we can regenerate against the same customer id.
- Keep `license.key` (or your `.env` file) out of git. Ever.

Three places the server looks for your token, in order:

1. `MULTIFORGE_LICENSE` environment variable (used by docker-compose).
2. `-Dmultiforge.license=<token>` JVM argument.
3. `license.key` file in the server root.

An invalid or missing token exits with code 78 and
`[MultiForge] Invalid or missing license` on stderr — that's your cue
to check which of the three the server is actually reading.

---

## Verify it's working

Once the server prints `Done!`, run:

```bash
# Docker
docker compose exec multiforge mc-send-to-console "/multiforge region list"

# Manual: same command via your server console
```

You should see one line per live region with MSPT (milliseconds per
tick) stats. If instead you see `Unknown command`, MultiForge isn't
loaded — check the boot log for a license or Java-version error.

---

## What if something goes wrong?

**Server exits with code 78** — invalid or missing license. Check:

- Your token is the full string (starts with `eyJ`, no truncation).
- No stray whitespace or trailing newline in `license.key`.
- The env var or JVM arg is spelled exactly `MULTIFORGE_LICENSE` /
  `multiforge.license`.

**Server won't start, no license error** — check `java -version`. You
need JDK 21+.

**World seems to have "forgotten" something after a crash** — the
MultiForge journal will replay any committed state on boot. If a
journal file itself is corrupt the boot log names the region and
sequence; open a support ticket at
<https://github.com/0xnullsect0r/multiforge/issues> with the log.

**Anything else** — email support@multiforge.example with your
customer id (the `sub` field printed by
`multiforge-license-cli verify`) and the boot log.

---

## Rolling back

MultiForge writes only to `world/multiforge/`,
`config/multiforge-server.toml`, and its own `logs/multiforge-*.log`
files. To roll back to upstream NeoForge:

1. Stop MultiForge (`/stop`, wait for `STOPPED` in the log).
2. Restore your original NeoForge server jar.
3. Delete `world/multiforge/` and `config/multiforge-server.toml`.
   The world itself is byte-compatible with upstream NeoForge.
4. Start NeoForge as normal.

No conversion required either way.
