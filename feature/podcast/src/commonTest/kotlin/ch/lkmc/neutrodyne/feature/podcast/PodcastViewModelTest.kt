// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import androidx.paging.testing.asSnapshot
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.RefreshStatus
import ch.lkmc.neutrodyne.core.domain.UnsubscribeUseCase
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.model.ShowType
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeRepository
import ch.lkmc.neutrodyne.core.testing.FakeFeedRepository
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import ch.lkmc.neutrodyne.core.testing.testFeedInfo
import ch.lkmc.neutrodyne.core.testing.testPodcastDetail
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `PodcastViewModel` on the desktop JVM (09): detail/feed-info state, the episode page keyed by
 * `FeedSource.Podcast`, 05's serial/episode order defaults, and every action the screen exposes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PodcastViewModelTest : MainDispatcherTest() {
    private val podcasts = FakePodcastRepository()
    private val feeds = FakeFeedRepository()
    private val episodes = FakeEpisodeRepository()
    private val refreshController = FakeRefreshController()
    private val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)

    private fun viewModel(podcastId: Long = 7) =
        PodcastViewModel(podcastId, podcasts, feeds, episodes, refreshController, UnsubscribeUseCase(podcasts), network)

    private fun TestScope.collect(viewModel: PodcastViewModel) {
        backgroundScope.launch { viewModel.uiState.collect {} }
    }

    @Test
    fun initialStateBeforeSubscription() =
        runTest {
            assertEquals(PodcastUiState(), viewModel().uiState.value)
        }

    @Test
    fun detailAndFeedUrlLoad() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            podcasts.detail.value = testPodcastDetail(7, displayTitle = "Serial Box")
            podcasts.feedInfo.value = testFeedInfo(feedUrl = "https://feeds.example.com/sb")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.loaded)
            assertEquals("Serial Box", state.detail?.displayTitle)
            assertEquals("https://feeds.example.com/sb", state.feedUrl)
            assertTrue(!state.gone)
        }

    @Test
    fun loadedNullDetailIsGone() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.gone)
        }

    @Test
    fun episodicDefaultRequestsNewestFirst() =
        runTest {
            val viewModel = viewModel()
            podcasts.detail.value = testPodcastDetail(7, showType = ShowType.EPISODIC, episodeOrder = null)
            feeds.rows.value = listOf(testEpisodeRow(1, podcastId = 7))

            viewModel.feed.take(1).asSnapshot()
            advanceUntilIdle()

            assertEquals(
                Triple(FeedSource.Podcast(7), FeedFilters(), FeedOrder.NEWEST_FIRST),
                feeds.lastRequest,
            )
            assertEquals(FeedOrder.NEWEST_FIRST, viewModel.uiState.value.effectiveOrder)
        }

    @Test
    fun serialDefaultRequestsOldestFirst() =
        runTest {
            val viewModel = viewModel()
            podcasts.detail.value = testPodcastDetail(7, showType = ShowType.SERIAL, episodeOrder = null)

            collect(viewModel)
            viewModel.feed.take(1).asSnapshot()
            advanceUntilIdle()

            assertEquals(FeedOrder.OLDEST_FIRST, feeds.lastRequest?.third)
            assertEquals(FeedOrder.OLDEST_FIRST, viewModel.uiState.value.effectiveOrder)
        }

    @Test
    fun persistedOrderWinsOverShowType() =
        runTest {
            val viewModel = viewModel()
            podcasts.detail.value =
                testPodcastDetail(7, showType = ShowType.SERIAL, episodeOrder = FeedOrder.NEWEST_FIRST)

            viewModel.feed.take(1).asSnapshot()
            advanceUntilIdle()

            assertEquals(FeedOrder.NEWEST_FIRST, feeds.lastRequest?.third)
        }

    @Test
    fun setOrderPersistsPerPodcast() =
        runTest {
            val viewModel = viewModel()
            viewModel.setOrder(FeedOrder.OLDEST_FIRST)
            advanceUntilIdle()

            assertEquals(
                mapOf<FeedSource, FeedOrder>(FeedSource.Podcast(7) to FeedOrder.OLDEST_FIRST),
                feeds.orders.value,
            )
        }

    @Test
    fun filtersFlowToThePageRequest() =
        runTest {
            val viewModel = viewModel()
            podcasts.detail.value = testPodcastDetail(7)
            collect(viewModel)
            advanceUntilIdle()

            val filtered = FeedFilters(unplayedOnly = true)
            viewModel.onFiltersChange(filtered)
            advanceUntilIdle()
            assertEquals(filtered, viewModel.uiState.value.filters)

            viewModel.feed.take(1).asSnapshot()
            advanceUntilIdle()
            assertEquals(filtered, feeds.lastRequest?.second)
        }

    @Test
    fun refreshingTracksThePodcastScope() =
        runTest {
            val viewModel = viewModel()
            podcasts.detail.value = testPodcastDetail(7)
            collect(viewModel)
            advanceUntilIdle()

            // A refresh covering another podcast does not spin this screen's indicator.
            refreshController.status.value =
                RefreshStatus(
                    running = true,
                    scope = RefreshScope.Podcasts(listOf(9L)),
                    done = 0,
                    total = 1,
                    lastRunFinishedAt = null,
                )
            advanceUntilIdle()
            assertTrue(!viewModel.uiState.value.refreshing)

            refreshController.status.value =
                RefreshStatus(
                    running = true,
                    scope = RefreshScope.Podcasts(listOf(7L, 9L)),
                    done = 0,
                    total = 2,
                    lastRunFinishedAt = null,
                )
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.refreshing)

            refreshController.status.value =
                RefreshStatus(running = false, scope = null, done = 0, total = 0, lastRunFinishedAt = null)
            advanceUntilIdle()
            assertTrue(!viewModel.uiState.value.refreshing)
        }

    @Test
    fun offlineFlagTracksTheMonitor() =
        runTest {
            val viewModel = viewModel()
            podcasts.detail.value = testPodcastDetail(7)
            collect(viewModel)
            advanceUntilIdle()
            assertTrue(!viewModel.uiState.value.offline)

            network.setStatus(FakeNetworkMonitor.OFFLINE)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.offline)
        }

    @Test
    fun onRefreshRequestsThePodcastFeed() =
        runTest {
            viewModel().onRefresh()
            assertEquals(listOf("refreshFeed(${FeedSource.Podcast(7)})"), refreshController.calls)
        }

    @Test
    fun loadOlderStartsTheRfc5005Session() =
        runTest {
            viewModel().loadOlder()
            assertEquals(listOf("loadOlderEpisodes(7)"), refreshController.calls)
        }

    @Test
    fun retryFeedCallsTheRepository() =
        runTest {
            val viewModel = viewModel()
            viewModel.retryFeed()
            advanceUntilIdle()
            assertEquals(listOf("retry(7, true)"), podcasts.calls)
        }

    @Test
    fun setCredentialsReturnsTheOutcome() =
        runTest {
            podcasts.credentialsOutcome = Outcome.Failure(AddPodcastError.Network(NetError.Timeout))
            val viewModel = viewModel()

            val outcome = viewModel.setCredentials(BasicCredentials("u", "p"))
            assertIs<Outcome.Failure<*>>(outcome)
            assertEquals(listOf("setCredentials(7, u)"), podcasts.calls)
        }

    @Test
    fun unsubscribeRunsTheUseCase() =
        runTest {
            podcasts.subscribedIds.value = setOf(7L)
            val viewModel = viewModel()
            viewModel.unsubscribe()
            advanceUntilIdle()
            assertEquals(listOf("unsubscribe([7])"), podcasts.calls)
        }

    @Test
    fun markAllPlayedCoversThePodcast() =
        runTest {
            val viewModel = viewModel()
            viewModel.markAllPlayed()
            advanceUntilIdle()
            assertEquals(listOf("markFeedPlayed(${FeedSource.Podcast(7)}, null)"), episodes.calls)
        }

    @Test
    fun downloadedCountReadsTheRepository() =
        runTest {
            podcasts.downloadedIds.value = mapOf(7L to listOf(1L, 2L, 3L))
            assertEquals(3, viewModel().downloadedCount())
        }

    @Test
    fun rowPlayedActionWritesToRepository() =
        runTest {
            val viewModel = viewModel()
            viewModel.onRowAction(EpisodeAction.SetPlayed(42, played = false))
            advanceUntilIdle()
            assertEquals(listOf("setPlayed([42], false)"), episodes.calls)
        }
}
