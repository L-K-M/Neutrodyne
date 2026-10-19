// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.model.DownloadAllEstimate
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedPrefs
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.MediaFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class FakeFeedRepositoryTest {
    @Test fun preferencesVisitsAndOrderRemainIndependentBySource() =
        runTest {
            val repo = FakeFeedRepository()
            val group = FeedSource.Group(1)
            repo.setFilters(group, FeedFilters(unplayedOnly = true, media = MediaFilter.VIDEO, minSortDate = 42))
            repo.setHideOlderThanDays(group, 7)
            repo.markVisited(group, 123)
            repo.setFeedOrder(group, FeedOrder.OLDEST_FIRST)
            repo.observePrefs(group).test {
                val prefs = awaitItem()
                assertEquals(FeedOrder.OLDEST_FIRST, prefs.feedOrder)
                assertEquals(7, prefs.hideOlderThanDays)
                assertEquals(123, prefs.lastViewedAt)
                assertEquals(TestClock.DEFAULT_NOW - 7 * DAY_MS, prefs.filters.minSortDate)
                repo.setFilters(group, FeedFilters())
                assertEquals(7, awaitItem().hideOlderThanDays)
            }
            assertEquals(FeedPrefs(), repo.observePrefs(FeedSource.Group(2)).first())
        }

    @Test fun unsupportedWritesAreRejected() =
        runTest {
            val repo = FakeFeedRepository()
            val podcast = FeedSource.Podcast(1)
            assertFailsWith<IllegalArgumentException> { repo.setFilters(podcast, FeedFilters()) }
            assertFailsWith<IllegalArgumentException> { repo.setHideOlderThanDays(podcast, null) }
            assertFailsWith<IllegalArgumentException> { repo.setHideOlderThanDays(FeedSource.All, 0) }
            assertFailsWith<IllegalArgumentException> { repo.setFeedOrder(FeedSource.All, FeedOrder.OLDEST_FIRST) }
            assertFailsWith<IllegalArgumentException> {
                repo.setFeedOrder(FeedSource.Ungrouped, FeedOrder.NEWEST_FIRST)
            }
            repo.markVisited(podcast, 123)
            assertNull(repo.prefs.value[podcast])
            repo.writeError = IllegalStateException("disk")
            assertFailsWith<IllegalStateException> { repo.setFilters(FeedSource.All, FeedFilters()) }
            assertEquals(emptyMap(), repo.prefs.value)
        }

    @Test fun hourlyAgeWindowAndVirtualVisitInitializationMatchProduction() =
        runTest {
            val repo = FakeFeedRepository(TestClock.from(testScheduler, TestClock.DEFAULT_NOW + 500))
            repo.setHideOlderThanDays(FeedSource.All, 1)
            repo.observePrefs(FeedSource.All).test {
                val initial = awaitItem()
                assertEquals(TestClock.DEFAULT_NOW + 500, initial.lastViewedAt)
                assertEquals(TestClock.DEFAULT_NOW - DAY_MS, initial.filters.minSortDate)
                advanceTimeBy(FeedPrefs.HOUR_MS - 500)
                runCurrent()
                assertEquals(TestClock.DEFAULT_NOW + FeedPrefs.HOUR_MS - DAY_MS, awaitItem().filters.minSortDate)
                repo.markVisited(FeedSource.All, 99)
                assertEquals(99, awaitItem().lastViewedAt)
            }
        }

    @Test fun downloadAllReturnsConfiguredSnapshotAndRecordsSources() =
        runTest {
            val repo = FakeFeedRepository()
            val group = FeedSource.Group(7)
            val estimate = DownloadAllEstimate(listOf(42, 41), 250, 123_456, 1, true)
            repo.downloadAllEstimates[group] = estimate
            assertEquals(estimate, repo.downloadAllEstimate(group))
            assertEquals(DownloadAllEstimate(emptyList(), 0, 0, 0, false), repo.downloadAllEstimate(FeedSource.All))
            assertEquals(listOf(group, FeedSource.All), repo.downloadAllRequests)
        }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
