// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.FeedPrefs
import ch.lkmc.neutrodyne.core.model.GroupCounts
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupCountsWindowTest {
    @Test fun roundsDownBeforeApplyingTheThirtyDayWindow() {
        val hour = 40 * GroupCounts.DAY_MS + 2 * FeedPrefs.HOUR_MS
        val expected = 10 * GroupCounts.DAY_MS + 2 * FeedPrefs.HOUR_MS

        assertEquals(expected, groupCountsWindowStart(hour))
        assertEquals(expected, groupCountsWindowStart(hour + FeedPrefs.HOUR_MS - 1))
        assertEquals(expected + FeedPrefs.HOUR_MS, groupCountsWindowStart(hour + FeedPrefs.HOUR_MS))
    }
}
