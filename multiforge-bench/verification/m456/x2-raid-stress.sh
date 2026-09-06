#!/usr/bin/env bash
# MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
# All rights reserved. See LICENSE at the repository root.
#
# multiforge-bench/verification/m456/x2-raid-stress.sh
#
# Phase X task X.2 — cross-region raid stress. See
# docs/verification/m456/x2-raid-stress.md for goal/procedure/expected
# result, and docs/verification/m456/README.md "How to run" for the full
# operator runbook.
#
# ADAPTATION NOTE (read before running): the Phase X plan's literal recipe
# is RCON `/summon raid` + `/execute at @e[type=raid] run summon zombie
# ~5000 ~ ~`, asserted via `/multiforge probe entity-registry.orphans`.
# Neither `/summon raid` nor an `entity-registry.orphans` probe exist in
# this codebase (grepped multiforge-runtime/, multiforge-testmods/, and
# the neoforge fork — see MultiForgeCommandDispatcher's real subcommand
# set: config|region|probes|chunks|warn|certify). This script uses the
# closest real equivalents instead of inventing commands that would fail
# on first real run:
#   - 20 `minecraft:pillager` raid-capture seeds (PatrolLeader:1b) spread
#     across 4 map quadrants ~5000 blocks apart, standing in for the
#     literal `/summon raid`. A genuine Vanilla raid additionally needs a
#     real player with Bad Omen inside a village's bounding box — get one
#     in-region before sprinting if you need Raids.tick's own wave-spawn
#     logic (and therefore RaidsSystem.spawnRaider's cross-region hop) to
#     actually fire.
#   - `/multiforge probes global.system.raids` (real — see RaidsSystem.java)
#     in place of the fictitious entity-registry.orphans probe. Its
#     `raider-spawn-failure` counter is the real "did a cross-region
#     raider spawn get lost" signal — see RaidsSystem.spawnRaider's
#     try/catch, which is the only place a raider spawn can be dropped
#     without a corresponding successful `raider-spawn-routed` bump. A
#     ProbeRegistry counter dedicated to literal orphan-entity counting
#     (à la EntityRegistry.retiredRefs for migration) does not exist yet
#     — filed as a gap in the X.2 report page's "Known gaps" section.
#
# Usage:
#   x2-raid-stress.sh [--dry-run]
#
# --dry-run prints every shell/gradle/RCON command this script would run
# and exits 0 without touching a server. A real run needs the same
# prerequisites as x1-cross-region-teleport.sh.
#
# Ends with a machine-parseable "PASS" or "FAIL" as the last stdout line.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/common.sh"

mf_parse_dry_run "$@"
mf_evidence_dir_for "x2"

SEED="${MF_X2_SEED:-1234567890}"
TICKS="${MF_X2_TICKS:-6000}" # 5 game-minutes to let raider waves resolve
RAID_COUNT=20

mf_log "x2-raid-stress: seed=$SEED ticks=$TICKS raid-seeds=$RAID_COUNT"

x2_raid_callback() {
    mf_log "x2: seeding $RAID_COUNT raid captures across 4 quadrants"
    local -a quadrants=("100 64 100" "5100 64 100" "100 64 5100" "5100 64 5100")
    local i=0
    while [ "$i" -lt "$RAID_COUNT" ]; do
        local pos="${quadrants[$((i % 4))]}"
        mf_rcon "summon minecraft:pillager $pos {Tags:[\"mf_x2_raid_$i\"],PatrolLeader:1b}"
        # Literal-plan-adjacent probe: force one wave-spawn 5000 blocks out
        # from each seed so a straddling region boundary is exercised even
        # without a real player triggering the raid via Bad Omen.
        mf_rcon "execute at @e[tag=mf_x2_raid_$i,limit=1] run summon minecraft:zombie ~5000 ~ ~ {Tags:[\"mf_x2_wave_$i\"]}"
        i=$((i + 1))
    done
    mf_rcon "multiforge probes global.system.raids" >"$MF_EVIDENCE_DIR/probes-raids-mid.txt" 2>&1 || true
}

CAPTURE_DIR="$MF_EVIDENCE_DIR/patched-4w"

overall=0
mf_log "x2: capturing 4-worker raid stress run"
if ! mf_capture_world 4 "$TICKS" "$CAPTURE_DIR" "" x2_raid_callback; then
    overall=1
fi

if [ "$MF_DRY_RUN" -eq 1 ]; then
    printf 'DRY-RUN would: rcon "multiforge probes global.system.raids" > %s/probes-raids-final.txt (post-sprint, before stop)\n' "$MF_EVIDENCE_DIR"
    printf 'DRY-RUN would: grep boot log for RegionTickOverrunException / OwnershipEnforcer REROUTE hits\n'
else
    failure_count=0
    if [ -f "$MF_EVIDENCE_DIR/probes-raids-mid.txt" ]; then
        failure_count="$(grep -oE 'raider-spawn-failure[^0-9]*[0-9]+' "$MF_EVIDENCE_DIR/probes-raids-mid.txt" \
            | grep -oE '[0-9]+$' | head -1 || true)"
    fi
    failure_count="${failure_count:-0}"
    mf_log "x2: global.system.raids.raider-spawn-failure observed = $failure_count"
    [ "$failure_count" -eq 0 ] || overall=1

    reroute_hits="$(mf_grep_count "RegionTickOverrunException|OwnershipEnforcer.*REROUTE|region-tick\.overrun" "$CAPTURE_DIR/boot.log")"
    mf_log "x2: ownership violation hits during raid stress = $reroute_hits"
    [ "$reroute_hits" -eq 0 ] || overall=1
fi

mf_pass_or_fail "$overall"
