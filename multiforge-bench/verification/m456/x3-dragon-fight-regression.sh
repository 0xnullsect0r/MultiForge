#!/usr/bin/env bash
# MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
# All rights reserved. See LICENSE at the repository root.
#
# multiforge-bench/verification/m456/x3-dragon-fight-regression.sh
#
# Phase X task X.3 — dragon fight regression. See
# docs/verification/m456/x3-dragon-fight-regression.md for goal/procedure/
# expected result, and docs/verification/m456/README.md "How to run" for
# the full operator runbook.
#
# ADAPTATION NOTE: there is no automated "bot" that flies a real combat
# loop against the dragon in this repo (multiforge-bench stays MC-free —
# see multiforge-bench/README.md — and no such client exists elsewhere in
# the tree). This script exercises the same DragonFightSystem code path a
# real kill would (health-reaches-zero handling: end-podium spawn, exit
# portal/gateway placement, TicketType.DRAGON unpin — see
# docs/design/global-region.md §6.3 and DragonFightSystem.java) by
# force-summoning the dragon if absent and then `/kill`ing it over RCON,
# rather than simulating combat. The regression this test cares about —
# byte/semantic parity of the resulting End structures between a 1-worker
# and a 4-worker run of the same seed — is exercised identically either
# way, since it's the death-handling code path under test, not the fight
# itself.
#
# Usage:
#   x3-dragon-fight-regression.sh [--dry-run]
#
# --dry-run prints every shell/gradle/RCON command this script would run
# and exits 0 without touching a server. A real run needs the same
# prerequisites as x1-cross-region-teleport.sh, plus enough sprint ticks
# for the dimension to load — see MF_X3_TICKS below if 6000 isn't enough
# on slower hardware.
#
# Ends with a machine-parseable "PASS" or "FAIL" as the last stdout line.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/common.sh"

mf_parse_dry_run "$@"
mf_evidence_dir_for "x3"

SEED="${MF_X3_SEED:-1985}" # matches the plan's -Ptest.seed=1985
TICKS_PER_CAPTURE="${MF_X3_TICKS:-6000}"

mf_log "x3-dragon-fight-regression: seed=$SEED ticks/capture=$TICKS_PER_CAPTURE"

x3_dragon_kill_callback() {
    mf_log "x3: ensuring an ender dragon exists in the_end, then killing it"
    mf_rcon "execute unless entity @e[type=minecraft:ender_dragon] in minecraft:the_end run summon minecraft:ender_dragon 0 128 0"
    mf_rcon "execute in minecraft:the_end run kill @e[type=minecraft:ender_dragon]"
    mf_rcon "multiforge probes global.system.dragon_fight" >"$MF_EVIDENCE_DIR/probes-dragon-fight.txt" 2>&1 || true
}

BASELINE_DIR="$MF_EVIDENCE_DIR/baseline-1w"
PATCHED_DIR="$MF_EVIDENCE_DIR/patched-4w"

overall=0

mf_log "x3: capturing baseline (1 worker)"
if ! mf_capture_world 1 "$TICKS_PER_CAPTURE" "$BASELINE_DIR" "" x3_dragon_kill_callback; then
    overall=1
fi

mf_log "x3: capturing patched (4 workers)"
if ! mf_capture_world 4 "$TICKS_PER_CAPTURE" "$PATCHED_DIR" "" x3_dragon_kill_callback; then
    overall=1
fi

DIFF_LOG="$MF_EVIDENCE_DIR/world-diff-semantic.log"
mf_log "x3: SEMANTIC diff baseline-1w vs patched-4w (end-podium + gateway parity)"
if [ "$MF_DRY_RUN" -eq 1 ]; then
    printf 'DRY-RUN would run: %s :multiforge-bench:determinism -PdiffMode=SEMANTIC -Pseed=%s --args="%s/world %s/world" > %s\n' \
        "$MF_GRADLEW" "$SEED" "$BASELINE_DIR" "$PATCHED_DIR" "$DIFF_LOG"
else
    diff_status=0
    (cd "$MF_REPO_ROOT" && "$MF_GRADLEW" :multiforge-bench:determinism -PdiffMode=SEMANTIC -Pseed="$SEED" \
        --args="$BASELINE_DIR/world $PATCHED_DIR/world") >"$DIFF_LOG" 2>&1 || diff_status=$?
    [ "$diff_status" -eq 0 ] || overall=1

    failure_hits="$(mf_grep_count "global\.system\.dragon_fight\.[a-z-]*failure|global\.system\.dragon_fight\.no-region" "$PATCHED_DIR/boot.log")"
    mf_log "x3: dragon_fight failure/no-region probe hits (patched) = $failure_hits"
    [ "$failure_hits" -eq 0 ] || overall=1
fi

mf_pass_or_fail "$overall"
