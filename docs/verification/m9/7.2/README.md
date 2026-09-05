# 7.2 — Byte-identical single-worker regression

This directory holds the artifacts for runbook task 7.2: `baseline-1w/`
is the world save captured from the last known-good pre-M9 tag ticked
for 20 minutes under a fixed seed with a single tick worker, and
`patched-1w/` is the same seed/tick-count capture taken on `develop`
with the M9 ownership patches applied. Both are produced out-of-band
(headless server launch is a manual step — see runbook §2, and the
known launcher-wrapper gap in §7). The resulting
`DeterminismHarness`/`WorldDiff` output — the pass/fail summary and, on
failure, the diverging-chunk detail — is saved alongside the two
captures once a run completes, so a failed run's evidence survives
past the terminal scrollback that produced it.
