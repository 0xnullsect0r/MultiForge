# `/multiforge` — operator command reference

Every runtime knob MultiForge exposes lives under a single Brigadier tree rooted at `/multiforge`. The command is registered directly on the server's Brigadier dispatcher from `handleServerStarting` (post-`loadLevel`), so it's available from the moment your server console prints `Done (…)! For help, type "help"`.

- **Permission**: op-only (`hasPermission(2)`). The server console has permission level 4 and always sees the command; player ops with level 2 or higher see it as well; non-op players don't see the command at all (it's filtered from tab-complete).
- **Aliases**: `/multiforge help`, `/multiforge ?`, `/multiforge -h`, `/multiforge --help`, and a bare `/multiforge` with no args all print the same help reference.
- **Tab-completion**: full Brigadier autocomplete on every subcommand + argument. Integer args are bounded, and dimension IDs are suggested from the server's loaded levels.
- **Config**: `config …` and `region size` change `config/multiforge-server.toml` and take effect immediately (except `config mode off`, see below); `region pin`/`unpin` change `config/multiforge-region-pins.toml`. Everything else is read-only.

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

## `config` — live configuration

Every change is applied to the running server at once and written to
`config/multiforge-server.toml`.

| Command | Effect |
|---|---|
| `config show` | Print the active configuration. |
| `config reload` | Re-read the toml (after editing it by hand) and apply it. |
| `config cores <n>` / `config threads <n>` | Resize the region worker pool to `cores × threads` threads, before the next tick. |
| `config mode <hybrid\|strict\|off>` | `hybrid` ↔ `strict` switch at once. `off` (no regionized runtime; Vanilla's tick) and leaving `off` take effect at the next start. |
| `config policy <warn\|reroute-only\|fail>` | What a cross-region write does: reroute and warn, reroute silently, or throw. |
| `config warnPerMin <n>` | Violation warnings per minute per call site; `0` silences them. |

```
/multiforge config cores 8
Set cores = 8 (worker pool 8 threads)
```

---

## `region` — region topology

### `region list`

Every world's live regions (largest first) with their size, then the pins.

```
/multiforge region list
minecraft:overworld: 3 region(s)
  region region#7 — 41 section(s), up to 10496 chunks, READY
  region region#9 — 2 section(s), up to 512 chunks, READY
  ...
No pinned regions.
```

### `region size <chunks>`

Section edge length in chunks (a power of two, 1..256). Regions merge when
their sections touch, so larger sections mean fewer, larger regions. Every
world is re-partitioned immediately: queued work is applied first, then each
loaded chunk and block-entity ticker moves to its new region.

### `region pin <id> <world> <fromCX> <fromCZ> <toCX> <toCZ>`

Pin a rectangle of chunks: its loaded chunks always tick in one region, even when unloaded chunks separate them (a farm and its distant item line, a base and its chunk loaders). A pin cannot keep neighbouring loaded chunks out of that region — regions whose sections touch always merge.

- **Args**:
  - `id` — an operator-chosen label. Anything that fits a single word (`spawn`, `farm-1`, `combat-arena-north`). Used later with `region unpin`.
  - `world` — dimension ID, e.g. `minecraft:overworld`. Tab-complete suggests loaded dimensions.
  - `fromCX fromCZ toCX toCZ` — chunk coordinates (not block coordinates). Divide block X/Z by 16 to convert.
- **Effect**: applies immediately and persists to `config/multiforge-region-pins.toml`. Pinned rectangles are drawn in-world by the client debug mod (see [`docs/client-mod-guide.md`](client-mod-guide.md) §2.4) so you can eyeball them.

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

## `chunks <world>` — loaded chunks per region

```
/multiforge chunks minecraft:overworld
world=minecraft:overworld loaded chunks=1024 regions=3
  region region#7: 961 chunk(s)
  region region#9: 48 chunk(s)
  region region#12: 15 chunk(s)
```

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

- **Requires**: `multiforge-scanner.jar` on the system property `multiforge.scanner.jar` (default: `multiforge-scanner.jar` in the server root). Every release attaches `multiforge-scanner.jar`, and the updater (`docs/install.md#updating`) installs it into the server root; without it the command prints one `Cannot certify:` line.

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
/multiforge config threads 1
/multiforge region size 16
/multiforge region list
```

All of it applies immediately; watch `region list` and `probes region-tick` while players spread out.

### Diagnosing a lag spike

```
/multiforge probes region-tick
/multiforge warn list
/multiforge region list
```

That triangulates: `probes region-tick.overrun` counts how many times a region tick went over budget, `warn list` shows recent reroutes/violations, and `region list` shows which regions exist and how large they are.

### Keeping a build on one thread

```
/multiforge region pin build-arena minecraft:overworld -32 -32 32 32
```

The loaded chunks inside the rectangle now always tick in one region, even
where unloaded chunks separate them. Undo with `/multiforge region unpin build-arena`.

### Pre-flight before a modpack bump

```
/multiforge certify all
```

Fix or drop mods with R09 (blocking on worker thread) ERROR findings; WARN-only findings ship without objection.

---

## Limits

- `config mode off` (and switching back from it) needs a restart: it decides whether the regionized runtime is installed at all.
- `certify` needs `multiforge-scanner.jar` on the server (`-Dmultiforge.scanner.jar=…`, default `./multiforge-scanner.jar`, which the updater installs).
