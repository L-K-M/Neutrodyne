#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# apply-screenshots.sh <run-id>
#
# Applies the golden PNGs a record-screenshots.yml run uploaded (09 Recording
# policy) to this checkout. The run must be a successful workflow_dispatch of
# that workflow in this repository; the artifact's manifest sourceSha — the
# checked-out source the record ran against, not the workflow run's headSha —
# must equal HEAD, and the checkout must be clean except for changes inside
# the golden PNG trees. Only regular .png files below an existing module's
# screenshots/{android,desktop}/ tree are unpacked, within the 09 budgets
# (android <= 800 files / 30 MiB, desktop <= 400 / 15 MiB). Every check runs
# before the first write; nothing is staged, committed or pushed.

set -euo pipefail

RUN_ID="${1:-}"
[ -n "$RUN_ID" ] || { echo "usage: $0 <run-id>" >&2; exit 2; }
case "$RUN_ID" in
    *[!0-9]*) echo "apply-screenshots: run-id must be numeric" >&2; exit 2 ;;
esac

WORK="$(mktemp -d "${TMPDIR:-/tmp}/apply-screenshots.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# Run identity is checked before its artifact is trusted: only a successful
# dispatch of the record workflow in this repository counts.
gh run view "$RUN_ID" \
    --json conclusion,event,headBranch,headSha,status,url,workflowName \
    > "$WORK/run.json"
gh repo view --json nameWithOwner > "$WORK/repo.json"

# Gate the download: a failed or foreign-workflow run's artifacts are never
# fetched. The validator below re-checks these fields with the manifest.
python3 - "$WORK/run.json" <<'PYEOF'
import json, sys
run = json.load(open(sys.argv[1], encoding="utf-8"))
ok = (run.get("workflowName") == "record-screenshots"
      and run.get("event") == "workflow_dispatch"
      and run.get("status") == "completed"
      and run.get("conclusion") == "success")
if not ok:
    sys.exit("apply-screenshots: run is not a successful "
             "record-screenshots.yml dispatch")
PYEOF

mkdir -p "$WORK/dl"
gh run download "$RUN_ID" --pattern 'screenshots-*' --dir "$WORK/dl"

python3 - "$WORK" <<'PYEOF'
import json, os, re, stat, subprocess, sys, zipfile

work = sys.argv[1]
errors = []

def fail(msg):
    errors.append(msg)

def sh(*args):
    return subprocess.run(args, capture_output=True, text=True, check=True).stdout.strip()

try:
    root = sh("git", "rev-parse", "--show-toplevel")
    head = sh("git", "rev-parse", "HEAD")
except subprocess.CalledProcessError:
    sys.exit("apply-screenshots: not inside a git checkout")

run = json.load(open(os.path.join(work, "run.json"), encoding="utf-8"))
repo = json.load(open(os.path.join(work, "repo.json"), encoding="utf-8"))
slug = repo.get("nameWithOwner", "")

if run.get("workflowName") != "record-screenshots":
    fail(f"run is workflow {run.get('workflowName')!r}, expected 'record-screenshots'")
if run.get("event") != "workflow_dispatch":
    fail(f"run event is {run.get('event')!r}, expected 'workflow_dispatch'")
if run.get("status") != "completed" or run.get("conclusion") != "success":
    fail(f"run did not succeed (status={run.get('status')!r} "
         f"conclusion={run.get('conclusion')!r})")

ZIPNAME = re.compile(r"screenshots-([0-9a-f]{40})\.zip\Z")
zips = []
for dirpath, _dirs, files in os.walk(os.path.join(work, "dl")):
    for fn in files:
        if ZIPNAME.fullmatch(fn):
            zips.append(os.path.join(dirpath, fn))
        else:
            fail(f"unexpected file in artifact download: {fn!r}")
name_sha = zpath = None
if len(zips) > 1:
    fail(f"multiple screenshots-*.zip files downloaded ({len(zips)})")
elif zips:
    zpath = zips[0]
    name_sha = ZIPNAME.fullmatch(os.path.basename(zpath)).group(1)
else:
    fail("no screenshots-<sha>.zip in the downloaded artifacts")

SETNAMES = ("android", "desktop")
# 09 Tiers budgets; the recorder enforces them too, the apply side re-checks
# because it cannot see the record log.
LIMITS = {"android": (800, 30 * 1024 * 1024), "desktop": (400, 15 * 1024 * 1024)}
MANIFEST_MAX = 64 * 1024
PNG_SIG = b"\x89PNG\r\n\x1a\n"
# <module path>/screenshots/<set>/<name>.png, flat, at least one module segment.
GOLDEN_PATH = re.compile(r"^(?:[^/]+/)+screenshots/(?:android|desktop)/[^/]+\.png\Z")

manifest = None
planned = []  # (member name, bytes) — buffered so nothing writes before all checks
if zpath:
    try:
        zf = zipfile.ZipFile(zpath)
    except zipfile.BadZipFile:
        zf = None
        fail(f"{os.path.basename(zpath)} is not a readable zip")
    if zf is not None:
        with zf:
            if zf.testzip() is not None:
                fail("zip integrity check (CRC) failed")
            else:
                seen = set()
                counts = {s: 0 for s in SETNAMES}
                sizes = {s: 0 for s in SETNAMES}
                for info in zf.infolist():
                    name = info.filename
                    if name in seen:
                        fail(f"duplicate member {name!r}")
                        continue
                    seen.add(name)
                    mode = (info.external_attr >> 16) & 0o170000
                    if info.is_dir() or mode not in (0, stat.S_IFREG):
                        fail(f"{name!r}: not a regular file")
                        continue
                    parts = name.split("/")
                    if (name.startswith("/") or "\\" in name or "\x00" in name
                            or any(p in ("", ".", "..") for p in parts)):
                        fail(f"{name!r}: unsafe member path")
                        continue
                    if name == "manifest.json":
                        if info.file_size > MANIFEST_MAX:
                            fail(f"manifest.json is {info.file_size} bytes (> {MANIFEST_MAX})")
                            continue
                        try:
                            manifest = json.loads(zf.read(info).decode("utf-8"))
                        except (ValueError, UnicodeDecodeError) as e:
                            fail(f"manifest.json is not valid JSON: {e}")
                        continue
                    if (len(parts) < 4 or parts[-3] != "screenshots"
                            or parts[-2] not in SETNAMES
                            or not parts[-1].endswith(".png") or parts[-1] == ".png"):
                        fail(f"{name!r}: outside the module screenshots/android|desktop layout")
                        continue
                    data = zf.read(info)
                    if not data.startswith(PNG_SIG):
                        fail(f"{name!r}: no PNG signature")
                        continue
                    setname = parts[-2]
                    counts[setname] += 1
                    sizes[setname] += len(data)
                    planned.append((name, data))
                if manifest is None:
                    fail("manifest.json is missing")
                if not planned and not errors:
                    fail("artifact carries no golden PNGs")
                for s in SETNAMES:
                    max_count, max_bytes = LIMITS[s]
                    if counts[s] > max_count or sizes[s] > max_bytes:
                        fail(f"{s} set over the 09 budget: {counts[s]} files / "
                             f"{sizes[s]} bytes (limit {max_count} / {max_bytes})")

if isinstance(manifest, dict):
    msha = manifest.get("sourceSha")
    if not isinstance(msha, str) or not re.fullmatch(r"[0-9a-f]{40}", msha):
        fail(f"manifest sourceSha {msha!r} is not a 40-hex SHA")
    else:
        if name_sha and msha != name_sha:
            fail(f"zip name sha {name_sha} != manifest sourceSha {msha}")
        if msha != head:
            fail(f"manifest sourceSha {msha} != checkout HEAD {head}")
    if manifest.get("repository") != slug:
        fail(f"manifest repository {manifest.get('repository')!r} != this repo {slug!r}")
    if manifest.get("tier") not in ("pr", "full"):
        fail(f"manifest tier {manifest.get('tier')!r} not in (pr, full)")
elif manifest is not None:
    fail("manifest.json is not an object")

# The checkout must be clean apart from golden-PNG changes: a dirty Kotlin or
# source tree means the references would not match the code under review.
porcelain = subprocess.run(
    ["git", "status", "--porcelain=v1", "-z", "--untracked-files=all"],
    cwd=root, capture_output=True, check=True).stdout.decode("utf-8")
entries = porcelain.split("\0")
offenders = []
i = 0
while i < len(entries):
    entry = entries[i]
    i += 1
    if not entry:
        continue
    status, path = entry[:2], entry[3:]
    paths = [path]
    if "R" in status or "C" in status:
        paths.append(entries[i])
        i += 1
    for p in paths:
        if not GOLDEN_PATH.match(p):
            offenders.append(f"{status.strip() or '??'} {p}")
if offenders:
    fail("checkout has changes outside the golden PNG trees: "
         + ", ".join(offenders[:8])
         + (" ..." if len(offenders) > 8 else ""))

# Destination safety: the golden must sit below an existing module's
# screenshots/<set>/ tree and no path component may escape through a symlink.
for name, _data in planned:
    parts = name.split("/")
    cur, ok = root, True
    for comp in parts[:-1]:
        cur = os.path.join(cur, comp)
        if os.path.islink(cur):
            fail(f"{name!r}: path component {cur} is a symlink")
            ok = False
            break
        if os.path.lexists(cur) and not os.path.isdir(cur):
            fail(f"{name!r}: path component {cur} is not a directory")
            ok = False
            break
    if not ok:
        continue
    dest = os.path.join(cur, parts[-1])
    if os.path.lexists(dest) and (os.path.islink(dest) or not os.path.isfile(dest)):
        fail(f"{name!r}: destination {dest} is not a regular file")
        continue
    moddir = os.path.join(root, *parts[:-3])
    if not os.path.isfile(os.path.join(moddir, "build.gradle.kts")):
        fail(f"{name!r}: {os.path.relpath(moddir, root)} has no build.gradle.kts")

if errors:
    for e in errors:
        print(f"apply-screenshots: FAIL  {e}", file=sys.stderr)
    sys.exit(1)

written = replaced = 0
member_names = set()
for name, data in planned:
    dest = os.path.join(root, name)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    if os.path.exists(dest):
        replaced += 1
    with open(dest, "wb") as f:
        f.write(data)
    member_names.add(name)
    written += 1

# Goldens left over from renamed or deleted tests stay in place — the author
# decides whether they are still wanted, they are not this artifact's files.
stale = []
for moddir in {os.path.join(root, *n.split("/")[:-3]) for n in member_names}:
    for setname in SETNAMES:
        d = os.path.join(moddir, "screenshots", setname)
        if not os.path.isdir(d):
            continue
        for fn in sorted(os.listdir(d)):
            rel = os.path.relpath(os.path.join(d, fn), root)
            if fn.endswith(".png") and rel not in member_names:
                stale.append(rel)

print(f"apply-screenshots: {written} PNG(s) applied "
      f"({replaced} replaced) from {run.get('url', 'the run')}")
print(f"  source {manifest.get('sourceSha')}  tier {manifest.get('tier')}  "
      f"requested ref {manifest.get('requestedRef')}")
if stale:
    print("  not in the artifact, left in place:")
    for p in stale:
        print(f"    {p}")
print("review `git status` and commit the references")
PYEOF
