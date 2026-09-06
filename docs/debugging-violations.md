# Debugging Violations Manual

An "ownership violation" is code — usually mod code, sometimes a
MultiForge bug — that touches state owned by a region from a thread
that isn't that region's worker. Per CLAUDE.md rule 5, MultiForge
never refuses to load a mod and never throws from a patched call site
over this by default: it reroutes the call to somewhere safe and logs
a rate-limited warning. This doc is about finding and fixing the
*cause* of those reroutes, not about the reroute mechanism itself
(that's `net.multiforge.runtime.ownership.OwnershipEnforcer`, see
`docs/concurrency-contract.md`).

## Reading `ViolationLogger` output

Every violation site that fires goes through
`net.multiforge.runtime.diagnostics.ViolationLogger.warn(modId, site,
detail)`. The log line shape is:

```
[<site>] <detail>
```

for example:

```
[Level.setBlock:off-thread] examplemod called Level.setBlock from thread "Netty Client IO #3" (region-7 owns chunk [12, -4])
```

If the same `(modId, site)` pair fires repeatedly, only the first
message in each 60-second window is logged in full; the second gets a
one-time note appended:

```
[Level.setBlock:off-thread] examplemod called Level.setBlock ... (further identical messages suppressed for 60s)
```

and every call after that in the same window is silent — no log line,
no HUD event. This budget is **per mod, per site** (a token bucket
keyed on `modId + "::" + site`), so one chatty mod spamming a single
site can't drown out a different mod's warnings, or even its own
warnings at a *different* site. Default budget is 5/min; override with
`-Dmultiforge.violations.warn-per-min=<n>` (`0` is a documented "silence
everything" idiom, not an error).

`/multiforge warn list` shows the same events the log does — the
in-memory history and the log line are populated together, from the
same fired-WARN branch, so they never disagree about what actually got
logged versus silently dropped by the rate limiter.

```
/multiforge warn list
Recent violations (3, oldest first):
  2026-09-06T14:02:11Z [examplemod] Level.setBlock:off-thread: examplemod called Level.setBlock from thread "Netty Client IO #3" (region-7 owns chunk [12, -4])
  2026-09-06T14:02:41Z [examplemod] Level.setBlock:off-thread: examplemod called Level.setBlock ... (further identical messages suppressed for 60s)
  2026-09-06T14:05:03Z [-] region-tick.overrun: region-3 exceeded msptSplitThreshold for 3 consecutive ticks
```

`/multiforge warn clear` drops that history (it does **not** reset the
rate-limit buckets — a mod mid-burst keeps its existing throttle
window, only the operator-visible list resets).

## Common violation types + fix strategies

| Log site prefix | Likely cause | Fix |
|---|---|---|
| `Level.setBlock:off-thread` | A mod mutates blocks from a callback thread (network IO, async download, scheduled executor) instead of the owning region worker. | Wrap in `RegionizedTaskQueue.queueChunkTask(world, chunkPos, task)`. See `docs/mod-porting.md` recipe 4. |
| `BlockEntity:off-thread` | Same shape as above, for `BlockEntity` NBT/inventory mutation held across a thread boundary. | Re-look up the `BlockEntity` inside a queued task rather than caching a reference across threads. See recipe 5. |
| `Entity:cross-region` | A mod calls `Entity.setPos`/`changeDimension` directly instead of going through the migration protocol. | Route through the migration coordinator (`docs/migration.md`). See recipe 2. |
| `blocking-future` (from `-Dmultiforge.ownership.mode=strict` runs, or scanner R03) | `.get()`/`.join()` called on a region-tick thread — head-of-line-blocks the whole region, can deadlock outright. | Use `.thenAccept`/`.thenCompose` and re-queue the continuation via `queueChunkTask` instead of blocking. |
| `static-mutation` (scanner R04, no runtime `ViolationLogger` equivalent — bytecode-only) | Unsynchronized static field written from tick-reachable code. | `volatile`/`Atomic*`/`RegionizedData`. See recipe 3. |
| `region-tick.overrun` (a `ProbeRegistry` counter, not a `ViolationLogger` site — check via `/multiforge probes region-tick`) | A region is spending more than its tick budget; not itself an ownership violation, but often co-occurs with a mod blocking the tick thread. | Check `/multiforge warn list` for a `blocking-future`/off-thread entry around the same timestamp before assuming it's just "the region is legitimately hot." |

## Using `-Dmultiforge.ownership.mode=strict` for regression

`OwnershipEnforcer` (`net.multiforge.runtime.ownership.OwnershipEnforcer`)
has three modes, selected via `-Dmultiforge.ownership.mode`:

| Mode | Behavior |
|---|---|
| `off` | Skip the check entirely; every call runs inline. Escape hatch for audited modpacks, never for regression testing. |
| `reroute` (default) | Off-owner-thread mutations are warned about (via `ViolationLogger`) and handed to the reroute target. Production default per CLAUDE.md rule 5. |
| `strict` | Off-owner-thread mutations throw `OwnershipViolationException` instead of rerouting. |

`strict` mode is for regression runs, not production: pass
`-Dmultiforge.ownership.mode=strict` when running a modpack against a
new MultiForge build (or a new mod version) in CI or a local test
server. Because it throws instead of silently rerouting, the stack
trace at the throw site pins down the exact call chain — mod class,
method, line — far more precisely than the rate-limited WARN log
alone. Never run production servers this way: any legitimate
`reroute`-shaped usage in a mod that was working fine under the
default mode becomes a hard crash under `strict`.

## Using the client debug mod HUD to spot issues in-game

`multiforge-client` (the `multiforge:debug/v1` wire protocol) gives a
live, in-world view of the same violation stream, without tailing
server logs:

- **F3 overlay** — one line per region:
  `region-<id> mspt=X.X/Y.Y owned=N sections=M`. A region whose p95
  MSPT is climbing while nothing else changed is a good place to
  start looking for an off-thread mutation forcing extra reroute work.
- **Chunk borders** — each region gets a distinct color, so you can
  see at a glance whether a base straddles a region boundary (a common
  source of cross-region violations for mods that assume "my base" is
  a single ownership domain).
- **Tick-cost heatmap** — per-chunk MSPT tinting; a hot single chunk
  inside an otherwise normal region often means a block-entity or
  tile-tick violation is repeatedly rerouting through that chunk.
- **Violation side panel** — live reroute/warn events, driven by the
  same `ViolationLogger` subscriber feed `/multiforge warn list` reads
  from server-side. Each entry shows mod id and site, so you can
  correlate what you're seeing in-world with the log/command output.

The server never emits these debug packets unless a connected client
requests them, so there's no always-on cost to leaving the feature
available.

## Correlating stack traces to source lines

The rate-limited `ViolationLogger.warn` output is deliberately terse —
it's meant to be safe to leave on in production without flooding the
log. When you need an exact source line:

1. Reproduce under `-Dmultiforge.ownership.mode=strict` (previous
   section). The thrown `OwnershipViolationException`'s stack trace
   names the exact mod class, method, and line that triggered the
   violation — not just the site label.
2. If you're working from a scanner report instead of a live
   reproduction, `Finding.line` (`docs/design/scanner-rules.md` §2.1)
   is best-effort: mod jars built without debug info, or obfuscated
   jars that strip `LineNumberTable`, report `line: -1`. In that case
   the `method` descriptor field (`"<method>(<descriptor>)"`) is the
   next-best identifying detail — decompile the specific method rather
   than searching by line number.
3. `/multiforge warn list`'s `detail` string is generated at the
   patched call site and usually already names the offending thread
   and the chunk/region involved — cross-reference that against the
   HUD's chunk-border overlay to see which region *should* have owned
   the call, which narrows down whether the bug is "wrong thread" or
   "wrong region assumption" in the mod's own logic.
