#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
# --no-sources skips only release source matching, never runtime/security checks.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
mode=release
if [[ "${1:-}" == --no-sources || "${1:-}" == --sources-tag ]]; then
    mode="${1#--}"
    shift
fi
image="${1:?usage: check-server-image.sh [--no-sources|--sources-tag] image [sources-directory]}"
source_dir="${2:-}"
work="$(mktemp -d)"
container=''
cleanup() {
    [[ -z "$container" ]] || docker rm -fv "$container" >/dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT
# Exporting a created container is read-only; do not rely on a shell in distroless.
docker image inspect "$image" > "$work/config.json" 2>/dev/null || {
    docker pull "$image"
    docker image inspect "$image" > "$work/config.json"
}
version="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[0]["Config"]["Labels"]["org.opencontainers.image.version"])' "$work/config.json")"
arch="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[0]["Architecture"])' "$work/config.json")"
container="$(docker create "$image")"
docker export "$container" > "$work/rootfs.tar"
python3 "$root/scripts/ci/server-image.py" check-rootfs --directory "$work/rootfs.tar" \
    --config "$work/config.json" --arch "$arch" --version "$version"
docker rm -fv "$container" >/dev/null
container=''
# Exercise the actual JAR, JNI extraction, read-only root, noexec /tmp and nonroot volume.
container="$(docker run -d --read-only --tmpfs /tmp:noexec,nosuid,nodev,size=16m \
    --cap-drop ALL --security-opt no-new-privileges:true --memory 320m \
    -e NEUTRODYNE_SERVER_UPDATE_CHECK=false -e NEUTRODYNE_SERVER_LISTEN=127.0.0.1:8787 \
    "$image" serve)"
ready=false
for _ in $(seq 1 60); do
    if docker exec "$container" /usr/bin/java -cp /app/neutrodyne-server.jar \
        ch.lkmc.neutrodyne.sync.server.HealthCheck; then ready=true; break; fi
    sleep 1
done
[[ "$ready" == true ]] || { docker logs "$container"; exit 1; }
docker exec "$container" /usr/bin/java -jar /app/neutrodyne-server.jar doctor
docker exec "$container" /usr/bin/java -jar /app/neutrodyne-server.jar backup /data/image-check.db
# The exclusive restore lease must refuse while serve is running.
if docker exec "$container" /usr/bin/java -jar /app/neutrodyne-server.jar restore /data/image-check.db; then
    echo 'restore accepted a live server' >&2
    exit 1
fi
if [[ "$mode" == no-sources ]]; then exit 0; fi
[[ -n "$source_dir" ]] || { echo 'source directory required' >&2; exit 1; }
python3 "$root/scripts/ci/server-image.py" check-sources --directory "$source_dir" --version "$version"
if [[ "$mode" == sources-tag ]]; then
    source_tag="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[0]["Config"]["Labels"]["ch.lkmc.neutrodyne.sources"].split()[0])' "$work/config.json")"
    mkdir "$work/sources"
    oras pull "$source_tag" -o "$work/sources"
    python3 "$root/scripts/ci/server-image.py" check-sources --directory "$work/sources" --version "$version"
fi
