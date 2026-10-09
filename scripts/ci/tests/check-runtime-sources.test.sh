#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# check-runtime-sources.test.sh — the --image native-file boundary of
# scripts/ci/check-runtime-sources.sh (11 Runtime exception obligations and
# checks): every native file under runtime/ must exist in the pinned Temurin
# tree, and a Mach-O file that differs must compare equal only after the fake
# signature strip both copies get (jpackage re-signs ad hoc, so raw bytes
# legitimately differ). A real byte difference must still fail.
#
# codesign does not exist on this host, so a stub stands in for it; the stub's
# --remove-signature removes the 8-byte trailer these fixtures carry.
#
# Run: bash scripts/ci/tests/check-runtime-sources.test.sh
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
mkdir -p "$FIX_ROOT/scripts/ci" "$FIX_ROOT/desktopApp"
cp "$REPO_ROOT/scripts/ci/check-runtime-sources.sh" "$FIX_ROOT/scripts/ci/"
cp "$REPO_ROOT/desktopApp/runtime.lock" "$FIX_ROOT/desktopApp/"
CHECK="$FIX_ROOT/scripts/ci/check-runtime-sources.sh"
LOCK="$FIX_ROOT/desktopApp/runtime.lock"

lock_prop() { grep -E "^$2=" "$1" | tail -1 | cut -d= -f2-; }

# A fake codesign whose --remove-signature strips the 8-byte trailer jpackage's
# ad-hoc re-signing stands in for here.
STUBBIN="$WORK/bin"
mkdir -p "$STUBBIN"
cat > "$STUBBIN/codesign" <<'EOF'
#!/usr/bin/env bash
while [ $# -gt 0 ]; do
    case "$1" in
        --remove-signature)
            head -c -8 "$2" > "$2.unsig" && mv "$2.unsig" "$2"
            shift 2 ;;
        *) shift ;;
    esac
done
exit 0
EOF
chmod +x "$STUBBIN/codesign"
export PATH="$STUBBIN:$PATH"

# The image and the pinned JDK tree: release matches the lock, legal/ is
# non-empty, lib/libtest.dylib is Mach-O (magic cffaedfe) in both trees.
IMG="$WORK/img"
JDK="$WORK/jdk"
mkdir -p "$IMG/lib/runtime/legal" "$IMG/lib/runtime/lib" "$JDK/legal" "$JDK/lib"
printf 'JAVA_VERSION="%s"\nMODULES=java.base\n' \
    "$(lock_prop "$LOCK" javaVersion)" > "$IMG/lib/runtime/release"
printf 'JAVA_VERSION="%s"\nMODULES=java.base\n' \
    "$(lock_prop "$LOCK" javaVersion)" > "$JDK/release"
: > "$IMG/lib/runtime/legal/NOTICE"
: > "$JDK/legal/NOTICE"

printf 'SMOKE {"javaVendor":"%s","javaVendorVersion":"%s","javaRuntimeVersion":"%s"}\n' \
    "$(lock_prop "$LOCK" vendor)" \
    "$(lock_prop "$LOCK" vendorVersion)" \
    "$(lock_prop "$LOCK" javaRuntimeVersion)" > "$WORK/smoke.txt"

# macho <file> <body> <sig-tail> — Mach-O magic + body + an 8-byte trailer that
# the fake codesign strips, like a signature.
macho() { printf '\xcf\xfa\xed\xfe%s%s' "$2" "$3" > "$1"; }

run_check() {
    bash "$CHECK" --image "$IMG" --jdk "$JDK" --smoke "$WORK/smoke.txt" \
        > "$WORK/check.out" 2>&1
}

# --- cases ----------------------------------------------------------------------

# 1. Same bytes modulo the signature trailer: the strip makes both copies equal.
#    (The old cp with four operands never copied either file and killed the
#    script mid-check instead of reaching the comparison.)
macho "$JDK/lib/libtest.dylib" "loadable bytes" "sig-jdk!"
macho "$IMG/lib/runtime/lib/libtest.dylib" "loadable bytes" "sig-img!"
if run_check && ! grep -q 'cp: ' "$WORK/check.out"; then
    t_ok "Mach-O differing only in the signature trailer verifies equal"
else
    t_fail "Mach-O differing only in the signature trailer verifies equal"
    sed 's/^/    /' "$WORK/check.out" >&2
fi

# 2. A real byte difference survives the strip and is reported, not masked.
macho "$JDK/lib/libtest.dylib" "loadable bytes" "sig-jdk!"
macho "$IMG/lib/runtime/lib/libtest.dylib" "tampered bytes" "sig-img!"
if ! run_check && grep -q 'runtime file differs from the pinned Temurin archive: lib/libtest.dylib' "$WORK/check.out"; then
    t_ok "Mach-O with different bytes is reported after the strip"
else
    t_fail "Mach-O with different bytes is reported after the strip"
    sed 's/^/    /' "$WORK/check.out" >&2
fi

# 3. An image file with no counterpart in the pinned tree is rejected.
macho "$JDK/lib/libtest.dylib" "loadable bytes" "sig-jdk!"
macho "$IMG/lib/runtime/lib/libtest.dylib" "loadable bytes" "sig-img!"
macho "$IMG/lib/runtime/lib/libextra.dylib" "extra bytes" "sig-ext!"
if ! run_check && grep -q 'no counterpart in the pinned Temurin archive: lib/libextra.dylib' "$WORK/check.out"; then
    t_ok "runtime file without a pinned counterpart is rejected"
else
    t_fail "runtime file without a pinned counterpart is rejected"
    sed 's/^/    /' "$WORK/check.out" >&2
fi
rm -f "$IMG/lib/runtime/lib/libextra.dylib"

# 5. macOS layout: jlink's home inside a .app is Contents/runtime/Contents/Home
#    — legal/, release and the natives all live there (nightly 37860407043's
#    real bundle). The checks must follow the same nesting the image checker
#    accepts.
IMGMAC="$WORK/img-mac"
mkdir -p "$IMGMAC/Contents/runtime/Contents/Home/legal" \
         "$IMGMAC/Contents/runtime/Contents/Home/lib"
printf 'JAVA_VERSION="%s"\nMODULES=java.base\n' \
    "$(lock_prop "$LOCK" javaVersion)" \
    > "$IMGMAC/Contents/runtime/Contents/Home/release"
: > "$IMGMAC/Contents/runtime/Contents/Home/legal/NOTICE"
macho "$IMGMAC/Contents/runtime/Contents/Home/lib/libtest.dylib" "loadable bytes" "sig-img!"
if bash "$CHECK" --image "$IMGMAC" --jdk "$JDK" --smoke "$WORK/smoke.txt" \
        > "$WORK/check-mac.out" 2>&1; then
    t_ok ".app Contents/runtime/Contents/Home is the runtime root on macOS images"
else
    t_fail ".app Contents/runtime/Contents/Home is the runtime root on macOS images"
    sed 's/^/    /' "$WORK/check-mac.out" >&2
fi

echo
if [ "$FAILED" -gt 0 ]; then
    echo "check-runtime-sources.test: $FAILED case(s) failing" >&2
    exit 1
fi
echo "check-runtime-sources.test: all cases behave"
