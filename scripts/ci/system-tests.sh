#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
# Run only on disposable emulators: installs/clears the published application ID.
set -euo pipefail
cd "$(dirname "$0")/../.."
profile="${1:?Usage: system-tests.sh process-death|smoke bench34|api36|connected}"
device="${2:?Missing device}"
case "$profile:$device" in
  process-death:bench34) class=ProcessDeathResumeTest ;;
  smoke:api36|smoke:connected) class=ReleaseSmokeJourneyTest ;;
  *) echo "Unsupported system test profile/device: $profile:$device" >&2; exit 2 ;;
esac
variant=benchmarkRelease
if [ "$device" = connected ]; then
  task=connectedBenchmarkReleaseAndroidTest
else
  task="${device}BenchmarkReleaseAndroidTest"
fi
# A successful no-op/up-to-date task must not reuse yesterday's XML as evidence.
# Restrict deletion to this test module's generated reports, retaining other suites.
rm -rf benchmark/build/outputs/androidTest-results benchmark/build/reports/androidTests
report="benchmark/build/reports/system-tests/$profile-$device"
mkdir -p "$report"
printf '{"profile":"%s","device":"%s","targetVariant":"%s","publishedReleaseCoverage":false,"sha":"%s"}\n' \
  "$profile" "$device" "$variant" "$(git rev-parse HEAD)" > "$report/run.json"
cleanup() {
  local status=$?
  if [ "$device" = connected ]; then
    local packages
    if packages=$(adb shell pm list packages); then
      for package in ch.lkmc.neutrodyne ch.lkmc.neutrodyne.benchmark; do
        if grep -Fxq "package:$package" <<< "$packages"; then
          adb uninstall "$package" || { [ "$status" -ne 0 ] || status=1; }
        fi
      done
    else
      [ "$status" -ne 0 ] || status=1
    fi
  fi
  exit "$status"
}
trap cleanup EXIT
./gradlew --no-daemon --max-workers=2 \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  -Pneutrodyne.systemTests=true -Pneutrodyne.testScope=nightly \
  -Pandroid.testInstrumentationRunnerArguments.class="ch.lkmc.neutrodyne.benchmark.$class" \
  -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect \
  ":benchmark:$task" --rerun
# Use the whole root: AGP may add shard/device subdirectories, but the checker
# still requires the exact variant/device pair.
python3 scripts/ci/assert-system-tests.py "$profile" benchmark/build/outputs/androidTest-results --device "$device"
printf '%s\n' 'Target: benchmarkRelease (profileable, minified); published release APK coverage: false.' \
  | tee "$report/variant.txt"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  cat "$report/variant.txt" >> "$GITHUB_STEP_SUMMARY"
fi
