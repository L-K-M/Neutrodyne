# Handoff — state of the Neutrodyne work

This file lets another person or agent take over the work at any point. **Last updated: 2026-10-19.** M0a is merged; M0b is held for Android publication; M1a, M1b and M2 are merged, with owner-run device and desktop checks open (see §0). M3 and later local integration evidence is recorded in §0. Follow [`docs/IMPLEMENTATION-PROMPT.md`](docs/IMPLEMENTATION-PROMPT.md) through the v1.0 release.

## 0. Implementation status

### M6a W10 integration evidence (2026-10-19)

Work is local on `impl/m6a-p1`, following W1–W9 at `bfa3ce48`.
The owner requires local commits only, with no push or PR: CI and review
have not run (0 review rounds). M6a acceptance remains open. The final
local command/counts and measured P1/P2/P3 patch sizes are recorded in
`.nd-context/m6a-w10-report.md`; that ignored operator report is retained
in this worktree. Host tests do not establish the device/manual columns.
No emulator existed before this run. An API30 default x86_64 software
emulator connected but did not complete boot within 909 seconds; the host
has no KVM. Only that instance was stopped. Instrumentation: 0 executed.
No API33/34/36/37, release, bmgr or packaged desktop run is claimed.

Current host reports after the gate repairs: `allTests` 6,268 passed / 1
skipped, `test` 790 passed / 2 skipped, `mutationTest` 40 passed / 0 skipped.
The initial full run found a stale queue-shortcut assertion which still
removed the now-supported Downloaded filter; it now requires preservation.
`BackupWorkHostTest.a library change after start-up pokes the debounced write`
timed out in the loaded full run and passed unchanged in the serial rerun
of all 139 data Android host cases. The 19 queue cases also passed.
The three skips are Windows DPAPI and two desktop file-menu picker cases.
The sync protocol mutation task has no cases and supplies no mutation proof.
Per-task counts, final command status and patch sizes are in the report.

All device method names below are in `ch.lkmc.neutrodyne.DownloadJourneyTest`.
`downloadServerOffPlaysLocalArtworkChaptersAndDefersDelete` is E4 and
`@ReleaseSmoke`: it throttles a generated enclosure above 5 MiB, observes
on-disk progress, and uses the real graph, HTTP client, scheduler, files,
index, chapters, session and deferred deletion. The cover is generated
locally and pre-pinned, so this proves offline availability rather than
remote artwork acquisition. Media are the existing generated fixtures
packaged only in the test APK through `app/build.gradle.kts`.
Server-off proof does not establish airplane-mode radio behavior.

| M6 AC | Host evidence (exact class/method; desktopTest unless stated) | Device or manual evidence still required |
|---|---|---|
| 1 | `RssTransferSourceTest.cutAtHalfResumesWithStrongValidatorAndActualLength`, `weakOrAbsentValidatorsRestartSameLengthReplacement`, `range416VerifiesWholeFileOrRestarts`, `htmlAndHlsAreRejectedBeforeAnyWrite`; actual-client suite also covers redirects/encoding | Real captive portal and radio/network transitions |
| 2 | `DownloadReconcilerTest.processDeathTrimsOnlyTheTornTailBeforeResume`, `crashAfterRenameBeforeCompletionIsAdoptedWithoutTransfer`, `staleTokensResetOnceAndRegisteredOwnersSurviveParallelPasses` | External reboot mid-transfer on API33 worker and API34/36 UIDT; package update mid-transfer; in-process tests cannot kill themselves and prove recovery |
| 3 | `DownloadWorkersTest.visiblePromotionAllowedOnlyBefore34` and `ManualDownloadJobServiceTest.notificationIsSynchronousWhileDatabaseOpenIsHeldAndPermissionDenied` (`testAndroidHostTest`) | `visibleApi33DownloadUsesDataSyncForegroundWorker` on API33; `backgroundUidtBesidePlaybackForTwentyMinutes` on both API34 and API36, with screen off, two notifications, continuing bytes and audio positions |
| 6 | `DownloadReconcilerTest.controllerDeletesRowAndIndexButKeepsCurrentFileUntilSessionChanges`; `ChapterRepositoryTest` and playback adapter suites | E4 on API26/36, API37 and optimized release; physical airplane-mode sound/artwork and actual Share consumer grant |
| 7 | `BackupRulesXmlTest.modernAndroidIncludesOnlySnapshotsAndPortableSettingsInBothModes` (`:app:testDebugUnitTest`) plus Python transport-archive fixtures | `seedCompletedDownloadsForBackupTransport` followed by `scripts/ci/bmgr-check.sh` on rootable API29/36 release builds; transport inspection, not static XML, closes exclusion |
| 8 | `DownloadsScreenTest.offlineWaitAndFailureRowsHaveActionsAndDesktopHidesShare` and `DownloadsViewModelTest.allSectionCommandsAndDownloadsPlaybackUseThePublicController` (`:feature:downloads:desktopTest`) | Real accessibility, keyboard, denied notification permission and mounted/removable-root actions |
| 9 | `DownloadEngineTest.freshSuccessHasExactlyThreeSqlWritesAndMemoryVerification`, `twoParallelDrainsNeverDoubleClaimAndInitializeOnce` and `progressTicksNeverWriteTheDatabase` | No extra platform claim from the host write/slot invariants |
| 10 | `AndroidDownloadSchedulerTest.visibilityAndApiChooseUidtOrWorker`, `emptyAndBackoffQueuesNeverStartIdleRunner` and `runningUidtNeverReschedulesWhenNeedsChange` (`:download:impl:testAndroidHostTest`) | UIDT device soak plus invisible enqueue, restricted bucket, Data Saver, Wi-Fi/mobile/VPN and assigned-versus-default-network experiment |
| 12 | `RssTransferSourceTest.onlyUnvalidatedAndroidHtmlWaitsForNetwork`; `DownloadReconcilerTest.missingLengthAndUnavailableRootsRemoveIndexThenRemountRewritesUri`; engine/filesystem faults | Physical SD unmount/remount, full disk, MTP/Files visibility, API26–29 `.nomedia` and keep-data uninstall |
| 14 | `DesktopDownloadRestartTest.gracefulQuitAtHalfResumesWithoutTornTailTrimming` and `staleDesktopTokenAtHalfResetsAndTrimsTheTornTail` (`:desktopApp:test`) and `DesktopDownloadLaneTest.cancellationUsesQuitReasonKeepsPartAndUnregistersOnlyAfterCommit`, shared transfer suite | MD1 blocks audible offline desktop acceptance. Packaged Windows/macOS/Linux quit/restart, sleep/wake, cable loss, long/non-ASCII paths, ENOSPC and antivirus/rename behavior remain unexecuted |

AC4/5/11/13/15 belong to M6b. Historical Task Manager inference, AUTO,
cleanup/quota, root moves and out-of-process system tests remain there.
Rename durability under power loss requires empirical evidence.

CI with `run-instrumented` requires the named E4 result on API26/36.
Nightly full runs API26/33/34/36 and requires worker33 plus 20-minute
UIDT on34/36. Dispatch `scope=downloads` selects the app download class
explicitly on that group, including API33. API37 and release smoke require
E4 too. `bmgr-download-exclusion` runs a separate release matrix29/36,
seeds real completed internal and external media without Orchestrator
clearing data, triggers the M3 snapshot receiver and inspects LocalTransport.
This exclusion job does not replace M3's reinstall/library restoration proof.
Evidence gates require one non-skipped, non-failed result per named case
and device, a finite soak duration of at least 1,200 seconds, and all nine
nonempty media fixtures in the test APK. API-specific skips on other SDKs
are not counted as evidence for the SDK under test.

### M5 local integration (2026-10-19)

Branch `impl/m5-p1` integrates the M5 features on baseline `30eef445`.
Local commits `f6f3e9ba` (service/graph integration) and `52b737a8`
(device evidence gates) contain the verified code. The owner explicitly
requested local commits only: no push, PR, merge, CI dispatch or automated
review was performed (0 review rounds). No emulator, optimized-release,
reboot, DHU, physical listening or accessibility run was performed.
The only attached emulator reported offline and was left untouched.
M5 remains open for the device and owner evidence in the
[AC-by-AC ledger](docs/PLAN.md#m5-playback-features-and-system-surfaces).

Both graphs resolve one `ChapterRepositoryImpl` from core data; desktop
playback remains unavailable. Player construction now retains chapter and
notes repository providers until observation needs them, preserving the
foundation's database startup gate. Custom command registration uses one
list, with sleep/chapter dispatch under the existing serialized callback.
Departure capture precedes the queue projector, which freezes completion
before the sleep observer disarms its own reason. Duration/chapter
observers do not choose transitions. No schema, entity, index, dependency
or D-id changed. The adopted M5 boundary resolutions remain in the owning
design notes; no additional design deviation was needed.

After running `spotlessApply` alone, this complete command passed with
JDK 21, the Android SDK and the graphics environment loaded:

```sh
./gradlew --no-daemon --max-workers=2 \
  -Pkotlin.incremental=false \
  -Pkotlin.compiler.execution.strategy=in-process \
  allTests test mutationTest :app:compileDebugAndroidTestKotlin \
  spotlessCheck :app:lintRelease assertModuleGraph checkBannedApis \
  :app:compileDebugKotlin :desktopApp:compileKotlin \
  :app:assembleDebugAndroidTest --continue
```

The final gate took 6 min 26 s: 224 executed tasks, 8 reused from cache and
1,212 up to date. Release lint reported 0 errors and 0 warnings. Counts
below include current unchanged reports reused by Gradle; there are
6,745 passes, 3 skips and 0 failures across the three selectors.

| Selector | Passed | Skipped |
|---|---|---|
| `allTests` | 5,931 | 1 |
| `test` | 774 | 2 |
| `mutationTest` | 40 | 0 |

The skips are the Windows-only DPAPI case and both desktop file-menu picker
cases. The sync protocol mutation task has no matching cases and supplies
no mutation evidence. The evidence checker's 7 Python tests, the reboot
runner's 1 boundary test and the existing nightly reporter's 9 tests pass.
Both workflow files parse as YAML. Both doc checks pass: 0 problems across
14 files and 0 invalid of 78 Mermaid diagrams.

The app debug test APK was assembled and inspected: all 9 nonempty media
fixtures, all 4 playback device classes, and every one of the 15 API 37
required method names are packaged. This is packaging proof only; it
supplies no device pass count. Local logs, APK hash and complete per-task
counts are retained under `build/reports/m5-integration/` (generated,
ignored outputs). No optimized-release APK execution is claimed.

The gates require 11 short cases on API 26 and 12 on API 36; nightly
requires 13 on API 26, 14 on API 33/36 and 15 on API 34; API 37 requires
15, and release smoke requires 8. The separate host reboot route requires
3 non-skipped phases and archives the actual System UI card/artwork,
source-restored resume, logs, SHA, ABI, image and APK hash. The host-only
phases intentionally skip outside that route. All device counts remain
unexecuted here. DHU and the physical matrix still require separate
reports; AAOS retail installation remains unverified, desktop audio is
MD1b, and real playing-download deletion is M6.

The initial failures had identified causes: two service tests still
assumed eager startup; the player resolved repositories before the
database gate, crashing Android and blocking desktop; and the decoder
probe's test visibility annotation rejected its same-package production
caller in release lint. The blocked desktop worker was stopped after its
thread stack established the cause. The artwork manifest test reproduced
alone, so it was not load-sensitive: Robolectric left the implicit
application process null, while the provider used Android's package-name
default. Its assertion now honors that default. All affected tests pass in
the final gate. There were no daemon/lock retries.

Per-task XML totals (aggregator and no-source tasks omitted):

| Task | Passed | Skipped |
|---|---|---|
| `:app:testDebugUnitTest` | 142 | 0 |
| `:core:artwork:desktopTest` | 25 | 0 |
| `:core:artwork:testAndroidHostTest` | 43 | 0 |
| `:core:common:desktopTest` | 100 | 0 |
| `:core:data:desktopTest` | 1,227 | 1 |
| `:core:data:mutationTest` | 1 | 0 |
| `:core:data:testAndroidHostTest` | 139 | 0 |
| `:core:database:desktopTest` | 155 | 0 |
| `:core:database:testAndroidHostTest` | 9 | 0 |
| `:core:datastore:desktopTest` | 18 | 0 |
| `:core:designsystem:desktopTest` | 14 | 0 |
| `:core:domain:desktopTest` | 3 | 0 |
| `:core:model:desktopTest` | 2,550 | 0 |
| `:core:navigation:desktopTest` | 25 | 0 |
| `:core:network:desktopTest` | 33 | 0 |
| `:core:network:okhttp:test` | 45 | 0 |
| `:core:network:testAndroidHostTest` | 6 | 0 |
| `:core:testing:desktopTest` | 67 | 0 |
| `:core:ui:desktopTest` | 154 | 0 |
| `:core:ui:testAndroidHostTest` | 134 | 0 |
| `:desktop:system:test` | 1 | 0 |
| `:desktopApp:test` | 113 | 2 |
| `:download:api:desktopTest` | 1 | 0 |
| `:download:impl:desktopTest` | 1 | 0 |
| `:feature:discover:desktopTest` | 40 | 0 |
| `:feature:episode:desktopTest` | 48 | 0 |
| `:feature:feeds:desktopTest` | 117 | 0 |
| `:feature:feeds:testAndroidHostTest` | 70 | 0 |
| `:feature:groups:desktopTest` | 82 | 0 |
| `:feature:importexport:desktopTest` | 190 | 0 |
| `:feature:library:desktopTest` | 57 | 0 |
| `:feature:player:desktopTest` | 67 | 0 |
| `:feature:podcast:desktopTest` | 110 | 0 |
| `:feature:queue:desktopTest` | 19 | 0 |
| `:feature:settings:desktopTest` | 38 | 0 |
| `:feature:sync:desktopTest` | 1 | 0 |
| `:feeds:desktopTest` | 215 | 0 |
| `:feeds:jvm:mutationTest` | 38 | 0 |
| `:feeds:jvm:test` | 252 | 0 |
| `:playback:api:desktopTest` | 1 | 0 |
| `:playback:core:desktopTest` | 120 | 0 |
| `:playback:desktop:test` | 1 | 0 |
| `:playback:engine:test` | 1 | 0 |
| `:playback:impl:testDebugUnitTest` | 158 | 0 |
| `:playback:native:test` | 1 | 0 |
| `:sync:api:desktopTest` | 1 | 0 |
| `:sync:impl:desktopTest` | 1 | 0 |
| `:sync:protocol:desktopTest` | 27 | 0 |
| `:sync:server:test` | 58 | 0 |
| `:youtube:api:desktopTest` | 22 | 0 |
| `:youtube:api:mutationTest` | 1 | 0 |
| `:youtube:engine:test` | 1 | 0 |
| `:youtube:impl:desktopTest` | 1 | 0 |
| `:youtube:ytdlp-desktop:test` | 1 | 0 |

### M4 integration status (2026-10-19)

M4 integration (2026-10-19, branch `impl/m4-p1`) is local work only, per the
owner's no-push instruction. The playback service adapters and full-player
queue are integrated. Framework playback tests are hosted in the app test APK;
labelled PR CI requires the short cases on API 26/36, nightly requires the
30-minute screen-off stream on API 34, and the existing API 37 and release
jobs require their playback cases. The result gate rejects skipped or empty
suites. No device, nightly, release-APK or physical-audio result has been run
for this integration. M4 acceptance 3/4/5/8/12 therefore remains unverified on
devices, including abrupt process-kill recovery, cover/lock-screen inspection,
TalkBack, calls/headsets and listening at 3x with skip silence. Local host-test
results are recorded separately from those pending checks; M4 is not complete.

### M4 local verification (2026-10-19)

The requested full gate passed after `spotlessApply` ran alone, using JDK 21,
the Android SDK and the graphics environment, `--no-daemon --max-workers=2`,
`-Pkotlin.incremental=false` and
`-Pkotlin.compiler.execution.strategy=in-process`. The gate included
`allTests test mutationTest :app:compileDebugAndroidTestKotlin spotlessCheck
:app:lintRelease assertModuleGraph checkBannedApis :app:compileDebugKotlin
:desktopApp:compileKotlin --continue`. Release lint reported no errors or
warnings. The build executed 93 tasks, reused 1 from cache and found 1,215
up to date. Counts below include the unchanged tests' reused results.

The host XML contains 6,625 passing cases and three environment-dependent
skips: the Windows-only DPAPI test and two desktop file-menu UI cases.
`allTests` accounts for 5,855 passes and one skip; `test` for 730 passes and
two skips; `mutationTest` for 40 passes. The sync protocol mutation task has
no matching cases and contributes no mutation evidence. The playback-report
checker's five Python fixture tests also pass.

The desktop localization case passed alone; the new socket cancellation
regression then failed before its fix. The accept loop now preserves
coroutine cancellation instead of leaking `ClosedByInterruptException` into
later tests. The Android graph's 16 cases passed alone; the previous full
run's SQLite JNI classloader conflict is avoided by sharing the startup
suite's API 34 Robolectric sandbox. The final full suites pass.

The debug instrumentation APK also assembles. Its ZIP contains all three
synthetic MP3 assets under `assets/media/`, plus the manifest and DEX files.
This proves packaging only; the APK was not installed or run on a device.

Both documentation checks pass: 0 problems and 0 invalid Mermaid diagrams.
Device suites, CI, automated PR review and physical audio were not run. No PR
was opened or merged, following the owner's local-commit-only instruction.

| Test task | Passed | Skipped |
|---|---:|---:|
| `:app:testDebugUnitTest` | 142 | 0 |
| `:core:artwork:desktopTest` | 25 | 0 |
| `:core:artwork:testAndroidHostTest` | 43 | 0 |
| `:core:common:desktopTest` | 100 | 0 |
| `:core:data:desktopTest` | 1193 | 1 |
| `:core:data:mutationTest` | 1 | 0 |
| `:core:data:testAndroidHostTest` | 139 | 0 |
| `:core:database:desktopTest` | 152 | 0 |
| `:core:database:testAndroidHostTest` | 9 | 0 |
| `:core:datastore:desktopTest` | 18 | 0 |
| `:core:designsystem:desktopTest` | 14 | 0 |
| `:core:domain:desktopTest` | 3 | 0 |
| `:core:model:desktopTest` | 2547 | 0 |
| `:core:navigation:desktopTest` | 25 | 0 |
| `:core:network:desktopTest` | 33 | 0 |
| `:core:network:okhttp:test` | 45 | 0 |
| `:core:network:testAndroidHostTest` | 6 | 0 |
| `:core:testing:desktopTest` | 65 | 0 |
| `:core:ui:desktopTest` | 151 | 0 |
| `:core:ui:testAndroidHostTest` | 134 | 0 |
| `:desktop:system:test` | 1 | 0 |
| `:desktopApp:test` | 113 | 2 |
| `:download:api:desktopTest` | 1 | 0 |
| `:download:impl:desktopTest` | 1 | 0 |
| `:feature:discover:desktopTest` | 40 | 0 |
| `:feature:episode:desktopTest` | 46 | 0 |
| `:feature:feeds:desktopTest` | 117 | 0 |
| `:feature:feeds:testAndroidHostTest` | 70 | 0 |
| `:feature:groups:desktopTest` | 82 | 0 |
| `:feature:importexport:desktopTest` | 190 | 0 |
| `:feature:library:desktopTest` | 57 | 0 |
| `:feature:player:desktopTest` | 61 | 0 |
| `:feature:podcast:desktopTest` | 110 | 0 |
| `:feature:queue:desktopTest` | 19 | 0 |
| `:feature:settings:desktopTest` | 35 | 0 |
| `:feature:sync:desktopTest` | 1 | 0 |
| `:feeds:desktopTest` | 215 | 0 |
| `:feeds:jvm:mutationTest` | 38 | 0 |
| `:feeds:jvm:test` | 252 | 0 |
| `:playback:api:desktopTest` | 1 | 0 |
| `:playback:core:desktopTest` | 100 | 0 |
| `:playback:desktop:test` | 1 | 0 |
| `:playback:engine:test` | 1 | 0 |
| `:playback:impl:testDebugUnitTest` | 114 | 0 |
| `:playback:native:test` | 1 | 0 |
| `:sync:api:desktopTest` | 1 | 0 |
| `:sync:impl:desktopTest` | 1 | 0 |
| `:sync:protocol:desktopTest` | 27 | 0 |
| `:sync:server:test` | 58 | 0 |
| `:youtube:api:desktopTest` | 22 | 0 |
| `:youtube:api:mutationTest` | 1 | 0 |
| `:youtube:engine:test` | 1 | 0 |
| `:youtube:impl:desktopTest` | 1 | 0 |
| `:youtube:ytdlp-desktop:test` | 1 | 0 |

| Milestone | State | Branch / PR | Notes |
|---|---|---|---|
| M0a.1 | scheduled gate green through `52b2fd6`; current-head and tagged checks pending | [PR #10](https://github.com/L-K-M/Neutrodyne/pull/10), merge `e0c3722` | Scaffold, Android shell, policy gates and release pipeline. Seven Codex Sol rounds; GLM waived after repeated timeouts. Scheduled-main [38037939442](https://github.com/L-K-M/Neutrodyne/actions/runs/38037939442) on `bb0d13e` passes all four jobs, including API26/33/34/36 debug, API37 16 KB and release smoke; report-only repro also passes. The newer scheduled nightly [38125978109](https://github.com/L-K-M/Neutrodyne/actions/runs/38125978109) passes on `52b2fd6`. Issue #58 closed for those heads. Next: a scheduled green on the current main head, checklist [#20](https://github.com/L-K-M/Neutrodyne/issues/20), owner-device acceptance and tagged-main checks before `v0.1.0`. |
| M0a.2 | merged | [PR #13](https://github.com/L-K-M/Neutrodyne/pull/13), merge `49bc849` | Library spikes and Android UI/resource tests passed on API 26 and 36. Below API 33 the language-switch test skips while only `en-US` ships as Android resources; recorded in `docs/design/01-foundation.md` (S11) and `docs/design/09-quality-and-release.md` (Shipped locales and per-app language). No separate tester build. |
| M0b | native repairs green; independently closed | [#19](https://github.com/L-K-M/Neutrodyne/pull/19), [#25](https://github.com/L-K-M/Neutrodyne/pull/25); draft [#34](https://github.com/L-K-M/Neutrodyne/pull/34) `dbf475a` | [Regular CI 37973990857](https://github.com/L-K-M/Neutrodyne/actions/runs/37973990857) and [full matrix 37973995959](https://github.com/L-K-M/Neutrodyne/actions/runs/37973995959) pass, eight jobs including four desktop targets. Codex reports no important finding: 12 fixtures/six real ELF cases, whole checker accepts five valid variants and rejects four loader mutants/eight malformed cases. Program headers/loaded bytes remain bound; section metadata gives no alternate authority. MSI requires the actual INSTALLDIR; 37 image/15 smoke fixtures pass. Hold #34 until Android publishes; physical/minimum-OS/upgrade/uninstall/DMG/S13 gates remain. |
| M1a database | merged | [#37](https://github.com/L-K-M/Neutrodyne/pull/37) `6ed5d75`, [#38](https://github.com/L-K-M/Neutrodyne/pull/38) `616fefa`, [#40](https://github.com/L-K-M/Neutrodyne/pull/40) `52b2fd6`, [#74](https://github.com/L-K-M/Neutrodyne/pull/74) `d759351` (code `85b5342`), [#41](https://github.com/L-K-M/Neutrodyne/pull/41) `97a57cf` (code `3c0df9f`) | Schema, Room runtime, recovery and startup are on main. #74 (from the 2026-10-11 periodic review) stops an exception during failure classification from stranding the open gate, and makes a denied prune fail the open instead of reporting success; regressions fail before/pass after, mutation-checked. #41 opens the database at startup on both shells and runs the KMP device tests on API 26/36 in CI; its first GLM round was minor-only (exec/APK-guard/intent/snackbar claims refuted with evidence), and exact-head CI including instrumented passed. Still open: actual V1→V2 upgrade on main (proof lives on #70), physical-device and crash/retry proof. |
| M1a feeds | merged | [#22](https://github.com/L-K-M/Neutrodyne/pull/22) `5602303` (code `7a4e825`), [#76](https://github.com/L-K-M/Neutrodyne/pull/76) `195a04a`, [#77](https://github.com/L-K-M/Neutrodyne/pull/77) `d8a7d7c` (code `f6975f0`), [#78](https://github.com/L-K-M/Neutrodyne/pull/78) `e8ea0cd` (code `3a7b54f`), [#89](https://github.com/L-K-M/Neutrodyne/pull/89) `c77d211` (code `1d7606a`) | #22 merged after 15 GLM rounds (stabilization exit; deferred minors in its conversation); #76 runs the numeric-host rule on the IDNA result. #32 was split by review size into the parser and sanitizer (#77; its second GLM round had no confirmed important defect) and the corpus and goldens (#78; one round, all applied; the corpus now fails on a missing or empty directory). #89 fixes two minors from the 2026-10-12 periodic review: prefixed Atom roots were sniffed as `OTHER`, and a long Basic challenge overflowed the regex stack (now an iterative, fuzzed parser). The corpus device leg merged as #98, replacing #60. |
| Room runtime repair | merged | [#56](https://github.com/L-K-M/Neutrodyne/pull/56), merge `98dd9a7`, code `9497e5a` | Two GLM rounds plus independent source/rebuild/API triage close important findings. R1 holder-entry/probe bounds are fixed; R2 cancellation claim is false because pinned recycle is ordinary synchronous fun. Effective AAR timestamp/order settings are reproducible; six artifact hashes and 64 checksum sidecars match, Android 156/156 and JVM 126/126 class/API parity holds. All six exact-head/main jobs pass, [main CI 37980960606](https://github.com/L-K-M/Neutrodyne/actions/runs/37980960606). Version drift fails loudly. Deterministic handoff, Proxy and physical proof remain separate gaps. |
| Room native ownership | merged; startup-compatible fixture checks merged | [#67](https://github.com/L-K-M/Neutrodyne/pull/67) merge `0526a46`, code `efca8a5`; [#71](https://github.com/L-K-M/Neutrodyne/pull/71) merge `0684257`, code `0f9b53b` | Patch0002 closes unpublished native connections across cancellation boundaries; independent six-artifact/API/Binder/resource proof and 59 desktop/9 host pass. The wrapper-routing defect is fixed. Actual startup composition exposed a second test defect: a global app-directory sentinel mistook legitimate initializer writes for misrouting. #71 records every test-owned open, retains rejection before native opening and adds a negative control. [Composed CI 38038627021](https://github.com/L-K-M/Neutrodyne/actions/runs/38038627021) executes all five methods on API26/API36 without method failures/skips (0.377/0.248 s). Each PR closes after one GLM round with no verified important remainder. FD visibility stays conditional; physical proof remains separate. |
| Room migration index inspection | merged | [#69](https://github.com/L-K-M/Neutrodyne/pull/69), merge `bb0d13e`, code `440b90f` | Actual V1→V2 validation exposed upstream counting of WITHOUT ROWID auxiliary PK fields as declared index columns. Patch0003 filters `index_xinfo.key = 0`; raw native controls fail before/pass after. Independent clean six-artifact reproduction, 64 sidecars, eight variant records and 126 JVM/156 Android descriptors pass; 63 desktop/9 host and all-six CI pass. One GLM round closes: remote-Maven cache allegation does not match the exclusive file repository or observed offline warm-worktree 2→3 behavior. Corrected source comments were republished; production binaries remain byte-identical to independently reviewed `e66a735`. |
| M1a data | merged | [#79](https://github.com/L-K-M/Neutrodyne/pull/79) `d89d290`, [#80](https://github.com/L-K-M/Neutrodyne/pull/80) `4d1876e`, [#81](https://github.com/L-K-M/Neutrodyne/pull/81) `04d15c7`, [#82](https://github.com/L-K-M/Neutrodyne/pull/82) `327a4e4`, [#83](https://github.com/L-K-M/Neutrodyne/pull/83) `c74b40c`, [#84](https://github.com/L-K-M/Neutrodyne/pull/84) `223aa5e`, [#87](https://github.com/L-K-M/Neutrodyne/pull/87) `c1d5f16`, [#99](https://github.com/L-K-M/Neutrodyne/pull/99) `03a65e3`, [#88](https://github.com/L-K-M/Neutrodyne/pull/88) `f56a2b9` | The closed DATA PRs #50–#52 and their follow-ups #61–#63, #65 and #70 were replayed onto main's merged parser and split by review size. Every GLM finding is first verified independently (helper CLIs, executed probes); verified majors are fixed test-first. #81: four rounds (per-feed fault isolation; a paging host-key regression). #82: three (Basic credentials leaked over the http fallback, chained wrappers, scheduler failures failing a committed subscribe; #86 folded in). #83: a four-reviewer pre-review (no `DesktopJobRunnerTest` although M1 AC10 names it; stranded paging under "Manual only"), then two GLM rounds with no verified important finding; one round needed a rerun after HTTP 429s. #84: the 2026-10-12 review of `327a4e4`'s three important findings plus #61 and #85. #87: the paged `FeedRepository`. #99: the 2026-10-13 review of `223aa5e`'s important finding, refresh start-up never resuming after a database-recovery retry. #88 (D98, V1→V2): r1's one verified important finding (a contested, unchanged re-offer dropping out of the feed) is fixed. r2 covered three of four chunks with no important finding; the last chunk and a full rerun failed on HTTP 429, so Devin reviewed the r1 fix delta (no defect). The V1→V2 device migration passed. |
| M1a UI | merged | [#91](https://github.com/L-K-M/Neutrodyne/pull/91) U1 `3f54537` (replaces #53, closed) → [#93](https://github.com/L-K-M/Neutrodyne/pull/93) U2 `f101552` (replaces #54, closed) → [#94](https://github.com/L-K-M/Neutrodyne/pull/94) U3 `f8540f2` (replaces #57, closed) → [#97](https://github.com/L-K-M/Neutrodyne/pull/97) Feeds `44e96e2` → [#96](https://github.com/L-K-M/Neutrodyne/pull/96) Podcast `0ae5485` → [#95](https://github.com/L-K-M/Neutrodyne/pull/95) Episode and Add-podcast `9436e04` (all three now target main) | #53/#54/#57's deltas and the remaining screens of the verified `wip/m1a-ui` package (`753bee3`) are replayed onto the reviewed stack. The package was never GLM-reviewed, so every slice had an independent pre-review with verified fixes: a Library crash (keys pushed before their screens exist; placeholder entries with graph tests now guard every pushed key), repeated artwork pins wiping stored metadata, locale-frozen collation and dates, inert snackbar actions, and the feed password kept in the saved-state bundle. Merge order #97 → #96 → #95; later slices replace earlier placeholders. #91 merged after two GLM rounds: r1's one confirmed important finding (desktop `file:` artwork URIs were not percent-encoded) and eleven minors were fixed; its Major and Critical claims on cache trimming, cache keys and a missing import were refuted with Coil bytecode and probes, and r2 only re-raised one of them. #93 merged after two rounds: three confirmed important findings fixed test-first (the hover overflow covered the play button, download menu labels contradicted the state, the declared image aspect overrode the loaded one); its two Criticals (a literal `%` in Compose resources) were refuted, and a queued row offers only Cancel until M6 adds `promote`. #94 merged after three rounds: no confirmed important GLM finding (accessibility of the sort menu, a named mark-all dialog and test fixes applied), but its new metrox edge had put lifecycle 2.10.0 on the desktop compile classpath against 2.11.0 at run time (`NoSuchMethodError`); the desktop application convention now uses `consistentResolution { useRuntimeClasspathVersions() }`, with a linkage test. #97 merged after two rounds with no confirmed important finding. Its round-1 change to show append errors only in the footer row was wrong: with placeholders the footer sits after every unloaded row, so a mid-list append failure became invisible; #96 restores the pinned banner for append on Feeds and removes the duplicate footer on both screens, and 08's two slice notes now agree. #96 merged after two rounds: its confirmed important findings were latent credential defects behind `FEED_ACCOUNT_CONTROLS_ENABLED` (unmasked passwords, the password in saved state), fixed test-first so M1b can enable the controls. #95 merged after two rounds: its one confirmed important finding (an action snackbar defaulting to `Indefinite`, which holds the single root snackbar queue) was fixed, and the same default elsewhere on main is fixed by #103. |
| M1a corpus device leg | merged | [#98](https://github.com/L-K-M/Neutrodyne/pull/98) `8b478e5` (replaces #60, closed) | All 70 goldens pass through the AOSP parser on the API 26 and 36 managed devices (`AndroidParserGoldenInstrumentedTest`, per-device XML on CI); leg (b) runs on the host since #83. |
| M1a acceptance | merged | [#105](https://github.com/L-K-M/Neutrodyne/pull/105) `173fd04`, [#106](https://github.com/L-K-M/Neutrodyne/pull/106) `26de636`, [#111](https://github.com/L-K-M/Neutrodyne/pull/111) `854d546` | The audit in `~/nd-wt/reviews/m1a-audit.md` listed automated gaps. #105 (two GLM rounds, no confirmed important finding; its "won't compile" claim refuted on the PR) proves the rewrite and catalogue journeys, the subscribe `syncId`, and the supplementary-reference corpus fixture (71 fixtures). It also narrows M1 AC11 to subscribe in a dated note, with import and restore moving to M3 acceptance 12. #111 proves AC2's M1a clauses (`FeedRefreshContractTest`), AC4 (`RefreshWorkManagerTest`, WorkManager `TestDriver`, mid-ingest cancellation rollback) and AC10's runner half. Its CI failure was a real test race: after a retry, WorkManager's reschedule pass cancels the scheduler's tokens asynchronously, so an early re-delivery was stopped. The test waits for `scheduleRequestedAt`; 10 of 10 runs pass under CPU load, against 5 of 10 failing before the fix. #106 runs the parser mutation gate in the PR unit job and adds the nightly `mutation-full`. Owner-run checks remain: AC5 on the reference device and AC10's manual desktop half (procedure in the audit). |
| M1b | merged; device and Windows checks open | [#112](https://github.com/L-K-M/Neutrodyne/pull/112) `6efe483`, [#115](https://github.com/L-K-M/Neutrodyne/pull/115) `7707ed2`, [#117](https://github.com/L-K-M/Neutrodyne/pull/117) `77a1ee1`, [#120](https://github.com/L-K-M/Neutrodyne/pull/120) `03ff793`, [#113](https://github.com/L-K-M/Neutrodyne/pull/113) `51d4c18`, [#114](https://github.com/L-K-M/Neutrodyne/pull/114) `002f1a9`, [#116](https://github.com/L-K-M/Neutrodyne/pull/116) `03e257a`, [#118](https://github.com/L-K-M/Neutrodyne/pull/118) `b684817`, [#122](https://github.com/L-K-M/Neutrodyne/pull/122) `b909b52`, [#121](https://github.com/L-K-M/Neutrodyne/pull/121) `cce2a41` | Ten PRs from Codex's plan (`~/nd-wt/reviews/m1b-plan.md`), no schema change: the `SecretStore` and `CredentialCommitCoordinator` contracts, the shared store core with Android Keystore AES-GCM and the desktop secrets file (DPAPI on Windows, `0600` on macOS and Linux), credential persistence and wiring, lossless merges, permanent redirects and renormalisation, the `itunes:new-feed-url` probe, `editFeedUrl`, the enabled account controls, and RFC 5005 paging acceptance (AC8). Settled in dated notes: 02's provenance union runs as two statements on SQLite 3.18; a 301 ending in 304 gets one confirmation fetch, and only a body replaces the 304; AC8's 5,000-item stop applies to automatic sessions. Every PR had one or two GLM rounds; confirmed defects were fixed test-first (a desktop secret lost to a reconcile race, an unreadable secrets file overwritten, cleanup masking a commit failure, a failed redirect confirmation failing a healthy feed, failed probes cached as rejections, UI-scope exceptions). Open: AC9's real-Keystore device run (`:core:data` has no device-test source set) and AC12's DPAPI round-trip on a Windows host. |
| M2 | merged; device checks open | all 28 of [#125](https://github.com/L-K-M/Neutrodyne/pull/125)–[#152](https://github.com/L-K-M/Neutrodyne/pull/152) merged (not in number order); the last was [#151](https://github.com/L-K-M/Neutrodyne/pull/151) `a712bef` | From the synthesized plan (`~/nd-wt/reviews/m2-plan.md`): groups, membership ordering, persisted tabs and visits, effective settings, mosaics, counts, the batched new-episode notifier and Android poster and channels, effective refresh intervals, delete with undo, the Feeds pager, group editor and manage screen, chips and add-to-groups, Library multi-select, counted mark-played, gestures and live rows, query-plan and invalidation-hygiene tests, and the desktop group journey. No schema change. Every PR had one to three GLM rounds; findings, refutations and deferrals per PR are in `~/nd-wt/reviews/m2-deferred.md`. In-progress and Downloaded filter chips stay hidden until M4/M6 (`FeedFilterCapabilities`). Owner checks open: AC2 timing on the reference device, AC8 TalkBack, AC6 real process death, the notification-permission flows. |
| M3 | in progress | drafts [#155](https://github.com/L-K-M/Neutrodyne/pull/155) A7, [#156](https://github.com/L-K-M/Neutrodyne/pull/156) A2, [#157](https://github.com/L-K-M/Neutrodyne/pull/157) A1 | Plan: `~/nd-wt/reviews/m3-plan.md` (synthesis of Codex, Devin and DeepSeek plans), about 35 PRs in waves A (formats), B (persistence and services), C (platform hosts), D (UI) and E (acceptance). No schema change planned. Helper branches are pre-reviewed by an independent helper before they enter the serial GLM queue. |
| M1a GUID provenance | merged | [#88](https://github.com/L-K-M/Neutrodyne/pull/88) `f56a2b9` (replaces #70 and the held #64) | D98/PO-49: feed-scoped GUID history and additive V1→V2 with the frozen V1 schema and fixture unchanged. Two Codex rounds before GLM; GLM r1's ten "critical" items were `execSQL` false positives. The V1→V2 device run passed on the managed devices. |
| Navigation transitions | merged | [#73](https://github.com/L-K-M/Neutrodyne/pull/73) `ff6ad06` (code `08570c3`) | Instant tabs, cover on push, wipe on back and predictive back are as the owner asked; expanded two-pane in-pane swaps (Settings About → Licences → Back) now animate too. Three earlier in-pane designs animated inside one scene and each failed independent review (state loss, duplicate-provider crashes, over-pops, cross-tab mutation). A two-member committee (Sol, SWE-2) traced the root cause, and both candidate designs were built against a 26-test public-behavior gate suite (`PaneSwapGateTest`): the in-pane swap as a real Navigation3 scene change passed 26/26, the alternative pane animator stalled at 19/26. Later Codex closure rounds fixed an early predictive over-pop, completion-only Back over-pops, and cross-tab gesture completion; `0657504` closes with no important finding (86 host, 40 desktop, all gates), and the first GLM round was minor-only (its blocker was refuted against the `NavigationEvent` bytecode). Also fixed [#75](https://github.com/L-K-M/Neutrodyne/issues/75) (two-pane Back popped nothing in Feeds, over-popped in Library). Documented limitations: an expanded detail swap stops an in-progress list fling and ends an IME composition (focus restored); the post-decoration pane animator (`proto/nav73-e`) is the follow-up. Physical predictive gestures unverified. |

**Toolchain used locally:** Temurin 21 and 25 (Gradle toolchains via `org.gradle.java.installations.paths` in `~/.gradle/gradle.properties`), Android SDK with build tools 36/37 and platform 37, host CPython 3.14 for Chaquopy's `buildPython` (`neutrodyne.buildPython` in `~/.gradle/gradle.properties`; builds need `LANG=C.UTF-8`). No KVM on the development machine: instrumented tests and Gradle Managed Devices run only on CI. Compose UI tests on the desktop JVM need a GL library; locally a user-level Mesa is unpacked in `~/.local/gfx` (`source ~/.local/gfx/env.sh`).

**Owner execution instructions, retain through compaction:**

- Follow `docs/IMPLEMENTATION-PROMPT.md` milestone by milestone through published v1.0.
- Use available helpers, especially free models, Devin SWE-2 and Codex Sol. Since 2026-10-14 the owner has paused Z.ai models for helpers ("Don't use z.ai for subagents right now"); the CI GLM review continues. Check helper results before integration.
- Periodically have Codex Sol 6.1 at maximum reasoning review merged `main`.
- Keep PRs small and GLM reviews serial to protect the Z.ai quota. Avoid pushes that cancel an active review. The owner allows waiving GLM after repeated integration failures; record the gap and waiver, never claim approval.
- Helper CLIs are the default for independent work (owner's global instructions, 2026-10-16); Anthropic-backed workers only when they fit better. On 2026-10-17 `venice/*` ran out of credits and the free opencode models were rate-limited or overloaded; Devin SWE-2 carried implementation and pre-reviews. Opencode helpers cannot read files outside their worktree.

**Release gate:** (baseline below is stale: main has since taken #103–#154; re-baseline at the next scheduled green) main `33b6cb5` adds the feed parser and corpus (#77, #78), data contracts and the fetcher (#79), ingest (#80), the sniff and challenge fixes (#89), the refresh engine (#81), add/subscribe (#82), the refresh runners (#83), the engine fixes (#84), the paged `FeedRepository` (#87), the start-up recovery fix (#99), GUID provenance with V1→V2 (#88), the show-notes format fix (#92), a runner test fix (#100), the device corpus leg (#98) and two test-race fixes (#101) to the feeds, schema, Room, startup and transitions work. Regular main CI passes at `33b6cb5` ([38447262960](https://github.com/L-K-M/Neutrodyne/actions/runs/38447262960)). The newest scheduled nightly, [38227948941](https://github.com/L-K-M/Neutrodyne/actions/runs/38227948941), passed on `72fe62f`, an ancestor; a scheduled green on the current head is still required before `v0.1.0`. Branch, GMD or older-head success never waives the schedule or owner gates, and `repro` stays report-only. The owner's device report asked for corrected transitions; #73 (`ff6ad06`) delivers them and needs device acceptance. Checklist [#20](https://github.com/L-K-M/Neutrodyne/issues/20), transition acceptance on a device, tagged-main and the remaining manual checks precede publication. Main now exports V2.

**Review queue:** one GLM review at a time. Merged since the last update: #82 (three rounds), #83 (two), #84 (one), #87 (one), #99 (two), #88 (two), #92 (one; both "Major" claims refuted on the PR), #100 (one; a `DesktopJobRunnerTest` race under load, the "blocker" refuted), #98 (two; stale leg (c) status fixed), #101 (one; two load-sensitive test failures: the Android start-up recovery bound and a `SubscribeFlowTest` announcement race Codex reproduced), #90 (two), #91 (two), #93 (two), #94 (three), #97 (two), #96 (two), #95 (two); the M1a UI stack is complete. #103 (one; action snackbars `Long` instead of `Indefinite`, per 08). #104 (one; the 9436e04 review's minors). #105 (two). Dependabot #107–#110 (action bumps, SHAs checked against upstream tags): GLM reviewed all four at once because Dependabot runs receive `ZAI_API_KEY`, so treat Dependabot PRs as quota consumers. #106 (two; both "blocking" claims, a missing task contract and a corrupted pin, refuted on the PR). #111 (two; the CI failure was a real WorkManager test race, root-caused and fixed; r2's stale-stamp claim refuted from the 2.12 bytecode). #119 (one; minor-only). #112 (one; minor-only, `6efe483`). #115 (two, `7707ed2`): r1's confirmed major, a reconcile dropping a desktop secret that a racing `put` had just re-persisted, was fixed test-first by running `rowDropped` under the store's lock. #117 (two plus a 429 rerun, `77a1ee1`): an unreadable secrets file is set aside as `.unreadable` instead of overwritten (test-first), and the temp file is forced to disk before the atomic rename. #120 (two plus a 429 rerun, `03ff793`): the credential cleanup no longer masks a commit failure when the database itself failed (test-first, closed database). The credential half of M1b is on main. #113 (one, `51d4c18`): no confirmed important finding; the "resurrected loser" major is refuted (podcast rows are inserted only by subscribe and import; an ingest into a deleted podcast hits the episode FK or returns `vanished`). Mark a PR ready only after GitHub shows the pushed head; otherwise the review covers the old head. #114 (two, `002f1a9`): r1's confirmed major, a failed redirect confirmation turning a healthy 304 into a failure, was fixed test-first (only a confirmation body replaces the 304; 03's note follows); its other three majors were refuted, and r2 only re-raised one of them. #116 (two plus a 429 rerun, `03e257a`): failed probe fetches no longer enter the 24-hour rejection LRU (03 reserves it for implausible targets; test-first), and a probe fault after a committed ingest no longer records a STORAGE failure. #118 (one, `b684817`): no confirmed important finding (userinfo in fetch metadata refuted: ingest persists neither URL; edit-URL credentials land with #122). #122 (two, `b909b52`): the account controls are live; r1's confirmed finding (a repository fault in edit-URL or set-password escaping the UI scope) was fixed test-first in both view models. GitHub retargets a stacked PR to main when its base merges (merged branches are deleted). Every M1b PR and both review-fix PRs are merged; this handoff (#102) is the last open item, and M2 is next. Main's head is `b50a8b1`. Keep #34 draft until Android publishes. Prune swallowing, IOERR escalation, redundant GUID index and exec claims stay rejected. Avoid starting GLM reviews right after 00:00 and 01:00 UTC: the Paseo stack heartbeats use the same Z.ai plan (HTTP 429 seen on #83); rerun a 429-only failure after ~20 min. Push the UI branches only when one enters review: pushing all eight at once saturated the Actions runners for over two hours. GMD emulator boots fail occasionally (seen on the earlier main commit `ea86095`, `api26Setup`); rerun them.

**Data-hang cause and proof:** the 73-minute U1 paging hang produced 72,445,492,508 bytes of test events, then a daemon OOM. Oversized binaries were removed; source, daemon log and retry XMLs remain. Capped probes reproduce inherited `TimeoutCancellationException` retrying, about 695 MB counted stderr/eight seconds and a fresh writer pending after six seconds with Free/permits-0 state. Protected normal release refutes interrupted-release claims; test-clock divergence accelerates a real hazard. All probe groups are dead, 168 KB evidence retained. Caps remain 120 s plus 15 s kill, 64 MiB/file, 512 MiB total, fresh no-daemon, ≤2 workers. Stable 3.0.3 has no fail-fast switch; alpha retains the flaw. #56's reproducible Apache-2.0 repair is merged in `98dd9a7`, compiler/plugin/paging/testing remain upstream 3.0.3. Remove vendoring once upstream ships equivalent behavior. Older synthetic data/UI bases still need refresh; never replay full unpatched suites or treat green retries as resolution.

**Remaining UI sequence:** feature installers activate routes immediately. #57's real Metro/Coil and sourced-Mesa window paths pass; minimum public scheduler export is now proven with internal constructor/rebaser. Fresh composed CI/review remains. `FeedRepositoryImpl` precedes feeds/podcast; preserve splash/startup gates and the 30-source desktop shell. Feeds/data, podcast/settings, discover/episode and the small documented remainder follow.

**Periodic main review (2026-10-08, Codex Sol 6.1 Max, `9805dbf`):** no Android-only blocker found in the reviewed range. All three important desktop fixes merged: runtime host/archive handling [#36](https://github.com/L-K-M/Neutrodyne/pull/36) (`8cccf7c`), bounded/cancellable handshake reads [#35](https://github.com/L-K-M/Neutrodyne/pull/35) (`221feb1`), and returning smoke-window/first-frame validation [#39](https://github.com/L-K-M/Neutrodyne/pull/39) (`9a6e78e`). GLM's alleged `assertThrows` compile blocker was refuted by the shared test helper and forced convention tests. The Windows jpackage suffix claim was Linux-only. Malformed server CIDRs and the stale licence-repair note in design 01 remain deferred.

**Windows test portability:** [#46](https://github.com/L-K-M/Neutrodyne/pull/46) merged as `9a81df3`. CI and the native Windows unit step above pass with identical test sources. One GLM round found no applicable defect: the resolved runtime uses `kotlin-test-junit` and JUnit 4. Temp-directory cleanup and unrelated fixture consolidation are deferred.

**Periodic main reviews (Codex Sol 6.1 Max):** 2026-10-09 on `ce2ec9a`: no important finding across `6ed5d75..ce2ec9a` (#59, #56, #55). 2026-10-10 on `a7200f5`: no important finding (schema #38). 2026-10-11 on `52b2fd6`: one important finding (an overflowing error code stranded the open gate), fixed by #74; the healthy-launch quarantine-expiry minor is deferred as an owner trade-off. 2026-10-12 on `97a57cf` (#22, #74, #41): no important finding; merge trees match the reviewed heads, startup preserves #74's failure publication on both shells, module-graph and dependency policies pass; the IDNA numeric-host minor was fixed by #76. 2026-10-12 on `d89d290` (#73, #76, #72, #77–#79): no important finding; #89 fixed the prefixed-Atom sniff and the Basic-challenge stack overflow, and this update fixes the stale handoff. 2026-10-12 on `c77d211` (#80, #89): no important finding; merge trees match the reviewed heads. Its two code minors (the show-notes cache ignores a changed link base; Atom plain-text descriptions render as HTML) were fixed by #92 (`ea86095`); this update fixes its third, the README's "no code yet". 2026-10-12 on `327a4e4` (#81, #82): three important findings (a discarded final-flush failure, an unguarded failure write in the fetch-exception guard, decimal-IPv4 host spellings bypassing the per-host lane) and two minors; all fixed by #84. 2026-10-13 on `223aa5e` (#83, #84): all earlier findings fixed; one important finding (refresh initialization never resumed after a database-recovery retry), fixed by #99; the stale corpus note is fixed on #98. 2026-10-13 on `f56a2b9` (#87, #99, #88): no important finding; merge trees match the reviewed heads, the startup-retry fix holds on both shells, and the V1→V2 migration matches frozen `2.json`. Of its four doc minors, #98 fixes the corpus note; #90 fixes the README status, 03's D98 upgrade sentence and 02's builder example. 2026-10-13 on `df14937` (#92, #100, #98, #101, #90): no important or new minor finding; every earlier finding fixed, merge trees reconstruct exactly, release APKs carry no corpus assets, and exact-main CI passes with all 70 device goldens and both V1→V2 driver paths on API 26 and 36. 2026-10-14 on `9436e04` (the M1a UI stack, #91–#97): one important finding (ignored action snackbars held the root queue), fixed by #103; two minors (the row's missing playback custom action, stale 08 placeholder notes), fixed by #104. Merge trees match the reviewed heads, 3,060 tests pass, desktop compile and runtime classpaths agree, and every pushed nav key renders its real screen. 2026-10-14 on `26de636` (#103–#110): no important finding; merge trees match, the 1,000-iteration mutation run passes locally in 16 min under the 128 MB heap, and all four action pins match upstream tags. Two minors (a step timeout for the nightly mutation job; R7.1, M1 AC11, M3 AC12 and 10 claiming every sync table stays empty although `sync_state` keeps its default row) are fixed by #119 (`8b8dc22`, one round). 2026-10-15 on `cce2a41` (#111, #119 and M1b #112–#122): six important findings, each reproduced by a scratch probe (`~/nd-wt/reviews/main-cce2a41.md`, evidence under `~/nd-wt/reviews/evidence/review-main-cce2a41/`). Secrets: a malformed desktop secrets file logs password excerpts; publisher userinfo in a 301 or `new-feed-url` target is stored in `feedUrl`; the Edit-URL draft keeps a typed password in saved state; stored IPv6 origins keep brackets and never match OkHttp's host. Engine: unbounded Levenshtein on publisher titles can stall a run; an in-flight 401 can re-block freshly saved credentials. The four secret findings are fixed test-first by #123 (`9b681ba`, one round; its "discards working credentials" major refuted on the PR: the fetcher never sends URL userinfo). The two engine findings (a 256-character title-compare bound; `setCredentials` under the refresher's run mutex, lock order run mutex → `commitMutex` → coordinator → Room writer) are fixed by #124 (`b50a8b1`, two rounds; its CI failure was a 200-ms test budget, now 2 s). 2026-10-17 on `4919b3a7` (Codex Sol max for data, domain and notifications; Devin SWE-2 for UI; every finding checked by an independent skeptic): five important or minor cancellation and race findings and one missing translation, fixed test-first in [#153](https://github.com/L-K-M/Neutrodyne/pull/153) `5fedf13` (writes and their owed effects now finish as one `NonCancellable` unit; the rebase write skips force marks; the channel sweep rereads groups after undo tokens; poster binder calls off Main; the two missing German strings) and [#154](https://github.com/L-K-M/Neutrodyne/pull/154) `97aaf40` (ingests that commit as a run is cancelled still notify). Evidence: `~/nd-wt/reviews/evidence/review-main-4919b3a7-{a,b}`.

**Resume:** fetch first; cut new task branches from `origin/main`, or merge it into existing work before edits. Check PR heads, CI and review comments before acting. Worktrees are under `~/nd-wt/`, prompts and reports under `~/nd-wt/prompts/` and `~/nd-wt/reviews/`. Use `export JAVA_HOME=$HOME/.local/jdks/jdk21 ANDROID_HOME=$HOME/android-sdk LANG=C.UTF-8`; source `~/.local/gfx/env.sh` for Compose JVM tests. Watch free disk space; prune only verified merged worktrees and regenerable outputs, never active helper work.

**Coordination:** the parent agent owns serial CI, reviews and this handoff; child agents run no monitors or heartbeats. Idle and reusable: `f0c145ae` (Devin, built the PR #73 gate base `proto/nav73-base`), committee members `eee69e7d` (Sol, author of the accepted #73 design on `proto/nav73-b`) and `8e9d8f13` (SWE-2). Keep `proto/nav73-e`, its WIP pane animator, intact until the #73 continuity follow-up lands. GUID `ff56521` stays frozen in `guid-provenance`. Open-PR and M3 worktrees under `~/nd-wt/`: `m3-a1` … `m3-b8` (one per M3 row branch `impl/m3-<row>`; drafts #155–#157, #159–#162 and later), `docs-handoff-m2` for this file; check `git worktree list` before removing any. The local branches `test/m1a-refresh-g1`, `-g3` and `-g5` were cherry-picked, modified, into #111 (merged); `git branch -d` refuses them and no PR covers them, so they stay; their content is on main through #111, so `git branch -D test/m1a-refresh-g1 test/m1a-refresh-g3 test/m1a-refresh-g5` is safe once the owner agrees. The Paseo worktrees `data-reviewed-refresh-base`, `db-shell-device-slice` and `navigation-transitions` predate this session; check their owners before removing them. Long helper-CLI jobs run detached through `~/nd-wt/bin/helper-job.sh` (start/wait), so workflow relays can poll past their 10-minute call limit. Review reports and probes live under `~/nd-wt/reviews/` and each review worktree's `build/review-*/`; prompts under `~/nd-wt/prompts/`. Legacy heartbeat `b6598800` expired 2026-10-10 15:32 UTC; delete it on takeover if it still exists and never extend it silently. Preserve the `~/nd-wt/review-*` worktrees' `build/review-*/` evidence: copy it to `~/nd-wt/reviews/evidence/` before removing a worktree. On 2026-10-13 the `review-main-52b2fd6`, `review-feeds2`, `review-nav-static` and `review-guid88` worktrees were removed before this rule existed: their `build/review-*/` evidence was deleted, not copied, and only their reports remain in `~/nd-wt/reviews/`. `main-review-3` and `rare-catfish` no longer exist; older proofs are listed in this file's git history.

**Deferred follow-ups:** integrate the verified query-component UTF-8 repair (#86, from #63) after its predecessors; make process-start probe reports atomic; guard `SettingStore.observe` against a key from the wrong file; optionally clamp sanitizer image dimensions. From this round:
- M2 and the 2026-10-17 review: every deferred item with its reason is in `~/nd-wt/reviews/m2-deferred.md`, among them D1's last `markVisited` on teardown, the podcast chip remove target (M10), the Library tile-menu counted dialog, D9's UTC month cutoffs, the sync-era merge of hidden filter flags on write (D11), the `FeedIngestor` caller-contract comment (#154), and `SubscribeUseCaseImpl`'s cancellable post-commit scheduling (same class as #153).
- Owner trade-offs: healthy-launch quarantine expiry for recovery copies (2026-10-11 review); a retry policy for transient prune failures (#74 round 1).
- #41 minors: stale KDoc, logging discarded retry failures, an `ActivityNotFoundException` guard on the storage action (`StorageManager.ACTION_MANAGE_STORAGE` may be the better target), the duplicated fixture parser, a cascade assertion.
- #22 deferred minors (PR conversation).
- #73's pane animator follow-up for list fling/IME continuity.
- #80: split ingest preparation from the write transaction (the 2c caller); dateless items and the new-episode window (product question).
- #81 test nits: close the database in `NextRefreshRebaserTest`; add forced and blocked rows to the manual-only rebaser case (production already skips them).
- #83 deferred: chunk `downloadedEpisodeIds` at the bind limit; GLM r2 test nits (hard-coded retry boundary, the host fixture's ingestor dispatcher).
- #84, #87 deferred: test-hygiene nits (single-dispatcher assumption, closing local test databases); affected-row counts from the order setters when M2 needs them.
- UI pre-review deferrals: the tile-menu separator, the Library empty state's Import action, the three duplicate `*Filled` navigation icons (pre-existing on main), and the declined Podcast-screen nits.
- #103 deferred: the About page's copy-link snackbar has no dismiss action; the action→`Long` rule repeats in `UrlOpeners` and `AboutPage` (a public core:ui helper would unify it).
- #95 deferred: an unused import in `AddPodcastRouteTest`.
- #112 deferred: document `SecretStore.put`'s secret-array ownership; `FakeSecretStore.get` returns its internal array and keys raw `put` origins differently from `storedKey`; test `putByOrigin`'s lowest-id branch for duplicate origins; `StoredSecret` has reference equality.
- #115 deferred: reload the cached Keystore key after an in-session wipe (a safe reload must never delete a key on a transient error); zero plaintext bytes (Strings carry them anyway); tell transient resolve failures apart from absent material; batch the absent-row check; harden the reconcile-pass test probe.
- #117 deferred: a second unreadable file replaces the first `.unreadable` copy; sweep crash-orphaned `secrets*.tmp` files; test teardown joins and timeout context.
- #120 deferred: a stale batched `needsCredentials` write can land after `setCredentials`'s clear (needs a concurrent 401 for that podcast; re-entering recovers); log a preview credential dropped when origin parsing fails; "podcast missing" maps to `InvalidUrl`; unsubscribe and subscribe test scopes, joins and probes. Declined: `AuthInterceptor` degrading to no-auth before the start-up gate.
- #114 deferred: `moveFeed` keeps `pendingNewFeedUrl`/`pagingNextUrl` (the same transaction's ingest rewrites the pending target); FeedMovesTest temp-dir teardown, unbounded awaits, raw table names, request-count pin.
- #116 deferred: an adoption fault still LRU-rejects a plausible target; the duplicated deadline margin; self-target and nil-GUID probe tests.
- #118 deferred: EditFeedUrlTest's active-subscription case compares the database with itself; seed episodes and assert their ids survive the move.
- #122 deferred: a credential displaced by an edit to another origin stays until 02's `db-maintenance` orphan sweep exists (not built yet); the edit dialog's in-flight indicator and blank-URL message.
- #123 deferred: artwork and enclosure URLs still persist publisher userinfo; the IPv6 origin parse assumes a leading bracket; CredentialCleanupTest reads the global LogSink.
- #124 deferred: `setCredentials` waits for a whole running refresh, and a cancelled save is dropped (the trade-off `editFeedUrl` makes).
- #119 deferred: the nightly mutation step's comment says it leaves the job's "last 15 minutes", but setup steps run first, leaving about 11 to 13.
- #96 deferred: autofill content types on `CredentialFields` (before M1b enables them); the settings pop waiting for the removed-feed snackbar (needs a root-owned snackbar scope); the header/banner/chips duplication; two test notes.
- #94 deferred: the mark-all pending id when its tile vanishes; sort radios outside a selectable group; `NdDialogAction` parameter order; the disabled destructive tint; the red-pixel predicate also matching amber; the hand-kept pushed-key lists in the graph tests.
- M3 B10 review (`impl/m3-b10`, 2026-10-17): resolved on `impl/m3-b11` — `mergeLibrary` returns a `LibraryMergeResult` carrying its settled key/alias → local-id and staged `uuid` → survivor maps, and the episode phase attaches only through them. Still on B12's checklist: nothing in `mergeLibrary` wraps or blocks linked-device capture triggers; the B12 runner owns that wrapping.
- #93 deferred: EmptyState's gap between stacked text buttons; a tile menu already open when selection starts; the zero-duration sentinel; `ShowNotesRendererTest`'s English click label.
- #91 deferred: `DefaultArtworkStoreTest` shares a fixed `build/tmp` directory (use a temp dir); declare the `:core:common` → `:core:model` edge as `api`.
- #90 deferred: 02's `encode` comment should say the header byte precedes both size paths.
- #98 deferred: a reverse (golden without fixture) pairing check, in corpus leg (a) rather than only on the device.
- #100 deferred: state `awaitIdle`'s started-run precondition in its KDoc.
- #92 deferred: exhaustive `when` in `DescriptionCodecTest`'s tag helpers; one shared JVM codec instead of identical Android/desktop copies.
- #88 deferred: #99's `refresh-periodic` literal in `AndroidStartupRecoveryTest`.
- #88 residual policy, owner-visible: a recycled `KNOWN_INDEPENDENT` GUID claims by authority; an episode returning without an enclosure to an unproven rotated slot duplicates.

These are not blanket release approvals or completed work.

## 1. What this repository is

Neutrodyne is an open-source podcast player in development. The sources of truth are:

- [CLAUDE.md](CLAUDE.md): the owner's standing conventions. Read this first; they are binding.
- [docs/PLAN.md](docs/PLAN.md): the master plan, holding requirements (R/N-ids), decisions (D-ids), owner decisions (PO-ids), the roadmap (M-ids), risks and the glossary.
- [docs/design/](docs/design/): the design docs `01-foundation` … `09-quality-and-release`, plus `10-sync` and `11-desktop` once the replan has written them.
- [docs/research/](docs/research/): raw research notes and the change briefs used for each revision. These are non-normative.
- [tools/doccheck/](tools/doccheck/): link/anchor and Mermaid checkers. Both must report 0 problems before any push.

## 2. Owner decisions so far (chronological)

| When | Decision |
|---|---|
| 2026-10-04 | Initial ask: Android podcast player with subscription import/export, user-defined groups each viewable as its own episode feed, YouTube channels as podcasts, streaming and downloading, a nice cover-art UI. Plan pushed to `main`. |
| 2026-10-05 | **PO-1** Avoid GPL: use **yt-dlp** (Unlicense) embedded via an embedded CPython instead of NewPipe Extractor. |
| 2026-10-05 | **PO-2** Distribution: **GitHub Releases only** (no Play, F-Droid or other stores). |
| 2026-10-05 | **PO-5** **No Google developer verification** registration. |
| 2026-10-05 | **PO-8** Package/ID prefix is always **`ch.lkmc`** (`ch.lkmc.neutrodyne`). |
| 2026-10-05 | **PO-31** Update checks are **notify-only**, linking to GitHub; the user downloads and installs manually. |
| 2026-10-05 | **PO-32** YouTube engine updates are automatic, limited to versions approved by our canary. |
| 2026-10-05 | **PO-33** No beta channel. **PO-34** No mirror. |
| 2026-10-05 | **PO-35** First answered "just build debug builds", then clarified as **"no key management"**: publish optimised release builds signed with the keystore committed to the repo. |
| 2026-10-05 | **Scope expansion:** add a **self-hosted sync server** (subscriptions, listening state and similar; never audio) and **desktop targets**. |
| 2026-10-05 | **LGPL allowed.** **Everything ships in v1.0** (Android + desktop + sync server). Desktop on **Windows, macOS and Linux, unsigned**. |
| 2026-10-05 | **Stack: Kotlin Multiplatform + Compose Multiplatform** (not Flutter), with a Kotlin/Ktor server. **Desktop bundles the Java runtime** (OpenJDK, narrow licence exception, its source attached to each release). |
| 2026-10-05 | The owner added the app icon `media-sources/icon.png` (vacuum-tube "N", amber on navy) and IntelliJ project files (`.idea/`). |
| 2026-10-06 | The owner enabled **immutable releases** in the repository settings (needed for 09's `publish` job and `gh release verify`). |
| 2026-10-10 | **GUID identity history:** retain persistent feed-scoped knowledge so erased duplicate-GUID history cannot silently redirect listening state. Conservative legacy UNKNOWN behavior and actual v1→v2 migration/restart/preservation proof are required; replacement implementation is in progress. |
| 2026-10-10 | **Navigation transitions:** switching tabs is immediate; back wipes to reveal the preceding screen. The owner reported general device success but rejected the default fades; corrected-build acceptance remains pending. |

## 3. Current state

- **`main`** holds the complete plan for **Android + desktop (Windows, macOS, Linux) + self-hosted sync server, all in v1.0**, built on Kotlin Multiplatform:
  - `docs/PLAN.md`: requirements R1–R8 and N1–N13, decisions D1–D97, owner decisions PO-1–PO-48, and milestones M0a…M11b, MD0–MD5 and MS0–MS3;
  - eleven design docs, including the new `10-sync.md` and `11-desktop.md`;
  - the README.

  Link and anchor checks report 0 problems, and all 78 Mermaid diagrams parse.
- The replan ([PR #4](https://github.com/L-K-M/Neutrodyne/pull/4)) and planning-review follow-ups are merged.
- `main` now includes the KMP scaffold, Android shell, library spikes, sync-server skeleton and desktop shell. §0 tracks unmerged implementation and outstanding release checks.

### Progress log

- 2026-10-04: first Android-only plan (PR #1).
- 2026-10-05: owner decisions PO-1/2/5/8: yt-dlp, GitHub only, no verification, `ch.lkmc` (PR #2).
- 2026-10-05: owner decisions PO-31–35: notify-only updates, no beta, no mirror, debug builds (PR #3).
- 2026-10-05 16:2x–22:3x UTC: KMP + desktop + sync replan (PR #4). Steps: the lead pass (brief saved as [`docs/research/briefs/change-brief-3-kmp.md`](docs/research/briefs/change-brief-3-kmp.md)); new docs `10-sync.md` and `11-desktop.md`, each written and then reviewed; revision of `01`–`09`; two critics (42 findings); a fixer (40 fixed, 1 handed to the orchestrator for `CLAUDE.md`, 1 rejected because it targeted the scratch brief). `CLAUDE.md` is updated to match.

### Planning review — 2026-10-06

A contributor (Sol, fork `BigBoyDevBox/Neutrodyne`) opened four documentation PRs that fix review findings. The owner asked us to review them and merge as appropriate.

| PR | Topic |
|---|---|
| [#5](https://github.com/L-K-M/Neutrodyne/pull/5) | Backup timestamps and history, OPML round trips, group undo, credential-commit coordination |
| [#6](https://github.com/L-K-M/Neutrodyne/pull/6) | Safe RSS resume, download redirects, small-library YouTube outages, resolve deadlines |
| [#7](https://github.com/L-K-M/Neutrodyne/pull/7) | Archive launcher paths and the scoped PBS licence-check contract (PO-48 stays pending) |
| [#8](https://github.com/L-K-M/Neutrodyne/pull/8) | Exact sync replay, raw state, resets, rekey collisions, crash-recoverable effects |

- **Review:** each PR had its own adversarial reviewer, and a sixth agent checked how they interact once combined. The verdict for every PR was "merge with follow-ups": no blockers or majors.
- **Merged:** all four on 2026-10-06, in order 5, 6, 7, 8.
- **Follow-ups:** the reviews left 46 follow-up items: about 35 minor fixes and a few notes on changes outside each PR's stated scope. All 46 were handled in [PR #9](https://github.com/L-K-M/Neutrodyne/pull/9), on `main` since 2026-10-06; a verifier pass confirmed them and both doc checks pass. Notable additions:
  - **Control-feed check:** before a 1–2-channel library declares a YouTube outage, the app fetches two pinned control channel feeds (R3.3 amended).
  - **Credential coordinator:** extended to sync password installs and every credential delete; arrives in M1b.
  - **Staging-only `EpisodeLineV1.fc` field:** per-field clocks for first-link staging.
  - **`SyncOutboxDao.captureIntent`:** captures mark-unplayed and position resets.
  - **`LinuxDesktopEntryWriter`:** writes both the menu and autostart entries with spec-correct `Exec` quoting.
  - **PBS patches and WiX:** both stay PO-48 proposed defaults awaiting the owner.

### Open owner questions (ask these next; each has a default in `docs/PLAN.md` §4)

Most important first:
- **PO-48 Licence classes:** the final review found two cases beyond the agreed rules.
  - **WiX code (MS-RL)** is embedded in every Windows MSI. If declined, Windows ships as a ZIP only.
  - **python-build-standalone carries MPL-2.0 patches** in the desktop Python. If declined, CPython must be built without them.
- **PO-46** Desktop recovery snapshot. Default: none in v1.0; the user restores a manual backup or reconnects sync.
- **PO-47** Sync timing promises and the mass-change threshold for small libraries.
- **PO-37 / PO-38** Sync scope (what syncs; feed passwords) and server accounts. Default: admin-created accounts, devices linked by code.
- **PO-39** macOS tester builds before 1.0.0. **PO-40** Windows on Arm (default: x64 under emulation). **PO-42** Ship the JDK AOT cache for faster desktop start.
- **PO-43** Reference hardware. **PO-44** Desktop secret storage. **PO-45** Server operations defaults. **PO-41** gpodder/Open Podcast API layers (default: v1.1).
- **Older questions still on their defaults:** PO-3, 4, 6, 7, 9–21, 24–28, 30, 36.

## 4. How to resume if this session stops

1. **Refresh:** fetch, inspect worktree status and read §0 plus `docs/IMPLEMENTATION-PROMPT.md`. Preserve active helper changes.
2. **Reconcile:** inspect open PRs, latest-commit CI and completed review comments. Resume the serial GLM queue; do not duplicate running helper tasks.
3. **Implement:** follow `docs/IMPLEMENTATION-PROMPT.md`; use PLAN §7 for dependencies and acceptance criteria. Resolve important review findings test-first and record design deviations.
4. **Run the checks:** `python3 tools/doccheck/checkdocs.py .` and the Mermaid checker. Both must report 0 problems.
5. **Merge and release:** commit and push focused branches, open PRs, finish CI/review and merge through GitHub. Publish milestone tester builds only after their release gates pass.
6. **Hand back:** update this file with exact heads, completed checks, remaining gaps and owner instructions. Report unmerged work as work in progress.

## 5. Binding decisions for the replan (S0–S13)

These are the instructions the replan agents received. They hold for anyone continuing the work.

- **S0** v1.0 = Android + desktop (Windows, macOS, Linux) + self-hosted sync server, all together. The server syncs state only, never audio or feed contents; each client polls feeds itself.
- **S1** Kotlin Multiplatform + Compose Multiplatform; targets `android` and `jvm("desktop")`; no iOS (would need Apple registration) but keep `commonMain` clean. Sync server in Kotlin + Ktor, sharing protocol and merge code.
- **S2** Android stays native where it matters, with designs unchanged: Media3 MediaLibraryService, Android Auto, WorkManager + user-initiated jobs, Android 15–17 rules, Chaquopy-hosted yt-dlp in `:ytx`, Auto Backup.
- **S3** Libraries: Compose Multiplatform, Material 3 multiplatform, Navigation 3 (Google runtime + JetBrains UI), lifecycle/ViewModel KMP, Room 3 KMP with `BundledSQLiteDriver`, Paging, DataStore, Coil 3, kotlinx-serialization/datetime, Ktor client on OkHttp (or OkHttp in JVM-shared modules), DI Hilt → Metro (Koin fallback). AGP 9 `com.android.kotlin.multiplatform.library`; `:app` (Android) and `:desktopApp`; JVM-only code in plain-JVM modules.
- **S4** Desktop (unsigned, GitHub only):
  - **Platforms:** Windows 10+ x64/arm64, macOS 13+ Apple Silicon only (ad-hoc signed), Linux x64/arm64 (glibc ≥ 2.31).
  - **Formats:** MSI + ZIP, DMG, DEB + RPM + tar.gz.
  - **Build:** one CI job per OS/arch. jpackage rejects 0.x versions on macOS.
  - **Updates:** notify-only checks.
- **S5** **Bundle** a jlink-trimmed, unmodified OpenJDK. It is a narrow licence exception: attach the runtime's corresponding source to every release. No ProGuard (GPL).
- **S6** LGPL allowed. Desktop playback is our own engine:
  - minimal LGPL FFmpeg build;
  - miniaudio output;
  - a Kotlin Sonic port plus silence skipping;
  - an OkHttp streaming cache.

  libmpv is the fallback. OS integration uses SMTC (Windows), Now Playing (macOS) and MPRIS (Linux). Audio-only on desktop in v1.0.
- **S7** Desktop YouTube engine: python-build-standalone CPython (trimmed to permissive parts) running yt-dlp as a child process over JSON/stdio, with the same trust chain and update policy as Android.
- **S8** Desktop behaviour:
  - an in-process scheduler and a single-instance lock;
  - closing the window keeps the app running (tray) only while playing or downloading;
  - start at login off;
  - downloads in the app data directory;
  - the Linux screen-reader gap is accepted.
- **S9** Sync uses our own protocol v1:
  - **Merging:** per-field last-writer-wins ordered by hybrid logical clocks; changes pulled since a server cursor; tombstones.
  - **Identity and order:** stable podcast UUIDs; per-item order keys for Up next and groups.
  - **Safety rules:** a position of 0 never wins without an explicit reset or mark-played; a mass unsubscribe asks first.
  - **First link** uses the backup Merge rules.
  - **Server:** Ktor with SQLite, SSE push, admin-created accounts, devices linked with short codes, no end-to-end encryption in v1. Request Android 17's `ACCESS_LOCAL_NETWORK` only for a LAN server.
  - **Data:** synced: subscriptions, groups and their order and settings, per-episode state, Up next, podcast settings, portable globals. Basic-auth passwords sync only when the user opts in. Device-local: auto-download, storage, notifications, refresh, appearance.
- **S10** Server distribution: a JAR (admin installs Java 21) plus a multi-arch GHCR image (counts as GitHub) and a reference docker-compose. Same release tags as the apps.
- **S11** Android publishes optimised release builds (R8, non-debuggable) signed with the committed keystore. The debug build type is for development only. Retire the debuggable-build risks; the public-key spoofing risk remains.
- **S12** Brand: `media-sources/icon.png`, seed colours ≈ amber `#FF8C1A` and navy `#0B1B2E`. Adaptive and monochrome Android icons and desktop ICO/ICNS/PNG are derived from it.
- **S13** Licensing: own code Unlicense; no GPL/AGPL except the S5 runtime exception; LGPL allowed (dynamically linked, notices and source offered); MPL-2.0 for unmodified files and data. Every component is listed with its licence, and CI licence checks cover Gradle, Python and native desktop libraries.

## 6. After the replan

- **Handing implementation to another agent:** use the ready-made prompt in [`docs/IMPLEMENTATION-PROMPT.md`](docs/IMPLEMENTATION-PROMPT.md).

- **Owner questions:** collect the open PO questions from `docs/PLAN.md` §4 and ask the owner. Each already has a default.
- **Implementation:** start with **M0** (scaffold and CI) per `docs/PLAN.md` §7 and `docs/design/01-foundation.md` "M0 scaffold checklist". Build Kotlin-Multiplatform-structured from the first commit.
- **Effort:** about 58–97 engineer-weeks for everything in v1.0, roughly 13–22 months solo. This is a planning estimate from `docs/research/2026-10-05-stack-choice/kmp-fit.md`.
