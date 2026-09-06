# syntax=docker/dockerfile:1.7
#
# MultiForge server image. Multi-stage build:
#   1. `builder`  — runs Gradle to produce the installer + runtime jars.
#   2. `runtime`  — minimal JDK 21 image with the server jar + entrypoint.
#
# The image is designed to be a drop-in replacement for
# `itzg/minecraft-server` (same env-var contract) so operators can swap
# `IMAGE:` in an existing compose file with no other changes.

# ---------- builder stage ------------------------------------------------
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /src
COPY . /src

# `-Pmc=true` requires the vendored NeoForge tree; the release build runs
# `:setup` first as a separate CI step and passes it in.
# For local docker builds without :setup, the pure-Java modules still build.
ARG MC=true
RUN --mount=type=cache,target=/root/.gradle \
    if [ "$MC" = "true" ] ; then \
        ./gradlew --no-daemon -Pmc=true :multiforge-installer:build ; \
    else \
        ./gradlew --no-daemon :multiforge-license:build :multiforge-license-cli:build :multiforge-runtime:build ; \
    fi

# Stage jars into a single directory regardless of MC=true|false so
# the runtime stage's COPY is deterministic. When MC=false the installer
# jar simply doesn't exist and the staging dir is empty except for a
# placeholder — the runtime image still boots into the license gate.
RUN mkdir -p /staging/lib /staging/root \
    && cp /src/multiforge-runtime/build/libs/*.jar /staging/lib/ 2>/dev/null || true \
    && cp /src/multiforge-license/build/libs/*.jar /staging/lib/ 2>/dev/null || true \
    && if [ -d /src/multiforge-installer/build/libs ]; then \
           cp /src/multiforge-installer/build/libs/*.jar /staging/root/ 2>/dev/null || true; \
       fi \
    && touch /staging/root/.keep

# ---------- runtime stage ------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

LABEL org.opencontainers.image.title="MultiForge Server"
LABEL org.opencontainers.image.description="Multithreaded NeoForge dedicated server"
LABEL org.opencontainers.image.source="https://github.com/multiforge/multiforge"
LABEL org.opencontainers.image.licenses="LicenseRef-MultiForge-Proprietary"

# Match itzg/minecraft-server data directory convention.
ENV DATA_DIR=/data \
    EULA="" \
    MEMORY="4G" \
    JVM_OPTS="" \
    MULTIFORGE_LICENSE="" \
    MULTIFORGE_MODE="hybrid" \
    MULTIFORGE_CORES="" \
    MULTIFORGE_THREADS_PER_CORE=""

RUN useradd --system --create-home --home-dir /home/multiforge --shell /usr/sbin/nologin multiforge \
    && mkdir -p ${DATA_DIR} \
    && chown -R multiforge:multiforge ${DATA_DIR} /home/multiforge

# mcstatus backs the compose healthcheck (docker/docker-compose.yml) with a
# real Minecraft server-list-ping probe instead of a bare TCP connect.
# `--break-system-packages` is only accepted by pip >= 23.0 (PEP 668,
# Debian bookworm+); older pip3 on this base rejects the flag outright, so
# try the plain install first and fall back to it rather than failing the
# build on either pip vintage.
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip \
    && rm -rf /var/lib/apt/lists/* \
    && (pip3 install --no-cache-dir mcstatus \
        || pip3 install --no-cache-dir --break-system-packages mcstatus)

# Pull the staged jars in one go. The builder stage guarantees these
# directories exist regardless of the MC=true|false build arg, so this
# COPY never trips on an empty glob.
COPY --from=builder /staging/lib/ /opt/multiforge/lib/
COPY --from=builder /staging/root/ /opt/multiforge/
COPY docker/entrypoint.sh /usr/local/bin/entrypoint

RUN chmod +x /usr/local/bin/entrypoint

USER multiforge
WORKDIR ${DATA_DIR}
EXPOSE 25565/tcp 25565/udp
VOLUME ["${DATA_DIR}"]

ENTRYPOINT ["/usr/local/bin/entrypoint"]
CMD ["nogui"]
