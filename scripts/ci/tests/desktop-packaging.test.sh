#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# desktop-packaging.test.sh — the convention plugin's packaging DSL invariants
# that native runners have bitten before (11 nativeDistributions configuration).
#
# Case: the macOS file association's icon must never land on the app icon's
# destination. Compose 1.12.1's createDistributable writes the app icon to
# Contents/Resources/<packageName>.icns through jpackage, then copies every
# fileAssociation iconFile to Contents/Resources/<its own basename> with
# copyTo(overwrite=false). The collision check's used-names set is
# case-sensitive, so an FA icon named neutrodyne.icns slips past the reserved
# Neutrodyne.icns — and on macOS's default case-insensitive filesystem the copy
# then fails with FileAlreadyExistsException (nightly 37831500506,
# desktop-matrix macos-arm64). With no FA iconFile the plugin writes
# CFBundleTypeIconFile=<packageName>.icns — the file association still shows
# the app icon — and nothing is copied.
#
# Run: bash scripts/ci/tests/desktop-packaging.test.sh
# Exit 0 = all cases behave; 1 = at least one regression.

set -uo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)"
REPO_ROOT="$(cd -- "$HERE/../../.." >/dev/null 2>&1 && pwd -P)"
PLUGIN="$REPO_ROOT/build-logic/convention/src/main/kotlin/DesktopApplicationConventionPlugin.kt"

FAILED=0
t_fail() { echo "not ok: $1" >&2; FAILED=$((FAILED + 1)); }
t_ok() { echo "ok: $1"; }

# 1. Inside the macOS {} block, fileAssociation() either omits iconFile or names
#    a file whose basename differs case-insensitively from <packageName>.icns.
if ! python3 - "$PLUGIN" <<'PY'
import re, sys

text = open(sys.argv[1]).read()
mac = re.search(r'macOS\s*\{', text)
if mac is None:
    print('no macOS {} block in the convention plugin', file=sys.stderr)
    sys.exit(1)
depth, i = 0, mac.end() - 1
for i in range(mac.end() - 1, len(text)):
    if text[i] == '{':
        depth += 1
    elif text[i] == '}':
        depth -= 1
        if depth == 0:
            break
block = text[mac.start():i]

pkg = re.search(r'DESKTOP_PACKAGE_NAME\s*=\s*"([^"]+)"', text)
if pkg is None:
    print('DESKTOP_PACKAGE_NAME constant not found', file=sys.stderr)
    sys.exit(1)
reserved = (pkg.group(1) + '.icns').lower()

if 'fileAssociation' not in block:
    print('macOS block has no fileAssociation call', file=sys.stderr)
    sys.exit(1)
# iconFile = project.file(...) is the fileAssociation argument; the app icon's
# iconFile.set(project.file(...)) is a different call shape.
icon = re.search(r'iconFile\s*=\s*project\.file\("([^"]+)"\)', block)
if icon is not None and icon.group(1).rsplit('/', 1)[-1].lower() == reserved:
    print(
        'macOS fileAssociation icon %s collides with Contents/Resources/%s.icns '
        'on a case-insensitive filesystem' % (icon.group(1), pkg.group(1)),
        file=sys.stderr,
    )
    sys.exit(1)

app_icon = re.search(r'iconFile\.set\(project\.file\("([^"]+)"\)\)', block)
if app_icon is None or not app_icon.group(1).endswith('.icns'):
    print('macOS block lost its app iconFile.set(...icns)', file=sys.stderr)
    sys.exit(1)
sys.exit(0)
PY
then
    t_fail "the macOS file-association icon cannot collide with the app icon in Contents/Resources"
else
    t_ok "the macOS file-association icon cannot collide with the app icon in Contents/Resources"
fi

# 2. The committed .icns asset is still the app's icon source.
if [ -f "$REPO_ROOT/desktopApp/icons/neutrodyne.icns" ]; then
    t_ok "the committed icns brand asset is still present"
else
    t_fail "the committed icns brand asset is still present"
fi

# 3. The RPM spec override must be named <linux package name>.spec — jpackage's
#    --resource-dir lookup resolves a resource by the name of the file it is
#    about to write (SPECS/neutrodyne.spec), so template.spec is silently
#    ignored and the RPM is built with jpackage's default spec — no Requires at
#    all (nightly 37860407043).
if [ -f "$REPO_ROOT/desktopApp/packaging/rpm/neutrodyne.spec" ] \
    && [ ! -e "$REPO_ROOT/desktopApp/packaging/rpm/template.spec" ] \
    && grep -qE '^Requires: +libc\.so\.6' \
        "$REPO_ROOT/desktopApp/packaging/rpm/neutrodyne.spec"; then
    t_ok "the RPM override is named neutrodyne.spec and carries the Requires"
else
    t_fail "the RPM override is named neutrodyne.spec and carries the Requires"
fi

echo
if [ "$FAILED" -gt 0 ]; then
    echo "desktop-packaging.test: $FAILED case(s) failing" >&2
    exit 1
fi
echo "desktop-packaging.test: all cases behave"
