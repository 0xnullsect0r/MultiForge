# Mod Porting Cookbook

This is a recipe book for getting an existing NeoForge 1.21.1 mod
running cleanly under MultiForge. Most mods need zero changes — they
only touch the world from inside a tick handler or an event callback,
which already runs on the correct region thread. The mods that need
work are the ones that reach across region boundaries: static state
shared between regions, direct references to internals MultiForge
replaces, or work dispatched from a thread that was never
region-owned to begin with (network IO, async downloads, scheduled
executors).

Each recipe below is **before / after / why**. "Before" is the
pattern that either breaks outright or silently degrades under
MultiForge's threading model; "after" is the MultiForge-safe
equivalent; "why" explains the hazard.

For automated detection instead of manual auditing, see
[`docs/design/scanner-rules.md`](design/scanner-rules.md) — the
mod-safety scanner's 12 frozen rules (R01–R12) catch most of the
patterns below in bytecode, before the jar ever touches a region
worker. `/multiforge certify <modId>` (see
[`certification.md`](certification.md)) runs the scanner against a jar
already sitting in `./mods` and prints pass/fail per rule.

## 1. Chunk access

**Before:**
```java
ChunkMap cm = (ChunkMap) ((ServerChunkCache) level.getChunkSource()).chunkMap;
LevelChunk chunk = cm.getVisibleChunkIfPresent(pos.toLong());
```

**After:**
```java
LevelChunk chunk = (LevelChunk) level.getChunkSource().getChunkNow(pos.x, pos.z);
```

**Why:** `ChunkMap`'s field layout survives for reflective-mod
compatibility, but under MultiForge it's a thin delegate shell over
the per-region holder pipeline (`docs/chunks.md`). Calls that reach
past it into raw internal state skip the region-aware ticket/holder
bookkeeping every mutation needs. `ChunkSource`'s public surface stays
stable and correctly routed. Caught by scanner rule **R01**
(`direct-ChunkMap-invoke`, WARN).

## 2. Entity teleport across regions

**Before:**
```java
// called from anywhere, assumes single-threaded world
entity.setPos(destX, destY, destZ);
if (destLevel != entity.level()) {
    entity.changeDimension(destLevel);
}
```

**After:**
```java
// go through the migration protocol — see docs/migration.md
migrationCoordinator.migrate(entity, destLevel, new Vec3(destX, destY, destZ));
```

**Why:** A destination position may belong to a different region (or
a different world's global region) than the one currently ticking the
entity. MultiForge owns cross-region entity movement through a
two-phase remove/add protocol (`MigrationState` machine,
passenger-tree atomicity) so the entity is never visible to two
region workers at once. A raw `setPos`/`changeDimension` call from the
wrong thread races the destination region's tick.

## 3. Static caches

**Before:**
```java
@Mod("examplemod")
public class ExampleMod {
    static int activeEffects = 0; // not volatile, not atomic

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent e) {
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
    static void onLevelTick(LevelTickEvent e) {
        activeEffects.incrementAndGet();
    }
}
```

If the value is conceptually per-region rather than truly global (a
counter for "effects active near this base," say), prefer
`net.multiforge.runtime.region.RegionizedData` so each region gets its
own slot instead of forcing atomics onto state that was never meant to
be shared in the first place.

**Why:** Multiple regions tick concurrently on different worker
threads — the whole point of the project. An unsynchronized static
field written from tick-reachable code races the moment two regions
both host the mod's blocks/entities. Caught by scanner rule **R04**
(`unsync-static-mutation`, WARN).

## 4. Off-thread block reads

**Before:**
```java
// called from a Netty IO callback thread, not a region worker
public void onDownloadComplete(BlockPos pos) {
    BlockState state = level.getBlockState(pos); // reads whatever region owns pos
}
```

**After:**
```java
public void onDownloadComplete(WorldRef world, BlockPos pos) {
    taskQueue.queueChunkTask(world, new ChunkPos(pos), () -> {
        BlockState state = world.level().getBlockState(pos);
        // ... use state here, still on the owning region thread
    });
}
```

**Why:** Even a *read* of `Level`/chunk state from a thread that
doesn't own the target region is racing the owning worker's writes —
`Level`'s internal section arrays have no synchronization of their own
(CLAUDE.md rule 4 extended to reads, not just writes). Route through
`RegionizedTaskQueue.queueChunkTask(world, chunkX, chunkZ, task)` and
do the read inside the task.

## 5. Block-entity mutation off-thread

**Before:**
```java
public void applyUpgrade(BlockEntity be, ItemStack upgrade) {
    be.getInventory().insertItem(0, upgrade, false); // called from a GUI packet handler
}
```

**After:**
```java
public void applyUpgrade(WorldRef world, BlockPos pos, ItemStack upgrade) {
    taskQueue.queueChunkTask(world, new ChunkPos(pos), () -> {
        BlockEntity be = world.level().getBlockEntity(pos);
        if (be != null) {
            be.getInventory().insertItem(0, upgrade, false);
        }
    });
}
```

**Why:** Same hazard as block mutation in general
(`Level.setBlock` is scanner rule **R02**, ERROR — a definite race,
not just a suspicious pattern), extended to any `BlockEntity` field a
mod owns. Don't hold a `BlockEntity` reference across a thread
boundary and mutate it later; re-look it up inside a queued task on
the owning region.

## 6. Config reload

**Before:**
```java
static ExampleConfig CONFIG; // mutated in place on /reload

public static void reload() {
    CONFIG.maxEffects = readFromFile(); // other regions may be reading CONFIG.maxEffects right now
}
```

**After:**
```java
static volatile ExampleConfig CONFIG = ExampleConfig.defaults();

public static void reload() {
    CONFIG = ExampleConfig.load(); // publish a whole new immutable snapshot
}
```

**Why:** This is exactly the pattern
`net.multiforge.runtime.config.MultiForgeConfig` itself uses: config
is an immutable record, and a reload produces a new instance published
to subscribers, never mutated in place. A region worker that read a
field mid-mutation on another thread gets a torn read; publishing a
new reference is atomic and lock-free for readers.

## 7. World-wide broadcasts

**Before:**
```java
// called from inside a region's tick
for (ServerPlayer p : server.getPlayerList().getPlayers()) {
    p.connection.send(myPacket);
}
```

**After:**
```java
router.routeToGlobal(() -> {
    for (ServerPlayer p : server.getPlayerList().getPlayers()) {
        p.connection.send(myPacket);
    }
});
```

**Why:** Iterating every player from inside a region worker touches
players owned by other regions. `NetworkPacketRouter.routeToGlobal`
(`docs/global-network.md`) hands the broadcast to the dedicated global
region thread, which is the one place server-wide iteration is safe.
If the broadcast is really player-scoped (e.g. "everyone near this
event"), prefer iterating the region's own tracked players instead of
reaching for the global player list at all.

## 8. Packet handling

**Before:**
```java
// bypasses enqueueWork, runs whatever thread decoded the packet
public void handle(MyPayload payload, IPayloadContext ctx) {
    applyEffect(payload.pos(), payload.effect()); // wrong thread
}
```

**After:**
```java
public void handle(MyPayload payload, IPayloadContext ctx) {
    ctx.enqueueWork(() -> applyEffect(payload.pos(), payload.effect()));
}
```

**Why:** The M5 patch replaces NeoForge's default
`IPayloadContext#enqueueWork` body with
`ModPacketContext.of(router, senderRef).enqueueWork(...)`, which
routes the work to the region owning the packet's target (or the
player's current region, if it crosses a border between decode and
dispatch). Mods that already use `enqueueWork` correctly get this for
free on recompile — the bug is mods that skip it and run handler logic
directly on the network thread.

## Detecting this automatically

Every recipe above maps to one of the scanner's 12 frozen rules
(`docs/design/scanner-rules.md` §4). Running the scanner against a mod
jar before shipping it catches most of these patterns in bytecode:

| Pattern | Rule | Severity |
|---|---|---|
| Direct `ChunkMap` internals access | R01 | WARN |
| Off-thread `Level.setBlock` | R02 | ERROR |
| Blocking `.get()`/`.join()` on `@RegionThread` | R03 | ERROR |
| Unsynchronized static mutation from tick-reachable code | R04 | WARN |

Run it directly:

```
java -jar multiforge-scanner.jar --severity=warn mods/examplemod-1.2.3.jar
```

or via the in-server convenience command:

```
/multiforge certify examplemod
```

See [`certification.md`](certification.md) for the full checklist and
what "MultiForge-certified" means.
