# Entities crossing regions

MultiForge does not migrate entities between regions. Ownership follows chunk
position, and moves that could cross regions within one tick are deferred to
the server thread. This page explains why that is enough. The full model is in
[`design/barrier-tick-model.md`](design/barrier-tick-model.md#ownership).

## Ownership follows position

An entity belongs to the region that owns the chunk it is in. There is no
entity ownership table and no per-entity state to move. When an entity walks,
flies or is pushed into a chunk owned by another region, it is ticked by that
region the next time that region ticks, because at that point its chunk is
owned by that region.

This cannot race between two workers: two distinct regions are always
separated by at least one fully unloaded section (see
[`regions.md`](regions.md#how-regions-form)), so an entity moving normally
cannot leave its region's chunks within one tick without first entering
unloaded chunks. If regions merge or split between ticks, the entity simply
belongs to whichever region owns its chunk afterwards.

Passengers and vehicles need no special handling: they are ticked with their
vehicle, as in Vanilla.

## Moves that are deferred

Some moves can jump arbitrarily far in one step. When a region worker starts
one, it is deferred to the server thread and runs after the barrier, while no
region is ticking (`OwnershipGuard.deferCrossRegionMove` /
`deferToServerThread`):

- an entity teleporting into a chunk owned by another region
  (`Entity.teleportTo`, same-level `changeDimension`,
  `ServerPlayer.teleportTo`, `teleportRelative`), which includes ender pearls
  and chorus fruit;
- a player changing dimension, because removing a player from a level updates
  every tracked entity's viewer set;
- command and function execution, since `/tp @e` or `/fill` from a command
  block can reach any chunk.

A non-player entity changing dimension (for example an item through a nether
portal) proceeds inline: only the current level's regions are running, so the
destination level is idle.

Teleports that stay within the entity's own region run immediately, as in
Vanilla.

## Player login and respawn

Logins, respawns and player list changes are handled by Vanilla on the server
thread, outside the region barrier. There is no separate join path.

## Entity creation and removal across regions

Adding an entity in a chunk the current region does not own
(`ServerLevel.addFreshEntity` / `addEntity`) and removing one (`Entity.remove`)
are ownership-checked like any other mutation. From a region worker they are
rerouted to the owning region's mailbox, or to the server thread if no region
owns the chunk. `addFreshEntity` returns Vanilla's expected result to the
caller immediately; if the owner's actual result differs when it applies the
change, the probe `reroute.ServerLevel.addFreshEntity.mismatch` is bumped and
a warning logged.

## What this replaced

The M4 design removed an entity from its source region, serialised it to NBT,
and re-created it in the destination region, holding a player's movement
packets while a hop was in flight. It broke references to the entity and made
players rubber-band. It was removed when ownership became positional; see
[`design/barrier-tick-model.md`](design/barrier-tick-model.md#what-this-replaced).
