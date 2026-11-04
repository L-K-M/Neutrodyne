#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
# Residential/manual only. Outputs a new staging directory for review, never runs in CI.
set -euo pipefail
if [[ -n "${CI:-}" ]]; then
    echo "Recording must not run in CI" >&2
    exit 1
fi
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$repo_root"
shim_python=$(command -v python3.14 || command -v python3)
# Reuse the test bootstrap's exact hash check; never import an arbitrary installed yt-dlp.
shim_asset=$("$shim_python" -B -c 'import sys; sys.path.insert(0, "youtube/engine/python/tests"); from run import prepare; print(prepare())')
export PYTHONPATH="$shim_asset:$repo_root/youtube/engine/python"
exec "$shim_python" -B -m neutrodyne_ytx.record "$@"
