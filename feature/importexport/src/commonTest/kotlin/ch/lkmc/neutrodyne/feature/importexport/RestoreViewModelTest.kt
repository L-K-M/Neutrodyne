// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.importexport

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.BackupRepository
import ch.lkmc.neutrodyne.core.domain.RestoreProgress
import ch.lkmc.neutrodyne.core.model.BackupPreview
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.RestoreMode
import ch.lkmc.neutrodyne.core.model.RestoreRequest
import ch.lkmc.neutrodyne.core.testing.FakeBackupRepository
import ch.lkmc.neutrodyne.core.testing.FakeImportRepository
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreViewModelTest : MainDispatcherTest() {
    private val backups = FakeBackupRepository().apply { inspectOutcome = Outcome.Success(restorePreview()) }
    private val imports = FakeImportRepository()
    private val playback = FakePlaybackController()

    @Test fun mergeDefaultsAndReplaceSelectionFollowThePreviewContract() =
        runTest {
            val vm = RestoreViewModel(7, backups, imports, playback, backgroundScope)
            runCurrent()
            assertEquals(listOf("inspect(7)"), backups.calls)
            assertEquals(restorePreview(), vm.uiState.value.preview)
            assertEquals(RestoreMode.MERGE, vm.uiState.value.mode)
            assertEquals(setOf(RestoreCategory.HISTORY, RestoreCategory.UP_NEXT), vm.uiState.value.categories)
            vm.setCategory(RestoreCategory.UP_NEXT, false)
            vm.selectMode(RestoreMode.REPLACE)
            assertEquals(RestoreCategory.entries.toSet(), vm.uiState.value.categories)
            vm.selectMode(RestoreMode.MERGE)
            assertFalse(RestoreCategory.SETTINGS in vm.uiState.value.categories)
        }

    @Test fun reselectingCurrentModePreservesCategoryChoices() =
        runTest {
            val vm = RestoreViewModel(7, backups, imports, playback, backgroundScope)
            runCurrent()
            vm.setCategory(RestoreCategory.HISTORY, false)
            vm.selectMode(RestoreMode.MERGE)
            assertFalse(
                RestoreCategory.HISTORY in vm.uiState.value.categories,
                "Reselecting Merge must keep the chosen categories",
            )
        }

    @Test fun replaceWaitsForConfirmationAndPausesBeforeRestore() =
        runTest {
            val ordered =
                object : BackupRepository by backups {
                    override suspend fun restore(
                        sessionId: Long,
                        request: RestoreRequest,
                    ): Outcome<Unit, BackupError> {
                        assertEquals(1, playback.pauseCalls, "Playback must pause before restore is enqueued")
                        return backups.restore(sessionId, request)
                    }
                }
            val vm = RestoreViewModel(7, ordered, imports, playback, backgroundScope)
            runCurrent()
            vm.selectMode(RestoreMode.REPLACE)
            vm.requestRestore()
            assertTrue(vm.uiState.value.confirmingReplace)
            assertTrue(backups.restoreRequests.isEmpty())
            vm.cancelReplace()
            vm.confirmReplace()
            assertEquals(0, playback.pauseCalls)
            vm.requestRestore()
            vm.confirmReplace()
            assertEquals(
                RestoreRequest(RestoreMode.REPLACE, RestoreCategory.entries.toSet()),
                backups.restoreRequests.single().second,
            )
            assertTrue(imports.calls.none { it.startsWith("confirm(") })
            vm.confirmReplace()
            assertEquals(1, backups.restoreRequests.size)
        }

    @Test fun mergeRestoresOnlySelectedCategoriesWithoutPausing() =
        runTest {
            val vm = RestoreViewModel(7, backups, imports, playback, backgroundScope)
            runCurrent()
            vm.setCategory(RestoreCategory.HISTORY, false)
            vm.requestRestore()
            assertEquals(
                RestoreRequest(RestoreMode.MERGE, setOf(RestoreCategory.UP_NEXT)),
                backups.restoreRequests.single().second,
            )
            assertEquals(0, playback.pauseCalls)
            assertFalse(vm.uiState.value.busy)
            assertTrue(vm.uiState.value.requested)
        }

    @Test fun inspectErrorsBlockRestoreAndRestoreRunningIsExplicit() =
        runTest {
            val errors =
                listOf(
                    BackupError.NotABackup,
                    BackupError.NewerFormat(99),
                    BackupError.Corrupt("episodes.jsonl"),
                    BackupError.TooLarge,
                    BackupError.Io,
                    BackupError.Storage,
                )
            for (error in errors) {
                backups.inspectOutcome = Outcome.Failure(error)
                val vm = RestoreViewModel(7, backups, imports, playback, backgroundScope)
                runCurrent()
                assertEquals(error, vm.uiState.value.error)
                vm.requestRestore()
                assertTrue(backups.restoreRequests.isEmpty())
            }
            backups.inspectOutcome = Outcome.Success(restorePreview())
            backups.restoreOutcome = Outcome.Failure(BackupError.RestoreRunning)
            val vm = RestoreViewModel(7, backups, imports, playback, backgroundScope)
            runCurrent()
            vm.requestRestore()
            assertEquals(BackupError.RestoreRunning, vm.uiState.value.error)
            assertFalse(vm.uiState.value.requested)
            assertFalse(vm.uiState.value.busy)
        }

    @Test fun progressAndReportFollowTheRepositoryAndIgnoreOtherFinishedSessions() =
        runTest {
            val vm = RestoreViewModel(7, backups, imports, playback, backgroundScope)
            runCurrent()
            vm.requestRestore()
            runCurrent()
            assertEquals(RestoreProgress.Finished(7, false), vm.uiState.value.progress)
            backups.restoreProgress.value = RestoreProgress.Running("episodes", 12, 20)
            runCurrent()
            assertEquals(backups.restoreProgress.value, vm.uiState.value.progress)
            backups.restoreProgress.value = RestoreProgress.Finished(99, false)
            runCurrent()
            assertTrue(vm.uiState.value.progress is RestoreProgress.Running)
            backups.restoreProgress.value = RestoreProgress.Failed(BackupError.Storage)
            runCurrent()
            assertEquals(BackupError.Storage, vm.uiState.value.error)
        }

    @Test fun persistedRequestAdoptsFailureFromRestoreProgress() =
        runTest {
            imports.createFormat = ch.lkmc.neutrodyne.core.model.ImportFormat.NEUTRODYNE_BACKUP
            val id =
                (
                    imports.create(
                        ch.lkmc.neutrodyne.core.model
                            .ImportSource("file:///backup.zip", null),
                    ) as Outcome.Success
                ).value
            imports.sessions.value = imports.sessions.value + (
                id to
                    imports.sessions.value.getValue(id).copy(
                        options =
                            ch.lkmc.neutrodyne.core.model
                                .ImportOptions(restoreRequestedAt = 1L),
                    )
            )
            backups.restoreProgress.value = RestoreProgress.Failed(BackupError.Storage)
            val vm = RestoreViewModel(id, backups, imports, playback, backgroundScope)
            runCurrent()
            assertEquals(BackupError.Storage, vm.uiState.value.error, "A resumed restore must expose its failure")
            assertTrue(vm.uiState.value.requested)
        }

    @Test fun anOlderGlobalFailureDoesNotPoisonANewRestore() =
        runTest {
            backups.restoreProgress.value = RestoreProgress.Failed(BackupError.Io)
            val accepted =
                object : BackupRepository by backups {
                    override suspend fun restore(
                        sessionId: Long,
                        request: RestoreRequest,
                    ): Outcome<Unit, BackupError> = Outcome.Success(Unit)
                }
            val vm = RestoreViewModel(7, accepted, imports, playback, backgroundScope)
            runCurrent()
            vm.requestRestore()
            assertEquals(null, vm.uiState.value.error)
            assertTrue(vm.uiState.value.requested)
        }

    @Test fun failureBeforeEnqueueReturnsIsNotLost() =
        runTest {
            val earlyFailure =
                object : BackupRepository by backups {
                    override suspend fun restore(
                        sessionId: Long,
                        request: RestoreRequest,
                    ): Outcome<Unit, BackupError> {
                        backups.restoreProgress.value = RestoreProgress.Failed(BackupError.Storage)
                        kotlinx.coroutines.yield()
                        return Outcome.Success(Unit)
                    }
                }
            val vm = RestoreViewModel(7, earlyFailure, imports, playback, backgroundScope)
            runCurrent()
            vm.requestRestore()
            runCurrent()
            assertEquals(
                BackupError.Storage,
                vm.uiState.value.error,
                "A failure emitted during enqueue must stay visible",
            )
        }

    @Test fun acceptedRestoreSurvivesTheCallerAndBusyClearsWhenTheJobFinishes() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val delayed =
                object : BackupRepository by backups {
                    override suspend fun restore(
                        sessionId: Long,
                        request: RestoreRequest,
                    ): Outcome<Unit, BackupError> {
                        entered.complete(Unit)
                        release.await()
                        return backups.restore(sessionId, request)
                    }
                }
            val vm = RestoreViewModel(7, delayed, imports, playback, scope)
            runCurrent()
            val caller = launch { vm.requestRestore() }
            entered.await()
            caller.cancel()
            assertTrue(vm.uiState.value.busy)
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, backups.restoreRequests.size)
            assertFalse(vm.uiState.value.busy)
            scope.cancel()
        }

    @Test fun busyClearsWhenCancellationPreventsTheRestoreBodyFromStarting() =
        runTest {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val vm = RestoreViewModel(7, backups, imports, playback, scope)
            runCurrent()
            val caller = launch(start = CoroutineStart.UNDISPATCHED) { vm.requestRestore() }
            assertTrue(vm.uiState.value.busy)
            scope.cancel()
            advanceUntilIdle()
            assertTrue(caller.isCancelled)
            assertFalse(vm.uiState.value.busy)
            assertTrue(backups.restoreRequests.isEmpty())
        }

    @Test fun cancelledInspectionAndInactiveScopeDoNotStrandTheScreen() =
        runTest {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val vm = RestoreViewModel(7, backups, imports, playback, scope)
            scope.cancel()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.loading)
            assertTrue(backups.calls.isEmpty())
            val active = CoroutineScope(coroutineContext + SupervisorJob())
            val ready = RestoreViewModel(7, backups, imports, playback, active)
            runCurrent()
            active.cancel()
            ready.requestRestore()
            assertFalse(ready.uiState.value.busy)
            assertEquals(BackupError.Io, ready.uiState.value.error)
        }
}

internal fun restorePreview() =
    BackupPreview(
        createdAt = 1_791_072_000_000L,
        appVersionName = "1.3",
        automatic = false,
        podcasts = 142,
        groups = 6,
        played = 3214,
        inProgress = 3,
        upNext = 12,
        settings = 8,
        includesCredentials = true,
        localPodcastsNotInBackup = 9,
        localGroupsNotInBackup = 2,
        warnings = emptyList(),
        linkedServerHost = null,
        writtenOn = "android",
    )
