// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.paging.testing.asSnapshot
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.RefreshStatus
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeRepository
import ch.lkmc.neutrodyne.core.testing.FakeFeedRepository
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import ch.lkmc.neutrodyne.core.testing.testLibraryTile
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.date_today
import ch.lkmc.neutrodyne.core.ui.resources.date_yesterday
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `FeedsViewModel` on the desktop JVM (09 Test matrix): feed paging against
 * [FakeFeedRepository]'s `PagingData.from` pages, the chips/offline/refresh state machine and the
 * row/mark-all actions — the repository calls asserted on the fakes' logs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedsViewModelTest : MainDispatcherTest() {
    private val feeds = FakeFeedRepository()
    private val episodes = FakeEpisodeRepository()
    private val refreshController = FakeRefreshController()
    private val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
    private val podcasts = FakePodcastRepository()
    private val clock = TestClock()

    private fun viewModel() = FeedsViewModel(feeds, episodes, refreshController, network, podcasts, clock)

    private fun TestScope.collect(viewModel: FeedsViewModel) {
        backgroundScope.launch { viewModel.uiState.collect {} }
    }

    @Test
    fun initialStateBeforeSubscription() =
        runTest {
            val viewModel = viewModel()
            assertEquals(FeedsUiState(), viewModel.uiState.value)
        }

    @Test
    fun feedPagesAllSourceNewestFirst() =
        runTest {
            val viewModel = viewModel()
            feeds.rows.value = listOf(testEpisodeRow(1), testEpisodeRow(2))

            val items = viewModel.feed.take(1).asSnapshot()
            advanceUntilIdle()

            assertEquals(
                Triple(FeedSource.All, FeedFilters(), FeedOrder.NEWEST_FIRST),
                feeds.lastRequest,
            )
            // One day header for the same-day rows, then the two episodes.
            assertEquals(3, items.size)
            assertIs<FeedItem.Day>(items[0])
            assertIs<FeedItem.Episode>(items[1])
            assertIs<FeedItem.Episode>(items[2])
        }

    @Test
    fun filtersFlowIntoThePageRequest() =
        runTest {
            val viewModel = viewModel()
            viewModel.onFiltersChange(FeedFilters(unplayedOnly = true))

            viewModel.feed.take(1).asSnapshot()
            advanceUntilIdle()

            assertEquals(FeedFilters(unplayedOnly = true), feeds.lastRequest?.second)
        }

    @Test
    fun feedInsertsOneHeaderPerDay() =
        runTest {
            val viewModel = viewModel()
            feeds.rows.value =
                listOf(
                    testEpisodeRow(1, sortDate = TestClock.DEFAULT_NOW),
                    testEpisodeRow(2, sortDate = TestClock.DEFAULT_NOW - 2 * HOUR),
                    // 36 h earlier is always a previous local day (max TZ offset is 14 h).
                    testEpisodeRow(3, sortDate = TestClock.DEFAULT_NOW - 36 * HOUR),
                )

            val items = viewModel.feed.take(1).asSnapshot()
            advanceUntilIdle()

            val days = items.filterIsInstance<FeedItem.Day>()
            assertEquals(2, days.size)
            assertEquals(
                listOf(
                    UiText.Res(Res.string.date_today),
                    UiText.Res(Res.string.date_yesterday),
                ),
                days.map { it.label },
            )
            assertEquals(listOf(1L, 2L, 3L), items.filterIsInstance<FeedItem.Episode>().map { it.row.id })
        }

    @Test
    fun uiStateReflectsFiltersAndConnectivity() =
        runTest {
            val viewModel = viewModel()
            podcasts.tiles.value = listOf(testLibraryTile(1))
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            assertEquals(
                FeedsUiState(filters = FeedFilters(), offline = false, refreshing = false, hasSubscriptions = true),
                viewModel.uiState.value,
            )

            network.setStatus(FakeNetworkMonitor.OFFLINE)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.offline)

            val filtered = FeedFilters(unplayedOnly = true)
            viewModel.onFiltersChange(filtered)
            advanceUntilIdle()
            assertEquals(filtered, viewModel.uiState.value.filters)
        }

    @Test
    fun emptyLibraryDisablesSubscriptions() =
        runTest {
            val viewModel = viewModel()
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            // The fake has no tiles: the onboarding empty state is the visible outcome.
            assertTrue(!viewModel.uiState.value.hasSubscriptions)

            podcasts.tiles.value = listOf(testLibraryTile(7))
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.hasSubscriptions)
        }

    @Test
    fun refreshTracksRunningAndCapsAtThirtySeconds() =
        runTest {
            val viewModel = viewModel()
            backgroundScope.launch { viewModel.uiState.collect {} }
            runCurrent()

            refreshController.status.value =
                RefreshStatus(running = true, scope = RefreshScope.All, done = 0, total = 1, lastRunFinishedAt = null)
            runCurrent()
            assertTrue(viewModel.uiState.value.refreshing)

            // The indicator caps at 30 s while the run itself continues (08 Pull to refresh).
            advanceTimeBy(31_000)
            runCurrent()
            assertTrue(!viewModel.uiState.value.refreshing)
            assertTrue(refreshController.status.value.running)
        }

    @Test
    fun onRefreshRequestsAllFeeds() =
        runTest {
            val viewModel = viewModel()
            viewModel.onRefresh()
            assertEquals(listOf("refreshFeed(All)"), refreshController.calls)
        }

    @Test
    fun markAllPlayedCoversTheWholeFeed() =
        runTest {
            val viewModel = viewModel()
            viewModel.markAllPlayed()
            advanceUntilIdle()
            assertEquals(listOf("markFeedPlayed(All, null)"), episodes.calls)
        }

    @Test
    fun rowPlayedActionWritesToRepository() =
        runTest {
            val viewModel = viewModel()
            viewModel.onRowAction(EpisodeAction.SetPlayed(42, played = true))
            advanceUntilIdle()
            assertEquals(listOf("setPlayed([42], true)"), episodes.calls)
        }

    @Test
    fun playerAndDownloadActionsAreInertAtM1a() =
        runTest {
            val viewModel = viewModel()
            viewModel.onRowAction(EpisodeAction.PlayToggle(42))
            viewModel.onRowAction(EpisodeAction.DownloadToggle(42))
            advanceUntilIdle()
            assertTrue(episodes.calls.isEmpty())
        }

    /** A throwing repository write becomes a snackbar message — not an uncaught exception (01). */
    @Test
    fun setPlayedWriteFailurePostsAMessage() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            episodes.writeError = Exception("disk full")

            viewModel.onRowAction(EpisodeAction.SetPlayed(42, played = true))
            advanceUntilIdle()

            assertTrue(episodes.calls.isEmpty())
            val message = viewModel.uiState.value.messages.single()
            assertEquals(Res.string.write_failed, (message.text as UiText.Res).id)

            viewModel.onMessageShown(message.id)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.messages.isEmpty())
        }

    @Test
    fun markAllPlayedWriteFailurePostsAMessage() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            episodes.writeError = Exception("disk full")

            viewModel.markAllPlayed()
            advanceUntilIdle()

            assertTrue(episodes.calls.isEmpty())
            assertTrue(viewModel.uiState.value.messages.isNotEmpty())
        }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
