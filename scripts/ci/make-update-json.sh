#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# make-update-json.sh <tag> <assets-dir> [out]
#
# Writes neutrodyne-update.json (schema 1, 09 Update manifest) for release.yml's
# `publish` job: version fields from the tag and gradle.properties, notes from
# changelogs/<versionCode>.txt, and one apks[] entry per renamed APK in
# <assets-dir> with its size and SHA-256. Default out is
# <assets-dir>/neutrodyne-update.json.
#
# `desktop[]` (M0b) and `server` (MS1) are additive schema-1 fields (D78): each
# is emitted only when its asset file exists in <assets-dir> — M0a therefore
# writes apks[] only. A server JAR additionally needs the attested image digest
# in NEUTRODYNE_SERVER_IMAGE (decided by release.yml's server-image jobs).

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." >/dev/null 2>&1 && pwd -P)"

TAG="${1:-}"
ASSETS="${2:-}"
OUT="${3:-}"
[ -n "$TAG" ] && [ -n "$ASSETS" ] || { echo "usage: $0 <tag> <assets-dir> [out]" >&2; exit 2; }
[ -d "$ASSETS" ] || { echo "make-update-json: $ASSETS is not a directory" >&2; exit 1; }
OUT="${OUT:-$ASSETS/neutrodyne-update.json}"

prop() { grep -E "^$1=" "$REPO_ROOT/gradle.properties" | tail -1 | cut -d= -f2-; }
VERSION_NAME="$(prop neutrodyne.versionName)"
VERSION_CODE="$(prop neutrodyne.versionCode)"
REPO_URL="$(prop neutrodyne.repoUrl)"
MIN_SDK=26 # 01 Toolchain and versions: the manifest repeats it (09 Update manifest)

[ "$TAG" = "v$VERSION_NAME" ] || { echo "make-update-json: tag $TAG != v$VERSION_NAME" >&2; exit 1; }
CHANGELOG="$REPO_ROOT/changelogs/$VERSION_CODE.txt"
[ -f "$CHANGELOG" ] || { echo "make-update-json: $CHANGELOG missing" >&2; exit 1; }

python3 - "$OUT" "$ASSETS" "$TAG" "$VERSION_NAME" "$VERSION_CODE" "$MIN_SDK" "$REPO_URL" "$CHANGELOG" <<'PYEOF'
import datetime, hashlib, json, os, sys

out, assets, tag, vname, vcode, min_sdk, repo_url, changelog = sys.argv[1:9]
version = tag[1:]
notes = open(changelog, encoding="utf-8").read().strip()
if not notes or len(notes) > 500:
    sys.exit("make-update-json: changelog empty or > 500 chars")

# published = now, UTC ISO-8601 (the tag's own time would work as well; the
# manifest is rewritten per release attempt and 09 only pins the format).
published = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

def asset(fname):
    """One file/url/size/sha256 entry; fails when the asset is missing."""
    path = os.path.join(assets, fname)
    if not os.path.isfile(path):
        sys.exit(f"make-update-json: expected asset {fname} missing in {assets}")
    return {
        "file": fname,
        "url": f"{repo_url}/releases/download/{tag}/{fname}",
        "size": os.path.getsize(path),
        "sha256": hashlib.sha256(open(path, "rb").read()).hexdigest(),
    }

apks = []
for abi in ("arm64-v8a", "x86_64", "armeabi-v7a"):
    fname = f"neutrodyne-{version}-{abi}.apk"
    e = asset(fname)
    e["abi"] = abi
    apks.append({k: e[k] for k in ("abi", "file", "url", "size", "sha256")})

manifest = {
    "schema": 1,
    "versionName": vname,
    "versionCode": int(vcode),
    "minSdk": int(min_sdk),
    "published": published,
    "releaseUrl": f"{repo_url}/releases/tag/{tag}",
    "notes": notes,
    "apks": apks,
}

# Additive fields: desktop[] (M0b) and server (MS1) join when their release
# jobs produce the assets; absent fields stay absent (09 Update manifest).
desktop = []
mac_1_0 = int(vname.split(".")[0]) >= 1
mac_kind = "dmg" if mac_1_0 else "mac-zip"              # D63/PO-39
mac_ext = "dmg" if mac_1_0 else "zip"
candidates = [
    ("windows", "x64",   "msi",    f"neutrodyne-{version}-windows-x64.msi",      "10.0"),
    ("windows", "x64",   "zip",    f"neutrodyne-{version}-windows-x64.zip",      "10.0"),
    ("macos",   "arm64", mac_kind, f"neutrodyne-{version}-macos-arm64.{mac_ext}", "13.0"),
]
for arch in ("x64", "arm64"):
    for kind in ("deb", "rpm", "tar.gz"):
        candidates.append(("linux", arch, kind, f"neutrodyne-{version}-linux-{arch}.{kind}", None))
for os_, arch, kind, fname, min_os in candidates:
    if not os.path.isfile(os.path.join(assets, fname)):
        continue
    e = asset(fname)
    e.update(os=os_, arch=arch, kind=kind)
    if min_os:
        e["minOs"] = min_os
    desktop.append({k: e[k] for k in ("os", "arch", "kind", "file", "url", "size", "sha256", "minOs") if k in e})
if desktop:
    manifest["desktop"] = desktop

jar_name = f"neutrodyne-server-{version}.jar"
if os.path.isfile(os.path.join(assets, jar_name)):
    # the image digest is decided by server-image-manifest, not by this script
    image = os.environ.get("NEUTRODYNE_SERVER_IMAGE", "")
    if not image:
        sys.exit("make-update-json: server JAR present but NEUTRODYNE_SERVER_IMAGE unset")
    manifest["server"] = {"jar": asset(jar_name), "image": image, "minJava": 21}
with open(out, "w", encoding="utf-8") as f:
    json.dump(manifest, f, indent=2, ensure_ascii=False)
    f.write("\n")
print(f"make-update-json: wrote {out}")
PYEOF
