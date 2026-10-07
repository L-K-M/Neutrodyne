#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# repro-build.sh [--path P] [--cpus N] [--umask U] <gradle args…>
#
# The pinned release container build (09 Reproducible builds / release.yml
# `android` job). Runs the repository's Gradle build inside the pinned
# `python:3.14-slim-trixie` image — Debian 13's CPython 3.14 (Chaquopy's
# build-time .pyc compilation needs the packaged minor version, 01 S7) plus
# Debian's openjdk-21-jdk-headless, installed below. The image digest is
# Renovate-managed (renovate.json keeps `docker` datasource updates behind
# dashboard approval).
#
#   --path P   mount the checkout at P inside the container (default /build/src)
#   --cpus N   docker --cpus limit (default: host default)
#   --umask U  umask inside the container (default 022)
#
# Every Android build here is signed with the committed keystore through
# `neutrodynePublic`, so there is no signing option and no secret is ever
# mounted. Reproducibility is report-only (D79): the nightly `repro` job diffs
# two runs of this script.
#
# TODO (M0b): when `desktopApp/runtime.lock` exists, the `repro` job also
# installs the pinned Temurin 25 archive inside the container for the desktop
# JAR comparison.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." >/dev/null 2>&1 && pwd -P)"

# Pinned release container (release.yml `android` job, 09): Renovate updates the digest.
IMAGE="python:3.14-slim-trixie@sha256:f85c5697265c178cc6887276c55fe16cf3d14ca35c3df6a5eab3b360534a55d2"

BUILD_PATH="/build/src"
CPUS=""
UMASK="022"

while [ $# -gt 0 ]; do
    case "$1" in
        --path)  BUILD_PATH="$2"; shift 2 ;;
        --cpus)  CPUS="$2"; shift 2 ;;
        --umask) UMASK="$2"; shift 2 ;;
        -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
        --) shift; break ;;
        *) break ;;
    esac
done
[ $# -gt 0 ] || { echo "usage: $0 [--path P] [--cpus N] [--umask U] <gradle args…>" >&2; exit 2; }

command -v docker >/dev/null 2>&1 || { echo "repro-build: docker not found" >&2; exit 2; }

# SOURCE_DATE_EPOCH = the checked-out commit's time (UTC): CPython then writes
# checked-hash .pyc headers for Chaquopy's build-time compile (09 Hygiene).
SOURCE_DATE_EPOCH="$(git -C "$REPO_ROOT" log -1 --format=%ct 2>/dev/null || date +%s)"

DOCKER_ARGS=(--rm -i)
[ -n "$CPUS" ] && DOCKER_ARGS+=(--cpus "$CPUS")

echo "repro-build: image $IMAGE"
echo "repro-build: checkout $REPO_ROOT -> $BUILD_PATH, cpus '${CPUS:-default}', umask $UMASK, SOURCE_DATE_EPOCH=$SOURCE_DATE_EPOCH"

docker run "${DOCKER_ARGS[@]}" \
    -v "$REPO_ROOT:$BUILD_PATH" \
    -w "$BUILD_PATH" \
    -e LC_ALL=C.UTF-8 \
    -e TZ=UTC \
    -e SOURCE_DATE_EPOCH="$SOURCE_DATE_EPOCH" \
    -e ANDROID_HOME=/opt/android-sdk \
    "$IMAGE" \
    bash -euxo pipefail -c '
        umask '"$UMASK"'
        apt-get update -qq
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends \
            openjdk-21-jdk-headless git unzip curl >/dev/null
        export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
        export PATH="$JAVA_HOME/bin:$PATH"
        scripts/ci/install-android-sdk.sh /opt/android-sdk
        ./gradlew --no-daemon --no-build-cache --stacktrace "$@"
    ' -- "$@"
