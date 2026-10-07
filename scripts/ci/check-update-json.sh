#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# check-update-json.sh <manifest> [assets-dir] [tag]
#
# Validates neutrodyne-update.json against schema 1's field rules (09 Update
# manifest), the tag (default: v + neutrodyne.versionName), gradle.properties,
# neutrodyne.repoUrl and the changelog. With <assets-dir> every listed file is
# checked present with matching size and sha256. A release is never published
# without a valid manifest — the update checks read
# releases/latest/download/neutrodyne-update.json. Also run in PRs that touch
# the release scripts.
#
# Field rules implemented (09): schema 1; versionName == tag and
# gradle.properties; versionCode == D63's formula with S = 95; minSdk 26;
# published ISO-8601 UTC; notes == changelogs/<versionCode>.txt and <= 500
# chars; releaseUrl == {repoUrl}/releases/tag/{tag}; every apks[]/desktop[]/jar
# url exactly {repoUrl}/releases/download/{tag}/{file} (no user-info, query,
# fragment, port, dot-segments or escapes — the parser's link rule); the APK
# set exactly arm64-v8a + x86_64 + armeabi-v7a; sha256 64 lowercase hex;
# unknown additive fields tolerated; whole manifest <= 64 KB.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." >/dev/null 2>&1 && pwd -P)"

MANIFEST="${1:-}"
ASSETS="${2:-}"
TAG="${3:-}"
[ -n "$MANIFEST" ] || { echo "usage: $0 <manifest> [assets-dir] [tag]" >&2; exit 2; }
[ -f "$MANIFEST" ] || { echo "check-update-json: $MANIFEST not found" >&2; exit 1; }

prop() { grep -E "^$1=" "$REPO_ROOT/gradle.properties" | tail -1 | cut -d= -f2-; }
VERSION_NAME="$(prop neutrodyne.versionName)"
VERSION_CODE="$(prop neutrodyne.versionCode)"
REPO_URL="$(prop neutrodyne.repoUrl)"
TAG="${TAG:-v$VERSION_NAME}"
CHANGELOG="$REPO_ROOT/changelogs/$VERSION_CODE.txt"

python3 - "$MANIFEST" "$ASSETS" "$TAG" "$VERSION_NAME" "$VERSION_CODE" "$REPO_URL" "$CHANGELOG" <<'PYEOF'
import datetime, hashlib, json, os, re, sys
from urllib.parse import unquote, urlsplit

manifest_path, assets, tag, vname, vcode, repo_url, changelog = sys.argv[1:8]
errors = []
def fail(msg): errors.append(msg)

raw = open(manifest_path, "rb").read()
if len(raw) > 64 * 1024:
    fail(f"manifest is {len(raw)} bytes (> 64 KB)")
doc = json.loads(raw.decode("utf-8"))

version = tag[1:]
if doc.get("schema") != 1:
    fail(f"schema is {doc.get('schema')!r}, expected 1")
if doc.get("versionName") != vname or doc.get("versionName") != version:
    fail(f"versionName {doc.get('versionName')!r} != tag/gradle.properties {version}")
m = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", vname)
want_code = int(m.group(1)) * 1000000 + int(m.group(2)) * 10000 + int(m.group(3)) * 100 + 95
if doc.get("versionCode") != int(vcode) or int(vcode) != want_code:
    fail(f"versionCode {doc.get('versionCode')!r} != gradle.properties {vcode} / formula {want_code}")
if not str(vcode).endswith("95"):
    fail(f"versionCode {vcode} does not end in S=95")
if doc.get("minSdk") != 26:
    fail(f"minSdk {doc.get('minSdk')!r} != 26")

pub = doc.get("published", "")
try:
    datetime.datetime.strptime(pub, "%Y-%m-%dT%H:%M:%SZ")
except ValueError:
    fail(f"published {pub!r} is not ISO-8601 UTC (YYYY-MM-DDTHH:MM:SSZ)")

notes = doc.get("notes")
want_notes = open(changelog, encoding="utf-8").read().strip()
if notes != want_notes:
    fail("notes do not equal changelogs/<versionCode>.txt")
elif len(notes) > 500:
    fail("notes longer than 500 chars")

# The link rule (09 Update manifest): https only, repo host, no user-info,
# query, fragment or port; the raw path split on '/' and each segment decoded
# once must equal the expected list exactly; a segment decoding to '.', '..' or
# containing '/' or '\\' rejects it.
repo = urlsplit(repo_url)
def check_url(url, segments, label):
    try:
        parts = urlsplit(url)
        port = parts.port
    except ValueError:
        return fail(f"{label}: unparsable url {url!r}")
    if parts.scheme != "https":
        fail(f"{label}: scheme of {url!r} is not https")
    if parts.username or parts.password:
        fail(f"{label}: user-info in {url!r}")
    if port not in (None, 443):
        fail(f"{label}: port in {url!r}")
    if parts.hostname != repo.hostname:
        fail(f"{label}: host of {url!r} is not {repo.hostname}")
    if parts.query or parts.fragment:
        fail(f"{label}: query/fragment in {url!r}")
    got = [unquote(s) for s in parts.path.split("/") if s != ""]
    for s in got:
        if s in (".", "..") or "/" in s or "\\" in s:
            fail(f"{label}: dot/segment escape in {url!r}")
            break
    else:
        if got != segments:
            fail(f"{label}: path {got} != {segments} for {url!r}")

owner_repo = [s for s in repo.path.split("/") if s]
check_url(doc.get("releaseUrl", ""), owner_repo + ["releases", "tag", tag], "releaseUrl")

def check_entry(e, label):
    if not isinstance(e.get("file"), str) or not e["file"]:
        fail(f"{label}: missing file")
    if not isinstance(e.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", e.get("sha256", "")):
        fail(f"{label}: sha256 is not 64 lower-case hex")
    if not isinstance(e.get("size"), int) or e.get("size", -1) <= 0:
        fail(f"{label}: bad size {e.get('size')!r}")
    check_url(e.get("url", ""), owner_repo + ["releases", "download", tag, e.get("file", "")], f"{label}.url")
    if e.get("url", "").rsplit("/", 1)[-1] != e.get("file", None):
        fail(f"{label}: url's last segment != file")
    if assets:
        path = os.path.join(assets, e.get("file", ""))
        if not os.path.isfile(path):
            fail(f"{label}: {e.get('file')} missing in {assets}")
        else:
            if os.path.getsize(path) != e.get("size"):
                fail(f"{label}: size {e['size']} != {os.path.getsize(path)} for {e['file']}")
            digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
            if digest != e.get("sha256"):
                fail(f"{label}: sha256 mismatch for {e['file']}")

apks = doc.get("apks")
if not isinstance(apks, list):
    fail("apks is missing")
else:
    abis = sorted(a.get("abi", "") for a in apks if isinstance(a, dict))
    if abis != ["arm64-v8a", "armeabi-v7a", "x86_64"]:
        fail(f"apks abi set {abis} != the three published ABIs")
    for a in apks:
        check_entry(a, f"apks[{a.get('abi')}]")

# Additive fields: desktop[] (M0b) and server (MS1) are validated when present,
# tolerated when absent (D78).
for i, d in enumerate(doc.get("desktop") or []):
    check_entry(d, f"desktop[{i}]")
    if d.get("os") == "windows" and d.get("minOs") != "10.0":
        fail(f"desktop[{i}]: windows minOs {d.get('minOs')!r} != 10.0")
    if d.get("os") == "macos" and d.get("minOs") != "13.0":
        fail(f"desktop[{i}]: macos minOs {d.get('minOs')!r} != 13.0")
    if d.get("os") == "linux" and "minOs" in d:
        fail(f"desktop[{i}]: minOs present on linux")
srv = doc.get("server")
if srv is not None:
    jar = srv.get("jar")
    if isinstance(jar, dict):
        check_entry(jar, "server.jar")
    else:
        fail("server.jar missing")
    img = srv.get("image", "")
    pat = rf"ghcr\.io/{re.escape(repo.path.strip('/').split('/')[0])}/neutrodyne-server@sha256:[0-9a-f]{{64}}"
    if not re.fullmatch(pat, img):
        fail(f"server.image {img!r} is not this repo's digest reference")
    if srv.get("minJava") != 21:
        fail(f"server.minJava {srv.get('minJava')!r} != 21")

if errors:
    for e in errors:
        print(f"check-update-json: FAIL  {e}", file=sys.stderr)
    sys.exit(1)
print(f"check-update-json: PASS ({os.path.basename(manifest_path)}, {len(raw)} bytes)")
PYEOF
