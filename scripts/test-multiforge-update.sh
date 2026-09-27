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
# End-to-end check of scripts/multiforge-update.sh against a real install.
#
#   scripts/test-multiforge-update.sh <old-installer.jar> <assets-dir> <work-dir>
#
# <old-installer.jar>  a previous release's multiforge-installer.jar (the "before")
# <assets-dir>         the "after" release's assets: multiforge-installer.jar,
#                      server.jar, multiforge-scanner.jar and (optionally) SHA256SUMS
# <work-dir>           scratch directory; wiped and reused
#
# Installs the old version, adds operator files (config, JVM args, a mod, a world
# file), updates, boots `java -jar server.jar nogui` to Done on the new NeoForge
# version, checks the updater refuses while that server runs, re-runs the update,
# then rolls back and forward. Downloads Minecraft on the first install. Exits 0 on
# success, 1 on the first failed assertion.

set -eu

[ $# -eq 3 ] || {
    echo "usage: $0 <old-installer.jar> <assets-dir> <work-dir>" >&2
    exit 2
}
OLD_INSTALLER=$(cd "$(dirname "$1")" && pwd -P)/$(basename "$1")
ASSETS=$(cd "$2" && pwd -P)
WORK=$3
UPDATER=$(cd "$(dirname "$0")" && pwd -P)/multiforge-update.sh
LIBS=libraries/net/neoforged/neoforge

fail() {
    echo "FAIL: $*" >&2
    exit 1
}
pass() { echo "ok: $*"; }
sum() { sha256sum "$1" | awk '{print $1}'; }
launched() { sed -n 's|.*libraries/net/neoforged/neoforge/\([^/]*\)/unix_args\.txt.*|\1|p' run.sh | head -n 1; }
count_versions() { find "$LIBS" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' '; }
update() { MF_BASE_URL="file://$ASSETS" sh "$UPDATER" "$@"; }

rm -rf "$WORK"
mkdir -p "$WORK/srv/config" "$WORK/srv/mods" "$WORK/srv/world"
SRV=$(cd "$WORK/srv" && pwd -P)

echo "== installing the old version"
(cd "$WORK" && java -jar "$OLD_INSTALLER" --installServer "$SRV" >"$WORK/old-install.log" 2>&1) || fail "old installer failed (see $WORK/old-install.log)"
cd "$SRV"
OLD=$(launched)
[ -n "$OLD" ] || fail "old install left no version in run.sh"
pass "old version $OLD installed"

printf 'mode = "on"\n# operator comment, must survive\nthreadsPerCore = 3\n' >config/multiforge-server.toml
printf -- '-Xmx2G\n# operator JVM args\n' >user_jvm_args.txt
echo "operator file" >mods/notes.txt
echo "operator file" >world/marker.txt
echo "eula=true" >eula.txt
TOML=$(sum config/multiforge-server.toml)
JVM=$(sum user_jvm_args.txt)
MOD=$(sum mods/notes.txt)
WORLD=$(sum world/marker.txt)

echo "== updating"
update || fail "updater exited non-zero"
NEW=$(launched)
[ -n "$NEW" ] && [ "$NEW" != "$OLD" ] || fail "run.sh still launches '$NEW' (old: $OLD)"
pass "run.sh launches $NEW"
[ "$(count_versions)" = 1 ] || fail "expected one version under $LIBS, found: $(ls "$LIBS")"
[ -d "$LIBS/$NEW" ] || fail "$LIBS/$NEW missing"
pass "exactly one version folder"
[ -d ".multiforge-backup/versions/$OLD" ] && [ "$(cat .multiforge-backup/VERSION)" = "$OLD" ] || fail "rollback point for $OLD missing"
pass "old version parked in .multiforge-backup"
[ "$(sum server.jar)" = "$(sum "$ASSETS/server.jar")" ] || fail "server.jar is not the release's"
[ "$(sum multiforge-scanner.jar)" = "$(sum "$ASSETS/multiforge-scanner.jar")" ] || fail "multiforge-scanner.jar is not the release's"
pass "server.jar and multiforge-scanner.jar installed"
[ "$(sum config/multiforge-server.toml)" = "$TOML" ] || fail "config/multiforge-server.toml changed"
[ "$(sum user_jvm_args.txt)" = "$JVM" ] || fail "user_jvm_args.txt changed"
[ "$(sum mods/notes.txt)" = "$MOD" ] || fail "mods/ changed"
[ "$(sum world/marker.txt)" = "$WORLD" ] || fail "world/ changed"
grep -q '^eula=true' eula.txt || fail "eula.txt changed"
pass "config, JVM args, mods, world and eula untouched"

echo "== booting java -jar server.jar nogui"
rm -f logs/latest.log
java -Xmx2G -jar server.jar nogui </dev/null >"$WORK/boot.out" 2>&1 &
PID=$!
booted=0
i=0
while [ $i -lt 600 ]; do
    if grep -q "Done (" logs/latest.log 2>/dev/null; then
        booted=1
        break
    fi
    kill -0 $PID 2>/dev/null || break
    sleep 1
    i=$((i + 1))
done
if [ $booted = 1 ]; then
    if update >"$WORK/update-while-running.out" 2>&1; then
        kill $PID 2>/dev/null || true
        fail "updater ran while the server was up"
    fi
    grep -q "server is running" "$WORK/update-while-running.out" || {
        kill $PID 2>/dev/null || true
        fail "updater failed while running, but not with the running-server message"
    }
fi
kill $PID 2>/dev/null || true
wait $PID 2>/dev/null || true
[ $booted = 1 ] || fail "server.jar did not boot to Done (see $WORK/boot.out)"
grep -q -e "--fml.neoForgeVersion, $NEW" logs/latest.log || fail "server.jar booted, but not NeoForge $NEW"
pass "java -jar server.jar nogui booted $NEW to Done"
[ "$(sum config/multiforge-server.toml)" = "$TOML" ] || fail "the booted server rewrote config/multiforge-server.toml"
pass "config/multiforge-server.toml unchanged after a boot"
pass "updater refused while the server ran"

echo "== re-running the update (same version)"
update || fail "second update exited non-zero"
[ "$(launched)" = "$NEW" ] && [ "$(count_versions)" = 1 ] || fail "second update changed the install"
[ "$(cat .multiforge-backup/VERSION)" = "$OLD" ] || fail "second update lost the rollback point"
[ "$(sum config/multiforge-server.toml)" = "$TOML" ] || fail "config changed on re-run"
pass "re-run is idempotent and keeps the rollback point"

echo "== rolling back"
update --rollback || fail "rollback exited non-zero"
[ "$(launched)" = "$OLD" ] && [ "$(count_versions)" = 1 ] && [ -d "$LIBS/$OLD" ] || fail "rollback did not restore $OLD"
[ "$(cat .multiforge-backup/VERSION)" = "$NEW" ] || fail "rollback did not keep $NEW as the way forward"
[ "$(sum config/multiforge-server.toml)" = "$TOML" ] || fail "config changed on rollback"
pass "rolled back to $OLD"
update --rollback || fail "roll-forward exited non-zero"
[ "$(launched)" = "$NEW" ] && [ "$(count_versions)" = 1 ] || fail "roll-forward did not restore $NEW"
pass "rolled forward to $NEW"

echo "all updater checks passed"
