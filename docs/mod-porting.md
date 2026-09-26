# Mod Porting Cookbook

Recipes for getting an existing NeoForge 1.21.1 mod to run cleanly under
MultiForge. Most mods need no changes. The tick model
([`design/barrier-tick-model.md`](design/barrier-tick-model.md)) keeps
Vanilla's main loop, chunk loading, commands, weather, raids and so on on
the server thread, and runs only per-chunk work (random ticks, spawning,
scheduled ticks, block events, entities, block entities) on region
workers, in parallel between two barriers. Code that touches only the
block, entity or chunk it was called for already runs on the right thread.

The mods that need work are the ones that reach further: code on a region
worker that touches a distant part of the world, static state shared
between regions, and work done on a thread that is not a region worker or
the server thread (network IO, a mod's own executor, a
`ServerDomains.async()` task).

None of these stop a mod from loading. A world write from the wrong thread
is rerouted to the chunk's owner with a rate-limited warning
(`docs/debugging-violations.md`); the recipes below remove the cause.

Each recipe is **before / after / why**.

For automated detection, the mod-safety scanner
([`certification.md`](certification.md),
[`design/scanner-rules.md`](design/scanner-rules.md)) checks a jar's
bytecode for the patterns below. `/multiforge certify <modId>` runs it
against a jar in `./mods`.

## 1. Reaching into another part of the world from tick code

**Before:**
```java
// In a block entity's tick: pull items from a linked chest 500 blocks away.
BlockEntity remote = level.getBlockEntity(linkedPos);
if (remote instanceof Container c) {
    ItemStack taken = c.removeItem(0, 1);
    ...
}
```

**After:**
```java
// Run the remote half on whichever region owns linkedPos.
WorldRef world = WorldRef.of(level.dimension().location().toString());
ServerDomains.region(world, net.multiforge.api.world.ChunkPos.ofBlock(linkedPos.getX(), linkedPos.getZ()))
    .execute(MOD, () -> {
        if (level.getBlockEntity(linkedPos) instanceof Container c) {
            ItemStack taken = c.removeItem(0, 1);
            // hand the result back with another ServerDomains.region(...) call
        }
    });
```

**Why:** A region worker owns only its region's chunks. A chunk more than
one section away can belong to another region that is ticking at the same
moment, so reading or changing it races that region. World writes through
the patched sites (`Level.setBlock`, `scheduleTick`, `addFreshEntity`,
`Entity.remove`, `BlockEntity.setChanged`, …) are rerouted automatically,
but direct mutation of a block entity's own fields (`removeItem` above) is
not intercepted. If the remote chunk is not loaded, the task waits until
it loads.

Reading chunks through `ChunkMap` internals instead of `ChunkSource`
(`getChunkNow`, `getChunk`) is flagged by scanner rule **R01**
(`direct-ChunkMap-invoke`, WARN). `ChunkMap` is Vanilla's, unchanged, but
its internal maps are updated on the server thread; `ServerChunkCache`'s
public methods are the ones MultiForge makes safe to call from a region
worker.

## 2. Moving entities

**Before:**
```java
entity.setPos(destX, destY, destZ);   // may land in another region
```

**After:**
```java
entity.teleportTo(destX, destY, destZ);
// or, across dimensions:
entity.changeDimension(new DimensionTransition(destLevel, destPos, Vec3.ZERO, yRot, xRot, DimensionTransition.DO_NOTHING));
```

**Why:** `Entity.teleportTo`, `ServerPlayer.teleportTo`,
`teleportRelative` and `changeDimension` are the entry points MultiForge
patches. Called on a region worker with a destination in another region
(or, for a player, another dimension), the whole call is deferred to the
server thread and runs after the tick barrier, while no region runs. The
entity's position is therefore not updated when the call returns. A raw
`setPos`/`moveTo` is not intercepted; use it only for short moves that
stay in the same region.

Scanner rule **R05** (`entity-setpos-off-coord`, ERROR) flags every direct
`Entity.setPos`/`setPosRaw` call. Its message refers to an
`EntityMigrationCoordinator` from the retired M4 design, which no longer
exists; for a short in-region move, suppress the finding with a
justification (`certification.md`).

## 3. Static state

**Before:**
```java
@Mod("examplemod")
public class ExampleMod {
    static int activeEffects = 0; // not volatile, not atomic

    @SubscribeEvent
    static void onEntityTick(EntityTickEvent.Post e) {
        activeEffects++; // races across regions
    }
}
```

**After:**
```java
@Mod("examplemod")
public class ExampleMod {
    static final AtomicInteger activeEffects = new AtomicInteger();

    @SubscribeEvent
    static void onEntityTick(EntityTickEvent.Post e) {
        activeEffects.incrementAndGet();
    }
}
```

Use `ConcurrentHashMap` for maps, or keep one structure per level or
position key.

**Why:** Region workers tick in parallel. An entity tick event is posted
on the worker ticking the entity, and a `hybrid-safe` mod's listener for
it runs right there (`docs/events.md`), so two regions can run it at once.
If the mod has many such listeners, classifying it `legacy` in
`config/multiforge-mods.toml` runs all of them on the serial lane, one at
a time, at the cost of parallelism. Caught by scanner rule **R04**
(`unsync-static-mutation`, WARN).

## 4. Work from a network, async or mod-owned thread

**Before:**
```java
// Runs on a mod's download thread.
public void onDownloadComplete(ServerLevel level, BlockPos pos, BlockState state) {
    level.setBlock(pos, state, Block.UPDATE_ALL);
}
```

**After:**
```java
public void onDownloadComplete(ServerLevel level, BlockPos pos, BlockState state) {
    WorldRef world = WorldRef.of(level.dimension().location().toString());
    ServerDomains.region(world, net.multiforge.api.world.ChunkPos.ofBlock(pos.getX(), pos.getZ()))
        .execute(MOD, () -> level.setBlock(pos, state, Block.UPDATE_ALL));
}
```

**Why:** The write from the download thread is an `:off-thread`
violation: it is rerouted, but the thread still reads world state (the
return value, neighbour states) without any protection. Doing the work in
a region task runs it on the owning region's worker. The same applies to
`ServerDomains.async()` tasks, which must not touch game state at all.
Reads count too: `level.getBlockState(pos)` from a foreign thread races
the owning worker's writes.

## 5. Block-entity mutation from a packet handler

**Before:**
```java
public void handle(UpgradePayload payload, IPayloadContext ctx) {
    BlockEntity be = ctx.player().level().getBlockEntity(payload.pos());
    ((MachineBlockEntity) be).installUpgrade(payload.upgrade()); // on the network thread
}
```

**After:**
```java
public void handle(UpgradePayload payload, IPayloadContext ctx) {
    ctx.enqueueWork(() -> {
        if (ctx.player().level().getBlockEntity(payload.pos()) instanceof MachineBlockEntity m) {
            m.installUpgrade(payload.upgrade());
        }
    });
}
```

**Why:** `IPayloadContext.enqueueWork` is unchanged NeoForge: it runs the
work on the server thread, which under MultiForge never overlaps region
work and may write anywhere. Running handler logic directly on the
network thread races the region ticking that block entity, and a
block entity's own fields are not guarded. Don't hold a `BlockEntity`
reference across threads; look it up inside the queued work. Off-thread
`Level.setBlock` and `BlockEntity.setChanged` are scanner rules **R02**
(ERROR) and **R08** (WARN).

## 6. Config reload

**Before:**
```java
static ExampleConfig CONFIG; // mutated in place on /reload

public static void reload() {
    CONFIG.maxEffects = readFromFile(); // region workers may be reading it now
}
```

**After:**
```java
static volatile ExampleConfig CONFIG = ExampleConfig.defaults();

public static void reload() {
    CONFIG = ExampleConfig.load(); // publish a whole new immutable snapshot
}
```

**Why:** A worker reading fields while another thread rewrites them can
see a half-updated config. Publishing a new immutable object through a
`volatile` field is atomic for readers. MultiForge's own
`MultiForgeConfig` is an immutable record handled the same way.

## 7. Server-wide broadcasts and other players

**Before:**
```java
// In a block entity tick: reward every online player.
for (ServerPlayer p : server.getPlayerList().getPlayers()) {
    p.getInventory().add(reward.copy()); // other players belong to other regions
}
```

**After:**
```java
for (ServerPlayer p : server.getPlayerList().getPlayers()) {
    p.connection.send(packet);                       // sending is fine
    WorldRef world = WorldRef.of(p.level().dimension().location().toString());
    ServerDomains.region(world, new net.multiforge.api.world.ChunkPos(p.chunkPosition().x, p.chunkPosition().z))
        .execute(MOD, () -> p.getInventory().add(reward.copy()));
}
```

**Why:** The player list only changes on the server thread (logins,
logouts, dimension changes), which never overlaps region work, so
iterating it and sending packets from a region worker is safe. A player
entity belongs to the region owning its chunk, so changing another
player's state from your region races that region's tick; run it on the
player's region. The chunk is resolved when the task is queued, so a
player who moves to another region before it runs will be modified from
the wrong region; keep such work idempotent or re-check the player's
position inside the task.

## Detecting this automatically

The scanner (`multiforge-scanner`) runs 12 rules. Severities, from the
rule classes in `multiforge-scanner/src/main/java/net/multiforge/scanner/rules/`:

| Rule | Name | Severity |
|---|---|---|
| R01 | `direct-ChunkMap-invoke` | WARN |
| R02 | `off-thread-Level.setBlock` | ERROR |
| R03 | `blocking-future` | ERROR |
| R04 | `unsync-static-mutation` | WARN |
| R05 | `entity-setpos-off-coord` | ERROR |
| R06 | `direct-ServerChunkCache-mutation` | WARN |
| R07 | `raw-DistanceManager-ticket` | WARN |
| R08 | `off-thread-BlockEntity-setChanged` | WARN |
| R09 | `sync-io-in-tick` | ERROR |
| R10 | `thread-start-in-mod-ctor` | WARN |
| R11 | `reflect-on-neoforged-internal` | WARN |
| R12 | `capture-server-in-lambda` | ERROR |

Run it directly:

```
java -jar multiforge-scanner.jar --severity=warn mods/examplemod-1.2.3.jar
```

or in game:

```
/multiforge certify examplemod
```

See [`certification.md`](certification.md) for what the scanner checks
and what "MultiForge-certified" means.
