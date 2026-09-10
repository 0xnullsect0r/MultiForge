# CHANGELOG

## v1.5.0 — the drop-in replacement ZIP now actually converts a server

Reported from a live test: unzip the archive, swap in its launcher, run it, and get

```
Error: Could not find or load main class net.multiforge.runtime.bootstrap.Main
```

Method 2 has never worked. Three independent reasons, any one of them fatal:

1. **`net.multiforge.runtime.bootstrap.Main` does not exist.** Not in the runtime jar, not anywhere in this repo — `grep -rn "package net.multiforge.runtime.bootstrap"` returns nothing. It was named only by the two launcher templates the archive shipped.
2. **The classpath could not have booted a server anyway.** `-cp "libraries/multiforge/*"` resolved to one 348 KB Minecraft-free library jar: no Minecraft, no NeoForge, no ModLauncher, no mods.
3. **The premise was wrong.** MultiForge is a *fork* — the regionized tick loop lives in patches to `net.minecraft.*` and `net.neoforged.*` classes inside the patched NeoForge jar. Dropping a library beside a stock NeoForge server leaves the stock, unpatched server running.

The archive also cannot simply ship a finished install: that tree is ~180 MB and contains Mojang's `server-1.21.1.jar` plus the `-srg` / `-slim` / `-extra` / `-unpacked` derivatives built from it. Redistributing those is not permitted — which is exactly why NeoForge, and Forge before it, ship an installer that downloads from Mojang and patches locally.

So the archive now embeds the fork installer and a converter that runs it in place. That is a genuine in-place conversion and the only lawful shape for one.

### The archive

```
multiforge/multiforge-installer.jar     the MultiForge installer
install-multiforge.sh / .bat            the converter
config/multiforge-server.toml.example
README-MULTIFORGE.txt
```

~9.8 MB, up from ~320 KB. `install-multiforge.sh` preflights JDK 21, backs up `run.sh` / `run.bat` / `user_jvm_args.txt` to `*.pre-multiforge-<timestamp>.bak`, runs `--installServer .`, rewrites `run.sh` to carry the same preflight, and writes `config/multiforge-server.toml` and `eula.txt` if absent. Nothing under `world/`, `mods/`, or the rest of `config/` is touched.

### The launcher operators actually get had no JDK preflight

v1.3.18 added a JDK-21 preflight and claimed "the launcher scripts refuse to run on the wrong JDK". It went into `run.multiforge.sh` — the launcher that could never run. The `run.sh` the fork installer writes is stock NeoForge's, calling bare `java` with no version check, so the JDK-26 failure that release set out to prevent was still fully reachable through Method 1. The converter now rewrites `run.sh` with the preflight.

### The preflight message never printed the version it detected

v1.3.18's script used `cat >&2 <<'PREFLIGHT_FAIL'` — a *quoted* heredoc delimiter, which suppresses expansion. The message added specifically to name the offending JDK printed the literal text `${JAVA_MAJOR:-unknown}` and `$JAVA_BIN`. Unquoted now, and verified against a faked JDK 26: `MultiForge requires JDK 21. Found: 26 at /path/to/java`.

### Other removals and fixes

- **`install` subcommand deleted** from the `multiforge-installer` module. It laid out a directory whose `run.sh` had the same nonexistent main class, so it could never work either. `build-zip` now requires `--installer <fork installer jar>` and refuses without one — the archive cannot be built empty again.
- **The module no longer bundles `multiforge-runtime.jar`.** Embedding it is what produced an archive that looked plausible and booted nothing. The jar drops from ~315 KB to ~8 KB; it is a build tool, not an operator download.
- **`scripts/install.sh` rewritten.** It called the deleted `install` subcommand with the wrong argument shape, and prompted for a `license.key` plus ran a `multiforge-license-cli verify` — license gating that CLAUDE.md ground rule 2 records as removed on 2026-09-06. It now wraps the fork installer and preflights JDK 21.
- **`release.yml`** builds the archive in `build-fork-installer` rather than `build-jars`, since it now needs the fork installer as an input. If that job fails, no archive ships — correct, as an archive without an installer is inert.

### Verified end-to-end

Not just built — run. A simulated stock NeoForge directory (world, mods, configs, `run.sh`, `user_jvm_args.txt`) was converted with the real archive, and the result booted a MultiForge-patched server to `You need to agree to the EULA`, with `--fml.neoForgeVersion 1.21.1-v1.4.1.0-beta` in the ModLauncher banner. World, mods, and configs verified byte-intact; backups verified present. Both preflights verified against a faked JDK 26.

`MainTest` rewritten accordingly. The old tests asserted only that files were *created*, which is how a launcher naming a nonexistent class survived six releases; the new ones assert what the scripts say — that nothing references `net.multiforge.runtime.bootstrap`, that the converter runs `--installServer`, that the heredoc is unquoted, and that the README keeps the redistribution rationale so nobody "simplifies" the archive back into being unlawful.

- `gradle.properties` + `upstream/neoforge-1.21.1/gradle.properties` bumped 1.4.1 → 1.5.0.

## v1.4.1 — the config screen's labels were wrong

v1.4.0 shipped the per-overlay config screen with translation keys that matched nothing NeoForge looks up, so the screen rendered raw key strings and, worse, one wrong label. Found while answering "how do I open the config UI" — the screen opened fine, but nothing in it was named correctly.

Two separate mistakes:

- **The group headings used invented keys.** `multiforge_debug.configuration.section.overlays` and friends resolve to nothing. `ConfigurationScreen` derives a subsection's title from `getTranslationKey(key)` and its button from that plus `.button` (`ConfigurationScreen.java:906-908`) — a different shape entirely. Its own `.section.` scheme (`:310`) is keyed by config *filename*, not by group.
- **A leaf-name collision.** With no explicit `translation(...)`, the screen falls back to `<modId>.configuration.<leaf key>` (`:539`) — the *leaf*, not the path. The boolean `overlays.hud` and the group `hud` both have the leaf name `hud`, so both resolved to `multiforge_debug.configuration.hud` and the group inherited the boolean's label.

Fix: every value and group now carries an explicit `.translation(...)` key (`Builder#translation` before `push` sets the group's, `ModConfigSpec.java:853-854`), namespaced by path — `configuration.overlays.hud` for the setting, `configuration.group.hud` for the group. `en_us.json` rewritten to match, with labels, tooltips, and subsection button text.

- **New `MultiForgeDebugConfigTest`** cross-checks the spec's declared keys against the lang file in both directions: every declared key has a label, every setting has a tooltip, every group has a button label, no duplicates, and no orphaned `configuration.*` entries left in the lang file. Verified non-vacuous by introducing a typo and watching two of its assertions fail. It compares the files as text because moddev only puts the NeoForge classpath on `main`, so a test cannot load `ModConfigSpec` — enough for the failure mode being guarded.
- **`docs/client-mod-guide.md` §2.8** now spells out how to reach the screen (Mods → MultiForge Debug Client → Config), notes that changes apply on close with no restart, and says plainly that F6 is not a way into it.

## v1.4.0 — finish the client debug mod: real region seams, the panels it always advertised, per-overlay config

An audit of `multiforge-client` against its own shipped metadata and docs found the mod was not finished. Four things were advertised in `neoforge.mods.toml`, in class javadoc, and in `docs/debugging-violations.md` but did not exist; the chunk-border overlay was drawing fabricated data that another doc told operators to debug with; and three rendering/state bugs were live. This release closes all of it, and raises the debug wire protocol to version 2.

### Bugs

- **The heatmap was drawing ~64 blocks above the player.** `HeatmapRenderer` translated only X and Z into the camera-relative frame that `RenderLevelStageEvent` supplies, leaving Y in absolute world space — so every quad landed at world `Y = camY + playerY`, a sheet of coloured squares over the player's head rather than under their feet. `ChunkBorderRenderer` and `PinRenderer` had always subtracted `camPos.y`; the heatmap now does too. An in-file comment had explicitly rationalised the omission, which is presumably why it survived from v1.3.5.
- **Client state was never cleared on disconnect.** `DebugSessionHandler` reset only the SUBSCRIBE latch; `DebugHudState` had no `clear()` at all. Leaving a MultiForge server left the HUD drawing that server's build label, region count and TPS indefinitely — and because the heatmap and pin renderers gate only on a world-id *string*, and `minecraft:overworld` matches everywhere, its heat tiles and pin boxes rendered on top of the player's own singleplayer world. New `DebugHudState.clear()` wipes every server-supplied field on `LoggingOut`; the F6 preference deliberately survives, since that is the user's, not the server's.
- **The client ignored `HELLO.protocolVersion`.** Protocol §8 states a client MUST read it before subscribing and MUST NOT subscribe to a version it does not understand. The client read the field, stored it, and subscribed regardless. `DebugPacketCodec` now names `MIN_SUPPORTED_PROTOCOL` (the constant §10 said Track C1 would introduce and never did) and exposes `supportsProtocol(int)`; the client refuses to subscribe outside the range and says so in the HUD instead of appearing broken.

### Chunk borders now show real ownership — wire protocol 2

From v1.3.5 through v1.3.18 `ChunkBorderRenderer.pickRegionId` hashed each chunk's coordinates modulo the live region count. The seams it drew were an artifact of *how many* regions existed, re-shuffled on every merge/split, and had no relationship to ownership. The class javadoc admitted this while `docs/debugging-violations.md` simultaneously instructed operators to use the overlay to diagnose real cross-region bugs.

The server always had the answer available: `ThreadedRegionizer.regionAtChunk(int, int)` is a lock-free `ConcurrentHashMap` read, documented safe from any thread and already called from main-thread event handlers elsewhere in the fork. Nothing shipped it over the wire.

- **New `CHUNK_OWNERSHIP` packet kind (`0x06`)** carrying `DebugPayload.OwnershipUpdate(worldId, sectionChunkShift, List<SectionOwner>)`. Entries are keyed by section *origin* chunk; the shift travels with the payload so a client can map any chunk to its section without knowing the server's `regionSize`. Ownership is per-section because that is where it genuinely changes — not a coarsening of finer data.
- **New `F_OWNERSHIP` subscribe bit (`0x10`)**, plus `F_ALL` as the canonical "every stream this build defines" constant. `DebugChannelServer` widened its inbound mask strip from `& 0x0F` accordingly.
- **New `OwnershipEmitter`** in the runtime, mirroring `TpsHistogramEmitter`'s per-world shape, installed per dimension on `LevelEvent.Load`.
- **`ChunkBorderRenderer` reads the real mapping** and has no hash fallback. With no ownership frame for the current dimension — overlay off, or a pre-v1.4.0 server — it draws nothing. Inventing a plausible boundary is the defect being fixed. An unknown *neighbour* is likewise not treated as a seam, or the overlay would draw a wall around the edge of the streamed area instead of around a region.
- `PROTOCOL_VERSION` 1 → 2. Both additions are backward-compatible per §8, so the channel name is unchanged: a v1.4.0 client on a v1.3.x server simply never sets the new bit, and a v1.3.x client on a v1.4.0 server never receives the new kind.

### Panels that were advertised but never built

- **Violation panel** (right edge). Promised in `neoforge.mods.toml`, in `MultiForgeDebugMod`'s and `DebugHudState`'s javadoc, and in `docs/debugging-violations.md` since M6. In reality the events landed in a 200-entry ring and only their *count* was ever drawn. Now renders `HH:mm:ss [modId] site — detail`, newest first, detail ellipsised so one long event cannot push the panel off-screen.
- **Region list panel** (top-left, under the summary). `DebugHudState.f3Lines()` has produced `region-<id> mspt=X/Y owned=N sections=M` since M6, and `docs/debugging-violations.md` documented that exact format as what the operator sees — but the method was dead outside its own unit test. Now drawn, capped, with a `… N more region(s)` line when truncated.
- Both panels' line builders are static and pure, which is what makes the new `DebugHudRendererTest` possible without a Minecraft runtime.

### Per-overlay config + a config screen

The mod had exactly one control: F6, all-or-nothing, which hid the overlays while the server kept pushing every stream. The subscription mask had supported per-stream opt-in since the protocol was frozen and nothing used it.

- **New `MultiForgeDebugConfig`** (`ModConfigSpec`, `config/multiforge_debug-client.toml`): individual toggles for all six overlays, row caps for both panels, and the seam/pin render extents that were previously hardcoded constants.
- **Config screen** via `IConfigScreenFactory` + NeoForge's auto-generated `ConfigurationScreen` — the Mods list now has a working Config button.
- **New `SubscriptionManager`** replaces v1.3.16's boolean latch with a tracked mask, re-sending SUBSCRIBE only when the desired mask actually changes. Turning an overlay off now stops the server sending that stream. Idle traffic stays zero, so the v1.3.15 SUBSCRIBE-spam bug does not return. This also restores protocol §6's "permission re-evaluated on every SUBSCRIBE" cadence, which the once-per-connection latch had made unreachable.

### Heatmap and ownership are now filtered per viewer

`TpsHistogramEmitter` applied no view-radius filter whatsoever, despite `DebugPayload.HeatmapUpdate`'s javadoc promising "per-chunk heat within the client's view radius" — it broadcast every loaded section in the world to every subscriber at 4 Hz. A player in the Nether received (and discarded client-side) every Overworld frame, and a large world could approach `MAX_FRAME_BYTES`, the codec's ceiling being 65 536 entries.

The producing emitters are Minecraft-free and have no access to a player position, so the narrowing lives fork-side: `DebugChannelServer.broadcast` now routes `HeatmapUpdate` and `OwnershipUpdate` through a per-viewer path that skips players in other dimensions and clips entries to each player's view distance, memoising the encode per chunk position so co-located players share one. Ownership widens the radius by one section so a section covering the edge of the view still reaches the client.

### `multiforge.debug.view` is now enforced — defaulting to allow-all

Protocol §6 named the SUBSCRIBE handler as the sole enforcement point for this node. Nothing enforced it: every emitter was installed with `PermissionFilter.ALWAYS_ALLOW` and the handler performed no check, so any player with the jar received the server's full telemetry.

New `DebugPermissions.VIEW` registers `multiforge.debug.view` on `PermissionGatherEvent.Nodes` and `handleClientFrame` consults it, zeroing the mask and logging (rate-limited per player, 60 s window) on denial. **The default resolver allows everyone**, which is a deliberate amendment to §6's original operator-level-2 default: the overlays are diagnostic convenience rather than privileged information, and a player who can see why their base is lagging is more useful than one who cannot. The node exists so servers that disagree can deny it through any permission handler.

### Docs

- **`docs/design/client-debug-protocol.md`** — Phase 0 amendment: `CHUNK_OWNERSHIP` in §2 and a new §7.7 schema, its cadence in §3, `F_OWNERSHIP` in §5, the allow-all default in §6, `PROTOCOL_VERSION` 2 and `MIN_SUPPORTED_PROTOCOL` in §8, and §10's stale "not yet a named constant" bullet narrowed to what is genuinely still open (there is still no client-version echo).
- **`docs/client-mod-guide.md`** — §2.2 still described the pre-v1.3.16 renderer ("borders around every chunk", "world-edge Y", "translucent walls") and said nothing about the data being fabricated; rewritten. New §2.6 (violation panel), §2.7 (region list), §2.8 (config table). §3 rewritten for per-stream subscription. §5's "server side is not yet wired" entry has been wrong since v1.3.14 — replaced, along with new entries for unsupported protocol version and permission denial. §6 claimed "No keybindings, no config screen" two sections after documenting the F6 keybind, and invited users to file an issue for a feature that shipped in v1.3.15.
- **`docs/debugging-violations.md`** — the section telling operators to debug ownership with the chunk-border overlay now says when that overlay can be trusted; the heatmap bullet states its per-region resolution; the violation-panel bullet is finally true.
- **`docs/README.md`** — `client-mod-guide.md` was not linked from the index at all.
- **`neoforge.mods.toml`** — description updated to match what the mod now does.

### Tests + CI

- New `DebugHudStateTest` (clear semantics, F6 preference survival, section-shift ownership lookup including negative coordinates, world scoping, replace-not-merge), `DebugHudRendererTest` (all three panels' builders), `SubscriptionManagerTest` (mask changes, idle silence, `F_OWNERSHIP` stripping on a protocol-1 server, reset on disconnect). `DebugPacketCodecTest` and `DebugChannelClientTest` extended for the new kind, the protocol window, and the channel-id pin that keeps the two `DebugFramePayload` copies from diverging.
- **`:multiforge-client:test` never ran in PR CI.** `ci.yml` enumerated modules explicitly and omitted the client behind a comment claiming moddev was not wired — false since v1.3.5, and `release.yml` has been building the module all along. New `client-build` job runs it, gated by a `paths` filter so the NeoForm decompile cost only lands on PRs that can actually break it.

### Deferred, with reasons

- **Shared `multiforge-wire` module** to dedupe `DebugFramePayload` between the client and the fork. The shared type extends `CustomPacketPayload`, so it needs Minecraft on the classpath and cannot live in the MC-free runtime; a new moddev-enabled module for 54 lines is not worth the fork-build cost. A test now pins the channel-id string on both sides instead.
- **Sub-section ownership fidelity** via `NewChunkHolder.owningRegion()`. Section granularity is the real boundary; the two differ only for chunks with no holder.
- **Real per-chunk MSPT instrumentation.** The heatmap value is a region average, as `TpsHistogramEmitter`'s own javadoc has always said. Making it genuinely per-chunk is a scheduler-instrumentation project.

- `gradle.properties` + `upstream/neoforge-1.21.1/gradle.properties` bumped 1.3.18 → 1.4.0.

## v1.3.18 — JDK 21 preflight in the installer-generated launcher scripts + loud docs warning

User's `~/MultiForge-Test/new-atm10-server.sh` (test harness for MultiForge against the ATM10 modpack) never reached `Done` — every `startserver.sh` cycle crashed at mod-scan with `Unsupported class file major version 70` and `MixinPreProcessorException: Attach error for cryonicconfig.mixins.json:server.MainMixin`. Root cause: the user's system-default `java` was JDK 26 (Oracle HotSpot 26.0.2). SpongeMixin — bundled by cryonicconfig and effectively every other 1.21.1 mod — ships a class-file reader that only understands Java 21 bytecode (major version ≤ 65) and rejects anything newer, crashing before ModLauncher can wire NeoForge. Not a MultiForge bug (upstream SpongeMixin), but the failure mode was completely opaque and MultiForge's own scripts/docs made no attempt to detect a wrong JDK. This release fixes what MultiForge can fix.

- **`multiforge-installer/src/main/java/net/multiforge/installer/Main.java`** — both `runShScript()` and `runBatScript()` (the templates that generate `run.multiforge.sh` and `run.multiforge.bat` inside `multiforge-replacement.zip`) now start with a JDK preflight. The scripts prefer `$JAVA_HOME/bin/java` when set, otherwise the `java` on `$PATH`, parse the `java -version` string, and refuse to run on anything but major version 21 with a message that names the detected version, the resolved path, the `Unsupported class file major version 7X` failure the user would otherwise hit, and remediation (`sudo pacman -S jdk21-temurin` / `apt install temurin-21-jdk` / `brew install temurin@21`, then `JAVA_HOME=/path ./run.sh`). Non-zero exit before ModLauncher ever starts, so the user gets a clean single-page error instead of a five-thousand-line mixin cascade.
- **`docs/install.md`** — Requirements section expanded from a one-line "Java 21" into a loud "**JDK 21 exactly**" warning that names the SpongeMixin trap, the common cause (user installed the "latest" JDK from their distro), and the `JAVA_HOME=... ./run.sh` workaround. New troubleshooting entry decoding `Unsupported class file major version 7X` (70 = JDK 26, 69 = JDK 25, …) with the same remediation.
- `gradle.properties` + `upstream/neoforge-1.21.1/gradle.properties` bumped 1.3.17 → 1.3.18.

Not shipped in the repo but worth noting: the user's local `~/MultiForge-Test/new-atm10-server.sh` test harness was rewritten with the same preflight logic and a hard 15-minute cap on the `startserver.sh` wait loop (previous version could loop indefinitely, growing `installer.log` in the process).

## v1.3.17 — stable-alias `multiforge-replacement.zip` in the release

- **`.github/workflows/release.yml`** — `build-jars` step now stages a `multiforge-replacement.zip` alias next to the versioned `multiforge-<v>-replacement.zip`. Same pattern the fork installer's `multiforge-installer.jar` has had since v1.3.3 and the client mod's `multiforge-client.jar` has had since v1.3.5. Users can link `.../releases/latest/download/multiforge-replacement.zip` and get the newest drop-in ZIP without needing to know the version. Release-body downloads section extended to name the stable alias.

Version bump 1.3.16 → 1.3.17.

## v1.3.16 — debug-mod UX cleanup (seven bugs surfaced by live testing)

User installed v1.3.14 server + v1.3.15 client and reported seven distinct bugs on a real playthrough. This release fixes all of them in one pass.

### Server console

- **SUBSCRIBE spam gone.** The client's `DebugPayloadRegistration` used to send a `SUBSCRIBE_ALL` frame on *every* incoming HELLO, and v1.3.14's `HeartbeatEmitter` re-broadcasts HELLO at 4 Hz as a keepalive — so the server console logged `SUBSCRIBE from <name> (mask=0xf)` four times per second per connected client. Fix has two halves:
  - **Client** — `DebugChannelClient` gains a once-per-connection `subscribed` latch. `DebugPayloadRegistration` only fires SUBSCRIBE on the first HELLO now; `DebugSessionHandler` resets the latch on `ClientPlayerNetworkEvent.LoggingOut` so hopping between servers works.
  - **Server** — `DebugChannelServer.handleClientFrame` only logs INFO when the received mask differs from the stored value; duplicate SUBSCRIBEs are silently applied.

### `/multiforge` commands

- **`/multiforge chunks <world>` now works.** The M9 `ChunkHolderManager` bridge was already installed on the fork; the dispatcher just didn't have a lookup Function into it. `MultiForgeCommandBinder` now uses the 3-arg dispatcher constructor with `world -> host.chunkManagerForOrNull(world)` — the reply now shows real chunk-shadow stats instead of "Chunk-system bridge not installed".
- **`/multiforge region pin ...` is now visible to the client.** Pre-v1.3.16 the command binder and the debug channel each loaded their own `RegionPinManager` instance from the same JSON, so command-side mutations never reached the debug-side emitter. New `MultiForgeServerState.pinManagerFor(server)` holder returns the same instance to both sides (`WeakHashMap` keyed by `MinecraftServer`, cleared on `ServerStoppingEvent`). Client sees the pin box appear within 250 ms.
- **`config` and `region mode`/`size` commands warn about restart-required.** Every persisting subcommand reply now appends `(applied on next server restart — live-reload not yet wired; see docs/multiforge-command.md)`. Live-reload of the running `MultiThreadedSchedulerHost` is deferred to M6.

### Client overlays

- **F3-style HUD moved to top-left, always visible.** Previously drew at bottom-left (contradicting `docs/client-mod-guide.md`) AND only when F3 was open (also contradicting the doc). Now: always visible when overlays are enabled and a HELLO frame has arrived.
- **Chunk-border renderer rewrite — no more pillar farm.** Old code drew a full-height AABB per chunk in a 9×9 grid around the player: 12 edges per cube, 4 vertical pillars per chunk corner, adjacent chunks sharing corners → a forest of vertical beacons following the player, with red/blue flashing at high altitude from Y-depth z-fighting between the full-world-height edges of adjacent chunks. New code walks the seams between adjacent chunks and draws a single line strip on each edge where the neighbours belong to different (hash-picked) regions; Y is clamped to a 48-block band around the player. Operator now sees just the region seams, no corner pillars, no z-fighting above clouds. Real per-chunk region-ownership is a wire-protocol change deferred to v1.4.
- **`PinRenderer` draws a real bounding box.** Pre-v1.3.16 only rendered a floating text label at the pin's centre — the doc promised a box; code didn't match. Now draws a proper `LevelRenderer.renderLineBox` in the same 48-block Y-band, with the pin's id billboarded above the box's NE-top corner.

### Docs + versioning

- `docs/client-mod-guide.md` §2.1 corrected (HUD position + always-visible); §2.4 corrected (real box + label).
- `docs/multiforge-command.md` Deferred behaviour section extended to explicitly cover `region size` and `region mode`.
- `gradle.properties` + `upstream/neoforge-1.21.1/gradle.properties` bumped 1.3.15 → 1.3.16.

## v1.3.15 — user-configurable keybind toggles the client mod's overlays

Adds a client-side toggle for the whole debug overlay stack (F3-style HUD, chunk borders, tick-cost heatmap, region-pin boxes). Default key: **F6** (unbound in Vanilla). User can remap or clear the binding under **Options → Controls → MultiForge Debug**.

- **New: `multiforge-client/…/MultiForgeKeyMappings.java`** — declares one `KeyMapping` (`key.multiforge_debug.toggle_overlays`) under a new `key.categories.multiforge_debug` category. `KeyConflictContext.IN_GAME`, `GLFW_KEY_F6` default. Registered via `RegisterKeyMappingsEvent` on the mod bus (in `MultiForgeDebugMod`'s constructor alongside the existing payload-handler registration).
- **New: `multiforge-client/…/KeyInputHandler.java`** — `@SubscribeEvent` handler for `ClientTickEvent.Post`. Drains any queued clicks via `consumeClick()`, flips `DebugHudState.overlaysEnabled` (new field, default `true`), and shows a status chat line (`MultiForge overlays: on/off`) as feedback.
- **Modified: `DebugHudState.java`** — new `AtomicBoolean overlaysEnabled` (default `true`), plus `overlaysEnabled()` getter and `toggleOverlays()` returning the new value.
- **Modified: `DebugHudRenderer`, `ChunkBorderRenderer`, `HeatmapRenderer`, `PinRenderer`** — each gains an early-exit `if (!state.overlaysEnabled()) return;` at the top of its event handler.
- **New: `multiforge-client/…/assets/multiforge_debug/lang/en_us.json`** — English strings for the category (`"MultiForge Debug"`) and binding (`"Toggle overlays"`) so the Controls menu shows real names instead of raw translation keys.

Default is ON — the mod behaves exactly like pre-v1.3.15 unless the user explicitly presses F6 (or their remapped key) to hide the overlays.

## v1.3.14 — wire the server side of `multiforge:debug/v1` so client overlays actually populate

v1.3.13 fixed the "Incompatible client!" disconnect by making the client's channel registration optional. Clients could now connect, but nothing appeared in the debug HUD or overlays because **no server-side of the channel existed** — no `RegisterPayloadHandlersEvent` listener ever ran on the server, no emitters were instantiated, no `PacketDistributor.sendToPlayer(...)` call site anywhere. The five emitter classes (`HeartbeatEmitter`, `RegionMapEmitter`, `PinListEmitter`, `TpsHistogramEmitter`, `ViolationEmitter`) shipped in the runtime jar were unreachable.

- **New: `upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/debug/DebugChannelServer.java`** — the missing bridge. On the neoforge mod bus, registers the `multiforge:debug/v1` payload channel as OPTIONAL (matches the client), handling inbound `SUBSCRIBE` frames per-player. On `NeoForge.EVENT_BUS`, hooks `ServerAboutToStart` (instantiates the four periodic emitters + heartbeat), `ServerStopping` (closes all handles), `PlayerLoggedIn` (sends unconditional `HELLO`), `PlayerLoggedOut` (clears per-player state), and `LevelEvent.Load`/`Unload` (installs one `TpsHistogramEmitter` per active world). Every emitter feeds a single fan-out sink that broadcasts each frame to every player whose subscription mask enables its stream (F_REGIONS/F_HEATMAP/F_PINS/F_VIOLATIONS per protocol §5).
- **New: `upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/debug/DebugFramePayload.java`** — deliberate duplicate of `multiforge-client/…/DebugFramePayload.java`. Same `Type.id()` (`ResourceLocation("multiforge:debug/v1")`), same `StreamCodec` shape, so both sides interoperate on the wire. Cannot live in `multiforge-runtime` (Minecraft-independent per CLAUDE.md); cannot live in `multiforge-client` (fork build doesn't depend on client module). Deferred cleanup: extract a shared `multiforge-wire` module.
- **`upstream/…/NeoForge.EVENT_BUS`-side glue**: `NeoForgeMod.java` gets one added line calling `DebugChannelServer.installOnModBus(modEventBus)` alongside the existing NeoForge event-handler registrations; `ServerLifecycleHooks.handleServerAboutToStart` gets one line calling `DebugChannelServer.installGameBusHooks()` right before the `ServerAboutToStartEvent` is posted.

Effect after v1.3.14: an op with the v1.3.14 client mod installed connects to a v1.3.14 MultiForge server. The three-line HUD (build + regions/workers/tps + worstP95/warns) populates within one second (HELLO arrives on channel-open). The chunk-border, heatmap, and pin overlays fill in as the client's SUBSCRIBE round-trips complete.

Deferred (not v1.3.14 scope):

- Permission-node gating via `multiforge.debug.view` (currently everyone in `PLAYERS` receives frames their SUBSCRIBE mask allows; per-viewer op-level filtering is a follow-up).
- Sharing the `RegionPinManager` between the `/multiforge region pin` command binder and the debug-channel pin emitter — currently each loads a fresh instance from the same JSON file, so runtime pin changes need a reload cycle to appear in the client overlay.
- Dropping HELLO's 4Hz keepalive cadence to something lighter (1Hz).

## v1.3.13 — mark `multiforge:debug/v1` channel optional so clients can connect anywhere

User reported connection rejected after installing v1.3.12 client jar:

```
Channel [multiforge:debug/v1] failed to connect: This channel is missing on the server side, but required on the client!
Client disconnected with reason: Incompatible client! Please use NeoForge 1.21.1-v1.3.12.0-beta
```

Root cause: `multiforge-client/src/main/java/net/multiforge/client/DebugPayloadRegistration.java` registered the channel via `event.registrar("1").playBidirectional(...)`, which defaults to REQUIRED. NeoForge's channel-negotiation phase rejects any connection whose peer doesn't advertise every REQUIRED channel — so the mod blocked connections to (a) every non-MultiForge server, and (b) every current MultiForge server too, because the server-side of the channel isn't wired yet on the fork.

The mod's `neoforge.mods.toml` description explicitly promises "the panel stays inert if the server does not advertise multiforge:debug/v1" — that only works if the channel is OPTIONAL.

- **DebugPayloadRegistration** — insert `.optional()` in the registrar chain: `event.registrar("1").optional().playBidirectional(...)`. Client can now connect to any NeoForge server; the debug HUD/renderers stay inert on servers that don't advertise the channel.

Follow-up (deferred): wire the *server side* of the `multiforge:debug/v1` channel on the MultiForge fork so the debug HUD actually gets frames when connected to a MultiForge server. Separate release.

## v1.3.12 — bundle `multiforge-runtime` + `multiforge-api` classes into `multiforge-client.jar`

v1.3.11 got `multiforge-client.jar` past FML's mods.toml parse, but the mod then crashed at construct-time on a stock NeoForge 21.1.249 client with:

```
java.lang.NoClassDefFoundError: net/multiforge/runtime/diagnostics/wire/DebugPayload$PinList
  at net.multiforge.client.DebugHudState.<init>(DebugHudState.java:41)
```

Root cause: the client jar contained only `net.multiforge.client.*` classes. `DebugHudState`, `DebugPayloadRegistration`, and the four renderers reference wire-protocol types from `net.multiforge.runtime.diagnostics.wire.*` (`DebugPayload` + its inner records, `DebugPacketCodec`, `DebugPacketKind`) — those classes ship in `multiforge-runtime.jar`, which is embedded as `META-INF/jarjar/multiforge-runtime-*.jar` inside the fork's universal jar server-side, but has no path onto a stock NeoForge client. The `implementation(project(":multiforge-runtime"))` gradle dep resolved the classes at *compile* time but the shipped jar's runtime classpath had nothing.

- **multiforge-client/build.gradle.kts** — new `tasks.jar { from(zipTree(...)) }` block that merges `multiforge-runtime`'s and `multiforge-api`'s class files directly into the client jar. Server-only runtime classes come along as dead code (harmless — class-loading is lazy; the client execution path never touches region/scheduler/chunk classes). Explicit excludes:
  - `META-INF/services/**` — avoids a v1.3.4-style `securejarhandler` "Invalid service type name" crash from the runtime's `SchedulerHost` SPI file landing in the client jar's module descriptor.
  - `META-INF/MANIFEST.MF` — client keeps its own manifest, no duplicate error.
  - `META-INF/maven/**` — drops the mavenLocal-published pom debris from the merge.
  - `multiforge-runtime.properties*` — pre-existing runtime-jar debris (the template file that survives runtime's `processResources` when the `filesMatching` pattern doesn't match); belongs in the runtime jar cleanup, not the client jar.

Also (same release):
- **`/multiforge help`** — new subcommand printing an intuitive one-screen reference for every `/multiforge` subcommand (worker pool / region topology / diagnostics / scanner). Same output now fires on a bare `/multiforge` too (previously printed a terse `Usage: /multiforge <config|region|…>` line). Aliases: `help`, `?`, `--help`, `-h`. `Unknown subcommand` responses now direct the user to `/multiforge help`.
- **Tab-completion for `/multiforge`** — `MultiForgeCommandBinder` now builds a full Brigadier tree instead of a single greedy-string catchall. Ops get real Brigadier autocomplete on every subcommand, argument-type checking (integer args are bounded — e.g. `config cores 1..128`, `region size 1..256`), and typed suggestions (`region mode` offers `player-only` / `full-world`; `region pin <id> <world>` and `chunks <world>` suggest the loaded dimensions via `SharedSuggestionProvider.suggestResource`). Every terminal node still routes through the same `MultiForgeCommandDispatcher.dispatch(String[], Consumer<String>)` so subcommand behavior stays centralized in the runtime.

## v1.3.11 — expand `${version}` in `multiforge-client`'s `neoforge.mods.toml`

Every release from v1.3.7 through v1.3.10 shipped a `multiforge-client.jar` with a literal `version = "${version}"` in its bundled `META-INF/neoforge.mods.toml` — FML rejects it at scan with `Illegal version number specified version` and refuses to load the mod. Reported by a user who dropped `multiforge-client.jar` into a stock NeoForge 21.1.249 client's `mods/` folder:

```
Exception message: net.neoforged.neoforgespi.locating.InvalidModFileException:
  Illegal version number specified version (multiforge-client.jar)
  at net.neoforged.fml.loading.moddiscovery.ModInfo.<init>(ModInfo.java:77)
```

Bug was invisible in server-side testing (the mod jar is never loaded there) and only surfaces on a real NeoForge client's mod scanner.

- **multiforge-client/build.gradle.kts** — new `processResources { filesMatching("META-INF/neoforge.mods.toml") { expand(mapOf("version" to project.version.toString())) } }` block. Mirrors the pattern already in `multiforge-runtime/build.gradle.kts` (which templates its own `multiforge-runtime.properties.in`). Root cause: the `net.neoforged.moddev` 2.0.78 plugin does NOT auto-configure Groovy-template expansion on `neoforge.mods.toml`.

## v1.3.5 — ship `multiforge-client` as a real mod jar + wire `/multiforge` into Brigadier

Two user-visible correctness gaps closed in the same release.

### Gap A — `multiforge-client` is now a distributable NeoForge mod jar

Prior to v1.3.5 the client debug mod lived in the repo only as source: `multiforge-client/build.gradle.kts` carried a `compileOnly` on the NeoForge `-universal` classifier (only ~1.4k of the ~8k needed classes), six of the nine `.java` files failed to compile, and the built jar contained three classes and no `FMLModType` manifest — not a mod. `docs/install.md:288` pointed users at the source directory, which is a dead link from an install doc.

- **`settings.gradle.kts`** — added `maven("https://maven.neoforged.net/releases")` to `pluginManagement.repositories { }` so the `net.neoforged.moddev` plugin resolves, and added `mavenLocal()` to `dependencyResolutionManagement.repositories { }` so the vendored fork's mavenLocal-published multiforge-runtime + multiforge-api artifacts are visible outer-side.
- **`multiforge-client/build.gradle.kts`** — replaced the compileOnly-`:universal` workaround with the `net.neoforged.moddev` plugin (`version = "2.0.78"`), which supplies a real NeoForge compile classpath (NeoForm-produced vanilla + NeoForge patches, merged). Targets `neoForgeVersion=21.1.90` from the outer `gradle.properties`. Deleted the ~40-line comment block that documented the missing wiring.
- **`multiforge-client/src/main/resources/META-INF/neoforge.mods.toml`** — dropped the `logoFile = "logo.png"` reference (no `logo.png` on disk); corrected the license field from `"Proprietary — see LICENSE …"` (stale, from pre-v1.2.0) to `"GPL-3.0-only"`; fixed the issue-tracker URL casing.
- **`.github/workflows/release.yml`** — `build-jars` job now also runs `:multiforge-client:build`; the assemble step stages the built jar into `release/` under both `multiforge-client-<v>.jar` and stable-alias `multiforge-client.jar` (matches the fork installer's pattern so docs can link `.../releases/latest/download/multiforge-client.jar`). Release-body Downloads section describes the new asset. `GRADLE_OPTS=-Xmx6G` bumped to match `scanner.yml`'s fork-build (moddev's NeoForm decompile is the largest step).
- **`docs/install.md`** — client-mod link swapped from source-dir path to stable-alias release URL, with drop-into-`mods/` instructions.
- **`RELEASING.md`** — asset enumeration extended for `multiforge-client-*.jar` + `multiforge-client.jar` (and the previously-undocumented `multiforge-installer.jar` stable alias).

### Gap B — `/multiforge` command is registered on Brigadier

Live-verified on a v1.3.4 server: an op user typing `multiforge` in chat, and the server-console typing the same, both hit `Unknown or incomplete command … multiforge<--[HERE]`. `/neoforge` worked, so Brigadier was healthy. Root cause: `multiforge-runtime/src/main/java/net/multiforge/runtime/commands/MultiForgeCommandDispatcher.java` was a pure-Java parser (`Consumer<String>` output); no code anywhere in the runtime, fork, or patches wrapped it in a Brigadier tree, constructed it, or subscribed to `RegisterCommandsEvent`. The class shipped in the runtime jar but was unreachable. Every command documented in `README.md § In-game commands` was dead.

- **`upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/commands/MultiForgeCommandBinder.java`** — new file. Loads `MultiForgeConfigStore` from `<serverDir>/config/multiforge-server.toml` and `RegionPinManager` from `<serverDir>/config/multiforge-region-pins.json` (both files are created on first successful mutation; missing/malformed files log a `ViolationLogger.warn` and fall back to defaults so the command tree still installs). Constructs a `MultiForgeCommandDispatcher(configStore, pins)` and registers a `RegisterCommandsEvent` listener on `NeoForge.EVENT_BUS` that binds `/multiforge` to a Brigadier tree with a greedy-string args argument. `.requires(src -> src.hasPermission(2))` restricts to ops + server console per README's "op-only" language.
- **`upstream/neoforge-1.21.1/src/main/java/net/neoforged/neoforge/server/ServerLifecycleHooks.java`** — call `MultiForgeCommandBinder.register(server)` from `handleServerAboutToStart`, gated on the same `freshInstall` flag used by `MultiForgeGlobalSystemsInit.install(...)` so a re-used GameTestServer JVM doesn't double-register the command.

Follow-up gap not in v1.3.5 scope: `/multiforge config cores N` writes to disk but the live `MultiForgeConfig` inside `MultiThreadedSchedulerHost` isn't re-plumbed to the store's `subscribe(...)` hook, so worker-pool changes still require a restart. Same pre-existing limitation as M6; separate M-milestone.

## v1.3.4 — fix `processResources` rename bug that crashed boot + fix README EULA step

v1.3.3 got the fork installer down the wire correctly, but the resulting server crashed on boot with:

```
IllegalArgumentException: multiforge-runtime.properties: Invalid service type name: 'multiforge-runtime' is not a Java identifier
  at cpw.mods.securejarhandler/cpw.mods.jarhandling.impl.SimpleJarMetadata.computeDescriptor(SimpleJarMetadata.java:47)
```

- **multiforge-runtime/build.gradle.kts** — `processResources` was calling `rename { "multiforge-runtime.properties" }` from inside a `filesMatching { }` block. In Kotlin DSL, that `rename(Closure)` resolves to the outer `AbstractCopyTask.rename` method, which runs the closure against **every file in the copy** — the closure unconditionally returns `"multiforge-runtime.properties"`, so every file got renamed to that. `META-INF/services/net.multiforge.api.spi.SchedulerHost` ended up as `META-INF/services/multiforge-runtime.properties`, which broke the SchedulerHost SPI AND crashed `securejarhandler` on boot because "multiforge-runtime" is not a valid Java identifier. Fix: replace the outer `rename` call with `name = "multiforge-runtime.properties"` on the `FileCopyDetails` inside `filesMatching`, and widen the pattern to `**/multiforge-runtime.properties.in` so the template is actually matched and expanded.
- **README.md + docs/install.md Method 1** — swapped the EULA step from `sed -i 's/eula=false/eula=true/' eula.txt` to `echo "eula=true" > eula.txt`. The fork installer doesn't seed `eula.txt`, so `sed -i` on a nonexistent file failed silently; users had to know to write it manually. Also removed the phantom `eula.txt` entry from the installer's file-layout diagram.

## v1.3.3 — `multiforge-installer.jar` stable alias points at the fork installer

v1.3.2 shipped both the working fork installer (`multiforge-1.3.2-installer.jar`, produced by `build-fork-installer` CI job) AND the broken pure-Java installer (`multiforge-installer-1.3.2.jar` + `multiforge-installer.jar` stable alias, produced by `build-jars` job). The README's `curl` command downloaded the stable alias, which resolved to the broken pure-Java installer — every user who followed the README got `Error: Could not find or load main class net.multiforge.runtime.bootstrap.Main`.

- **release.yml** — moved the `multiforge-installer.jar` stable-alias `cp` from `build-jars` (pointing at the broken pure-Java jar) into `build-fork-installer` (pointing at the working fork jar). Extended the fork installer's upload-artifact path glob to include both filenames. Reworded the release-body Downloads section to describe the fork installer's `--installServer <dir>` CLI (not the pure-Java `install --install-dir` shape).
- **README.md** — `java -jar ../multiforge-installer.jar install --install-dir .` → `java -jar ../multiforge-installer.jar --installServer .` (NeoForge installer shape).
- **docs/install.md Method 1** — same CLI swap; rewrote the "This drops" file-layout block to reflect the fork installer's real output (`libraries/net/neoforged/neoforge/<v>/`, top-level `run.sh` + `user_jvm_args.txt`, no `libraries/multiforge/` at top of tree); rewrote the "Installer CLI reference" block to describe NeoForge's actual `--installServer`/`--installClient`/`--extract`/`--help` args.

The pure-Java `multiforge-installer/` module stays around because its `build-zip` subcommand still synthesizes the Method 2 drop-in replacement ZIP. Follow-up cleanup will delete it entirely once the drop-in-ZIP flow is either fixed or dropped.

## v1.3.2 — B2 binary-patch integration + regionizer noise + Pelican egg + CI

First release where a fresh `--installServer` produces a fully-functional MultiForge server. Prior to v1.3.2 the shipped installer wrote a broken layout; v1.3.1 (the M12 boot fix) got the server past mod-loading but B2 global subsystems still failed to register.

### P — B2 binary-patch integration (the real bug fix)

`binarypatcher` (net.minecraftforge:binarypatcher:1.1.1, invoked by NeoGradle's `generateServerBinaryPatches`) runs in **whitelist mode** when `--patches` is non-empty: only classes with a matching `.patch` file in the patch dir get a binary diff generated. NeoGradle wires `--patches` = `upstream/neoforge-1.21.1/patches/` (NeoForge's own tree only), so our `multiforge-patches/**/*.java.patch` never reached binarypatcher. Classes we seed-from-base and patch (WorldBorder, Raids, LevelTicks, ServerFunctionManager, ServerScoreboard, CustomBossEvents, ChunkGenerationTask, ChunkHolder, ThreadedLevelLightEngine, ProcessorMailbox, LevelAccessor) compiled correctly into the fork jar but the shipped installer's `server.lzma` had no binary diff for them. Runtime code hit `NoSuchMethodError: 'void net.minecraft.world.level.border.WorldBorder.mfTickBody()'` at boot.

- **P.1** — New `stageMultiforgePatchesForBinaryPatcher` Sync task in `multiforge-patches.gradle` flattens `multiforge-patches/**/net/minecraft/**/*.java.patch` into `build/multiforge-patches-staging/<target-path>.patch` (the layout binarypatcher expects). `net/neoforged/**` patches excluded — they target NeoForge's own hand-written source, not vanilla, and staging them causes JPMS split-package errors. Uses `Sync` (not `Copy`) so stale destination files get cleaned.
- **P.2** — Wires the staging dir into `generateServerBinaryPatches`/`generateClientBinaryPatches`/`generateJoinedBinaryPatches` via `.getPatches().from(...)` + `dependsOn`. Live verification: `mfTickBody` string count in `output.lzma` went from 0 → 3; fresh install + boot completes with `Done (2.803s)`, zero `NoSuchMethodError`, zero `failed to register B2low`, all 8 global subsystems bind cleanly.

### R — Regionizer noise (dimensions that never load chunks)

- **R.1** — New `RegionizerEagerInit.materialiseAll(server, host)` in the fork bridge iterates `server.getAllLevels()` on `ServerAboutToStartEvent` and calls `host.regionizerFor(...)` for each. Ensures dimensions like `the_end` and `the_nether` that never see `ChunkEvent.Load` at boot get a regionizer up front. `regionizerFor` is idempotent (`computeIfAbsent`), so lazily-loaded worlds remain safe.
- **R.2** — `LevelTickDispatchProbes.noRegionizerSkip` gains a per-world `ConcurrentHashMap` sentinel: probe counter still bumps unconditionally, but `ViolationLogger.warn` fires exactly once per world (site = `"region-tick.no-regionizer-skip::" + worldId`). Removes the pre-fix log spam where every unloaded dim warned every tick, competing for a single rate-limit bucket.

### E — Pelican Panel / Pterodactyl egg

- **E.1** — New `pelican-egg.json` at repo root. PLCN_v1 schema. Startup command mirrors NeoForge's own `run.sh` (`java @user_jvm_args.txt @unix_args.txt nogui`). Install script downloads the MultiForge fork installer JAR from the latest release, runs `--installServer`, and symlinks the generated `libraries/net/neoforged/neoforge/<v>/unix_args.txt` to `/mnt/server/unix_args.txt`. 4 variables: `MULTIFORGE_VERSION` (default `latest`, resolves via GitHub API), `DOWNLOAD_URL` (template with `{VERSION}` substitution), `MC_VERSION` (display only, default `1.21.1`), `SERVER_JARFILE` (fallback name). No `LICENSE_KEY` — GPL-3, no gating.
- **E.2** — `docs/install.md` gains "Method 3 — Pelican Panel / Pterodactyl egg" section; `README.md` gains third install bullet.
- **E.3** — Release workflow attaches `pelican-egg.json` as a release asset; release-body template mentions it with a Method 3 link.

### C — Release CI + fallback

- **C.1** — New `build-fork-installer` job in `.github/workflows/release.yml` runs `./gradlew :setup :neoforge:applyMultiforgePatches :neoforge:signInstallerJar` and stages the output as `release/multiforge-<v>-installer.jar`. `continue-on-error: true` (fork build has been GHA-preempted historically); `gh-release` still publishes from `build-jars` alone if the fork job fails, via `if: always() && needs.build-jars.result == 'success'`.
- **C.2** — New `RELEASING.md` at repo root documents the maintainer's release flow: standard (tag → CI → verify), fallback if `build-fork-installer` failed (local `./gradlew :setup :neoforge:signInstallerJar` + `gh release upload --clobber`), post-release verification (`curl` the download URL, install into a temp dir, grep boot log), version bumping (gradle.properties in both outer + fork), and rollback.

### Also

- Version bump: `gradle.properties` and `upstream/neoforge-1.21.1/gradle.properties` both from 1.3.0 → 1.3.2 (v1.3.1's tag was cut without a version bump; catching up in the same commit).
- Install docs and README's "two ways" language corrected to "three ways" now that the Pelican egg is a documented method.

### Deferred past v1.3.2

- MC 1.21.2+ / Fabric support
- Top-20 mod compat matrix + 24h ATM10 soak
- `multiforge-client` gradle wiring via NeoForge `moddev` plugin
- Fork-compile CI stability if GHA still preempts even on ubuntu-24.04-large
- v1.3.0 GHCR package deletion (still user's UI action)

## v1.3.1 — M12 live-boot fix + Docker/GHCR removal

- **fix(m12):** `DispatchingEventBus.addListener(Consumer)` family broke NeoForge's ASM consumer-type introspection ("Failed to resolve consumer event type: RoutingListenerWrapper@…") because a plain wrapper class doesn't carry the invokedynamic lambda bootstrap NeoForge inspects. Fix: pass the raw Consumer through unwrapped for the addListener family; `@DispatchDomain` routing still applies to `@SubscribeEvent` methods registered via `register(Object)`. Unit tests couldn't catch this — surfaced only in live server boot.
- **chore:** removed Docker + GHCR support entirely. The published image was never wired to actually boot a server (bundled the installer but no server main class). Users install via Method 1 (fresh installer JAR) or Method 2 (drop-in replacement ZIP).
- **docs(install):** new `docs/install.md` guide covering both install methods with EULA, config, systemd, rollback, troubleshooting.
- **chore:** removed the `sync-downloads-repo` workflow (obsolete post-GPL-3).

## v1.3.0 — Full B3 (per-region entity/block-tick) + M12 (event routing)

The final two blueprint milestones needed for a completely working product: the entity-AI hot loop finally runs on region workers (the whole point of parallelization), and event dispatch honors `@DispatchDomain` transparently for every listener.

### B3 (M13) — Per-region entity, block-entity, and scheduled block/fluid tick wiring

Prior state: v1.2.0's `RegionizedTickCoordinator.dispatchLevelTick` ran the residual per-level tick body (`entityTickList.forEach(::tickNonPassenger)`, `blockEntityTickers.tick()`, `blockTicks.tick` / `fluidTicks.tick`, `chunkSource.tick`) on the main server thread via a trailing `vanillaBody.run()`. Region workers only handled chunk loading + globals. Full B3 pulls the residual work into per-region phase bodies and removes the trailing call.

- **B3.0** — Design doc `docs/design/m13-b3-region-tick.md` (819 lines) + correction to `docs/blueprint.md:512-516` (§M8 sub-step 6b was incorrectly marked DONE — 6b landed only the fan-out barrier, not the actual per-chunk decomposition).
- **B3.1** — Runtime foundation: `ChunkHolderManager.holdersOwnedBy(RegionId)`, `HolderManagerRegionData.blockEntityTickers` per-region slice, `TickingBlockEntityRef` MC-free abstraction, `Region.ownedChunkSnapshot()` via `RegionChunkSource` functional interface (avoids region↔chunk package cycle), split/merge redistribution invariance.
- **B3.2** — `BLOCK_FLUID_TICKS` phase wired: `ScheduledTickRunner` interface + `ScheduledTickRunnerBridge`; thin patches for `ServerLevel.mfTickBlockFluidTicksForChunk` + `LevelTicks.mfContainerForChunk` (per-chunk drain never touches neighbor's entries).
- **B3.3** — `ENTITY_AI` phase wired: `EntityTickRunner` + `EntityTickRunnerBridge`; `ServerLevel.mfTickEntitiesForChunk` extracts the `entityTickList.forEach` iteration; `OwnerToken` correctness guard warns + skips on wrong-owner; `MIGRATING`-state entities skipped.
- **B3.4** — `BLOCK_ENTITIES` per-region phase wired **layered after** the existing global-only `phaseGlobalSystemsTick`: `BlockEntityTickRunner` + `VanillaTickingBlockEntityAdapter` + `BlockEntityTickerBridge`; `Level.updateBlockEntityTicker` routes into the owning region's slice; `Level.tickBlockEntities` guarded to skip when regions handle it.
- **B3.5** — Removed trailing `vanillaBody.run()`. `dispatchLevelTick` refactored to the frozen target shape from `docs/design/global-region.md:773-793`. Three fallbacks (bootstrap-skip, no-regionizer-skip, dispatch-failure) now `ProbeRegistry.bump` + `ViolationLogger.warn` — visible, not silent (v1.2.0's naive removal `a4c6bd9` was reverted precisely because it was silent). `git grep vanillaBody upstream/neoforge-1.21.1/src/main/java/net/multiforge/` returns zero.

### M12 — Transparent event-bus routing

Prior state: `@DispatchDomain` + `@Ordering` annotations existed in `multiforge-api/` but `IEventBus.post` ignored them.

- **M12.0** — Design doc `docs/design/m12-event-routing.md` (697 lines). Discovered via `javap` decompile of `net.neoforged:bus:8.0.1` that `EventBus.registerListener` is private — so `DispatchingEventBus` does its own `@SubscribeEvent` reflection scan + `addListener` rather than intercepting the internal path.
- **M12.1** — Runtime dispatcher: `AnnotationScanner` (3-tier: method → class → EventTypeDomainMap → LEGACY_SERIAL), `DomainDispatcher` (full decision tree), `DispatchExecutor` (MC-free interface), `RoutingListenerWrapper`, `DispatchingEventBus` (implements `IEventBus`), `AsyncEventPool` (bounded, `-Dmultiforge.event-async-pool.size` configurable). Added `net.neoforged:bus:8.0.1` as explicit runtime dep.
- **M12.2** — Fork bridge: `LazyDispatchingEventBus` (extends dispatcher, lazy `attachExecutor` via CAS) so `NeoForge.EVENT_BUS` can be initialized at class-load before MultiForge is installed; `SchedulerBackedDispatchExecutor` implements `DispatchExecutor` over `MultiThreadedSchedulerHost` (`enqueueRegion` via `RegionizedTaskQueue.queueChunkTask`, `enqueueGlobal` mirrors host's own pattern, `enqueueAsync` forwards to `AsyncEventPool`, `resolveEventLocation` pattern-matches ~14 event base classes). Patch: `multiforge-patches/09-events/net/neoforged/neoforge/common/NeoForge.java.patch` (first patch targeting NeoForge's own hand-written source; small `multiforge-patches.gradle` infra fix to resolve per-patch apply-target directory). `-Dmultiforge.event-dispatch=off` safety valve. `MultiForgeGlobalSystemsInit.install()` attaches the executor at `ServerAboutToStart`.
- **M12.4** — `EventTypeDomainMap` — 32 default entries covering the highest-value NeoForge events (tick/lifecycle/spawn/death/interaction/chat/command/server-lifecycle). Class-hierarchy walk in `lookup()` handles subclasses without explicit entries.

### /67 round-6 findings from v1.2.0 — all already fixed

CRITICAL F1 (silent tick disable from initial B3 attempt) reverted as `7b68c27`. Six HIGH findings (add-then-check races in `addSettledListener` + `enqueueOutbound`, `BossEvent`/`Scoreboard` global-worker reentry, installer fail-open, scanner R03/R09 gaps, `Entity.onPositionChanged` double-fire) all fixed inline before v1.2.0 shipped.

### v1.2.0 addendum — the license flip

The v1.2.0 shape shipped a re-license from proprietary to GPL-3.0-only (`f91b732`) — omitted from the v1.2.0 changelog entry below. Full removal of `multiforge-license/`, `multiforge-license-cli/`, Ed25519 signing infrastructure, and every `MULTIFORGE_LICENSE` env/token reference. `CLAUDE.md` rule #1 flipped. `README.md` rewritten. Repo public on GitHub.

### Deferred past v1.3.0

- **X.4** client HUD manual smoke on live NeoForge client (needs live client)
- **X.1/X.2/X.3/X.8** actual bench evidence collection — scripts landed under `docs/verification/m456/`, operator runs the benches
- B3 + M12 live smoke on the operator's workstation (unit tests + fork compile green; live server run recommended before production use)
- `multiforge-client` gradle wiring via NeoForge `moddev` plugin
- Fork-compile CI GHA-preemption (advisory, needs beefier runner)
- Scanner CI empty-SARIF-on-exit-1 anomaly triage
- Top-20 mod compatibility matrix
- 24-hour ATM10 soak test
- MC 1.21.2+ / Fabric support
- OSS onboarding polish (CONTRIBUTING.md, CODE_OF_CONDUCT.md, SECURITY.md)

## v1.2.0 — M4 + M5 + M6 landing

### M4 — Entity migration (`multiforge-patches/05-entity-migration/`, `06-networking/`)

- **A1** — Runtime hardening: `EntitySnapshot.payload` moved from `String`-stub to `CompoundTag` NBT + `passengers: List<EntitySnapshot>` for whole-tree capture; `EntityRegistry.retiredRefs` cache (ConcurrentHashMap + `ScheduledExecutorService` 200-tick TTL) so stale-UUID cross-region tasks resolve deterministically to `RETIRED`; `MigratingEntityRef.beginPassengerTreeSnapshot` DFS + single-pass CAS with abort-and-restore on any failure; `EntityMigrationCoordinator.completeAt` refuses destination insert until target holder ≥ `BORDER` via new `ChunkHolderManager.scheduleWhenHolderAt`; `ProbeRegistry.recordMigration` hooks + violation emit on abort.
- **A2** — Vanilla `Entity.setPosRaw` / `onMove` / `teleportTo` / `changeDimension` hop patches; `PersistentEntitySectionManager.onMove` swaps to `MigratingEntityRef.updateLocation`; `ServerLevel.addFreshEntity` funnels through `EntityRegistry.register`; 500-mob cross-region stampede + 5-deep passenger stack stress test.
- **A3** — Networking: `ServerGamePacketListenerImpl.handleMovePlayer` triggers `beginMigration` on cross-region move (blocks Vanilla `setPos` while `MIGRATING`); `Connection.send` queues packets to `MigratingEntityRef.pendingOutbound` during migration, drained in FIFO order via new `addSettledListener`; `PlayerList.placeNewPlayer` wires `PlayerJoinCoordinator` Netty→global→spawn-chunk-region hop end-to-end.
- Correctness fix: `MigratingEntityRef.forceTerminalFromMigrating` (single-CAS `MIGRATING`→`RETIRED`) replaces the old two-step `abortMigration()`-then-`retire()` that could fire settled-listeners twice.

### M5 — Global subsystems (`multiforge-patches/08-globals/`)

- **B1** — `GlobalSystems.tickAll` wired at phase 4 (`BLOCK_ENTITIES`) of the synthetic global region's tick body.
- **B2 low-risk (5)** — `WeatherSystem`, `TimeSystem`, `WorldBorderSystem`, `ScoreboardSystem`, `BossEventSystem`. Wrap-and-rename pattern on each Vanilla `tick()`; `GlobalSystemsBridge.xxxReady()` early-return guard.
- **B2 high-risk (3)** — `RaidsSystem` (raider spawn routes via `EntityMigrationCoordinator.spawnInDestRegion`, never direct entity-list touch); `DragonFightSystem` (new `TicketType.DRAGON` pins end-podium chunks; cross-region effects for portal/egg drops); `CommandDispatchSystem` (single-region commands → caller's region; multi-region → global; cross-region `/tp` → A3's networking hop).

### M6 — Tooling

- **C1** — Client debug mod (`multiforge-client/`): `@Mod("multiforge_debug")` scaffold + 4 renderers (HUD, chunk-border, heatmap, pin) + 5 server-side emitters (heartbeat, region-map, violation, pin-list, TPS histogram). See [docs/design/client-debug-protocol.md](docs/design/client-debug-protocol.md).
- **C2** — Mod-safety scanner (`multiforge-scanner/`): ASM 9.7 bytecode walker with 12 rules (R01-R12, see [docs/design/scanner-rules.md](docs/design/scanner-rules.md)). JSON + SARIF v2.1.0 emitters. `.multiforgeignore` fingerprint suppression.
- **C3** — 8 new operator docs: [concurrency-contract](docs/concurrency-contract.md), [scheduler-api](docs/scheduler-api.md), [events](docs/events.md), [legacy-compat](docs/legacy-compat.md), [mod-porting](docs/mod-porting.md), [debugging-violations](docs/debugging-violations.md), [perf-tuning](docs/perf-tuning.md), [certification](docs/certification.md). New `/warn` and `/certify` commands.
- **C4** — Ops hardening: Docker healthcheck real (mcstatus server-list-ping, no `|| exit 0`); real OTEL exporter honoring `-Dmultiforge.otel.endpoint` (hand-rolled OTLP-HTTP, non-blocking on region worker); installer Ed25519 signature check + `--require-signed` flag (default `true` for release builds, fails closed on missing `.sig`); bench `:atm10` fetch helper; scanner CI workflow (`.github/workflows/scanner.yml`).

### /67 round-6 review — CRITICAL + 6 HIGH findings, all fixed

- **CRITICAL (fork B F1)** — reverted the initial B3 commit (`a4c6bd9`). Removing the trailing `vanillaBody.run()` silently disabled `entityTickList.forEach(this::tickNonPassenger)`, `blockEntityTickers.tick()`, scheduled block/fluid ticks, and `serverChunkCache.tick()` on the MultiForge-installed path. Region workers' `BLOCK_FLUID_TICKS` and `ENTITY_AI` phases have no production wiring yet (only tests set them). Full B3 (per-region entity/block-tick wiring) is deferred to a follow-up milestone.
- **HIGH (fork A)** — `addSettledListener` + `enqueueOutbound` add-then-check races. Fixed with re-check-after-add + `FiredOnceListener` at-most-once wrapper.
- **HIGH (fork B F2)** — `BossEventSystem`/`ScoreboardSystem` `tryRoute` deferred same-thread mutations to next tick's mailbox drain when caller was already on the global-region worker; Vanilla's return-after-mutate contract broken. Fixed via `GlobalRegionThreadMarker` ThreadLocal; same-thread reentry runs inline.
- **HIGH (fork C, 3)** — installer fails-open when `.sig` resource missing (fixed with `--require-signed` flag + `SignatureCheck` fail-closed); scanner R03 missed polymorphic Future receivers (fixed with per-scan `TypeHierarchy` index); R09 disk-I/O allowlist missed common idioms (fixed by extending to `read*/write*` prefixes across `java/io/*Stream` + `java/nio/channels/*Channel` + wrapped-stream cases).
- **HIGH (fork D)** — `Entity.onPositionChanged` fired twice per move-tick (both `setPos` post-call and `setPosRaw` post-call hunks bound the hook); dropped the outer hunk, kept the inner funnel.

### Phase X

- **X.5** — `:multiforge-scanner:test` green (12/12 rules, R07-R12 tests + report emitters + ignore-file).
- **X.6** — 8 new C3 docs pages linked from `docs/README.md`.
- **X.7** — /67 round-6 (4 lenses in parallel forks); findings fixed as listed above.
- **X.1/X.2/X.3/X.8** — bench-verification harness scaffolded under [docs/verification/m456/](docs/verification/m456/) + [multiforge-bench/verification/m456/](multiforge-bench/verification/m456/) with `--dry-run` smoke passing; actual bench evidence collection deferred to the operator per that README's "How to run" runbook (needs a live MC server + physical infrastructure).
- **X.4** — client HUD sanity requires a live NeoForge client; pending manual operator test.

### Deferred past v1.2.0

- Full B3: per-region entity + block/fluid-tick wiring into the `ENTITY_AI` / `BLOCK_FLUID_TICKS` scheduler phases so the trailing `vanillaBody.run()` can be removed cleanly.
- Phase X.1/X.2/X.3/X.8 actual bench evidence collection (scripts + docs are landed; runs are the operator's).
- Phase X.4 manual client HUD smoke test.
- `multiforge-client` gradle wiring via the NeoForge `moddev` plugin so `./gradlew :multiforge-client:compileJava` passes through the ordinary build (currently verified only by direct javac against the fork's built classes, per the module's own build.gradle.kts commentary).

## v1.1.1 — M9 chunk-system port (CI stabilization)

Follow-up: fork-compile CI job continuation flag; race fixes in AutoSaveRunner + PhasedRegionTickBody tests.

## v1.1.0 — M9 chunk-system port

Per-region `ChunkMap` / `DistanceManager` / `ThreadedLevelLightEngine` / `RegionFile` facades; `ChunkHolderManager` + `NewChunkHolder`; `AutoSaveRunner` + `RegionJournal` WAL; MCA I/O; Vanilla-parity semantic NBT diff.

## v1.0.0 — M8 scheduler landing

Regionized tick pipeline; `ThreadedRegionizer` + `TickRegionScheduler` + `PhasedRegionTickBody` + `RegionizedTaskQueue`; `RegionListener` merge/split hooks.

## v0.9.0-m9 → v1.0.0

M0–M8 foundational milestones.
