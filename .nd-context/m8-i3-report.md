# M8 I3 download integration report

Date: 2026-10-19. Branch: `impl/m8-p1`. Starting M8 tip: `e1ebc114`.
Requested M6b tip merged: `8ab74ff0f025df46f7614629a074cff05fc089c7`.
M6b W11 is excluded, including any later sibling updates. The requested
tip already includes Task Manager W6 (`f945bae6`, merged by `8ab74ff0`);
older HANDOFF text saying W6 was excluded describes the earlier I2 run.
Local work only; no push, PR, remote CI, external review, helpers or agents.

## Download merge conflicts and resolutions

11 conflicted files, resolved by preserving both additions:

| File / area | Resolution |
|---|---|
| DataBindings | Keep directory provider/client and ingestion event port bindings. |
| PodcastDao | Keep DirectorySubscription and AutoDownloadWatermark projections and methods; no entity/index/schema edit. |
| AllSettingKeys | Register discovery, YouTube and downloads; compose all type/range validators. |
| core/ui strings, en/de | Keep both resource sets, verify no duplicate names per locale. |
| PlatformActions | Retain Apple link settings and download system settings ports. |
| NeutrodyneWindow | Keep row artwork and failed-download flows through both content layers. |
| FeedsRoute | Retain suggestions, manual download and Download all dialogs. |
| PodcastSettingsScreen | Retain variants callbacks and scoped download controls. |
| PodcastSettingsViewModel | Retain variant writes and download edits. |
| SettingsRoute | Retain Discover/Privacy and Downloads/Desktop pages. |

Shell graphs, core:testing fakes, workflow jobs/selectors/evidence scripts,
Gradle catalog and owning docs auto-merged. Every original dated
Implementation-note heading survives; no duplicate long note paragraph or
resource name was found in the merged parents.

## M6 versus frozen M8 contracts: W8 follow-up

| Boundary | Landed code and assessment |
|---|---|
| Request | DownloadControllerImpl.Source.rejection rejects every non-RSS source with YOUTUBE_NOT_SUPPORTED. Matches M8 external mode. |
| Planner | AutoDownloadPlannerImpl rejects NotSupported, queries with youtubeDownloads=false and inserts RSS only. Preserves existing channel rows/watermarks. Matches the no-new-download contract. |
| Claim | DownloadComponents fixes Conditions.youtubeAllowed=false; candidates/claimNext exclude SourceKind.YOUTUBE. Low-level DownloadDao.claim itself only checks state/token and trusts its caller; there is no joined podcast source check. W8 must assess malformed/restored RSS-tagged channel rows and direct API refusal coverage. No guard added here. |
| Bulk | FeedRepositoryImpl.downloadAllEstimate uses the live downloads capability; FeedQueryBuilder.downloadAll gates podcast source even if an enclosure exists. Matches M8 with all capabilities false. Future capability=true estimates and RSS-only controller deliberately differ until M9/MD3. |
| Restore | RestoreDownloadRequests.eligible selects RSS only, even if a channel has a local/enclosure record. Matches M8. |
| INITIAL / D67 | Both trigger hosts skip initialFetch events; planner uses eligibility watermarks and existing no-backfill tests. M8 integrated real-graph zero-INITIAL-download proof remains W8. |
| Settings / UI mismatch | EffectiveSettingsResolverImpl emits NotSupported for external channels, but ScopeSettingsRepositoryImpl.observePodcast resolves ordinary scoped values without capabilities. PodcastSettingsScreen passes that view to DownloadSettingsRows, whose hiding depends on NotSupported. Thus channel auto controls can remain visible/editable despite runtime refusal. PodcastScreen also exposes Download all unconditionally. W8 must connect capability-aware scoped presentation and hide those actions, with mixed-group/browser mark-played/Undo/E8 coverage. These pre-existing M6 versus M8 gaps were not fixed here. |
| Capabilities | Both shells retain named external implementations and NOT_YET_AVAILABLE. Runtime has only RSS transfer source. No engine/dependency added. |

## Missing M7 baseline work

The provided starting tip did not contain `1736ff99` (Discover/link entry)
or `025cebff` (M7 evidence), despite the stated complete-M7 baseline.
To satisfy the explicit preservation of the discovery gate, the existing
M7 delta from shared ancestor `e73120eb` through `025cebff` was integrated
using a three-way patch. No new M7 feature was designed. Applying W8 alone
was first attempted and rejected atomically because its prerequisite files
were absent; that attempt changed no files.

Nine additional conflicts: `.editorconfig` (union composition locals),
CI (both script suites), nightly (both gate sets; exclude download-only from
discovery assertion), HANDOFF (all histories), Android graph (both ports),
Android graph tests (both methods), data build (all shared test roots),
GroupFeedJourneyTest (retain readiness and category assertion; one tab tap),
09 (retain M8 fixture note and M7 corrected directory location).

## Documentation

Added dated M8 integration notes to 04 capability consumers, 09 recorded
responses and HANDOFF. All prior dated notes are retained. Existing adopted
resolutions 1–8 are preserved; resolution 9's future feed-only canary is W9,
not implemented by I3. No new D-id or design deviation is introduced.

## Gate failures and focused repairs

The full gate ran once, unchanged while running, and returned failure after
11m43s (1,374 actionable tasks: 1,141 executed, 184 cached, 49 up-to-date).
Its completed XML suites recorded 7,466 passed / 1 skipped / 1 failed.
It exposed two root causes, reported as three Gradle failures:

1. `SearchRepositoryTest.partialAndAllProviderFailuresFinishWithoutLeakingErrors`
   crashed in `FakeSettingsRepository.get`: concurrent directory providers
   append to a plain ArrayList. This pre-existing M7 fake race is exposed by
   the combined gate, not an RSS/download behavior defect. A focused new
   `FakeSettingsRepositoryTest.concurrentSettingsCallsAreAllRecorded` failed
   before the fix: 2,000 reads expected, 1,957 logged. The fake now uses an
   atomically updated persistent list; its public log still returns a stable
   List snapshot and the existing order/contract tests are retained.
2. M7's new `PreviewSubscribeErrorText` predates M8's
   `SubscribeError.YouTubeNotYetAvailable`. Its exhaustive when failed both
   Android and desktop compilation. Add the existing YouTube-unavailable
   resource mapping, matching Add's existing mapper; no new UI behavior or
   W8 action flow is introduced. The compiler failure is the regression
   proof for this missing enum/sealed-result branch.

Initial full-gate receipts: `.nd-context/m8-i3-full-gate.log`,
`.nd-context/m8-i3-initial-counts.json`; red regression receipt:
`.nd-context/m8-i3-fake-red.log`. These raw operator receipts stay local.

## Verification

All affected checks passed after the two focused repairs. The full gate was
not rerun; the original failed run and its focused follow-up are distinguished
above. Final retained XML across those runs: **7,947 passed / 3 skipped /
0 failed**. Counts are per final leaf task, not sums across repeated runs.
The new regression is included once; its expected red run is excluded.

The focused repair gate passed in 5m41s: 1,137 actionable tasks, 129 executed
and 1,008 up-to-date. The complete fake suite (92 cases), complete data host
suites (1,390 desktop passes + 1 skip, 176 Android host passes), podcast
suites (127 desktop, 69 Android host), Android app host suite (160) and desktop
app suite (123 passes + 2 skips) all pass.

All Gradle commands used JDK 21, ANDROID_HOME=$HOME/android-sdk,
LANG=C.UTF-8, LC_ALL=C.UTF-8 and the sourced ~/.local/gfx/env.sh. Every
invocation used --no-daemon, -Pkotlin.incremental=false and
-Pkotlin.compiler.execution.strategy=in-process. The full and repair gates
also used --max-workers=2 and --continue. No two builds were run concurrently
by this task. Sibling builds were left alone.

Full gate, once, after spotlessApply alone:

```sh
./gradlew --no-daemon --max-workers=2 \
  -Pkotlin.incremental=false \
  -Pkotlin.compiler.execution.strategy=in-process \
  allTests test mutationTest :app:compileDebugAndroidTestKotlin \
  spotlessCheck :app:lintRelease assertModuleGraph checkBannedApis \
  :benchmark:assembleBenchmarkRelease :app:compileDebugKotlin \
  :desktopApp:compileKotlin --continue
```

Focused repair gate, after spotlessApply alone:

```sh
./gradlew --no-daemon --max-workers=2 \
  -Pkotlin.incremental=false \
  -Pkotlin.compiler.execution.strategy=in-process \
  :core:testing:desktopTest :core:data:allTests :feature:podcast:allTests \
  :app:test :desktopApp:test :app:compileDebugAndroidTestKotlin \
  spotlessCheck :app:lintRelease assertModuleGraph checkBannedApis \
  :benchmark:assembleBenchmarkRelease :app:compileDebugKotlin \
  :desktopApp:compileKotlin --continue
```

Final non-test checks: spotlessCheck, :app:lintRelease, all five
assertModuleGraph consumers, checkBannedApis, :app:compileDebugKotlin,
:desktopApp:compileKotlin, :app:compileDebugAndroidTestKotlin and
:benchmark:assembleBenchmarkRelease all pass. Mutation leaf suites total
43 passes / 0 skips. :sync:protocol:mutationTest has no cases and supplies
no mutation proof. The benchmark test aggregate has no host cases;
assembly is build evidence only.

Doc checks: `python3 tools/doccheck/checkdocs.py .`: **0 problems across
14 files**; `node tools/doccheck/mmdcheck.mjs .`: **0 invalid of 78 diagrams**.
Both ran again after the final owning-document edits. The fixed-list doc
checkers do not scan this operator report. All original dated implementation
note headings survive, and merged resource locales have no duplicate names.
All added files from the requested parents survive (134 M8-side, 164 M6-side).
Both workflows parse without duplicate keys; all jobs from M8, M6b and M7's
evidence commit survive. `git diff --check` is clean; schema exports unchanged.

CI assertion fixture tests pass: discovery 4/0, downloads 4/0, system tests
11/0, playback 7/0 (passed/skipped), 26/0 total. Their printed device results
use synthetic XML, not executed devices. The separate actual
`python3 scripts/ci/assert-discovery-tests.py .` gate passes all **26 named
host cases / 0 skips** from the final application XML.

The three final skips are the Windows-only DPAPI round trip and the German
and English desktop native file-menu picker cases. No skip is counted as
proof of that platform behavior.

### Final per-task test counts

Aggregate task families, with each final leaf report counted once:

| Task family | Passed | Skipped | Failed |
|---|---:|---:|---:|
| allTests (KMP desktop and Android host suites) | 7,074 | 1 | 0 |
| test (JVM and app unit suites) | 830 | 2 | 0 |
| mutationTest | 43 | 0 | 0 |
| Total | 7,947 | 3 | 0 |


| Task | Passed | Skipped | Failed |
|---|---:|---:|---:|
| `:app:testDebugUnitTest` | 160 | 0 | 0 |
| `:core:artwork:desktopTest` | 25 | 0 | 0 |
| `:core:artwork:testAndroidHostTest` | 50 | 0 | 0 |
| `:core:common:desktopTest` | 100 | 0 | 0 |
| `:core:data:desktopTest` | 1390 | 1 | 0 |
| `:core:data:mutationTest` | 1 | 0 | 0 |
| `:core:data:testAndroidHostTest` | 176 | 0 | 0 |
| `:core:database:desktopTest` | 173 | 0 | 0 |
| `:core:database:testAndroidHostTest` | 9 | 0 | 0 |
| `:core:datastore:desktopTest` | 18 | 0 | 0 |
| `:core:designsystem:desktopTest` | 14 | 0 | 0 |
| `:core:domain:desktopTest` | 3 | 0 | 0 |
| `:core:model:desktopTest` | 2556 | 0 | 0 |
| `:core:navigation:desktopTest` | 25 | 0 | 0 |
| `:core:network:desktopTest` | 33 | 0 | 0 |
| `:core:network:okhttp:test` | 46 | 0 | 0 |
| `:core:network:testAndroidHostTest` | 6 | 0 | 0 |
| `:core:testing:desktopTest` | 92 | 0 | 0 |
| `:core:ui:desktopTest` | 188 | 0 | 0 |
| `:core:ui:testAndroidHostTest` | 157 | 0 | 0 |
| `:desktop:system:test` | 1 | 0 | 0 |
| `:desktopApp:test` | 123 | 2 | 0 |
| `:download:api:desktopTest` | 1 | 0 | 0 |
| `:download:impl:desktopTest` | 190 | 0 | 0 |
| `:download:impl:testAndroidHostTest` | 327 | 0 | 0 |
| `:feature:discover:desktopTest` | 69 | 0 | 0 |
| `:feature:discover:testAndroidHostTest` | 41 | 0 | 0 |
| `:feature:downloads:desktopTest` | 12 | 0 | 0 |
| `:feature:episode:desktopTest` | 50 | 0 | 0 |
| `:feature:feeds:desktopTest` | 119 | 0 | 0 |
| `:feature:feeds:testAndroidHostTest` | 72 | 0 | 0 |
| `:feature:groups:desktopTest` | 88 | 0 | 0 |
| `:feature:importexport:desktopTest` | 192 | 0 | 0 |
| `:feature:library:desktopTest` | 57 | 0 | 0 |
| `:feature:player:desktopTest` | 67 | 0 | 0 |
| `:feature:podcast:desktopTest` | 127 | 0 | 0 |
| `:feature:podcast:testAndroidHostTest` | 69 | 0 | 0 |
| `:feature:queue:desktopTest` | 19 | 0 | 0 |
| `:feature:settings:desktopTest` | 56 | 0 | 0 |
| `:feature:sync:desktopTest` | 1 | 0 | 0 |
| `:feeds:desktopTest` | 234 | 0 | 0 |
| `:feeds:jvm:mutationTest` | 41 | 0 | 0 |
| `:feeds:jvm:test` | 276 | 0 | 0 |
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
| `:youtube:api:desktopTest` | 41 | 0 | 0 |
| `:youtube:api:mutationTest` | 1 | 0 | 0 |
| `:youtube:engine:test` | 1 | 0 | 0 |
| `:youtube:impl:desktopTest` | 38 | 0 | 0 |
| `:youtube:impl:testAndroidHostTest` | 38 | 0 | 0 |
| `:youtube:ytdlp-desktop:test` | 1 | 0 | 0 |

## Completion and remaining work

The integration is committed locally on `impl/m8-p1`; the commit containing
this report is the integration commit, with parents `e1ebc114` and
`8ab74ff0`. No push, PR, main merge, remote CI or external review was
performed (0 review rounds), per the explicit local-only task instructions.
The missing M7 changes were applied from existing commits, as recorded above.
No worktree or branch was created; there is none to clean up.

M8 W8 remains undone as requested, including channel action hiding,
capability-aware scoped settings presentation, direct-claim/malformed-row
coverage, mixed groups and integrated zero-INITIAL-download/E8 proof.
M6b W11 sibling documentation is not merged. No device, process-death E10,
Task Manager versus Recents, physical storage move, bmgr, optimized release,
cross-OS packaged desktop, network-host capture or live YouTube acceptance
was run. Host success does not close those existing milestone gates.

### Files changed relative to the starting M8 tip

413 files; most are the requested inherited M6 implementation and the missing M7 baseline.

```text
.editorconfig
.github/workflows/ci.yml
.github/workflows/nightly.yml
.nd-context/m6b-i2-report.md
.nd-context/m8-i3-report.md
HANDOFF.md
app/build.gradle.kts
app/policy/permissions.txt
app/src/androidTest/kotlin/ch/lkmc/neutrodyne/DiscoveryFixtureDeviceTest.kt
app/src/androidTest/kotlin/ch/lkmc/neutrodyne/DownloadJourneyTest.kt
app/src/androidTest/kotlin/ch/lkmc/neutrodyne/SubscribeJourneyTest.kt
app/src/main/AndroidManifest.xml
app/src/main/kotlin/ch/lkmc/neutrodyne/MainActivity.kt
app/src/main/kotlin/ch/lkmc/neutrodyne/MainIntentViewModel.kt
app/src/main/kotlin/ch/lkmc/neutrodyne/di/AndroidAppGraph.kt
app/src/main/kotlin/ch/lkmc/neutrodyne/di/CoreBindings.kt
app/src/main/kotlin/ch/lkmc/neutrodyne/playback/AndroidLocalMediaBindings.kt
app/src/main/res/xml/file_paths.xml
app/src/test/kotlin/ch/lkmc/neutrodyne/DiscoveryIntentTest.kt
app/src/test/kotlin/ch/lkmc/neutrodyne/DiscoveryManifestTest.kt
app/src/test/kotlin/ch/lkmc/neutrodyne/DownloadFileProviderTest.kt
app/src/test/kotlin/ch/lkmc/neutrodyne/di/AndroidAppGraphTest.kt
app/src/test/kotlin/ch/lkmc/neutrodyne/di/AndroidDownloadGraphTest.kt
app/src/test/kotlin/ch/lkmc/neutrodyne/di/AndroidStartupRecoveryTest.kt
benchmark/build.gradle.kts
benchmark/src/main/AndroidManifest.xml
benchmark/src/main/kotlin/ch/lkmc/neutrodyne/benchmark/BackgroundStandInActivity.kt
benchmark/src/main/kotlin/ch/lkmc/neutrodyne/benchmark/FixtureServer.kt
benchmark/src/main/kotlin/ch/lkmc/neutrodyne/benchmark/ProcessDeathResumeTest.kt
benchmark/src/main/kotlin/ch/lkmc/neutrodyne/benchmark/ReleaseSmokeJourneyTest.kt
benchmark/src/main/kotlin/ch/lkmc/neutrodyne/benchmark/SystemTestApp.kt
build-logic/convention/build.gradle.kts
build-logic/convention/src/main/kotlin/AndroidSystemTestConventionPlugin.kt
build-logic/convention/src/main/kotlin/DependencyPolicy.kt
core/data/build.gradle.kts
core/data/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/core/data/AndroidTestSupport.kt
core/data/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/core/data/backup/FirstLaunchRestoreInitializerTest.kt
core/data/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/core/data/backup/RestoreWorkerTest.kt
core/data/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/data/DataBindings.kt
core/data/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/data/backup/RestoreMerger.kt
core/data/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/data/ingest/IngestionEventBus.kt
core/data/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/data/playback/ChapterRepositoryImpl.kt
core/data/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/data/repo/EpisodeLiveStateSourceImpl.kt
core/data/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/data/repo/FeedRepositoryImpl.kt
core/data/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/data/repo/PodcastMerger.kt
core/data/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/data/repo/PodcastRepositoryImpl.kt
core/data/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/data/settings/EffectiveSettingsResolverImpl.kt
core/data/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/data/settings/ScopeSettingsRepositoryImpl.kt
core/data/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/data/settings/ScopeSettingsResolution.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/AutoDownloadResolutionTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/ChapterRepositoryTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/CountsTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/DownloadAllEstimateTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/EditFeedUrlTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/EpisodeLiveStateSourceTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/FeedMovesTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/FeedRepositoryImplTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/FeedRepositoryTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/GroupDeleteTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/ImportCredentialOrderTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/PodcastMergeTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/PodcastRepositoryTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/ScopeSettingsRepositoryTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/SyncInertTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/TestSupport.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/UnsubscribeUseCaseTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/backup/BackupCrossPlatformTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/backup/BackupRepositoryImplTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/backup/BackupStagerTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/backup/M6RestoreSettingsTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/backup/RestoreEpisodeMergerTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/backup/RestoreMergerTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/backup/RestoreRunnerTest.kt
core/data/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/data/importing/DesktopImportBackupLaneTest.kt
core/data/src/jvmTest/kotlin/ch/lkmc/neutrodyne/core/data/DiscoveryHtmlReplayTest.kt
core/database/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/database/AreaDaos.kt
core/database/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/database/DownloadContracts.kt
core/database/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/database/DownloadDao.kt
core/database/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/database/EpisodeDao.kt
core/database/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/database/FeedDao.kt
core/database/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/database/PlaySessionDao.kt
core/database/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/database/PodcastDao.kt
core/database/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/database/ScopeSettingsDao.kt
core/database/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/database/AutoDownloadQueriesTest.kt
core/database/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/database/DownloadCleanupQueriesTest.kt
core/database/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/database/DownloadDaoTest.kt
core/designsystem/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/designsystem/components/NdNavigationSuiteScaffold.kt
core/designsystem/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/designsystem/components/NdScrollbar.kt
core/domain/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/domain/AutomaticDownloads.kt
core/domain/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/domain/ChapterRepository.kt
core/domain/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/domain/DownloadLifecycle.kt
core/domain/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/domain/EffectiveSettingsResolver.kt
core/domain/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/domain/EmbeddedChapterReader.kt
core/domain/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/domain/FeedRepository.kt
core/domain/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/domain/RestoreDownloadRequests.kt
core/model/build.gradle.kts
core/model/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/model/DownloadEnums.kt
core/model/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/model/EffectiveAutoDownload.kt
core/model/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/model/FeedFilterCapabilities.kt
core/model/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/model/ScopedSettings.kt
core/model/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/model/settings/AllSettingKeys.kt
core/model/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/model/settings/DesktopSettingKeys.kt
core/model/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/model/settings/DownloadSettingKeys.kt
core/model/src/commonTest/kotlin/ch/lkmc/neutrodyne/core/model/DownloadSettingsTest.kt
core/model/src/commonTest/kotlin/ch/lkmc/neutrodyne/core/model/FeedFilterCapabilitiesTest.kt
core/testing/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/testing/DownloadFixtures.kt
core/testing/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/testing/FakeAutomaticDownloads.kt
core/testing/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/testing/FakeChapterRepository.kt
core/testing/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/testing/FakeDownloadController.kt
core/testing/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/testing/FakeDownloadLifecycle.kt
core/testing/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/testing/FakeDownloadProgressSource.kt
core/testing/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/testing/FakeEffectiveSettingsResolver.kt
core/testing/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/testing/FakeFeedRepository.kt
core/testing/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/testing/FakePlaybackController.kt
core/testing/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/testing/FakePlaybackDependencies.kt
core/testing/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/testing/FakeScopeSettingsRepository.kt
core/testing/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/testing/FakeSettingsRepository.kt
core/testing/src/commonTest/kotlin/ch/lkmc/neutrodyne/core/testing/FakeAutomaticDownloadsTest.kt
core/testing/src/commonTest/kotlin/ch/lkmc/neutrodyne/core/testing/FakeDownloadContractsTest.kt
core/testing/src/commonTest/kotlin/ch/lkmc/neutrodyne/core/testing/FakeFeedRepositoryTest.kt
core/testing/src/commonTest/kotlin/ch/lkmc/neutrodyne/core/testing/FakeScopedSettingsTest.kt
core/testing/src/commonTest/kotlin/ch/lkmc/neutrodyne/core/testing/FakeSettingsRepositoryTest.kt
core/testing/src/commonTest/kotlin/ch/lkmc/neutrodyne/core/testing/RestoreDownloadRequestsTest.kt
core/testing/src/desktopMain/kotlin/ch/lkmc/neutrodyne/core/testing/database/TestDb.kt
core/ui/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/core/ui/platform/AppleLinkSettingsTest.kt
core/ui/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/core/ui/platform/NotificationPermissionRequesterTest.kt
core/ui/src/androidMain/kotlin/ch/lkmc/neutrodyne/core/ui/platform/PlatformActions.android.kt
core/ui/src/commonMain/composeResources/values-de/discovery-w5.xml
core/ui/src/commonMain/composeResources/values-de/strings.xml
core/ui/src/commonMain/composeResources/values/discovery-w5.xml
core/ui/src/commonMain/composeResources/values/downloads.xml
core/ui/src/commonMain/composeResources/values/strings.xml
core/ui/src/commonMain/composeResources/values/w8_downloads.xml
core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/AttributionText.kt
core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadAllController.kt
core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadAllDialog.kt
core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadDialogs.kt
core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadNotices.kt
core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadRequestHandler.kt
core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadSettingsRows.kt
core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadUiText.kt
core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/EpisodeRow.kt
core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/OrphanFilesDialog.kt
core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/PodcastHeader.kt
core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/PointerMenus.kt
core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/ReportVisibleEpisodeIds.kt
core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/StringMappers.kt
core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/platform/PlatformActions.kt
core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/root/DiscoverSearchFocus.kt
core/ui/src/commonMain/kotlin/ch/lkmc/neutrodyne/core/ui/root/NeutrodyneRoot.kt
core/ui/src/commonTest/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadAllControllerTest.kt
core/ui/src/commonTest/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadRequestHandlerTest.kt
core/ui/src/commonTest/kotlin/ch/lkmc/neutrodyne/core/ui/root/DiscoverSearchFocusTest.kt
core/ui/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadSettingsRowsTest.kt
core/ui/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/ui/DownloadsScreenStringsTest.kt
core/ui/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/ui/EpisodeRowTest.kt
core/ui/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/ui/FeedFilterChipsTest.kt
core/ui/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/ui/ReportVisibleEpisodeIdsTest.kt
core/ui/src/desktopTest/kotlin/ch/lkmc/neutrodyne/core/ui/root/NeutrodyneRootTest.kt
desktopApp/build.gradle.kts
desktopApp/src/main/kotlin/ch/lkmc/neutrodyne/desktop/DesktopShell.kt
desktopApp/src/main/kotlin/ch/lkmc/neutrodyne/desktop/di/DesktopAppGraph.kt
desktopApp/src/main/kotlin/ch/lkmc/neutrodyne/desktop/notifications/DesktopDownloadAlerts.kt
desktopApp/src/main/kotlin/ch/lkmc/neutrodyne/desktop/playback/DesktopUnavailablePlayback.kt
desktopApp/src/main/kotlin/ch/lkmc/neutrodyne/desktop/smoke/SmokeMode.kt
desktopApp/src/main/kotlin/ch/lkmc/neutrodyne/desktop/window/NeutrodyneWindow.kt
desktopApp/src/test/kotlin/ch/lkmc/neutrodyne/desktop/DesktopShellTest.kt
desktopApp/src/test/kotlin/ch/lkmc/neutrodyne/desktop/di/DesktopAppGraphTest.kt
desktopApp/src/test/kotlin/ch/lkmc/neutrodyne/desktop/di/DesktopDownloadAlertsTest.kt
desktopApp/src/test/kotlin/ch/lkmc/neutrodyne/desktop/di/DesktopDownloadRestartTest.kt
desktopApp/src/test/kotlin/ch/lkmc/neutrodyne/desktop/di/DiscoveryJourneyTest.kt
desktopApp/src/test/kotlin/ch/lkmc/neutrodyne/desktop/di/GroupFeedJourneyTest.kt
desktopApp/src/test/kotlin/ch/lkmc/neutrodyne/desktop/smoke/SmokeModeTest.kt
docs/PLAN.md
docs/design/01-foundation.md
docs/design/02-data-model.md
docs/design/03-feeds-and-discovery.md
docs/design/04-youtube.md
docs/design/05-groups-opml-backup.md
docs/design/06-playback.md
docs/design/07-downloads.md
docs/design/08-ui-ux.md
docs/design/09-quality-and-release.md
docs/design/11-desktop.md
docs/evidence/m7-discovery.md
download/api/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/api/DownloadController.kt
download/api/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/api/DownloadModels.kt
download/api/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/api/DownloadProgressSource.kt
download/api/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/api/LocalMediaChanges.kt
download/impl/build.gradle.kts
download/impl/src/androidDeviceTest/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadSchedulingDeviceTest.kt
download/impl/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/download/impl/AndroidDownloadApiGateTest.kt
download/impl/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/download/impl/AndroidDownloadMaintenanceTest.kt
download/impl/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/download/impl/AndroidDownloadResetSeamTest.kt
download/impl/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/download/impl/AndroidDownloadSchedulerTest.kt
download/impl/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/download/impl/AndroidDownloadTriggersTest.kt
download/impl/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/download/impl/AndroidStorageRootsTest.kt
download/impl/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/download/impl/CleanupWorkerTest.kt
download/impl/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadActionReceiverTest.kt
download/impl/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadAutoWorkManagerTest.kt
download/impl/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadChargingMonitorTest.kt
download/impl/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadMoveWorkerHostTest.kt
download/impl/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadNotificationsTest.kt
download/impl/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadReconcilerTest.kt
download/impl/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadWorkManagerTest.kt
download/impl/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadWorkersTest.kt
download/impl/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/download/impl/ManualDownloadJobServiceTest.kt
download/impl/src/androidMain/AndroidManifest.xml
download/impl/src/androidMain/kotlin/ch/lkmc/neutrodyne/download/impl/AndroidDownloadBindings.kt
download/impl/src/androidMain/kotlin/ch/lkmc/neutrodyne/download/impl/AndroidDownloadMaintenance.kt
download/impl/src/androidMain/kotlin/ch/lkmc/neutrodyne/download/impl/AndroidDownloadRuntime.kt
download/impl/src/androidMain/kotlin/ch/lkmc/neutrodyne/download/impl/AndroidDownloadScheduler.kt
download/impl/src/androidMain/kotlin/ch/lkmc/neutrodyne/download/impl/AndroidDownloadTriggers.kt
download/impl/src/androidMain/kotlin/ch/lkmc/neutrodyne/download/impl/AndroidDownloadWork.kt
download/impl/src/androidMain/kotlin/ch/lkmc/neutrodyne/download/impl/AndroidExitReasonProbe.kt
download/impl/src/androidMain/kotlin/ch/lkmc/neutrodyne/download/impl/AndroidStorageRoots.kt
download/impl/src/androidMain/kotlin/ch/lkmc/neutrodyne/download/impl/AndroidTaskManagerReset.kt
download/impl/src/androidMain/kotlin/ch/lkmc/neutrodyne/download/impl/CleanupWorker.kt
download/impl/src/androidMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadActionReceiver.kt
download/impl/src/androidMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadAppVisibility.kt
download/impl/src/androidMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadChargingMonitor.kt
download/impl/src/androidMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadInitializers.kt
download/impl/src/androidMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadMoveWorker.kt
download/impl/src/androidMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadNotifications.kt
download/impl/src/androidMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadWorkers.kt
download/impl/src/androidMain/kotlin/ch/lkmc/neutrodyne/download/impl/JobSchedulerFacade.kt
download/impl/src/androidMain/kotlin/ch/lkmc/neutrodyne/download/impl/LaneRegistry.kt
download/impl/src/androidMain/kotlin/ch/lkmc/neutrodyne/download/impl/ManualDownloadJobService.kt
download/impl/src/androidMain/res/values/strings.xml
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/ActiveTransfers.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/AutoDownloadPlannerImpl.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/CleanupPlannerImpl.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/DeferredDeletes.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadComponents.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadControllerFiles.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadControllerImpl.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadEngine.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadEstimates.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadFiles.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadLifecycleImpl.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadMoverImpl.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadPaths.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadProgressHub.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadQuota.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadReconciler.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadSlots.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadStorage.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/LocalMediaIndexImpl.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/LocalMediaIndexStore.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/MediaSniffer.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/OkioDownloadFileSystem.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/RssTransferSource.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/RunnerContracts.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/StorageContracts.kt
download/impl/src/commonMain/kotlin/ch/lkmc/neutrodyne/download/impl/TransferContracts.kt
download/impl/src/commonTest/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadProgressHubTest.kt
download/impl/src/commonTest/kotlin/ch/lkmc/neutrodyne/download/impl/FakeDownloadRunner.kt
download/impl/src/commonTest/kotlin/ch/lkmc/neutrodyne/download/impl/MediaSnifferTest.kt
download/impl/src/desktopMain/kotlin/ch/lkmc/neutrodyne/download/impl/DesktopDownloadBindings.kt
download/impl/src/desktopMain/kotlin/ch/lkmc/neutrodyne/download/impl/DesktopDownloadLane.kt
download/impl/src/desktopMain/kotlin/ch/lkmc/neutrodyne/download/impl/DesktopDownloadTriggers.kt
download/impl/src/desktopMain/kotlin/ch/lkmc/neutrodyne/download/impl/DesktopMoveLane.kt
download/impl/src/desktopMain/kotlin/ch/lkmc/neutrodyne/download/impl/DesktopStorageRoots.kt
download/impl/src/desktopTest/kotlin/ch/lkmc/neutrodyne/download/impl/AutoDownloadPlannerTest.kt
download/impl/src/desktopTest/kotlin/ch/lkmc/neutrodyne/download/impl/CleanupPlannerTest.kt
download/impl/src/desktopTest/kotlin/ch/lkmc/neutrodyne/download/impl/DesktopDownloadLaneTest.kt
download/impl/src/desktopTest/kotlin/ch/lkmc/neutrodyne/download/impl/DesktopDownloadTriggersTest.kt
download/impl/src/desktopTest/kotlin/ch/lkmc/neutrodyne/download/impl/DesktopMoveTest.kt
download/impl/src/desktopTest/kotlin/ch/lkmc/neutrodyne/download/impl/DesktopStorageRootsTest.kt
download/impl/src/desktopTest/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadCleanupEngineTest.kt
download/impl/src/desktopTest/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadControllerImplTest.kt
download/impl/src/desktopTest/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadEngineTest.kt
download/impl/src/desktopTest/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadFilesTest.kt
download/impl/src/desktopTest/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadMoveWorkerTest.kt
download/impl/src/desktopTest/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadQuotaTest.kt
download/impl/src/desktopTest/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadReconcilerTest.kt
download/impl/src/desktopTest/kotlin/ch/lkmc/neutrodyne/download/impl/DownloadSlotsTest.kt
download/impl/src/desktopTest/kotlin/ch/lkmc/neutrodyne/download/impl/EngineTestSupport.kt
download/impl/src/desktopTest/kotlin/ch/lkmc/neutrodyne/download/impl/RssTransferSourceTest.kt
download/impl/src/desktopTest/kotlin/ch/lkmc/neutrodyne/download/impl/TransferTestSupport.kt
download/impl/src/jvmMain/kotlin/ch/lkmc/neutrodyne/download/impl/PlatformFiles.kt
download/impl/src/systemTest/kotlin/ch/lkmc/neutrodyne/download/impl/SystemTestDownloadInitializer.kt
feature/discover/build.gradle.kts
feature/discover/screenshots/android/discovery_content.png
feature/discover/screenshots/android/discovery_error.png
feature/discover/screenshots/android/discovery_loading.png
feature/discover/screenshots/desktop/discovery_content.png
feature/discover/screenshots/desktop/discovery_error.png
feature/discover/screenshots/desktop/discovery_loading.png
feature/discover/src/androidHostTest/AndroidManifest.xml
feature/discover/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/feature/discover/DiscoveryScreenshotTest.kt
feature/discover/src/androidHostTest/resources/robolectric.properties
feature/discover/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/discover/DirectoryRoute.kt
feature/discover/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/discover/DirectoryViewModel.kt
feature/discover/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/discover/DiscoverNavigation.kt
feature/discover/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/discover/DiscoverRoute.kt
feature/discover/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/discover/DiscoverViewModel.kt
feature/discover/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/discover/DiscoveryContent.kt
feature/discover/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/discover/add/AddPodcastRoute.kt
feature/discover/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/discover/add/AddPodcastSheet.kt
feature/discover/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/discover/add/AddPodcastViewModel.kt
feature/discover/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/discover/DiscoveryScreenshotFixture.kt
feature/discover/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/discover/DiscoveryViewModelTest.kt
feature/discover/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/discover/add/AddPodcastViewModelTest.kt
feature/discover/src/desktopTest/kotlin/ch/lkmc/neutrodyne/feature/discover/DiscoveryScreenTest.kt
feature/discover/src/desktopTest/kotlin/ch/lkmc/neutrodyne/feature/discover/DiscoveryScreenshotTest.kt
feature/downloads/build.gradle.kts
feature/downloads/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/downloads/DownloadsRoute.kt
feature/downloads/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/downloads/DownloadsScreen.kt
feature/downloads/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/downloads/DownloadsViewModel.kt
feature/downloads/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/downloads/DownloadsViewModelTest.kt
feature/downloads/src/desktopTest/kotlin/ch/lkmc/neutrodyne/feature/downloads/DownloadsScreenTest.kt
feature/episode/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/episode/EpisodeRoute.kt
feature/episode/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/episode/EpisodeScreen.kt
feature/episode/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/episode/EpisodeViewModel.kt
feature/episode/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/episode/EpisodeViewModelTest.kt
feature/episode/src/desktopTest/kotlin/ch/lkmc/neutrodyne/feature/episode/EpisodeScreenTest.kt
feature/feeds/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/feeds/FeedsPager.kt
feature/feeds/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/feeds/FeedsRoute.kt
feature/feeds/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/feeds/FeedsScreen.kt
feature/feeds/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/feeds/FeedsViewModel.kt
feature/feeds/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/feeds/FeedsViewModelTest.kt
feature/feeds/src/desktopTest/kotlin/ch/lkmc/neutrodyne/feature/feeds/AllGroupsSheetTest.kt
feature/feeds/src/desktopTest/kotlin/ch/lkmc/neutrodyne/feature/feeds/FeedsPagingContextTest.kt
feature/feeds/src/desktopTest/kotlin/ch/lkmc/neutrodyne/feature/feeds/FeedsScreenTest.kt
feature/groups/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/groups/GroupSettingsRoute.kt
feature/groups/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/groups/GroupSettingsScreen.kt
feature/groups/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/groups/GroupSettingsViewModel.kt
feature/groups/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/groups/GroupSettingsViewModelTest.kt
feature/groups/src/desktopTest/kotlin/ch/lkmc/neutrodyne/feature/groups/GroupSettingsScreenTest.kt
feature/importexport/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/importexport/RestoreRoute.kt
feature/importexport/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/importexport/RestoreViewModel.kt
feature/importexport/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/importexport/RestoreProgressOwnershipTest.kt
feature/importexport/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/importexport/RestoreViewModelTest.kt
feature/importexport/src/desktopTest/kotlin/ch/lkmc/neutrodyne/feature/importexport/ImportRouteTest.kt
feature/player/src/commonMain/composeResources/values-de/player.xml
feature/player/src/commonMain/composeResources/values/player.xml
feature/podcast/build.gradle.kts
feature/podcast/screenshots/android/preview_content.png
feature/podcast/screenshots/android/preview_error.png
feature/podcast/screenshots/android/preview_loading.png
feature/podcast/screenshots/desktop/preview_content.png
feature/podcast/screenshots/desktop/preview_error.png
feature/podcast/screenshots/desktop/preview_loading.png
feature/podcast/src/androidHostTest/AndroidManifest.xml
feature/podcast/src/androidHostTest/kotlin/ch/lkmc/neutrodyne/feature/podcast/PreviewScreenshotTest.kt
feature/podcast/src/androidHostTest/resources/robolectric.properties
feature/podcast/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastEffectiveSettings.kt
feature/podcast/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastNavigation.kt
feature/podcast/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastPreviewRoute.kt
feature/podcast/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastPreviewViewModel.kt
feature/podcast/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastRoute.kt
feature/podcast/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastScreen.kt
feature/podcast/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastSettingsRoute.kt
feature/podcast/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastSettingsScreen.kt
feature/podcast/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastSettingsViewModel.kt
feature/podcast/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastViewModel.kt
feature/podcast/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/podcast/PreviewErrorText.kt
feature/podcast/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastPreviewViewModelTest.kt
feature/podcast/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastSettingsViewModelTest.kt
feature/podcast/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastViewModelTest.kt
feature/podcast/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/podcast/PreviewScreenshotFixture.kt
feature/podcast/src/desktopTest/kotlin/ch/lkmc/neutrodyne/feature/podcast/PodcastPreviewScreenTest.kt
feature/podcast/src/desktopTest/kotlin/ch/lkmc/neutrodyne/feature/podcast/PreviewScreenshotTest.kt
feature/queue/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/queue/UpNextViewModelTest.kt
feature/settings/build.gradle.kts
feature/settings/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/settings/DownloadSettingsViewModel.kt
feature/settings/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/settings/DownloadsPage.kt
feature/settings/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/settings/DownloadsSettingsPage.kt
feature/settings/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/settings/DownloadsSettingsViewModel.kt
feature/settings/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/settings/SettingsHomeRoute.kt
feature/settings/src/commonMain/kotlin/ch/lkmc/neutrodyne/feature/settings/SettingsRoute.kt
feature/settings/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/settings/DownloadSettingsViewModelTest.kt
feature/settings/src/commonTest/kotlin/ch/lkmc/neutrodyne/feature/settings/DownloadsSettingsViewModelTest.kt
feeds/build.gradle.kts
feeds/jvm/build.gradle.kts
feeds/src/replay/kotlin/ch/lkmc/neutrodyne/feeds/directory/DirectoryReplayAssertions.kt
feeds/src/replay/kotlin/ch/lkmc/neutrodyne/feeds/directory/HtmlReplayAssertions.kt
feeds/src/test/kotlin/ch/lkmc/neutrodyne/feeds/directory/DirectoryReplayTest.kt
feeds/src/test/resources/discovery/README.md
feeds/src/test/resources/discovery/journey-feed.xml
gradle/libs.versions.toml
playback/api/src/commonMain/kotlin/ch/lkmc/neutrodyne/playback/api/PlaybackController.kt
playback/api/src/commonMain/kotlin/ch/lkmc/neutrodyne/playback/api/UnavailablePlaybackController.kt
playback/core/src/commonMain/kotlin/ch/lkmc/neutrodyne/playback/core/EffectivePlaybackSettings.kt
playback/core/src/commonMain/kotlin/ch/lkmc/neutrodyne/playback/core/PlayStarter.kt
playback/core/src/commonTest/kotlin/ch/lkmc/neutrodyne/playback/core/PlayStarterTest.kt
playback/impl/build.gradle.kts
playback/impl/src/main/kotlin/ch/lkmc/neutrodyne/playback/impl/AndroidServiceBridge.kt
playback/impl/src/main/kotlin/ch/lkmc/neutrodyne/playback/impl/Chapters.kt
playback/impl/src/main/kotlin/ch/lkmc/neutrodyne/playback/impl/CustomCommands.kt
playback/impl/src/main/kotlin/ch/lkmc/neutrodyne/playback/impl/ErrorRecovery.kt
playback/impl/src/main/kotlin/ch/lkmc/neutrodyne/playback/impl/MediaLibraryTree.kt
playback/impl/src/main/kotlin/ch/lkmc/neutrodyne/playback/impl/MetadataRetrieverChapterReader.kt
playback/impl/src/main/kotlin/ch/lkmc/neutrodyne/playback/impl/PlaybackControllerImpl.kt
playback/impl/src/main/kotlin/ch/lkmc/neutrodyne/playback/impl/PlaybackServiceAccess.kt
playback/impl/src/main/kotlin/ch/lkmc/neutrodyne/playback/impl/QueueProjector.kt
playback/impl/src/test/kotlin/ch/lkmc/neutrodyne/playback/impl/CarLibraryTest.kt
playback/impl/src/test/kotlin/ch/lkmc/neutrodyne/playback/impl/ChapterFixturesTest.kt
playback/impl/src/test/kotlin/ch/lkmc/neutrodyne/playback/impl/ErrorRecoveryTest.kt
playback/impl/src/test/kotlin/ch/lkmc/neutrodyne/playback/impl/LifecycleHarness.kt
playback/impl/src/test/kotlin/ch/lkmc/neutrodyne/playback/impl/PlaybackAdapterTestSupport.kt
playback/impl/src/test/kotlin/ch/lkmc/neutrodyne/playback/impl/QueueProjectorTest.kt
scripts/ci/assert-discovery-tests.py
scripts/ci/assert-playback-tests.py
scripts/ci/assert-system-tests.py
scripts/ci/bmgr-check.sh
scripts/ci/check-apk.sh
scripts/ci/system-tests.sh
scripts/ci/tests/assert-discovery-tests.test.py
scripts/ci/tests/assert-download-tests.test.py
scripts/ci/tests/assert-system-tests.test.py
settings.gradle.kts
```
