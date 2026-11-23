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
# Minecraft version, before it changes anything. Needs any JDK (for `jar`); stub JDKs stand in for
# the Java each case runs on. Downloads nothing.
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

# stub_jdk <major>: a JAVA_HOME that reports that Java version and cannot run anything.
stub_jdk() {
    stub="$WORK/jdk-$1"
    if [ ! -d "$stub" ]; then
        mkdir -p "$stub/bin"
        printf '#!/bin/sh\necho "    java.specification.version = %s"\ncase "$1" in -XshowSettings*) exit 0 ;; esac\nexit 1\n' "$1" >"$stub/bin/java"
        chmod +x "$stub/bin/java"
        ln -s "$(command -v "${JAVA_BIN}jar")" "$stub/bin/jar"
    fi
    printf '%s' "$stub"
}

# java_for <minecraft>: the Java version a release for that Minecraft needs.
java_for() {
    case "$1" in
        1.21*) echo 21 ;;
        *) echo 25 ;;
    esac
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
    if JAVA_HOME="$(stub_jdk "$(java_for "$3")")" MF_BASE_URL="file://$d/rel" sh "$UPDATER" "$d/srv" >"$d/out" 2>&1; then
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
    env ${4:-_=_} JAVA_HOME="$(stub_jdk "$(java_for "$3")")" MF_BASE_URL="file://$d/rel" sh "$UPDATER" "$d/srv" >"$d/out" 2>&1 || true
    grep -q "running the MultiForge installer" "$d/out" || fail "$1: stopped before the installer: $(cat "$d/out")"
    echo "ok: $1"
}

# wrong_java <name> <server neoforge> <release minecraft> <java>: refused, nothing changed.
wrong_java() {
    d="$WORK/$(printf '%s' "$1" | tr -c 'a-zA-Z0-9' '-')"
    server "$d/srv" "$2"
    assets "$d/rel" "$3"
    before=$(state "$d/srv")
    if JAVA_HOME="$(stub_jdk "$4")" MF_BASE_URL="file://$d/rel" sh "$UPDATER" "$d/srv" >"$d/out" 2>&1; then
        fail "$1: the updater ran on Java $4"
    fi
    grep -q "needs Java $(java_for "$3")" "$d/out" || fail "$1: wrong refusal: $(cat "$d/out")"
    grep -q "running the MultiForge installer" "$d/out" && fail "$1: reached the installer"
    [ "$(state "$d/srv")" = "$before" ] || fail "$1: the refused update changed files"
    echo "ok: $1"
}

# A 26.x server has no `latest`: that is always a 1.21.1 release. Refused before any download.
no_latest_for_26() {
    d="$WORK/no-latest"
    server "$d/srv" 26.1.2.129-multiforge-2.0.0-alpha.1
    before=$(state "$d/srv")
    if env -u MF_VERSION -u MF_BASE_URL JAVA_HOME="$(stub_jdk 25)" sh "$UPDATER" "$d/srv" >"$d/out" 2>&1; then
        fail "a 26.x server followed latest"
    fi
    grep -q "Name a 26.x release" "$d/out" || fail "26.x without MF_VERSION: wrong refusal: $(cat "$d/out")"
    grep -q "downloading from" "$d/out" && fail "26.x without MF_VERSION: started a download"
    [ "$(state "$d/srv")" = "$before" ] || fail "26.x without MF_VERSION changed files"
    echo "ok: 26.x server without MF_VERSION does not follow latest"
}

# A stub curl records what the updater fetched, so nothing here touches the network.
stub_curl() {
    if [ ! -x "$WORK/curl-bin/curl" ]; then
        mkdir -p "$WORK/curl-bin"
        printf '#!/bin/sh\nfor a in "$@"; do last=$a; done\necho "$last" >>"$CURL_LOG"\nexit 22\n' >"$WORK/curl-bin/curl"
        chmod +x "$WORK/curl-bin/curl"
    fi
    printf '%s' "$WORK/curl-bin"
}

# pin_refused <name> <server neoforge> <MF_VERSION> <message>: refused before any download.
pin_refused() {
    d="$WORK/pin-$(printf '%s' "$1" | tr -c 'a-zA-Z0-9' '-')"
    server "$d/srv" "$2"
    before=$(state "$d/srv")
    : >"$d/curl.log"
    if env -u MF_BASE_URL PATH="$(stub_curl):$PATH" CURL_LOG="$d/curl.log" MF_VERSION="$3" \
        JAVA_HOME="$(stub_jdk 25)" sh "$UPDATER" "$d/srv" >"$d/out" 2>&1; then
        fail "$1: the updater accepted MF_VERSION=$3"
    fi
    grep -q "$4" "$d/out" || fail "$1: wrong refusal: $(cat "$d/out")"
    [ ! -s "$d/curl.log" ] || fail "$1: downloaded before refusing: $(cat "$d/curl.log")"
    [ "$(state "$d/srv")" = "$before" ] || fail "$1: the refused update changed files"
    echo "ok: $1"
}

# pin_url <name> <server neoforge, or "fresh"> <MF_VERSION> <java>: asks for exactly that tag's assets.
pin_url() {
    d="$WORK/url-$(printf '%s' "$1" | tr -c 'a-zA-Z0-9' '-')"
    mkdir -p "$d/srv"
    [ "$2" = fresh ] || server "$d/srv" "$2"
    : >"$d/curl.log"
    env -u MF_BASE_URL PATH="$(stub_curl):$PATH" CURL_LOG="$d/curl.log" MF_VERSION="$3" \
        JAVA_HOME="$(stub_jdk "$4")" sh "$UPDATER" "$d/srv" >"$d/out" 2>&1 || true
    want="https://github.com/0xnullsect0r/MultiForge/releases/download/$3/multiforge-installer.jar"
    [ "$(head -n 1 "$d/curl.log")" = "$want" ] || fail "$1: fetched '$(head -n 1 "$d/curl.log")', not $want"
    echo "ok: $1"
}

# tag_mismatch <name> <MF_VERSION> <installer minecraft>: a fresh install refuses a mislabelled release.
tag_mismatch() {
    d="$WORK/mismatch-$(printf '%s' "$1" | tr -c 'a-zA-Z0-9' '-')"
    mkdir -p "$d/srv"
    assets "$d/rel" "$3"
    if MF_VERSION="$2" JAVA_HOME="$(stub_jdk "$(java_for "$3")")" MF_BASE_URL="file://$d/rel" \
        sh "$UPDATER" "$d/srv" >"$d/out" 2>&1; then
        fail "$1: installed a mislabelled release"
    fi
    grep -q "but its installer is for $3" "$d/out" || fail "$1: wrong refusal: $(cat "$d/out")"
    grep -q "running the MultiForge installer" "$d/out" && fail "$1: reached the installer"
    [ -z "$(ls -A "$d/srv")" ] || fail "$1: the refused install changed files"
    echo "ok: $1"
}

refused "1.21.1 server, 26.1.2 release" 21.1.251-multiforge-1.12.0 26.1.2
refused "stock 1.21.1 server, 26.1.2 release" 21.1.234 26.1.2
refused "26.1.2 server, 1.21.1 release" 26.1.2.129-multiforge-2.0.0-alpha.1 1.21.1
refused "26.1.2 server, 26.3 release" 26.1.2.129 26.3.0
passes "same line, 1.21.1" 21.1.234 1.21.1
passes "same line, 26.1.2" 26.1.2.126 26.1.2
passes "explicit override" 21.1.251 26.1.2 MF_ALLOW_MINECRAFT_CHANGE=1
wrong_java "1.21.1 release on Java 25" 21.1.234 1.21.1 25
wrong_java "26.1.2 release on Java 21" 26.1.2.126 26.1.2 21
wrong_java "26.1.2 release on Java 26" 26.1.2.126 26.1.2 26
no_latest_for_26
pin_refused "1.21.1 server pinned to a 26.1.2 tag" 21.1.251-multiforge-1.12.4 mc26.1.2-v2.0.0-alpha.1 "is a release for Minecraft 26.1.2"
pin_refused "stock 1.21.1 server pinned to a 26.1.2 tag" 21.1.234 mc26.1.2-v2.0.0-alpha.1 "is a release for Minecraft 26.1.2"
pin_refused "26.1.2 server pinned to a 1.x tag" 26.1.2.129-multiforge-2.0.0-alpha.1 v1.12.4 "is a release for Minecraft 1.21"
pin_refused "26.1.2 server pinned to another 26.x tag" 26.1.2.129-multiforge-2.0.0-alpha.1 mc26.3.0-v2.0.0-alpha.1 "is a release for Minecraft 26.3.0"
pin_refused "26.1.2 server pinned to the retired tag format" 26.1.2.129-multiforge-2.0.0-alpha.1 v2.0.0-alpha.1 "not a MultiForge release tag"
pin_refused "26.1.2 server pinned to latest" 26.1.2.129-multiforge-2.0.0-alpha.1 latest "not a MultiForge release tag"
pin_refused "1.21.1 server pinned to the retired tag format" 21.1.251-multiforge-1.12.4 v2.0.0-alpha.1 "not a MultiForge release tag"
pin_refused "a tag that is a path" 26.1.2.129-multiforge-2.0.0-alpha.1 "mc26.1.2-v2.0.0-alpha.1/../x" "not a MultiForge release tag"
pin_refused "a 1.x tag that is a path" 21.1.251-multiforge-1.12.3 "v1.12.4/../x" "not a MultiForge release tag"
pin_url "26.1.2 server downloads its own tag" 26.1.2.129-multiforge-2.0.0-alpha.1 mc26.1.2-v2.0.0-alpha.2 25
pin_url "fresh install downloads the pinned 26.1.2 tag" fresh mc26.1.2-v2.0.0-alpha.1 25
pin_url "1.21.1 server downloads its own tag" 21.1.251-multiforge-1.12.3 v1.12.4 21
passes "pinned, same line, 26.1.2" 26.1.2.129-multiforge-2.0.0-alpha.1 26.1.2 MF_VERSION=mc26.1.2-v2.0.0-alpha.2
passes "pinned, same line, 1.21.1" 21.1.251-multiforge-1.12.3 1.21.1 MF_VERSION=v1.12.4
passes "pinned across lines with the explicit override" 21.1.251 26.1.2 "MF_VERSION=mc26.1.2-v2.0.0-alpha.1 MF_ALLOW_MINECRAFT_CHANGE=1"
tag_mismatch "26.1.2 tag holding a 1.21.1 installer" mc26.1.2-v2.0.0-alpha.1 1.21.1
tag_mismatch "1.x tag holding a 26.1.2 installer" v1.12.4 26.1.2
echo "all Minecraft version guard checks passed"
