#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
# Called only after immutable publication, in the shared release-publish concurrency group.
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
tag="${1:?tag required}"
image="${2:?digest reference required}"
sources="${3:?verified source directory required}"
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || exit 1
[[ "$image" =~ ^ghcr.io/[a-z0-9-]+/neutrodyne-server@sha256:[a-f0-9]{64}$ ]] || exit 1
repository="${GITHUB_REPOSITORY:?}"
version="${tag#v}"
image_repo="${image%@*}"
# A failed draft must never move any release image tag.
release="$(gh api "repos/$repository/releases/tags/$tag")"
python3 -c 'import json,sys; r=json.loads(sys.argv[1]); sys.exit(0 if not r["draft"] and not r["prerelease"] and r["immutable"] else "Release is not immutable and published")' "$release"
python3 "$root/scripts/ci/server-image.py" check-sources --version "$version" --directory "$sources"
runtime="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["runtime"]["name"])' "$root/sync/server/deploy/image-sources.lock.json")"
(
    cd "$sources"
    oras push "$image_repo:$version-sources" --artifact-type application/vnd.neutrodyne.sources.v1 \
        "$runtime:application/gzip" "neutrodyne-server-image-sources-$version.tar.xz:application/x-xz" \
        RUNTIME-SOURCES.md:text/markdown
)
"$root/scripts/ci/check-server-image.sh" --sources-tag "$image" "$sources"
# Re-read publication order while serialized. verify-tag's preview can be hours old.
latest="$(gh api "repos/$repository/releases/latest" --jq .tag_name)"
releases="$(gh api --paginate "repos/$repository/releases?per_page=100" --jq '.[] | select(.draft == false and .prerelease == false) | .tag_name')"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
printf '%s\n' "$releases" > "$work/releases"
selected="$(python3 "$root/scripts/ci/server-image.py" promotion-tags --version "$version" --directory "$work/releases" --latest "$latest" --make-latest "${PUBLISH_MAKE_LATEST:?}")"
mapfile -t names <<< "$selected"
tags=()
for name in "${names[@]}"; do tags+=(-t "$image_repo:$name"); done
docker buildx imagetools create "${tags[@]}" "$image"
for ((i=1; i<${#tags[@]}; i+=2)); do
    [[ "$(oras resolve "${tags[$i]}")" == "${image#*@}" ]] || exit 1
done
echo 'Verified immutable version, ordered minor/latest tags and same-registry source offer'
