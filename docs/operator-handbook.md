# Operator handbook

This is the day-to-day guide for running a MultiForge server. For the
underlying design, see the topic docs (`regions.md`, `chunks.md`, …).

## Installation

Two ways to run MultiForge:

### Docker (recommended)

```yaml
# docker-compose.yml
services:
  minecraft:
    image: ghcr.io/0xnullsect0r/multiforge-server:1.0.0
    ports: ["25565:25565"]
    environment:
      EULA: "TRUE"
      MEMORY: "8G"
      MULTIFORGE_LICENSE: "<paste your token here>"
      MOTD: "A MultiForge server"
    volumes:
      - ./world:/data/world
      - ./mods:/data/mods
      - ./config:/data/config
```

Same env-var contract as `itzg/minecraft-server` — swapping images is
supposed to be a one-line change.

### Bare-metal installer

```bash
java -jar multiforge-<version>-installer.jar --install-dir ./server
cd ./server
# license.key must contain the token, or pass -Dmultiforge.license=...
./run.sh
```

## License

MultiForge boots gated on an Ed25519-signed license token. The gate
runs before Vanilla init — an invalid or missing token exits with code
78 and a "Invalid or missing license" message on stderr.

The token is looked up in this order:

1. Env var `MULTIFORGE_LICENSE`
2. JVM arg `-Dmultiforge.license=<token>`
3. File `license.key` in the server root

Tokens are single-tier for v1 (`"features": ["core"]`) and either
perpetual or expiring. See `docs/license.md` for the payload format.

## Runtime configuration

Everything past the license lives in `config/multiforge-server.toml`.
It is reloaded on `/multiforge config reload`. Defaults are safe for
any hardware.

```toml
# CPU
cores = 8                    # worker cores to dedicate
threads-per-core = 2         # SMT threads per core (worker pool = cores * threads-per-core)

# Regions
[regions]
size = 16                    # chunks per side of the base section
mode = "player-only"         # or "full-world"
mspt-split-threshold = 30.0  # ms — regions hotter than this get split
mspt-merge-threshold = 5.0   # ms — regions colder than this can be merged

# Persistence
[persistence]
autosave-per-tick-chunks = 8         # max chunks written per tick per region
autosave-per-tick-nanos  = 2000000   # 2 ms budget per tick per region
journal-fsync = true                 # fsync on every append (only turn off for benchmarks)

# Diagnostics
[diagnostics]
warn-per-mod-per-second = 5          # rate limit for reroute warnings
debug-channel-enabled = true         # multiforge:debug/v1 for the client mod
```

## Commands

All in-game commands live under `/multiforge`. Permission level 2.

### Cores / threads

```
/multiforge config cores 8
/multiforge config threads 2
/multiforge config reload
```

Changing cores or threads reboots the worker pool. Regions ride through
without dropping ticks — the coordinator drains inboxes, swaps pools,
and resumes.

### Regions

```
/multiforge region size 16
/multiforge region mode player-only      # regions only around players
/multiforge region mode full-world       # regions over the entire loaded map
/multiforge region list                  # dump every live region + MSPT
```

### Pins

Pins force a rectangle of chunks into a single dedicated region. Useful
to isolate two nearby bases so a lag spike in one doesn't bleed into
the other. Coordinates are chunk coordinates, inclusive.

```
/multiforge region pin base-alpha 100 200 132 232
/multiforge region unpin base-alpha
```

Pins persist in `world/multiforge/pins.dat`.

## Client debug mod

`multiforge-client-<version>.jar` is an ordinary NeoForge mod for the
client. It loads on any vanilla NeoForge 1.21.x client and lights up
when connected to a MultiForge server. Features:

- **F3 overlay** — one line per region: `region-<id> mspt=X.X/Y.Y owned=N sections=M`
- **Chunk borders** — each region gets a distinct colour.
- **Tick-cost heatmap** — per-chunk MSPT tinting.
- **Pin selection boxes** — every operator-created pin shows as an in-world box.
- **Violation side panel** — live reroute/warn events.

Ships as a separate optional download; not required to play. The
server never emits debug packets unless a client requests them.

## Shutdown

`/stop` (or SIGTERM to the container) walks the shutdown coordinator:

1. Stop accepting new packets.
2. Drain every region's mailbox — bounded by `shutdown-drain-seconds`
   in `multiforge-server.toml` (default 30).
3. Flush every journal.
4. Stop the worker pool.

You'll see the phase transitions in the log:

```
[MultiForge] shutdown ACCEPTING → DRAINING_INBOX (inbox=142, migrations=3)
[MultiForge] shutdown DRAINING_INBOX → FLUSHING_JOURNAL (inbox=0, migrations=0)
[MultiForge] shutdown FLUSHING_JOURNAL → STOPPING_WORKERS
[MultiForge] shutdown STOPPING_WORKERS → STOPPED
```

If the drain deadline is exceeded the coordinator proceeds anyway and
the phase message says how many items were still queued.

## Crash recovery

On boot MultiForge scans `world/multiforge/journal/region-*.mjl` and
replays every committed entry through `JournalReplayHarness`:

- `CHUNK_SAVE` — reapply the snapshot to the chunk.
- `ENTITY_MIGRATION` — recover the transfer (either finish it or roll
  it back, depending on which side committed first).
- `REGION_MERGE` / `REGION_SPLIT` — rebuild the regionizer state.
- `TICK_MARK` — advance the region's clock.

A journal with a torn tail (crash mid-append) is truncated silently.
A journal with a CRC mismatch inside an otherwise-valid entry fails
the boot hard — the log names the region id and sequence and the
operator should escalate rather than blindly retry.

## Watching the numbers

Three places to see how the server is doing:

- **Log line, every N ticks** (`diagnostics.log-tps-every`) — cumulative
  TPS + slowest region.
- **`/multiforge region list`** — one line per region with MSPT p50/p95.
- **OpenTelemetry OTLP export**, if `-Dmultiforge.otel.endpoint=<url>` is set.

Rule of thumb: if a region sits above `mspt-split-threshold` for more
than a few seconds, the sizer should split it. If it doesn't, either
the region has a single very hot chunk (which can't be split) or a mod
is blocking on the region worker thread — check the warn log first.

## Reporting bugs

- **Server crash.** Grab `logs/latest.log`, `world/multiforge/journal/`
  (all of it — the replay harness reproduces the state), and the
  contents of `world/multiforge/pins.dat`. File an issue at
  <https://github.com/0xnullsect0r/multiforge/issues>.
- **Mod misbehaviour.** Enable violation logging in the debug client
  and capture a screen recording of the side panel; the mod id + site
  columns say which mod is doing what.
- **Performance.** Run `multiforge-bench/report.sh` (or attach the
  OTEL trace if you have one). Include mod list.

## Out of scope for v1.0

- Integrated (single-player) server parallelization — vanilla only.
- Full Starlight light-engine port — kept NeoForge's engine.
- Bukkit / Paper plugins — only *ported* Folia patterns run via the
  Folia-shaped mirror API.
- Modrinth / CurseForge — distribution is Docker + GitHub Releases.
- Multi-tier licenses — single "core" tier for v1.
- Machine-binding on license activation — offline signature only.
