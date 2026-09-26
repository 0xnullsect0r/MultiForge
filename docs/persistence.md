# Persistence

World saving is Vanilla's. MultiForge writes nothing into the world
directory: no journal, no per-region save files, no extra metadata. A world
saved by MultiForge is an ordinary NeoForge 1.21.1 world and can be opened by
stock NeoForge, and the reverse.

## Saving during play

Autosave, `/save-all`, chunk saving on unload and player data saving run on
the server thread in Vanilla's code, with Vanilla's settings. They run outside
the region barrier, so no region is ticking while the server saves.

MultiForge has no autosave or save-related configuration.

## Server stop

On a normal stop (`/stop`, console shutdown):

1. The tick loop ends. The last barrier has completed, so no region runs.
2. `ServerLifecycleHooks.handleServerStopping` posts `ServerStoppingEvent`
   and then calls `MultiThreadedSchedulerHost.drainMailboxesOnCaller()`,
   which runs, on the server thread, every task still waiting in a region
   mailbox (for example a block change rerouted to another region during the
   last tick), so those changes are in the world before it is saved.
3. Vanilla's `MinecraftServer.stopServer` saves players and all chunks and
   closes the levels.
4. `handleServerStopped` shuts down the regionized runtime and its worker
   pool.

If the server crashes instead of stopping, step 2 is skipped: work still in
a mailbox is lost, as is everything since the last save, as in Vanilla.

## Files MultiForge writes

Both under the server's `config/` directory, not in the world:

- `multiforge-server.toml`: runtime configuration
  ([`regions.md`](regions.md#configuration)).
- `multiforge-region-pins.toml`: region pins
  ([`regions.md`](regions.md#region-pins)).

## What this replaced

The M6 design had per-region autosave queues, a write-ahead journal per region
under `world/multiforge/journal/`, boot-time journal replay and a phased
shutdown coordinator. Vanilla kept saving every chunk throughout; the journal
never took part in a running server's saves, and the design was removed along
with the M9 chunk-system fork. See
[`design/barrier-tick-model.md`](design/barrier-tick-model.md#what-this-replaced).
