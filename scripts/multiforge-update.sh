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
# Install or update MultiForge in a server directory, in one line:
#
#   curl -fsSL https://github.com/0xnullsect0r/MultiForge/releases/latest/download/multiforge-update.sh | sh
#
# Run it from the server directory, or pass the directory:
#
#   curl -fsSL .../multiforge-update.sh | sh -s -- /path/to/server
#   curl -fsSL .../multiforge-update.sh | sh -s -- --rollback [/path/to/server]
#
# Afterwards start the server with `java -Xms4G -Xmx20G -jar server.jar nogui`
# (or ./run.sh). server.jar is replaced on every run; it launches whatever
# run.sh names, and the installer rewrites run.sh to the new version.
#
# Leaves alone: config/ (multiforge-server.toml included), mods/, worlds,
# server.properties, eula.txt, ops/whitelist files. Keeps one rollback point
# in .multiforge-backup/.
#
# Environment:
#   MF_VERSION   release tag to install (e.g. v1.8.0); default: the latest release
#   MF_BASE_URL  where to download the release assets from, overriding GitHub
#                (a directory URL; file:// works) — for testing and mirrors
#   JAVA_HOME    JDK 21 to use; default: java on PATH
#
# The whole script is one function called on the last line, so a download cut
# short by the network never runs half a script.

mf_main() {
    set -eu

    REPO="0xnullsect0r/MultiForge"
    NEOFORGE_LIBS="libraries/net/neoforged/neoforge"
    BACKUP=".multiforge-backup"

    say() { printf '[multiforge] %s\n' "$*"; }
    warn() { printf '[multiforge] warning: %s\n' "$*" >&2; }
    fail() {
        printf '[multiforge] error: %s\n' "$*" >&2
        exit 1
    }

    ROLLBACK=0
    DIR=""
    for arg in "$@"; do
        case "$arg" in
            --rollback) ROLLBACK=1 ;;
            -h | --help)
                echo "usage: multiforge-update.sh [--rollback] [server-dir]   (env: MF_VERSION, MF_BASE_URL, JAVA_HOME)"
                exit 0
                ;;
            -*) fail "unknown option: $arg" ;;
            *) DIR="$arg" ;;
        esac
    done
    [ -n "$DIR" ] || DIR=$(pwd)
    [ -d "$DIR" ] || fail "no such directory: $DIR"
    DIR=$(cd "$DIR" && pwd -P)
    cd "$DIR"

    # --- preflight -----------------------------------------------------------

    if [ ! -d libraries ] && [ ! -f server.properties ] && [ ! -f run.sh ]; then
        fail "$DIR does not look like a Minecraft server directory (no libraries/, server.properties or run.sh).
       cd into the server directory first, or pass it: ... | sh -s -- /path/to/server"
    fi

    # A running server holds these files open and would keep the old version.
    if [ -d /proc ]; then
        for p in /proc/[0-9]*; do
            cwd=$(readlink "$p/cwd" 2>/dev/null) || continue
            [ "$cwd" = "$DIR" ] || continue
            case "$(cat "$p/comm" 2>/dev/null)" in
                java*) fail "a server is running in $DIR (pid ${p#/proc/}). Stop it first, then re-run." ;;
            esac
        done
    fi

    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        JAVA="$JAVA_HOME/bin/java"
    else
        JAVA=$(command -v java || true)
    fi
    [ -n "$JAVA" ] || fail "no java on PATH and JAVA_HOME is unset. Install a JDK 21 (e.g. Temurin 21)."
    java_major=$("$JAVA" -version 2>&1 | awk -F '"' '/version/ { split($2, a, "."); print a[1]; exit }')
    [ "${java_major:-0}" = "21" ] || fail "MultiForge needs Java 21; $JAVA is Java ${java_major:-unknown}.
       Re-run with JAVA_HOME pointing at a JDK 21."

    # The version a run script launches: libraries/net/neoforged/neoforge/<ver>/unix_args.txt
    launched_version() {
        [ -f run.sh ] || return 0
        sed -n 's|.*libraries/net/neoforged/neoforge/\([^/]*\)/unix_args\.txt.*|\1|p' run.sh | head -n 1
    }

    # Move every NeoForge version folder except $1 into $2.
    park_other_versions() {
        keep="$1"
        dest="$2"
        [ -d "$NEOFORGE_LIBS" ] || return 0
        for v in "$NEOFORGE_LIBS"/*/; do
            [ -d "$v" ] || continue
            name=$(basename "$v")
            [ "$name" = "$keep" ] && continue
            mkdir -p "$dest"
            rm -rf "${dest:?}/$name"
            mv "$NEOFORGE_LIBS/$name" "$dest/$name"
        done
    }

    LAUNCH_FILES="run.sh run.bat user_jvm_args.txt server.jar multiforge-scanner.jar"

    # --- rollback --------------------------------------------------------------

    if [ "$ROLLBACK" = 1 ]; then
        [ -f "$BACKUP/VERSION" ] || fail "nothing to roll back to: $BACKUP/VERSION is missing."
        target=$(cat "$BACKUP/VERSION")
        current=$(launched_version)
        [ -d "$BACKUP/versions/$target" ] || fail "$BACKUP/versions/$target is missing; cannot roll back."
        say "rolling back MultiForge ${current:-unknown} -> $target"
        swap="$BACKUP.swap"
        rm -rf "$swap"
        mkdir -p "$swap/versions"
        for f in $LAUNCH_FILES; do
            [ -f "$f" ] && mv "$f" "$swap/$f"
        done
        park_other_versions "" "$swap/versions"
        mv "$BACKUP/versions/$target" "$NEOFORGE_LIBS/$target"
        for f in $LAUNCH_FILES; do
            [ -f "$BACKUP/$f" ] && mv "$BACKUP/$f" "$f"
        done
        [ -n "$current" ] && printf '%s\n' "$current" >"$swap/VERSION"
        rm -rf "$BACKUP"
        mv "$swap" "$BACKUP"
        say "done: run.sh now launches $(launched_version). Run --rollback again to return to ${current:-the newer version}."
        exit 0
    fi

    # --- download ---------------------------------------------------------------

    if [ -n "${MF_BASE_URL:-}" ]; then
        BASE="${MF_BASE_URL%/}"
    elif [ -n "${MF_VERSION:-}" ]; then
        BASE="https://github.com/$REPO/releases/download/$MF_VERSION"
    else
        BASE="https://github.com/$REPO/releases/latest/download"
    fi

    TMP=$(mktemp -d "${TMPDIR:-/tmp}/multiforge-update.XXXXXX")
    trap 'rm -rf "$TMP"' EXIT
    trap 'exit 1' INT TERM

    fetch() { # fetch <asset> -> 0 if downloaded
        curl -fsSL --retry 3 -o "$TMP/$1" "$BASE/$1" 2>/dev/null
    }

    say "downloading from $BASE"
    fetch multiforge-installer.jar || fail "could not download $BASE/multiforge-installer.jar"
    HAVE_STARTER=1
    fetch server.jar || HAVE_STARTER=0
    HAVE_SCANNER=1
    fetch multiforge-scanner.jar || HAVE_SCANNER=0

    if fetch SHA256SUMS; then
        if command -v sha256sum >/dev/null 2>&1; then
            sha() { sha256sum "$1" | awk '{print $1}'; }
        else
            sha() { shasum -a 256 "$1" | awk '{print $1}'; }
        fi
        for f in multiforge-installer.jar server.jar multiforge-scanner.jar; do
            [ -f "$TMP/$f" ] || continue
            want=$(awk -v n="$f" '{ sub(/^\*/, "", $2) } $2 == n { print $1; exit }' "$TMP/SHA256SUMS")
            [ -n "$want" ] || fail "SHA256SUMS has no entry for $f"
            [ "$(sha "$TMP/$f")" = "$want" ] || fail "checksum mismatch for $f — download corrupted or tampered with"
        done
        say "checksums verified"
    else
        warn "this release has no SHA256SUMS; skipping checksum verification"
    fi

    # --- install ---------------------------------------------------------------

    previous=$(launched_version)
    stage="$BACKUP.new"
    rm -rf "$stage"
    mkdir -p "$stage/versions"
    for f in $LAUNCH_FILES; do
        [ -f "$f" ] && cp -p "$f" "$stage/$f"
    done

    restore_launch_files() {
        for f in $LAUNCH_FILES; do
            if [ -f "$stage/$f" ]; then cp -p "$stage/$f" "$f"; fi
        done
    }

    say "running the MultiForge installer (downloads Minecraft and libraries on first run)"
    set -- --installServer "$DIR"
    # No mirrored server.jar in this release: have the installer fetch NeoForged's.
    [ "$HAVE_STARTER" = 1 ] || set -- "$@" --server.jar
    # The installer writes its log next to itself, so run it from the temp dir.
    if ! (cd "$TMP" && "$JAVA" -jar "$TMP/multiforge-installer.jar" "$@" >"$TMP/installer.out" 2>&1); then
        restore_launch_files
        rm -rf "$stage"
        tail -n 30 "$TMP/installer.out" >&2 || true
        fail "the installer failed; nothing was changed (full log above, last 30 lines)."
    fi

    current=$(launched_version)
    if [ -z "$current" ] || [ ! -f "$NEOFORGE_LIBS/$current/unix_args.txt" ]; then
        restore_launch_files
        rm -rf "$stage"
        fail "after installing, run.sh does not name an installed version; restored the previous launch files."
    fi

    # The installer rewrites user_jvm_args.txt; keep the operator's.
    [ -f "$stage/user_jvm_args.txt" ] && cp -p "$stage/user_jvm_args.txt" user_jvm_args.txt

    # Exactly one version stays under libraries/; the one we replaced becomes the rollback point.
    if [ -n "$previous" ] && [ "$previous" != "$current" ]; then
        park_other_versions "$current" "$stage/versions"
        printf '%s\n' "$previous" >"$stage/VERSION"
        rm -rf "$BACKUP"
        mv "$stage" "$BACKUP"
    else
        # Same version re-installed (or a fresh install): keep the existing rollback point,
        # but still clear out any stray version folders into it.
        park_other_versions "$current" "$BACKUP/versions"
        rm -rf "$stage"
    fi

    # server.jar and the scanner are replaced on every run, atomically.
    if [ "$HAVE_STARTER" = 1 ]; then
        cp "$TMP/server.jar" server.jar.new && mv -f server.jar.new server.jar
    fi
    [ -f server.jar ] || fail "server.jar is missing after the install."
    if [ "$HAVE_SCANNER" = 1 ]; then
        cp "$TMP/multiforge-scanner.jar" multiforge-scanner.jar.new && mv -f multiforge-scanner.jar.new multiforge-scanner.jar
    else
        warn "this release has no multiforge-scanner.jar; /multiforge certify will not work"
    fi

    if [ -n "$previous" ] && [ "$previous" != "$current" ]; then
        say "MultiForge $previous -> $current"
        say "previous version kept in $BACKUP/ (undo with: ... | sh -s -- --rollback)"
    else
        say "MultiForge $current installed"
    fi
    [ -f eula.txt ] || say "first run: accept the Minecraft EULA by setting eula=true in eula.txt"
    say "start the server with:  java -Xms4G -Xmx20G -jar server.jar nogui   (or ./run.sh)"
}

mf_main "$@"
