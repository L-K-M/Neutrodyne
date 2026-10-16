// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.domain.MarkFeedPlayedResult
import ch.lkmc.neutrodyne.core.model.FeedCounts
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.VirtualCounts
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FakeCountsTest {
    @Test fun countFixturesAreObservableAndBulkCountsUseSourceAndCutoff() =
        runTest {
            val repo = FakeFeedRepository()
            repo.observeGroupCounts(0).test {
                assertEquals(emptyMap(), awaitItem())
                repo.groupCounts.value = mapOf(1L to FeedCounts(2, 1))
                assertEquals(mapOf(1L to FeedCounts(2, 1)), awaitItem())
            }
            repo.observeVirtualCounts(0).test {
                assertEquals(VirtualCounts(), awaitItem())
                repo.virtualCounts.value = VirtualCounts(FeedCounts(5, 3), FeedCounts(1, 0))
                assertEquals(repo.virtualCounts.value, awaitItem())
            }
            repo.unplayedCounts[FeedSource.All to 123L] = 7
            assertEquals(7, repo.countUnplayed(FeedSource.All, 123))
            assertEquals(0, repo.countUnplayed(FeedSource.All, null))
            assertEquals(0, repo.countUnplayed(FeedSource.Ungrouped, 123))
        }

    @Test fun staleGuardRecordsNoWriteAndRetryConsumesTheFixture() =
        runTest {
            val repo = FakeEpisodeRepository()
            repo.unplayedCounts[FeedSource.All to 123L] = 2
            assertEquals(
                MarkFeedPlayedResult.SelectionChanged(2),
                repo.markFeedPlayed(FeedSource.All, 123, 1),
            )
            assertTrue(repo.calls.isEmpty())
            assertEquals(MarkFeedPlayedResult.Played(2), repo.markFeedPlayed(FeedSource.All, 123, 2))
            assertEquals(listOf("markFeedPlayed(All, 123, 2)"), repo.calls)
            assertEquals(MarkFeedPlayedResult.SelectionChanged(0), repo.markFeedPlayed(FeedSource.All, 123, 2))
            assertFailsWith<IllegalArgumentException> { repo.markFeedPlayed(FeedSource.All, null, -1) }
            repo.writeError = IllegalStateException("disk")
            assertFailsWith<IllegalStateException> { repo.markFeedPlayed(FeedSource.All, null, 0) }
        }
}
