# MultiForge — release binaries

Public download point for **MultiForge**, a Folia-inspired
multithreaded, drop-in replacement for the NeoForge dedicated
Minecraft server.

- **Marketing + purchase site:** <https://multiforge.example>
- **Source code:** private (proprietary license — buy a license key
  to run the server).
- **Support:** [open an issue here](https://github.com/0xnullsect0r/multiforge-releases/issues/new/choose).

This repo hosts:

- **Release binaries** — every version's installer JAR, replacement ZIP,
  runtime library, license CLI, and operator install bundle.
- **Docs** — mirrored from the source repo on every release to
  [`docs/`](docs/).
- **Issue tracker** — bug reports, license questions, feature requests.

The code that produces these binaries lives in a private repo. This
repo is deliberately code-free.

---

## Downloads

Pinned to the latest release (stable, safe to hardcode):

| What | URL |
|------|-----|
| Fresh installer JAR | [`multiforge-installer.jar`](https://github.com/0xnullsect0r/multiforge-releases/releases/latest/download/multiforge-installer.jar) |
| Drop-in replacement ZIP | [`multiforge-replacement.zip`](https://github.com/0xnullsect0r/multiforge-releases/releases/latest/download/multiforge-replacement.zip) |
| Docker image (GHCR) | `docker pull ghcr.io/0xnullsect0r/multiforge-server:latest` |
| Runtime library (for mod authors) | see [Releases](https://github.com/0xnullsect0r/multiforge-releases/releases/latest) |
| License CLI (offline verify) | see [Releases](https://github.com/0xnullsect0r/multiforge-releases/releases/latest) |
| Operator install bundle (docs + compose + install helper) | see [Releases](https://github.com/0xnullsect0r/multiforge-releases/releases/latest) |

Every past release is at <https://github.com/0xnullsect0r/multiforge-releases/releases>.

---

## Install

Three ways to install, pick whichever fits where you already run your
server. Full step-by-step is in [`docs/install.md`](docs/install.md);
short version:

### 1. Docker Compose

```yaml
services:
  multiforge:
    image: ghcr.io/0xnullsect0r/multiforge-server:1.0.0
    ports: ["25565:25565/tcp", "25565:25565/udp"]
    environment:
      EULA: "TRUE"
      MEMORY: "8G"
      MULTIFORGE_LICENSE: "${MULTIFORGE_LICENSE}"
    volumes: ["./data:/data"]
```

Put the token in a sibling `.env`:

```
MULTIFORGE_LICENSE=eyJhbGciOi...
```

Then:

```bash
docker compose up -d
```

### 2. Fresh installer JAR

```bash
curl -LO https://github.com/0xnullsect0r/multiforge-releases/releases/latest/download/multiforge-installer.jar
java -jar multiforge-installer.jar install --license "$MULTIFORGE_LICENSE"
sed -i 's/eula=false/eula=true/' eula.txt
./run.sh
```

### 3. Overlay onto an existing NeoForge 1.21.1 server

```bash
# stop your NeoForge server, back up the world, then:
curl -LO https://github.com/0xnullsect0r/multiforge-releases/releases/latest/download/multiforge-replacement.zip
unzip multiforge-replacement.zip
mv run.multiforge.sh run.sh
mv config/multiforge-server.toml.example config/multiforge-server.toml
# paste your license token into ./license.key
./run.sh
```

---

## Docs

Mirrored here from the source repo:

- [Install guide](docs/install.md) — Docker + manual + systemd + Windows, plus migration and rollback.
- [Operator handbook](docs/operator-handbook.md) — day-to-day: config, commands, debug client, shutdown, crash recovery.
- [License format](docs/license.md) — Ed25519 offline verification, token payload.
- [Persistence](docs/persistence.md) — per-region WAL journal, autosave, crash recovery.
- [Regions](docs/regions.md) — section→region→worker, adaptive sizing, cross-region routing.
- [Chunks](docs/chunks.md) — chunk holder + tickets + per-region merge/split.
- [Entity migration](docs/migration.md) — two-phase teleport, passenger trees.
- [Global systems + network](docs/global-network.md) — global tick, packet routing, region pins.
- [Public API](docs/api.md) — `ServerDomains` + Folia-shaped mirrors for mod authors.

---

## Support

Open an issue with the right template:

- [Bug report](https://github.com/0xnullsect0r/multiforge-releases/issues/new?template=bug.yml) — server crash, mod incompatibility, wrong behavior
- [License problem](https://github.com/0xnullsect0r/multiforge-releases/issues/new?template=license.yml) — token won't activate, reissue request, upgrade
- [Feature request](https://github.com/0xnullsect0r/multiforge-releases/issues/new?template=feature.yml)
- [Question](https://github.com/0xnullsect0r/multiforge-releases/issues/new?template=question.yml) — anything else

Security disclosures: see [`SECURITY.md`](SECURITY.md).

Never paste your license token into an issue. If you need to prove
ownership for a reissue, include only the customer id (the `sub`
field printed by `multiforge-license-cli verify`).

---

## License

MultiForge is proprietary. Running the server requires a valid,
purchased license token. See <https://multiforge.example> to buy a
license.

The binaries in this repo are distributed under the same proprietary
license — download and use per the terms shipped in the runtime jar's
`LICENSE` file.
