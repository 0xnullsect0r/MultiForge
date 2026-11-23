#!/bin/bash
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
# Runs the release-URL block of pelican-egg-modpack.json's real install script
# and checks which release it downloads and which versions it refuses. Needs
# python3. Downloads nothing and installs nothing.
#
#   scripts/test-pelican-modpack-egg.sh

set -euo pipefail

EGG="$(cd "$(dirname "$0")/.." && pwd -P)/pelican-egg-modpack.json"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/multiforge-egg.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

python3 -I - "$EGG" "$WORK/script.sh" "$WORK/block.sh" "$WORK/rule" <<'PY'
import json, sys
egg, script, block, rule = sys.argv[1:]
data = json.load(open(egg, encoding="utf-8"))
text = data["scripts"]["installation"]["script"]
open(script, "w", encoding="utf-8").write(text)
begin, end = "## BEGIN release-url", "## END release-url"
if text.count(begin) != 1 or text.count(end) != 1:
    sys.exit("the install script has no single release-url block")
open(block, "w", encoding="utf-8").write(text[text.index(begin):text.index(end)])
rules = [v["rules"] for v in data["variables"] if v["env_variable"] == "MULTIFORGE_VERSION"]
open(rule, "w", encoding="utf-8").write(rules[0])
PY

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

# run <MULTIFORGE_VERSION> <MC_VERSION>: prints BASE and ASSET, or fails as the egg would.
run() {
    env -i PATH="$PATH" MULTIFORGE_VERSION="$1" MC_VERSION="$2" bash -c '
        set -euo pipefail
        fail() { echo "ERROR: $*" >&2; exit 1; }
        source "$0"
        echo "$BASE/$ASSET"
        echo "$BASE/SHA256SUMS"
    ' "$WORK/block.sh"
}

R=https://github.com/0xnullsect0r/MultiForge/releases/download

accepts() { # <input> <mc> <tag> <version>
    out=$(run "$1" "$2" 2>&1) || fail "'$1' refused: $out"
    want="$R/$3/multiforge-$4-installer.jar
$R/$3/SHA256SUMS"
    [ "$out" = "$want" ] || fail "'$1': got: $out"
    echo "ok: '$1' downloads $3"
}

refuses() { # <input> <mc>
    if out=$(run "$1" "$2" 2>&1); then
        fail "'$1' on Minecraft $2 was accepted: $out"
    fi
    case "$out" in
        ERROR:*) ;;
        *) fail "'$1': wrong refusal: $out" ;;
    esac
    echo "ok: refuses '$1' on Minecraft $2"
}

accepts 2.0.0-alpha.1 26.1.2 mc26.1.2-v2.0.0-alpha.1 2.0.0-alpha.1
accepts v2.0.0-alpha.1 26.1.2 mc26.1.2-v2.0.0-alpha.1 2.0.0-alpha.1
accepts mc26.1.2-v2.0.0-alpha.1 26.1.2 mc26.1.2-v2.0.0-alpha.1 2.0.0-alpha.1
accepts 2.1.3-alpha.12 26.1.2 mc26.1.2-v2.1.3-alpha.12 2.1.3-alpha.12

for bad in "" latest LATEST v latest-alpha 1.12.4 v1.12.4 2.0.0 v2.0.0 2.0.0-beta.1 2.0.0-alpha 2.0.0-alpha.0 \
    2.0.0-alpha.01 3.0.0-alpha.1 mc1.21.1-v1.12.4 mc26.1.1-v2.0.0-alpha.1 mc26.1.2-v1.12.4 mc26.1.2-vlatest \
    mc26.1.2-v2.0.0 "2.0.0-alpha.1/../x" "2.0.0-alpha.1 " "mc26.1.2-v2.0.0-alpha.1/x"; do
    refuses "$bad" 26.1.2
done
refuses 2.0.0-alpha.1 1.21.1
refuses 2.0.0-alpha.1 latest
refuses 2.0.0-alpha.1 ""

# The rest of the script must use this block's URL, and nothing may follow `latest`.
grep -qF 'curl -fsSL -o "${WORK}/${ASSET}" "${BASE}/${ASSET}"' "$WORK/script.sh" || fail "the installer is not downloaded from BASE"
grep -qF 'curl -fsSL -o "${WORK}/SHA256SUMS" "${BASE}/SHA256SUMS"' "$WORK/script.sh" || fail "SHA256SUMS is not downloaded from BASE"
if grep -q 'releases/latest' "$WORK/script.sh"; then fail "the script references releases/latest"; fi
[ "$(grep -c 'BASE=' "$WORK/script.sh")" = 1 ] || fail "BASE is assigned more than once"
echo "ok: the script downloads only from that URL"

# The longest accepted input must fit the panel's validation rule.
max=$(sed -n 's/.*max:\([0-9]*\).*/\1/p' "$WORK/rule")
longest=mc26.1.2-v2.100.100-alpha.100
[ "${max:-0}" -ge "${#longest}" ] || fail "the panel rule max:$max is shorter than the tag $longest"
echo "ok: the panel accepts a full tag"

echo "all modpack egg URL checks passed"
