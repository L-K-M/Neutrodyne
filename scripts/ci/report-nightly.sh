#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# report-nightly.sh <job> [--label L] [--close]
#
# One issue per failing nightly or canary job (09 nightly.yml): a failing job
# opens or updates the issue, the next green run closes it. Called by
# nightly.yml steps with `if: failure()` (default) and `if: success()`
# (--close). Needs GH_TOKEN/GITHUB_TOKEN with `issues: write`.
#
#   --label L   issue label (default nightly-failure; `repro` for the
#               report-only reproducibility job, which never blocks)
#   --close     mark the job's issue fixed instead of opening/updating one
#
# The issue is matched by a marker comment (`<!-- nightly-job:<job> -->`) so a
# renamed title still maps to its job.

set -euo pipefail

JOB=""
LABEL="nightly-failure"
CLOSE=0

while [ $# -gt 0 ]; do
    case "$1" in
        --label) LABEL="$2"; shift 2 ;;
        --close) CLOSE=1; shift ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) if [ -z "$JOB" ]; then JOB="$1"; shift; else echo "usage: $0 <job> [--label L] [--close]" >&2; exit 2; fi ;;
    esac
done

[ -n "$JOB" ] || { echo "usage: $0 <job> [--label L] [--close]" >&2; exit 2; }
command -v gh >/dev/null 2>&1 || { echo "report-nightly: gh not found" >&2; exit 2; }

REPO="${GITHUB_REPOSITORY:-}"
RUN_URL="${GITHUB_SERVER_URL:-https://github.com}/${REPO}/actions/runs/${GITHUB_RUN_ID:-}"
MARKER="<!-- nightly-job:$JOB -->"
TITLE="nightly: $JOB failing"

existing="$(gh issue list --repo "$REPO" --label "$LABEL" --state open --limit 50 --json number,body 2>/dev/null || echo '[]')"
issue_no="$(printf '%s' "$existing" | python3 -c "
import json, sys
for i in json.load(sys.stdin):
    if '$MARKER' in i.get('body') or '':
        print(i['number']); break
")"

if [ "$CLOSE" -eq 1 ]; then
    if [ -n "$issue_no" ]; then
        gh issue close "$issue_no" --repo "$REPO" \
            --comment "green again in ${RUN_URL} — closing (the job files one issue per failure streak)."
        echo "report-nightly: closed #$issue_no for $JOB"
    else
        echo "report-nightly: nothing open for $JOB"
    fi
    exit 0
fi

body="$(cat <<EOF
$MARKER

The nightly job \`$JOB\` failed: $RUN_URL

One issue per job (09 nightly.yml): it is updated on each failure and closed by
the next green run. Fix forward — a red nightly blocks the next release.
EOF
)"

if [ -n "$issue_no" ]; then
    gh issue comment "$issue_no" --repo "$REPO" --body "still failing: $RUN_URL"
    echo "report-nightly: updated #$issue_no for $JOB"
else
    gh issue create --repo "$REPO" --label "$LABEL" --title "$TITLE" --body "$body" >/dev/null
    echo "report-nightly: opened an issue for $JOB"
fi
