#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
# Update a Neutrodyne Sync deployment from its Git checkout.
set -euo pipefail

usage() {
    echo "Usage: ./update.sh [branch]"
    echo "Fast-forward the current or named branch from origin and rebuild the Docker Compose server."
}

supports_health_wait() {
    local up_help
    up_help=$("$@" up --help 2>/dev/null) && [[ "$up_help" == *--wait-timeout* ]]
}

if [[ $# -eq 1 && ${1} == --help ]]; then
    usage
    exit 0
fi
if [[ $# -gt 1 ]]; then
    usage >&2
    exit 2
fi

cd -- "$(dirname -- "$0")"
if supports_health_wait docker compose; then
    compose=(docker compose)
elif supports_health_wait docker-compose; then
    compose=(docker-compose)
else
    echo "Docker Compose 2.20 or later with up --wait-timeout support is required." >&2
    exit 1
fi

if [[ $# -eq 1 ]]; then
    branch=$1
elif ! branch=$(git symbolic-ref --quiet --short HEAD); then
    echo "The checkout is detached. Pass the branch to deploy." >&2
    exit 1
fi
ref="refs/heads/$branch"
git check-ref-format "$ref"
if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
    echo "Commit tracked changes before updating. Put deployment settings in .env or compose.override.yaml." >&2
    exit 1
fi

echo "==> Syncing $branch from origin"
remote_ref="refs/remotes/origin/$branch"
git fetch --no-tags origin "+$ref:$remote_ref"
revision=$(git rev-parse --verify "$remote_ref")
if ! git cat-file -e "$revision:compose.yaml"; then
    echo "Branch $branch does not contain the Neutrodyne Compose deployment." >&2
    exit 1
fi
if git show-ref --verify --quiet "$ref"; then
    if ! git merge-base --is-ancestor "$ref" "$revision"; then
        echo "Local branch $branch has commits outside origin/$branch. Resolve those before updating." >&2
        exit 1
    fi
    git switch --no-overwrite-ignore -- "$branch"
    git merge --ff-only --no-overwrite-ignore "$revision"
else
    git switch --no-overwrite-ignore --create "$branch" "$revision"
fi

echo "==> Building the server image"
"${compose[@]}" build --pull
echo "==> Starting the server and waiting for it to become healthy"
if ! "${compose[@]}" up -d --no-build --remove-orphans --wait --wait-timeout 120; then
    "${compose[@]}" logs --tail=40 neutrodyne-server >&2 || true
    exit 1
fi
"${compose[@]}" ps
