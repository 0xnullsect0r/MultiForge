#!/usr/bin/env bash
# MultiForge install helper — walks you through the manual install
# after you have downloaded multiforge-installer-<v>.jar and
# multiforge-<v>-server.jar into this directory.
#
# See docs/install.md for the full flow, including Docker install and
# migrating from an existing NeoForge server.

set -euo pipefail

say() { printf '\033[1;36m[multiforge]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[multiforge]\033[0m %s\n' "$*" >&2; exit 1; }

# 1. Java 21 check ---------------------------------------------------
command -v java >/dev/null || fail "Java 21+ not found on PATH."
JAVA_VER="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
[ "${JAVA_VER:-0}" -ge 21 ] || fail "Java 21+ required (found $JAVA_VER)."

# 2. Installer jar ---------------------------------------------------
INSTALLER=""
for f in multiforge-installer-*.jar; do
    [ -f "$f" ] || continue
    INSTALLER="$f"
    break
done
[ -n "$INSTALLER" ] || fail "multiforge-installer-<version>.jar not found in $(pwd)."

if [ ! -f run.sh ] && [ ! -f run.bat ]; then
    say "1. Running installer $INSTALLER"
    java -jar "$INSTALLER" --install-dir .
else
    say "1. run.sh already present — skipping installer."
fi

# 3. EULA ------------------------------------------------------------
if [ ! -f eula.txt ] || ! grep -q '^eula=true' eula.txt; then
    say "2. Accepting EULA (edit eula.txt to revoke)"
    echo "eula=true" > eula.txt
fi

# 4. License ---------------------------------------------------------
if [ ! -f license.key ]; then
    say "3. Paste your MultiForge license token below, then press Ctrl-D:"
    cat > license.key
    chmod 600 license.key
else
    say "3. license.key already present — skipping."
fi

# 5. Verify token ----------------------------------------------------
CLI=""
for d in multiforge-license-cli-*/bin; do
    [ -d "$d" ] || continue
    CLI="$d"
    break
done
if [ -n "$CLI" ] && [ -x "$CLI/multiforge-license-cli" ]; then
    say "4. Verifying license offline"
    "$CLI/multiforge-license-cli" verify --token "$(tr -d '\n' < license.key)" \
        || fail "License token failed verification — see boot log."
fi

say "5. Ready. Start the server with: ./run.sh"
