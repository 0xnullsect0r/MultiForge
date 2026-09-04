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

# Copy the assembled installer + runtime jars from the builder stage.
# During M0 this is only the pure-Java runtime; the installer jar is
# populated once M2 lands the full server assembly.
COPY --from=builder /src/multiforge-runtime/build/libs/*.jar        /opt/multiforge/lib/
COPY --from=builder /src/multiforge-license/build/libs/*.jar        /opt/multiforge/lib/
COPY --from=builder /src/multiforge-installer/build/libs/*.jar      /opt/multiforge/
COPY docker/entrypoint.sh /usr/local/bin/entrypoint

RUN chmod +x /usr/local/bin/entrypoint

USER multiforge
WORKDIR ${DATA_DIR}
EXPOSE 25565/tcp 25565/udp
VOLUME ["${DATA_DIR}"]

ENTRYPOINT ["/usr/local/bin/entrypoint"]
CMD ["nogui"]
