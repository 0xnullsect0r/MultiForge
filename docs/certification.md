# Certification Checklist

"MultiForge-certified" is a signal, not a load gate — per CLAUDE.md
rule 5, MultiForge never refuses to load a mod, certified or not. A
certified mod is one that's been checked, automatically and manually,
against the patterns that break under MultiForge's threading model, so
a modpack author can tell at a glance which jars in their pack are
known-safe versus known-risky versus simply unaudited.

## Criteria

A mod is MultiForge-certified when all of the following hold:

- [ ] **Zero unsuppressed ERROR findings.**
      `java -jar multiforge-scanner.jar --severity=error <jar>` exits
      `0`. ERROR-severity rules (R02 `off-thread-Level.setBlock`, R03
      `blocking-future`, and any future ERROR-tier rule) detect
      patterns that are *definitely broken* under MultiForge — not
      "might be fine depending on modpack topology" — so none may be
      present, suppressed or not, for certification.
- [ ] **Every WARN finding is fixed or has a documented
      `.multiforgeignore` suppression.** WARN-tier rules (R01, R04, and
      the rest of the WARN-severity set — see
      `docs/design/scanner-rules.md` §4) flag patterns that are
      suspicious but not guaranteed broken. A suppression is
      acceptable, but it must carry the justification convention from
      the scanner spec §5.3 (why the flagged call site is actually
      safe) — an undocumented blanket suppression doesn't count.
- [ ] **Public API only.** The mod uses only `net.multiforge.api.*`.
      No reflection into `net.multiforge.runtime.*`, which is
      `@ApiStatus.Internal` and explicitly "subject to change without
      notice" per CLAUDE.md — this is a manual review point, since the
      scanner doesn't special-case reflective access beyond what R01
      and R04 already catch structurally.
- [ ] **No direct `ChunkMap`/`DistanceManager` internals access.**
      Everything goes through the `MultiForgeChunkMap` /
      `MultiForgeDistanceManager` facades. (R01.)
- [ ] **No blocking `Future`/`CompletableFuture` calls on
      `@RegionThread`-marked methods.** (R03.)
- [ ] **No off-thread block mutation, and no cross-region entity touch
      outside the migration protocol.** (R02, R05.)
- [ ] **Static mutable state reachable from tick code is
      synchronized, atomic, or per-region.** (R04.)

See [`mod-porting.md`](mod-porting.md) for the before/after recipe
matching each of these.

## Certification process

1. **Automated gate.** Run the scanner against the mod's release jar:
   ```
   java -jar multiforge-scanner.jar --severity=error mods/examplemod-1.2.3.jar
   ```
   Exit code `0` means no ERROR findings. Re-run without
   `--severity=error` (or with `--severity=warn`, the default) to see
   the full WARN + ERROR picture for the suppression review step.
2. **Suppression review.** For every WARN finding, either fix the
   underlying pattern (`mod-porting.md`) or add a justified
   `.multiforgeignore` entry using the fingerprint from the finding's
   `fingerprint` field (`docs/design/scanner-rules.md` §5).
3. **Manual review.** Confirm public-API-only usage — the scanner
   flags structural violations but doesn't do a full API-surface audit
   by itself.
4. **Sign off.** Once all criteria above are met, the mod is
   certified. There's no separate signing step or registry in v1 —
   certification is evidenced by a clean scanner report the mod author
   or modpack curator keeps alongside the jar.

The in-server `/multiforge certify` command (below) is a convenience
wrapper for step 1 — an operator with a `./mods` directory full of
jars can check certification status without leaving the game or
hand-building the scanner invocation.

## Commands

Two new subcommand groups, alongside the existing `/multiforge` tree
(`config`, `region`, `probes`, `chunks` — see
`docs/global-network.md`). Both are `op`-gated like every other
`/multiforge` command.

### `/multiforge warn`

```
/multiforge warn list     # show recent violations from ViolationLogger
/multiforge warn clear    # reset the violation history ring buffer
```

`warn list` prints the last (at most 200) violations
`ViolationLogger` has emitted, oldest first — mod id, site, and
detail, timestamped. See `docs/debugging-violations.md` for how to
read and act on the output. `warn clear` empties that history; it does
not reset the underlying rate-limit buckets, so a mod mid-burst keeps
its existing throttle window.

### `/multiforge certify`

```
/multiforge certify <modId>   # certification status for one mod's jar under ./mods
/multiforge certify all       # same, for every jar under ./mods
```

For each resolved jar, the command shells out to
`java -jar multiforge-scanner.jar --json --severity=warn <jar>`
(scanner jar path overridable via `-Dmultiforge.scanner.jar=<path>`,
default `./multiforge-scanner.jar`), and prints one pass/fail line per
rule plus an overall verdict:

```
> /multiforge certify examplemod
=== examplemod-1.2.3.jar ===
  R01: PASS
  R02: PASS
  R03: FAIL (ERROR)
  R04: PASS
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

`certify all` runs the same per-jar report for every `*.jar` under
`./mods` and reports a certified/not-certified verdict per jar. If the
scanner jar isn't present at the configured path, the command reports
that clearly and fails the command (never throws) — consistent with
CLAUDE.md rule 5's "auto-reroute and warn, never refuse" spirit
extended to tooling: a missing scanner jar is a tooling gap for the
operator to fix, not a crash.
