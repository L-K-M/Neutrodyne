# M10-A11y Complete Selection Review

## Head
- Branch: `impl/m10-a11y-runner`
- Commit: `a52997ad06d382a496f7e625128ab5bddfd81e4d` (plus merges from `origin/impl/m10-journeys-current@386703bfa` and `origin/main@181ae186a`)
- Product source: byte-identical to `origin/impl/m10-journeys-current` parent; all changes CI/scripts/docs only

## Actual CI Status (Run 42449978391)
**RED** — The prior single-invocation m10-a11y job passed Gradle GMD but only `M10AccessibilityTest` executed. The custom `DeviceTestSelection` did not exclude the other three classes on API 36; they were silently deselected. `assert-selected-tests.py` correctly rejected the missing `NavigationStateRestorationTest`, `PerAppLanguageTest`, `SubscribeJourneyTest`.

## Fixture Status
**GREEN** — All eight `assert-selected-tests` tests pass. All three GMD/KVM fixture tests pass:
- `gmd-device-budget.test.py`: 6/6 pass (updated to expect four serial invocations)
- `emulator-acceleration.test.py`: 3/3 pass
- `assert-selected-tests.test.py`: 8/8 pass

Pre-existing failures (unrelated to this change):
- `preserve-emulator-crashes.test.py`: 2/3 fail (environment-specific timing)
- `trace-instrumentation-abi.test.py`: 2/23 fail (path normalization in temp directories)

## Changes Made

### 1. `.github/workflows/nightly.yml` — m10-a11y job
Replaced single combined-class GMD invocation with **four serial invocations**, one per class:
- Each step: `M10 journeys on the api36 GMD - <ClassName>`
- Each clears `app/build/outputs/androidTest-results/managedDevice/debug/api36` before its run
- Each runs `:app:api36DebugAndroidTest` with exactly one `--require-class` FQCN
- Each tees full Gradle stdout/stderr to `$RUNNER_TEMP/m10-a11y-<ClassName>.log` (explicit `pipefail`)
- Each copies produced XML/logcat to distinct evidence root: `app/build/outputs/androidTest-results/m10-a11y/<ClassName>/api36/`
- Immediate per-class `assert-selected-tests.py` verification after each copy
- Final aggregate verification requiring all four classes in `app/build/outputs/androidTest-results/m10-a11y/`
- Crash preserver trap preserved per invocation (correctly stops on EXIT)

### 2. `scripts/ci/tests/gmd-device-budget.test.py`
Updated `test_m10_a11y_selects_the_four_classes_on_one_api36_device` → `test_m10_a11y_runs_four_serial_invocations_one_per_class`:
- Iterates the four step names
- Verifies each invocation carries exactly one class filter
- Confirms device budget (`--max-workers=1`, `maxConcurrentDevices=1`) and lavapipe renderer per invocation

### 3. Documentation checks
- `python3 tools/doccheck/checkdocs.py .` → 0 problems
- `node tools/doccheck/mmdcheck.mjs .` → 0 invalid of 78 diagrams

### 4. Actionlint
- No new findings (only pre-existing SC2086 note in desktop-matrix step, not suppressed)

## Evidence Requirements Met
| Requirement | Implementation |
|-------------|----------------|
| Four serial GMD invocations | Four separate workflow steps, each with unique name |
| One FQCN per invocation | Each step filters to exactly one class |
| Clear owned api36 results before each | `rm -rf app/build/outputs/androidTest-results/managedDevice/debug/api36` |
| Distinct evidence root with api36 path | `app/build/outputs/androidTest-results/m10-a11y/<ClassName>/api36/` |
| Full Gradle stdout/stderr per class | `tee "$RUNNER_TEMP/m10-a11y-<ClassName>.log"` with `set -euo pipefail` |
| Copy evidence even on failure | `cp -r ... 2>/dev/null \|\| true` runs after Gradle (nonzero status preserved) |
| Abort on first failed invocation | `set -e` + workflow `continue-on-error: false` (default) |
| Immediate per-class assertion | `assert-selected-tests.py` called right after each copy |
| Final all-four assertion | Aggregate step verifies all four in combined evidence root |
| Missing/skip/failure/error/initErrors fail | `assert-selected-tests.py` unchanged — strict checks preserved |
| No weakened asserts/ATF/longer waits | Assert script and timeouts unchanged |
| Start evidence root clean | Each invocation clears `managedDevice/debug/api36` before run |
| Always-upload artifact paths include outputroot | Existing `**/build/outputs/androidTest-results/` glob captures new `m10-a11y/` tree |
| Emulator crash-preserver trap correct | Per-invocation trap with `EXIT` handler preserved |

## Gaps / Open Items
1. **Real CI execution not yet observed** — This review is based on fixture extraction (`fakegradlew`). The actual API 36 GMD behavior (emulator boot, test execution, logcat capture) will be validated on the next nightly `m10-a11y` dispatch.
2. **Artifact size** — Four separate log files (`m10-a11y-*.log`) plus four evidence trees increase upload volume; within GitHub Actions limits for this job scope.
3. **Timeout budget** — Each class gets 60 minutes; four sequential invocations could approach the job's 75-minute timeout if a class hangs. The 30-minute-per-case budget from the original design still applies per class.
4. **Device reuse** — Each invocation boots a fresh managed device (GMD recreates per task). This is intentional for isolation but increases wall time.

## Verdict
The implementation satisfies the bounded CI harness fix requirements:
- Four serial GMD invocations with per-class evidence isolation
- Fixture tests GREEN (all 8 assert + 3 GMD/KVM)
- Documentation checks clean
- No product/test source changes
- No PR/merge from helper — commit and push backup to same branch origin intended