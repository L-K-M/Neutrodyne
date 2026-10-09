#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
# Extracts the pinned upstream room3-runtime sources jars into build/room3-src/,
# verifies the overlapping source sets are byte-identical between the -jvm and
# -android jars, merges them and applies patches/ in order. Idempotent.
set -euo pipefail
cd "$(dirname "$0")"

OUT=build/room3-src
rm -rf "$OUT"
mkdir -p "$OUT/jvm" "$OUT/android" "$OUT/merged"

sha256sum --check upstream/SHA256SUMS.txt

python3 - <<'PY'
import zipfile
zipfile.ZipFile('upstream/room3-runtime-jvm-3.0.3-sources.jar').extractall('build/room3-src/jvm')
zipfile.ZipFile('upstream/room3-runtime-android-3.0.3-sources.jar').extractall('build/room3-src/android')
PY

# Source sets shared by both jars must agree byte for byte before merging.
for dir in commonMain nonWebMain jvmAndAndroidMain; do
    diff -r "$OUT/jvm/$dir" "$OUT/android/$dir" >/dev/null
done

cp -a "$OUT/jvm/." "$OUT/merged/"
cp -a "$OUT/android/." "$OUT/merged/"

git apply --directory="$OUT/merged" patches/*.patch

# Stage the Apache-2.0 notice for embedding in the binary artifacts, matching
# upstream's META-INF/androidx/room3/room3-runtime/LICENSE.txt layout.
mkdir -p "$OUT/lic-res/META-INF/androidx/room3/room3-runtime"
cp "$OUT/merged/META-INF/androidx/room3/room3-runtime/LICENSE.txt" \
    "$OUT/lic-res/META-INF/androidx/room3/room3-runtime/LICENSE.txt"

echo "prepared $OUT/merged"
