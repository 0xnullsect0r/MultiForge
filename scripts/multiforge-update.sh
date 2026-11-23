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
# Afterwards start the server with `<jdk>/bin/java -Xmx8G -jar server.jar nogui`, on
# Java 21 for Minecraft 1.21.1 and Java 25 for 26.1 (or ./run.sh, which refuses any
# other Java). A 26.1 server must name its release, for example
# MF_VERSION=mc26.1.2-v2.0.0-alpha.1: `latest` is always a 1.21.1 release. server.jar is replaced on every run; it launches whatever
# run.sh names, and the installer rewrites run.sh to the new version.
#
# Leaves alone: config/ (multiforge-server.toml included), mods/, worlds,
# server.properties, eula.txt, ops/whitelist files. Keeps one rollback point
# in .multiforge-backup/.
#
# Environment:
#   MF_VERSION   release tag to install (v1.8.0, or mc26.1.2-v2.0.0-alpha.1); it must be
#                for this server's Minecraft. Default: the latest release (1.21.1 only)
#   MF_BASE_URL  where to download the release assets from, overriding GitHub
#                (a directory URL; file:// works) — for testing and mirrors
#   MF_ALLOW_UNVERIFIED=1  install even if the release's SHA256SUMS cannot be fetched
#   JAVA_HOME    JDK to use (21 for Minecraft 1.21.1, 25 for 26.1); default: java on PATH
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

    # A server directory, or an empty one for a fresh install; anything else is probably a mistake.
    if [ ! -d libraries ] && [ ! -f server.properties ] && [ ! -f run.sh ] && [ -n "$(ls -A)" ]; then
        fail "$DIR is neither empty nor a Minecraft server directory (no libraries/, server.properties or run.sh).
       cd into the server directory (or an empty one) first, or pass it: ... | sh -s -- /path/to/server"
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
        swap="$BACKUP.swap"
        [ ! -d "$swap" ] || fail "$swap exists: an earlier rollback was interrupted. Its files are split between
       $BACKUP/ and $swap/; move the launch files and the version folder back by hand, then remove $swap."
        [ -f "$BACKUP/VERSION" ] || fail "nothing to roll back to: $BACKUP/VERSION is missing."
        target=$(cat "$BACKUP/VERSION")
        current=$(launched_version)
        [ -d "$BACKUP/versions/$target" ] || fail "$BACKUP/versions/$target is missing; cannot roll back."
        say "rolling back ${current:-unknown} -> $target"
        trap 'warn "rollback interrupted: files are split between $BACKUP/ and $swap/"; exit 1' INT TERM
        mkdir -p "$swap/versions"
        for f in $LAUNCH_FILES; do
            # A file the backup does not hold (server.jar after a first install by
            # another method) stays: the server starter works with any version.
            [ -f "$f" ] && [ -f "$BACKUP/$f" ] && mv "$f" "$swap/$f"
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

    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        JAVA="$JAVA_HOME/bin/java"
    else
        JAVA=$(command -v java || true)
    fi
    [ -n "$JAVA" ] || fail "no java on PATH and JAVA_HOME is unset. Install a JDK (21 for Minecraft 1.21.1, 25 for 26.1)."

    # NeoForge 21.1.x is Minecraft 1.21.1; from 26.1 on, the first three parts are the version.
    minecraft_of() {
        printf '%s\n' "$1" | awk -F'[.-]' '$1 + 0 >= 26 { print $1 "." $2 "." $3; exit }
            $1 + 0 > 0 { if ($2 + 0 == 0) print "1." $1; else print "1." $1 "." $2; exit }'
    }

    # The Minecraft a release tag is for ("1.21" for the v1.x line); nothing if it is not a tag.
    tag_minecraft() {
        case "$1" in
            v1.* | 1.*)
                if printf '%s\n' "$1" | grep -Eq '^v?1\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$'; then
                    echo 1.21
                fi
                ;;
            mc*)
                if printf '%s\n' "$1" | grep -Eq '^mc[0-9]+\.[0-9]+(\.[0-9]+)?-v[0-9]+\.[0-9]+\.[0-9]+(-[a-z]+\.[0-9]+)?$'; then
                    mc=${1#mc}
                    printf '%s\n' "${mc%%-v*}"
                fi
                ;;
        esac
    }

    # Every 1.21.x server and release is one line; any other version is its own.
    line_of() {
        case "$1" in
            1.21 | 1.21.*) echo 1.21 ;;
            *) printf '%s\n' "$1" ;;
        esac
    }

    # Choose only a release of this server's own line, before downloading anything.
    server_mc=""
    if [ -n "$(launched_version)" ]; then
        server_mc=$(minecraft_of "$(launched_version)")
    fi
    tag_mc=""
    if [ -n "${MF_VERSION:-}" ]; then
        tag_mc=$(tag_minecraft "$MF_VERSION")
        [ -n "$tag_mc" ] || fail "MF_VERSION='$MF_VERSION' is not a MultiForge release tag.
       Nothing was changed. Use v1.X.Y for Minecraft 1.21.1, or mc26.1.2-v2.X.Y-alpha.N for 26.1.2."
        if [ -n "$server_mc" ] && [ "$(line_of "$server_mc")" != "$(line_of "$tag_mc")" ] \
            && [ "${MF_ALLOW_MINECRAFT_CHANGE:-0}" != 1 ]; then
            fail "this server is on Minecraft $server_mc, but $MF_VERSION is a release for Minecraft $tag_mc.
       Nothing was changed. Updating would permanently upgrade the world and break its mods."
        fi
    elif [ -z "${MF_BASE_URL:-}" ]; then
        # GitHub's `latest` is always a 1.21.1 release, so it can never update a 26.x server.
        case "$server_mc" in
            26.*)
                fail "this server is on Minecraft $server_mc, and the latest MultiForge release is for 1.21.1.
       Nothing was changed. Name a 26.x release: MF_VERSION=mc$server_mc-v<version>, for example
       MF_VERSION=mc26.1.2-v2.0.0-alpha.1"
                ;;
        esac
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
        # Every release from v1.8.0 on ships SHA256SUMS. A missing one means a release
        # still being published (assets upload one by one) or a broken download, not an
        # old release: refuse, unless the operator pinned a release older than v1.8.0.
        old_release=0
        case "${MF_VERSION:-}" in
            v1.[0-7].* | 1.[0-7].*) old_release=1 ;;
        esac
        if [ "$old_release" = 1 ] || [ "${MF_ALLOW_UNVERIFIED:-0}" = 1 ]; then
            warn "no SHA256SUMS for this release; the downloads were not verified"
        else
            fail "could not download $BASE/SHA256SUMS, so the downloads cannot be verified.
       If a release is being published right now, wait a minute and re-run.
       (MF_ALLOW_UNVERIFIED=1 skips the check.)"
        fi
    fi

    # --- Minecraft version guard ------------------------------------------------

    # The Minecraft version the downloaded installer installs.
    installer_minecraft() {
        if command -v unzip >/dev/null 2>&1; then
            profile=$(unzip -p "$TMP/multiforge-installer.jar" install_profile.json 2>/dev/null) || profile=
        elif [ -x "$(dirname "$JAVA")/jar" ]; then
            profile=$(cd "$TMP" && "$(dirname "$JAVA")/jar" xf multiforge-installer.jar install_profile.json \
                && cat install_profile.json) || profile=
        else
            profile=
        fi
        printf '%s' "$profile" | tr -d '\r\n' | sed -n 's/.*"minecraft"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
    }

    # A tag and its installer must agree, on a fresh install too.
    if [ -n "$tag_mc" ]; then
        release_mc=$(installer_minecraft)
        [ "$(line_of "$release_mc")" = "$(line_of "$tag_mc")" ] || fail "$MF_VERSION is tagged for Minecraft $tag_mc, but its installer is for ${release_mc:-an unknown version}.
       Nothing was changed."
    fi

    # Loading a world in a newer Minecraft upgrades it permanently, and a modpack's mods
    # only load on their own version, so an update never crosses versions by itself.
    previous=$(launched_version)
    if [ -n "$previous" ]; then
        from_mc=$(minecraft_of "$previous")
        to_mc=$(installer_minecraft)
        [ -n "$from_mc" ] && [ -n "$to_mc" ] || fail "could not compare Minecraft versions (server: ${from_mc:-unknown},
       this release: ${to_mc:-unknown}); nothing was changed."
        if [ "$from_mc" != "$to_mc" ] && [ "${MF_ALLOW_MINECRAFT_CHANGE:-0}" != 1 ]; then
            fail "this server is on Minecraft $from_mc, but this MultiForge release is for Minecraft $to_mc.
       Nothing was changed. Updating would permanently upgrade the world and break its mods.
       Pin a release for $from_mc with MF_VERSION=<tag>. To migrate on purpose, back up the
       server, install a modpack for $to_mc, and re-run with MF_ALLOW_MINECRAFT_CHANGE=1."
        fi
    fi

    # --- Java for the Minecraft version being installed --------------------------

    target_mc=$(installer_minecraft)
    case "$target_mc" in
        1.21 | 1.21.*) need_java=21 ;;
        26.*) need_java=25 ;;
        *) fail "could not tell which Minecraft this release is for (got '${target_mc:-nothing}'); nothing was changed." ;;
    esac
    java_major=$("$JAVA" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.specification\.version = //p' | head -n 1)
    [ "${java_major:-0}" = "$need_java" ] || fail "MultiForge for Minecraft $target_mc needs Java $need_java; $JAVA is Java ${java_major:-unknown}.
       Nothing was changed. Re-run with JAVA_HOME pointing at a JDK $need_java."

    # --- install ---------------------------------------------------------------

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

    # The installer leaves an existing user_jvm_args.txt alone; restore it anyway in
    # case an older installer replaced it.
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
        say "updated $previous -> $current"
        say "previous version kept in $BACKUP/ (undo with: ... | sh -s -- --rollback)"
    else
        say "MultiForge $current installed"
    fi
    [ -f eula.txt ] || say "first run: accept the Minecraft EULA by setting eula=true in eula.txt"
    # server.jar runs in the JVM that starts it, so name the Java checked above:
    # a bare `java` may be another JDK, which this version's mods cannot load on.
    say "start the server with:  \"$JAVA\" -Xms4G -Xmx8G -jar server.jar nogui   (or ./run.sh, which checks the JDK)"
    say "set -Xmx to what this machine can give the server (8G is a starting point for modpacks)"
}

mf_main "$@"
