# MultiForge

**MultiForge is a closed-source, license-gated, drop-in replacement for the NeoForge dedicated Minecraft server that runs with Folia-style regionized multithreading.**

Swap your `neoforge-<version>-server.jar` (or Docker image) for MultiForge's. Your existing world, mods, configs, and launch command keep working. Under the hood, MultiForge partitions the world into ownership regions that tick in parallel on a worker pool sized by `cores × threads-per-core`, with automatic reroute-and-warn for mods that assume single-thread access.

MultiForge is **not** open source. Access requires a valid license token (see [License](#license) below).

---

## Status

Pre-alpha. In active development. See [`docs/blueprint.md`](docs/blueprint.md) for the design and the roadmap in the private plan file for the milestone plan.

Current milestone: **M0 — Repo bootstrap, license gate, instrumentation-only build.**

---

## Quick start (once M0 ships)

```yaml
# docker-compose.yml
services:
  minecraft:
    image: ghcr.io/multiforge/multiforge-server:0.1.0
    environment:
      EULA: "TRUE"
      MEMORY: 8G
      MULTIFORGE_LICENSE: "eyJ2IjoxLC..."  # your license token
    ports:
      - "25565:25565"
    volumes:
      - ./data:/data
```

```
docker compose up -d
```

In-game commands (op only):

```
/multiforge config cores 8
/multiforge config threads 2
/multiforge region mode player-only
/multiforge region size 16
/multiforge region pin -128 -128 128 128
/multiforge region list
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
├── multiforge-license/      Ed25519 license token verifier
├── multiforge-license-cli/  CLI for signing tokens (used by purchase site)
├── multiforge-runtime/      Region manager, schedulers, mailboxes, diagnostics
├── multiforge-patches/      Patches applied to vendored NeoForge 1.21.1
├── multiforge-installer/    Repackages patched NeoForge + runtime + license
├── multiforge-client/       Client-side debug mod (F3 overlay, region renderer)
├── multiforge-testmods/     Fixture mods exercising violation classes
├── multiforge-bench/        Headless bot-swarm TPS harness
├── docker/                  Dockerfile + docker-compose examples
├── docs/                    Design docs, operator handbook
└── upstream/neoforge-1.21.1 Vendored NeoForge source (git submodule)
```

---

## Building from source

MultiForge is source-available to license holders only. If you have access:

```
git clone git@github.com:multiforge/multiforge.git
cd multiforge
./gradlew :setup                # vendor NeoForge 1.21.1
./gradlew build                 # build all artifacts
./gradlew :multiforge-installer:dockerBuild   # build the Docker image
```

Requires JDK 21, Docker with buildx, and ~20 GB free disk for the NeoForge workspace.

---

## License

**MultiForge is proprietary software.** See [`LICENSE`](LICENSE).

- Source access is restricted to authorized license holders.
- The MultiForge server refuses to boot without a valid Ed25519-signed license token.
- Tokens are verified fully offline — MultiForge never contacts a license server at runtime.
- Purchase and license issuance are handled at (site TBD).

License token format is documented in [`docs/license.md`](docs/license.md).

---

## Support

- Issues: (private tracker TBD)
- Security disclosures: `security@multiforge.example` (PGP key TBD)
