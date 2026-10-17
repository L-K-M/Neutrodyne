// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.RestoreProgress
import ch.lkmc.neutrodyne.core.model.BackupSummary
import ch.lkmc.neutrodyne.core.model.RestoreCategory
import ch.lkmc.neutrodyne.core.model.RestoreMode
import ch.lkmc.neutrodyne.core.model.RestoreRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The fake's snapshot-state rules (05): the enable switch writes `enabled`, adopting or
 * discarding a foreign snapshot clears `foreignPending`, and a successful restore reports
 * `Finished` on `observeRestore`.
 */
class FakeBackupRepositoryTest {
    @Test fun snapshotEnableWritesThroughToStatus() =
        runTest {
            val repo = FakeBackupRepository()
            assertEquals(false, repo.observeSnapshotStatus().first().enabled)

            repo.setSnapshotEnabled(true)
            assertEquals(true, repo.observeSnapshotStatus().first().enabled)
            assertEquals(listOf("setSnapshotEnabled(true)"), repo.calls)
        }

    @Test fun foreignPendingClearsOnRestoreOrDiscard() =
        runTest {
            val repo = FakeBackupRepository()
            repo.snapshotStatus.value = repo.snapshotStatus.value.copy(foreignPending = true)
            repo.discardAndroidBackup()
            assertEquals(false, repo.observeSnapshotStatus().first().foreignPending)

            repo.snapshotStatus.value = repo.snapshotStatus.value.copy(foreignPending = true)
            repo.androidBackupOutcome = Outcome.Success(7L)
            assertEquals(Outcome.Success(7L), repo.restoreAndroidBackup())
            assertEquals(false, repo.observeSnapshotStatus().first().foreignPending)
        }

    @Test fun restoreRecordsRequestAndReportsFinished() =
        runTest {
            val repo = FakeBackupRepository()
            val request = RestoreRequest(RestoreMode.REPLACE, RestoreCategory.entries.toSet())

            assertEquals(Outcome.Success(Unit), repo.restore(4L, request))
            assertEquals(listOf(4L to request), repo.restoreRequests)
            assertEquals(
                RestoreProgress.Finished(4L, auto = false),
                repo.observeRestore().first(),
            )
        }

    @Test fun failedRestoreReportsNothing() =
        runTest {
            val repo = FakeBackupRepository()
            repo.restoreOutcome = Outcome.Failure(BackupError.RestoreRunning)

            assertEquals(
                Outcome.Failure(BackupError.RestoreRunning),
                repo.restore(4L, RestoreRequest(RestoreMode.MERGE, emptySet())),
            )
            assertEquals(null, repo.observeRestore().first())
        }

    @Test fun writeSnapshotNowReturnsTheStatusOrTheFixtureError() =
        runTest {
            val repo = FakeBackupRepository()
            assertEquals(Outcome.Success(repo.snapshotStatus.value), repo.writeSnapshotNow())

            repo.snapshotNowError = BackupError.Io
            assertEquals(Outcome.Failure(BackupError.Io), repo.writeSnapshotNow())
        }

    @Test fun createBackupAndPreflightReturnFixtures() =
        runTest {
            val repo = FakeBackupRepository()
            val summary = BackupSummary(2_400, 142, 3_214)
            repo.createOutcome = Outcome.Success(summary)

            assertEquals(Outcome.Success(summary), repo.createBackup("content://dest", true))
            assertEquals(repo.preflightCounts, repo.preflight(true))
            assertEquals(
                listOf("createBackup(content://dest, true)", "preflight(true)"),
                repo.calls,
            )
        }
}
