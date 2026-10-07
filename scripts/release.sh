#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# release.sh <patch|minor|major|X.Y.Z> [--hotfix] [--dry-run]
#
# The release procedure of 09 (Versioning and signing): bumps the two version
# lines in gradle.properties (the single source of truth, D63), commits
# "Release vX.Y.Z" and tags `vX.Y.Z` (annotated; signed when the maintainer's
# git signing is configured), then `git push --atomic origin <branch> vX.Y.Z`
# and release.yml takes over.
#
#   patch/minor/major   bump that component (MINOR/PATCH <= 99; major is a PO
#                       decision), S = 95
#   X.Y.Z               name it explicitly; a suffix is refused
#   --hotfix            run on a release/X.Y branch instead of main
#   --dry-run           print the computed version and stop before changing
#                       anything (use it to name changelogs/<versionCode>.txt)
#
# Equal versionCode without an existing tag is the "tag the prepared version"
# case: `release.sh 0.1.0` for the very first release tags HEAD without a
# release commit. A versionCode is never reused.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." >/dev/null 2>&1 && pwd -P)"
cd "$REPO_ROOT"

OP=""
HOTFIX=0
DRY=0
for arg in "$@"; do
    case "$arg" in
        --hotfix) HOTFIX=1 ;;
        --dry-run) DRY=1 ;;
        -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
        patch|minor|major) OP="$arg" ;;
        [0-9]*.[0-9]*.[0-9]*) OP="$arg" ;;
        *) echo "usage: $0 <patch|minor|major|X.Y.Z> [--hotfix] [--dry-run]" >&2; exit 2 ;;
    esac
done
[ -n "$OP" ] || { echo "usage: $0 <patch|minor|major|X.Y.Z> [--hotfix] [--dry-run]" >&2; exit 2; }

fail() { echo "release: FAIL  $*" >&2; exit 1; }
prop() { grep -E "^$1=" gradle.properties | tail -1 | cut -d= -f2-; }

CUR_NAME="$(prop neutrodyne.versionName)"
CUR_CODE="$(prop neutrodyne.versionCode)"
CUR_MAJOR="${CUR_NAME%%.*}"; CR="${CUR_NAME#*.}"; CUR_MINOR="${CR%%.*}"; CUR_PATCH="${CR#*.}"

case "$OP" in
    patch) NV_MAJOR=$CUR_MAJOR; NV_MINOR=$CUR_MINOR; NV_PATCH=$((CUR_PATCH + 1)) ;;
    minor) NV_MAJOR=$CUR_MAJOR; NV_MINOR=$((CUR_MINOR + 1)); NV_PATCH=0 ;;
    major) NV_MAJOR=$((CUR_MAJOR + 1)); NV_MINOR=0; NV_PATCH=0 ;;
    *)     NV_MAJOR="${OP%%.*}"; NR="${OP#*.}"; NV_MINOR="${NR%%.*}"; NV_PATCH="${NR#*.}" ;;
esac
NEW_NAME="$NV_MAJOR.$NV_MINOR.$NV_PATCH"
[ "$NV_MINOR" -le 99 ] && [ "$NV_PATCH" -le 99 ] || fail "MINOR and PATCH must be <= 99 (D63)"
NEW_CODE=$((NV_MAJOR * 1000000 + NV_MINOR * 10000 + NV_PATCH * 100 + 95))
NEW_TAG="v$NEW_NAME"

if [ "$DRY" -eq 1 ]; then
    echo "release: $CUR_NAME ($CUR_CODE) -> $NEW_NAME ($NEW_CODE), tag $NEW_TAG"
    echo "release: changelog file: changelogs/$NEW_CODE.txt"
    exit 0
fi

# --- 1. preconditions -----------------------------------------------------------

BRANCH="$(git symbolic-ref --short -q HEAD || true)"
if [ "$HOTFIX" -eq 1 ]; then
    grep -qE '^release/[0-9]+\.[0-9]+$' <<< "$BRANCH" || fail "--hotfix must run on a release/X.Y branch (got '${BRANCH:-detached}')"
    case "$OP" in
        patch|[0-9]*.[0-9]*.[0-9]*) ;;
        minor|major) fail "--hotfix ships a PATCH only" ;;
    esac
else
    [ "$BRANCH" = "main" ] || fail "releases run on main (or release/X.Y with --hotfix); got '${BRANCH:-detached}'"
fi
[ -z "$(git status --porcelain)" ] || fail "working tree is not clean"
HEAD_SHA="$(git rev-parse HEAD)"
[ "$HEAD_SHA" = "$(git rev-parse "origin/$BRANCH" 2>/dev/null || echo none)" ] \
    || fail "HEAD != origin/$BRANCH — push or rebase first"

command -v gh >/dev/null 2>&1 || fail "gh is needed for the preconditions"
REPO="$(prop neutrodyne.repoUrl | sed 's|https://github.com/||')"
# --commit is forwarded to the API as head_sha, which does not resolve ref names —
# the literal "HEAD" would never match a run.
ci_ok="$(gh run list --repo "$REPO" --branch "$BRANCH" --workflow ci.yml --commit "$HEAD_SHA" --limit 5 \
    --json conclusion --jq '[.[] | select(.conclusion == "success")] | length' 2>/dev/null || echo 0)"
[ "$ci_ok" -ge 1 ] || fail "no green ci.yml run for HEAD (09 release.sh step 1)"
blockers="$(gh issue list --repo "$REPO" --label release-blocker --state open --json number --jq length 2>/dev/null || echo '?')"
[ "$blockers" = "0" ] || fail "open release-blocker issues: $blockers"

# the latest scheduled nightly is green — or, for a PATCH hotfix, a dispatched
# nightly whose checkout IS this commit and whose youtube-smoke jobs
# (instrumented-full + release-build-smoke, nightly.yml's scope filter) ran
# green on it. A green run for another sha or a narrower scope proves nothing.
# Judged per job: `repro` is report-only (D79), so its result never gates a tag; every other
# job of the latest finished scheduled run must have succeeded (or been skipped by its scope).
nightly=""
nightly_id="$(gh run list --repo "$REPO" --workflow nightly.yml --branch "$BRANCH" --limit 5 \
    --json databaseId,conclusion,event \
    --jq '[.[] | select(.event == "schedule" and .conclusion != null)] | .[0].databaseId // empty' 2>/dev/null || echo '')"
if [ -n "$nightly_id" ]; then
    failed_jobs="$(gh api "repos/$REPO/actions/runs/$nightly_id/jobs?per_page=100" \
        --jq '[.jobs[] | select(.name != "repro" and .conclusion != "success" and .conclusion != "skipped") | .name] | join(", ")' \
        2>/dev/null || echo 'unknown')"
    if [ -z "$failed_jobs" ]; then nightly="success"; else nightly="failed: $failed_jobs"; fi
fi
if [ "$nightly" != "success" ]; then
    smoke_ok=0
    if [ "$HOTFIX" -eq 1 ]; then
        # nightly.yml checks out github.sha, so a dispatched run tests exactly
        # its head_sha; --commit keeps runs dispatched from another ref (or
        # green on an older, pre-rebase HEAD) out of this gate
        dispatched="$(gh run list --repo "$REPO" --workflow nightly.yml --branch "$BRANCH" \
            --commit "$HEAD_SHA" --limit 10 --json databaseId,conclusion,event \
            --jq '[.[] | select(.event == "workflow_dispatch" and .conclusion == "success")] | .[].databaseId' \
            2>/dev/null || true)"
        for run_id in $dispatched; do
            jobs="$(gh api "repos/$REPO/actions/runs/$run_id/jobs?per_page=100" \
                --jq '[.jobs[] | select(.conclusion == "success") | .name] | join(" ")' 2>/dev/null || echo '')"
            if grep -qw "instrumented-full" <<< "$jobs" && grep -qw "release-build-smoke" <<< "$jobs"; then
                smoke_ok=1
                break
            fi
        done
    fi
    if [ "$smoke_ok" -eq 1 ]; then
        echo "release: no scheduled green nightly; accepting dispatched youtube-smoke green on $HEAD_SHA"
    else
        fail "latest scheduled nightly.yml on $BRANCH is not green (${nightly:-none})"
    fi
fi

# --- 2. the new version ----------------------------------------------------------

git rev-parse --verify -q "refs/tags/$NEW_TAG" >/dev/null && fail "tag $NEW_TAG already exists"
[ "$NEW_CODE" -lt "$CUR_CODE" ] && fail "$NEW_CODE < current $CUR_CODE — version codes never go down"
TAG_EXISTS=0
git rev-parse --verify -q "refs/tags/v$CUR_NAME" >/dev/null && TAG_EXISTS=1
PREPARED=0
if [ "$NEW_CODE" -eq "$CUR_CODE" ]; then
    [ "$TAG_EXISTS" -eq 0 ] || fail "versionCode $NEW_CODE already tagged (v$CUR_NAME)"
    [ "$NEW_NAME" = "$CUR_NAME" ] || fail "equal versionCode but different name — bump instead"
    PREPARED=1 # tag the prepared version: no release commit (first release, v0.1.0)
fi

# --- 3. the changelog is already in HEAD -----------------------------------------

CHANGELOG="changelogs/$NEW_CODE.txt"
[ -f "$CHANGELOG" ] || fail "$CHANGELOG must be in HEAD already (merged through a PR; --dry-run prints the name)"
[ -s "$CHANGELOG" ] || fail "$CHANGELOG is empty"
[ "$(wc -c < "$CHANGELOG")" -le 500 ] || fail "$CHANGELOG is longer than 500 characters"

# --- 4. version lines, commit, tag ------------------------------------------------

if [ "$PREPARED" -eq 0 ]; then
    python3 - <<PYEOF
import re
p = "gradle.properties"
s = open(p, encoding="utf-8").read()
s, n1 = re.subn(r"(?m)^neutrodyne\.versionName=.*$", "neutrodyne.versionName=$NEW_NAME", s)
s, n2 = re.subn(r"(?m)^neutrodyne\.versionCode=.*$", "neutrodyne.versionCode=$NEW_CODE", s)
assert n1 == 1 and n2 == 1, "version lines not found"
open(p, "w", encoding="utf-8").write(s)
PYEOF
    git add gradle.properties
    git commit -m "Release $NEW_TAG" >/dev/null
    echo "release: committed the version bump (single file, two lines — the diff verify-tag.sh accepts)"
fi
# Annotated; signed when the maintainer's git config signs tags (09).
git tag -a "$NEW_TAG" -m "Neutrodyne $NEW_NAME"

# --- 5. push ----------------------------------------------------------------------

echo "release: pushing $BRANCH + $NEW_TAG (ruleset bypass for maintainers)"
git push --atomic origin "$BRANCH" "$NEW_TAG"
echo "release: done — release.yml takes over ($NEW_TAG, versionCode $NEW_CODE)"
