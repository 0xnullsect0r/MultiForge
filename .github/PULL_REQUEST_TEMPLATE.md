## Summary

<!-- 1–3 sentences: what changed and why. Name the concurrency contract a patch defends. -->

## Scope

<!-- Which module or patch group. E.g. "02-region-tick", "runtime · scheduler", "scanner". -->

## Checklist

- [ ] Follows `docs/design/barrier-tick-model.md` and `docs/concurrency-contract.md`.
- [ ] No blocking calls on region worker threads (CLAUDE.md rule 4 and its bounded exceptions).
- [ ] Never throws from a mod code path — reroute + warn only.
- [ ] Every behaviour fix has a test that fails without it (unit test or GameTest).
- [ ] GPL-3 header on every new source file (spotless / licenseCheck fail otherwise).
- [ ] No new dependency (or the PR body explains GPL-3 compatibility and binary size).
- [ ] Commits end with the co-author trailer when AI-authored (CLAUDE.md, Git workflow).
- [ ] **Patches to `net.minecraft.*`** (`multiforge-patches/`): edited through `scripts/mf-patches.py`, no new lambdas in Vanilla classes, and:
  - [ ] `cd upstream/neoforge-1.21.1 && ./gradlew --no-configuration-cache :neoforge:checkMixinTargets :tests:runGameTestServer` green
  - [ ] `./gradlew :multiforge-bench:determinism -Pworkers=1,4` passes (vanilla-parity gate)

## Test plan

- [ ] `./gradlew spotlessCheck` and the module builds (`./gradlew build`)
- [ ] GameTests (above) when the fork or runtime changed
- [ ] Manual smoke: attach steps if this touches user-visible behaviour.
