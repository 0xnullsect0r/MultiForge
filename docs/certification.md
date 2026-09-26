# Certification Checklist

"MultiForge-certified" is a signal, not a load gate. Per CLAUDE.md rule 5,
MultiForge never refuses to load a mod, certified or not. A certified mod
has been checked, by the scanner and by hand, against the patterns that
misbehave when regions tick in parallel, so a modpack author can tell
which jars are known-good, which are known-risky, and which are
unaudited.

## The scanner

`multiforge-scanner` is a standalone command-line tool that reads a mod
jar's bytecode (ASM) and reports findings against 12 rules. It never runs
mod code. Build it with `./gradlew :multiforge-scanner:jar`; the jar
bundles its dependencies.

```
java -jar multiforge-scanner.jar [--json|--sarif] [--severity=warn|error] [--ignore-file <path>] <jar-or-dir>...
```

- Output is JSON by default, SARIF 2.1.0 with `--sarif`.
- `--severity=error` reports only ERROR findings (a report filter; every
  rule still runs).
- Exit code: `0` no unsuppressed ERROR finding, `1` at least one, `2`
  usage or I/O error.
- Suppressions are read from `.multiforgeignore` in the working
  directory, next to each input, and from `--ignore-file`; they add up.

### Rules

| Rule | Name | Severity | Flags |
|---|---|---|---|
| R01 | `direct-ChunkMap-invoke` | WARN | Calls into `ChunkMap` internals instead of `ChunkSource` |
| R02 | `off-thread-Level.setBlock` | ERROR | `Level.setBlock` from a method that is not tick-reachable |
| R03 | `blocking-future` | ERROR | `Future`/`CompletionStage`/`ForkJoinTask` blocking waits in tick-reachable code |
| R04 | `unsync-static-mutation` | WARN | Non-final static field of a `@Mod` class written from tick-reachable code |
| R05 | `entity-setpos-off-coord` | WARN | Any direct `Entity.setPos`/`setPosRaw` call (a long jump should use `teleportTo`) |
| R06 | `direct-ServerChunkCache-mutation` | WARN | Mutating `ServerChunkCache` calls |
| R07 | `raw-DistanceManager-ticket` | WARN | Direct `DistanceManager.addTicket` |
| R08 | `off-thread-BlockEntity-setChanged` | WARN | `BlockEntity.setChanged` from a method that is not tick-reachable |
| R09 | `sync-io-in-tick` | ERROR | Synchronous disk I/O in tick-reachable code |
| R10 | `thread-start-in-mod-ctor` | WARN | Raw `Thread`/`Executors` construction in a `@Mod` constructor or setup handler |
| R11 | `reflect-on-neoforged-internal` | WARN | Reflection (`getDeclaredField`, `setAccessible`, …) into NeoForge or Vanilla internals |
| R12 | `capture-server-in-lambda` | ERROR | A lambda capturing `MinecraftServer`/`ServerLevel` stored in a static field |

The full specification is [`design/scanner-rules.md`](design/scanner-rules.md).
R01, R06 and R07 flag direct use of the chunk system (`ChunkMap`,
`ServerChunkCache` mutations, `DistanceManager` tickets): it is the server
thread's, so code that can run in a region tick should do it through
`ServerDomains.global()`. R05 flags raw position writes, since a jump into
another region from a region tick skips the deferral `teleportTo` gets (see
[`mod-porting.md`](mod-porting.md)).

### Tick reachability

R02, R04, R08 and R09 only fire in (or, for R02/R08, only *outside*)
methods the scanner considers reachable from a region tick. A method is a
seed if:

- it carries `@RegionThread` (on the method; a type-level annotation is
  not read), or
- its sole parameter is a tick event: a type whose simple name ends in
  `TickEvent`, or a class nested in one (`EntityTickEvent$Post`,
  `LevelTickEvent$Pre`). `@SubscribeEvent` is not required, so handlers
  registered with `addListener(...)` count too.

From the seeds, calls to methods of the same class are followed to a
depth of 3. Virtual dispatch, reflection and calls into other classes are
not followed; this is a lint heuristic, not a proof.

### Suppressions

A `.multiforgeignore` line is a finding's fingerprint:

```
<rule-id>:<class-fqn>#<method><descriptor>#<line-hash>
```

`<line-hash>` hashes the flagged instruction and its neighbours, so a
suppression survives unrelated edits but goes stale when the flagged call
changes. A stale suppression is reported separately
(`staleSuppressions` in the JSON), neither silently applied nor silently
dropped. Lines starting with `#` are comments; put the justification in a
comment directly above the line it covers. The scanner does not enforce
this.

### The corpus gate

CI (`.github/workflows/scanner.yml`) runs
`./gradlew :multiforge-scanner:scanCorpus`, which scans the
`multiforge-testmods` fixture mods (`writer`, `legacy`) and the client
debug mod and compares one line per finding with
`multiforge-scanner/corpus/expected.txt`. A rule change that adds, drops
or moves a finding on those jars fails the build until the expectation is
regenerated on purpose (`-PupdateCorpus`). Today the only expected
findings are the legacy fixture's two unsynchronised static counters
(R04).

## Criteria

A mod is MultiForge-certified when all of the following hold:

- [ ] **Zero unsuppressed ERROR findings.**
      `java -jar multiforge-scanner.jar --severity=error <jar>` exits `0`.
      ERROR rules (R02, R03, R09, R12) flag patterns that are wrong
      under parallel region ticks in the general case. An ERROR
      suppression needs a justification the reviewer accepts.
- [ ] **Every WARN finding is fixed or suppressed with a justification.**
      A suppression without a comment explaining why the flagged site is
      safe does not count.
- [ ] **Public API only.** The mod uses only `net.multiforge.api.*` from
      MultiForge; `net.multiforge.runtime.*` is internal and changes
      without notice. Manual check: the scanner does not look for this.
- [ ] **No blocking calls in tick code.** No `Future.get()`/`join()`,
      `Thread.sleep` or synchronous disk I/O on a region worker (R03, R09;
      both see tick-reachable code only, within one class, so check other
      tick paths by hand).
- [ ] **No world access from foreign threads.** Network handlers use
      `IPayloadContext.enqueueWork`; background threads and
      `ServerDomains.async()` tasks hand world work to
      `ServerDomains.region(...)` (R02, R08).
- [ ] **Static mutable state reachable from tick code is synchronised or
      atomic, or the mod is classified `legacy`.** (R04.)

See [`mod-porting.md`](mod-porting.md) for the before/after recipe for
each.

## Process

1. **Automated gate.** Run the scanner on the release jar:
   ```
   java -jar multiforge-scanner.jar --severity=error mods/examplemod-1.2.3.jar
   ```
   Then run it without `--severity=error` to see WARN findings too.
2. **Suppression review.** For each finding, fix the pattern or add a
   justified `.multiforgeignore` line using the finding's `fingerprint`.
3. **Manual review.** Public-API-only use, blocking calls outside
   `@RegionThread` methods, threads the mod starts.
4. **Sign-off.** There is no signing step and no registry; certification
   is evidenced by a clean scanner report kept with the jar.

## In-game commands

Both are part of the `/multiforge` tree (see
[`multiforge-command.md`](multiforge-command.md)) and require operator
permission.

### `/multiforge certify`

```
/multiforge certify <modId>   # jars under ./mods whose file name starts with <modId> (case-insensitive)
/multiforge certify all       # every *.jar directly under ./mods
```

For each jar the command runs
`java -jar <scanner> --json --severity=warn <jar>` as a child process and
prints one PASS/FAIL line per rule and a verdict. The scanner jar is
`./multiforge-scanner.jar` in the server's working directory, or the path
in `-Dmultiforge.scanner.jar=<path>`. Suppression files in the server
directory and in `mods/` apply as they do on the command line.

```
> /multiforge certify examplemod
=== examplemod-1.2.3.jar ===
  R01: PASS
  R02: PASS
  R03: FAIL (ERROR)
  R04: FAIL (WARN)
  R05: PASS
  R06: PASS
  R07: PASS
  R08: PASS
  R09: PASS
  R10: PASS
  R11: PASS
  R12: PASS
NOT CERTIFIED: examplemod-1.2.3.jar
```

The verdict is `CERTIFIED` when no rule has an unsuppressed ERROR
finding; WARN findings are shown but do not change it (the suppression
review above is still up to a person). If the scanner jar is missing or
the scanner fails, the jar is reported `NOT CERTIFIED` with the reason;
the command never throws. The scanner runs synchronously, so the server
stalls while it scans.

### `/multiforge warn`

```
/multiforge warn list     # the last (at most 200) logged violation warnings, oldest first
/multiforge warn clear    # empty that list; rate-limit budgets are not reset
```

See [`debugging-violations.md`](debugging-violations.md) for reading the
output.
