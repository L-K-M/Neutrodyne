#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# verify-tag.sh [tag] [--products a,b]
#
# release.yml step 1 (09): runs on the tag checkout (fetch-depth 0). A tag
# publishes only when every precondition holds:
#
#   * the tag equals `v` + neutrodyne.versionName and matches
#     ^v[0-9]+\.[0-9]+\.[0-9]+$ (no suffix, S = 95 version code)
#   * the tagged commit is on `main` or a `release/*` branch (hotfixes)
#   * changelogs/<versionCode>.txt exists in it
#   * ci.yml concluded `success` for the tagged commit — OR the commit is a
#     release.sh commit: exactly one parent, ci.yml `success` on that parent,
#     and the diff touches only gradle.properties with the two version lines.
#     (The release commit's own ci.yml starts together with release.yml, and a
#     version-line change cannot alter what CI verified.)
#   * make_latest: true when this tag's versionCode is higher than the current
#     latest release's; true when no published release exists yet ("release not
#     found"), so the first release is latest; any other gh error fails.
#
# --products selects the asset groups of this release from release-assets.json
# (default: android — the only product that ships at M0a; desktop, sources and
# server join as their jobs land).
#
# Outputs (GITHUB_OUTPUT and stdout): version, versionCode, make_latest,
# youtube_engine, mac_kind, expected_assets (newline-separated asset names).

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." >/dev/null 2>&1 && pwd -P)"
cd "$REPO_ROOT"

TAG=""
PRODUCTS="android"
for arg in "$@"; do
    case "$arg" in
        --products=*) PRODUCTS="${arg#--products=}" ;;
        -h|--help) sed -n '2,33p' "$0"; exit 0 ;;
        *) [ -z "$TAG" ] && TAG="$arg" ;;
    esac
done
TAG="${TAG:-${GITHUB_REF_NAME:-}}"

fail() { echo "verify-tag: FAIL  $*" >&2; exit 1; }
out() { echo "$1=$2"; [ -n "${GITHUB_OUTPUT:-}" ] && echo "$1=$2" >> "$GITHUB_OUTPUT" || true; }

[ -n "$TAG" ] || fail "no tag (arg or GITHUB_REF_NAME)"
grep -qE '^v[0-9]+\.[0-9]+\.[0-9]+$' <<< "$TAG" || fail "tag '$TAG' is not vX.Y.Z (no suffixes, PO-33)"
VERSION="${TAG#v}"

prop() { grep -E "^$1=" gradle.properties | tail -1 | cut -d= -f2-; }
GV="$(prop neutrodyne.versionName)"; GC="$(prop neutrodyne.versionCode)"
REPO_URL="$(prop neutrodyne.repoUrl)"
ENGINE="$(prop neutrodyne.youtubeEngine)"

[ "$GV" = "$VERSION" ] || fail "tag $TAG but gradle.properties carries versionName=$GV"
case "$GC" in *95) ;; *) fail "versionCode $GC does not end in S=95" ;; esac

MAJOR="${VERSION%%.*}"; REST="${VERSION#*.}"; MINOR="${REST%%.*}"; PATCH="${REST#*.}"
[ "$MINOR" -le 99 ] && [ "$PATCH" -le 99 ] || fail "MINOR and PATCH must be <= 99 (D63)"
WANT_CODE=$((MAJOR * 1000000 + MINOR * 10000 + PATCH * 100 + 95))
[ "$GC" -eq "$WANT_CODE" ] || fail "versionCode $GC != $WANT_CODE for $VERSION (D63)"

[ -f "changelogs/$GC.txt" ] || fail "changelogs/$GC.txt missing in the tag"
[ -s "changelogs/$GC.txt" ] || fail "changelogs/$GC.txt is empty"
notes_len=$(wc -c < "changelogs/$GC.txt")
[ "$notes_len" -le 500 ] || fail "changelogs/$GC.txt is $notes_len chars (> 500)"

# --- the tagged commit must sit on main or a release/* branch -----------------

SHA="$(git rev-parse HEAD)"
# a tag checkout may not carry the remote branch refs; fetch just what we test
git fetch -q origin +refs/heads/main:refs/remotes/origin/main \
    '+refs/heads/release/*:refs/remotes/origin/release/*' 2>/dev/null || true
branches="$(git branch -r --contains "$SHA" 2>/dev/null | tr -d ' ' || true)"
grep -qE '(^|\n)origin/(main|release/[0-9]+\.[0-9]+)$' <<< "$branches" \
    || fail "tagged commit is not on origin/main or an origin/release/* branch"

# --- ci.yml success on the tagged commit, else the release.sh commit shape ----

REPO="${GITHUB_REPOSITORY:-${REPO_URL#https://github.com/}}"
ci_green() { # ci_green <sha>: at least one ci.yml run on it concluded success
    local n
    n="$(gh api "repos/$REPO/actions/workflows/ci.yml/runs?head_sha=$1&per_page=10" \
        --jq '[.workflow_runs[] | select(.conclusion == "success")] | length' 2>/dev/null)" || return 1
    [ -n "$n" ] && [ "$n" -ge 1 ] 2>/dev/null
}

if ! ci_green "$SHA"; then
    # the release.sh fallback: one parent, parent green, only the two version lines
    parents="$(git rev-list --parents -n1 HEAD | wc -w)"
    [ "$parents" -eq 2 ] || fail "no green ci.yml for $SHA and the commit has $((parents-1)) parents (release.sh makes one)"
    PARENT="$(git rev-parse HEAD^)"
    ci_green "$PARENT" || fail "no green ci.yml for $SHA or its parent $PARENT"
    files="$(git diff --numstat HEAD^ HEAD -- | awk '{print $3}')"
    [ "$files" = "gradle.properties" ] || fail "release commit touches more than gradle.properties: $files"
    changed="$(git diff HEAD^ HEAD -- gradle.properties | grep -cE '^[+-]neutrodyne\.version(Name|Code)=')"
    total="$(git diff HEAD^ HEAD -- gradle.properties | grep -cE '^[+-][^+-]')"
    [ "$changed" -eq 4 ] && [ "$total" -eq 4 ] \
        || fail "release commit must change exactly the two version lines ($changed/4 version lines of $total changes)"
    echo "verify-tag: accepted the release.sh commit shape (CI on parent $PARENT)"
fi

# --- make_latest ----------------------------------------------------------------

# The latest release is the most recent non-prerelease, non-draft one (every tag
# is normal, so "latest" is the release the last publish marked). A 404 means no
# published release exists yet — the first release is latest; every other error
# fails the run (09 release.yml step 1).
latest_err="$(mktemp)"
if latest_tag="$(gh api "repos/$REPO/releases/latest" --jq .tag_name 2>"$latest_err")"; then
    lv="${latest_tag#v}"
    lm="${lv%%.*}"; lr="${lv#*.}"; ln="${lr%%.*}"; lp="${lr#*.}"
    LATEST_CODE=$((lm * 1000000 + ln * 10000 + lp * 100 + 95))
    if [ "$GC" -gt "$LATEST_CODE" ]; then MAKE_LATEST=true; else MAKE_LATEST=false; fi
    echo "verify-tag: latest release is $latest_tag ($LATEST_CODE)"
elif grep -qE '404|Not Found' "$latest_err"; then
    MAKE_LATEST=true
    echo "verify-tag: no published release yet (release not found)"
else
    cat "$latest_err" >&2
    rm -f "$latest_err"
    fail "gh api releases/latest failed for $REPO"
fi
rm -f "$latest_err"

# mac_kind: the DMG appears with 1.0.0 (jpackage refuses a leading 0; PO-39, D63).
if [ "$MAJOR" -ge 1 ]; then MAC_KIND=dmg; else MAC_KIND=mac-zip; fi

# --- expected assets ------------------------------------------------------------

EXPECTED="$(PRODUCTS="$PRODUCTS" MAC_EXT="$([ "$MAC_KIND" = dmg ] && echo dmg || echo zip)" \
    python3 - "$REPO_ROOT/release-assets.json" "$VERSION" <<'PYEOF'
import json, os, sys

doc = json.load(open(sys.argv[1], encoding="utf-8"))
version = sys.argv[2]
products = set(os.environ["PRODUCTS"].split(","))
mac_ext = os.environ["MAC_EXT"]

names = list(doc["always"])
for group in products:
    files = doc["groups"].get(group)
    if files is None:
        sys.exit(f"verify-tag: release-assets.json has no group '{group}'")
    names += files
for n in names:
    print(n.replace("{v}", version).replace("{mac_ext}", mac_ext))
PYEOF
)" || exit 1

echo "verify-tag: tag $TAG, version $VERSION ($GC), make_latest=$MAKE_LATEST, engine=$ENGINE, mac_kind=$MAC_KIND"
echo "verify-tag: expected assets:"
while IFS= read -r a; do echo "  $a"; done <<< "$EXPECTED"

out version "$VERSION"
out versionCode "$GC"
out make_latest "$MAKE_LATEST"
out youtube_engine "$ENGINE"
out mac_kind "$MAC_KIND"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
    { echo "expected_assets<<__NL_EOF__"; echo "$EXPECTED"; echo "__NL_EOF__"; } >> "$GITHUB_OUTPUT"
fi
