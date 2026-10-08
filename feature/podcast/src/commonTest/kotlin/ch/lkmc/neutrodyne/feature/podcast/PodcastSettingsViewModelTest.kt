// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.model.ShowType
import ch.lkmc.neutrodyne.core.testing.FakeFeedRepository
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.testFeedInfo
import ch.lkmc.neutrodyne.core.testing.testPodcastDetail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `PodcastSettingsViewModel` on the desktop JVM (09): the combined detail/feed-info state, 05's
 * effective order and every settings write the screen exposes (03 Edit URL, Basic auth).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PodcastSettingsViewModelTest : MainDispatcherTest() {
    private val podcasts = FakePodcastRepository()
    private val feeds = FakeFeedRepository()

    private fun viewModel(podcastId: Long = 7) = PodcastSettingsViewModel(podcastId, podcasts, feeds)

    private fun TestScope.collect(viewModel: PodcastSettingsViewModel) {
        backgroundScope.launch { viewModel.uiState.collect {} }
    }

    @Test
    fun initialStateBeforeSubscription() =
        runTest {
            assertEquals(PodcastSettingsUiState(), viewModel().uiState.value)
        }

    @Test
    fun detailAndFeedInfoLoad() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            podcasts.detail.value = testPodcastDetail(7, displayTitle = "Serial Box")
            podcasts.feedInfo.value = testFeedInfo(feedUrl = "https://feeds.example.com/sb")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.loaded)
            assertEquals("Serial Box", state.detail?.displayTitle)
            assertEquals("https://feeds.example.com/sb", state.feedInfo?.feedUrl)
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
    fun effectiveOrderFollowsShowTypeAndOverride() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            podcasts.detail.value = testPodcastDetail(7, showType = ShowType.SERIAL, episodeOrder = null)
            advanceUntilIdle()
            assertEquals(FeedOrder.OLDEST_FIRST, viewModel.uiState.value.effectiveOrder)

            podcasts.detail.value =
                testPodcastDetail(7, showType = ShowType.SERIAL, episodeOrder = FeedOrder.NEWEST_FIRST)
            advanceUntilIdle()
            assertEquals(FeedOrder.NEWEST_FIRST, viewModel.uiState.value.effectiveOrder)
        }

    @Test
    fun setCustomTitleWritesTheRepository() =
        runTest {
            val viewModel = viewModel()
            viewModel.setCustomTitle("My title")
            advanceUntilIdle()
            viewModel.setCustomTitle(null)
            advanceUntilIdle()
            assertEquals(listOf("setCustomTitle(7, My title)", "setCustomTitle(7, null)"), podcasts.calls)
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
    fun setIncludeInAllWritesTheRepository() =
        runTest {
            val viewModel = viewModel()
            viewModel.setIncludeInAll(false)
            advanceUntilIdle()
            viewModel.setIncludeInAll(true)
            advanceUntilIdle()
            assertEquals(
                listOf("setIncludeInAll(7, false)", "setIncludeInAll(7, true)"),
                podcasts.calls,
            )
        }

    @Test
    fun editFeedUrlReturnsTheOutcome() =
        runTest {
            val viewModel = viewModel()

            val ok = viewModel.editFeedUrl("https://new.example.com/feed")
            assertIs<Outcome.Success<*>>(ok)
            assertEquals(listOf("editFeedUrl(7, https://new.example.com/feed)"), podcasts.calls)

            podcasts.editUrlOutcome = Outcome.Failure(AddPodcastError.InvalidUrl)
            val bad = viewModel.editFeedUrl("not a url")
            assertEquals(Outcome.Failure(AddPodcastError.InvalidUrl), bad)
        }

    @Test
    fun setCredentialsReturnsTheOutcome() =
        runTest {
            val viewModel = viewModel()
            assertIs<Outcome.Success<*>>(viewModel.setCredentials(BasicCredentials("u", "p")))
            assertEquals(listOf("setCredentials(7, u)"), podcasts.calls)

            podcasts.credentialsOutcome = Outcome.Failure(AddPodcastError.Network(NetError.Offline))
            assertIs<Outcome.Failure<*>>(viewModel.setCredentials(BasicCredentials("u", "p")))
        }
}
