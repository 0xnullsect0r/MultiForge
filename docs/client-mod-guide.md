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

The mod stays quiet until you're connected to a MultiForge server. Once the server sends the `HELLO` frame on the `multiforge:debug/v1` channel, every overlay you have enabled activates at once. All of them can be switched on and off individually — see [§2.8](#28-configuring-what-you-see).

### 2.1 F3-style HUD (top-left)

A three-line status block, drawn in the top-left corner in the same style as vanilla F3 but **always visible** — you don't need to press F3. It appears as soon as a HELLO frame arrives from a MultiForge server (usually within one second of connecting):

```
MultiForge build=1.3.13 proto=1 tickHz=20
MultiForge regions=8 workers~=8 tps~=20.0
MultiForge worstP95=8.3ms warns=3(last 200)
```

- **Line 1** — server build label, wire-protocol version, and the server's target tick rate (20 Hz for a normal 1.21.1 world).
- **Line 2** — live region count, an approximate worker-thread count (currently reported as `≈ region count`, since regions each own one worker at a time), and an estimated TPS (`min(tickHz, 1000 / worstP95)`).
- **Line 3** — worst region's MSPT at the p95 percentile, and the number of ownership/reroute warnings in a rolling 200-event ring buffer.

### 2.2 Chunk-border renderer

Coloured vertical strips along the **seams** where two adjacent chunks belong to different regions:

- Each region gets its own hue, stable for as long as that region exists.
- Only the seams are drawn — not a box around every chunk. Between two chunks the same region owns, nothing is drawn at all.
- Strips span a configurable band around the player (16 blocks below, 32 above by default) rather than the full world height, which keeps them crisp and avoids the z-fighting the old full-height boxes produced at altitude.

**As of v1.4.0 these seams are real.** From v1.3.5 through v1.3.18 the client had no source of truth for chunk ownership — the server never sent one — so it hash-picked a region id from each chunk's coordinates modulo the live region count. The seams that produced were an artifact of *how many* regions existed, they re-shuffled whenever a region merged or split, and they had no relationship to what actually owned anything. v1.4.0 adds the `CHUNK_OWNERSHIP` packet, and the overlay now draws the server's true mapping.

Ownership is tracked per **section** (a square block of chunks whose size follows the server's `region size` setting), so seams land on section boundaries. That is the genuine ownership boundary, not a rounding of something finer.

If you see no seams at all, either the whole area you're standing in is owned by one region, or the server predates v1.4.0 and doesn't send ownership — in which case the overlay draws nothing rather than inventing a boundary.

### 2.3 Tick-cost heatmap

A translucent tint on the ground, coloured by how expensive each chunk was to tick: green under 5 ms, yellow under 20 ms, red at 50 ms and above. Useful for spotting a mob farm or entity swarm eating one region's tick budget.

Two things to know about the numbers:

- The server measures tick cost per **region**, not per chunk, so every chunk in a region shows that region's average. A single genuinely hot chunk will tint its whole region rather than lighting up alone.
- Since v1.4.0 the server sends only the chunks within your view distance, and only for the dimension you're actually in. Earlier versions broadcast every loaded chunk in every world to every client four times a second.

### 2.4 Region-pin renderer

When an op runs `/multiforge region pin <id> <world> <fromCX> <fromCZ> <toCX> <toCZ>` on the server, the pinned rectangle appears in-world as a labelled **line box** — a yellow-ish wireframe outline around the chunks in the pin's rectangle, with the pin's id billboarded above the box's north-east top corner. The box's vertical extent is clamped to a 48-block band around the player (16 below, 32 above) so it stays crisp at any altitude. Pinned regions are exempt from automatic merge/split, so this shows the "hand-frozen" boundaries the operator has locked in. As of v1.3.16 the box appears within ~250 ms of the pin being set (previous releases had a shared-state bug where the pin never reached the client until a server restart).

### 2.5 Toggle overlays with a keybind

Since **v1.3.15** the whole overlay stack (HUD lines, chunk borders, heatmap, pin boxes) has a master on/off toggle bound to a keybind. Default: **F6** (unbound in Vanilla, so no conflict). Press it once to hide everything, again to bring it back. You'll see a small status message (`MultiForge overlays: on / off`) in the chat area as feedback.

To remap or clear the binding:

1. `Esc` → **Options → Controls → Key Binds**.
2. Scroll to the **MultiForge Debug** category.
3. Click the button next to **Toggle overlays**, press the new key (or `Esc` to leave it unbound), and click **Done**.

Default is ON — the mod behaves like pre-v1.3.15 unless you explicitly press the key to hide it.

---

### 2.6 Violation panel (right edge)

A live feed of the server's ownership/reroute warnings — the same events `/multiforge warn list` shows and the same ones `ViolationLogger` writes to the server log. Newest at the top, one row each:

```
14:22:07 [somemod] entity.move — off-thread mutation rerouted
14:22:03 [othermod] blockentity.tick — cross-region access
```

Each row is the local time, the mod id, the call site, and a truncated detail string. Rate limiting happens server-side, so what you see here is one row per warning that actually fired, not per suppressed repeat.

The client keeps the last 200 events and draws the newest 8 by default (`hud.violationMaxRows`). The summary block's `warns=N(last 200)` counter refers to that same rolling window — it is not a since-connect total.

**New in v1.4.0.** This panel was described in the mod's own description, in this guide, and in [`docs/debugging-violations.md`](debugging-violations.md) from M6 onward, but was never actually built: the events were collected and only their count was drawn.

### 2.7 Region list (top-left, under the summary)

One row per live region, under the three summary lines:

```
region-1 mspt=8.1/12.4 owned=132 sections=6
region-2 mspt=2.0/3.1 owned=18 sections=2
```

That's the region id, its p50/p95 tick cost in milliseconds, how many entities it owns, and how many sections it spans. Capped at 8 rows by default (`hud.regionListMaxRows`), with a `… N more region(s)` line when there are more.

**New in v1.4.0**, in the same sense as the violation panel: the data and its exact format have existed since M6 and were documented, but nothing drew them.

### 2.8 Configuring what you see

Every overlay above can be switched on and off independently, and switching one off actually **stops the server sending that data** rather than just hiding it — the mod re-sends its subscription whenever the setting changes.

**To open it:** from the main menu or the in-game pause menu, click **Mods**, select **MultiForge Debug Client** in the list, then click **Config**. The settings are grouped into *Overlays*, *HUD panels*, and *World rendering*; changes apply as soon as you leave the screen — no restart, no reconnect.

There is no keybind for the config screen. F6 is the overlay master toggle (§2.5), not a way in here.

The same settings live in `config/multiforge_debug-client.toml`, created on first launch, if you would rather edit text.

| Setting | Default | What it controls |
|---|---|---|
| `overlays.hud` | on | The three-line summary block (§2.1) |
| `overlays.regionList` | on | Per-region rows (§2.7) |
| `overlays.violations` | on | Violation panel (§2.6) |
| `overlays.chunkBorders` | on | Region seams (§2.2) |
| `overlays.heatmap` | on | Tick-cost tint (§2.3) |
| `overlays.pins` | on | Pin boxes (§2.4) |
| `hud.regionListMaxRows` | 8 | Region rows before truncating |
| `hud.violationMaxRows` | 8 | Violation rows drawn |
| `render.borderRadiusChunks` | 4 | How far out to look for seams |
| `render.yBelow` / `render.yAbove` | 16 / 32 | Vertical extent of seams and pin boxes |

The F6 keybind (§2.5) is a master switch on top of all of this — it hides everything at once regardless of the individual settings, and while it's off the mod unsubscribes entirely, so the server stops sending anything but the handshake.

---

## 3. Auto-behaviour on connect

On receiving the server's `HELLO` frame, the mod checks the protocol version it advertises and then sends back a `SUBSCRIBE` frame naming exactly the streams your config has enabled. Those overlays fill in as soon as the next frames arrive — usually within a second, since the debug channel runs at ~4 Hz.

The subscription is re-sent whenever what you want changes: you edit the config, or you press F6. It is *not* re-sent otherwise, so an idle client costs the server nothing beyond the streams it asked for.

If the server speaks a protocol version this client doesn't understand — a much newer server, or a much older one — the mod refuses to subscribe at all and says so in the summary block instead of silently showing nothing.

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
Check, in order: the F6 master toggle isn't off; the individual overlay is enabled in the config (§2.8); the server is v1.3.14 or newer (older MultiForge servers never wired the server side of the channel at all); and the summary block isn't reporting an unsupported protocol version (see below).

**Summary block says "server protocol N unsupported by this client".**
The server speaks a version of the debug protocol this client mod doesn't know. The mod deliberately refuses to subscribe rather than misinterpret frames. Match the client mod's version to the server's — both ship from the same release.

**Everything works except chunk borders.**
The server is older than v1.4.0 and doesn't send region-ownership data. Rather than fabricate seams from a hash (which is what v1.3.5–v1.3.18 did), the overlay draws nothing. Upgrade the server.

**No overlays, and the server log says "SUBSCRIBE from &lt;you&gt; declined: missing multiforge.debug.view".**
Someone has restricted the `multiforge.debug.view` permission on that server. It defaults to allow-everyone, so this only happens where an operator running a permission plugin has deliberately denied it. Ask them to grant it.

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
- No modification of world data — the mod is purely read-only display glue on top of a server-pushed data stream.
- No per-chunk tick timings. The heatmap's resolution is bounded by what the server measures, which is per-region (§2.3).
- No sub-section ownership detail. Seams land on section boundaries because that is where ownership actually changes (§2.2).

Extension points that *may* land in future releases are tracked in [`docs/design/client-debug-protocol.md`](design/client-debug-protocol.md).
