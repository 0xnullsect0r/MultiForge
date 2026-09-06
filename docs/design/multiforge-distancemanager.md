# MultiForgeDistanceManager — Design

**Status:** Phase 4 task 4.2a of the M9 landing plan
(`plans/bubbly-jumping-comet.md`).
**Verdict:** REPLACE (`docs/design/m9-patch-strategy.md:75-108`).
**Public API contract:** frozen in `docs/design/m9-contracts.md:545-621`.
**Vanilla source:**
`upstream/neoforge-1.21.1/projects/neoforge/src/main/java/net/minecraft/server/level/DistanceManager.java`
(535 LOC — 511 Vanilla + 24 NeoForge).
**Non-goals:** implement (4.2b); patch shell (4.2c); tests (4.2d).

---

## 1. Purpose

Vanilla `DistanceManager` is a global ticket ledger. Writes mutate a
single `Long2ObjectMap<SortedArraySet<Ticket<?>>>`
(`DistanceManager.java:43`), push deltas through `ChunkTicketTracker`
(`DistanceManager.java:349-393`), and reach `ChunkHolder.setTicketLevel`
via the abstract `updateChunkScheduling` hook (`:106`). Application is
batched: `runAllUpdates(ChunkMap)` (`:108-147`) drains
`chunksToUpdateFutures` at end-of-tick from the main thread.

Under MultiForge chunk state is per-region. `MultiForgeDistanceManager`
REPLACES the ledger and routes every write to the owning region's
`PerRegionTicketMap` (`multiforge-runtime/.../PerRegionTicketMap.java:23`)
via `ChunkHolderManager.addTicket`
(`multiforge-runtime/.../ChunkHolderManager.java:81`). Promotion enqueues
a full-load update on `HolderManagerRegionData`
(`ChunkHolderManager.java:94`) that the owning region worker drains in
`PhasedRegionTickBody.Phase.INBOUND_MAILBOX` (Phase 5.1). NeoForge
`forceTicks` semantics (`DistanceManager.java:194-208, 291-294`) are
preserved verbatim.

---

## 2. Class shape

```java
package net.multiforge.neoforge.chunk;

@ApiStatus.Internal
public abstract class MultiForgeDistanceManager
        extends net.minecraft.server.level.DistanceManager {

    protected final MultiThreadedSchedulerHost host;
    protected final WorldRef worldRef;
    protected final ChunkHolderManager holderManager;

    protected MultiForgeDistanceManager(Executor mainExecutor,
                                        Executor bgExecutor,
                                        MultiThreadedSchedulerHost host,
                                        WorldRef worldRef) {
        super(mainExecutor, bgExecutor);
        this.host = host;
        this.worldRef = worldRef;
        this.holderManager = host.chunkManagerFor(worldRef);
    }
    // abstract seams fulfilled by MultiForgeChunkMap.DistanceManager (§9)
}
```

Extending the abstract base is mandatory:
`ServerChunkCache.chunkMap.distanceManager` is typed as `DistanceManager`,
mods reflect on the type (grep: 4 external references), and
`ChunkMap$DistanceManager` is Vanilla's only production subclass.
Extending preserves `instanceof` and field-reflection compatibility. The
three inherited long maps (`tickets`, `forcedTickets`, `playersPerChunk`)
remain declared on the base and stay empty at runtime (§3).

---

## 3. Field mapping — Vanilla → MultiForge

| Vanilla field (`DistanceManager.java`) | MultiForge equivalent |
|---|---|
| `tickets: Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>>` (`:43`) | `holderManager.ticketsFor(owner).addTicket(pos, muTicket)` (`ChunkHolderManager.java:72, 81`); inherited map stays empty. |
| `forcedTickets` (`:57`) | `MultiForge.Ticket.forceTicks` bit (Phase 4.8a); `shouldForceTicks(long)` reads it. Inherited map stays empty. |
| `playersPerChunk` (`:42`) | `NewChunkHolder.playersWatching` (Phase 2 task 2.4). |
| `naturalSpawnChunkCounter: FixedPlayerDistanceChunkTracker` (`:45`) | Per-region slot on `HolderManagerRegionData` (new). Kept algorithm, per-region owner. |
| `tickingTicketsTracker: TickingTracker` (`:46`) | Per-region `TickingTracker` on `HolderManagerRegionData` (Vanilla data structure reused). |
| `playerTicketManager: PlayerTicketTracker` (`:47, :452-534`) | **Deleted.** Replaced by Phase 1.9 player-radius promoter that emits `TicketType.PLAYER` on player join/move. |
| `ticketThrottler` / `ticketThrottlerInput` / `ticketThrottlerReleaser` (`:49-51`) | Replaced by `ChunkTaskScheduler` routing (Phase 4.6). Base fields allocated by `super` but never invoked. |
| `chunksToUpdateFutures` (`:48`) | Removed. `HolderManagerRegionData.enqueueFullLoadUpdate` (`:38`) replaces the batching. |
| `ticketsToRelease` (`:52`) | Removed — the throttled release step disappears with `playerTicketManager`. |
| `mainThreadExecutor` (`:53`) | Kept via `super` for source-compat; no runtime consumer. |
| `ticketTickCounter` (`:54`) | Removed. `TicketExpiryTicker` (Phase 1.10) reads `Ticket.expiryTick` directly. |
| `simulationDistance` (`:55`) | Kept; change fans out to regions (§4.6). |
| `ChunkTicketTracker` (`:349-393`) | Deleted. Its `getLevelFromSource / getLevel / setLevel` collapse into `ChunkHolderManager.addTicket → HolderManagerRegionData.enqueueFullLoadUpdate → NewChunkHolder.updateFutures`. |
| `FixedPlayerDistanceChunkTracker` (`:395-450`) | Kept as per-region instance under `HolderManagerRegionData` for `naturalSpawnChunkCounter` and `hasPlayersNearby`. |

---

## 4. Public / protected API

Tags: **KEPT** (signature + semantics), **REPLACED** (signature identical,
body rerouted), **NEW**, **NO-OP** (signature preserved, empty body).

### 4.1 Constructor — **NEW**
Adds `host` + `worldRef` to Vanilla's two-arg ctor (`:59`) so writes can
resolve the owning region.

### 4.2 Package-private choke — **REPLACED**
- `void addTicket(long pos, Ticket<?> ticket)` (`:149-161`) — resolve the
  owning region via `regionizerFor(worldRef).regionAtChunk(x, z)`
  (`ThreadedRegionizer.java:114-118`; null → warn + drop, strict-mode
  throw per plan §Ground rule 5), translate to
  `net.multiforge.runtime.chunk.Ticket`, call
  `holderManager.addTicket(regionId, chunkPos, muTicket)`. `forceTicks`
  rides on the runtime `Ticket` (Phase 4.8a).
- `void removeTicket(long, Ticket<?>)` (`:163-180`) — symmetric,
  `ChunkHolderManager.java:104`.

### 4.3 Public adds/removes — **KEPT signatures; bodies REPLACED**
- `<T> void addTicket(TicketType<T>, ChunkPos, int, T)` (`:182-184`).
- `<T> void removeTicket(TicketType<T>, ChunkPos, int, T)` (`:186-189`).
- `<T> void addRegionTicket(TicketType<T>, ChunkPos, int, T)` (`:191-193`)
  — forwards to 5-arg with `false`.
- `<T> void addRegionTicket(..., boolean forceTicks)` (`:194-199` NeoForge)
  — MUST preserve. Sets `forceTicks` bit on runtime ticket.
- `<T> void removeRegionTicket(TicketType<T>, ChunkPos, int, T)` (`:201-203`).
- `<T> void removeRegionTicket(..., boolean forceTicks)` (`:204-209` NeoForge).

All route through §4.2.

### 4.4 Forced chunks — **REPLACED**
- `updateChunkForced(ChunkPos, boolean)` (`:215-225`) — writes a MultiForge
  `TicketType.FORCED` ticket at `ChunkMap.FORCED_TICKET_LEVEL`
  (`ChunkMap.java:115`) with `forceTicks=true`. Phase 1.8 wiring.

### 4.5 Players — **REPLACED**
- `addPlayer(SectionPos, ServerPlayer)` (`:227-234`) — resolve region,
  call `NewChunkHolder.addPlayer(p)` (Phase 2.4), emit a
  `TicketType.PLAYER` region ticket at `simulationDistance`.
- `removePlayer(SectionPos, ServerPlayer)` (`:236-247`) — symmetric.
- `int getPlayerTicketLevel()` (`:249-251`) — **KEPT** (pure math).

### 4.6 View / simulation distance — **REPLACED**
- `updatePlayerTickets(int viewDistance)` (`:266-268`) — fan out to every
  region via `ChunkTaskScheduler.scheduleChunkTask` so each region's
  player-radius promoter re-evaluates local players. Not a no-op.
- `updateSimulationDistance(int)` (`:270-275`) — update base field, then
  fanout.

### 4.7 Reads — **REPLACED**
- `inEntityTickingRange(long)` / `inBlockTickingRange(long)` (`:253-259`)
  — read `holderManager.holderAt(pos).currentLoadLevel().isOrAfter(...)`.
  Cross-region safe (holder table is a `ConcurrentMap`).
- `getNaturalSpawnChunkCount()` (`:277-280`) — folds per-region
  `naturalSpawnChunkCounter.chunks.size()` across regions via lock-free
  snapshot.
- `hasPlayersNearby(long)` (`:282-285`) — resolve region, delegate to its
  per-region counter.
- `getDebugStatus()` (`:287-289`) — short per-region summary; the deleted
  `ticketThrottler.getDebugStatus()` no longer exists.
- `shouldForceTicks(long)` (`:291-294`) — **KEPT signature**; enumerates
  `PerRegionTicketMap.ticketsAt(pos)` and returns true iff any has
  `forceTicks=true`. NeoForge contract preserved.

### 4.8 Tick end-of-frame
- `runAllUpdates(ChunkMap)` (`:108-147`) — **NO-OP**, always returns
  `false`. See §6.
- `purgeStaleTickets()` (`:68-94`) — **REPLACED**: delegates to
  `TicketExpiryTicker` (Phase 1.10). `ticketTickCounter` gone.

### 4.9 Debug / shutdown — **REPLACED**
- `getTicketDebugString(long)` (`:261-264`) — reads the per-region map.
- `removeTicketsOnClosing()` (`:317-343`) — walks every
  `PerRegionTicketMap`, removes everything not in
  `{UNKNOWN, POST_TELEPORT}`. Runs from main thread during shutdown after
  `RegionShutdownCoordinator` (Phase 5.5) quiesces workers, so
  single-writer.
- `hasTickets()` (`:345-347`) — OR across every region.
- `tickingTracker()` (`:312-315`, `@VisibleForTesting`) — placeholder
  empty tracker; global aggregation is meaningless under regionization.

### 4.10 Abstract seams — fulfilled by `MultiForgeChunkMap.DistanceManager` (§9)
- `isChunkToRemove(long)`, `getChunk(long)`,
  `updateChunkScheduling(long, int, ChunkHolder, int)` (`:100-106`).
  `getChunk` returns the Vanilla shell `ChunkHolder` wrapping a
  `NewChunkHolder` per contracts §1.4.

---

## 5. Region routing

Every ticket write calls
`host.regionizerFor(worldRef).regionAtChunk(x, z)`
(`ThreadedRegionizer.java:114-118`). The regionizer's `sectionToRegion`
is a `ConcurrentHashMap<SectionPos, Region>`
(`ThreadedRegionizer.java:53`) — O(1) hash lookup, no lock (contracts §4
enshrines lock-free readers).

**Caching?** Two options: (1) no cache, look up per write; (2) per-pos
cache. A cache is worth the invalidation risk (missed merge/split
listener → stale region) only if profiling shows lookup dominating.
**Recommendation:** no cache. Bench under Phase 7.4 will confirm; revisit
as a 5.x optimisation only if hot.

**Null region.** `regionAtChunk` returns null when the section isn't yet
regionized (early boot, chunk far from any player). Path: rate-limited
warn + drop (plan §Ground rule 5). The next `addTicket` after the
section joins a region succeeds. This matches today's
`ChunkHolderManagerBridge.onTicketLevelUpdated` silent-drop path; Phase
1.7 tightens the diagnostic on that side already.

---

## 6. `runAllUpdates(ChunkMap)` — critical semantics

**Vanilla.** Main thread batches ticket-level changes into
`ChunkTicketTracker.pendingUpdates` during the tick; at end-of-tick,
`ChunkMap.tick` (`ChunkMap.java:448`) calls `runAllUpdates`, which drains
`chunksToUpdateFutures` and calls
`ChunkHolder.updateFutures(chunkMap, mainThreadExecutor)` on each. This
is the single-threaded chunk-status transition point.

**MultiForge.** Per-region ticket writes happen inline via
`ChunkHolderManager.addTicket` (`ChunkHolderManager.java:81`), which
either promotes/demotes immediately or, for off-owner writes, enqueues a
full-load update on `HolderManagerRegionData.enqueueFullLoadUpdate`
(`HolderManagerRegionData.java:38`). That queue drains in the region's
tick phase 1 (`PhasedRegionTickBody.Phase.INBOUND_MAILBOX`) via Phase
5.1. Nothing is left for `runAllUpdates` to do.

**Therefore:** `runAllUpdates(ChunkMap)` becomes a NO-OP returning
`false`. Signature preserved so `ChunkMap.tick`'s call still compiles
and returns a determinate value. Downstream:
`ServerChunkCache.runDistanceManagerUpdates` (`ServerChunkCache.java:141-143`
NeoForge) is also a no-op after Phase 4.3c. `chunksToUpdateFutures`
stays declared, empty — mods reflecting on it see empty (documented
drift).

---

## 7. NeoForge extensions to preserve

| Extension | Line | Strategy |
|---|---|---|
| `forcedTickets` map | `:57` | Base field declared, empty at runtime; runtime truth lives on `MultiForge.Ticket.forceTicks`. Reflective reads see empty (drift). |
| `addRegionTicket(..., boolean forceTicks)` | `:194-199` | Signature KEPT; body sets `forceTicks` bit on runtime ticket. |
| `removeRegionTicket(..., boolean forceTicks)` | `:204-209` | Symmetric. |
| `shouldForceTicks(long)` | `:291-294` | Signature KEPT; reads MultiForge per-region tickets. `ServerChunkCache.java:364` (NeoForge) uses it as the `TickingTracker.naturalSpawningAllowed` gate — MultiForge preserves the semantic that a `forceTicks` ticket enables natural spawning on the chunk. |
| `Ticket.forceTicks` + `isForceTicks()` | `Ticket.java:12-15` NeoForge | Phase 4.8a mirrors it on the runtime `Ticket`. Vanilla `Ticket` still receives the flag via the constructor called from `addRegionTicket`; MultiForge treats the runtime bit as the truth. |

Any regression on the `shouldForceTicks` gate surfaces in mob-spawn
regression under Phase 7.4.

---

## 8. Threading contract

- **Every public/protected method** is safe from the main thread and
  from any region worker. Ticket writes route via lock-free
  `regionAtChunk` into `ChunkHolderManager.addTicket` (designed for
  cross-region callers per contracts §2.2).
- **`playersPerChunk` reads** go through
  `holderManager.holderAt(pos).playersWatching` — a
  `ConcurrentHashMap.newKeySet()` (Phase 2.4).
- **Per-region maps** (`PerRegionTicketMap`, `HolderManagerRegionData`)
  are single-writer — only the owning region worker mutates them.
  Cross-region reads use the read-only iteration path (contracts §7).
- **Cross-region walks** (`getNaturalSpawnChunkCount`, `hasTickets`,
  `removeTicketsOnClosing`, `getDebugStatus`) take a lock-free snapshot
  or run during shutdown-quiesced state.
- **`runAllUpdates`** is a no-op called from main thread; returns
  immediately.
- **No locks held by this class.** CLAUDE.md §4 golden rule applies: no
  `Thread.sleep`, no `CompletableFuture.get`, no `synchronized` on shared
  objects.

---

## 9. Interaction with `MultiForgeChunkMap`

Vanilla instantiates its `DistanceManager` as an anonymous inner class of
`ChunkMap`. Under MultiForge:

```java
final class MultiForgeChunkMap extends net.minecraft.server.level.ChunkMap {
    final DistanceManager distanceManager = new DistanceManager();

    final class DistanceManager extends MultiForgeDistanceManager {
        DistanceManager() {
            super(MultiForgeChunkMap.this.mainThreadExecutor,
                  MultiForgeChunkMap.this.worldGenExecutor,
                  MultiForgeChunkMap.this.host,
                  MultiForgeChunkMap.this.worldRef);
        }

        @Override protected boolean     isChunkToRemove(long pos) { /* pendingUnloads.contains(pos) */ }
        @Override protected ChunkHolder getChunk(long pos)        { /* shadow of holderManager.holderAt(pos) */ }
        @Override protected ChunkHolder updateChunkScheduling(long pos, int newLevel,
                                                              ChunkHolder oldHolder, int oldLevel) {
            /* update NewChunkHolder level + return shadow ChunkHolder */
        }
    }
}
```

The abstract seams stay `protected`; the inner class is `final`, not
mod-exposed. Mods subclassing `DistanceManager` through the Vanilla-typed
reference on `ServerChunkCache.chunkMap.distanceManager` see the
`DistanceManager` compile type, unchanged.

---

## 10. Migration order (Phase 4.2b roadmap)

Each step is a compile-and-test milestone.

1. **Skeleton.** Create
   `upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/chunk/MultiForgeDistanceManager.java`
   with fields, constructor, every §4 signature — bodies throw
   `UnsupportedOperationException("Phase 4.2b step N")`. Compile.
2. **Region-resolve helpers.** Private `regionFor(long)` /
   `regionOrNull(long)` around `regionizerFor(worldRef).regionAtChunk`.
   Unit test the null-region path.
3. **Ticket translate helper.** Private `translate(TicketType<T>, int,
   T, boolean)` producing `runtime.chunk.Ticket`. Depends on Phase 4.8a
   `Ticket.forceTicks`. Round-trip test.
4. **Package-private choke** (§4.2). Test writes from main thread, from
   a region worker, with null-region.
5. **Public adds** (§4.3, add side). Verify `forceTicks` preserved.
6. **Public removes** (§4.3, remove side).
7. **`updateChunkForced`** (§4.4). Integration test with Phase 1.8
   `/forceload add 0 0` → 1 FORCED ticket, 1 BORDER holder.
8. **Player tickets** (§4.5). Needs Phase 2.4 (`playersWatching`).
9. **Range reads** (§4.7 first three bullets).
10. **Per-region trackers.** Add `NaturalSpawnCounter` +
    `TickingTracker` slots to `HolderManagerRegionData`; implement
    `getNaturalSpawnChunkCount` fold.
11. **Distance updates** (§4.6): `updatePlayerTickets`,
    `updateSimulationDistance` fanout.
12. **`shouldForceTicks`** (§4.7). Integration test:
    `addRegionTicket(..., true)` → `shouldForceTicks` → mob spawn
    allowed via `TickingTracker.naturalSpawningAllowed`.
13. **Debug + shutdown** (§4.9).
14. **NO-OP stubs.** `runAllUpdates → false`; `purgeStaleTickets →
    TicketExpiryTicker.sweep()` (§4.8).
15. **Abstract seams.** Leave abstract; Phase 4.1b's inner class fulfils
    them.
16. **Confirm dead code.** `ChunkTicketTracker` and `PlayerTicketTracker`
    unreferenced under MultiForge; Vanilla source stays; patch-shell
    (4.2c) simply doesn't call them.
17. **Handoff to 4.2c.** Patch shell replaces
    `ChunkMap$DistanceManager` construction with
    `MultiForgeChunkMap$DistanceManager`.

Peak testable state is after step 12: full-cycle `addRegionTicket →
NewChunkHolder promotion → LevelChunk gate flip` works end-to-end.
