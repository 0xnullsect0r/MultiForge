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
# Checks that scripts/multiforge-update.sh refuses to move a server to another
# Minecraft version, before it changes anything. Needs a JDK 21; downloads nothing.
#
#   scripts/test-multiforge-update-guard.sh

set -eu

UPDATER=$(cd "$(dirname "$0")" && pwd -P)/multiforge-update.sh
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/multiforge-guard.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

# assets <dir> <minecraft>: a release whose installer targets <minecraft>. It is not runnable.
assets() {
    mkdir -p "$1/in"
    printf '{"version":"guard-test","minecraft":"%s"}\n' "$2" >"$1/in/install_profile.json"
    "${JAVA_BIN}jar" cf "$1/multiforge-installer.jar" -C "$1/in" install_profile.json
    echo starter >"$1/server.jar"
    echo scanner >"$1/multiforge-scanner.jar"
    (cd "$1" && sha256sum multiforge-installer.jar server.jar multiforge-scanner.jar >SHA256SUMS)
}

# server <dir> <neoforge version>
server() {
    mkdir -p "$1/libraries/net/neoforged/neoforge/$2" "$1/world"
    printf 'java @user_jvm_args.txt @libraries/net/neoforged/neoforge/%s/unix_args.txt "$@"\n' "$2" >"$1/run.sh"
    echo args >"$1/libraries/net/neoforged/neoforge/$2/unix_args.txt"
    echo world >"$1/world/marker.txt"
}

state() { (cd "$1" && find . -type f -exec sha256sum {} + | sort); }

# refused <name> <server neoforge> <release minecraft>
refused() {
    d="$WORK/$(printf '%s' "$1" | tr -c 'a-zA-Z0-9' '-')"
    server "$d/srv" "$2"
    assets "$d/rel" "$3"
    before=$(state "$d/srv")
    if MF_BASE_URL="file://$d/rel" sh "$UPDATER" "$d/srv" >"$d/out" 2>&1; then
        fail "$1: the updater accepted a move to Minecraft $3"
    fi
    grep -q "this MultiForge release is for Minecraft $3" "$d/out" || fail "$1: wrong refusal: $(cat "$d/out")"
    [ "$(state "$d/srv")" = "$before" ] || fail "$1: the refused update changed files"
    echo "ok: $1"
}

# passes <name> <server neoforge> <release minecraft> [env]: reaches the (unrunnable) installer.
passes() {
    d="$WORK/$(printf '%s' "$1" | tr -c 'a-zA-Z0-9' '-')"
    server "$d/srv" "$2"
    assets "$d/rel" "$3"
    env ${4:-_=_} MF_BASE_URL="file://$d/rel" sh "$UPDATER" "$d/srv" >"$d/out" 2>&1 || true
    grep -q "running the MultiForge installer" "$d/out" || fail "$1: stopped before the installer: $(cat "$d/out")"
    echo "ok: $1"
}

refused "1.21.1 server, 26.1.2 release" 21.1.251-multiforge-1.12.0 26.1.2
refused "stock 1.21.1 server, 26.1.2 release" 21.1.234 26.1.2
refused "26.1.2 server, 1.21.1 release" 26.1.2.129-multiforge-2.0.0-alpha.1 1.21.1
refused "26.1.2 server, 26.3 release" 26.1.2.129 26.3.0
passes "same line, 1.21.1" 21.1.234 1.21.1
passes "same line, 26.1.2" 26.1.2.126 26.1.2
passes "explicit override" 21.1.251 26.1.2 MF_ALLOW_MINECRAFT_CHANGE=1
echo "all Minecraft version guard checks passed"
