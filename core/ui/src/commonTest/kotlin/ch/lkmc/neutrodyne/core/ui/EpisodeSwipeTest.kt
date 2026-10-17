// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.model.SwipeAction
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeSwipeTest {
    @Test
    fun onlyReleasedDistancePastFortyPercentCommits() {
        assertFalse(swipeCommits(0f, 0))
        for (direction in listOf(-1, 1)) {
            assertFalse(swipeCommits(39f * direction, 100))
            assertFalse(swipeCommits(40f * direction, 100))
            assertTrue(swipeCommits(41f * direction, 100))
        }
    }

    @Test
    fun playedActionTogglesAndUndeliveredActionsStayInert() {
        val row = testEpisodeRow(7)
        assertEquals(
            EpisodeAction.SetPlayed(7, true, PlayedActionOrigin.SWIPE),
            swipeEpisodeAction(SwipeAction.MARK_PLAYED, row),
        )
        assertEquals(
            EpisodeAction.SetPlayed(7, false, PlayedActionOrigin.SWIPE),
            swipeEpisodeAction(SwipeAction.MARK_PLAYED, row.copy(playedAt = 42)),
        )
        for (action in SwipeAction.entries.filter { it != SwipeAction.MARK_PLAYED }) {
            assertNull(swipeEpisodeAction(action, row), action.name)
        }
    }
}
