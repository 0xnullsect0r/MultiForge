## Summary

<!-- 1–3 sentences: what changed and why. -->

## Scope

<!-- Which milestone/module. E.g. "M0 · multiforge-license", "M2 · region tick". -->

## Checklist

- [ ] Follows the concurrency contract in `docs/blueprint.md` (owner-required / global-only / async-safe declared for any new API).
- [ ] No blocking calls on region worker threads.
- [ ] Never throws from a mod code path — reroute + warn only.
- [ ] Tests added or updated; `./gradlew build` green locally.
- [ ] No dependency additions (or PR body explains license/binary-size review).
- [ ] Diff does **not** contain a real Ed25519 private key or license token.

## Test plan

- [ ] Unit tests: `./gradlew test`
- [ ] Spotless: `./gradlew spotlessCheck`
- [ ] Deterministic-mode regression (when touching NMS patches): `./gradlew :multiforge-bench:determinism`
- [ ] Manual smoke: attach steps if this touches user-visible behavior.
