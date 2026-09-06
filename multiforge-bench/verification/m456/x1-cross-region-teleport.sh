#!/usr/bin/env bash
# MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
# All rights reserved. See LICENSE at the repository root.
#
# multiforge-bench/verification/m456/x1-cross-region-teleport.sh
#
# Phase X task X.1 — cross-region entity teleport regression. See
# docs/verification/m456/x1-cross-region-teleport.md for the goal,
# procedure, and expected-result writeup, and
# docs/verification/m456/README.md "How to run" for the full operator
# runbook.
#
# What this does (real run): captures the same fixed seed twice — once
# with a single worker (one region; a /tp never actually crosses a
# region boundary) and once with four workers (four regions; the same
# /tp calls now exercise EntityMigrationCoordinator) — then diffs the two
# world saves with WorldDiff.DiffMode.SEMANTIC. Same trick M9 used for
# its 7.3 N-worker regression (docs/verification/m9/7.3): a fixed seed
# must produce the same semantic world state regardless of how many
# regions it was ticked across, so a SEMANTIC mismatch here means the
# migration hop introduced a divergence.
#
# Mid-capture, 100 RCON `/tp` calls rapidly bounce 10 tagged test mobs
# across 4 map quadrants ~5000 blocks apart (comfortably wider than any
# sane region-size config), forcing repeated cross-region migrations.
# `/multiforge probes entity-migration` is captured afterward so a
# migration count of zero — i.e. the mobs never actually left their
# starting region, which would silently pass the diff for the wrong
# reason — fails the run too.
#
# Usage:
#   x1-cross-region-teleport.sh [--dry-run]
#
# --dry-run prints every shell/gradle/RCON command this script would run
# and exits 0 without touching a server (see verify step 2 in the Phase X
# plan). Without it, a real run needs:
#   - ~/.multiforge/license.key (a valid MultiForge license token)
#   - the vendored upstream/neoforge-1.21.1 workspace (./gradlew :setup)
#   - a free RCON port 25575 and ~15 min of wall clock
#
# Ends with a machine-parseable "PASS" or "FAIL" as the last stdout line.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/common.sh"

mf_parse_dry_run "$@"
mf_evidence_dir_for "x1"

SEED="${MF_X1_SEED:-1234567890}"
TICKS_PER_CAPTURE="${MF_X1_TICKS:-6000}" # 5 game-minutes per capture
TP_COUNT=100

mf_log "x1-cross-region-teleport: seed=$SEED ticks/capture=$TICKS_PER_CAPTURE tp-count=$TP_COUNT"

# Mid-run callback for the 4-worker (patched) capture — summons 10 tagged
# test mobs, then rapid-fires 100 /tp calls cycling them through 4 map
# quadrants ~5000 blocks apart, then snapshots the entity-migration probe
# counters while RCON is still reachable.
x1_teleport_callback() {
    mf_log "x1: summoning 10 cross-region test mobs"
    local i=0
    while [ "$i" -lt 10 ]; do
        mf_rcon "summon minecraft:pig 100 -60 100 {Tags:[\"mf_x1_test\"]}"
        i=$((i + 1))
    done

    mf_log "x1: rapid-teleporting test mobs across 4 quadrants ($TP_COUNT total /tp calls)"
    local -a quadrants=("100 -60 100" "5100 -60 100" "100 -60 5100" "5100 -60 5100")
    i=0
    while [ "$i" -lt "$TP_COUNT" ]; do
        local dest="${quadrants[$((i % 4))]}"
        mf_rcon "execute as @e[tag=mf_x1_test] run tp @s $dest"
        i=$((i + 1))
    done

    mf_rcon "multiforge probes entity-migration" >"$MF_EVIDENCE_DIR/probes-entity-migration.txt" 2>&1 || true
}

BASELINE_DIR="$MF_EVIDENCE_DIR/baseline-1w"
PATCHED_DIR="$MF_EVIDENCE_DIR/patched-4w"

overall=0

mf_log "x1: capturing baseline (1 worker, no migration possible — single region)"
if ! mf_capture_world 1 "$TICKS_PER_CAPTURE" "$BASELINE_DIR" "" ""; then
    overall=1
fi

mf_log "x1: capturing patched (4 workers, teleport callback drives cross-region migration)"
if ! mf_capture_world 4 "$TICKS_PER_CAPTURE" "$PATCHED_DIR" "" x1_teleport_callback; then
    overall=1
fi

DIFF_LOG="$MF_EVIDENCE_DIR/world-diff-semantic.log"
mf_log "x1: SEMANTIC diff baseline-1w vs patched-4w"
if [ "$MF_DRY_RUN" -eq 1 ]; then
    printf 'DRY-RUN would run: %s :multiforge-bench:determinism -PdiffMode=SEMANTIC -Pseed=%s --args="%s/world %s/world" > %s\n' \
        "$MF_GRADLEW" "$SEED" "$BASELINE_DIR" "$PATCHED_DIR" "$DIFF_LOG"
else
    diff_status=0
    (cd "$MF_REPO_ROOT" && "$MF_GRADLEW" :multiforge-bench:determinism -PdiffMode=SEMANTIC -Pseed="$SEED" \
        --args="$BASELINE_DIR/world $PATCHED_DIR/world") >"$DIFF_LOG" 2>&1 || diff_status=$?
    [ "$diff_status" -eq 0 ] || overall=1

    migration_count=0
    if [ -f "$MF_EVIDENCE_DIR/probes-entity-migration.txt" ]; then
        migration_count="$(grep -oE 'entity-migration\.total[^0-9]*[0-9]+' "$MF_EVIDENCE_DIR/probes-entity-migration.txt" \
            | grep -oE '[0-9]+$' | head -1 || true)"
    fi
    migration_count="${migration_count:-0}"
    mf_log "x1: entity-migration.total observed = $migration_count"
    [ "$migration_count" -gt 0 ] || overall=1
fi

mf_pass_or_fail "$overall"
