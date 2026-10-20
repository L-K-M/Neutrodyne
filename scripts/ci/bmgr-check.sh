#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
# Run only on a disposable, rootable AOSP emulator with release app/test APKs installed.
set -euo pipefail
pkg=${1:?Usage: bmgr-check.sh ch.lkmc.neutrodyne}
test "$pkg" = ch.lkmc.neutrodyne
output=build/bmgr-evidence
mkdir -p "$output"
adb root
adb wait-for-device
test "$(adb shell id -u | tr -d '\r')" = 0
# Do not use Orchestrator: its clearPackageData would erase the completed download seed.
timeout 240 adb shell am instrument -w -e class \
  ch.lkmc.neutrodyne.DownloadJourneyTest#seedCompletedDownloadsForBackupTransport \
  "$pkg.test/ch.lkmc.neutrodyne.NeutrodyneTestRunner" | tee "$output/seed.txt"
grep -Eq '^OK \(1 test\)' "$output/seed.txt"
adb shell bmgr enable true
adb shell bmgr transport com.android.localtransport/.LocalTransport | tee "$output/transport.txt"
grep -q 'Selected transport' "$output/transport.txt"
adb shell settings put secure backup_local_transport_parameters 'is_encrypted=true,fake_encryption_flag=true'
# Pending is bounded and never treated as a completed snapshot.
for attempt in 1 2 3 4 5; do
  adb shell am broadcast --include-stopped-packages \
    -n "$pkg/ch.lkmc.neutrodyne.SnapshotNowReceiver" | tee "$output/snapshot.txt"
  if grep -Eq 'result=-1, data="ok ' "$output/snapshot.txt"; then break; fi
  grep -q 'data="pending"' "$output/snapshot.txt"
  sleep 5
done
grep -Eq 'result=-1, data="ok ' "$output/snapshot.txt"
adb shell bmgr backupnow "$pkg" | tee "$output/backup.txt"
grep -q "Package $pkg with result: Success" "$output/backup.txt"
# LocalTransport's directory moved between platform releases. Require exactly one archive.
adb shell find /data/backup /data/local/tmp /cache/backup \
  /data/user/0/com.android.localtransport /data/user_de/0/com.android.localtransport -type f -path "*/_full/$pkg" 2>/dev/null \
  | tr -d '\r' > "$output/paths.txt" || true
test "$(wc -l < "$output/paths.txt")" -eq 1
archive=$(cat "$output/paths.txt")
adb exec-out cat "$archive" > "$output/transport.tar"
python3 scripts/ci/assert-playback-tests.py bmgr "$output/transport.tar" | tee "$output/exclusion.txt"
