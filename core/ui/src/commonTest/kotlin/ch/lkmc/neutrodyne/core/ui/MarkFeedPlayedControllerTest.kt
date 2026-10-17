// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MarkFeedPlayedControllerTest {
    @Test
    fun latestOptionCancelsOldCountAndDismissCancelsPendingRead() =
        runTest {
            val first = CompletableDeferred<Int>()
            var reads = 0
            val controller =
                MarkFeedPlayedController(backgroundScope, TestClock(), { _, _ ->
                    if (reads++ == 0) first.await() else 7
                }) { _, _, _ -> error("Unexpected write") }
            controller.open(FeedSource.All, UiText.Raw("All"))
            runCurrent()
            assertTrue(controller.state.value!!.busy)
            controller.confirm()
            controller.select(MarkPlayedOption.WEEK)
            runCurrent()
            first.complete(99)
            runCurrent()
            assertEquals(7, controller.state.value?.count)
            assertEquals(MarkPlayedOption.WEEK, controller.state.value?.option)
            controller.dismiss()
            assertNull(controller.state.value)
        }

    @Test
    fun cancelledReadCannotRestoreDismissedDialogOrReplaceNewerOption() =
        runTest {
            lateinit var pending: Continuation<Int>
            var reads = 0
            val controller =
                MarkFeedPlayedController(backgroundScope, TestClock(), { _, _ ->
                    if (reads++ % 2 == 0) suspendCoroutine { pending = it } else 7
                }) { _, _, _ -> error("Unexpected write") }
            controller.open(FeedSource.All, UiText.Raw("All"))
            runCurrent()
            controller.select(MarkPlayedOption.WEEK)
            runCurrent()
            pending.resume(99)
            runCurrent()
            assertEquals(7, controller.state.value?.count)

            controller.open(FeedSource.All, UiText.Raw("All"))
            runCurrent()
            controller.dismiss()
            pending.resume(99)
            runCurrent()
            assertNull(controller.state.value)
        }

    @Test
    fun calendarOptionsClampMonthEndsAndUseUtcTimeOfDay() =
        runTest {
            val clock = TestClock(Instant.parse("2026-05-31T12:34:56Z").toEpochMilliseconds())
            val controller =
                MarkFeedPlayedController(backgroundScope, clock, { _, _ -> 1 }) { _, _, _ -> MarkPlayedOutcome.Played }
            controller.open(FeedSource.All, UiText.Raw("All"))
            for ((option, expected) in listOf(
                MarkPlayedOption.WEEK to "2026-05-24T12:34:56Z",
                MarkPlayedOption.MONTH to "2026-04-30T12:34:56Z",
                MarkPlayedOption.THREE_MONTHS to "2026-02-28T12:34:56Z",
            )) {
                controller.select(option)
                runCurrent()
                assertEquals(Instant.parse(expected).toEpochMilliseconds(), controller.state.value?.sortDateBefore)
            }
            controller.select(MarkPlayedOption.ALL)
            runCurrent()
            assertNull(controller.state.value?.sortDateBefore)
        }

    @Test
    fun countFailureAllowsReselectionAndWriteFailureKeepsFrozenCount() =
        runTest {
            var failCount = true
            var failWrite = true
            val controller =
                MarkFeedPlayedController(backgroundScope, TestClock(), { _, _ ->
                    if (failCount) error("read failure") else 2
                }) { _, _, _ ->
                    if (failWrite) error("write failure") else MarkPlayedOutcome.Played
                }
            controller.open(FeedSource.All, UiText.Raw("All"))
            runCurrent()
            assertNull(controller.state.value?.count)
            assertTrue(controller.state.value?.error != null)
            controller.confirm()
            failCount = false
            controller.select(MarkPlayedOption.WEEK)
            runCurrent()
            val before = controller.state.value?.sortDateBefore
            controller.confirm()
            runCurrent()
            assertEquals(2, controller.state.value?.count)
            assertEquals(before, controller.state.value?.sortDateBefore)
            assertFalse(controller.state.value!!.busy)
            assertTrue(controller.state.value?.error != null)
            failWrite = false
            controller.confirm()
            runCurrent()
            assertNull(controller.state.value)
        }

    @Test
    fun writingBlocksDuplicateConfirmSelectionAndDismiss() =
        runTest {
            val result = CompletableDeferred<MarkPlayedOutcome>()
            var writes = 0
            val controller =
                MarkFeedPlayedController(backgroundScope, TestClock(), { _, _ -> 1 }) { _, _, _ ->
                    writes++
                    result.await()
                }
            controller.open(FeedSource.All, UiText.Raw("All"))
            runCurrent()
            controller.confirm()
            controller.confirm()
            controller.dismiss()
            controller.select(MarkPlayedOption.WEEK)
            controller.open(FeedSource.Ungrouped, UiText.Raw("Ungrouped"))
            runCurrent()
            assertEquals(1, writes)
            assertEquals(FeedSource.All, controller.state.value?.source)
            assertEquals(MarkPlayedOption.ALL, controller.state.value?.option)
            result.complete(MarkPlayedOutcome.SelectionChanged(0))
            runCurrent()
            assertEquals(0, controller.state.value?.count)
            assertTrue(controller.state.value!!.selectionChanged)
            controller.confirm()
            runCurrent()
            assertEquals(1, writes)
            controller.dismiss()
            assertNull(controller.state.value)
        }
}
