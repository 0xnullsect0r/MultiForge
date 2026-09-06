#!/usr/bin/env bash
# MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
# All rights reserved. See LICENSE at the repository root.
#
# multiforge-bench/verification/m456/lib/common.sh
#
# Shared helpers for the Phase X (m456) bench-verification scripts
# (x1-cross-region-teleport.sh, x2-raid-stress.sh,
# x3-dragon-fight-regression.sh, x8-strict-mode-swarm.sh). Reuses the
# RCON `/tick freeze` + `/tick sprint N` capture pattern from M9's
# scratchpad/capture.sh (see docs/verification/m9/7.2-7.6) so a fixed
# tick count — not wall-clock time — determines when a capture is
# "done", the same trick that made the M9 determinism captures
# reproducible.
#
# NOT MEANT TO BE EXECUTED DIRECTLY — each x{N}-*.sh script sources this
# file: `source "$SCRIPT_DIR/lib/common.sh"`.

# Resolve paths relative to this file, not the caller's $PWD, so the
# x{N} scripts behave the same whether invoked directly, via the
# `:multiforge-bench:x{N}...` Gradle Exec tasks, or from CI.
MF_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MF_REPO_ROOT="$(cd "$MF_LIB_DIR/../../../.." && pwd)"
MF_NEOFORGE_DIR="$MF_REPO_ROOT/upstream/neoforge-1.21.1"
MF_RUN_DIR="$MF_NEOFORGE_DIR/projects/neoforge/run/server"
MF_RCON_PY="$MF_LIB_DIR/rcon.py"
MF_RCON_HOST="127.0.0.1"
MF_RCON_PORT="25575"
MF_RCON_PASS="multiforge"
MF_GRADLEW="$MF_REPO_ROOT/gradlew"

MF_DRY_RUN=0
MF_EVIDENCE_DIR=""

# mf_parse_dry_run "$@" — sets MF_DRY_RUN=1 if --dry-run is among the args.
mf_parse_dry_run() {
    local arg
    for arg in "$@"; do
        if [ "$arg" = "--dry-run" ]; then
            MF_DRY_RUN=1
        fi
    done
}

mf_log() {
    printf '[%s] %s\n' "$(date +%T)" "$*" >&2
}

# mf_rcon <minecraft-command...> — sends one RCON command via lib/rcon.py.
# All args are joined with spaces into a single command string (mirrors
# scratchpad/rcon.py's <command> being one shell-quoted argument).
mf_rcon() {
    local cmd="$*"
    if [ "$MF_DRY_RUN" -eq 1 ]; then
        printf 'DRY-RUN would rcon: %s\n' "$cmd"
        return 0
    fi
    python3 "$MF_RCON_PY" "$MF_RCON_HOST" "$MF_RCON_PORT" "$MF_RCON_PASS" "$cmd"
}

# mf_evidence_dir_for <test-id> — sets MF_EVIDENCE_DIR to
# docs/verification/m456/evidence/<test-id> and creates it. Created even
# in --dry-run mode (an empty directory is not "touching a server") since
# several callbacks below redirect a dry-run "would rcon" transcript into
# a file under it for the smoke run to inspect.
mf_evidence_dir_for() {
    MF_EVIDENCE_DIR="$MF_REPO_ROOT/docs/verification/m456/evidence/$1"
    mkdir -p "$MF_EVIDENCE_DIR"
}

# mf_wait_for_rcon <boot-log> <timeout-sec> — blocks (real runs only) until
# the boot log shows RCON is up, or the timeout elapses. Returns 1 on
# timeout — non-fatal, the caller decides what to do.
mf_wait_for_rcon() {
    local bootlog="$1" timeout="$2" waited=0
    if [ "$MF_DRY_RUN" -eq 1 ]; then
        printf 'DRY-RUN would: poll %s for "RCON running on" up to %ss\n' "$bootlog" "$timeout"
        return 0
    fi
    while [ "$waited" -lt "$timeout" ]; do
        if grep -q "RCON running on\|Thread RCON Listener started" "$bootlog" 2>/dev/null; then
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}

# mf_prepare_run_dir <seed> — resets the vendored NeoForge run dir the same
# way M9's scratchpad/capture.sh did, so every m456 capture starts clean.
mf_prepare_run_dir() {
    local seed="$1"
    if [ "$MF_DRY_RUN" -eq 1 ]; then
        printf 'DRY-RUN would: rm -rf %s/{world,logs,crash-reports}; write server.properties (level-seed=%s, rcon on) + eula.txt; cp ~/.multiforge/license.key\n' \
            "$MF_RUN_DIR" "$seed"
        return 0
    fi
    rm -rf "$MF_RUN_DIR/world" "$MF_RUN_DIR/logs" "$MF_RUN_DIR/crash-reports"
    mkdir -p "$MF_RUN_DIR"
    echo "eula=true" >"$MF_RUN_DIR/eula.txt"
    cat >"$MF_RUN_DIR/server.properties" <<EOF
level-seed=$seed
level-name=world
motd=MF m456 verify
online-mode=false
spawn-protection=0
max-players=4
sync-chunk-writes=true
enable-rcon=true
rcon.password=$MF_RCON_PASS
rcon.port=$MF_RCON_PORT
EOF
    cp "$HOME/.multiforge/license.key" "$MF_RUN_DIR/"
}

# mf_stop_server <jvm-pid> <gradle-pid> — save-all flush + stop over RCON,
# then waits for the JVM to exit (falls back to SIGTERM/SIGKILL after 90s),
# then waits on the gradle subprocess itself.
mf_stop_server() {
    local jvm_pid="$1" gradle_pid="$2" waited=0
    if [ "$MF_DRY_RUN" -eq 1 ]; then
        printf 'DRY-RUN would: rcon "save-all flush"; sleep 3; rcon "stop"; wait (up to 90s, then SIGTERM/SIGKILL) for JVM pid to exit\n'
        return 0
    fi
    mf_rcon "save-all flush"
    sleep 3
    mf_rcon "stop"
    while kill -0 "$jvm_pid" 2>/dev/null; do
        sleep 1
        waited=$((waited + 1))
        if [ "$waited" -ge 90 ]; then
            kill -TERM "$jvm_pid" 2>/dev/null || true
            sleep 5
            kill -KILL "$jvm_pid" 2>/dev/null || true
            break
        fi
    done
    wait "$gradle_pid" 2>/dev/null || true
}

# mf_capture_world <workers> <ticks> <outdir> <extra-jvm-args> [mid-run-callback]
#
# Boots a patched server per M9's capture.sh pattern: launches
# `:neoforge:runServer -Dmultiforge.workers=<workers>` (plus any
# whitespace-separated <extra-jvm-args>), waits for RCON, `tick freeze`,
# invokes the optional <mid-run-callback> shell function (RCON is already
# reachable — the callback scripts /summon, /tp, /kill, etc.), `tick
# sprint <ticks>`, stops cleanly, then copies world/ + logs/latest.log +
# its own boot log into <outdir> (created under $MF_EVIDENCE_DIR by the
# caller).
mf_capture_world() {
    local workers="$1" ticks="$2" outdir="$3" extra_jvm="${4:-}" callback="${5:-}"
    local bootlog
    bootlog="$MF_EVIDENCE_DIR/boot-$(basename "$outdir").log"
    local -a gradle_args=(":neoforge:runServer" "-Dmultiforge.workers=$workers")
    if [ -n "$extra_jvm" ]; then
        local -a extra_arr
        read -r -a extra_arr <<<"$extra_jvm"
        gradle_args+=("${extra_arr[@]}")
    fi

    if [ "$MF_DRY_RUN" -eq 1 ]; then
        printf 'DRY-RUN would run (cwd=%s): MULTIFORGE_LICENSE=<license> %s %s > %s 2>&1\n' \
            "$MF_NEOFORGE_DIR" "$MF_GRADLEW" "${gradle_args[*]}" "$bootlog"
        printf 'DRY-RUN would: poll boot log for RCON, then rcon "tick freeze"\n'
        if [ -n "$callback" ]; then
            printf 'DRY-RUN would: invoke mid-run callback function: %s\n' "$callback"
        fi
        printf 'DRY-RUN would: rcon "tick sprint %s"\n' "$ticks"
        printf 'DRY-RUN would: rcon "save-all flush" + rcon "stop"; copy world/ + logs/latest.log + boot log into %s\n' "$outdir"
        if [ -n "$callback" ]; then
            "$callback"
        fi
        return 0
    fi

    mkdir -p "$(dirname "$bootlog")"
    (
        cd "$MF_NEOFORGE_DIR" || exit 1
        MULTIFORGE_LICENSE="$(cat "$HOME/.multiforge/license.key")" \
            "$MF_GRADLEW" "${gradle_args[@]}" >"$bootlog" 2>&1
    ) &
    local gradle_pid=$!

    if ! mf_wait_for_rcon "$bootlog" 180; then
        mf_log "FATAL: RCON never came up (workers=$workers outdir=$outdir) — see $bootlog"
        kill "$gradle_pid" 2>/dev/null || true
        return 1
    fi
    local jvm_pid
    jvm_pid="$(pgrep -f "cpw.mods.bootstraplauncher.BootstrapLauncher" | head -1)"

    mf_rcon "tick freeze"
    if [ -n "$callback" ]; then
        "$callback"
    fi
    mf_rcon "tick sprint $ticks"

    local waited=0
    while [ "$waited" -lt 300 ]; do
        if grep -qE "Sprint.*finished|Nothing to sprint" "$bootlog" 2>/dev/null; then
            break
        fi
        sleep 1
        waited=$((waited + 1))
    done

    mf_stop_server "$jvm_pid" "$gradle_pid"

    mkdir -p "$outdir"
    [ -d "$MF_RUN_DIR/world" ] && cp -r "$MF_RUN_DIR/world" "$outdir/"
    [ -f "$MF_RUN_DIR/logs/latest.log" ] && cp "$MF_RUN_DIR/logs/latest.log" "$outdir/latest.log"
    cp "$bootlog" "$outdir/boot.log"
}

# mf_grep_count <extended-regex> <file> — prints the match count, 0 if the
# file is missing. Never fails the caller under `set -e` (grep -c exits 1
# on zero matches, which this swallows).
mf_grep_count() {
    local pattern="$1" file="$2"
    if [ ! -f "$file" ]; then
        echo 0
        return 0
    fi
    grep -cE "$pattern" "$file" 2>/dev/null || true
}

# mf_pass_or_fail <status> — prints the final, machine-parseable PASS/FAIL
# line (must be the LAST line of stdout) and exits with the matching code.
# Must be the last thing an x{N} script calls.
mf_pass_or_fail() {
    local status="$1"
    if [ "$status" -eq 0 ]; then
        echo "PASS"
        exit 0
    else
        echo "FAIL"
        exit 1
    fi
}
