# multiforge-bench

Live-server checks for MultiForge: the vanilla-parity gate, the Phase X
behaviour scenarios, and the MSPT/TPS benches. Latest results and the
runbooks for hardware-sized runs are in
[`docs/verification/README.md`](../docs/verification/README.md).

The module never imports `net.minecraft.*`. It installs real servers from
installer jars, boots each in a child JVM on free ports, and talks to it over
RCON, the game port (protocol bots) and its console log.

## Servers

- **MultiForge** (default): installed from the installer built by
  `:neoforge:installerJar` in `upstream/neoforge-1.21.1` (after publishing
  the api and runtime to mavenLocal), or `-Pinstaller=<jar>`.
- **Stock NeoForge** (`-Pserver=stock`): installed from
  `-PstockInstaller=<jar>`, or the official 21.1.251 installer downloaded
  from maven.neoforged.net.

Installs live under `multiforge-bench/build/server/<flavour>/` and are
reused until the installer changes. `-Pworkers=<n>` sets
`-Dmultiforge.workers` (a stock server ignores it); `-PextraJvmArgs="…"`
adds server JVM flags.

## Tasks

| Task | What it does |
|---|---|
| `determinism` | Vanilla-parity gate. It generates a frozen seed world on stock, ticks a copy on stock and on MultiForge at each of `-Pworkers=1,4`, and compares the terrain of every full chunk. The task fails on any difference. |
| `scenario` | Phase X checks `x1`–`x4` (`-Pscenario=`) on stock and MultiForge. Each check records observations that must match between the two. |
| `vanilla` | `/tick sprint` on a fresh no-mods world; per-tick MSPT distribution. |
| `atm10` | The same for a modpack: `-PmodpackDir=<dir with mods/>`, or `-PmodpackUrl=<server zip> -PmodpackSha256=<hex>`. Exits 2 with no pack. |
| `swarm` | `-Pplayers` protocol bots (MCProtocolLib) placed on rings up to `-Pspread` blocks from spawn, walking, placing and breaking in real time for `ticks/20` seconds. |
| `x8StrictSwarm` | The swarm with `-Dmultiforge.mode=strict`; fails on any ownership violation or region overrun. Defaults to 100 bots for 60 minutes. |
| `console` | Boot a server and relay stdin lines to it over RCON. |
| `worldDiff` | Semantic or byte-level diff of two world directories. |

Every run writes a JSON result (`-PoutputFile`, default under
`multiforge-bench/build/bench-results/`) and keeps the server log
(`-PbootLog`, default under `multiforge-bench/build/bench-logs/`).

## Timing

On MultiForge the benches read `/multiforge tickstats`, which records
Vanilla's own time for every tick in the measured window, so mean,
percentiles and maximum are exact, and TPS is ticks completed over wall time.
Stock NeoForge has no equivalent. There the benches sample `/tick query`
(the last 100 ticks) every 10 seconds and take TPS from game time over wall
time. Every result says which it used (`timing_source`).

Sprint runs (`vanilla`, `atm10`) measure per-tick compute without the 50 ms
pacing; their rate is reported as `sprint_tps`, not as sustained TPS. The
swarm runs in real time, because what it measures is whether the server
keeps up.

## Classes

| Class | Role |
|---|---|
| `ServerInstall`, `BenchSetup` | Run an installer into a server directory; pick the flavour and installer |
| `HeadlessServerRunner` | Boot, RCON, log tail, RSS sampling, measured window, clean stop |
| `RconClient` | Source RCON client |
| `BotSwarm` | MCProtocolLib bots: login, configuration, teleport confirms, walking, block work |
| `MetricsCollector`, `BenchResult` | Timing samples and the result JSON |
| `ProbeSummary` | Parses `/multiforge probes` (violations, overruns, reroutes) |
| `DeterminismRun`, `determinism.TerrainHash` | The parity gate and its per-chunk terrain hash |
| `ScenarioRun` | Phase X scenarios |
| `VanillaBench`, `Atm10Bench`, `ModpackFetcher`, `SwarmBench`, `ServerConsole` | Task entry points |
