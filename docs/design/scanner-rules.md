# M6 Mod-Safety Scanner — Rule Specification

**Status:** frozen (Phase 0 task 0.4 of the M4+M5+M6 landing plan,
`plans/bubbly-jumping-comet.md`). Track C2 (`multiforge-scanner/` module,
16 tasks) implements against this spec; changes require a Phase 0
amendment.

**Purpose:** Define the scanner's architecture, the shape of a rule, the
12 frozen rules (R01–R12), the `.multiforgeignore` suppression format, the
report formats, the CLI, and the test-corpus invariant each rule must
satisfy before Track C2 is considered done.

**Non-goals:** This document does not implement anything. It does not
cover the runtime enforcement path (`OwnershipEnforcer`,
`multiforge-runtime/src/main/java/net/multiforge/runtime/ownership/OwnershipEnforcer.java`)
— the scanner is a *static*, pre-deploy check that runs against mod jars
before they ever touch a region worker thread. The two are complementary:
the scanner catches what it can see in bytecode; `OwnershipEnforcer`
catches what slips through at runtime and reroutes it per CLAUDE.md rule 5
("auto-reroute + warn is the default"). A mod that fails every scanner
rule still boots — the scanner's job is signal, not a load-time gate. Load
refusal is explicitly out of scope (CLAUDE.md rule 5 forbids it anyway).

---

## 1. Scanner architecture

### 1.1 Module and entry point

The scanner lives in `multiforge-scanner/`, a new Gradle module with **no
dependency on `upstream/neoforge-1.21.1/`** and no dependency on
`multiforge-runtime/`. It depends only on:

- `org.ow2.asm:asm:9.7` and `org.ow2.asm:asm-tree:9.7` (tree API is used
  by three rules — R03, R09, R12 — that need multi-instruction pattern
  matching the pure event-visitor API makes awkward; the rest use the
  streaming `ClassVisitor` API directly for speed).
- `com.fasterxml.jackson.core:jackson-databind` for the JSON/SARIF
  emitters (already a transitive dep elsewhere in the build, so no new
  license-compat review per CLAUDE.md's "adding a new dependency" gate).
- No Minecraft, no NeoForge, no MultiForge runtime classes. The scanner
  must run against a mod jar built for vanilla NeoForge with zero
  MultiForge awareness, and must build/test standalone the same way
  `multiforge-license` does — CLAUDE.md build section, "sub-projects that
  do not depend on Minecraft ... build standalone without the `:setup`
  step".

### 1.2 Walk strategy

```
Main.main(String[] args)
  -> CliOptions.parse(args)
  -> for each input path (jar file or directory):
       -> JarWalker.entries(path)     // ZipInputStream, filters *.class
       -> for each class entry:
            byte[] bytes = entry.read()
            ClassReader reader = new ClassReader(bytes)
            RuleEngine engine = new RuleEngine(activeRules, classContext)
            reader.accept(engine, ClassReader.SKIP_FRAMES)
       -> engine collects Finding records into a per-jar FindingSet
  -> ReportEmitter.emit(allFindings, format)
```

`ClassReader.accept` is called with `ClassReader.SKIP_FRAMES`, not `0`.
None of the 12 rules need computed stack-map frames (they match on
invocation opcodes, field access opcodes, and annotation presence — not
inferred types at merge points), and `SKIP_FRAMES` roughly halves parse
time on obfuscated/heavily-optimized mod jars, which matters because CI
(Track C4.5, `.github/workflows/scanner.yml`) runs this on every PR
touching `upstream/`. `RuleEngine.NEEDED_FLAGS` is the single source of
truth on this so a future rule that *does* need frames fails loudly in
`RuleEngineTest` rather than silently getting corrupted frame data.

`RuleEngine implements ClassVisitor` and is the fan-out point: one
`ClassReader.accept` call drives all 12 rules in a single pass over each
class file (not 12 separate passes) — mod jars in a modpack like ATM10
(Track X.8 target) can carry 300+ classes per jar. `RuleEngine.visitMethod`
returns a composite `MethodVisitor` fanning each callback out to every
rule's `MethodVisitor`; `Rule.forClass(ClassContext)` is called once per
class and returns `null` to opt out early (e.g. R04 and R10 both check
for `@Mod` before returning a non-null method visitor, so a class with no
`@Mod` annotation short-circuits both rules for free).

### 1.3 `ClassContext`

Every rule receives a `ClassContext` at `forClass(ClassContext)`:

```java
public record ClassContext(
    String className,          // internal name, e.g. "com/example/mod/FooBlock"
    String superName,
    List<String> interfaces,
    Set<String> classAnnotations,   // internal descriptors, e.g. "Lnet/neoforged/fml/common/Mod;"
    String sourceJarName,           // for report grouping / .multiforgeignore scoping
    boolean isModEntryClass         // true iff @Mod is present — cached so rules don't re-scan
) {}
```

`isModEntryClass` matters for R04 and R10 specifically (see §4). It is
computed once by `RuleEngine` before fan-out, not per-rule, to keep the
single-pass property honest.

### 1.4 Method-reachability approximation

Two rules (R02, R04) need "is this method reachable from the region-tick
thread" — a whole-program call-graph question the scanner cannot answer
precisely from a single class file (it has no closed-world view of the
mod jar, let alone NeoForge's event bus wiring). The scanner uses a
conservative **name/signature heuristic** instead of true reachability:

- A method is **tick-reachable** if its name matches a known
  NeoForge/Forge tick-adjacent event handler signature (`@SubscribeEvent`
  present + parameter type `ServerTickEvent`, `LevelTickEvent`,
  `PlayerTickEvent`, or anything ending in `TickEvent`), **or** it is
  annotated `@RegionThread` (§1.5), **or** it is reachable by a direct
  (non-virtual-dispatch) call chain of depth ≤ 3 from such a method,
  resolved within the same class file only — no cross-class call-graph;
  whole-jar closure is explicitly deferred (see §8, scoped to single-hop
  sentinel fixtures for this reason).
- This trades recall for zero cross-jar false negatives on the common
  case (a tick handler calling a private helper 1–2 hops deep — the
  dominant shape in the ATM10 corpus). It misses virtual-dispatch chains
  and reflection-invoked handlers: an accepted gap, since the scanner is
  a lint pass, not a soundness proof. R02 and R04 flag this explicitly.

### 1.5 The `@RegionThread` marker

This spec assumes `multiforge-api` grows a
`net.multiforge.api.annotation.RegionThread` marker annotation (`RUNTIME`
retention, `METHOD` target) before Track C2 lands — it does not exist in
the runtime today (no `@interface RegionThread` anywhere in the tree as
of this freeze). Its introduction is **not** part of this task; it is a
Phase 1/Track A dependency Track C2 blocks on. The scanner reads it
purely as a bytecode-visible descriptor string
(`Lnet/multiforge/api/annotation/RegionThread;`) and never loads the
annotation class, so it has no runtime dependency on `multiforge-api`
either — consistent with the "no Minecraft, no MultiForge runtime"
constraint in §1.1.

---

## 2. Rule shape

Every rule is a small, stateless class implementing:

```java
public interface Rule {
    String id();                    // "R01".."R12"
    String name();                  // one word, e.g. "direct-ChunkMap-invoke"
    Severity severity();            // WARN | ERROR

    /**
     * Called once per class visited. Return null to skip this class
     * entirely (fast-path opt-out, e.g. "not a @Mod class").
     */
    ClassVisitor forClass(ClassContext ctx, FindingSink sink);
}
```

`ClassVisitor.forClass` returns an ASM `ClassVisitor` (or, for the three
tree-API rules, an adapter that buffers a `MethodNode` and analyzes it in
`visitEnd`) whose overridden callbacks (`visitMethod`, `visitField`,
`visitAnnotation`, etc.) push zero or more `Finding` records into the
shared `FindingSink` as they detect the rule's pattern. A rule never
throws for a pattern it doesn't understand — an unparseable method body
(rare, but obfuscators produce them) is skipped with a
`RuleEngine`-level debug log line, never a scanner crash. This mirrors
CLAUDE.md rule 5's spirit (reroute + warn, never refuse) even though the
scanner is a different codebase from the runtime enforcement path.

### 2.1 `Finding`

```java
public record Finding(
    String ruleId,          // "R01".."R12"
    Severity severity,      // WARN | ERROR
    String className,       // dotted, e.g. "com.example.mod.FooBlock"
    String methodName,      // "<method>(<descriptor>)" or "<class-init>" / "<field:name>" for field-only rules
    int line,                // best-effort; -1 if no line-number table entry covers the site
    String message,          // human-readable, one sentence, includes the offending call/field
    String fingerprint       // see §5 — used for .multiforgeignore matching
) {}
```

`line` is best-effort because mod jars are sometimes built without debug
info (`-g:none`), and obfuscated jars routinely strip
`LineNumberTable`. When absent, the report still emits the finding with
`line: -1` and the method descriptor carries the identifying weight
instead — `fingerprint` degrades gracefully (§5.2).

### 2.2 Severity levels

- **WARN** — the pattern is *suspicious but not necessarily broken*: it
  may work correctly today by accident (e.g. relying on the current
  single-region-per-world topology in early MultiForge versions, or
  because the mod happens to only ever touch chunks it created and owns).
  WARN findings are exactly the class of bug the plan's rule R01
  description calls "should go through MultiForge facade" — a portability
  and forward-compat concern, not a guaranteed-broken one.
- **ERROR** — the pattern is *definitely broken under MultiForge's
  threading model*, full stop, regardless of modpack topology: a blocking
  `.get()` on a region-tick thread head-of-line-blocks the entire region
  (CLAUDE.md rule 4, "no blocking calls on a region worker thread ...
  ever"), and there is no configuration of MultiForge under which that is
  safe.

Severity is fixed per-rule (not per-finding) — no rule downgrades or
upgrades its own severity based on context. This keeps report output
deterministic and lets `--severity=error` (§7) act as a pure filter.

---

## 3. `.multiforgeignore` fingerprint format

See §5 (moved after the rule catalog is referenced heavily throughout
§4, so the fingerprint algorithm is specified once there rather than
duplicated per rule). Forward-reference: `fingerprint` in `Finding` above
is exactly the string a `.multiforgeignore` line matches against.

---

## 4. The 12 rules

Each rule below lists: ID, name, severity, detection prose, bytecode
pattern, rationale, escape hatch, and a buggy/safe code pair. The
bytecode pattern is written as ASM visitor pseudocode close enough to the
real implementation that Track C2's `RxxRule.java` files should need
only mechanical translation, not redesign.

### R01 — `direct-ChunkMap-invoke`

**Severity:** WARN. **Detects:** mod bytecode that reaches past
`ChunkSource`/`ServerChunkCache`'s public surface directly into
`net.minecraft.server.level.ChunkMap` internals, instead of the
`MultiForgeChunkMap` facade
(`upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/chunk/MultiForgeChunkMap.java`,
per `docs/design/m9-patch-strategy.md` §"ChunkMap", verdict REPLACE).

**Bytecode pattern:**
```
visitMethodInsn(INVOKEVIRTUAL | INVOKEINTERFACE, owner, name, desc, itf)
  where owner == "net/minecraft/server/level/ChunkMap" (or an unlisted subtype)
    and name is not in PUBLIC_STABLE_API   // small allowlist: getServer, level, ...
    and enclosing class is not under net/multiforge/** or net/minecraft/**
```

**Rationale:** `ChunkMap`'s field layout survives for reflective-mod
compatibility, but its *behavior* is a thin delegate shell. Calls that
reach past it into raw state, or into methods off the stable allowlist,
skip the region-aware ticket/holder bookkeeping the facade performs on
every mutation.

**Escape hatch:** `.multiforgeignore` fingerprint; common for narrow,
audited debug tooling that only reads ChunkMap state.

**Buggy:**
```java
ChunkMap cm = (ChunkMap) ((ServerChunkCache) level.getChunkSource()).chunkMap;
cm.getVisibleChunkIfPresent(pos.toLong());
```
**Safe:**
```java
level.getChunkSource().getChunkNow(pos.x, pos.z); // stable ChunkSource API
```

### R02 — `off-thread-Level.setBlock`

**Severity:** ERROR. **Detects:** a call to
`Level.setBlock(BlockPos, BlockState, int)` (or overloads) from a method
the tick-reachability heuristic (§1.4) classifies as running off the
region-tick thread — not annotated `@RegionThread`, not itself a
tick-event handler.

**Bytecode pattern:**
```
visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/Level", "setBlock", "(...)Z", false)
  guarded by: enclosingMethod NOT tick-reachable (§1.4)
              AND enclosingMethod has no @RegionThread annotation
```

**Rationale:** `Level.setBlock` mutates chunk section state with no
synchronization of its own — it assumes the caller already owns the
target region (CLAUDE.md rule 4 extended to block mutation, not just
blocking calls). An off-thread call races the owning region worker or
silently no-ops depending on patch state — a determinism hazard, hence
ERROR (contrast R01, R08).

**Escape hatch:** `.multiforgeignore` fingerprint. The fix is almost
always mechanical (wrap in `RegionizedTaskQueue.queueChunkTask`), so
suppressions here should mostly be transient.

**Buggy:**
```java
// called from a Netty IO callback thread, not a region worker
public void onDownloadComplete(BlockPos pos, BlockState result) {
    level.setBlock(pos, result, 3);
}
```
**Safe:**
```java
public void onDownloadComplete(WorldRef world, BlockPos pos, BlockState result) {
    taskQueue.queueChunkTask(world, new ChunkPos(pos), () ->
        world.level().setBlock(pos, result, 3));
}
```
(`RegionizedTaskQueue.queueChunkTask` signature per
`multiforge-runtime/src/main/java/net/multiforge/runtime/region/RegionizedTaskQueue.java:121`.)

### R03 — `blocking-future`

**Severity:** ERROR. **Detects:** `.get()`, `.get(long, TimeUnit)`,
`.join()`, `.getNow(Object)`, or `.awaitUninterruptibly()` invoked on a
receiver whose static type is, or is a subtype of,
`java.util.concurrent.Future`, `java.util.concurrent.CompletionStage`,
or `java.util.concurrent.ForkJoinTask`, lexically inside a method
annotated `@RegionThread`.

**Bytecode pattern (tree API — needs local dataflow + a per-scan `TypeHierarchy`):**
```
for each MethodNode m with @RegionThread:
    for each MethodInsnNode insn in m.instructions:
        if insn.name in {"get", "join", "getNow", "awaitUninterruptibly"}
           and TypeHierarchy.isSubtypeOfAny(insn.owner,
                 {"java/util/concurrent/Future", "java/util/concurrent/CompletionStage",
                  "java/util/concurrent/ForkJoinTask"})
        -> Finding(line = source line map lookup for insn)
```
The receiver's static type comes straight from the invoke instruction's
`owner` operand, so this works fine under `SKIP_FRAMES` (§1.2). `owner`
is not matched against a literal two-name set, though: `RuleEngine`
first-passes every class in the jar being scanned (header-only,
`SKIP_CODE`) into a `TypeHierarchy` index, so a mod-defined subtype
(`class MyFuture extends CompletableFuture<T>`) is still caught even
though its bytecode owner is `MyFuture`, not `CompletableFuture` —
`TypeHierarchy` walks the in-jar superclass/interface edges and falls
back to real classpath reflection once it reaches a JDK-only ancestor
(round-6 fork C HIGH finding). A chained idiom like
`future.orTimeout(...).join()` fires because the trailing `.join()` is
its own `INVOKEVIRTUAL`; `orTimeout` itself doesn't block and isn't in
the name set.

**Rationale:** Direct bytecode expression of CLAUDE.md rule 4: "No
blocking calls on a region worker thread. Ever." A `.get()`/`.join()` on
a region-tick thread can deadlock outright if the future depends on that
same region's tick loop, and otherwise head-of-line-blocks the region.

**Escape hatch:** `.multiforgeignore` fingerprint — reserved for futures
provably already complete at the call site. The scanner does not
special-case this automatically; it pushes the judgment call to the
ignore file plus the justification convention in §5.3.

**Buggy:**
```java
@RegionThread
void onNeighborChanged(BlockPos pos) {
    LevelChunk chunk = chunkFuture.get(); // blocks the region worker
    chunk.setBlockState(pos, Blocks.AIR.defaultBlockState());
}
```
**Safe:**
```java
@RegionThread
void onNeighborChanged(BlockPos pos) {
    chunkFuture.thenAccept(chunk ->
        taskQueue.queueChunkTask(world, new ChunkPos(pos), () ->
            chunk.setBlockState(pos, Blocks.AIR.defaultBlockState())));
}
```

### R04 — `unsync-static-mutation`

**Severity:** WARN. **Detects:** a `PUTSTATIC` to a non-`final` static
field declared in a `@Mod`-annotated class, from a method the
tick-reachability heuristic (§1.4) classifies as reachable on the
region-tick thread.

**Bytecode pattern:**
```
visitFieldInsn(PUTSTATIC, owner, name, desc)
  where owner is @Mod-annotated (or shares its top-level enclosing class)
    and the target field's ACC_FINAL bit is not set
    guarded by: enclosingMethod is tick-reachable (§1.4)
```
The field's `ACC_FINAL` bit is read from its own `visitField` callback
and reconciled against `PUTSTATIC` sites at `visitEnd`, rather than
assuming fields are visited before methods — robust to obfuscator
reordering.

**Rationale:** Multiple regions tick concurrently on different worker
threads — the whole point of the project. A static field with no
`volatile`/`Atomic*`/`final` qualifier written from tick-reachable code
is exactly the unsynchronized shared mutable state that races the moment
two regions both host the mod's blocks/entities — the same hazard
`NewChunkHolder`'s field table (`docs/design/m9-contracts.md` §1.1) was
designed to avoid from the start.

**Escape hatch:** `.multiforgeignore` fingerprint — legitimate for a
counter intentionally guarded by external synchronization the scanner
cannot see (a `synchronized` method it doesn't correlate with the write,
per the whole-program limitation in §1.4).

**Buggy:**
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
**Safe:**
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

### R05 — `entity-setpos-off-coord`

**Severity:** ERROR. **Detects:** a direct call to
`Entity.setPos(double,double,double)` or `setPosRaw` from mod code, not
made from within
`net.multiforge.runtime.entity.EntityMigrationCoordinator` itself.

**Bytecode pattern:**
```
visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/entity/Entity", "setPos"|"setPosRaw", "(DDD)V", false)
  guarded by: ClassContext.className does NOT start with "net/multiforge/runtime/entity/"
```

**Rationale:** Cross-region entity movement is not just "set the
coordinate fields" — `EntityMigrationCoordinator.beginMigration`/
`completeAt`
(`multiforge-runtime/src/main/java/net/multiforge/runtime/entity/EntityMigrationCoordinator.java:56,89`)
exists because a boundary-crossing position write must also transfer
ownership, drain in-flight per-region state, and (per
`beginMigrationWithTree`, line 115) carry passengers atomically. A raw
`setPos` silently desyncs region ownership bookkeeping from the entity's
actual coordinates.

**Escape hatch:** `.multiforgeignore` fingerprint — legitimate for small,
same-region cosmetic nudges that never cross a boundary in practice; the
scanner cannot prove that, so it flags all direct calls.

**Buggy:**
```java
void teleportToBase(Entity e, BlockPos base) {
    e.setPos(base.getX(), base.getY(), base.getZ()); // may cross region ownership
}
```
**Safe:**
```java
void teleportToBase(MigratingEntityRef ref, WorldRef world, BlockPos base) {
    migrationCoordinator.beginMigration(ref, world, base);
}
```

### R06 — `direct-ServerChunkCache-mutation`

**Severity:** WARN. **Detects:** mod calls to `ServerChunkCache`'s
mutating surface (`addRegionTicket`, `removeRegionTicket`,
`updateChunkForced`, and any `void`-returning method not named
`get*`/`is*`/`has*`) bypassing `ServerChunkCacheDelegate`
(`upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/chunk/ServerChunkCacheDelegate.java`,
per `docs/design/m9-patch-strategy.md` §"ServerChunkCache", verdict PATCH
heavy).

**Bytecode pattern:**
```
visitMethodInsn(INVOKEVIRTUAL, owner, name, desc, itf)
  where owner == "net/minecraft/server/level/ServerChunkCache"
    and name in MUTATING_METHOD_ALLOWLIST
    and enclosing class is mod code (not net/minecraft/** or net/multiforge/**)
```

**Rationale:** Same shape as R01 one layer up: `ServerChunkCache` is the
deliberately stable API boundary (44 files use `getChunkSource()`, per
m9-patch-strategy.md, which is why it's PATCH not REPLACE), but its
mutating methods route through `ServerChunkCacheDelegate` for per-region
resolution. Direct calls usually still work but signal ticket-related
activity outside the ticket API — worth a WARN for review.

**Escape hatch:** `.multiforgeignore` fingerprint.

**Buggy:**
```java
// bypasses delegate's forced-chunk region bookkeeping
serverLevel.getChunkSource().updateChunkForced(pos, true);
```
**Safe:**
```java
// goes through the ticket API, which the delegate wires correctly
serverLevel.getChunkSource().addRegionTicket(TicketType.FORCED, pos, 2, pos);
```

### R07 — `raw-DistanceManager-ticket`

**Severity:** WARN. **Detects:** mod calls to `DistanceManager.addTicket`
where the receiver resolves to `net.minecraft.server.level.DistanceManager`
directly, rather than through `MultiForgeDistanceManager`
(`upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/chunk/MultiForgeDistanceManager.java`,
per `docs/design/m9-patch-strategy.md` §"DistanceManager", verdict
REPLACE).

**Bytecode pattern:**
```
visitMethodInsn(INVOKEVIRTUAL, owner, "addTicket", desc, false)
  where owner == "net/minecraft/server/level/DistanceManager"
    and enclosing class is mod code
```

**Rationale:** `DistanceManager`'s ticket/tracker state — `tickets`,
`playersPerChunk`, `ticketTracker`, `naturalSpawnChunkCounter` — "all
becomes per-region under MultiForge... keeping the Vanilla trackers alive
as a parallel truth is a bug factory" (m9-patch-strategy.md verbatim).
Direct `addTicket` calls risk writing to exactly that parallel truth.

**Escape hatch:** `.multiforgeignore` fingerprint.

**Buggy:**
```java
distanceManager.addTicket(TicketType.START, pos, 22, Unit.INSTANCE);
```
**Safe:**
```java
serverLevel.getChunkSource().addRegionTicket(TicketType.START, pos, 22, Unit.INSTANCE);
```

### R08 — `off-thread-BlockEntity-setChanged`

**Severity:** WARN. **Detects:** a call to `BlockEntity.setChanged()`
from a method not classified tick-reachable for that entity's owner
(same heuristic family as R02, §1.4) — not `@RegionThread`, not itself a
tick-event handler.

**Bytecode pattern:**
```
visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/block/entity/BlockEntity", "setChanged", "()V", false)
  guarded by: enclosingMethod NOT tick-reachable (§1.4)
              AND enclosingMethod has no @RegionThread annotation
```

**Rationale:** `setChanged` flips the block entity's dirty flag, feeding
the autosave path (`NewChunkHolder.markDirty`,
`docs/design/m9-contracts.md` §1.2, "owning region worker only"). An
off-owner-thread call is a lower-severity cousin of R02: it races the
dirty flag against the owner's own writes, causing a spurious or missed
autosave rather than corrupting block state directly — hence WARN, not
ERROR.

**Escape hatch:** `.multiforgeignore` fingerprint.

**Buggy:**
```java
// called from an inventory GUI packet handler thread
void onGuiSlotChanged(BlockEntity be) {
    be.setChanged();
}
```
**Safe:**
```java
void onGuiSlotChanged(WorldRef world, BlockPos pos, BlockEntity be) {
    taskQueue.queueChunkTask(world, new ChunkPos(pos), be::setChanged);
}
```

### R09 — `sync-io-in-tick`

**Severity:** ERROR. **Detects:** a call to a fixed, extendable
synchronous-disk-I/O allowlist (round-6 fork C HIGH finding extended
this from the original two-owner, three-exact-name list) inside a
method the tick-reachability heuristic (§1.4) classifies as
region-tick-reachable:

- any `read*`/`write*`/`transferTo` call on `InputStream`,
  `OutputStream`, `FileInputStream`, `FileOutputStream`,
  `DataInputStream`, `DataOutputStream`, `BufferedInputStream`, or
  `BufferedOutputStream` (owner matched by exact type, not
  hierarchy — this covers a wrapped stream like `new
  BufferedInputStream(fileInputStream)` since `read`/`write` are
  called directly on the wrapper);
- any `read*`/`write*` call on `FileChannel` or
  `AsynchronousFileChannel`;
- every method on `RandomAccessFile` (opening or operating on one is
  inherently synchronous disk I/O, not just its `read*` methods);
- `Files.readAllBytes`, `Files.readString`, `Files.newInputStream`,
  `Files.lines`, `Files.readAllLines`, `Files.newBufferedReader`,
  `Files.newBufferedWriter` — matched by method name only, so every
  overload (e.g. `Files.readString(Path, Charset)`) is covered without
  enumerating descriptors. `Files.write`/`Files.newOutputStream` are
  deliberately not on this list (see `R09SyncIoInTickTest`).

**Bytecode pattern (tree API — same shape as R03):**
```
for each MethodNode m classified tick-reachable (§1.4):
    for each MethodInsnNode insn in m.instructions:
        if (insn.owner, insn.name) in SYNC_IO_ALLOWLIST
        -> Finding(...)
```

**Rationale:** Synchronous disk I/O on the region-tick thread blocks that
region's tick for however long the page-cache miss (or network
filesystem stall, common in containerized deployments) takes — the
I/O-shaped sibling of CLAUDE.md rule 4's blocking-call prohibition, with
the same head-of-line-blocking effect as R03.

**Escape hatch:** `.multiforgeignore` fingerprint — legitimate for
one-time startup reads the tick-reachability heuristic over-classified
(a known weakness; see §1.4's stated tradeoff).

**Buggy:**
```java
@RegionThread
void onChunkLoad(ChunkPos pos) {
    byte[] cfg = Files.readAllBytes(configPath); // blocks the region worker on disk I/O
    applyConfig(cfg);
}
```
**Safe:**
```java
@RegionThread
void onChunkLoad(ChunkPos pos) {
    AsyncScheduler.runNow(modId, task -> {
        byte[] cfg = readBytesUnchecked(configPath);
        taskQueue.queueChunkTask(world, pos, () -> applyConfig(cfg));
    });
}
```

### R10 — `thread-start-in-mod-ctor`

**Severity:** WARN. **Detects:** a `new Thread(...)` allocation followed
by `.start()` (or a direct `Executors.new*` construction) inside a
`@Mod` class's constructor or a method reachable from
`FMLCommonSetupEvent`/`FMLClientSetupEvent` — the standard mod-init
surface.

**Bytecode pattern (tree API):**
```
for each MethodNode m in a @Mod class where
     m.name == "<init>" or m handles FMLCommonSetupEvent/FMLClientSetupEvent:
    scan m.instructions for:
        NEW "java/lang/Thread" ... INVOKEVIRTUAL owner="java/lang/Thread" name="start"
        or INVOKESTATIC owner="java/util/concurrent/Executors" name starts with "new"
    -> Finding(...)
```

**Rationale:** Arbitrary mod-spawned threads are invisible to
MultiForge's scheduler and region model — they can call back into
region-owned state with no ownership protocol, defeating CLAUDE.md rule
4 and the `AsyncScheduler`'s task-cancellation guarantees
(`AsyncScheduler.cancelTasks(ModIdentifier)`,
`multiforge-api/src/main/java/net/multiforge/api/folia/AsyncScheduler.java:32`
— an unknown thread can't be cancelled on mod unload/reload).

**Escape hatch:** `.multiforgeignore` fingerprint — some mods
legitimately need a raw thread (e.g. wrapping a blocking native
callback); the scanner cannot verify isolation, so it flags and defers.

**Buggy:**
```java
@Mod("examplemod")
public class ExampleMod {
    public ExampleMod() {
        new Thread(this::pollRemoteConfig).start();
    }
}
```
**Safe:**
```java
@Mod("examplemod")
public class ExampleMod {
    public ExampleMod() {
        AsyncScheduler.runAtFixedRate(MOD_ID, task -> pollRemoteConfig(), 0, 30, TimeUnit.SECONDS);
    }
}
```

### R11 — `reflect-on-neoforged-internal`

**Severity:** WARN. **Detects:** `Class.getDeclaredField`,
`Class.getDeclaredMethod`, or `Field.setAccessible(true)` calls where the
string-constant class-name argument (an `LDC` immediately preceding the
call, matched by local def-use within the same method) names a class
under `net.neoforged.neoforge.*` or `net.minecraft.*` whose target member
is not on a small allowlist of stable, known-safe reflection targets.

**Bytecode pattern:**
```
LDC "net.neoforged.neoforge.something.Internal"   // or a net.minecraft.* string
...
INVOKEVIRTUAL java/lang/Class.getDeclaredField / getDeclaredMethod
...
(optionally) INVOKEVIRTUAL java/lang/reflect/{Field,Method}.setAccessible (Z)V
```

**Rationale:** Reflection on NeoForge/Vanilla internals is exactly the
access m9-patch-strategy.md repeatedly flags as needing explicit
preservation guarantees (e.g. "mods may still probe
`chunkholder.currentlyLoading` so keep the field wired"). A mod reaching
an internal not on that list is one refactor from `NoSuchFieldException`
— and, worse, is invisible to every other rule here, since reflection
bypasses every static call-site check R01–R10 perform.

**Escape hatch:** `.multiforgeignore` fingerprint — expected to have the
highest false-positive rate of the twelve (debug mods, compat shims, and
dev tooling all legitimately reflect); liberal suppression is fine here.

**Buggy:**
```java
Field mailbox = ChunkMap.class.getDeclaredField("mainThreadMailbox");
mailbox.setAccessible(true);
```
**Safe:**
```java
// Use the stable public API instead of reflecting into ChunkMap internals.
level.getChunkSource().getChunkNow(x, z);
```

### R12 — `capture-server-in-lambda`

**Severity:** ERROR. **Detects:** a lambda (`invokedynamic` with a
`LambdaMetafactory` bootstrap) whose captured variables include a typed
`MinecraftServer` or `ServerLevel` parameter, where the resulting lambda
instance is itself stored into a `static` field (a `PUTSTATIC` within ≤ 2
instructions / one assignment chain of the `invokedynamic` result).

**Bytecode pattern:**
```
INVOKEDYNAMIC bootstrap=LambdaMetafactory, capturedArgTypes contains
    "Lnet/minecraft/server/MinecraftServer;" or "Lnet/minecraft/server/level/ServerLevel;"
...
PUTSTATIC <any static field>   // within ≤ 2 instructions of the invokedynamic result
```

**Rationale:** A leak/lifecycle bug specific to MultiForge's shutdown
story: `RegionShutdownCoordinator`
(`multiforge-runtime/src/main/java/net/multiforge/runtime/shutdown/RegionShutdownCoordinator.java`)
tears down region workers and expects `MinecraftServer` references
reachable only through paths it controls. A lambda that captures the live
server and is pinned in a static field survives server shutdown
(world unload / `/reload`) and holds the entire server object graph
alive — every entity, chunk, region — as a classic static-field GC leak;
a same-JVM restart (dev harnesses, some hosts) then sees duplicate/ghost
`MinecraftServer` instances. ERROR because the failure mode is a hard
leak with no graceful degradation.

**Escape hatch:** `.multiforgeignore` fingerprint — reserved for mods
that explicitly null the static field in their own `ServerStoppedEvent`
hook, which the scanner cannot verify statically.

**Buggy:**
```java
public class ExampleMod {
    static Supplier<Boolean> isHardcore; // static, long-lived

    @SubscribeEvent
    static void onServerStarting(ServerStartingEvent e) {
        MinecraftServer server = e.getServer();
        isHardcore = () -> server.isHardcore(); // captures + leaks MinecraftServer
    }
}
```
**Safe:**
```java
public class ExampleMod {
    @SubscribeEvent
    static void onServerStarting(ServerStartingEvent e) {
        // Re-fetch the current server each call instead of capturing it.
        Supplier<Boolean> isHardcore = () -> ServerLifecycleHooks.getCurrentServer().isHardcore();
        register(isHardcore);
    }
}
```

---

## 5. `.multiforgeignore` fingerprint format

### 5.1 File location and scope

One `.multiforgeignore` file per scanned jar-or-directory root, discovered
at `<input-path-parent>/.multiforgeignore` (sibling to the jar, or inside
the directory root for an unpacked directory scan). A global
`.multiforgeignore` at the CLI's working directory applies across all
inputs in that invocation, and per-input files add to (never replace) it
— a layered lookup a modpack CI job (Track C4.5) needs: one repo-root
file for patterns the whole modpack accepts, plus per-mod files for
narrow suppressions the modpack maintainer didn't write.

### 5.2 Line format

```
<rule-id>:<class-fqn>#<method>#<line-hash>
```

- `<rule-id>` — `R01`..`R12`, exact match, case-sensitive.
- `<class-fqn>` — dotted fully-qualified class name, exact match.
- `<method>` — method name + erased descriptor
  (`onGuiSlotChanged(Lnet/minecraft/world/level/block/entity/BlockEntity;)V`),
  or the literal token `<field:name>` for field-only findings (R04's
  finding key), or `<class-init>` for class-level findings that have no
  single method (none of the 12 rules currently produce class-level-only
  findings, but the format reserves the token for forward compat).
- `<line-hash>` — a stable content hash of the **finding's bytecode
  window**, not the source line number: SHA-256 (first 12 hex chars) of
  the mnemonic + operand-summary of the flagged instruction plus its
  immediate ±2 instructions in the same basic block — a small,
  deterministic slice, not the whole method body. A pure source-line move
  (a blank line added, an unrelated method reordered above this one)
  changes the `LineNumberTable` entry but not the instruction window, so
  the hash — and the suppression — survives. A refactor that changes the
  actual flagged call (different arguments/receiver, or a new try/catch
  altering the basic block) changes the hash, correctly invalidating the
  suppression and forcing re-review.

Example line:
```
R03:com.example.mod.ChunkListener#onNeighborChanged(Lnet/minecraft/core/BlockPos;)V#a1b2c3d4e5f6
```

### 5.3 Suppression discipline

`IgnoreFile.java` (Track C2.16) requires no accompanying comment by
format, but the report emitters (§6) print a `# TODO justify` marker next
to any line with no preceding `#`-comment, as a soft nudge rather than an
enforced requirement — enforcing it would be a load-refusal-adjacent gate
CLAUDE.md rule 5 already forbids, so CI/report friction is the pressure
point instead.

### 5.4 Matching semantics

A `Finding` is suppressed iff a `.multiforgeignore` line matches on all
three of `rule-id`, `class-fqn`, and `method` **and** the line-hash
matches. A line-hash mismatch on an otherwise-matching entry produces a
**visible "stale suppression" note** in the report (distinct from a fresh
finding) rather than silently either suppressing or un-suppressing —
this surfaces drift for the reviewer instead of hiding it.

---

## 6. Report format

### 6.1 JSON (default)

```json
{
  "scannerVersion": "1.0.0",
  "asmVersion": "9.7",
  "scannedAt": "2026-09-06T00:00:00Z",
  "inputs": ["docker/atm10-modpack/mods/examplemod-1.2.3.jar"],
  "summary": { "errors": 2, "warnings": 5, "suppressed": 1, "staleSuppressions": 0 },
  "findings": [
    {
      "ruleId": "R03",
      "severity": "ERROR",
      "className": "com.example.mod.ChunkListener",
      "method": "onNeighborChanged(Lnet/minecraft/core/BlockPos;)V",
      "line": 42,
      "message": "CompletableFuture.get() called from @RegionThread method onNeighborChanged",
      "fingerprint": "R03:com.example.mod.ChunkListener#onNeighborChanged(Lnet/minecraft/core/BlockPos;)V#a1b2c3d4e5f6",
      "sourceJar": "examplemod-1.2.3.jar"
    }
  ],
  "staleSuppressions": []
}
```

`JsonEmitter.java` (Track C2.14) is the canonical format — SARIF is a
projection of the same `Finding` list, not a parallel computation, so the
two formats can never disagree about what was found.

### 6.2 SARIF (`--sarif`)

Standard SARIF 2.1.0 for GitHub code-scanning integration
(`.github/workflows/scanner.yml`, Track C4.5):

```json
{
  "$schema": "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json",
  "version": "2.1.0",
  "runs": [{
    "tool": {
      "driver": {
        "name": "multiforge-scanner",
        "version": "1.0.0",
        "rules": [
          { "id": "R01", "name": "direct-ChunkMap-invoke",
            "shortDescription": { "text": "Direct call into ChunkMap internals" },
            "defaultConfiguration": { "level": "warning" } }
          /* ... one entry per R01-R12, level "warning" or "error" per §4 severities ... */
        ]
      }
    },
    "results": [{
      "ruleId": "R03",
      "level": "error",
      "message": { "text": "CompletableFuture.get() called from @RegionThread method onNeighborChanged" },
      "locations": [{
        "physicalLocation": {
          "artifactLocation": { "uri": "examplemod-1.2.3.jar!/com/example/mod/ChunkListener.class" },
          "region": { "startLine": 42 }
        }
      }],
      "partialFingerprints": { "multiforgeFingerprint/v1": "a1b2c3d4e5f6" }
    }]
  }]
}
```

`level` maps WARN → `"warning"`, ERROR → `"error"` per §2.2. SARIF's
`partialFingerprints` carries the same line-hash used in
`.multiforgeignore` (§5.2), giving GitHub's code-scanning UI stable
issue identity across commits for free.

---

## 7. CLI shape

```
java -jar multiforge-scanner.jar [--sarif|--json] [--severity=warn|error] <jar-or-dir>...
```

- Default output format: JSON to stdout (`--json` is an explicit no-op
  alias for discoverability; `--sarif` switches format).
- `--severity=warn` (default): report WARN and ERROR findings.
  `--severity=error`: report ERROR only (WARN findings are still computed
  for summary counts, just filtered from the `findings` array). This is a
  report-time filter, not a rule-disable switch — no flag disables
  individual rules in v1.
- `<jar-or-dir>...` — one or more paths; a directory is walked
  recursively for `*.jar` files, not loose `.class` files (unpacked trees
  are a test-corpus-only input behind a `--unpacked` flag reserved for
  `RuleEngineTest`, not part of the public CLI surface).
- Exit code: `0` if no ERROR findings (WARN-only is a green run, matching
  CLAUDE.md rule 5's "never refuse to load" spirit extended to CI), `1`
  if any unsuppressed ERROR finding is present, `2` on a scanner internal
  failure (bad jar, I/O error) so CI can tell "found problems" from
  "scanner broke".

Example CI invocation (Track C4.5 target):
```
java -jar multiforge-scanner.jar --sarif docker/atm10-modpack/mods/*.jar > scanner-results.sarif
```

---

## 8. Test invariants

Track C2.15 commits, under `multiforge-scanner/src/test/resources/`, a
fixture jar pair **per rule**: `R0N-bad-1.jar`..`R0N-bad-3.jar` (three
independent bytecode shapes that should each trigger the rule — not just
three trivial variations of the same call site, but three genuinely
different call shapes drawn from the buggy-example family in §4, e.g. for
R03: a direct `.get()`, a `.join()`, and a `.get(long, TimeUnit)`) and
`R0N-good-1.jar`..`R0N-good-3.jar` (three shapes that must **not**
trigger it — including at least one "near miss" per rule, i.e. a shape
that exercises the same method name/owner but fails one of the rule's
guard conditions, such as an `Entity.setPos` call made from *inside*
`EntityMigrationCoordinator` itself for R05's good-3, or a
`CompletableFuture.get()` inside a method with no `@RegionThread`
annotation for R03's good-3).

`RuleEngineTest` (or per-rule `RxxRuleTest`) asserts, for every one of
the 12 × 6 = 72 fixture jars:

```java
@ParameterizedTest
@MethodSource("allRuleFixtures")
void ruleFiresExactlyOnBadFixturesNeverOnGood(RuleFixtureCase c) {
    FindingSet findings = RuleEngine.scan(c.jarPath(), Set.of(c.rule()));
    if (c.expectedFire()) {
        assertThat(findings.forRule(c.rule().id())).isNotEmpty();
    } else {
        assertThat(findings.forRule(c.rule().id())).isEmpty();
    }
}
```

This is the invariant Track X.5 checks at the milestone exit gate ("12/12
rules fire on bad jars, 0/12 on good jars",
`./gradlew :multiforge-scanner:test`). A rule that fires on any
`-good-*` fixture is a false positive and blocks the exit gate exactly as
hard as a rule that fails to fire on a `-bad-*` fixture — the test suite
does not distinguish "somewhat matched" from "fired"; `Finding` presence
is binary per fixture.

Fixture jars are built from tiny, hand-written `.class` files compiled
against a minimal stub classpath (stub `Level`, `ChunkMap`,
`MinecraftServer`, etc. — Track C2.15 is responsible for authoring these
stubs; they live alongside the fixtures, not in `multiforge-runtime` or
`upstream/`, preserving the "no Minecraft dependency" property from
§1.1) rather than compiled against the real NeoForge jar, so the test
suite builds and runs standalone and fast, matching the
`multiforge-license`-style "no `:setup` step" build path CLAUDE.md calls
out for non-Minecraft-dependent modules.
