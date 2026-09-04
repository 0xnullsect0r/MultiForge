#!/usr/bin/env bash
#
# MultiForge server container entrypoint. Wraps `java -jar
# multiforge-server.jar` with:
#
#   - `itzg/minecraft-server`-style env-var handling (EULA, MEMORY, ...)
#   - MultiForge-specific env-var → JVM property mapping
#     (MULTIFORGE_LICENSE, MULTIFORGE_MODE, MULTIFORGE_CORES,
#      MULTIFORGE_THREADS_PER_CORE)
#   - EULA acceptance from the env var into eula.txt on first boot
#
# Exit codes:
#   78  — license gate refused (see docs/license.md)
#   0   — normal shutdown (/stop)

set -euo pipefail

log() { printf '[entrypoint] %s\n' "$*" >&2; }

# 1. EULA acceptance (matches itzg image contract).
if [ "${EULA:-}" = "TRUE" ] || [ "${EULA:-}" = "true" ]; then
    printf 'eula=true\n' > "${DATA_DIR}/eula.txt"
elif [ ! -f "${DATA_DIR}/eula.txt" ]; then
    log 'EULA not accepted. Set EULA=TRUE to accept the Minecraft EULA (https://aka.ms/MinecraftEULA).'
    exit 1
fi

# 2. Memory / JVM options.
JVM_ARGS=("-Xms${MEMORY}" "-Xmx${MEMORY}")
if [ -n "${JVM_OPTS:-}" ]; then
    # shellcheck disable=SC2206
    JVM_ARGS+=(${JVM_OPTS})
fi

# 3. MultiForge-specific properties.
if [ -n "${MULTIFORGE_LICENSE:-}" ]; then
    # License is already picked up from MULTIFORGE_LICENSE by LicenseGate,
    # but expose the value as a JVM property too so a mis-configured shell
    # (no environ passthrough) still boots.
    JVM_ARGS+=("-Dmultiforge.license=${MULTIFORGE_LICENSE}")
fi
JVM_ARGS+=("-Dmultiforge.mode=${MULTIFORGE_MODE}")
if [ -n "${MULTIFORGE_CORES:-}" ]; then
    JVM_ARGS+=("-Dmultiforge.cores=${MULTIFORGE_CORES}")
fi
if [ -n "${MULTIFORGE_THREADS_PER_CORE:-}" ]; then
    JVM_ARGS+=("-Dmultiforge.threadsPerCore=${MULTIFORGE_THREADS_PER_CORE}")
fi

# 4. Find the installer jar.
SERVER_JAR=""
for candidate in /opt/multiforge/multiforge-installer-*.jar /opt/multiforge/multiforge-server-*.jar ; do
    if [ -f "${candidate}" ]; then SERVER_JAR="${candidate}"; break; fi
done

if [ -z "${SERVER_JAR}" ]; then
    # M0 fallback: no installer jar yet; just verify the license and exit.
    log 'No server jar found (expected for pre-M2 builds). Running license gate only.'
    RUNTIME_JAR=$(ls /opt/multiforge/lib/multiforge-runtime-*.jar 2>/dev/null | head -1 || true)
    LICENSE_JAR=$(ls /opt/multiforge/lib/multiforge-license-*.jar 2>/dev/null | head -1 || true)
    if [ -z "${LICENSE_JAR}" ] || [ -z "${RUNTIME_JAR}" ]; then
        log 'No MultiForge jars found under /opt/multiforge/. Broken image.'
        exit 2
    fi
    exec java "${JVM_ARGS[@]}" -cp "${LICENSE_JAR}:${RUNTIME_JAR}" \
        net.multiforge.runtime.bootstrap.LicenseOnlyMain "$@"
fi

log "Launching MultiForge from ${SERVER_JAR}"
exec java "${JVM_ARGS[@]}" -jar "${SERVER_JAR}" "$@"
