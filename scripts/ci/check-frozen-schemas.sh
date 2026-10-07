#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# check-frozen-schemas.sh
#
# Frozen-schema rule (02 Schema export and versioning): a schema version is
# frozen once any tagged release carries it, so every `<n>.json` under a
# `schemas/` directory that exists at the newest `v*` tag must equal HEAD's.
# Before the first tag nothing is frozen and the check passes vacuously.
# Runs in ci.yml's `static` job.

set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." >/dev/null 2>&1 && pwd -P)"
cd "$REPO_ROOT"

TAG="$(git describe --tags --abbrev=0 --match 'v*' 2>/dev/null || true)"
if [ -z "$TAG" ]; then
    echo "check-frozen-schemas: no v* tag yet; nothing is frozen"
    exit 0
fi

echo "check-frozen-schemas: frozen at $TAG"
failed=0
while IFS= read -r file; do
    if ! git diff --quiet "$TAG" -- "$file"; then
        echo "check-frozen-schemas: FAIL  $file differs from frozen schema at $TAG" >&2
        failed=1
    fi
done < <(git ls-tree -r --name-only "$TAG" | grep -E '/schemas/.*/[0-9]+\.json$|/schemas/[0-9]+\.json$')

if [ "$failed" -ne 0 ]; then
    echo "check-frozen-schemas: a frozen schema may never change; add the next version instead" >&2
    exit 1
fi
echo "check-frozen-schemas: PASS"
