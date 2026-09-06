# 7.3 — N-worker semantic parity regression

This directory holds the artifacts for runbook task 7.3: `patched-Nw/`
is the world save captured on `develop` with the same fixed seed and
tick count as 7.2's `baseline-1w/`, but ticked with N region workers
(`cores * threadsPerCore > 1`, set via `multiforge.toml` or the
`-Dmultiforge.workers=N` JVM override) instead of one. Because
byte-identical output is not achievable under real parallel dispatch,
this run is compared against the 7.2 baseline under
`WorldDiff.DiffMode.SEMANTIC` (`-PdiffMode=SEMANTIC` on the
`:determinism` task) rather than `BYTE_IDENTICAL`. The resulting diff
summary — and, if useful, the per-region `canonicalMcaHash(...,
SEMANTIC)` values used as the semantic baseline — are saved alongside
the `patched-Nw/` capture once a run completes.
