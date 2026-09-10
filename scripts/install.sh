#!/usr/bin/env bash
# MultiForge install helper — wraps the fork installer for a fresh
# server directory.
#
# Download multiforge-installer.jar from the latest release into an
# empty directory, then run this from that directory.
#
# For converting an *existing* NeoForge server instead, use the drop-in
# replacement ZIP and its install-multiforge.sh — see
# docs/install.md § Method 2.

set -euo pipefail

say() { printf '\033[1;36m[multiforge]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[multiforge]\033[0m %s\n' "$*" >&2; exit 1; }

# 1. JDK 21 preflight -------------------------------------------------
# Exactly 21, not ">= 21". NeoForge 1.21.1 targets 21, and any 1.21.1
# pack bundling SpongeMixin dies at mod-scan on 22+ with "Unsupported
# class file major version 7X".
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
else
    JAVA_BIN="$(command -v java || true)"
fi
[ -n "$JAVA_BIN" ] || fail "No java on PATH and JAVA_HOME is unset. Install Temurin 21."

JAVA_MAJOR=$("$JAVA_BIN" -version 2>&1 | awk -F '"' '/version/ { split($2, a, "."); print a[1]; exit }')
if [ "${JAVA_MAJOR:-0}" != "21" ]; then
    fail "MultiForge requires JDK 21. Found: ${JAVA_MAJOR:-unknown} at $JAVA_BIN
       Install Temurin 21 and re-run as:
         JAVA_HOME=/usr/lib/jvm/temurin-21-jdk $0"
fi
say "JDK 21 at $JAVA_BIN"

# 2. Locate the installer ---------------------------------------------
INSTALLER=""
for f in multiforge-installer.jar multiforge-installer-*.jar; do
    [ -f "$f" ] || continue
    INSTALLER="$f"
    break
done
[ -n "$INSTALLER" ] || fail "multiforge-installer.jar not found in $(pwd).
       Fetch it with:
         curl -LO https://github.com/0xnullsect0r/MultiForge/releases/latest/download/multiforge-installer.jar"

# 3. Install ----------------------------------------------------------
if [ ! -f run.sh ]; then
    say "running $INSTALLER (downloads Minecraft + libraries on first run)"
    "$JAVA_BIN" -jar "$INSTALLER" --installServer .
else
    say "run.sh already present — skipping the installer."
fi

# 4. EULA -------------------------------------------------------------
if [ ! -f eula.txt ] || ! grep -q '^eula=true' eula.txt; then
    say "accepting the Minecraft EULA (edit eula.txt to revoke)"
    echo "eula=true" > eula.txt
fi

# 5. Config -----------------------------------------------------------
mkdir -p config
if [ ! -f config/multiforge-server.toml ]; then
    say "no config/multiforge-server.toml — MultiForge will boot on defaults."
    say "See docs/install.md for the tunables (cores, threads-per-core, region size)."
fi

say "ready. Start the server with: ./run.sh"
