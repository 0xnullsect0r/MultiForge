# MultiForge Entity Migration (M4)

## The problem

An entity in region A moves across a chunk boundary owned by region B.
Or portal-teleports to the Nether. Or gets pearl-thrown. The mid-tick
"just update the position" approach used by single-threaded Vanilla is
unsafe in parallel: two workers could touch the entity at once, or an
observer holds a stale pointer to a region that no longer owns it.

## The Folia-inspired protocol

Everything is a **two-phase remove-then-add**, coordinated through
`RegionizedTaskQueue`.

```
source region worker                      destination region worker
─────────────────────                     ───────────────────────
1. CAS RESIDENT → MIGRATING (recursive
   over passenger tree). If any CAS fails
   the whole tree is rolled back.

2. Snapshot the entity + passengers into
   an immutable EntitySnapshot (uuid,
   destWorld, destPos, opaque payload,
   nested passenger list).

3. Remove every ref from the source
   world's EntityRegistry.

4. taskQueue.queueChunkTask(destWorld,
   destChunk, () -> migrator.completeAt)
                                          ─→ 5. Recursively re-materialize:
                                                 - new MigratingEntityRef with
                                                   updated (world, chunkPos)
                                                 - registry.add(ref, payload)
                                                 - ref.completeMigration()
                                                   (CAS MIGRATING → RESIDENT)
                                                 - for each passenger, recurse
```

## Why remove-then-add

Trying to atomically move a live entity between owners requires
locking both regions — which is exactly what MultiForge is designed
to avoid. Snapshot-based transfer decouples the two sides: the source
finalizes its detach in isolation, the destination runs its attach in
isolation, and the mailbox handoff provides the ordering guarantee.

## MigrationState machine

```
                 ┌───────────┐
     ┌─────CAS───│ RESIDENT  │◀────completeMigration────┐
     ▼           └───────────┘                           │
┌──────────┐                                        (new world/pos
│ MIGRATING│──abort──▶ RESIDENT                      published)
└────┬─────┘
     │
   retire()
     ▼
  RETIRED (terminal)
```

- `beginMigration()` — CAS `RESIDENT → MIGRATING`. Fails if the
  entity is already migrating or retired.
- `completeMigration(newWorld, newChunkPos)` — publish new location
  and CAS `MIGRATING → RESIDENT`. If the entity was retired mid-flight,
  state stays `RETIRED`.
- `abortMigration()` — best-effort return to `RESIDENT` when the
  destination refuses (rare — full chunk unloaded, teleport quota).
- `retire()` — terminal. All observers see `isRetired() == true`
  immediately.

## Vehicle + passenger tree

Passengers move atomically with their vehicle. The source captures the
whole tree in one snapshot; the destination re-materializes bottom-up
so parents exist before children are re-mounted.

`beginMigrationWithTree(vehicle, destWorld, destPos, passengerSpec)`
takes an explicit tree — the M4 patch fills it from Vanilla's
`Entity.getPassengers()` walk; tests provide one directly. The CAS is
applied to every ref in the tree in one pass; if any fails, all are
rolled back.

## Player join

Players are the special case where the "source" is a Netty IO thread,
not a region worker. Two hops:

1. Netty thread → global region (via `queueChunkTask` on the global
   world). Global region assigns UUID row in the shared datastore.
2. Global region → spawn-chunk region. Spawn region materializes the
   player, adds it to its `EntityRegistry`, and releases the initial
   game packets.

`PlayerJoinCoordinator.onPlayerLoginCompleted(playerUuid, spawnWorld,
spawnPos, payload)` does the whole flow — the M4 patch calls it from
`ServerConnectionListener` after successful login.

## Invariants the pure-Java M4 layer proves

- Exactly one owner at any time — no two workers can hold an
  `EntityRef` in `RESIDENT` state simultaneously.
- No dupes on transfer — `registry.remove(uuid)` on source before
  `registry.add(freshRef)` on destination.
- Passenger tree is all-or-nothing — CAS on every ref before snapshot,
  rollback if any fails.
- Cross-dimension crosses region **and** regionizer boundaries — the
  `taskQueue`'s ownerLookup finds the destination regionizer by
  `WorldRef.dimensionId()`.
- Retired mid-flight — destination discovers `RETIRED` on
  `completeMigration` and leaves the entity absent from the target
  registry (never rematerialized).

## What the M4 patch adds on top

- Snapshot payload becomes a Vanilla `CompoundTag` populated by
  `Entity.saveWithoutId`.
- Rematerialization at the destination uses
  `ServerLevel.addFreshEntity` after `Entity.loadWithId`.
- `Entity#teleportAsync(...)` is the operator-visible entry that
  wraps `EntityMigrationCoordinator.beginMigration`.
- Ender-pearl fix couples with the M3 `TicketType.ENDER_PEARL` — each
  pearl migration adds/removes its own per-entityId ticket so target
  chunks stay loaded through the flight.
- The M4 patch also binds `PlayerJoinCoordinator` into
  `ServerConnectionListener` so login → global → spawn happens on the
  region timer, not on Netty.

## Test coverage

- `MigratingEntityRefTest` — RESIDENT initial, `beginMigration`
  once-per-round-trip, abort returns to RESIDENT, retire is terminal.
- `EntityMigrationCoordinatorTest` — single-entity cross-region,
  cross-dimension, vehicle+rider+pet atomic tree transfer, double
  migration attempt rejected, retired-entity migration rejected.
- `PlayerJoinCoordinatorTest` — full Netty → global → spawn hop
  produces exactly one entry in the target world's registry.
