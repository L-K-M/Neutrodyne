#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# post-image-hook.test.sh — the Linux installer staging hook contract (11 AOT
# cache, Linux DEB, RPM and tar.gz). Neutrodyne-post-image.sh is jpackage's
# supported <app name>-post-image.sh hook: ScriptRunner runs it under `sh` with a
# cleared child environment plus $JpAppImageDir — jpackage's private staging copy
# — after the attribute-losing copyAppImage and before dpkg-deb/rpmbuild consume
# the tree. The hook restores the fixed epoch (318211200000 ms,
# 1980-02-01T00:00:00Z — AOT_IMAGE_TIMESTAMP_MS) the trained AOT cache recorded.
# Fixtures only: fabricated opt/neutrodyne trees, never a real image, package or
# install — native DEB/RPM verification is the Linux runners' job.
#
# Run: bash scripts/ci/tests/post-image-hook.test.sh
# Exit 0 = all cases behave; 1 = at least one regression.

set -uo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$HERE/../../.." >/dev/null 2>&1 && pwd -P)"
DEB_HOOK="$REPO_ROOT/desktopApp/packaging/deb/Neutrodyne-post-image.sh"
RPM_HOOK="$REPO_ROOT/desktopApp/packaging/rpm/Neutrodyne-post-image.sh"
EPOCH_NS=318211200000000000

FAILED=0
t_fail() { echo "not ok: $1" >&2; FAILED=$((FAILED + 1)); }
t_ok() { echo "ok: $1"; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

mtime_ns() { python3 -c 'import os,sys; print(os.lstat(sys.argv[1]).st_mtime_ns)' "$1"; }

# A fabricated staging tree in jpackage's Linux package layout: the installed
# tree staged below <install-dir>/<package> = opt/neutrodyne.
make_staging() { # <staging root>
    local s="$1"
    mkdir -p "$s/opt/neutrodyne/bin" "$s/opt/neutrodyne/lib/app" \
        "$s/opt/neutrodyne/lib/runtime"
    printf 'launcher\n' > "$s/opt/neutrodyne/bin/Neutrodyne"
    chmod 0755 "$s/opt/neutrodyne/bin/Neutrodyne"
    # shellcheck disable=SC2016 # the .cfg carries literal $APPDIR tokens
    printf '[Application]\napp.classpath=$APPDIR/tiny.jar\n\n[JavaOptions]\njava-options=-XX:AOTCache=$APPDIR/neutrodyne.aot\n' \
        > "$s/opt/neutrodyne/lib/app/Neutrodyne.cfg"
    printf 'aot\n' > "$s/opt/neutrodyne/lib/app/neutrodyne.aot"
    printf 'jar\n' > "$s/opt/neutrodyne/lib/app/tiny.jar"
    printf 'release\n' > "$s/opt/neutrodyne/lib/runtime/release"
}

# Run a hook the way ScriptRunner does: `sh` with a cleared environment.
run_hook() { # <hook file> <JpAppImageDir> [extra env name=value ...]
    local hook="$1" dir="$2"
    shift 2
    env -i JpAppImageDir="$dir" "$@" sh "$hook"
}

# --- 0. both resource dirs carry the hook, byte-identical ----------------------
if [ -f "$DEB_HOOK" ] && [ -f "$RPM_HOOK" ]; then
    t_ok "both Linux resource dirs carry Neutrodyne-post-image.sh"
else
    t_fail "a resource dir is missing Neutrodyne-post-image.sh"
fi
if cmp -s "$DEB_HOOK" "$RPM_HOOK"; then
    t_ok "the deb and rpm hook copies are byte-identical"
else
    t_fail "the deb and rpm hook copies differ"
fi
if sh -n "$DEB_HOOK" 2>/dev/null; then
    t_ok "the hook parses under sh"
else
    t_fail "the hook does not parse under sh"
fi

# --- 1. happy path under a cleared, non-UTC environment ------------------------
staging="$work/stage one/image"
make_staging "$staging"
if run_hook "$DEB_HOOK" "$staging" TZ=Pacific/Kiritimati; then
    t_ok "the hook exits 0 on a valid staging tree"
else
    t_fail "the hook failed on a valid staging tree"
fi
bad="$(python3 - "$staging" <<'PY'
import os, sys
root = sys.argv[1]
bad = []
for base, dirs, files in os.walk(root):
    for name in dirs + files:
        p = os.path.join(base, name)
        if os.path.islink(p):
            continue
        if os.lstat(p).st_mtime_ns != 318211200000000000:
            bad.append(p)
print("\n".join(bad))
PY
)"
if [ -z "$bad" ]; then
    t_ok "every non-symlink entry carries the fixed epoch under a non-UTC TZ"
else
    t_fail "entries left off the epoch: $(echo "$bad" | head -3 | tr '\n' ' ')"
fi

# The RPM resource dir's copy behaves the same on its own tree.
staging_rpm="$work/stage rpm/image"
make_staging "$staging_rpm"
if run_hook "$RPM_HOOK" "$staging_rpm" TZ=America/New_York \
    && [ "$(mtime_ns "$staging_rpm/opt/neutrodyne/lib/app/tiny.jar")" = "$EPOCH_NS" ]; then
    t_ok "the rpm copy restores the epoch too"
else
    t_fail "the rpm copy did not restore the epoch"
fi

# --- 2. content, modes and links survive ---------------------------------------
if [ "$(cat "$staging/opt/neutrodyne/lib/app/tiny.jar")" = "jar" ] \
    && [ -x "$staging/opt/neutrodyne/bin/Neutrodyne" ] \
    && [ ! -x "$staging/opt/neutrodyne/lib/app/tiny.jar" ]; then
    t_ok "content and modes are preserved"
else
    t_fail "content or modes changed"
fi

# A symlink inside the tree is skipped: neither the link nor the outside target
# it points at is touched.
outside="$work/outside file.jar"
printf 'outside\n' > "$outside"
outside_before="$(mtime_ns "$outside")"
ln -s "$outside" "$staging/opt/neutrodyne/lib/app/link.jar"
link_before="$(mtime_ns "$staging/opt/neutrodyne/lib/app/link.jar")"
run_hook "$DEB_HOOK" "$staging"
if [ "$(mtime_ns "$outside")" = "$outside_before" ] \
    && [ "$(mtime_ns "$staging/opt/neutrodyne/lib/app/link.jar")" = "$link_before" ]; then
    t_ok "a symlink and its outside target stay untouched"
else
    t_fail "the hook followed or rewrote a symlink"
fi

# --- 3. fail-closed contexts ----------------------------------------------------
expect_fail() { # <case name> <JpAppImageDir value> [extra env name=value ...]
    local name="$1" dir="$2"
    shift 2
    if run_hook "$DEB_HOOK" "$dir" "$@" >/dev/null 2>&1; then
        t_fail "the hook accepted an invalid context: $name"
    else
        t_ok "the hook fails closed: $name"
    fi
}

if env -i sh "$DEB_HOOK" >/dev/null 2>&1; then
    t_fail "the hook accepted an unset JpAppImageDir"
else
    t_ok "the hook fails closed: JpAppImageDir unset"
fi
mkdir -p "$work/notthere-parent"
expect_fail "JpAppImageDir missing" "$work/notthere-parent/image"
mkdir -p "$work/empty/image"
expect_fail "no staged install tree" "$work/empty/image"

no_aot="$work/no-aot/image"
make_staging "$no_aot"
rm "$no_aot/opt/neutrodyne/lib/app/neutrodyne.aot"
expect_fail "no trained archive" "$no_aot"

no_flag="$work/no-flag/image"
make_staging "$no_flag"
printf '[JavaOptions]\njava-options=-Djpackage.app-version=1.0\n' \
    > "$no_flag/opt/neutrodyne/lib/app/Neutrodyne.cfg"
expect_fail ".cfg without the AOT read flag" "$no_flag"

no_jars="$work/no-jars/image"
make_staging "$no_jars"
rm "$no_jars/opt/neutrodyne/lib/app/tiny.jar"
expect_fail "no classpath JARs" "$no_jars"

ln -sfn "$staging" "$work/image-link"
expect_fail "JpAppImageDir is a symlink" "$work/image-link"

# --- 4. the original trained image stays untouched ------------------------------
# A second tree stands in for the trained app image the staging copy was made
# from: the hook must write below $JpAppImageDir only.
original="$work/original image"
make_staging "$original"
state() { python3 - "$1" <<'PY'
import os, sys
root = sys.argv[1]
for base, dirs, files in os.walk(root):
    for name in sorted(dirs + files):
        p = os.path.join(base, name)
        if not os.path.islink(p):
            print(os.path.relpath(p, root), os.lstat(p).st_mtime_ns)
PY
}
snapshot="$(state "$original")"
run_hook "$DEB_HOOK" "$staging" >/dev/null
if [ "$snapshot" = "$(state "$original")" ]; then
    t_ok "the original image's paths and timestamps are untouched"
else
    t_fail "the hook touched the original image"
fi

# --- 5. the DMG and MSI hooks --------------------------------------------------
# The DMG hook is the same supported point with the macOS staging layout: on
# macOS JpAppImageDir is the staged .app bundle itself, not the staging root.
DMG_HOOK="$REPO_ROOT/desktopApp/packaging/dmg/Neutrodyne-post-image.sh"
MSI_HOOK="$REPO_ROOT/desktopApp/packaging/msi/Neutrodyne-post-image.wsf"

if [ -f "$DMG_HOOK" ]; then
    t_ok "the DMG resource dir carries Neutrodyne-post-image.sh"
else
    t_fail "the DMG resource dir is missing Neutrodyne-post-image.sh"
fi
if [ -f "$MSI_HOOK" ]; then
    t_ok "the MSI resource dir carries Neutrodyne-post-image.wsf"
else
    t_fail "the MSI resource dir is missing Neutrodyne-post-image.wsf"
fi
if sh -n "$DMG_HOOK" 2>/dev/null; then
    t_ok "the DMG hook parses under sh"
else
    t_fail "the DMG hook does not parse under sh"
fi

make_dmg_staging() { # <staged .app bundle> — JpAppImageDir on macOS
    local s="$1"
    mkdir -p "$s/Contents/MacOS" "$s/Contents/app" "$s/Contents/runtime"
    printf 'launcher\n' > "$s/Contents/MacOS/Neutrodyne"
    chmod 0755 "$s/Contents/MacOS/Neutrodyne"
    # shellcheck disable=SC2016 # the .cfg carries literal $APPDIR tokens
    printf '[Application]\napp.classpath=$APPDIR/tiny.jar\n\n[JavaOptions]\njava-options=-XX:AOTCache=$APPDIR/neutrodyne.aot\n' \
        > "$s/Contents/app/Neutrodyne.cfg"
    printf 'aot\n' > "$s/Contents/app/neutrodyne.aot"
    printf 'jar\n' > "$s/Contents/app/tiny.jar"
    printf 'release\n' > "$s/Contents/runtime/release"
}

dmg_stage="$work/dmg stage/Neutrodyne.app"
make_dmg_staging "$dmg_stage"
if run_hook "$DMG_HOOK" "$dmg_stage" TZ=Pacific/Kiritimati; then
    t_ok "the DMG hook exits 0 on a valid .app staging tree"
else
    t_fail "the DMG hook failed on a valid .app staging tree"
fi
bad="$(python3 - "$dmg_stage" <<'PY'
import os, sys
root = sys.argv[1]
bad = []
for base, dirs, files in os.walk(root):
    for name in dirs + files:
        p = os.path.join(base, name)
        if not os.path.islink(p) and os.lstat(p).st_mtime_ns != 318211200000000000:
            bad.append(p)
print("\n".join(bad))
PY
)"
if [ -z "$bad" ]; then
    t_ok "every non-symlink DMG staged entry carries the fixed epoch under a non-UTC TZ"
else
    t_fail "DMG entries left off the epoch: $(echo "$bad" | head -3 | tr '\n' ' ')"
fi

# Fail-closed contexts for the DMG hook.
dmg_expect_fail() { # <case name> <JpAppImageDir value>
    local name="$1" dir="$2"
    if run_hook "$DMG_HOOK" "$dir" >/dev/null 2>&1; then
        t_fail "the DMG hook accepted an invalid context: $name"
    else
        t_ok "the DMG hook fails closed: $name"
    fi
}
if env -i sh "$DMG_HOOK" >/dev/null 2>&1; then
    t_fail "the DMG hook accepted an unset JpAppImageDir"
else
    t_ok "the DMG hook fails closed: JpAppImageDir unset"
fi
mkdir -p "$work/dmg-empty"
dmg_expect_fail "no staged app bundle" "$work/dmg-empty"
dmg_no_aot="$work/dmg-no-aot/Neutrodyne.app"
make_dmg_staging "$dmg_no_aot"
rm "$dmg_no_aot/Contents/app/neutrodyne.aot"
dmg_expect_fail "no trained archive" "$dmg_no_aot"
dmg_no_flag="$work/dmg-no-flag/Neutrodyne.app"
make_dmg_staging "$dmg_no_flag"
printf '[JavaOptions]\njava-options=-Djpackage.app-version=1.0\n' \
    > "$dmg_no_flag/Contents/app/Neutrodyne.cfg"
dmg_expect_fail ".cfg without the AOT read flag" "$dmg_no_flag"
dmg_no_jars="$work/dmg-no-jars/Neutrodyne.app"
make_dmg_staging "$dmg_no_jars"
rm "$dmg_no_jars/Contents/app/tiny.jar"
dmg_expect_fail "no classpath JARs" "$dmg_no_jars"

# The MSI hook cannot run here (cscript/WSF is Windows-only — the native
# fixture in post-image-hook.test.ps1 executes it on the Windows runners); pin
# its contract as data: well-formed JScript WSF, the JDK 25 direct-root staging
# layout (JpAppImageDir IS the app image, with the launcher at its root and
# app/ below — not the Neutrodyne-App install dir the --install-dir value
# names), the read flag, the epoch, and the path read from the environment
# rather than interpolated into executable command text.
if python3 - "$MSI_HOOK" <<'PY'
import sys, xml.etree.ElementTree as ET
path = sys.argv[1]
root = ET.parse(path).getroot()
assert root.tag == "package", root.tag
jobs = root.findall("job")
assert len(jobs) == 1, len(jobs)
script = jobs[0].find("script")
assert script is not None and script.get("language") == "JScript"
text = script.text or ""
for token in (
    "318211200000",
    "var app = dir;",
    'app + "\\\\app\\\\Neutrodyne.cfg"',
    'app + "\\\\app\\\\neutrodyne.aot"',
    'app + "\\\\Neutrodyne.exe"',
    'java-options=-XX:AOTCache=$APPDIR/neutrodyne.aot',
    'Environment("Process").Item("JpAppImageDir")',
    "ModifyDate",
    "DateLastModified",
):
    assert token in text, token
# JDK 25's WinPackagingPipeline stages the app image at JpAppImageDir itself;
# the hook must not nest the checks under the installed Neutrodyne-App dir.
assert 'dir + "\\\\Neutrodyne-App"' not in text, \
    "the staging tree has no Neutrodyne-App level (that is the install dir)"
# Supported APIs only: the environment comes from WshShell.Environment (the
# WScript object has no Environment collection) and file timestamps go through
# the writable FolderItem.ModifyDate, never the read-only FSO property.
assert 'WScript.Environment(' not in text, "WScript has no Environment property"
assert "DateLastModified = " not in text, "FSO DateLastModified is read-only"
# The path is data: it must not be concatenated into a command string.
assert ".Exec(" not in text and ".Run(" not in text, \
    "the WSF must not spawn a command with the path"
PY
then
    t_ok "the MSI hook is a well-formed JScript WSF with the layout/read-flag/epoch contract"
else
    t_fail "the MSI hook does not carry the contract"
fi

echo
if [ "$FAILED" -gt 0 ]; then
    echo "post-image-hook.test: $FAILED case(s) failing" >&2
    exit 1
fi
echo "post-image-hook.test: all cases pass"
