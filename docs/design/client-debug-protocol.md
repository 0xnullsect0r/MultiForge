# Client Debug Wire Protocol v1

**Status:** frozen (Phase 0 task 0.3). Track C1 (client mod scaffold) and
any server-side sender implementation land against this document;
changes require a Phase 0 amendment and a version bump per §8.

Cite convention: `file:line` refers to a snippet in the repo at the time
of freezing. `net.mf.rt.*` = `net.multiforge.runtime.*`; `net.mf.c.*` =
`net.multiforge.client.*`.

**Grounding sources** (read in full before this freeze):

- `multiforge-runtime/src/main/java/net/multiforge/runtime/diagnostics/wire/DebugPacketKind.java`
- `multiforge-runtime/src/main/java/net/multiforge/runtime/diagnostics/wire/DebugPacketCodec.java`
- `multiforge-runtime/src/main/java/net/multiforge/runtime/diagnostics/wire/DebugPayload.java`
- `multiforge-runtime/src/main/java/net/multiforge/runtime/diagnostics/ViolationLogger.java`
- `multiforge-runtime/src/main/java/net/multiforge/runtime/region/pin/RegionPinManager.java`
- `multiforge-client/src/main/java/net/multiforge/client/DebugChannelClient.java`
- `multiforge-client/src/main/java/net/multiforge/client/DebugHudState.java`
- `multiforge-client/src/test/java/net/multiforge/client/DebugChannelClientTest.java`

This document freezes the **wire contract**: channel id, packet kind
values, frame layout, payload schemas, cadence, subscription semantics,
and the permission gate. It does not freeze HUD rendering, F3 layout, or
color ramps — those are client-mod presentation concerns and may change
freely as long as they consume the frozen `DebugPayload` records.

---

## 1. Channel

- **Channel id:** `multiforge:debug/v1`. Defined today as
  `DebugChannelClient.CHANNEL_ID` (`DebugChannelClient.java:25`).
- **Transport:** a NeoForge custom payload channel (`IPayloadHandler` /
  `CustomPacketPayload`), registered on both the server and client
  network buses. The channel is **bidirectional**: server → client for
  HELLO / REGION_SNAPSHOT / HEATMAP_UPDATE / PIN_LIST / VIOLATION_EVENT,
  client → server for SUBSCRIBE.
- **Versioning is baked into the channel name**, not negotiated at the
  NeoForge channel-registration layer. A future incompatible wire change
  ships as `multiforge:debug/v2` registered alongside `v1` for one
  release cycle (see §8). Do not reuse `multiforge:debug/v1` for a
  breaking change.
- **Per-connection lifecycle:** the server opens the channel on player
  join (after login, before the player is added to the world — exact
  hook is a Track C1 implementation detail, not part of this freeze) and
  sends HELLO unconditionally, whether or not the client has the debug
  mod installed. A vanilla/NeoForge client without the mod silently
  drops the unknown payload channel per NeoForge's own channel
  negotiation; MultiForge does not special-case that path.
- The channel carries **only** the five packet kinds in §2. It is not a
  general-purpose RPC channel — do not multiplex unrelated traffic onto
  it.

---

## 2. Packet kinds

Frozen 1:1 with `DebugPacketKind` (`DebugPacketKind.java:30-52`). Do not
renumber existing values; new kinds append at the next free ID within
their direction's block (`0x06`–`0x0F` reserved for future
server→client kinds, `0x11`–`0x1F` reserved for future client→server
kinds).

| Kind | Wire ID | Direction | Purpose |
|---|---|---|---|
| `HELLO` | `0x01` | server → client | Handshake: protocol version, server tick rate, build label. |
| `REGION_SNAPSHOT` | `0x02` | server → client | Per-region metadata: id, section count, MSPT p50/p95, owned entity count. Drives the F3 overlay and chunk-border colouring. |
| `HEATMAP_UPDATE` | `0x03` | server → client | Per-chunk MSPT delta within the client's view radius. Powers the tick-cost heatmap overlay. |
| `PIN_LIST` | `0x04` | server → client | Full snapshot of every operator-created region pin from `RegionPinManager`, so the client can draw selection boxes. |
| `VIOLATION_EVENT` | `0x05` | server → client | One ownership-violation / reroute event for the live side panel, sourced from `ViolationLogger`. |
| `SUBSCRIBE` | `0x10` | client → server | Per-viewer subscription bitmask: which of the four push streams above this client wants. |

Notes on the ID layout:

- Server→client kinds occupy the low block (`0x01`–`0x0F`); client→server
  kinds start at `0x10`. This is deliberate so a decoder can sanity-check
  direction from the ID's high nibble without a lookup table, even though
  today's `DebugPacketCodec` does not enforce it (see §9 for the
  invariant that *is* enforced: unknown-kind rejection).
- `0x06`–`0x0F` and `0x11`–`0x1F` are reserved, not merely unused. A
  future packet kind added under `multiforge:debug/v1` (a
  backward-compatible addition per §8) must draw from these ranges.

---

## 3. Cadence

| Stream | Cadence | Trigger |
|---|---|---|
| `HELLO` | once per connection | Sent immediately when the channel opens for a joining player. Never repeated on that connection. |
| `REGION_SNAPSHOT` | 4 Hz (every 250 ms) | Server-side `ScheduledExecutorService` heartbeat tick, gated per-viewer by the subscription mask (§5). |
| `HEATMAP_UPDATE` | 4 Hz (every 250 ms) | Same 250 ms heartbeat tick as `REGION_SNAPSHOT`; independently gated by its own subscription bit so a client can take snapshots without the heatmap or vice versa. |
| `PIN_LIST` | event-driven | Sent once on subscribe (if `F_PINS` set) and again whenever `RegionPinManager.add`/`remove`/`save` changes the pin set. Not part of the 250 ms tick — a static pin list should not cost bandwidth every quarter-second. |
| `VIOLATION_EVENT` | event-driven | One frame per `ViolationLogger.warn(...)` call site that actually fires (i.e. one frame per emitted WARN, not per rate-limiter-suppressed call — see §9 and `ViolationLogger.java:61-75`). Fan-out to every subscribed viewer. |
| `SUBSCRIBE` | client-initiated, any time | Sent on connect (to establish the viewer's non-zero mask; see §5) and again whenever the player toggles overlays client-side. No rate limit is placed on this packet by the protocol, but the server MAY apply a generic per-connection payload-channel flood guard as part of its normal NeoForge networking hygiene — that guard is out of scope for this document. |

Rationale for 4 Hz: matches the existing HUD refresh cadence used
elsewhere in the client debug tooling and keeps `REGION_SNAPSHOT` +
`HEATMAP_UPDATE` well under one packet per tick (20 Hz) so region-worker
threads never block on network serialization inside the hot tick path —
see Ground rule 4 in `CLAUDE.md`. The heartbeat runs on a dedicated
`ScheduledExecutorService`, not on any region worker thread; it reads
already-published snapshot state (the same kind of `volatile`/atomic
publish pattern used throughout `NewChunkHolder`, see
`docs/design/m9-contracts.md` §1.1) and never invokes `.get()` on a
region-owned future.

---

## 4. Frame format

Verbatim from `DebugPacketCodec` (`DebugPacketCodec.java:17-30`, `166-223`),
length-prefixed, big-endian:

```
byte    kind        (unsigned, one of the wire IDs in §2)
int32   bodyLength   (signed, big-endian, 0 <= bodyLength <= MAX_FRAME_BYTES)
byte[]  body         (bodyLength bytes, kind-specific — see §7)
```

- **`kind`** is read as `in.readByte() & 0xFF` then resolved through
  `DebugPacketKind.fromWireId(int)`. An unrecognized value throws
  `IllegalArgumentException("Unknown debug packet kind: " + id)` —
  callers of `readFrame` must not let this propagate uncaught into a
  network thread (see §9).
- **`bodyLength`** is a signed `int32`. `readFrame` rejects it if
  negative, greater than `MAX_FRAME_BYTES` (`1 << 20` = 1 MiB), or if it
  does not exactly match the remaining bytes in the raw frame
  (`bodyLen != raw.length - 5`). Frames shorter than 5 bytes are
  rejected outright (`DebugPacketCodec.java:167`).
- **`body`** is opaque to the framing layer; each packet kind defines
  its own internal layout (§7), written with a plain `DataOutputStream`
  (no further length-prefixing at the frame level beyond `bodyLength`
  itself).
- **Strings** inside a body use a `uint16` length prefix (`writeShort`)
  followed by that many UTF-8 bytes (`DebugPacketCodec.java:206-217`).
  Encoding throws if the UTF-8 byte length exceeds `65535`; there is no
  provision for strings longer than that anywhere in this protocol.
- **Lists** inside a body (region list, heat list, pin list) are encoded
  as an `int32` count followed by that many fixed-shape elements —
  there is no per-element length prefix; the element shape is fixed by
  the packet kind. Decoders bound the count against a kind-specific
  ceiling before allocating (`requireLen`, `DebugPacketCodec.java:220-222`)
  — see §7 for the ceiling per kind.
- **Numeric types** map directly onto `DataOutputStream`/`DataInputStream`
  primitives: `int32` via `writeInt`/`readInt`, `int64` via
  `writeLong`/`readLong`, `float32` via `writeFloat`/`readFloat`,
  `float64` (double) via `writeDouble`/`readDouble`. All are big-endian,
  which is the fixed byte order of `java.io.Data{Input,Output}Stream`.
  There is no varint or zigzag encoding anywhere in this protocol —
  every integer field costs its full fixed width on the wire. This is a
  deliberate simplicity-over-bandwidth tradeoff appropriate to a 4 Hz
  debug/diagnostic channel, not a hot gameplay path.

`MAX_FRAME_BYTES` (1 MiB) is a hard ceiling shared by every kind; it
exists so a malicious or buggy peer cannot force an unbounded
allocation from a single frame (`DebugPacketCodec.java:27-28, 171-172`).
It is deliberately generous relative to any single frame this protocol
actually produces today (§7's per-kind ceilings all resolve to frames
far under 1 MiB even at their count ceiling) — it is a safety backstop,
not a target size.

---

## 5. Per-viewer subscription mask

- The mask is an `int32` bitmask carried in the `SUBSCRIBE` packet body
  (`DebugPayload.Subscribe`, `DebugPayload.java:77-86`).
- **Default is `0`** — a freshly connected viewer is subscribed to
  nothing. The server sends `HELLO` unconditionally (it is not gated by
  the mask — see §3) but must not push any of `REGION_SNAPSHOT`,
  `HEATMAP_UPDATE`, `PIN_LIST`, or `VIOLATION_EVENT` to a connection
  until that connection has sent a `SUBSCRIBE` with the corresponding
  bit set. This is a hard server-side invariant, not a bandwidth
  optimization — see §9.
- Bits, frozen from `DebugPayload.Subscribe` (`DebugPayload.java:78-81`):

  | Bit | Value | Gates |
  |---|---|---|
  | `F_REGIONS` | `0x01` | `REGION_SNAPSHOT` |
  | `F_HEATMAP` | `0x02` | `HEATMAP_UPDATE` |
  | `F_PINS` | `0x04` | `PIN_LIST` |
  | `F_VIOLATIONS` | `0x08` | `VIOLATION_EVENT` |

  Bits `0x10` and above are reserved for future streams; a server
  receiving an unrecognized bit set MUST ignore that bit (mask it off)
  rather than reject the whole `SUBSCRIBE` — this keeps a newer client
  talking to an older server forward-compatible per §8, at the cost of
  the newer stream silently not being delivered.
- `Subscribe.wants(int flag)` (`DebugPayload.java:83-85`) is the
  canonical single-bit test: `(flags & flag) == flag`. Server-side
  fan-out logic must use this exact test (or an equivalent bitwise AND)
  per stream, independently — a viewer may subscribe to any subset, in
  any combination, and the four bits are orthogonal.
- **Re-sending `SUBSCRIBE` replaces the mask**, it does not OR into the
  previous value. A client that wants to add a stream while keeping
  existing ones must include all previously-set bits it still wants in
  the new `SUBSCRIBE` frame. (This matches `Subscribe` being a plain
  single-`int` record with no accumulation semantics anywhere in
  `DebugPacketCodec` or `DebugPayload`.)
- On subscribe (mask transitions from not-wanting to wanting a given
  bit), the server sends **one immediate frame** of that kind so the
  client isn't left waiting up to 250 ms (for the ticked streams) or
  indefinitely (for `PIN_LIST`, which has no periodic re-send — see
  §3) to populate its view. This applies per-bit, independently, at the
  moment each bit transitions on.
- On disconnect, the server discards the viewer's mask; there is no
  persistence of subscription state across reconnects. A reconnecting
  client starts at mask `0` and must re-`SUBSCRIBE`.

---

## 6. Permission node

- **Node:** `multiforge.debug.view`.
- **Enforcement point:** exclusively the server's `SUBSCRIBE` handler.
  A connection that has not passed the permission check is treated
  exactly as if it had never sent `SUBSCRIBE` — its mask stays `0` and
  it receives nothing beyond `HELLO`.
- **Failure behavior:** per Ground rule 5 in `CLAUDE.md` ("auto-reroute
  + warn is the default... never throw from a mod's code path"), a
  `SUBSCRIBE` from a player lacking `multiforge.debug.view` is **not**
  an error the server reports back over the channel — there is no
  NACK/error packet kind in this protocol (see §2; nothing occupies
  that role). The server silently declines to raise the mask above `0`
  and logs a rate-limited server-side note (reusing the
  `ViolationLogger` rate-limiting shape from
  `ViolationLogger.java:61-75`, keyed per-player, is the intended
  pattern — not routed through `ViolationLogger` itself, since a
  missing permission is not an ownership violation, but the same
  token-bucket discipline applies so a client hammering `SUBSCRIBE`
  cannot flood the server log).
- **Default grant:** consistent with NeoForge's own permission
  convention (see `NeoForgeMod.USE_SELECTORS_PERMISSION`,
  `upstream/neoforge-1.21.1/.../NeoForgeMod.java:658-659`, which
  defaults to `Commands.LEVEL_GAMEMASTERS`), `multiforge.debug.view`
  defaults to **operator level 2** (`Commands.LEVEL_GAMEMASTERS`) absent
  a permission-mod override. Servers running a full permission plugin
  (LuckPerms et al.) can grant it independently of op status.
- **Re-check cadence:** the permission is re-evaluated on every
  `SUBSCRIBE` frame, not cached for the life of the connection. If an
  operator's permission is revoked mid-session, the next `SUBSCRIBE`
  they send (e.g. from toggling an overlay) re-evaluates and can zero
  their mask; the server does not proactively push a mask reset on
  permission revocation (no such push exists in this protocol).
- **Scope:** the permission gates subscribing (i.e. receiving data). It
  does not gate opening the channel itself — `HELLO` is unconditional
  (§3, §5) since it carries no privileged information (protocol version
  and tick rate are not secrets), and the client mod uses `HELLO` to
  decide whether to render debug UI affordances at all before the
  player has necessarily been granted `multiforge.debug.view`.

---

## 7. Payload schemas

All field names/types below are frozen to match the `DebugPayload`
records (`DebugPayload.java`) and their encode/decode pairs in
`DebugPacketCodec`. "u8/u16/u32/i64/f32/f64/string/list&lt;T&gt;" notation:
unsigned widths note wire representation; Java-side these are read into
signed types per §4 (there is no unsigned integer type in this
protocol's Java representation — `bodyLength`, list counts, etc. are all
signed `int32`s that happen to only ever carry non-negative values, with
`requireLen`/range checks enforcing that at decode time).

### 7.1 `HELLO` (0x01) — `DebugPayload.Hello`

Source: `DebugPacketCodec.encodeHello`/`decodeHello`
(`DebugPacketCodec.java:38-53`), record at `DebugPayload.java:21-26`.

| Field | Wire type | Meaning |
|---|---|---|
| `protocolVersion` | i32 | This document's version — currently `DebugPacketCodec.PROTOCOL_VERSION = 1`. See §8. |
| `tickHz` | i32 | Server's configured tick rate (ticks/sec), for the client to reason about staleness of subsequent snapshots. |
| `buildLabel` | string | Free-form server build identifier. Bounded to 256 UTF-16 chars by the record's compact constructor (`DebugPayload.java:23-24`); the wire string-length ceiling is a separate, looser 65535-UTF-8-byte cap from §4. |

### 7.2 `REGION_SNAPSHOT` (0x02) — `DebugPayload.RegionSnapshot`

Source: `DebugPacketCodec.encodeRegionSnapshot`/`decodeRegionSnapshot`
(`DebugPacketCodec.java:55-81`), records at `DebugPayload.java:29-37`.

| Field | Wire type | Meaning |
|---|---|---|
| `tick` | i64 | Server tick counter at snapshot time. |
| `regionCount` | i32 | Count of `RegionStat` entries that follow. Ceiling: **65536** (`requireLen(n, 65536, "region count")`, `DebugPacketCodec.java:73`). |
| `regions[]` | list\<RegionStat\> | One entry per live region. |

Each `RegionStat` element:

| Field | Wire type | Meaning |
|---|---|---|
| `regionId` | i64 | Stable identifier of the region. |
| `sectionCount` | i32 | Number of loaded chunk sections owned by the region. |
| `msptP50` | f64 | Median tick duration, milliseconds. |
| `msptP95` | f64 | 95th-percentile tick duration, milliseconds. |
| `ownedEntities` | i32 | Entity count currently owned by the region. |

### 7.3 `HEATMAP_UPDATE` (0x03) — `DebugPayload.HeatmapUpdate`

Source: `DebugPacketCodec.encodeHeatmap`/`decodeHeatmap`
(`DebugPacketCodec.java:83-106`), records at `DebugPayload.java:40-49`.

| Field | Wire type | Meaning |
|---|---|---|
| `worldId` | string | Dimension key the heat samples belong to (e.g. `minecraft:overworld`). |
| `heatCount` | i32 | Count of `ChunkHeat` entries that follow. Ceiling: **65536** (`1 << 16`, `DebugPacketCodec.java:99`). |
| `heats[]` | list\<ChunkHeat\> | Per-chunk samples, scoped to the client's view radius (server-side filtering — the wire format itself carries no radius field; the sender decides which chunks to include). |

Each `ChunkHeat` element:

| Field | Wire type | Meaning |
|---|---|---|
| `chunkX` | i32 | Chunk-grid X coordinate. |
| `chunkZ` | i32 | Chunk-grid Z coordinate. |
| `heatMspt` | f32 | Tick-cost delta attributed to this chunk, milliseconds. `float32`, not `double` — the only `f32` field in this protocol; chosen for this high-cardinality, per-chunk stream to roughly halve heatmap frame size relative to `double`. |

### 7.4 `PIN_LIST` (0x04) — `DebugPayload.PinList`

Source: `DebugPacketCodec.encodePinList`/`decodePinList`
(`DebugPacketCodec.java:108-134`), records at `DebugPayload.java:52-65`.

| Field | Wire type | Meaning |
|---|---|---|
| `pinCount` | i32 | Count of `PinBox` entries that follow. Ceiling: **4096** (`1 << 12`, `DebugPacketCodec.java:125`) — pins are operator-authored and few by nature. |
| `pins[]` | list\<PinBox\> | Every pin currently held by `RegionPinManager`, full snapshot (not a delta). |

Each `PinBox` element:

| Field | Wire type | Meaning |
|---|---|---|
| `id` | string | Pin identifier, unique per `RegionPinManager` (see `RegionPinManager.add`, which throws on a duplicate id). |
| `worldId` | string | Dimension key the pin belongs to. |
| `fromChunkX` | i32 | Inclusive chunk-grid X lower bound. |
| `fromChunkZ` | i32 | Inclusive chunk-grid Z lower bound. |
| `toChunkX` | i32 | Inclusive chunk-grid X upper bound. |
| `toChunkZ` | i32 | Inclusive chunk-grid Z upper bound. |

### 7.5 `VIOLATION_EVENT` (0x05) — `DebugPayload.ViolationEvent`

Source: `DebugPacketCodec.encodeViolation`/`decodeViolation`
(`DebugPacketCodec.java:136-153`), records at `DebugPayload.java:68-74`.

| Field | Wire type | Meaning |
|---|---|---|
| `epochMillis` | i64 | Wall-clock time the violation was recorded. |
| `modId` | string | Offending mod's id, or an empty/sentinel value when the underlying `ViolationLogger.warn` call was the site-scoped overload (`modId == null` case, `ViolationLogger.java:52-53`) — the wire has no null representation, so the server-side sender must substitute a defined sentinel (e.g. empty string) rather than encode a null `modId`. |
| `site` | string | Call-site identifier of the reroute/warn. |
| `detail` | string | Human-readable detail message. |

This packet carries **one event per frame** — there is no batched/list
form. A burst of violations produces a burst of frames, one each,
naturally throttled upstream by `ViolationLogger`'s own token bucket
(5/min per key by default, `ViolationLogger.java:24-25`) before a
`warn()` call even reaches the point where a frame would be sent — see
§9 for the exact fan-out rule.

### 7.6 `SUBSCRIBE` (0x10) — `DebugPayload.Subscribe`

Source: `DebugPacketCodec.encodeSubscribe`/`decodeSubscribe`
(`DebugPacketCodec.java:155-163`), record at `DebugPayload.java:77-86`.

| Field | Wire type | Meaning |
|---|---|---|
| `flags` | i32 | Bitmask per §5. No list, no string — the entire body is 4 bytes. |

---

## 8. Versioning and forward compatibility

- `DebugPacketCodec.PROTOCOL_VERSION` (currently `1`) is carried in
  every `HELLO` (§7.1) and is the single source of truth for "what
  version does this server speak." There is no separate per-packet
  version field — versioning is whole-protocol, not per-kind.
- **Client obligation:** a client mod MUST read `HELLO.protocolVersion`
  before sending `SUBSCRIBE` and MUST NOT send `SUBSCRIBE` (or assume
  any stream will arrive) if it does not understand that version.
- **Server obligation:** the server defines a `MIN_SUPPORTED_PROTOCOL`
  floor (not yet a named constant in `DebugPacketCodec` as of this
  freeze — Track C1 introduces it alongside the first server-side
  sender). On receiving `SUBSCRIBE`, if the connection's advertised
  client protocol version (learned out-of-band, e.g. via a client-echo
  mechanism Track C1 defines, or conservatively assumed to be the
  client's own compiled-against `PROTOCOL_VERSION` when no echo exists)
  is below that floor, the server refuses to raise the mask above `0`
  — identical failure shape to a permission failure (§6): silent from
  the wire's perspective, logged rate-limited server-side. This
  protocol does not mandate how the server learns the client's version;
  it only mandates the refusal behavior once a below-floor version is
  known.
- **Backward-compatible changes** (allowed within `multiforge:debug/v1`
  without a channel rename):
  - Adding a new packet kind at a reserved ID (§2).
  - Adding a new bit to the `SUBSCRIBE` mask (§5) — old servers ignore
    unknown bits sent by a newer client; old clients simply never set
    a bit they don't know about.
  - Widening a list-count ceiling (`requireLen` bounds) upward. Never
    narrow one without a version bump — a narrower ceiling can turn a
    previously-valid frame into a rejected one.
- **Breaking changes** (require `multiforge:debug/v2` per §1, plus a
  `PROTOCOL_VERSION` bump so `HELLO` self-reports correctly on the new
  channel):
  - Changing a field's wire type or position within an existing kind's
    body.
  - Removing or renumbering an existing `DebugPacketKind` value.
  - Changing the frame header shape (§4) itself.
  - Changing `SUBSCRIBE` from replace-mask to accumulate-mask semantics,
    or any other change to §5's core contract.
- **Deprecation window:** when `v2` ships, `v1` stays registered and
  functional for at least one MultiForge minor release so mixed
  server/client-mod-version fleets keep working; `v1`'s removal is
  itself a breaking change subject to the same amendment process as
  this document.

---

## 9. Test invariants

These are the behavioral guarantees this freeze commits to. Track C1
and any server-side sender implementation must have tests pinning each
one; the client-side subset is already partially covered by
`DebugChannelClientTest`.

1. **Decoder rejects unknown kinds without crashing the caller.**
   `DebugPacketKind.fromWireId` throws `IllegalArgumentException` for an
   unrecognized wire ID (`DebugPacketKind.java:48-51`); `readFrame`
   lets that propagate (`DebugPacketCodec.java:169`). Any code that
   calls `readFrame` on network-attacker-controlled bytes — both the
   client's `IPayloadHandler` and any future server-side receiver —
   MUST catch `IOException`/`IllegalArgumentException`/
   `UncheckedIOException` around the call and drop the frame rather
   than let the exception unwind into NeoForge's network thread. This
   is a decode-time contract, not (only) a unit-test assertion:
   `DebugChannelClient.onFrame` currently declares `throws IOException`
   and does not itself catch `IllegalArgumentException` from a
   corrupt-kind byte — the *caller* wiring `onFrame` into the NeoForge
   `IPayloadHandler` (Track C1's job) is responsible for the
   catch-and-drop, matching Ground rule 5 (never throw from a mod's
   code path — reroute and warn instead).
2. **Frame-length mismatches are rejected, not silently truncated or
   over-read.** `bodyLen != raw.length - 5` throws `IOException`
   (`DebugPacketCodec.java:173`); a frame under 5 bytes throws
   immediately (`DebugPacketCodec.java:167`); `bodyLen` outside
   `[0, MAX_FRAME_BYTES]` throws (`DebugPacketCodec.java:171-172`).
3. **Per-kind list ceilings are enforced at decode time**, independent
   of the shared 1 MiB frame ceiling: region count ≤ 65536, heat count
   ≤ 65536, pin count ≤ 4096 (§7.2–§7.4). A count outside range throws
   `IOException` before any element allocation (`requireLen`,
   `DebugPacketCodec.java:220-222`) — this is what prevents a forged
   `bodyLength`-consistent-but-huge count field from driving an
   oversized `ArrayList` allocation.
4. **Round-trip fidelity.** For every kind, `decode(encode(x))` equals
   `x` field-for-field. `DebugChannelClientTest` already exercises this
   for `HELLO`, `REGION_SNAPSHOT` (via `f3Lines()` derived assertions),
   `VIOLATION_EVENT`, `PIN_LIST`, `HEATMAP_UPDATE`, and `SUBSCRIBE`
   (`DebugChannelClientTest.java:17-88`). Any new field added to a
   payload record must extend both the encode and decode sides and get
   a round-trip assertion in the same style.
5. **Server never sends a gated stream to an unsubscribed viewer.**
   For each of `REGION_SNAPSHOT`, `HEATMAP_UPDATE`, `PIN_LIST`,
   `VIOLATION_EVENT`, the server-side fan-out must test
   `Subscribe.wants(bit)` (§5) for that specific viewer's last-received
   mask before serializing and sending a frame to them — not merely
   before rendering client-side. A default (never-subscribed) viewer
   with mask `0` must receive zero frames of any of these four kinds,
   ever, across the life of the connection, until they send a
   `SUBSCRIBE` with a non-zero relevant bit.
6. **`SUBSCRIBE` replaces, not merges.** A second `SUBSCRIBE` frame with
   a different mask fully replaces the viewer's prior mask (§5) — test
   that subscribing to `F_REGIONS`, then to `F_HEATMAP` alone, leaves
   the viewer receiving `HEATMAP_UPDATE` but no longer `REGION_SNAPSHOT`.
7. **Permission check fires on every `SUBSCRIBE`, not just the first.**
   The server-side `SUBSCRIBE` handler must call the
   `multiforge.debug.view` permission check (§6) on each received
   `SUBSCRIBE` frame, not cache a "checked once, trust forever" flag
   per connection. Test: a player without the permission sends
   `SUBSCRIBE`, receives no gated frames; granting the permission then
   having the player re-send `SUBSCRIBE` must start delivery; revoking
   and re-sending must stop it again.
8. **`SUBSCRIBE` from a player lacking `multiforge.debug.view` never
   throws and never disconnects them.** Consistent with Ground rule 5 —
   test that the handler completes normally (mask stays/returns to `0`,
   a rate-limited log line is emitted, no exception escapes).
9. **Protocol-version floor is enforced before any gated stream is
   sent**, independent of and in addition to the permission check (§8,
   §6) — a permitted player on a too-old client protocol version still
   receives nothing beyond `HELLO`.
10. **`ViolationEvent` fan-out matches `ViolationLogger`'s own
    rate limiting** — a burst of `ViolationLogger.warn` calls that the
    logger itself suppresses (beyond the per-key token bucket, default
    5/min, `ViolationLogger.java:24-25, 61-75`) must not produce
    additional `VIOLATION_EVENT` frames beyond what actually logged.
    This is naturally satisfied if the server-side sender is driven
    directly off `ViolationLogger`'s own emission point rather than a
    separate un-rate-limited hook into the underlying violation source
    — test that this wiring choice holds as the sender is implemented.
11. **Unknown `SUBSCRIBE` bits are masked off, not rejected.** A
    `SUBSCRIBE` frame with bits set above `0x08` (§5) must not error;
    the server applies only the bits it recognizes and (per invariant
    6) still fully replaces the previously known bits.

---

## 10. Non-goals of this freeze

Explicitly out of scope for `docs/design/client-debug-protocol.md`,
left to Track C1 or later:

- HUD layout, F3 line formatting, heatmap color ramp, or any rendering
  concern downstream of `DebugHudState` (`DebugHudState.java`).
- The exact NeoForge registration boilerplate (`IPayloadHandler`
  registration, `CustomPacketPayload.Type` wiring) — this is standard
  NeoForge networking API usage, not a MultiForge-specific contract.
- The concrete `MIN_SUPPORTED_PROTOCOL` constant and how a client's
  protocol version is learned out-of-band (§8) — named and wired by
  Track C1.
- Compression of frames at the NeoForge/Netty layer below this
  protocol — NeoForge's own payload channel transport may or may not
  compress; this document only specifies the logical byte layout
  `DebugPacketCodec` produces before any transport-level framing.
