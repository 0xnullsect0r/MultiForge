# syntax=docker/dockerfile:1.7
#
# MultiForge server image. Multi-stage build:
#   1. `builder`  — runs Gradle to produce the installer jar (with
#      multiforge-runtime bundled inside).
#   2. `runtime`  — minimal JRE image with the server jar + entrypoint.
#
# The image is designed to be a drop-in replacement for
# `itzg/minecraft-server` (same env-var contract) so operators can swap
# `image:` in an existing compose file with no other changes.
#
# GPL-3.0-only. See LICENSE at the repository root.

# ---------- builder stage ------------------------------------------------
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /src
COPY . /src

# Build the pure-Java modules that don't need the vendored NeoForge tree
# (multiforge-installer bundles multiforge-runtime + multiforge-api as
# jar resources, so producing the installer jar is enough for the runtime
# image — the fork bridge under upstream/neoforge-1.21.1 doesn't ship
# inside the container).
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon \
        :multiforge-api:build \
        :multiforge-runtime:build \
        :multiforge-installer:build

# Stage the installer jar and its bundled resources into predictable paths.
RUN mkdir -p /staging/root \
    && cp /src/multiforge-installer/build/libs/multiforge-installer-*.jar /staging/root/ 2>/dev/null || \
       cp /src/multiforge-installer/build/libs/*.jar /staging/root/

# ---------- runtime stage ------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime

LABEL org.opencontainers.image.title="MultiForge Server"
LABEL org.opencontainers.image.description="Multithreaded NeoForge dedicated server (GPL-3.0-only)"
LABEL org.opencontainers.image.source="https://github.com/0xnullsect0r/MultiForge"
LABEL org.opencontainers.image.licenses="GPL-3.0-only"

ENV DATA_DIR=/data \
    EULA="" \
    MEMORY="4G" \
    JVM_OPTS="" \
    MULTIFORGE_MODE="hybrid" \
    MULTIFORGE_CORES="" \
    MULTIFORGE_THREADS_PER_CORE=""

# Create the unprivileged runtime user. The entrypoint starts as root
# (so it can chown /data), then drops to this user via runuser.
RUN useradd --system --create-home --home-dir /home/multiforge --shell /usr/sbin/nologin multiforge \
    && mkdir -p ${DATA_DIR} \
    && chown -R multiforge:multiforge ${DATA_DIR} /home/multiforge

# mcstatus backs the compose healthcheck (docker/docker-compose.yml) with a
# real Minecraft server-list-ping probe instead of a bare TCP connect.
# `--break-system-packages` is only accepted by pip >= 23.0 (PEP 668,
# Debian bookworm+); older pip3 on this base rejects the flag outright, so
# try the plain install first and fall back to it rather than failing the
# build on either pip vintage.
# hadolint ignore=DL3008
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip \
    && rm -rf /var/lib/apt/lists/* \
    && (pip3 install --no-cache-dir "mcstatus==11.1.1" \
        || pip3 install --no-cache-dir --break-system-packages "mcstatus==11.1.1")

# Pull the staged installer jar. The builder stage guarantees this
# directory exists, so this COPY never trips on an empty glob.
COPY --from=builder /staging/root/ /opt/multiforge/
COPY docker/entrypoint.sh /usr/local/bin/entrypoint

RUN chmod +x /usr/local/bin/entrypoint

# NOTE: we do NOT set USER here. The entrypoint starts as root, chowns
# the mounted /data volume to the multiforge user, then re-execs itself
# as that user via runuser. This handles the common case where the host
# ./data directory is owned by root (docker's default) or an unrelated
# UID — the entrypoint fixes it up rather than requiring the operator
# to pre-chown their host dir.
WORKDIR ${DATA_DIR}
EXPOSE 25565/tcp 25565/udp
VOLUME ["${DATA_DIR}"]

ENTRYPOINT ["/usr/local/bin/entrypoint"]
CMD ["nogui"]
