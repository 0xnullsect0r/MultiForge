# 7.6 — Strict-mode watchdog (30 min)

This directory holds the server logs from runbook task 7.6: a 30-minute
bench-swarm run under `-Dmultiforge.regiontick.strict=on`. The pass
criteria are read directly off the log — zero
`RegionTickOverrunException` throws, zero rate-limited
`region-tick.overrun` warns, and zero `OwnershipEnforcer` REROUTE
lines — so the full server log (not just a pass/fail summary) is saved
here for each run, along with a short note on the outcome. A failing
run's stack trace and region-id/chunk-pos context (from
`RegionTickWatchdog.enterTick`) should be preserved verbatim rather than
excerpted, since that context is what a HIGH-severity issue filed
against the run will need.
