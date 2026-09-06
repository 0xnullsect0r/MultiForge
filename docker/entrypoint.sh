#!/usr/bin/env bash
#
# MultiForge server container entrypoint.
#
# Starts as root, fixes ownership of the mounted /data volume so the
# unprivileged multiforge user can write to it, then drops privileges
# via runuser to run the JVM.
#
# Env-var contract mirrors itzg/minecraft-server for drop-in
# compatibility:
#   EULA=TRUE|true         Accept the Minecraft EULA on first boot.
#   MEMORY=4G              JVM -Xms/-Xmx.
#   JVM_OPTS=...           Extra whitespace-separated JVM flags.
#   MULTIFORGE_MODE=hybrid Region partitioning mode.
#   MULTIFORGE_CORES=N     Worker-pool cores (defaults to config file).
#   MULTIFORGE_THREADS_PER_CORE=N  Threads per core.

set -euo pipefail

log() { printf '[entrypoint] %s\n' "$*" >&2; }

DATA_DIR="${DATA_DIR:-/data}"

# ---- privilege drop -----------------------------------------------------
# If we booted as root (the usual case — Docker's default), fix ownership
# on the mounted /data so the multiforge user can write, then re-exec
# ourselves as that user. If we're already unprivileged (someone set
# `user:` in compose to a specific UID that matches the volume owner),
# skip both steps.
if [ "$(id -u)" = "0" ]; then
    log "starting as root; fixing ownership on ${DATA_DIR} and dropping to multiforge"
    mkdir -p "${DATA_DIR}"
    chown -R multiforge:multiforge "${DATA_DIR}"
    exec runuser -u multiforge -- "$0" "$@"
fi

# ---- everything below runs as the multiforge user ----------------------

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
JVM_ARGS+=("-Dmultiforge.mode=${MULTIFORGE_MODE}")
if [ -n "${MULTIFORGE_CORES:-}" ]; then
    JVM_ARGS+=("-Dmultiforge.cores=${MULTIFORGE_CORES}")
fi
if [ -n "${MULTIFORGE_THREADS_PER_CORE:-}" ]; then
    JVM_ARGS+=("-Dmultiforge.threadsPerCore=${MULTIFORGE_THREADS_PER_CORE}")
fi

# 4. Find the installer jar. The image ships one canonical installer;
# glob so the version bump doesn't require an entrypoint edit.
SERVER_JAR=""
for candidate in /opt/multiforge/multiforge-installer-*.jar ; do
    if [ -f "${candidate}" ]; then SERVER_JAR="${candidate}"; break; fi
done

if [ -z "${SERVER_JAR}" ]; then
    log 'No MultiForge installer jar found under /opt/multiforge/. Broken image.'
    exit 2
fi

log "Launching MultiForge from ${SERVER_JAR}"
cd "${DATA_DIR}"
exec java "${JVM_ARGS[@]}" -jar "${SERVER_JAR}" "$@"
