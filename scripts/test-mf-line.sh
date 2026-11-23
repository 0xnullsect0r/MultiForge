#!/bin/sh
# MultiForge — Copyright (c) 2026 MultiForge authors.
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, version 3.
# This program is distributed in the hope that it will be useful, but
# WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
# General Public License for more details.
# You should have received a copy of the GNU General Public License
# along with this program. If not, see <https://www.gnu.org/licenses/>.
#
# Checks which tags each release line accepts, what it publishes them as, and
# which patch states may ship. Downloads nothing.
#
#   scripts/test-mf-line.sh

set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
LINE="$ROOT/.github/scripts/mf-line.sh"
GATE="$ROOT/.github/scripts/mf-patches-gate.sh"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/multiforge-line.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

# accepts <tag> <line> <version> <prerelease> <make_latest>
accepts() {
    out=$(bash "$LINE" --tag "$1" 2>&1) || fail "$1: refused: $out"
    for want in "MF_LINE=$2" "MF_RELEASE_VERSION=$3" "MF_PRERELEASE=$4" "MF_MAKE_LATEST=$5"; do
        printf '%s\n' "$out" | grep -qxF "$want" || fail "$1: no $want in: $out"
    done
    echo "ok: accepts $1"
}

refuses() {
    if out=$(bash "$LINE" --tag "$1" 2>&1); then
        fail "'$1' was accepted: $out"
    fi
    printf '%s\n' "$out" | grep -q "matches no release line" || fail "'$1': wrong refusal: $out"
    echo "ok: refuses '$1'"
}

# gate <state, or "absent"> <MF_PRERELEASE> <allow|refuse>
gate() {
    tree="$WORK/tree-$1-$2"
    mkdir -p "$tree"
    : >"$tree/gradle.properties"
    [ "$1" = absent ] || echo "multiforge_patches_enabled=$1" >"$tree/gradle.properties"
    if MF_TREE="$tree" MF_PRERELEASE="$2" sh "$GATE" >/dev/null 2>&1; then got=allow; else got=refuse; fi
    [ "$got" = "$3" ] || fail "patch state $1 with prerelease=$2: expected $3, got $got"
    echo "ok: patch state $1, prerelease=$2: $3"
}

accepts v1.12.4 1.21 1.12.4 false true
accepts v1.0.0 1.21 1.0.0 false true
accepts mc26.1.2-v2.0.0-alpha.1 26.1 2.0.0-alpha.1 true false
accepts mc26.1.2-v2.0.0-alpha.12 26.1 2.0.0-alpha.12 true false
accepts mc26.1.2-v2.3.10-alpha.2 26.1 2.3.10-alpha.2 true false

for tag in \
    "" latest v2.0.0 v2.0.0-alpha.1 v1.12 v1.12.4-beta.1 v1.12.4-alpha.1 \
    mc26.1.2 mc26.1.2-v2.0.0 mc26.1.2-v2.0.0-beta.1 mc26.1.2-v2.0.0-alpha \
    mc26.1.2-v2.0.0-alpha.0 mc26.1.2-v2.0.0-alpha.01 mc26.1.2-v2.00.0-alpha.1 \
    mc26.1.2-v1.12.4 mc26.1.2-v3.0.0-alpha.1 mc26.1.1-v2.0.0-alpha.1 mc26.1.3-v2.0.0-alpha.1 \
    mc1.21.1-v1.12.4 mc1.21.1-v2.0.0-alpha.1 MC26.1.2-v2.0.0-alpha.1 \
    "mc26.1.2-v2.0.0-alpha.1 " mc26.1.2-v2.0.0-alpha.1-extra xmc26.1.2-v2.0.0-alpha.1; do
    refuses "$tag"
done

# Only the 1.x line may ever become `latest`.
bash "$LINE" --tag v1.12.4 | grep -qxF 'MF_RELEASE_BRANCHES=main release/1.21.x' || fail "1.x release branches changed"
bash "$LINE" --tag mc26.1.2-v2.0.0-alpha.1 | grep -qxF 'MF_RELEASE_BRANCHES=port/26.1.2' || fail "26.1 release branch changed"
echo "ok: each line releases only from its own branches"

gate absent false allow
gate true false allow
gate true true allow
gate prerelease true allow
gate prerelease false refuse
gate partial true refuse
gate partial false refuse
gate false true refuse
gate false false refuse
gate typo true refuse

echo "all release line checks passed"
