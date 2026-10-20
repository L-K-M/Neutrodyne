# M6b I2 integration report

Date: 2026-10-19. Worktree: /home/paseo/nd-wt/m6b-p1.
Branch: impl/m6b-p1. Starting HEAD: 98793c45.
Both requested branches are merged locally. The final integration fixes,
formatting and this ledger are committed together after verification.
No helper CLI or agent was launched. No push, PR, CI dispatch or external
review was performed; external review rounds completed: 0. The explicit
local-only instruction replaces the usual PR/main end state.

## Commits and merge order

| Commit | Result |
|---|---|
| `138053e3` | Merge completed M6a `38f139e2` into the M6b stack; verify before W9 |
| `9d6c1e9d` | Merge M6b W9 `f17a9f4d`, retargeting its UI onto M6a |
| Final report commit | Shared folder/prompt fixes, formatting, documentation and receipts |

Both 38f139e2 and f17a9f4d are ancestors of the final branch.
W6 was not merged or implemented. Its existing beforeRunnerReset seam remains
available to impl/m6b-w6; no Task Manager inference is claimed here.
No worktree or branch was created for this task, so none needs removal.

## Step 1 conflicts: four files

- `.github/workflows/nightly.yml`: retain the M6b system-tests job,
  benchmarkRelease smoke/release legs and uploads alongside M6a's downloads
  dispatch, E4/worker/UIDT evidence assertions and bmgr job. Remove only the
  superseded M6b release-leg TODO.
- `download/impl/.../DownloadComponents.kt`: combine M6b's charging/network
  observation, planner, cleanup, mover, quota, busy and reset seams with M6a's
  bounded chapter enrichment, lifecycle owner, provider and IO dispatcher.
- `download/impl/.../DesktopDownloadBindings.kt`: keep NetworkMonitor for both
  lanes and the IO dispatcher imports/consumers for LocalMediaReadiness.
- `download/impl/.../DownloadReconcilerTest.kt`: use M6a's shared ReconcileRig
  storage/controller, adding M6b's required DownloadQuota dependency there.

Auto-merges retained M6a playback restoration, chapter loading, pre-cascade
cleanup and episode-merge ownership, current-file deletion deferral and index
readiness. They also retained M6b's active-part protection, cleanup, moves,
manual/AUTO scheduling and desktop lanes. The controller, storage, engine,
reconciler and both platform graphs were checked together by tests/builds.
M6a's core:testing lifecycle/progress fixtures and M6b's contracts coexist.
Dated notes in 05/07/08/09 and HANDOFF were retained, with current integration
notes qualifying historical milestone-only limitations.

Step 1: spotlessApply passed. The following selected tests passed before the
merge commit and before beginning step 2: **2,162 passed, 1 skipped, 0 failed**.
Android and desktop app compilation, checkBannedApis and assertModuleGraph
also passed. Receipt: m6b-i2-step1-tests.log, BUILD SUCCESSFUL in 6m 2s.

| Task | Passed | Skipped | Failed/errors |
|---|---:|---:|---:|
| `:core:data:desktopTest` | 1260 | 1 | 0 |
| `:core:testing:desktopTest` | 81 | 0 | 0 |
| `:download:impl:desktopTest` | 190 | 0 | 0 |
| `:download:impl:testAndroidHostTest` | 298 | 0 | 0 |
| `:feature:downloads:desktopTest` | 11 | 0 | 0 |
| `:feature:settings:desktopTest` | 40 | 0 | 0 |
| `:playback:core:desktopTest` | 121 | 0 | 0 |
| `:playback:impl:testDebugUnitTest` | 161 | 0 | 0 |

## Step 2 conflicts: sixteen files

| Files | Resolution |
|---|---|
| `core/testing/.../FakeDownloadController.kt` | Keep independent registration/move results; MOVE_EXISTING reads moveResult; replace removed Unsupported with Unavailable |
| `core/ui/.../platform/PlatformActions.kt`, Android implementation | Keep M6a's SystemSettingsOpener and targets; remove W9's duplicate API and retarget its settings-action assertions |
| `docs/design/08-ui-ux.md` | Preserve M6a's dated history; replace W9's missing-UI claim with the actual integrated state |
| `feature/downloads/.../DownloadsRoute.kt`, `DownloadsViewModel.kt` | Keep M6a's real route/VM, selection, playback, manual consent and visible progress; attach W9 notices and guarded orphan dialog |
| `feature/feeds/.../FeedsRoute.kt`, `FeedsViewModel.kt` | Retain both manual and Download-all handlers/dialogs, sharing the DownloadController constructor argument |
| `feature/feeds/.../FeedsViewModelTest.kt`, `AllGroupsSheetTest.kt`, `FeedsPagingContextTest.kt`, `FeedsScreenTest.kt` | Keep M6a's named fake-controller constructor arguments and its manual-entry assertions |
| `feature/podcast/.../PodcastRoute.kt`, `PodcastViewModel.kt`, `PodcastViewModelTest.kt` | Retain both manual and bulk actions with one controller; keep existing fake and regression coverage |
| `feature/settings/.../SettingsHomeRoute.kt` | One Downloads entry plus desktop folder entry; remove the silently duplicated Downloads row |

Other semantic integration fixes:

- Remove the duplicate DOWNLOADS dispatch case and duplicate English
  downloads_settings resource. Keep M6a's "Download settings" label.
- Reuse M6a's manual mobile-data/root settings rows inside W9's complete settings
  page. Android root rows use W9's move-or-leave confirmation, as desktop folder
  changes do. Remove W9's forwarding-only registration wrapper and stale
  RootChangeResult.Unsupported branch; the real W4 controller owns the work.
- Use W9's shared ordered/max-two/platform-aware notices on M6a's actual screen.
  Preserve manual selection/deletion confirmation, Play all/row playback,
  download consent, progress overlays and live observer lifetimes.
- Keep orphan confirmation visible on failure; prevent duplicate cleanup while
  working; expose read-only working/error state to the route.
- Reveal completed desktop files through the existing LocalMediaIndex URI and
  RevealInFolder mechanism, rather than W9's desktop-null shareableFile route.
  Android Share file remains available through the original handler.
- Episode DownloadToggle is already implemented by the M6a merge. Its actual
  screen and request-handler wiring, and existing VM regression, are retained
  for ReleaseSmokeJourneyTest; no artificial download bypass was added.
- After review, add two regressions and observe both fail: storage lookup chose
  /default instead of selected /custom; bulk download failed to activate the
  contextual notification prompt. Fix the shared selected-root lookup and hand
  bulk notification consent to M6a's existing handler in feeds and podcast.
  Remove W9's duplicate direct permission launch. Both regressions pass in the
  complete affected suites, including Android-host coverage.

## Full gate and final validation

Every Gradle command used JDK21, ANDROID_HOME, LANG/LC_ALL C.UTF-8 and
~/.local/gfx/env.sh, --no-daemon, -Pkotlin.incremental=false and
-Pkotlin.compiler.execution.strategy=in-process. Builds were sequential.
spotlessApply ran alone before test commands. Test runs used --continue and
--no-build-cache to prevent restoration of stale result XML.

The requested FULL gate ran **once** with --max-workers=2:

```sh
./gradlew --no-daemon --max-workers=2 --no-build-cache \
  -Pkotlin.incremental=false \
  -Pkotlin.compiler.execution.strategy=in-process \
  allTests test mutationTest :app:compileDebugAndroidTestKotlin \
  :benchmark:assembleBenchmarkRelease spotlessCheck :app:lintRelease \
  assertModuleGraph checkBannedApis :app:compileDebugKotlin \
  :desktopApp:compileKotlin --continue
```

assembleBenchmarkRelease is the actual benchmark task discovered via tasks
--all; no nonexistent benchmark assemble task was claimed.

The aggregate ended after 14m 52s with one failure: allTests had 6,557 passes
and 1 skip; test had 795 passes, 2 skips and 1 failure; mutationTest had 40
passes. The only failed task was playback:impl:testDebugUnitTest. All compile,
benchmark assembly and static gates completed successfully under --continue.
Release lint reported zero errors and zero warnings.

### Loaded-run failure and reruns

SmartResumeTest.coldProjectionAndArrivalsReadTheLiveSmartResumeSetting failed
at SmartResumeTest.kt:137 with "Adapter did not converge" (4.499 seconds).
It passed unchanged as a single isolated test (39-second Gradle command), then
all **161/161** playback adapter cases passed unchanged with --max-workers=1
(46 seconds). The same suite had passed before step 2. This is evidence of
scheduling sensitivity in the larger run, not proof of a production root
cause. No playback code, test assertion or timing threshold was changed.
No other load-only failure, daemon failure or lock retry occurred in this run.
Original failure, isolated XML and serial-suite receipts are retained under
.nd-context/m6b-i2-*. No failed result was counted as a pass.

The two intentional UI regressions failed before their fixes in
m6b-i2-regressions-red.log. After fixing them and running spotlessApply alone,
the complete affected UI suites and all affected static/compile gates passed
in **3m 21s** (m6b-i2-final-affected-gate.log). This reran core:ui desktop and
Android-host tests, downloads, feeds, podcast and settings desktop tests;
app instrumented-test/debug and desktop compilation; benchmark assembly;
spotlessCheck, release lint, module graph and banned APIs.

A final separate opt-in command passed in **52s** with
-Pneutrodyne.systemTests=true: feature:feeds:testAndroidHostTest (including the
new bulk-prompt regression), app:compileBenchmarkReleaseKotlin and
benchmark:assembleBenchmarkRelease. The opt-in disables other app variants,
so it was deliberately not mixed into the normal full gate. This compiles the
fixture driver used by CI; it is not device or published-release evidence.

### Final per-task counts

Counts combine unchanged suites from the one full gate with the latest complete
suite reruns after fixes; they are **not** a claim that a second full aggregate
ran green. All current JUnit XML was inspected: zero failures/errors. Current
UP-TO-DATE suite reports are included, and partial filtered red/isolated reports
were replaced by complete green task reports before counting.

| Selector | Passed | Skipped | Failed/errors |
|---|---:|---:|---:|
| allTests | 6,561 | 1 | 0 |
| test | 796 | 2 | 0 |
| mutationTest | 40 | 0 | 0 |
| Total | 7,397 | 3 | 0 |

Skips: Windows-only DPAPI round trip; German and English desktop file-menu
picker cases. The sync protocol mutation task has no cases, supplies no
mutation evidence and adds no skipped test case to the counts.

| Task | Passed | Skipped | Failed/errors |
|---|---:|---:|---:|
| `:app:testDebugUnitTest` | 154 | 0 | 0 |
| `:core:artwork:desktopTest` | 25 | 0 | 0 |
| `:core:artwork:testAndroidHostTest` | 43 | 0 | 0 |
| `:core:common:desktopTest` | 100 | 0 | 0 |
| `:core:data:desktopTest` | 1260 | 1 | 0 |
| `:core:data:mutationTest` | 1 | 0 | 0 |
| `:core:data:testAndroidHostTest` | 139 | 0 | 0 |
| `:core:database:desktopTest` | 171 | 0 | 0 |
| `:core:database:testAndroidHostTest` | 9 | 0 | 0 |
| `:core:datastore:desktopTest` | 18 | 0 | 0 |
| `:core:designsystem:desktopTest` | 14 | 0 | 0 |
| `:core:domain:desktopTest` | 3 | 0 | 0 |
| `:core:model:desktopTest` | 2553 | 0 | 0 |
| `:core:navigation:desktopTest` | 25 | 0 | 0 |
| `:core:network:desktopTest` | 33 | 0 | 0 |
| `:core:network:testAndroidHostTest` | 6 | 0 | 0 |
| `:core:network:okhttp:test` | 45 | 0 | 0 |
| `:core:testing:desktopTest` | 81 | 0 | 0 |
| `:core:ui:desktopTest` | 183 | 0 | 0 |
| `:core:ui:testAndroidHostTest` | 151 | 0 | 0 |
| `:desktop:system:test` | 1 | 0 | 0 |
| `:desktopApp:test` | 120 | 2 | 0 |
| `:download:api:desktopTest` | 1 | 0 | 0 |
| `:download:impl:desktopTest` | 190 | 0 | 0 |
| `:download:impl:testAndroidHostTest` | 298 | 0 | 0 |
| `:feature:discover:desktopTest` | 40 | 0 | 0 |
| `:feature:downloads:desktopTest` | 12 | 0 | 0 |
| `:feature:episode:desktopTest` | 50 | 0 | 0 |
| `:feature:feeds:desktopTest` | 119 | 0 | 0 |
| `:feature:feeds:testAndroidHostTest` | 72 | 0 | 0 |
| `:feature:groups:desktopTest` | 84 | 0 | 0 |
| `:feature:importexport:desktopTest` | 191 | 0 | 0 |
| `:feature:library:desktopTest` | 57 | 0 | 0 |
| `:feature:player:desktopTest` | 67 | 0 | 0 |
| `:feature:podcast:desktopTest` | 112 | 0 | 0 |
| `:feature:queue:desktopTest` | 19 | 0 | 0 |
| `:feature:settings:desktopTest` | 45 | 0 | 0 |
| `:feature:sync:desktopTest` | 1 | 0 | 0 |
| `:feeds:desktopTest` | 215 | 0 | 0 |
| `:feeds:jvm:mutationTest` | 38 | 0 | 0 |
| `:feeds:jvm:test` | 252 | 0 | 0 |
| `:playback:api:desktopTest` | 1 | 0 | 0 |
| `:playback:core:desktopTest` | 121 | 0 | 0 |
| `:playback:desktop:test` | 1 | 0 | 0 |
| `:playback:engine:test` | 1 | 0 | 0 |
| `:playback:impl:testDebugUnitTest` | 161 | 0 | 0 |
| `:playback:native:test` | 1 | 0 | 0 |
| `:sync:api:desktopTest` | 1 | 0 | 0 |
| `:sync:impl:desktopTest` | 1 | 0 | 0 |
| `:sync:protocol:desktopTest` | 27 | 0 | 0 |
| `:sync:server:test` | 58 | 0 | 0 |
| `:youtube:api:desktopTest` | 22 | 0 | 0 |
| `:youtube:api:mutationTest` | 1 | 0 | 0 |
| `:youtube:engine:test` | 1 | 0 | 0 |
| `:youtube:impl:desktopTest` | 1 | 0 | 0 |
| `:youtube:ytdlp-desktop:test` | 1 | 0 | 0 |
| `:sync:protocol:mutationTest` (no cases) | 0 | 0 | 0 |

Other verification:

- Python evidence fixture suites: assert-download-tests 4/4,
  assert-playback-tests 7/7, assert-system-tests 11/11; no skips.
- Workflow YAML parses. All six CI and eight nightly jobs from both W10
  branches are preserved; their job-name union was explicitly checked.
- bash -n passes for bmgr-check.sh and system-tests.sh.
- Documentation: checkdocs.py reports 0 problems across 14 files;
  mmdcheck.mjs reports 0 invalid of 78 diagrams.
- git diff --check passes. No schema JSON, entity or index change relative to
  98793c45. No new dependency, domain interface, D-id or licence exception.

## Documentation and design/code contradictions

- 07's M6a lifecycle note and M6b planner/cleanup/mover/runner notes coexist.
  Both milestones' 05/07/08/09/HANDOFF dated evidence was preserved.
- 08's dated M6b implementation note now describes the actual M6a screen and
  controls, replacing W9's obsolete "missing M6a UI" claim and qualifying the
  historical M6a-only hiding of automatic controls. It documents move/root
  integration, indexed desktop reveal, contextual permission reuse and the
  selected-folder lookup. M6a's permission-denial and desktop SYSTEM wording
  qualifications remain intact.
- 09's dated M6b W10 note no longer claims DownloadToggle is a no-op. It records
  the real M6a entry point and unified M6a/M6b evidence wiring. The existing
  benchmarkRelease fallback remains explicit; no published-release execution
  is inferred. HANDOFF now leads with I2's state and the validation gap.
- W9's Unsupported contract and duplicate platform/settings implementations
  were stale against W4/M6a. Code was adapted to those real contracts rather
  than reintroducing unsupported results or parallel UI surfaces.
- W9's direct bulk permission launcher conflicted with M6a's contextual prompt
  and denied-permission guidance. The existing handler now owns that flow.
- MOVE_IN_PROGRESS still uses "Moving downloads…" because the overview has no
  move counters. No fabricated progress fields or schema changes were added.

## Remaining gaps and limits

- W6 Task Manager inference is intentionally excluded and remains parallel work.
- No instrumented/device tests executed here: 0 device cases claimed.
  E4/E10, optimized-release and API33/34/36/37 UIDT/worker journeys, real process
  kill/reboot, Task Manager/Recents, network/Data Saver/restricted-bucket,
  physical storage remount/ENOSPC, Share consumers, bmgr transport and packaged
  desktop acceptance remain unexecuted by this task.
- MD1 still blocks audible desktop offline acceptance. benchmarkRelease
  compilation/assembly does not establish published-release APK behavior.
- The Smart Resume loaded-run timeout remains disclosed despite green isolated
  and full serial reruns; no assertion or timeout was weakened to hide it.
- No remote CI or external review ran. Both merges and final fixes are local
  on impl/m6b-p1; nothing was pushed, as instructed.

## Changed files

156 files relative to 98793c45, including this report. The imported
M6a/W9 implementations account for most of the patch; this is integration, not
a reimplementation of their work items.

- `.github/workflows/ci.yml`
- `.github/workflows/nightly.yml`
- `.nd-context/m6b-i2-report.md`
- `HANDOFF.md`
- `app/src/androidTest/kotlin/ch/lkmc/neutrodyne/DownloadJourneyTest.kt`
- `app/src/main/kotlin/ch/lkmc/neutrodyne/MainActivity.kt`
- `app/src/main/kotlin/ch/lkmc/neutrodyne/di/AndroidAppGraph.kt`
- `core/data/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/core/data/AndroidTestSupport.kt`
- `core/data/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/core/data/backup/FirstLaunchRestoreInitializerTest.kt`
- `core/data/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/core/data/backup/RestoreWorkerTest.kt`
- `core/data/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/data/backup/RestoreMerger.kt`
- `core/data/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/data/playback/ChapterRepositoryImpl.kt`
- `core/data/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/data/repo/EpisodeLiveStateSourceImpl.kt`
- `core/data/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/data/repo/PodcastMerger.kt`
- `core/data/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/data/repo/PodcastRepositoryImpl.kt`
- `core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/ChapterRepositoryTest.kt`
- `core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/EditFeedUrlTest.kt`
- `core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/EpisodeLiveStateSourceTest.kt`
- `core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/FeedMovesTest.kt`
- `core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/ImportCredentialOrderTest.kt`
- `core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/PodcastMergeTest.kt`
- `core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/PodcastRepositoryTest.kt`
- `core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/SyncInertTest.kt`
- `core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/TestSupport.kt`
- `core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/UnsubscribeUseCaseTest.kt`
- `core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/backup/BackupCrossPlatformTest.kt`
- `core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/backup/BackupRepositoryImplTest.kt`
- `core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/backup/BackupStagerTest.kt`
- `core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/backup/RestoreEpisodeMergerTest.kt`
- `core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/backup/RestoreMergerTest.kt`
- `core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/backup/RestoreRunnerTest.kt`
- `core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/importing/DesktopImportBackupLaneTest.kt`
- `core/designsystem/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/designsystem/components/NdNavigationSuiteScaffold.kt`
- `core/domain/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/domain/DownloadLifecycle.kt`
- `core/domain/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/domain/RestoreDownloadRequests.kt`
- `core/model/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/model/FeedFilterCapabilities.kt`
- `core/model/src/commonTest/kotlin/ch/lkmc/neutrodyne/core/model/FeedFilterCapabilitiesTest.kt`
- `core/testing/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/testing/DownloadFixtures.kt`
- `core/testing/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/testing/FakeDownloadController.kt`
- `core/testing/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/testing/FakeDownloadLifecycle.kt`
- `core/testing/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/testing/FakeDownloadProgressSource.kt`
- `core/testing/src/commonTest/kotlin/ch/lkmc/neutrodyne/core/testing/RestoreDownloadRequestsTest.kt`
- `core/ui/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/core/ui/platform/NotificationPermissionRequesterTest.kt`
- `core/ui/src/androidMain/kotlin/ch/lkmc/neutrodyne/core/ui/platform/PlatformActions.android.kt`
- `core/ui/src/commonMain/composeResources/values-de/strings.xml`
- `core/ui/src/commonMain/composeResources/values/downloads.xml`
- `core/ui/src/commonMain/composeResources/values/strings.xml`
- `core/ui/src/commonMain/composeResources/values/w8_downloads.xml`
- `core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/AttributionText.kt`
- `core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadAllController.kt`
- `core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadAllDialog.kt`
- `core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadDialogs.kt`
- `core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadNotices.kt`
- `core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadRequestHandler.kt`
- `core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadSettingsRows.kt`
- `core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadUiText.kt`
- `core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/EpisodeRow.kt`
- `core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/OrphanFilesDialog.kt`
- `core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/PointerMenus.kt`
- `core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/ReportVisibleEpisodeIds.kt`
- `core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/StringMappers.kt`
- `core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/platform/PlatformActions.kt`
- `core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/root/NeutrodyneRoot.kt`
- `core/ui/src/commonTest/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadAllControllerTest.kt`
- `core/ui/src/commonTest/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadRequestHandlerTest.kt`
- `core/ui/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadSettingsRowsTest.kt`
- `core/ui/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadsScreenStringsTest.kt`
- `core/ui/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/ui/EpisodeRowTest.kt`
- `core/ui/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/ui/FeedFilterChipsTest.kt`
- `core/ui/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/ui/ReportVisibleEpisodeIdsTest.kt`
- `core/ui/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/ui/root/NeutrodyneRootTest.kt`
- `desktopApp/src/main/kotlin/ch/lkmc/neutrodyne/desktop/DesktopShell.kt`
- `desktopApp/src/main/kotlin/ch/lkmc/neutrodyne/desktop/window/NeutrodyneWindow.kt`
- `docs/design/05-groups-opml-backup.md`
- `docs/design/06-playback.md`
- `docs/design/07-downloads.md`
- `docs/design/08-ui-ux.md`
- `docs/design/09-quality-and-release.md`
- `download/api/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/api/LocalMediaChanges.kt`
- `download/impl/src/androidMain/kotlin/ch/lkmc/neutrodyne/download/impl/AndroidDownloadBindings.kt`
- `download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadComponents.kt`
- `download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadLifecycleImpl.kt`
- `download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadReconciler.kt`
- `download/impl/src/desktopMain/kotlin/ch/lkmc/neutrodyne/download/impl/DesktopDownloadBindings.kt`
- `download/impl/src/desktopTest/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadReconcilerTest.kt`
- `feature/downloads/build.gradle.kts`
- `feature/downloads/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/downloads/DownloadsRoute.kt`
- `feature/downloads/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/downloads/DownloadsScreen.kt`
- `feature/downloads/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/downloads/DownloadsViewModel.kt`
- `feature/downloads/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/downloads/DownloadsViewModelTest.kt`
- `feature/downloads/src/desktopTest/kotlin/ch/lkmc/neutrodyne/feature/downloads/DownloadsScreenTest.kt`
- `feature/episode/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/episode/EpisodeRoute.kt`
- `feature/episode/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/episode/EpisodeScreen.kt`
- `feature/episode/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/episode/EpisodeViewModel.kt`
- `feature/episode/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/episode/EpisodeViewModelTest.kt`
- `feature/episode/src/desktopTest/kotlin/ch/lkmc/neutrodyne/feature/episode/EpisodeScreenTest.kt`
- `feature/feeds/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/feeds/FeedsPager.kt`
- `feature/feeds/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/feeds/FeedsRoute.kt`
- `feature/feeds/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/feeds/FeedsScreen.kt`
- `feature/feeds/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/feeds/FeedsViewModel.kt`
- `feature/feeds/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/feeds/FeedsViewModelTest.kt`
- `feature/feeds/src/desktopTest/kotlin/ch/lkmc/neutrodyne/feature/feeds/AllGroupsSheetTest.kt`
- `feature/feeds/src/desktopTest/kotlin/ch/lkmc/neutrodyne/feature/feeds/FeedsPagingContextTest.kt`
- `feature/feeds/src/desktopTest/kotlin/ch/lkmc/neutrodyne/feature/feeds/FeedsScreenTest.kt`
- `feature/groups/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/groups/GroupSettingsRoute.kt`
- `feature/groups/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/groups/GroupSettingsScreen.kt`
- `feature/groups/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/groups/GroupSettingsViewModel.kt`
- `feature/groups/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/groups/GroupSettingsViewModelTest.kt`
- `feature/groups/src/desktopTest/kotlin/ch/lkmc/neutrodyne/feature/groups/GroupSettingsScreenTest.kt`
- `feature/importexport/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/importexport/RestoreRoute.kt`
- `feature/importexport/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/importexport/RestoreViewModel.kt`
- `feature/importexport/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/importexport/RestoreProgressOwnershipTest.kt`
- `feature/importexport/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/importexport/RestoreViewModelTest.kt`
- `feature/importexport/src/desktopTest/kotlin/ch/lkmc/neutrodyne/feature/importexport/ImportRouteTest.kt`
- `feature/player/src/commonMain/composeResources/values-de/player.xml`
- `feature/player/src/commonMain/composeResources/values/player.xml`
- `feature/podcast/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastEffectiveSettings.kt`
- `feature/podcast/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastRoute.kt`
- `feature/podcast/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastScreen.kt`
- `feature/podcast/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastSettingsRoute.kt`
- `feature/podcast/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastSettingsScreen.kt`
- `feature/podcast/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastSettingsViewModel.kt`
- `feature/podcast/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastViewModel.kt`
- `feature/podcast/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastSettingsViewModelTest.kt`
- `feature/podcast/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastViewModelTest.kt`
- `feature/queue/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/queue/UpNextViewModelTest.kt`
- `feature/settings/build.gradle.kts`
- `feature/settings/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/settings/DownloadSettingsViewModel.kt`
- `feature/settings/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/settings/DownloadsPage.kt`
- `feature/settings/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/settings/DownloadsSettingsPage.kt`
- `feature/settings/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/settings/DownloadsSettingsViewModel.kt`
- `feature/settings/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/settings/SettingsHomeRoute.kt`
- `feature/settings/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/settings/SettingsRoute.kt`
- `feature/settings/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/settings/DownloadSettingsViewModelTest.kt`
- `feature/settings/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/settings/DownloadsSettingsViewModelTest.kt`
- `playback/core/src/commonMain/kotlin/ch/lkmc/neutrodyne/playback/core/PlayStarter.kt`
- `playback/core/src/commonTest/kotlin/ch/lkmc/neutrodyne/playback/core/PlayStarterTest.kt`
- `playback/impl/build.gradle.kts`
- `playback/impl/src/main/kotlin/ch/lkmc/neutrodyne/playback/impl/AndroidServiceBridge.kt`
- `playback/impl/src/main/kotlin/ch/lkmc/neutrodyne/playback/impl/Chapters.kt`
- `playback/impl/src/main/kotlin/ch/lkmc/neutrodyne/playback/impl/CustomCommands.kt`
- `playback/impl/src/main/kotlin/ch/lkmc/neutrodyne/playback/impl/ErrorRecovery.kt`
- `playback/impl/src/main/kotlin/ch/lkmc/neutrodyne/playback/impl/MediaLibraryTree.kt`
- `playback/impl/src/main/kotlin/ch/lkmc/neutrodyne/playback/impl/MetadataRetrieverChapterReader.kt`
- `playback/impl/src/main/kotlin/ch/lkmc/neutrodyne/playback/impl/PlaybackControllerImpl.kt`
- `playback/impl/src/main/kotlin/ch/lkmc/neutrodyne/playback/impl/PlaybackServiceAccess.kt`
- `playback/impl/src/main/kotlin/ch/lkmc/neutrodyne/playback/impl/QueueProjector.kt`
- `playback/impl/src/test/kotlin/ch/lkmc/neutrodyne/playback/impl/CarLibraryTest.kt`
- `playback/impl/src/test/kotlin/ch/lkmc/neutrodyne/playback/impl/ChapterFixturesTest.kt`
- `playback/impl/src/test/kotlin/ch/lkmc/neutrodyne/playback/impl/ErrorRecoveryTest.kt`
- `playback/impl/src/test/kotlin/ch/lkmc/neutrodyne/playback/impl/LifecycleHarness.kt`
- `playback/impl/src/test/kotlin/ch/lkmc/neutrodyne/playback/impl/PlaybackAdapterTestSupport.kt`
- `playback/impl/src/test/kotlin/ch/lkmc/neutrodyne/playback/impl/QueueProjectorTest.kt`
- `scripts/ci/assert-playback-tests.py`
- `scripts/ci/bmgr-check.sh`
- `scripts/ci/tests/assert-download-tests.test.py`
