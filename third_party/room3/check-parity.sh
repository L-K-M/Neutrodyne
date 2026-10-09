#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
# Compares the rebuilt room3-runtime-rebuild artifacts in ../room3-maven against
# the upstream 3.0.3 artifacts resolved in the Gradle cache: named .class parity
# for the Android AAR and the JVM jar, plus the AAR's manifest service
# declaration, LICENSE.txt and proguard.txt. Compiler-generated classes
# (numbered lambdas, $$inlined$ and $DefaultImpls helpers) legitimately differ
# between compiler versions and the applied patch, so parity is asserted on the
# named class surface only. Exits non-zero on any drift, so it catches packaging
# regressions such as sources that fail to reach the archive.
#
# Usage: check-parity.sh [upstream-cache-root]
#   upstream-cache-root defaults to ~/.gradle/caches/modules-2/files-2.1
set -euo pipefail
cd "$(dirname "$0")"

CACHE="${1:-$HOME/.gradle/caches/modules-2/files-2.1}"
MAVEN=../room3-maven/androidx/room3

UPSTREAM_AAR=$(find "$CACHE/androidx.room3/room3-runtime-android/3.0.3" -name '*.aar' | head -n1)
UPSTREAM_JAR=$(find "$CACHE/androidx.room3/room3-runtime-jvm/3.0.3" -name 'room3-runtime-jvm-3.0.3.jar' | head -n1)
REBUILT_AAR="$MAVEN/room3-runtime-rebuild-android/3.0.3/room3-runtime-rebuild-android-3.0.3.aar"
REBUILT_JAR="$MAVEN/room3-runtime-rebuild-jvm/3.0.3/room3-runtime-rebuild-jvm-3.0.3.jar"

for f in "$UPSTREAM_AAR" "$UPSTREAM_JAR" "$REBUILT_AAR" "$REBUILT_JAR"; do
    [ -f "$f" ] || { echo "missing artifact: $f" >&2; exit 2; }
done

python3 - "$UPSTREAM_AAR" "$UPSTREAM_JAR" "$REBUILT_AAR" "$REBUILT_JAR" <<'PY'
import re, sys, zipfile

upstream_aar, upstream_jar, rebuilt_aar, rebuilt_jar = sys.argv[1:5]
failures = []

# Compiler-synthetic members whose names drift between compiler versions and
# patched code: numbered lambdas (…$1), $$inlined$ helpers, $DefaultImpls stubs.
SYNTHETIC = re.compile(r"(\$\d+|\$\$inlined|\$DefaultImpls)")

def named(classes):
    return {n for n in classes if not SYNTHETIC.search(n)}

def aar_classes(path):
    with zipfile.ZipFile(path) as aar:
        names = set(aar.namelist())
        with zipfile.ZipFile(aar.open("classes.jar")) as jar:
            classes = {n for n in jar.namelist() if n.endswith(".class")}
    return names, named(classes)

def jar_classes(path):
    with zipfile.ZipFile(path) as jar:
        return named({n for n in jar.namelist() if n.endswith(".class")})

up_aar_entries, up_aar_classes = aar_classes(upstream_aar)
re_aar_entries, re_aar_classes = aar_classes(rebuilt_aar)
up_jar_classes = jar_classes(upstream_jar)
re_jar_classes = jar_classes(rebuilt_jar)

def diff(label, expected, actual):
    missing = sorted(expected - actual)
    extra = sorted(actual - expected)
    for n in missing:
        failures.append(f"{label}: missing {n}")
    for n in extra:
        failures.append(f"{label}: unexpected {n}")
    print(f"{label}: upstream {len(expected)} named classes, rebuilt {len(actual)}")

diff("aar classes.jar", up_aar_classes, re_aar_classes)
diff("jvm jar", up_jar_classes, re_jar_classes)

# AndroidManifest must keep declaring MultiInstanceInvalidationService and the
# AAR must ship the upstream license path and consumer keep rules.
manifest = zipfile.ZipFile(rebuilt_aar).read("AndroidManifest.xml")
if b"MultiInstanceInvalidationService" not in manifest:
    failures.append("aar AndroidManifest.xml: no MultiInstanceInvalidationService")
for entry in ("META-INF/androidx/room3/room3-runtime/LICENSE.txt", "proguard.txt"):
    if entry not in re_aar_entries:
        failures.append(f"aar: missing {entry}")

if failures:
    print("\n".join(failures))
    sys.exit(1)
print("PARITY")
PY
