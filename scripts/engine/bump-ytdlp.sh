#!/bin/sh
# SPDX-License-Identifier: Unlicense
# stage one yt-dlp stable release into youtube/ytdlp/engine/ for canary gating.
#
# Usage: bump-ytdlp.sh <tag> [--force]
#
# The script is the "download + upstream verify" half of the engine canary
# (docs/design/04-youtube.md): it resolves the GitHub release named <tag>,
# downloads the yt-dlp wheel-zip, its SHA2-256SUMS and the detached sums
# signature, verifies the signature against the pinned release key in
# youtube/ytdlp/keys/ and the archive hash against the signed sums, then
# rewrites youtube/ytdlp/engine/ with the new archive plus metadata.
#
# No GitHub API calls: release assets are located through the tag's
# expanded_assets HTML page and downloaded with a 10 MiB cap each, the same
# ceiling EngineHttp enforces on devices.
set -eu

ROOT=$(CDPATH='' cd -- "$(dirname "$0")/../.." && pwd)
# ND_ENGINE_DIR redirects the staging area in script tests; production keeps the
# real host engine dir, and the component lockfile sits next to it in both.
ENGINE_DIR=${ND_ENGINE_DIR:-"$ROOT/youtube/ytdlp/engine"}
LOCK_FILE="$(dirname "$ENGINE_DIR")/python-components.lock"
PUBLIC_KEY="$ROOT/youtube/ytdlp/keys/yt-dlp-release-key.asc"
FINGERPRINT='AC0CBBE6848D6A873464AF4E57CF65933B5A7581'
CAP_BYTES=10485760

usage() {
    echo 'usage: bump-ytdlp.sh <tag> [--force]' >&2
    exit 2
}

TAG=${1:-}
[ "$TAG" ] || usage
shift
FORCE=0
while [ $# -gt 0 ]; do
    case "$1" in
        --force) FORCE=1 ;;
        *) usage ;;
    esac
    shift
done

# The manifest's versionPattern accepts exactly yyyy.mm.dd; yt-dlp's rare
# ".n" rebuild tags cannot be represented, so they are rejected here rather
# than failing the manifest schema check after a full download.
case "$TAG" in
    *[!0-9.]*|'') usage ;;
esac
case "$TAG" in
    20[0-9][0-9].[0-9][0-9].[0-9][0-9]) ;;
    *) echo "tag '$TAG' is not a yyyy.mm.dd release; rebuild (.n) tags are unapprovable" >&2; exit 2 ;;
esac

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

fetch() {
    # fetch <url> <destination> — capped, fail on HTTP errors.
    url=$1
    dest=$2
    curl -fsSL --max-time 120 --retry 2 -o "$dest" --max-filesize "$CAP_BYTES" "$url" \
        || { echo "download failed: $url" >&2; exit 1; }
    size=$(wc -c <"$dest" | tr -d ' ')
    [ "$size" -le "$CAP_BYTES" ] || {
        echo "asset exceeds the ${CAP_BYTES}-byte cap: $url" >&2
        exit 1
    }
}

# Refuse to clobber a staged tree unless asked: a rerun after an interrupted
# gate must start clean rather than mixing old and new asset generations.
if [ "$FORCE" != 1 ] && [ -f "$ENGINE_DIR/bundled.json" ]; then
    current=$(sed -n 's/.*"version"[^"]*"\([^"]*\)".*/\1/p' "$ENGINE_DIR/bundled.json" | head -1)
    if [ "$current" = "$TAG" ]; then
        echo "youtube/ytdlp/engine already carries $TAG; nothing to do"
        exit 0
    fi
fi

ASSETS_PAGE="$WORK/assets.html"
fetch "https://github.com/yt-dlp/yt-dlp/releases/expanded_assets/$TAG" "$ASSETS_PAGE"

# Asset hrefs look like /yt-dlp/yt-dlp/releases/download/<tag>/<name>.
asset_url() {
    # asset_url <basename> — echo the download URL or die.
    href=$(grep -o "/yt-dlp/yt-dlp/releases/download/$TAG/$1" "$ASSETS_PAGE" | head -1)
    [ "$href" ] || { echo "release $TAG has no $1 asset" >&2; exit 1; }
    printf 'https://github.com%s\n' "$href"
}

fetch "$(asset_url 'yt-dlp')" "$WORK/yt-dlp"
fetch "$(asset_url 'SHA2-256SUMS')" "$WORK/SHA2-256SUMS"
fetch "$(asset_url 'SHA2-256SUMS.sig')" "$WORK/SHA2-256SUMS.sig"

# Upstream detached signature over the sums file, checked by the same pinned
# public key the app ships (EngineKeys.ytDlpReleaseKey) — never a fetched key.
export GNUPGHOME="$WORK/gnupg"
mkdir -p "$GNUPGHOME"
chmod 700 "$GNUPGHOME"
gpg --batch --quiet --import "$PUBLIC_KEY"
status=$(gpg --batch --quiet --status-fd 1 --verify "$WORK/SHA2-256SUMS.sig" "$WORK/SHA2-256SUMS" 2>/dev/null || true)
case "$status" in
    *"VALIDSIG $FINGERPRINT"*) ;;
    *) echo "SHA2-256SUMS.sig does not verify against key $FINGERPRINT" >&2; exit 1 ;;
esac

# The archive hash must match the line inside the *signed* sums file.
expected=$(sed -n 's/^\([0-9a-f]\{64\}\)  yt-dlp$/\1/p' "$WORK/SHA2-256SUMS")
[ "$expected" ] || { echo "SHA2-256SUMS has no yt-dlp line" >&2; exit 1; }
actual=$(shasum -a 256 "$WORK/yt-dlp" | cut -d' ' -f1)
[ "$actual" = "$expected" ] || {
    echo "yt-dlp sha256 $actual does not match the signed sums line $expected" >&2
    exit 1
}

# ejs is detected by the shim at runtime; record which yt_dlp_ejs the archive
# declares so the manifest and BundledZip pin stay in lock-step.
ejs_version=$(unzip -p "$WORK/yt-dlp" 'yt_dlp_ejs/_version.py' 2>/dev/null \
    | sed -n "s/^__version__ = version = ['\"]\([^'\"]*\)['\"].*/\1/p")
if [ ! "$ejs_version" ]; then
    echo "archive declares no yt_dlp_ejs version; expected a bundled_ejs build" >&2
    exit 1
fi

mkdir -p "$ENGINE_DIR"
cp "$WORK/yt-dlp" "$ENGINE_DIR/yt-dlp"
cp "$WORK/SHA2-256SUMS" "$ENGINE_DIR/SHA2-256SUMS"
cp "$WORK/SHA2-256SUMS.sig" "$ENGINE_DIR/SHA2-256SUMS.sig"
printf '{\n  "version": "%s",\n  "tag": "%s",\n  "sha256": "%s",\n  "ejsVersion": "%s"\n}\n' \
    "$TAG" "$TAG" "$actual" "$ejs_version" >"$ENGINE_DIR/bundled.json"

# The same bump a PR would commit: verifyBundledYtDlp and checkPythonLicences
# read the lockfile, so the four yt-dlp-sourced components track the tag here
# (meriyah/astring versions stay pinned — a change there fails the gate for a
# human-reviewed bump, 09 Review rules).
python3 - "$LOCK_FILE" "$TAG" "$ejs_version" <<'PYEOF'
import sys

path, tag, ejs = sys.argv[1:4]
lines = open(path, encoding='utf-8').read().splitlines(keepends=True)
out = []
name = None
versions = {'yt-dlp': tag, 'yt-dlp-ejs': ejs}
for line in lines:
    if line.startswith('name = '):
        name = line.split('"')[1]
    elif line.startswith('version = ') and name in versions:
        line = f'version = "{versions[name]}"\n'
    elif line.startswith('origin = ') and name in versions.keys() | {'meriyah', 'astring'}:
        line = f'origin = "https://github.com/yt-dlp/yt-dlp/releases/download/{tag}/yt-dlp"\n'
    out.append(line)
open(path, 'w', encoding='utf-8').writelines(out)
PYEOF
echo "staged yt-dlp $TAG (sha256 $actual, ejs $ejs_version)"
