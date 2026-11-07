#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# check-desktop-image.test.sh — boundary cases for scripts/ci/check-desktop-image.sh
# (11 Image scan rules; MD3 AC5). The scan resolves runtime.lock, the python lock and
# the runtime-classpath manifest relative to its own path, so each run copies it into a
# fixture repo root — no repository state is touched.
#
# The good fixture is a minimal macOS .app image: the bundled interpreter, CA bundle,
# standard library, yt-dlp archive, shim, keys, the jlink runtime layout and one JAR on
# the Licensee-checked classpath manifest, signed ad hoc. Each bad fixture breaks one
# rule the MD3 AC5 slice owns: a forbidden component in the tree or the yt-dlp archive,
# a missing interpreter/CA/stdlib, a stdlib minor that disagrees with the lock, an
# unsigned bundle, an unmanifested JAR.
#
# Run: bash scripts/ci/tests/check-desktop-image.test.sh
# Exit 0 = all cases behave; 1 = at least one regression.

set -uo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$HERE/../../.." >/dev/null 2>&1 && pwd -P)"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAILED=0
t_fail() { echo "not ok: $1" >&2; FAILED=$((FAILED + 1)); }
t_ok() { echo "ok: $1"; }

# --- fixture plumbing -----------------------------------------------------------

FIX_ROOT="$WORK/repo"
mkdir -p "$FIX_ROOT/scripts/ci" \
    "$FIX_ROOT/desktopApp/build/desktop-packaging" \
    "$FIX_ROOT/youtube/ytdlp-desktop"
cp "${SCRIPT_UNDER_TEST:-$REPO_ROOT/scripts/ci/check-desktop-image.sh}" \
    "$FIX_ROOT/scripts/ci/"
cp "$REPO_ROOT/desktopApp/runtime.lock" "$FIX_ROOT/desktopApp/"
cp "$REPO_ROOT/youtube/ytdlp-desktop/python-components.lock" \
    "$FIX_ROOT/youtube/ytdlp-desktop/"
SCAN="$FIX_ROOT/scripts/ci/check-desktop-image.sh"
MANIFEST="$FIX_ROOT/desktopApp/build/desktop-packaging/runtime-classpath.txt"

# The one image JAR the manifest names; its canonical content hash is what the scan
# compares against, so the fixture writes a genuine tiny zip.
JAR_NAME="fixture-2f44c7a1b6d94e0fa3b2c8d1e5f60718.jar"
mkdir -p "$WORK/jarbuild/META-INF"
printf 'fixture\n' > "$WORK/jarbuild/fixture.txt"
(cd "$WORK/jarbuild" && python3 -c '
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as z:
    z.writestr("META-INF/", "")
    z.writestr("fixture.txt", "fixture\n")
' "$WORK/$JAR_NAME")
python3 - "$WORK/$JAR_NAME" "$MANIFEST" "$JAR_NAME" <<'PY'
import hashlib, sys, zipfile
jar, manifest, name = sys.argv[1:4]
with zipfile.ZipFile(jar) as z:
    outer = hashlib.sha256()
    for n in sorted(z.namelist()):
        outer.update(n.encode() + b"\0")
        outer.update(hashlib.sha256(z.read(n)).hexdigest().encode() + b"\n")
with open(manifest, "w") as f:
    f.write("%s  %s\n" % (outer.hexdigest(), name))
PY

# build_app <dest> — the good image. A second caller (bad-fixture construction) copies
# it and breaks one rule.
build_app() {
    local dest="$1"
    mkdir -p "$dest/Contents/MacOS" "$dest/Contents/Resources" \
        "$dest/Contents/runtime/Contents/Home/legal" \
        "$dest/Contents/app/resources/engine/python/bin" \
        "$dest/Contents/app/resources/engine/python/lib/python3.14" \
        "$dest/Contents/app/resources/engine/ytdlp" \
        "$dest/Contents/app/resources/engine/shim/neutrodyne_ytx" \
        "$dest/Contents/app/resources/engine/keys"
    printf 'fixture launcher\n' > "$dest/Contents/MacOS/Neutrodyne"
    chmod +x "$dest/Contents/MacOS/Neutrodyne"
    printf 'fixture icon\n' > "$dest/Contents/Resources/Neutrodyne.icns"
    printf 'fixture plist\n' > "$dest/Contents/Info.plist"
    printf 'JAVA_VERSION="25.0.4.1"\nMODULES="java.base"\n' \
        > "$dest/Contents/runtime/Contents/Home/release"
    printf 'fixture license\n' > "$dest/Contents/runtime/Contents/Home/legal/LICENSE"
    printf '#!/bin/sh\nprint("3.14.8")\n' > "$dest/Contents/app/resources/engine/python/bin/python3"
    chmod +x "$dest/Contents/app/resources/engine/python/bin/python3"
    printf 'import os\n' > "$dest/Contents/app/resources/engine/python/lib/python3.14/os.py"
    printf 'fixture CA bundle\n' > "$dest/Contents/app/resources/engine/cacert.pem"
    printf '#!/usr/bin/env python3\n' \
        > "$dest/Contents/app/resources/engine/shim/neutrodyne_ytx/__init__.py"
    printf 'fixture key\n' \
        > "$dest/Contents/app/resources/engine/keys/engine-manifest-ed25519.pub"
    cp "$WORK/$JAR_NAME" "$dest/Contents/app/$JAR_NAME"
    # A genuine yt-dlp-shaped zip: __main__.py + yt_dlp/ + yt_dlp_ejs/.
    python3 - "$dest/Contents/app/resources/engine/ytdlp/yt-dlp" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as z:
    z.writestr("__main__", "")
    z.writestr("__main__.py", "from yt_dlp import main\n")
    z.writestr("yt_dlp/__init__.py", "")
    z.writestr("yt_dlp_ejs/__init__.py", "")
PY
}

GOOD="$WORK/images/good/Neutrodyne.app"
build_app "$GOOD"
codesign --force --deep -s - "$GOOD" 2>/dev/null

# A bad fixture: copy the good image, break one rule.
break_app() { # <name> <shell snippet over $app>
    local name="$1"
    local snippet="$2"
    local app="$WORK/images/$name/Neutrodyne.app"
    rm -rf "$WORK/images/$name"
    mkdir -p "$WORK/images/$name"
    cp -R "$GOOD" "$app"
    codesign --remove-signature "$app" 2>/dev/null || true
    (eval "$snippet")
    codesign --force --deep -s - "$app" 2>/dev/null
    echo "$app"
}

# --- cases ----------------------------------------------------------------------

# 1. The good image passes every rule.
if bash "$SCAN" "$GOOD" > "$WORK/out" 2>&1; then
    t_ok "the good image passes"
else
    t_fail "the good image passes"
    sed 's/^/    /' "$WORK/out" >&2
fi

# 2. Each forbidden component in the tree is rejected.
for component in _dbm mutagen ensurepip qjs deno node bun; do
    app="$(break_app "bad-$component" "mkdir -p \"\$app/Contents/app/resources/engine/python/lib/python3.14/$component\"")"
    if ! bash "$SCAN" "$app" > "$WORK/out" 2>&1 &&
        grep -q "forbidden file or directory" "$WORK/out"; then
        t_ok "a bundled $component is rejected"
    else
        t_fail "a bundled $component is rejected"
        sed 's/^/    /' "$WORK/out" >&2
    fi
done

# 3. A forbidden component inside the yt-dlp archive is rejected.
app="$(break_app "bad-zip" "python3 - \"\$app/Contents/app/resources/engine/ytdlp/yt-dlp\" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'w') as z:
    z.writestr('__main__.py', '')
    z.writestr('mutagen/__init__.py', '')
PY")"
if ! bash "$SCAN" "$app" > "$WORK/out" 2>&1 &&
    grep -q "forbidden entry in the bundled yt-dlp archive" "$WORK/out"; then
    t_ok "a forbidden yt-dlp archive entry is rejected"
else
    t_fail "a forbidden yt-dlp archive entry is rejected"
    sed 's/^/    /' "$WORK/out" >&2
fi

# 4. A missing interpreter is rejected.
app="$(break_app "bad-interpreter" "rm -f \"\$app/Contents/app/resources/engine/python/bin/python3\"")"
if ! bash "$SCAN" "$app" > "$WORK/out" 2>&1 &&
    grep -q "no Python interpreter" "$WORK/out"; then
    t_ok "a missing interpreter is rejected"
else
    t_fail "a missing interpreter is rejected"
    sed 's/^/    /' "$WORK/out" >&2
fi

# 5. A non-executable interpreter is rejected.
app="$(break_app "bad-not-executable" "chmod -x \"\$app/Contents/app/resources/engine/python/bin/python3\"")"
if ! bash "$SCAN" "$app" > "$WORK/out" 2>&1 &&
    grep -q "not executable" "$WORK/out"; then
    t_ok "a non-executable interpreter is rejected"
else
    t_fail "a non-executable interpreter is rejected"
    sed 's/^/    /' "$WORK/out" >&2
fi

# 6. A missing CA bundle is rejected.
app="$(break_app "bad-cacert" "rm -f \"\$app/Contents/app/resources/engine/cacert.pem\"")"
if ! bash "$SCAN" "$app" > "$WORK/out" 2>&1 &&
    grep -q "CA bundle is missing" "$WORK/out"; then
    t_ok "a missing CA bundle is rejected"
else
    t_fail "a missing CA bundle is rejected"
    sed 's/^/    /' "$WORK/out" >&2
fi

# 7. A missing standard library is rejected.
app="$(break_app "bad-stdlib" "rm -rf \"\$app/Contents/app/resources/engine/python/lib/python3.14\"")"
if ! bash "$SCAN" "$app" > "$WORK/out" 2>&1 &&
    grep -q "no Python standard library" "$WORK/out"; then
    t_ok "a missing standard library is rejected"
else
    t_fail "a missing standard library is rejected"
    sed 's/^/    /' "$WORK/out" >&2
fi

# 8. A stdlib minor that disagrees with the lock is rejected.
app="$(break_app "bad-version" "mv \"\$app/Contents/app/resources/engine/python/lib/python3.14\" \"\$app/Contents/app/resources/engine/python/lib/python3.13\"")"
if ! bash "$SCAN" "$app" > "$WORK/out" 2>&1 &&
    grep -q "stdlib minor" "$WORK/out"; then
    t_ok "a stdlib minor that disagrees with the lock is rejected"
else
    t_fail "a stdlib minor that disagrees with the lock is rejected"
    sed 's/^/    /' "$WORK/out" >&2
fi

# 9. An unsigned bundle is rejected on macOS.
app="$WORK/images/bad-unsigned/Neutrodyne.app"
rm -rf "$WORK/images/bad-unsigned"
mkdir -p "$WORK/images/bad-unsigned"
cp -R "$GOOD" "$app"
codesign --remove-signature "$app" 2>/dev/null || true
if ! bash "$SCAN" "$app" > "$WORK/out" 2>&1 &&
    grep -q "codesign" "$WORK/out"; then
    t_ok "an unsigned bundle is rejected"
else
    t_fail "an unsigned bundle is rejected"
    sed 's/^/    /' "$WORK/out" >&2
fi

# 10. A JAR outside the Licensee-checked classpath is rejected.
app="$(break_app "bad-jar" "cp \"\$WORK/$JAR_NAME\" \"\$app/Contents/app/stray-9f8e7d6c5b4a3210fedcba9876543210.jar\"")"
if ! bash "$SCAN" "$app" > "$WORK/out" 2>&1 &&
    grep -q "not on the Licensee-checked runtime classpath" "$WORK/out"; then
    t_ok "an unmanifested JAR is rejected"
else
    t_fail "an unmanifested JAR is rejected"
    sed 's/^/    /' "$WORK/out" >&2
fi

# --- summary --------------------------------------------------------------------

echo
if [ "$FAILED" -gt 0 ]; then
    echo "check-desktop-image.test: $FAILED case(s) failing" >&2
    exit 1
fi
echo "check-desktop-image.test: all cases behave"
