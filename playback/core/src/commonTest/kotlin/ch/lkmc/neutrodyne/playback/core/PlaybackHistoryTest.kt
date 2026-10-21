// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlaybackHistoryTest {
    private val history = PlaybackHistory()

    @Test fun historyKeepsOnlyTheLastTwentyItems() {
        (1L..25L).forEach { history.record(item(it)) }
        assertEquals((6L..25L).map { item(it) }, history.items.value)
    }

    @Test fun previousAboveThreeSecondsRestarts() {
        history.record(item(1))
        assertIs<PreviousAction.Restart>(history.previous(3_001))
        assertIs<PreviousAction.Restart>(history.previous(Long.MAX_VALUE))
    }

    @Test fun previousAtOrBelowThreeSecondsGoesBackToTheLastItem() {
        // Empty history has nothing to go back to, so it restarts too.
        assertIs<PreviousAction.Restart>(history.previous(0))
        assertIs<PreviousAction.Restart>(history.previous(3_000))

        history.record(item(1))
        history.record(item(2))
        assertEquals(PreviousAction.GoBack(item(2)), history.previous(3_000))
        assertEquals(PreviousAction.GoBack(item(2)), history.previous(0))
    }

    @Test fun goBackRetainsTheRecordedQueueOrigin() {
        history.record(item(7, QueueOrigin.CONTEXT))
        val action = history.previous(0)
        assertIs<PreviousAction.GoBack>(action)
        assertEquals(QueueItem(7, QueueOrigin.CONTEXT), action.item)
    }

    @Test fun failedTransactionPeekDoesNotConsume() {
        history.record(item(1))
        history.record(item(2))
        val first = history.previous(0)
        assertIs<PreviousAction.GoBack>(first)

        // The arrival transaction failed: without a consume call the same item is offered again.
        assertEquals(first, history.previous(0))
        assertEquals(listOf(item(1), item(2)), history.items.value)
    }

    @Test fun consumeAfterSuccessDropsTheReturnedItem() {
        history.record(item(1))
        history.record(item(2))
        val action = history.previous(0)
        assertIs<PreviousAction.GoBack>(action)

        history.consume(action.item)
        assertEquals(listOf(item(1)), history.items.value)
        assertEquals(PreviousAction.GoBack(item(1)), history.previous(0))
    }

    @Test fun consumeOnlyDropsTheMatchingTailItem() {
        history.record(item(1))
        history.record(item(2))
        history.consume(item(1))
        history.consume(item(9))
        assertEquals(listOf(item(1), item(2)), history.items.value)
    }

    @Test fun clearEmptiesHistory() {
        history.record(item(1))
        history.clear()
        assertTrue(history.items.value.isEmpty())
        assertIs<PreviousAction.Restart>(history.previous(0))
    }
}
