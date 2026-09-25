# MultiForge legacy compatibility

MultiForge's promise is "auto-reroute + warn, never refuse to load a mod"
(CLAUDE.md rule 5): a mod written for Vanilla's single-threaded server boots
and runs unmodified, at whatever parallelism is safe for it. This page
describes the mechanisms that keep unported mod code correct and what an
operator or mod author can tune.

## The three safety nets

1. **Positional ownership.** Every patched mutation site
   (`multiforge-patches/01-ownership/`) checks whether the calling region
   owns the chunk being written. A write into another region's chunk is
   rerouted to that region's mailbox (or to the server thread for a chunk no
   region owns) and a rate-limited warning names the site. Mod code cannot
   corrupt another region's state, whichever thread it runs on.
2. **The serial lane for event listeners.** A listener of a mod that has not
   declared itself thread-safe, for an event MultiForge does not know to be
   local to one block, chunk or entity, runs on the server thread one
   listener at a time while the posting region worker waits
   (`docs/events.md`). Listener code with unsynchronised static state
   therefore never runs concurrently with itself, and cancellation and
   results work as in NeoForge.
3. **Deferral of cross-region operations.** Teleports into another region,
   player dimension changes and command execution started on a region worker
   run on the server thread after the tick barrier
   (`docs/design/barrier-tick-model.md`).

## Per-mod classification

| `multiforge_safety` | Unannotated listeners run… | Use for |
|---|---|---|
| `legacy` | on the serial lane, for every event | Mods that share unsynchronised state across listeners and break under `hybrid-safe`. |
| `hybrid-safe` (default) | on the posting region worker for block/chunk/entity-local events, on the serial lane otherwise | Most mods. |
| `strict-safe` | on the posting thread, for every event | Mods whose listeners are thread-safe. |

A mod declares its classification in `neoforge.mods.toml`
(`[modproperties.<modid>] multiforge_safety = "strict-safe"`); an operator
overrides it in `config/multiforge-mods.toml` (`[mods] <modid> = "legacy"`).
`@DispatchDomain` on a listener always wins over the classification.

## Operator settings

`config/multiforge-server.toml`, all changeable live with `/multiforge config …`:

```toml
[mtserver]
mode = "hybrid"         # off | hybrid | strict

[violations]
policy = "warn"         # warn | reroute-only | fail
warnPerMin = 5          # warnings per minute per violation site
```

- `mode = "off"` disables the regionized runtime entirely: Vanilla's
  single-threaded tick, no rerouting. Use it to check whether a problem is
  MultiForge's (takes effect at the next start).
- `mode = "strict"` and `policy = "fail"` turn a cross-region write into an
  `OwnershipViolationException` instead of a reroute — for testing a mod,
  never for production.
- `policy = "reroute-only"` reroutes without logging.

`-Dmultiforge.mode=…` overrides `mode` for one run.

## Mod patterns

| Pattern | Works as-is? | Notes |
|---|---|---|
| Listener that reads or writes only the event's own block, entity or chunk | Yes | Runs on the region worker for mapped events, on the serial lane otherwise. |
| Listener keeping a static `HashMap` updated from entity or block events | Yes, if classified `legacy` | Under `hybrid-safe`, listeners of mapped (local) events run in parallel across regions; classify the mod `legacy` or synchronise the map. |
| Code that caches a level, entity or block entity and mutates it later from elsewhere | Works, with reroutes | Each write outside the calling region is rerouted and warned about. Move the work to `ServerDomains.region(world, pos)` / `ServerDomains.entity(ref)` to run it on the owner directly. |
| Blocking `.get()`/`.join()`/`Thread.sleep` in tick or listener code | Stalls the region | Stalls the region (or, on the serial lane, every waiting region). Use a continuation (`ScheduledTask`, `queueChunkTask`, `ServerDomains`). |
| A mod thread that mutates world state directly | Works, with reroutes | The write is rerouted to its owner; the read side is not protected. Do the background work in `ServerDomains.async()` and send the mutation to `region`/`entity`/`global`. |

## Porting a mod that hits reroute warnings

1. Read the warning: it names the patched call site (e.g. `Level.setBlock`),
   the chunk, and the calling and owning regions; `/multiforge warn list`
   shows recent ones.
2. Find the code path in the mod that made the call.
3. Run the work on the owning domain: `ServerDomains.region(world, pos)`,
   `ServerDomains.entity(ref)`, `ServerDomains.global()`, or
   `ServerDomains.async()` for work that touches no game state
   (`docs/scheduler-api.md`).
4. Test on a dev server with `/multiforge config mode strict`: a remaining
   cross-region write throws instead of rerouting.
