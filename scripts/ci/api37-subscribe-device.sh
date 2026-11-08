#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
#
# api37-subscribe diagnostic leg (nightly api37-16k, scope api37-subscribe):
# runs only SubscribeJourneyTest on the already booted API 37 emulator, pulls
# the test's captured ATF evidence, then requires the class to report all its
# cases executed unskipped. The job's emulator boot gates are unchanged; only
# the device test selection is narrowed to the failing class.
set -uo pipefail
cd "$(dirname "$0")/../.."
variant="${1:?Usage: api37-subscribe-device.sh debug|release}"
class=ch.lkmc.neutrodyne.SubscribeJourneyTest
case "$variant" in
  debug)
    task=":app:connectedDebugAndroidTest"
    props=(-Pneutrodyne.testScope=nightly)
    package="ch.lkmc.neutrodyne.debug"
    ;;
  release)
    task=":app:connectedReleaseAndroidTest"
    props=(
      -PtestBuildType=release
      -Pandroid.testInstrumentationRunnerArguments.annotation=ch.lkmc.neutrodyne.core.testing.ReleaseSmoke
      -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
    )
    package="ch.lkmc.neutrodyne"
    ;;
  *) echo "unsupported variant: $variant" >&2; exit 2 ;;
esac

status=0
./gradlew --max-workers=2 "${props[@]}" \
  -Pandroid.testInstrumentationRunnerArguments.ytxReplay=true \
  -Pandroid.testInstrumentationRunnerArguments.class="$class" \
  "$task" || status=$?

# A failing suite still produced evidence: the test leaves the ATF window dump
# and the screenshot in the app's external files dir for the artifact upload.
adb pull "/sdcard/Android/data/$package/files/api37-subscribe" \
  "${RUNNER_TEMP:-/tmp}/api37-subscribe-$variant" || true

python3 scripts/ci/assert-selected-tests.py \
  "app/build/outputs/androidTest-results/connected/$variant" --device connected \
  --require-class "$class" --require-cases 4 || status=$?
exit "$status"
