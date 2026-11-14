# M6b acceptance ledger

2026-11-02, branch `impl/m6b-current`, ported onto the current M6a code (base
`1015ce56`, with its review fixes) from reference branch `impl/m6b-p1`
(`8ab74ff0f025df46f7614629a074cff05fc089c7`). Authority:
[PLAN M6](../PLAN.md#m6-downloads); inherited M6a evidence:
[HANDOFF](../../HANDOFF.md). No M7 ledger exists in this tree.

Host: Linux x64, JDK21/Android SDK/software graphics, desktop JVM and Robolectric.
**0 device/nightly/packaged executions, 0 external review rounds** on this
branch. Host/assembly evidence does not prove OS behavior.

## AC-by-AC coverage

Methods are exact source names. Default tasks:
`:download:impl:desktopTest`, or `:download:impl:testAndroidHostTest` for Android
host cases. Device evidence is pending unless a receipt below records it.

| M6 AC | Host assertion and exact tests | Device, nightly or owner evidence still required |
|---|---|---|
| 1, inherited M6a | `RssTransferSourceTest.cutAtHalfResumesWithStrongValidatorAndActualLength`; shared transfer suite | Physical radio/network/captive-portal checks; remaining M6a cases are in HANDOFF |
| 2, M6b E10 half | `DownloadReconcilerTest.processDeathTrimsOnlyTheTornTailBeforeResume`, `staleTokensResetOnceAndRegisteredOwnersSurviveParallelPasses` | E10 passed on bench34: actual PID death, strong ETag, server Range/If-Range, reset row and final hash. Reboot, force-stop and update remain separate gaps |
| 3, inherited M6a | `DownloadWorkersTest.visiblePromotionAllowedOnlyBefore34` (Android host) | `DownloadJourneyTest.visibleApi33DownloadUsesDataSyncForegroundWorker` on33; `backgroundUidtBesidePlaybackForTwentyMinutes` on34/36, screen off with bytes/audio/notifications |
| 4, M6b | `AutoDownloadPlannerTest.ac4GroupKeepsNewestThreeAndTombstonesFreeSlots`, `d45TwoGroupsUseOneResolvedPolicyAndOneRowPerEpisode`, `killedBeforeWatermarkCommitKeepsBaselineAndRollsBackWholePass`, `killedAfterWatermarkCommitBeforeBaselinePreservesCommittedWatermark`, `noBackfillFromInitialIngestOldArrivalOrDumpGuard` | API34+ real settings/refresh on unmetered network: keep 3, kill/relaunch around enabling, delete then refresh, two groups with one row, no initial backfill |
| 5, M6b | `CleanupPlannerTest.deletesAtTwentyFourHoursButNotOneSecondEarlier`, `favouritesCurrentAndExactlyNextThreeProtectPlayedFiles`, `unplayedManualAndStartedAutoAreProtectedEvenOutsideRollingWindow`, `deletesPlayedManualAndAutoWithoutWritingOrClearingTombstones` | Real overnight 24 h from playedAt plus protected controls; TestClock proves eligibility, not an exact OS wake |
| 6, inherited M6a | `DownloadReconcilerTest.controllerDeletesRowAndIndexButKeepsCurrentFileUntilSessionChanges` | `DownloadJourneyTest.downloadServerOffPlaysLocalArtworkChaptersAndDefersDelete` (E4), API26/36/37 and optimized release; physical airplane-mode sound and Share grant |
| 7, inherited M6a | `BackupRulesXmlTest.modernAndroidIncludesOnlySnapshotsAndPortableSettingsInBothModes` (`:app:testDebugUnitTest`) | `DownloadJourneyTest.seedCompletedDownloadsForBackupTransport` plus `scripts/ci/bmgr-check.sh`, rootable API29/36 release, actual transport inspection; remains M6a |
| 8, touched by M6b | `DownloadsScreenStringsTest.everyWaitReasonAndFolderRejectionHasLocalizedText`, `everyNoticeHasLocalizedTextOnBothPlatforms`, `designOrderIsStableAndShowsAtMostTwo` (`:core:ui:desktopTest`) | Physical accessibility/keyboard, missing roots, denied notifications, Data Saver/standby actions |
| 9, inherited M6a | `DownloadEngineTest.freshSuccessHasExactlyThreeSqlWritesAndMemoryVerification`, `twoParallelDrainsNeverDoubleClaimAndInitializeOnce`, `progressTicksNeverWriteTheDatabase` | No new platform claim; production storage durability remains empirical |
| 10, scheduler extended | `AndroidDownloadSchedulerTest.visibilityAndApiChooseUidtOrWorker`, `autoNeverUsesUidtExpeditedOrForegroundWait`, `autoWakeOnlyCoversAutoQueuedUnclaimedRssRows`, `manualAndAutoPendingAndWorkerHistoryAreIndependent` (Android host) | Real network assignment, Data Saver, standby/restricted bucket, charging, reboot/force-stop during AUTO; UIDT soak remains AC3 |
| 11, M6b | Android-host `DownloadReconcilerTest.newerUserExitWithoutJobPausesStoppedManualJobAndItsQueue`, `pendingPersistedManualJobKeepsRecentsSwipeOnSystemRecoveryPath`, `olderUserExitDoesNotPauseANewerTransfer`, `processKillDoesNotPauseEvenWithNoPendingJob`, `api33DoesNotInferUserStopEvenWithUserRequestedHistory` | Actual Task Manager Stop versus Recents on API34+: exit reason/time, UIDT token, pending namespaced job 1001 and row results. OEM Recents job removal leaves the distinction unverified |
| 12, inherited M6a | `RssTransferSourceTest.onlyUnvalidatedAndroidHtmlWaitsForNetwork`; `DownloadReconcilerTest.missingLengthAndUnavailableRootsRemoveIndexThenRemountRewritesUri` | SD remount, full disk, Files/MTP, API26–29 `.nomedia`, keep-data uninstall, captive portal |
| 13, M6b | `DownloadMoveWorkerTest.copySyncVerifyRenameCommitIndexAndJournalDelete`, `currentCopyCommitsButSourceDeletionWaitsForSessionChange`, `deadlineDuringCopyLeavesIntentAndNextRunSweepsAndRestarts`; Android-host `DownloadMoveWorkerHostTest.eightMinuteDeadlineAndContinuationUseSameTarget` | Real internal ↔ SD move, interrupt/restart, remount, full disk and playing-source deferral; bytes/URI/intent/journal verification |
| 14, inherited M6a | `DesktopDownloadRestartTest.gracefulQuitAtHalfResumesWithoutTornTailTrimming` (`:desktopApp:test`); shared transfer suite | MD1 audible offline proof; packaged per-OS restart/sleep/cable/ENOSPC/long paths/antivirus |
| 15, M6b | `DesktopDownloadLaneTest.autoLaneUsesSharedPlannerToDownloadNewestThreeWithoutPolicyHolds`, `dailyRunsAtExactBoundaryPersistsAcrossRestartAndCatchesUpAfterSleep`; `DesktopMoveTest.sameStoreRenamesWithoutCopySyncOrSpaceReservation`, `sameStorePlayingFileRemainsPendingUntilSessionChanges`, `crossStoreMoveKeepsTargetOnQuitAndContinuesNextRun`, `leaveThemWhereTheyAreMakesLaneIdle` | Packaged per-OS Change folder/Use default folder, same/cross-store, quit/restart, USB/network loss. Linux host does not cover Windows/macOS/ARM |

## Other M6b deliverables

Supplementary host coverage (same task conventions as the AC table):

| Work | Exact test and boundary |
|---|---|
| W1 | `AutoDownloadResolutionTest.defaultsBatchSelectionAndFullMap`, `notSupportedUsesGlobalDeletionAndFlipsWithoutSettingsWrites` (`:core:data:desktopTest`); `DownloadSettingsTest` (`:core:model:desktopTest`). PO-12/05 defaults and classification; no device UI execution |
| W2 | `AutoDownloadPlannerTest.watermarkPassBatchesFullLibraryAndSkipsUnchangedWrites`, `capStopsAtFirstRefusalWithoutSkippingToSmallerOrLaterPodcast`. Full-library batched pass, cap blocks AUTO only; YouTube remains unsupported |
| W3 | `CleanupPlannerTest.playedObserverWaitsThirtySecondsAndCoalescesChanges`, `dailySweepDoesNotResetOrDeleteLiveTransferParts`. Real scheduling pending; no automatic orphan deletion |
| W5/W7 | `CleanupWorkerTest.dailyWorkerRunsExactlyOneFullPass` (Android host); `DesktopDownloadLaneTest.onlyAutoPerformsDailyMaintenanceEvenOffline`, `dailyFailureOrQuitDoesNotAdvancePersistedTime`. Trigger suites: `AndroidDownloadTriggersTest`/`DesktopDownloadTriggersTest`. Power/quota pending; desktop runs only while the app runs |
| W8 | `DownloadAllEstimateTest.capKeepsNewestTwoHundredAndSizesOnlyThoseIds`, `enclosureThresholdAndDurationFallbackUseBytesPerMillisecond` (`:core:data:desktopTest`); `DownloadAllControllerTest.confirmationSubmitsExactlyTheOfferedIdsAsManualOnce`, `rejectionAndCancelledReadNeverCreateRows`, `meteredDecisionRequiresAnotherExplicitConfirmation` (`:core:ui:desktopTest`). Real count/size/consent UI pending |
| W9 | `DownloadsSettingsViewModelTest.controlsWriteTypedPoliciesAndReflectDefaults`, `androidRootSelectionRequiresMoveOrLeaveConfirmation`, `orphanCleanupNeedsConfirmationAndDoesNotHideFailure` (`:feature:settings:desktopTest`); `DownloadSettingsRowsTest.groupDependentsRequireOwnOptInEvenWhenGlobalIsOn`, `unsupportedPodcastHidesAutomaticRowsButKeepsCleanup` (`:core:ui:desktopTest`). Native UI pending; external-mode cleanup follows global deletion policy |
| W10 | `:benchmark:assembleBenchmarkRelease`, `:app:compileDebugAndroidTestKotlin`; checker fixtures in `scripts/ci/tests/assert-system-tests.test.py`. Build evidence only. I2 recorded opted-in driver compilation; W11's ordinary gate omits it |

## Device and job receipts to collect

Receipts record the SHA/task/test, device/API/ABI or OS/arch/package, application ID,
variant/date/counts/artifacts. Debug app: `ch.lkmc.neutrodyne.debug`; benchmark
target: `ch.lkmc.neutrodyne`, test APK: `ch.lkmc.neutrodyne.benchmark`.

| Lane | Entry point and required evidence |
|---|---|
| E10 nightly | Full-scope `system-tests`: `scripts/ci/system-tests.sh process-death bench34` → `:benchmark:bench34BenchmarkReleaseAndroidTest`; full-image API34, named E10 XML, SHA/device/variant `run.json` and `variant.txt` |
| Nightly smoke | `instrumented-full`: `scripts/ci/system-tests.sh smoke api36` → `:benchmark:api36BenchmarkReleaseAndroidTest`; API36 ATD, named smoke XML and metadata |
| API37 16 KB | `api37-16k`: `scripts/ci/system-tests.sh smoke connected` → `:benchmark:connectedBenchmarkReleaseAndroidTest`; actual page size/image and named smoke XML |
| App downloads | `run-instrumented` PR lane and nightly `scope=downloads` keep M6a E4/worker/UIDT assertions; they do not supply named M6b AC4/5/11/13 scenarios |
| Desktop matrix | No `desktop-matrix` job exists. Local Linux x64 does not cover windows-2025, macos-15 or ubuntu-24.04-arm; packaged/native checks remain owner work |

Adopted resolution 9 allows the sole enabled `benchmarkRelease` variant;
plain published-release coverage remains open. The minified/profileable opt-in
fixture target includes a restricted driver. E10 uses the real engine without
FGS so `am kill` can kill its cached process; it does not prove UIDT/Task Manager
behavior. Smoke uses normal UI/scheduling. `system-tests.sh` deletes stale XML;
`assert-system-tests.py` rejects empty/duplicate/failed/skipped evidence. E10
needs full scope, not `downloads` scope.

### E10 process death receipt

The [system-tests job](https://github.com/L-K-M/Neutrodyne/actions/runs/42793164779/job/129751499951)
passed on 2026-11-10 at `c380f83c8893034e72c8b5ca39ad5412c7490287`.
The `system-tests-results` archive's `run.json` and `variant.txt` identify
`process-death`, full-image API 34 `bench34`, and the minified, profileable
`benchmarkRelease` target. Its `TEST-bench34.xml` records one passing case,
zero failures, zero errors and zero skips:
`ProcessDeathResumeTest.killReconcilesSystemAndResumesFromPartLength`.
This proves E10 on that build. It does not cover the published APK, reboot,
force-stop, update, or the other jobs in that nightly run.

## Owner hardware checklist

1. Task Manager Stop versus fresh-state Recents on API34+: exit reason/time,
   UIDT token, namespaced job 1001 and MANUAL/AUTO rows, by OEM/API. API33 must
   not infer Task Manager. Recents must retain ordinary SYSTEM recovery.
2. Overnight cleanup: playedAt, clock/timezone, protected controls, observation
   before 24 h and first eligible cleanup after; TestClock is insufficient.
3. Internal ↔ SD: playing file, interrupt/restart, remount, full disk; capture
   bytes/hash/URI/intent/journal. Apply 07's unavailable-only terminal rule.
4. Packaged per-OS Change folder/Use default folder, Move/Leave, same/cross-store,
   quit/restart, USB/network loss, protected-folder prompts, long paths/full disk.
   Record OS/arch/package; audible proof awaits MD1.

## Gate and acceptance status

The reference branch's local gate passed once at its implementation SHA
(`allTests`: 6,592 passed/1 skipped; `test`: 796/2; `mutationTest`: 40/0.
Total: 7,428 passed, 3 skipped, 0 failed/errors; release lint: no issues;
both doc checks: 0 problems/0 invalid). This branch re-proves the same
assertions through CI on its final head; the run URL and per-job results are
recorded in the port's final report. Skips there: Windows DPAPI and two
desktop menu pickers. Sync protocol mutation has no cases and supplies no
mutation proof.

AC4/5/11/13/15 and the remaining AC2 and inherited M6a device gaps remain open.
No schema/entity/index/dependency/code change or milestone-completion claim.
