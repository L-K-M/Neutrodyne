#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
set -euo pipefail
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
exec python3 "$root/scripts/ci/server-image.py" collect --version "${1:?version required}" --directory "${2:?output directory required}"
