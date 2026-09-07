# MultiForge client debug mod — user guide

The MultiForge client debug mod (`multiforge_debug`) is an optional companion mod that visualises what a MultiForge server is doing under the hood. It loads on any NeoForge 1.21.1 client, stays inert on stock (non-MultiForge) servers, and lights up automatically the moment you connect to a MultiForge server that advertises the `multiforge:debug/v1` channel.

You don't need it to *play* on a MultiForge server — MultiForge is a drop-in NeoForge replacement, and any vanilla NeoForge 1.21.1 client can connect. Install the debug mod when you want to see region boundaries, tick-cost heatmaps, live MSPT/TPS overlays, and the reroute/violation log.

---

## 1. Install

1. Have a working NeoForge 1.21.1 client (any launcher — vanilla launcher, Prism, CurseForge, ATLauncher, Modrinth App). NeoForge 21.1.90 or newer, JDK 21.
2. Download the mod jar from the latest release:
   ```
   https://github.com/0xnullsect0r/MultiForge/releases/latest/download/multiforge-client.jar
   ```
   Stable-alias URL — it always resolves to the newest release.
3. Drop the jar into your instance's `mods/` folder. Nothing else — no config file to edit up front, no keybind to register manually.
4. Launch. In the mod list you should see **MultiForge Debug Client** at whatever version you downloaded.

The mod jar is fully self-contained — the wire-protocol types from `multiforge-runtime` are bundled inside it, so nothing else has to be installed alongside.

### Uninstall

Delete `multiforge-client.jar` from `mods/` and relaunch. No world data, config, or key mapping is touched.

---

## 2. What you see when it's running

The mod stays quiet until you're connected to a MultiForge server. Once the server sends the `HELLO` frame on the `multiforge:debug/v1` channel, four overlays activate at once:

### 2.1 F3-style HUD (top-left)

A three-line status block, drawn in the same style as vanilla F3 but always visible when the mod is connected:

```
MultiForge build=1.3.13 proto=1 tickHz=20
MultiForge regions=8 workers~=8 tps~=20.0
MultiForge worstP95=8.3ms warns=3(last 200)
```

- **Line 1** — server build label, wire-protocol version, and the server's target tick rate (20 Hz for a normal 1.21.1 world).
- **Line 2** — live region count, an approximate worker-thread count (currently reported as `≈ region count`, since regions each own one worker at a time), and an estimated TPS (`min(tickHz, 1000 / worstP95)`).
- **Line 3** — worst region's MSPT at the p95 percentile, and the number of ownership/reroute warnings in a rolling 200-event ring buffer.

### 2.2 Chunk-border renderer

Colored borders around every chunk the server is telling you about, coded by region ownership:

- Each region gets its own hue (stable within a session — a region keeps the same colour as long as it exists).
- Borders sit at world-edge Y (0 to build limit), rendered as translucent walls so you can see through them.
- When two adjacent chunks belong to different regions, you see the seam directly.

### 2.3 Tick-cost heatmap

Per-chunk overlay coloured by how expensive the chunk was to tick last frame. Roughly green → yellow → red for cheap → hot. Useful for spotting a mob farm or entity swarm that's eating one region's tick budget.

### 2.4 Region-pin renderer

When an op has run `/multiforge region pin <id> <world> <fromCX> <fromCZ> <toCX> <toCZ>` on the server, the pinned rectangle is drawn as a labelled box in the world. Pinned regions are exempt from automatic merge/split, so this is what you look at to see the "hand-frozen" boundaries the operator has locked in.

### 2.5 Toggle overlays with a keybind

Since **v1.3.15** the whole overlay stack (HUD lines, chunk borders, heatmap, pin boxes) has a master on/off toggle bound to a keybind. Default: **F6** (unbound in Vanilla, so no conflict). Press it once to hide everything, again to bring it back. You'll see a small status message (`MultiForge overlays: on / off`) in the chat area as feedback.

To remap or clear the binding:

1. `Esc` → **Options → Controls → Key Binds**.
2. Scroll to the **MultiForge Debug** category.
3. Click the button next to **Toggle overlays**, press the new key (or `Esc` to leave it unbound), and click **Done**.

Default is ON — the mod behaves like pre-v1.3.15 unless you explicitly press the key to hide it.

---

## 3. Auto-behaviour on connect

You don't opt into any of these streams manually. On receiving the server's `HELLO` frame, the mod immediately sends back a `SUBSCRIBE` frame with every stream enabled (regions, heatmap, pins, violations). All four overlays fill in as soon as the next server tick's frames arrive — usually within one full second (the debug channel runs at ~4 Hz).

There is currently no client-side toggle to disable individual overlays. If you want the HUD off, remove the mod jar.

---

## 4. Playing on a non-MultiForge server

The mod loads harmlessly on stock NeoForge servers. Since v1.3.13 the `multiforge:debug/v1` channel is registered as **optional**, so:

- Connection succeeds normally.
- The mod never draws any overlay (no `HELLO` frame arrives → the HUD's `buildLines()` returns an empty list; the three renderers have no snapshot to draw from).
- No performance impact — the renderer event handlers early-exit on the null state.

You can leave the jar in `mods/` permanently and forget about it.

---

## 5. Troubleshooting

**"Incompatible client! Please use NeoForge 1.21.1-v…-beta" on connect.**
You're on v1.3.12 or earlier of the client mod — the channel was REQUIRED there. Upgrade to v1.3.13 or newer.

**Mod loads but no overlays draw when connected to a MultiForge server.**
The server side of `multiforge:debug/v1` is not yet wired on the fork (deferred to a post-v1.3.13 release). The mod is functioning correctly; the server simply isn't sending frames yet. Track the follow-up in the CHANGELOG.

**"File mods/multiforge-client.jar is not a valid mod file — Illegal version number".**
You have v1.3.7–v1.3.10 (mods.toml carried a literal `${version}` token). Upgrade to v1.3.11 or newer.

**"NoClassDefFoundError: net/multiforge/runtime/diagnostics/wire/DebugPayload$…"** at mod-construct time.
You have v1.3.11 (the wire-type classes weren't bundled). Upgrade to v1.3.12 or newer.

**F3 shows the vanilla debug screen; the MultiForge HUD is missing.**
The MultiForge HUD is separate from F3 and always visible when connected to a MultiForge server — you don't need F3 open to see it. If you don't see the three-line block at the top-left, either you're not on a MultiForge server, or the server hasn't wired the channel yet (see previous point).

**Client crash on any Linux distro that lacks the flite/text2speech native library.**
Not caused by MultiForge — that's a Minecraft narrator crash. Install `libflite` on your distro, or disable the narrator in Vanilla accessibility options.

---

## 6. What the mod does NOT do

- No client-side commands. All operator commands live on the server side (see [`docs/multiforge-command.md`](multiforge-command.md)).
- No keybindings, no config screen, no mod menu integration.
- No modification of world data — the mod is purely read-only display glue on top of a server-pushed data stream.
- No opt-in for individual streams — you get all four overlays or none.

Extension points that *may* land in future releases are tracked in [`docs/design/client-debug-protocol.md`](design/client-debug-protocol.md). If you want per-overlay toggles or a keybind, file an issue.
