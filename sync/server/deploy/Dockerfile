# SPDX-License-Identifier: Unlicense
# Release builds start with the checked artifacts. The checkout's Compose
# build selects checkout-files to compile the same server and notices tasks.
ARG SERVER_SOURCE=release-files

FROM scratch AS release-files
ARG VERSION
COPY neutrodyne-server-${VERSION}.jar /server/neutrodyne-server.jar
COPY THIRD_PARTY_NOTICES.md SOURCES.md /server/
COPY data/ /server/data/

FROM eclipse-temurin:21-jdk-noble AS checkout-files
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon --configure-on-demand --no-configuration-cache --max-workers=2 \
      '-Dorg.gradle.jvmargs=-Xmx1536m -XX:+UseSerialGC -Dfile.encoding=UTF-8' \
      -Pkotlin.compiler.execution.strategy=in-process :sync:server:fatJar :sync:server:serverNotices \
    && mkdir /server \
    && version="$(sed -n 's/^neutrodyne.versionName=//p' gradle.properties)" \
    && cp "sync/server/build/libs/neutrodyne-server-$version.jar" /server/neutrodyne-server.jar \
    && cp sync/server/build/legal/THIRD_PARTY_NOTICES.md /server/ \
    && cp -r sync/server/deploy/data /server/ \
    && cp sync/server/deploy/image-sources.lock.json /server/ \
    && printf '%s\n' '# Corresponding sources' '' \
      'The server JAR was built from the local Neutrodyne checkout.' \
      'See image-sources.lock.json in this directory for exact runtime and base package sources.' \
      > /server/SOURCES.md

FROM ${SERVER_SOURCE} AS server-files

# D95: digest and exact source inventory move together in reviewed updates.
FROM gcr.io/distroless/java25-debian13:nonroot@sha256:bb5fd2c016691aef1cf9ff376294c1eeaa5b635af240a0a3ed3242e2877f7866 AS runtime
ARG REPOSITORY=L-K-M/Neutrodyne
# WORKDIR creates /app as a traversable 0755 directory; a --chmod on the first
# COPY into a not-yet-existing directory would apply to /app itself and leave
# it untraversable for UID 65532.
WORKDIR /app
# --chmod: COPY preserves the build host's file mode, and a 0600 JAR (umask 077)
# leaves /app/neutrodyne-server.jar unreadable to UID 65532.
COPY --from=server-files --chmod=0644 /server/neutrodyne-server.jar /app/neutrodyne-server.jar
COPY --from=server-files --chmod=0644 /server/THIRD_PARTY_NOTICES.md /server/SOURCES.md /app/
COPY --from=server-files --chown=65532:65532 --chmod=0700 /server/data/ /data/
USER 65532:65532
ENV NEUTRODYNE_SERVER_DATA=/data \
    NEUTRODYNE_SERVER_LISTEN=0.0.0.0:8787
VOLUME /data
EXPOSE 8787
LABEL org.opencontainers.image.source="https://github.com/${REPOSITORY}" \
      org.opencontainers.image.licenses="Unlicense" \
      org.opencontainers.image.description="Neutrodyne Sync server"
# The probe JVM is bounded: it runs inside the same 320 MiB cgroup as serve, and an
# unbounded default heap (25 % of the limit) can OOM-kill the server process.
# start-period gives slow hosts (a Raspberry Pi takes seconds, not the 0.9 s an
# x86 CI runner needs) room before failed probes count toward unhealthy.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s CMD ["/usr/bin/java", "-Xmx32m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xss512k", "-cp", "/app/neutrodyne-server.jar", "ch.lkmc.neutrodyne.sync.server.HealthCheck"]
ENTRYPOINT ["/usr/bin/java", "-Xmx96m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xss512k", "-jar", "/app/neutrodyne-server.jar"]
CMD ["serve"]

FROM runtime AS checkout
COPY --from=server-files --chmod=0644 /server/image-sources.lock.json /app/image-sources.lock.json
LABEL org.opencontainers.image.version="checkout"

FROM runtime AS release
ARG VERSION
ARG REPOSITORY=L-K-M/Neutrodyne
ARG IMAGE_REPOSITORY=ghcr.io/l-k-m/neutrodyne-server
LABEL org.opencontainers.image.version="${VERSION}" \
      ch.lkmc.neutrodyne.sources="${IMAGE_REPOSITORY}:${VERSION}-sources https://github.com/${REPOSITORY}/releases/tag/v${VERSION}"
