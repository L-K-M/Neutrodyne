# SPDX-License-Identifier: Unlicense
# D95: digest and exact source inventory move together in reviewed updates.
FROM gcr.io/distroless/java25-debian13:nonroot@sha256:bb5fd2c016691aef1cf9ff376294c1eeaa5b635af240a0a3ed3242e2877f7866
ARG VERSION
ARG REPOSITORY=L-K-M/Neutrodyne
ARG IMAGE_REPOSITORY=ghcr.io/l-k-m/neutrodyne-server
COPY neutrodyne-server-${VERSION}.jar /app/neutrodyne-server.jar
COPY THIRD_PARTY_NOTICES.md SOURCES.md /app/
COPY --chown=65532:65532 --chmod=0700 data/ /data/
USER 65532:65532
ENV NEUTRODYNE_SERVER_DATA=/data \
    NEUTRODYNE_SERVER_LISTEN=0.0.0.0:8787
VOLUME /data
EXPOSE 8787
LABEL org.opencontainers.image.source="https://github.com/${REPOSITORY}" \
      org.opencontainers.image.licenses="Unlicense" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.description="Neutrodyne Sync server" \
      ch.lkmc.neutrodyne.sources="${IMAGE_REPOSITORY}:${VERSION}-sources https://github.com/${REPOSITORY}/releases/tag/v${VERSION}"
HEALTHCHECK --interval=30s --timeout=5s CMD ["/usr/bin/java", "-cp", "/app/neutrodyne-server.jar", "ch.lkmc.neutrodyne.sync.server.HealthCheck"]
ENTRYPOINT ["/usr/bin/java", "-Xmx96m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xss512k", "-jar", "/app/neutrodyne-server.jar"]
CMD ["serve"]
