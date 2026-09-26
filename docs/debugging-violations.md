# Debugging Violations Manual

An **ownership violation** is a world write made from a thread that does
not own the chunk being written: a region worker writing into another
region's chunk, or a thread that is not a region worker or the server
thread (the async pool, a Netty thread, a mod's own thread) writing
anywhere. Per CLAUDE.md rule 5, MultiForge does not refuse such code: by
default it reroutes the write to the chunk's owner and logs a rate-limited
warning. This page is about finding and fixing the *cause* of those
warnings. The mechanism itself is described in
[`concurrency-contract.md`](concurrency-contract.md); the tick model in
[`design/barrier-tick-model.md`](design/barrier-tick-model.md).

## Reading `ViolationLogger` output

Every warning goes through
`net.multiforge.runtime.diagnostics.ViolationLogger` (logger name
`multiforge.violation`). The log line is:

```
[<site>] <detail>
```

The two ownership warnings look like this:

```
[Level.setBlock] cross-region mutation of chunk [12, -4] in minecraft:overworld from region 7 (owner=3) — rerouted to owner
[Level.setBlock] off-thread mutation attempt from 'Netty Server IO #3' (domain=UNKNOWN)
```

`owner=none` means no region owns the chunk (it is not loaded, or not yet
indexed); the write then goes to the server thread.

**Rate limit.** Each site has a budget of `warnPerMin` warnings per
60-second window (default 5, `[violations] warnPerMin` in
`config/multiforge-server.toml`; `-Dmultiforge.violations.warn-per-min=<n>`
overrides it). Within a window the first `warnPerMin` calls are logged in
full, the next one is logged with `(further identical messages suppressed
for 60s)` appended, and the rest are silent. `0` silences every warning,
and `[violations] policy = "reroute-only"` sets the budget to 0. The probe
counters (below) still count every occurrence.

`ViolationLogger` also supports a per-mod budget keyed on
`modId::site`, but no call site in the runtime or the fork currently
passes a mod id, so every warning is site-scoped and shows `[-]` as its
mod in `/multiforge warn list`.

### `/multiforge warn`

```
/multiforge warn list
Recent violations (2, oldest first):
  2026-09-06T14:02:11Z [-] Level.setBlock: cross-region mutation of chunk [12, -4] in minecraft:overworld from region 7 (owner=3) — rerouted to owner
  2026-09-06T14:05:03Z [-] region-tick.overrun: region 3 tick body took 612ms (threshold 500ms, 0ms of designed waits not counted) — likely a blocking wait or a region that needs to split
```

`warn list` shows the last 200 warnings that were actually logged (a call
silenced by the rate limiter is not recorded). `warn clear` empties that
list; it does not reset the rate-limit budgets.

### Probe counters

Most sites also bump a `ProbeRegistry` counter on every occurrence,
whether or not the warning was logged. `/multiforge probes` lists all of
them; `/multiforge probes <prefix>` filters, e.g. `/multiforge probes Level.setBlock`.

## What each site means

| Site / probe | Meaning | What to do |
|---|---|---|
| `<site>:cross-region` (probe), warning `[<site>] cross-region mutation …` | Code running on a region worker wrote into a chunk another region owns. The write was queued on the owner's mailbox and runs at its next drain. Typical causes: a block entity or entity that edits blocks far away, a mod holding a reference to a distant block entity. | Often harmless: the reroute preserves the write. If it is frequent or the caller depends on the write having happened immediately, move the work to the owner with `ServerDomains.region(world, chunkPos)` (`docs/api.md`). |
| `<site>:off-thread` (probe), warning `[<site>] off-thread mutation attempt …` | A thread that is neither a region worker nor the server thread wrote to the world: a `ServerDomains.async()` task, a Netty handler that skipped `enqueueWork`, a mod's own executor. | Do the write on the owner: `ServerDomains.region(...)`, or `IPayloadContext.enqueueWork` in packet handlers (`docs/mod-porting.md`). |
| `reroute.<site>.mismatch` (probe), warning `[<site>.mismatch]` | A rerouted `Level.setBlock` or `ServerLevel.addFreshEntity` returned a predicted result to its caller, and the owner's actual result differed (the target changed in between). | The caller saw the wrong return value once. Fix the underlying cross-region write if it matters. |
| `<site>:deferred-cross-region`, `<site>:deferred-player-dimension-change`, `<site>:deferred-to-server-thread` (probes only) | A teleport into another region, a player dimension change, or a command/function run on a region worker was deferred to the server thread. Not a violation. | Nothing; informational. |
| `region-tick.overrun` (probe + warning) | One region's tick body took longer than the watchdog threshold (default 500 ms, `-Dmultiforge.watchdog.warn-ms`), not counting designed waits. | Usually a blocking call or a very busy region. Check `/multiforge warn list` for off-thread warnings around the same time, `/multiforge region list` for region sizes, and a thread dump. |
| `region-tick.dispatch.overrun` (probe + warning) | At a level's barrier, some regions had not finished within the dispatch deadline (default 500 ms, `-Dmultiforge.regiontick.dispatch-ms`), not counting designed waits. The server thread still waits for them. | As above. |
| `region-tick.wait-ms.<kind>` (probe) | Total milliseconds region workers spent in designed waits: `main-thread-chunk-load` (a worker waiting for the server thread to load a chunk) and `serial-lane` (a worker waiting for a listener on the serial lane). | High `serial-lane` time: a `legacy` or `hybrid-safe` mod's listeners run on hot events; see `docs/events.md`. High `main-thread-chunk-load` time: regions touching unloaded chunks. |
| `region.main-thread-chunk-load` (probe) | Count of worker chunk loads handed to the server thread (`MainThreadHandoff`). | As above. |
| `serial-lane.handoff`, `event.dispatch.{inline,serial,async,global}`, `event.dispatch.async.overflow` (probes) | Event-dispatch routing counts (`docs/events.md`). `async.overflow` also warns: the async listener queue was full and the oldest pending task was dropped. | Informational, except `async.overflow`. |
| `entity-ai.wrong-owner`, `block-entities.phase.wrong-owner` (probe + warning) | A region's entity or block-entity phase was invoked under another region's token and skipped. Should never happen. | A MultiForge bug; report it with the log. |
| `region-tick.block-fluid.off-thread`, `block-entities.ticker-add.failure` (probe + warning) | Internal guards in the fork's tick bridges. | A MultiForge bug; report it. |

Scanner findings (`docs/certification.md`) complement these runtime
signals: R03 (`blocking-future`) and R09 (`sync-io-in-tick`) find blocking
calls that show up at runtime only as overruns, and R04
(`unsync-static-mutation`) finds unsynchronised static state, which has no
runtime detector at all.

## Strict mode for regression runs

Strict mode turns every ownership violation and every region-tick overrun
into an exception:

| How | Effect |
|---|---|
| `[mtserver] mode = "strict"` in `config/multiforge-server.toml`, `/multiforge config mode strict`, or `-Dmultiforge.mode=strict` | Ownership violations throw `OwnershipViolationException`; `region-tick.overrun` throws `RegionTickOverrunException`; `region-tick.dispatch.overrun` throws `RegionDispatchOverrunException`. |
| `[violations] policy = "fail"` / `/multiforge config policy fail` | Ownership violations throw; overruns still only warn. |

An exception on a region worker is rethrown on the server thread once the
barrier completes, where Vanilla's "Exception ticking world" handling
stops the server. A violation on some other thread (a Netty handler, a
mod's executor) throws in that thread. Use strict mode on a test server
to find the exact call chain; never run production this way.

`-Dmultiforge.ownership.mode` sets `OwnershipEnforcer`'s initial mode, but
a server overwrites it from the config file at startup, so use the config
or `-Dmultiforge.mode` instead.

`mode = "off"` (config, or `-Dmultiforge.mode=off`, takes effect at the
next start) runs Vanilla's single-threaded tick with no regions. If a
problem persists with `mode = "off"`, it is not caused by MultiForge's
threading.

## Using the client debug mod

`multiforge-client` shows the same data in game (see
[`client-mod-guide.md`](client-mod-guide.md)). The server sends a stream
only to a client that subscribed to it, and only if the player has the
`multiforge.debug.view` permission node, which defaults to everyone; a
permission handler can deny it.

- **Region list panel**: one row per region,
  `region-<id> mspt=X.X/Y.Y owned=N sections=M`.
- **Chunk borders**: seams where adjacent chunks belong to different
  regions, from the server's real section-to-region mapping (v1.4.0 and
  later; older clients drew fabricated seams and should not be used).
  Useful for seeing whether a build straddles a region boundary, which is
  where cross-region writes come from.
- **Tick-cost heatmap**: per-region MSPT painted on chunks. The server
  measures cost per region, so every chunk of a region shows the same
  value.
- **Violation panel**: the live feed of logged warnings, the same events
  as `/multiforge warn list`.

## From a warning to a source line

1. Reproduce in strict mode. The `OwnershipViolationException` stack trace
   names the mod class, method and line that made the write.
2. Without a live reproduction, use the scanner report. `Finding.line` is
   `-1` for jars without line numbers; the JSON `className` and `method`
   (`<name><descriptor>`) fields then identify the method to decompile.
3. The warning detail names the chunk and both regions; compare with the
   client's chunk-border overlay to see which region the mod expected to
   own the write.
