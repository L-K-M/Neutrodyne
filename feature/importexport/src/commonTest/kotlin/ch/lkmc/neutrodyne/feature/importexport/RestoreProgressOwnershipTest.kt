// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.RestoreProgress
import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportOptions
import ch.lkmc.neutrodyne.core.model.ImportSource
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.RestoreMode
import ch.lkmc.neutrodyne.core.testing.FakeBackupRepository
import ch.lkmc.neutrodyne.core.testing.FakeImportRepository
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreProgressOwnershipTest : MainDispatcherTest() {
    @Test fun foreignRunningFailureAndFinishCannotReplaceCurrentProgress() =
        runTest {
            val backups = FakeBackupRepository().apply { inspectOutcome = Outcome.Success(restorePreview()) }
            val imports = FakeImportRepository()
            val vm =
                RestoreViewModel(
                    7,
                    backups,
                    imports,
                    FakePlaybackController(),
                    backgroundScope,
                    restoreDownloads(),
                )
            runCurrent()
            vm.requestRestore()
            val running = RestoreProgress.Running(7, "library", 3, 12)
            backups.restoreProgress.value = running
            runCurrent()

            for (foreign in listOf(
                RestoreProgress.Running(PREVIOUS_SESSION_ID, "episodes", 10, 20),
                RestoreProgress.Failed(PREVIOUS_SESSION_ID, BackupError.Storage),
                RestoreProgress.Finished(PREVIOUS_SESSION_ID, false),
            )) {
                backups.restoreProgress.value = foreign
                runCurrent()
                assertEquals(running, vm.uiState.value.progress)
                assertNull(vm.uiState.value.error)
            }

            backups.restoreProgress.value = RestoreProgress.Failed(7, BackupError.Io)
            runCurrent()
            assertEquals(BackupError.Io, vm.uiState.value.error)
            backups.restoreProgress.value = RestoreProgress.Running(PREVIOUS_SESSION_ID, "library", 0, 1)
            runCurrent()
            assertEquals(BackupError.Io, vm.uiState.value.error, "Another session must not hide the current failure")
        }

    @Test fun reopeningQueuedSessionDoesNotAdoptPreviousSessionsFailure() =
        runTest {
            val backups = FakeBackupRepository().apply { inspectOutcome = Outcome.Success(restorePreview()) }
            val imports = FakeImportRepository().apply { createFormat = ImportFormat.NEUTRODYNE_BACKUP }
            // A previous restore failed; the singleton runner retains that terminal progress.
            backups.restoreProgress.value = RestoreProgress.Failed(PREVIOUS_SESSION_ID, BackupError.Storage)
            val id = (imports.create(ImportSource("file:///second.zip", null)) as Outcome.Success).value
            // The second session has been accepted and enqueued, but its worker has not started yet.
            imports.sessions.value = imports.sessions.value + (
                id to
                    imports.sessions.value.getValue(id).copy(
                        options =
                            ImportOptions(
                                restoreRequestedAt = 20L,
                                restoreMode = RestoreMode.MERGE,
                                restoreCategories = setOf(RestoreCategory.HISTORY, RestoreCategory.UP_NEXT),
                            ),
                    )
            )
            val vm =
                RestoreViewModel(
                    id,
                    backups,
                    imports,
                    FakePlaybackController(),
                    backgroundScope,
                    restoreDownloads(),
                )
            runCurrent()
            assertTrue(vm.uiState.value.requested)
            assertNull(vm.uiState.value.error, "Queued session B must not inherit failed session A's Storage error")
            assertNull(vm.uiState.value.progress)
        }

    @Test fun currentRunningProgressClearsAnUnrelatedOldError() =
        runTest {
            val backups = FakeBackupRepository().apply { inspectOutcome = Outcome.Success(restorePreview()) }
            val imports = FakeImportRepository().apply { createFormat = ImportFormat.NEUTRODYNE_BACKUP }
            backups.restoreProgress.value = RestoreProgress.Failed(PREVIOUS_SESSION_ID, BackupError.Storage)
            val id = (imports.create(ImportSource("file:///second.zip", null)) as Outcome.Success).value
            imports.sessions.value = imports.sessions.value + (
                id to
                    imports.sessions.value
                        .getValue(
                            id,
                        ).copy(options = ImportOptions(restoreRequestedAt = 20L, restoreMode = RestoreMode.MERGE))
            )
            val vm =
                RestoreViewModel(
                    id,
                    backups,
                    imports,
                    FakePlaybackController(),
                    backgroundScope,
                    restoreDownloads(),
                )
            runCurrent()
            backups.restoreProgress.value = RestoreProgress.Running(id, "library", 0, 12)
            runCurrent()
            assertNull(vm.uiState.value.error, "B is now running but stale A error still suppresses restore progress")
            assertEquals(RestoreProgress.Running(id, "library", 0, 12), vm.uiState.value.progress)
        }
}

private const val PREVIOUS_SESSION_ID = 99L

private fun restoreDownloads() =
    ch.lkmc.neutrodyne.core.domain.RestoreDownloadRequests(
        ch.lkmc.neutrodyne.core.testing
            .FakeDownloadController(),
        ch.lkmc.neutrodyne.core.testing
            .FakePlaybackMediaLookup(),
    )
