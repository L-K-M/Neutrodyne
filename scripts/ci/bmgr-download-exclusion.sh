#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
# Run only on a disposable, rootable AOSP emulator with release app/test APKs installed.
set -euo pipefail
pkg=${1:?Usage: bmgr-download-exclusion.sh ch.lkmc.neutrodyne}
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
# LocalTransport registers asynchronously; a backupnow before the registration
# completes is rejected with "Transport rejected package because it wasn't able
# to process it at the time" (nightly 2026-11-06, api29). bmgr-check.sh waits
# for the same observable before its init; do the same here.
transport_wait=60
deadline=$((SECONDS + transport_wait))
registered=""
while [ "$SECONDS" -lt "$deadline" ]; do
  if adb shell bmgr list transports | tr -d '\r' \
      | grep -Eq '^[[:space:]*]*com\.android\.localtransport/\.LocalTransport$'; then
    registered=1; break
  fi
  sleep 2
done
test -n "$registered" || { echo "::error::local transport did not register within ${transport_wait}s"; exit 1; }
selected="$(adb shell bmgr list transports | tr -d '\r' | sed -n 's/^ *\* //p' | head -1)"
test "$selected" = "com.android.localtransport/.LocalTransport" || {
  echo "::error::local transport is not the selected transport: $selected"
  exit 1
}
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
# LocalTransport initializes asynchronously: a backupnow before the init completes is
# rejected with "Transport rejected package because it wasn't able to process it at the
# time" (nightly 2026-11-06, api29). Run the init after the snapshot, as bmgr-check.sh
# does, and require its real result. A bounded retry is allowed only for the observed
# rc0 + a single "Initialization result: -1000" publish-before-store race (05's
# Testing with bmgr); anything else fails closed, as does exhaustion.
transport=com.android.localtransport/.LocalTransport
init_attempts=0
while :; do
  init_attempts=$((init_attempts + 1))
  init_rc=0
  adb shell bmgr init "$transport" > "$output/init.txt" 2>&1 || init_rc=$?
  if [ "$init_rc" -ne 0 ]; then
    echo "::error::bmgr init $transport failed rc=$init_rc: $(tr -d '\r' < "$output/init.txt" | tr '\n' ' ')"
    exit 1
  fi
  init_results=$(tr -d '\r' < "$output/init.txt" | grep -c 'Initialization result:' || true)
  init_value=$(tr -d '\r' < "$output/init.txt" | sed -n 's/^Initialization result: //p' | head -1)
  case "$init_results:$init_value" in
    1:0) break ;;
    1:-1000)
      if [ "$init_attempts" -ge 3 ]; then
        echo "::error::bmgr init $transport still prints -1000 after $init_attempts bounded attempts"
        exit 1
      fi ;;
    *) echo "::error::bmgr init $transport ambiguous output ($init_results result lines): $(tr -d '\r' < "$output/init.txt" | tr '\n' ' ')"; exit 1 ;;
  esac
done
# The init result is published before the transport finishes storing it (05's
# Testing with bmgr); a backupnow immediately after the init is rejected with
# "Transport rejected package because it wasn't able to process it at the time".
sleep 5
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
