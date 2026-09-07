# `/multiforge` — operator command reference

Every runtime knob MultiForge exposes lives under a single Brigadier tree rooted at `/multiforge`. The command is registered directly on the server's Brigadier dispatcher from `handleServerStarting` (post-`loadLevel`), so it's available from the moment your server console prints `Done (…)! For help, type "help"`.

- **Permission**: op-only (`hasPermission(2)`). The server console has permission level 4 and always sees the command; player ops with level 2 or higher see it as well; non-op players don't see the command at all (it's filtered from tab-complete).
- **Aliases**: `/multiforge help`, `/multiforge ?`, `/multiforge -h`, `/multiforge --help`, and a bare `/multiforge` with no args all print the same help reference.
- **Tab-completion**: full Brigadier autocomplete on every subcommand + argument. Integer args are bounded (`config cores 1..128`, `region size 1..256`), and dimension IDs are suggested from the server's currently-loaded levels via `SharedSuggestionProvider.suggestResource`.
- **Config persistence**: subcommands under `config` and `region pin`/`unpin` mutate on-disk files under `<serverDir>/config/` — `multiforge-server.toml` and `multiforge-region-pins.json`. `region size`/`region mode` also persist to the toml. Everything else is read-only.

Every subcommand below shows the exact Brigadier form, an example, and what it prints back.

---

## `help`

Print the one-screen operator reference (the same output you're reading here, condensed). Also fires on bare `/multiforge` with no arguments.

```
/multiforge help
/multiforge
/multiforge ?
```

Output is grouped by intent: worker pool + region sizing, region topology, diagnostics, and mod-safety scanner.

---

## `config` — worker pool + region sizing

Live-mutable knobs backed by `config/multiforge-server.toml`. Changes persist immediately.

### `config cores <n>`

Set the worker-pool core count. The scheduler runs `cores × threads-per-core` worker threads.

- **Type**: integer, 1..128.
- **Effect**: takes effect on the next server restart. The M6 hook for live-mutating the running `MultiThreadedSchedulerHost`'s pool size isn't wired yet — the store is written now, and the runtime picks it up on next boot.
- **Recommendation**: at most your machine's physical core count minus one (leave one core for OS/network/JVM housekeeping). Use `nproc` on Linux or `lscpu` for a quick reading.

```
/multiforge config cores 8
```

Reply: `config: cores = 8 (was N)`.

### `config threads <n>`

Threads per core. Typically `1` on machines without SMT/hyper-threading (Zen 4/5 non-X3D, most laptop chips) and `2` on machines with it (Intel Core, Ryzen 7950X, most datacenter parts).

- **Type**: integer, 1..8.
- **Effect**: same as `cores` — persists now, applies next restart.

```
/multiforge config threads 2
```

Reply: `config: threads = 2 (was N)`.

**Combined guidance**: for a Ryzen 9 7950X (16 physical / 32 logical), `cores=12 threads=2` is a good starting point (24 workers, leaves 4 physical cores for the rest of the machine). For a 4-core VPS, `cores=3 threads=1` (leave one core for the OS).

---

## `region` — region topology

Region control surface. `region list` and `region size`/`mode` are the day-to-day knobs; `region pin`/`unpin` are for advanced use.

### `region list`

Print every materialised region (per-world) with its owning worker thread ID and current membership.

```
/multiforge region list
```

Sample output (one region per line, grouped by world):

```
=== minecraft:overworld ===
  region#0: owner=worker-3, chunks=[(0,0)..(15,15)], entities=142, blockEntities=57, mspt(p95)=2.1ms
  region#1: owner=worker-7, chunks=[(16,0)..(31,15)], entities=88, blockEntities=31, mspt(p95)=1.4ms
=== minecraft:the_nether ===
  (no regions — dimension has no active players)
```

Use this whenever you want to answer "which worker is handling this player" or "why is that dimension not multithreading".

### `region size <chunks>`

Set the default region edge length in chunks. Regions are square; `region size 16` means each region owns up to a 16×16 chunk grid (256 chunks). Adaptive sizing may split/merge below this cap.

- **Type**: integer, power of 2, 1..256.
- **Effect**: persists to toml, applies on the next region rebuild (server restart, or when the adaptive sizer next reconsiders topology).

```
/multiforge region size 32
```

**Recommendation**: `16` for laggy hardware or fewer than 10 players; `32` for a typical 20-slot survival server; `64` for large-scale (100+ slot) worlds where you want fewer, larger regions.

### `region mode <player-only|full-world>`

Region partitioning strategy.

- **`player-only`** (default) — regions materialise only around players, and cold areas of the world don't consume worker threads. Best for typical survival/PvE where players are clustered.
- **`full-world`** — the whole loaded world is regionised whether players are present or not. Better for headless simulations, farm servers with lots of always-loaded chunks, and adventure maps where you want tickable command blocks / redstone in unloaded areas of the world.

```
/multiforge region mode player-only
/multiforge region mode full-world
```

Tab-complete suggests both values.

### `region pin <id> <world> <fromCX> <fromCZ> <toCX> <toCZ>`

Pin a rectangle of chunks so the adaptive sizer never merges or splits any region overlapping it. Useful for a spawn plaza, a mega-farm, or anywhere you want a stable region boundary.

- **Args**:
  - `id` — an operator-chosen label. Anything that fits a single word (`spawn`, `farm-1`, `combat-arena-north`). Used later with `region unpin`.
  - `world` — dimension ID, e.g. `minecraft:overworld`. Tab-complete suggests loaded dimensions.
  - `fromCX fromCZ toCX toCZ` — chunk coordinates (not block coordinates). Divide block X/Z by 16 to convert.
- **Effect**: persists to `config/multiforge-region-pins.json` immediately. Pinned rectangles are drawn in-world by the client debug mod (see [`docs/client-mod-guide.md`](client-mod-guide.md) §2.4) so you can eyeball them.

```
/multiforge region pin spawn minecraft:overworld -8 -8 8 8
```

That pins the 16×16 area centred on world spawn (chunks (-8,-8) to (8,8) — 256 chunks, 4096 blocks square).

### `region unpin <id>`

Remove a pin by ID.

```
/multiforge region unpin spawn
```

Reply: `unpinned "spawn"` (or `no such pin: spawn` if the ID wasn't in the store).

---

## `probes` — diagnostic counters

`probes` prints the current values of every `ProbeRegistry` counter — the same counters MultiForge's ownership guard, watchdog, and per-subsystem instrumentation bump.

### `probes` (no args)

Dump every counter, sorted alphabetically.

```
/multiforge probes
```

Sample output:

```
chunk-system.holder-load.count = 4321
chunk-system.ticket-add.count = 8712
event-dispatch.async.count = 42
event-dispatch.region.count = 1093
event-dispatch.unknown.count = 0
ownership.reroute.count = 3
region-tick.no-regionizer-skip.count = 12
region-tick.overrun.count = 0
```

### `probes <prefix>`

Filter to counters whose key starts with the prefix. Tab-complete suggests common prefixes.

```
/multiforge probes region-tick
/multiforge probes event-dispatch
```

Use `probes region-tick.overrun` to answer "have we ever hit a region-tick overrun since boot" and `probes ownership.reroute` to check how many mod calls have been silently rerouted to the owner thread.

---

## `chunks <world>` — M9 chunk-shadow summary

Report the M9 `ChunkHolderManager` shadow for one world: how many chunk holders exist and how they're distributed across load levels.

- **Args**: `world` — dimension ID, tab-completed against loaded dimensions.
- **Requires**: the M9 chunk-system bridge to be installed. If it isn't, prints `Chunk-system bridge not installed. Ensure ChunkHolderManagerBridge is wired.` and exits.

```
/multiforge chunks minecraft:overworld
```

Sample output:

```
world=minecraft:overworld holders=1024
  ENTITY_TICKING (distance=1): 88
  BLOCK_TICKING (distance=2): 214
  BORDER (distance=3): 512
  INACCESSIBLE (distance=4): 210
```

If you see many holders sitting at `BORDER` or `INACCESSIBLE`, it usually means chunks that were force-loaded and never released — worth grepping `world/multiforge/*.log` for ticket-holder churn.

---

## `warn` — violation log

Live view of the `ViolationLogger` ring buffer — rerouted calls, blocking-on-worker warnings, and any other guard fires.

### `warn list`

Print recent violations (bounded ring, capped at the last 200 events).

```
/multiforge warn list
```

Sample output:

```
=== recent violations (24) ===
[16:33:02] OwnershipEnforcer.reroute: mod "othermod" called Level.setBlock off-thread — rerouted
[16:34:11] OwnershipEnforcer.reroute: mod "othermod" called Level.setBlock off-thread — rerouted
[16:34:15] region-tick.no-regionizer-skip::minecraft:the_end: level minecraft:the_end has no materialised regionizer yet — skipping
```

The `site` column tells you which subsystem raised the violation.

### `warn clear`

Reset the ring buffer.

```
/multiforge warn clear
```

Reply: `violation history cleared`.

Typical workflow: `warn clear` before deliberate testing, run the scenario, then `warn list` — the buffer now contains only what your scenario triggered.

---

## `certify` — mod-safety scanner

Run the ASM-based `multiforge-scanner` (see `docs/design/scanner-rules.md`) against a mod jar sitting in `./mods/`. The scanner checks 12 rules for known-unsafe patterns (direct `ChunkMap` access, blocking on a worker thread, etc.) and reports WARN/ERROR findings.

- **Requires**: `multiforge-scanner.jar` on the system property `multiforge.scanner.jar` (default: `multiforge-scanner.jar` in the server root). Ship the scanner jar alongside your server if you want to use this subcommand — it's not bundled with the runtime by default.

### `certify <modId>`

Scan one mod by its mod-ID.

```
/multiforge certify othermod
```

Output:

```
scanning mods/OtherMod-3.4.1.jar (modId=othermod)...
  R03 WARN net/othermod/BlockHandler.onBlockPlaced — unsynchronized Level mutation
  R09 ERROR net/othermod/TickHandler.onTick — Thread.sleep in event listener
2 findings (1 WARN, 1 ERROR).
```

### `certify all`

Scan every jar in `./mods/`. Same output shape, one section per mod.

```
/multiforge certify all
```

Useful before a bump: run against your modpack, get a snapshot of every ERROR, and either patch the mod or file an issue upstream.

---

## Common workflows

### First-time server tuning

```
/multiforge config cores 8
/multiforge config threads 2
/multiforge region size 32
/multiforge region mode player-only
```

Reboot the server (config changes apply next boot). Confirm with `/multiforge region list` — you should see approximately `cores × threads` workers show up as regions materialise around players.

### Diagnosing a lag spike

```
/multiforge probes region-tick
/multiforge warn list
/multiforge region list
```

That triangulates: `probes region-tick.overrun` counts how many times a region tick went over budget, `warn list` shows recent reroutes/violations, and `region list` shows which region's MSPT is high.

### Freezing region topology for a build event

```
/multiforge region pin build-arena minecraft:overworld -32 -32 32 32
/multiforge region mode full-world
```

Now the arena rectangle is guaranteed stable, and the whole loaded world (not just player-nearby areas) is regionised so redstone contraptions tick reliably.

### Undo:

```
/multiforge region unpin build-arena
/multiforge region mode player-only
```

### Pre-flight before a modpack bump

```
/multiforge certify all
```

Fix or drop mods with R09 (blocking on worker thread) ERROR findings; WARN-only findings ship without objection.

---

## Deferred behaviour

- `/multiforge config cores <n>` and `config threads <n>` persist to disk immediately but don't yet re-plumb the running `MultiThreadedSchedulerHost`'s pool. Restart required. Live-mutating hook is a follow-up (M6 hook not yet wired).
- `certify` requires you to ship `multiforge-scanner.jar` on-server — no auto-bundle.

Both are known limitations; behaviour will change in a future release without breaking the command surface.
