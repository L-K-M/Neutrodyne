// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.DeletedGroupToken
import ch.lkmc.neutrodyne.core.domain.GroupError
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_deleted
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_undo
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_undo_expired
import ch.lkmc.neutrodyne.core.ui.resources.group_edit_undo_name_taken
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GroupEditUndoTest {
    // Resource reads can dispatch to IO. Warm each key before measuring scheduler time.
    private suspend fun warmStrings() {
        getString(Res.string.group_edit_deleted)
        getString(Res.string.group_edit_undo)
        getString(Res.string.group_edit_undo_expired)
        getString(Res.string.group_edit_undo_name_taken, "Tech")
        getString(Res.string.write_failed)
    }

    @Test fun snackbarOffersUndoAndDismissesAtTheOriginalDeadline() =
        runTest {
            warmStrings()
            val undoLabel = getString(Res.string.group_edit_undo)
            val expiredText = getString(Res.string.group_edit_undo_expired)
            val host = SnackbarHostState()
            val done = GroupEditDone.Deleted(DeletedGroupToken("token"), "Tech", testScheduler.timeSource.markNow())
            // A root-host queue delayed the editor's effect by two seconds.
            advanceTimeBy(2_000)
            var calls = 0
            val job =
                launch {
                    showDeleteUndo(host, done) {
                        calls++
                        Outcome.Success(7L)
                    }
                }
            runCurrent()
            assertEquals(undoLabel, host.currentSnackbarData?.visuals?.actionLabel)
            assertEquals(SnackbarDuration.Long, host.currentSnackbarData?.visuals?.duration)
            advanceTimeBy(7_999)
            assertTrue(job.isActive)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(job.isCompleted)
            assertNull(host.currentSnackbarData)
            assertEquals(0, calls)
        }

    @Test fun collisionKeepsUndoForRetryWithoutExtendingTheWindow() =
        runTest {
            warmStrings()
            val undoLabel = getString(Res.string.group_edit_undo)
            val expiredText = getString(Res.string.group_edit_undo_expired)
            val host = SnackbarHostState()
            val done = GroupEditDone.Deleted(DeletedGroupToken("token"), "Tech", testScheduler.timeSource.markNow())
            var calls = 0
            launch {
                showDeleteUndo(host, done) {
                    calls++
                    if (calls == 1) Outcome.Failure(GroupError.NameTaken(9)) else Outcome.Success(7L)
                }
            }
            runCurrent()
            host.currentSnackbarData!!.performAction()
            runCurrent()
            assertEquals(SnackbarDuration.Indefinite, host.currentSnackbarData?.visuals?.duration)
            assertTrue(
                host.currentSnackbarData!!
                    .visuals.message
                    .contains("Tech"),
            )
            advanceTimeBy(9_000)
            host.currentSnackbarData!!.performAction()
            runCurrent()
            assertEquals(2, calls)
            assertNull(host.currentSnackbarData)
        }

    @Test fun collisionAndExpiryAreNeverReportedAsSuccessfulRestores() =
        runTest {
            warmStrings()
            val undoLabel = getString(Res.string.group_edit_undo)
            val expiredText = getString(Res.string.group_edit_undo_expired)
            val host = SnackbarHostState()
            val done = GroupEditDone.Deleted(DeletedGroupToken("token"), "Tech", testScheduler.timeSource.markNow())
            val job = launch { showDeleteUndo(host, done) { Outcome.Failure(GroupError.UndoExpired) } }
            runCurrent()
            host.currentSnackbarData!!.performAction()
            runCurrent()
            assertEquals(expiredText, host.currentSnackbarData?.visuals?.message)
            host.currentSnackbarData!!.dismiss()
            runCurrent()
            assertTrue(job.isCompleted)
        }
}
