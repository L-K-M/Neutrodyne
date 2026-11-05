#!/bin/sh
# SPDX-License-Identifier: Unlicense
# The blocking half of the engine canary (09 engine-canary.yml, job `gate`).
#
#   canary.sh --tag TAG [--out FILE]   stage the candidate, then run the gate
#   canary.sh --staged [--out FILE]    gate only; youtube/ytdlp/engine is ready
#
# The gate command is the full verification leg: upstream signature and sums
# (verifyBundledYtDlp), licence inventory, the contract-mode shim suite and
# the vendored-release trust-chain test. CANARY_GATE_CMD overrides it in
# tests so a failing self-test can be observed blocking the candidate output.
#
# On green the candidate's version/tag/sha256/ejsVersion are written as
# `key=value` lines to --out (or stdout); on red nothing is written and the
# exit is nonzero, so a later approve job can only ever see a gated engine.
set -eu

ROOT=$(CDPATH='' cd -- "$(dirname "$0")/../.." && pwd)
ENGINE_DIR=${ND_ENGINE_DIR:-"$ROOT/youtube/ytdlp/engine"}

GATE_CMD=${CANARY_GATE_CMD:-"./gradlew :youtube:ytdlp:verifyBundledYtDlp :youtube:ytdlp:checkPythonLicences :youtube:ytdlp:shimTest :youtube:ytdlp:preparedShimTest :youtube:engine:test --tests '*UpstreamReleaseVerifierTest*' -Preplay=contract"}

TAG=''
STAGED=0
OUT=''
while [ $# -gt 0 ]; do
    case "$1" in
        --tag) TAG=$2; shift 2 ;;
        --staged) STAGED=1; shift ;;
        --out) OUT=$2; shift 2 ;;
        *) echo "usage: canary.sh --tag TAG | --staged [--out FILE]" >&2; exit 2 ;;
    esac
done
[ "$STAGED" = 1 ] || [ "$TAG" ] || { echo "canary.sh: need --tag or --staged" >&2; exit 2; }
[ "$STAGED" = 1 ] && [ "$TAG" ] && { echo "canary.sh: --tag and --staged are exclusive" >&2; exit 2; }
[ -f "$ENGINE_DIR/bundled.json" ] || { echo "canary.sh: $ENGINE_DIR/bundled.json missing" >&2; exit 2; }

if [ "$TAG" ]; then
    "$ROOT/scripts/engine/bump-ytdlp.sh" "$TAG"
fi

# The gate is the only exit gate between an upstream release and an approval:
# run it as one command line so a self-test, replay or trust-chain failure is
# a single nonzero result.
cd "$ROOT"
# shellcheck disable=SC2086 # GATE_CMD is a deliberately split command line.
sh -c "$GATE_CMD"

emit() {
    # emit <key> <jq-expression over bundled.json>
    value=$(python3 -c "import json,sys;print(json.load(open('$ENGINE_DIR/bundled.json'))$1)")
    printf '%s=%s\n' "$2" "$value"
}

{
    emit "['version']" version
    emit "['tag']" tag
    emit "['sha256']" sha256
    emit "['ejsVersion']" ejsVersion
} >"${OUT:-/dev/stdout}"
