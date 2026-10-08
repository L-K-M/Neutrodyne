// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.library

import ch.lkmc.neutrodyne.core.common.TitleCollator
import ch.lkmc.neutrodyne.core.domain.UnsubscribeUseCase
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.LibrarySort
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeRepository
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.testLibraryTile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `LibraryViewModel` on the desktop JVM (09): tile sorting per `appearance.library_sort`,
 * title visibility, the offline flag and the overflow actions — every write asserted on the
 * fakes' call logs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest : MainDispatcherTest() {
    private val podcasts = FakePodcastRepository()
    private val episodes = FakeEpisodeRepository()
    private val refreshController = FakeRefreshController()
    private val settings = FakeSettingsRepository()
    private val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)

    /** Case-insensitive ordering so the sort assertions are locale-free and deterministic. */
    private val collator =
        object : TitleCollator {
            override fun compare(
                a: String,
                b: String,
            ): Int = a.compareTo(b, ignoreCase = true)
        }

    private fun viewModel() =
        LibraryViewModel(
            podcasts,
            episodes,
            refreshController,
            UnsubscribeUseCase(podcasts),
            settings,
            collator,
            network,
        )

    private fun TestScope.collect(viewModel: LibraryViewModel) {
        backgroundScope.launch { viewModel.uiState.collect {} }
    }

    @Test
    fun initialStateBeforeSubscription() =
        runTest {
            assertEquals(LibraryUiState(), viewModel().uiState.value)
        }

    @Test
    fun tilesLoadAndSortByTitle() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            podcasts.tiles.value =
                listOf(testLibraryTile(1, "Zulu"), testLibraryTile(2, "alpha"), testLibraryTile(3, "Mike"))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.loaded)
            assertEquals(listOf(2L, 3L, 1L), state.tiles.map { it.podcastId })
            assertEquals(LibrarySort.TITLE, state.sort)
        }

    @Test
    fun sortSettingOrdersTiles() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            val day = 24 * 3_600_000L
            podcasts.tiles.value =
                listOf(
                    testLibraryTile(
                        1,
                        "A",
                        latestEpisodeAt = TestClock.DEFAULT_NOW - 3 * day,
                        unplayedCount = 1,
                        subscribedAt =
                            TestClock.DEFAULT_NOW - day,
                    ),
                    testLibraryTile(
                        2,
                        "B",
                        latestEpisodeAt = TestClock.DEFAULT_NOW,
                        unplayedCount = 0,
                        subscribedAt =
                            TestClock.DEFAULT_NOW - 2 * day,
                    ),
                    testLibraryTile(
                        3,
                        "C",
                        latestEpisodeAt = null,
                        unplayedCount = 5,
                        subscribedAt =
                            TestClock.DEFAULT_NOW - 3 * day,
                    ),
                )

            for ((sort, expected) in SORT_ORDERS) {
                settings.set(AppearanceSettingKeys.LIBRARY_SORT, sort)
                advanceUntilIdle()
                assertEquals(
                    expected,
                    viewModel.uiState.value.tiles
                        .map { it.podcastId },
                    "sort=$sort",
                )
            }
        }

    @Test
    fun setSortAndTitlesWriteTheSettings() =
        runTest {
            val viewModel = viewModel()
            viewModel.setSort(LibrarySort.RECENTLY_ADDED)
            viewModel.setShowTitles(true)
            advanceUntilIdle()

            assertEquals(
                listOf("set(appearance.library_sort)", "set(appearance.library_titles)"),
                settings.calls,
            )
            assertEquals(LibrarySort.RECENTLY_ADDED, settings.get(AppearanceSettingKeys.LIBRARY_SORT))
            assertEquals(true, settings.get(AppearanceSettingKeys.LIBRARY_TITLES))
        }

    @Test
    fun offlineFlagTracksTheMonitor() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            advanceUntilIdle()
            assertTrue(!viewModel.uiState.value.offline)

            network.setStatus(FakeNetworkMonitor.OFFLINE)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.offline)
        }

    @Test
    fun refreshPodcastRequestsThatFeed() =
        runTest {
            viewModel().refreshPodcast(7)
            assertEquals(listOf("refreshFeed(${FeedSource.Podcast(7)})"), refreshController.calls)
        }

    @Test
    fun markAllPlayedCoversThePodcast() =
        runTest {
            val viewModel = viewModel()
            viewModel.markAllPlayed(7)
            advanceUntilIdle()
            assertEquals(listOf("markFeedPlayed(${FeedSource.Podcast(7)}, null)"), episodes.calls)
        }

    @Test
    fun downloadedCountReadsTheRepository() =
        runTest {
            podcasts.downloadedIds.value = mapOf(7L to listOf(11L, 12L))
            assertEquals(2, viewModel().downloadedCount(7))
            assertEquals(listOf("downloadedEpisodeIds([7])"), podcasts.calls)
        }

    @Test
    fun unsubscribeRunsTheUseCase() =
        runTest {
            podcasts.subscribedIds.value = setOf(7L, 8L)
            val viewModel = viewModel()
            viewModel.unsubscribe(7)
            advanceUntilIdle()

            assertEquals(listOf("unsubscribe([7])"), podcasts.calls)
            assertEquals(setOf(8L), podcasts.subscribedIds.value)
        }

    private companion object {
        /** (sort key → expected tile-id order) covering every `LibrarySort` branch. */
        val SORT_ORDERS =
            listOf(
                LibrarySort.TITLE to listOf(1L, 2L, 3L),
                LibrarySort.RECENTLY_UPDATED to listOf(2L, 1L, 3L),
                LibrarySort.MOST_UNPLAYED to listOf(3L, 1L, 2L),
                LibrarySort.RECENTLY_ADDED to listOf(1L, 2L, 3L),
            )
    }
}
