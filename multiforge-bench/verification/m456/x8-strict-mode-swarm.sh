#!/usr/bin/env bash
# MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
# All rights reserved. See LICENSE at the repository root.
#
# multiforge-bench/verification/m456/x8-strict-mode-swarm.sh
#
# Phase X task X.8 — 60-minute strict-mode headless swarm. See
# docs/verification/m456/x8-strict-mode-swarm.md for goal/procedure/
# expected result, and docs/verification/m456/README.md "How to run" for
# the full operator runbook.
#
# Unlike x1/x2/x3, this drives the real `:multiforge-bench:swarm` bench
# profile (net.multiforge.bench.harness.SwarmBench) instead of talking to
# `:neoforge:runServer` directly, at 100 simulated players for
# ticks/20 = 3600s = 60 real-time minutes (see multiforge-bench/README.md
# "Why sprint for vanilla/atm10 but real-time for swarm" — a swarm bench
# is specifically about sustained real-time load, so it never sprints).
#
# Strict mode (-Dmultiforge.regiontick.strict=on) is threaded through via
# a new `bench.extraJvmArgs` system property this task added to
# SwarmBench (see multiforge-bench/src/main/java/net/multiforge/bench/
# harness/SwarmBench.java) — it did not exist before this change, so a
# strict-mode swarm run was not previously wireable through the Gradle
# `swarm` task at all. multiforge-bench/build.gradle.kts's `swarm` task
# now also accepts `-PextraJvmArgs=`, `-PoutputFile=`, and `-PbootLog=`
# overrides for the same reason (the M9-era task hardcoded all three).
#
# Because the whole 60-minute run is one blocking Gradle invocation, this
# script backgrounds it, waits for RCON, sleeps to just short of the
# expected run length, and grabs a `/multiforge probes` snapshot while the
# server is still up — then waits on the Gradle process for its own
# flush+stop teardown. The ProbeRegistry snapshot plus the full boot log
# (which carries every `multiforge.violation` / RegionTickOverrunException
# line — same log the M9 7.6 strict-mode watchdog run reads) are this
# task's evidence.
#
# Usage:
#   x8-strict-mode-swarm.sh [--dry-run]
#
# --dry-run prints every shell/gradle/RCON command this script would run
# and exits 0 without touching a server. A real run needs the same
# prerequisites as x1-cross-region-teleport.sh, plus ~65 min of wall
# clock (matches the :multiforge-bench:x8StrictSwarm Gradle task timeout).
#
# Ends with a machine-parseable "PASS" or "FAIL" as the last stdout line.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/common.sh"

mf_parse_dry_run "$@"
mf_evidence_dir_for "x8"

PLAYERS="${MF_X8_PLAYERS:-100}"
TICKS="${MF_X8_TICKS:-72000}" # ticks/20 = 3600s = 60 real-time minutes
STRICT_JVM_ARG="-Dmultiforge.regiontick.strict=on"
# Wait 58 minutes before snapshotting probes over RCON, leaving a margin
# before the swarm's own ~60-minute teardown begins.
PROBE_SNAPSHOT_DELAY_SEC="${MF_X8_PROBE_DELAY_SEC:-3480}"

BOOT_LOG="$MF_EVIDENCE_DIR/boot.log"
GRADLE_LOG="$MF_EVIDENCE_DIR/gradle-swarm.log"
OUTPUT_JSON="$MF_EVIDENCE_DIR/patched.json"

mf_log "x8-strict-mode-swarm: players=$PLAYERS ticks=$TICKS strict=$STRICT_JVM_ARG"

overall=0

if [ "$MF_DRY_RUN" -eq 1 ]; then
    printf 'DRY-RUN would run (background, ~60min real time): %s :multiforge-bench:swarm -Pplayers=%s -Pticks=%s -PextraJvmArgs="%s" -PoutputFile=%s -PbootLog=%s > %s 2>&1\n' \
        "$MF_GRADLEW" "$PLAYERS" "$TICKS" "$STRICT_JVM_ARG" "$OUTPUT_JSON" "$BOOT_LOG" "$GRADLE_LOG"
    printf 'DRY-RUN would: poll %s for "RCON running on" up to 180s\n' "$BOOT_LOG"
    printf 'DRY-RUN would: sleep %ss, then rcon "multiforge probes" > %s/probes-snapshot.txt\n' "$PROBE_SNAPSHOT_DELAY_SEC" "$MF_EVIDENCE_DIR"
    printf 'DRY-RUN would: wait for the gradle swarm task to finish its own flush+stop teardown\n'
    printf 'DRY-RUN would: grep %s for RegionTickOverrunException / OwnershipEnforcer REROUTE / region-tick.overrun hits (expect 0)\n' "$BOOT_LOG"
    printf 'DRY-RUN would: tail -n 200 %s > %s/violation-log-tail.txt\n' "$BOOT_LOG" "$MF_EVIDENCE_DIR"
else
    (
        cd "$MF_REPO_ROOT" || exit 1
        "$MF_GRADLEW" :multiforge-bench:swarm \
            -Pplayers="$PLAYERS" -Pticks="$TICKS" \
            -PextraJvmArgs="$STRICT_JVM_ARG" \
            -PoutputFile="$OUTPUT_JSON" \
            -PbootLog="$BOOT_LOG"
    ) >"$GRADLE_LOG" 2>&1 &
    gradle_pid=$!

    if ! mf_wait_for_rcon "$BOOT_LOG" 180; then
        mf_log "WARN: RCON boot pattern not observed within 180s — continuing to wait on the gradle task"
    fi

    mf_log "x8: sleeping ${PROBE_SNAPSHOT_DELAY_SEC}s before snapshotting probes"
    sleep "$PROBE_SNAPSHOT_DELAY_SEC"
    mf_rcon "multiforge probes" >"$MF_EVIDENCE_DIR/probes-snapshot.txt" 2>&1 \
        || mf_log "WARN: could not capture probes snapshot (server may already be tearing down)"

    gradle_exit=0
    wait "$gradle_pid" || gradle_exit=$?
    mf_log "x8: gradle swarm task exit code = $gradle_exit"
    [ "$gradle_exit" -eq 0 ] || overall=1

    violation_hits="$(mf_grep_count "RegionTickOverrunException|OwnershipEnforcer.*REROUTE|region-tick\.overrun" "$BOOT_LOG")"
    mf_log "x8: strict-mode violation hits = $violation_hits"
    [ "$violation_hits" -eq 0 ] || overall=1

    tail -n 200 "$BOOT_LOG" >"$MF_EVIDENCE_DIR/violation-log-tail.txt" 2>/dev/null || true
fi

mf_pass_or_fail "$overall"
