#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# check-desktop-image.test.sh — boundary cases for the JAR rule of
# scripts/ci/check-desktop-image.sh (11 Image scan rules): an image JAR is on
# the Licensee-checked runtime classpath when the manifest
# (writeDesktopRuntimeClasspath's `sha256  <image name>` rows, the image name
# being Compose's `<base>-<md5>.jar` mangle) names it exactly and its SHA-256
# equals the row's. Compose's skiko-awt-runtime stub JAR is the one documented
# off-manifest case: its name carries the stub's own md5, the manifest carries a
# skiko-awt-runtime-<os>-<arch>-*.jar entry, the extracted native sits beside it
# with a matching .sha256 sidecar, and the jar itself is a manifest-only zip —
# a class-bearing jar under the stub's name is the classpath jar in disguise.
# All jar fixtures are genuine tiny ZIPs, like the artifacts the scan reads.
#
# The scan resolves runtime.lock and the manifest relative to its own path, so
# each run copies it into a fixture repo root — no repository state is touched.
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
mkdir -p "$FIX_ROOT/scripts/ci" "$FIX_ROOT/desktopApp/build/desktop-packaging"
cp "$REPO_ROOT/scripts/ci/check-desktop-image.sh" "$FIX_ROOT/scripts/ci/"
cp "$REPO_ROOT/desktopApp/runtime.lock" "$FIX_ROOT/desktopApp/"
MANIFEST="$FIX_ROOT/desktopApp/build/desktop-packaging/runtime-classpath.txt"
SCAN="$FIX_ROOT/scripts/ci/check-desktop-image.sh"

md5_of() { md5sum "$1" | cut -d' ' -f1; }
sha_of() { sha256sum "$1" | cut -d' ' -f1; }

# Compose's mangle suffix: the file's md5 rendered per byte with %x (no padding).
unpadded_md5() {
    local padded byte i out=""
    padded="$(md5_of "$1")"
    for ((i = 0; i < ${#padded}; i += 2)); do
        byte="${padded:i:2}"
        out+="${byte#0}"
    done
    echo "$out"
}

# new_image <tag> — a fresh case: an empty manifest plus the smallest image the
# scan accepts (one launcher, a jlink'd runtime/release matching runtime.lock, a
# non-empty legal/, and the JDK's own jar, skipped by the */runtime/* rule).
# Prints the image path.
new_image() {
    local img="$WORK/img-$1"
    : > "$MANIFEST"
    mkdir -p "$img/bin" "$img/lib/app" "$img/lib/runtime/legal" "$img/lib/runtime/lib"
    : > "$img/bin/Neutrodyne"
    printf 'JAVA_VERSION="%s"\nMODULES=java.base\n' \
        "$(grep -E '^javaVersion=' "$FIX_ROOT/desktopApp/runtime.lock" | cut -d= -f2)" \
        > "$img/lib/runtime/release"
    : > "$img/lib/runtime/legal/NOTICE"
    : > "$img/lib/runtime/lib/jrt-fs.jar"
    echo "$img"
}

# jar_zip <path> [entry...] — a real zip: META-INF/MANIFEST.MF plus each named
# entry filled with its own name as content.
jar_zip() {
    local out="$1"
    shift
    python3 - "$out" "$@" <<'PY'
import sys, zipfile
out, entries = sys.argv[1], sys.argv[2:]
with zipfile.ZipFile(out, 'w') as z:
    z.writestr('META-INF/', '')
    z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n')
    for e in entries:
        z.writestr(e, e + ' bytes')
PY
}

# mkjar <dir> <name> [entry...] — a jar carrying Compose's image name
# <name>-<unpadded md5>.jar; prints "basename<TAB>sha256".
mkjar() {
    local dir="$1" name="$2" f
    shift 2
    jar_zip "$dir/.tmp-jar" "$@"
    f="$dir/${name}-$(unpadded_md5 "$dir/.tmp-jar").jar"
    mv "$dir/.tmp-jar" "$f"
    printf '%s\t%s\n' "${f##*/}" "$(sha_of "$f")"
}

# mkstub <dir> [entry...] — Compose's stub shape: a manifest-only zip named
# skiko-awt-runtime-linux-x64-0.150.1-<own unpadded md5>.jar. The zip variant
# loops until the md5 carries a leading-zero byte, so the name exercises
# Compose's %x rendering dropping it (padded hex would not match). Extra
# entries turn the "stub" into whatever content the case needs. Prints the path.
mkstub() {
    local dir="$1" i=0 f padded j
    shift
    while :; do
        jar_zip "$dir/.stub" "META-INF/stub-$i" "$@"
        padded="$(md5_of "$dir/.stub")"
        for ((j = 0; j < ${#padded}; j += 2)); do
            [ "${padded:j:1}" = 0 ] && break
        done
        [ "$j" -lt "${#padded}" ] && break
        i=$((i + 1))
    done
    f="$dir/skiko-awt-runtime-linux-x64-0.150.1-$(unpadded_md5 "$dir/.stub").jar"
    mv "$dir/.stub" "$f"
    echo "$f"
}

# native_next_to <img> — the extracted libskiko with its .sha256 sidecar.
native_next_to() {
    printf 'native bytes' > "$1/lib/app/libskiko-linux-x64.so"
    sha_of "$1/lib/app/libskiko-linux-x64.so" > "$1/lib/app/libskiko-linux-x64.so.sha256"
}

manifest_row() { printf '%s  %s\n' "$1" "$2" >> "$MANIFEST"; }

run_scan() {
    bash "$SCAN" "$1" > "$WORK/scan.out" 2>&1
}

expect_fail() { # <pattern> <case name>
    local status
    run_scan "$img"
    status=$?
    if [ "$status" -eq 0 ] || ! grep -q "$1" "$WORK/scan.out"; then
        t_fail "$2"
        sed 's/^/    /' "$WORK/scan.out" | tail -8 >&2
    else
        t_ok "$2"
    fi
}

# --- cases ---------------------------------------------------------------------

# 1. A mangled-name image jar listed on the manifest by its image name, hashing
#    to the row's sha256, is a classpath jar. (The old base-name lookup rejected
#    every real image jar.)
img="$(new_image a)"
read -r name sha < <(mkjar "$img/lib/app" neutrodyne-core ch/lkmc/neutrodyne/MainKt.class)
manifest_row "$sha" "$name"
if run_scan "$img"; then
    t_ok "image jar named on the manifest with matching sha256 is accepted"
else
    t_fail "image jar named on the manifest with matching sha256 is accepted"
    sed 's/^/    /' "$WORK/scan.out" >&2
fi

# 2. Same name, different bytes: the row's sha256 binds the artifact.
img="$(new_image b)"
read -r name _sha < <(mkjar "$img/lib/app" neutrodyne-core ch/lkmc/neutrodyne/MainKt.class)
manifest_row "$(printf 'tampered' | sha256sum | cut -d' ' -f1)" "$name"
expect_fail 'JAR differs from the Licensee-checked classpath artifact' \
    "manifest-named jar with wrong bytes is rejected"

# 3. A mangled-looking jar absent from the manifest is rejected.
img="$(new_image c)"
mkjar "$img/lib/app" intruder ch/lkmc/neutrodyne/Intruder.class >/dev/null
expect_fail 'not on the Licensee-checked runtime classpath' \
    "jar missing from the manifest is rejected"

# 4. The skiko stub under its documented conditions: a manifest-only zip whose
#    name suffix is its own md5, the manifest carries a
#    skiko-awt-runtime-<os>-<arch>-*.jar entry (the classpath jar stays
#    manifest-only), and the extracted native verifies against its .sha256
#    sidecar.
img="$(new_image d)"
read -r skiko_name skiko_sha \
    < <(mkjar "$WORK" skiko-awt-runtime-linux-x64-0.150.1 org/jetbrains/skiko/SkiaLayer.class)
manifest_row "$skiko_sha" "$skiko_name"
mkstub "$img/lib/app" >/dev/null
native_next_to "$img"
if run_scan "$img"; then
    t_ok "skiko stub is accepted under the documented conditions"
else
    t_fail "skiko stub is accepted under the documented conditions"
    sed 's/^/    /' "$WORK/scan.out" >&2
fi

# 5. The stub without a manifest skiko entry is just another off-manifest jar.
img="$(new_image e)"
mkstub "$img/lib/app" >/dev/null
native_next_to "$img"
expect_fail 'not on the Licensee-checked runtime classpath' \
    "skiko stub without a manifest entry is rejected"

# 6. A jar squatting on the stub's name pattern — its suffix is not the file's
#    own md5 — is not the stub, manifest entry and native notwithstanding.
img="$(new_image f)"
manifest_row "$(printf 'x' | sha256sum | cut -d' ' -f1)" \
    "skiko-awt-runtime-linux-x64-0.150.1-00000000000000000000000000000000.jar"
jar_zip "$img/lib/app/.squat"
mv "$img/lib/app/.squat" \
    "$img/lib/app/skiko-awt-runtime-linux-x64-0.150.1-11111111111111111111111111111111.jar"
native_next_to "$img"
expect_fail 'not on the Licensee-checked runtime classpath' \
    "skiko-named jar whose suffix is not its own md5 is rejected"

# 7. The stub without the extracted native and its sidecar is rejected (the
#    exemption exists because the classpath jar's payload lands as the native).
img="$(new_image g)"
manifest_row "$skiko_sha" "$skiko_name"
mkstub "$img/lib/app" >/dev/null
expect_fail 'not on the Licensee-checked runtime classpath' \
    "skiko stub without the extracted native and .sha256 sidecar is rejected"

# 8. A manifest skiko entry for a different os-arch does not cover the stub.
#    The stub here carries its md5 zero-padded — the accepted alternative
#    rendering — and still fails at the os-arch gate.
img="$(new_image h)"
manifest_row "$skiko_sha" "skiko-awt-runtime-windows-x64-0.150.1-${skiko_name##*-0.150.1-}"
stub="$(mkstub "$img/lib/app")"
mv "$stub" "$img/lib/app/skiko-awt-runtime-linux-x64-0.150.1-$(md5_of "$stub").jar"
native_next_to "$img"
expect_fail 'not on the Licensee-checked runtime classpath' \
    "skiko stub whose manifest entry names another os-arch is rejected"

# 9. A "stub" carrying executable classes is the classpath jar in disguise: the
#    exemption is for Compose's manifest-only stub, not for the name alone.
img="$(new_image i)"
manifest_row "$skiko_sha" "$skiko_name"
mkstub "$img/lib/app" org/jetbrains/skiko/SkiaLayer.class >/dev/null
native_next_to "$img"
expect_fail 'not on the Licensee-checked runtime classpath' \
    "skiko-named jar carrying a .class is rejected"

# 10. Non-class payload outside META-INF is unexpected stub content too.
img="$(new_image j)"
manifest_row "$skiko_sha" "$skiko_name"
mkstub "$img/lib/app" skiko/linux/x64/libskiko.so >/dev/null
native_next_to "$img"
expect_fail 'not on the Licensee-checked runtime classpath' \
    "skiko-named jar carrying a non-META-INF payload is rejected"

# 11. A file under the stub's correctly-mangled name that is not a jar at all
#     is not the stub.
img="$(new_image k)"
manifest_row "$skiko_sha" "$skiko_name"
printf 'not a zip' > "$img/lib/app/.raw"
mv "$img/lib/app/.raw" \
    "$img/lib/app/skiko-awt-runtime-linux-x64-0.150.1-$(unpadded_md5 "$img/lib/app/.raw").jar"
native_next_to "$img"
expect_fail 'not on the Licensee-checked runtime classpath' \
    "skiko-named non-zip is rejected"

echo
if [ "$FAILED" -gt 0 ]; then
    echo "check-desktop-image.test: $FAILED case(s) failing" >&2
    exit 1
fi
echo "check-desktop-image.test: all cases behave"
