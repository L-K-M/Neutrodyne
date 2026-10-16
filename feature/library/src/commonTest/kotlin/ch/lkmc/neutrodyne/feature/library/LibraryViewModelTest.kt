// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.library

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.TitleCollator
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.UnsubscribeUseCase
import ch.lkmc.neutrodyne.core.domain.groupCountsWindowStart
import ch.lkmc.neutrodyne.core.model.FeedCounts
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.FeedSourceRef
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.LibrarySegment
import ch.lkmc.neutrodyne.core.model.settings.LibrarySort
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeRepository
import ch.lkmc.neutrodyne.core.testing.FakeFeedRepository
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.testLibraryTile
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `LibraryViewModel` on the desktop JVM (09): tile sorting per `appearance.library_sort`,
 * title visibility, the offline flag and the overflow actions — every write asserted on the
 * fakes' call logs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest : MainDispatcherTest() {
    private val groups = FakeGroupRepository()
    private val feeds = FakeFeedRepository()
    private val clock = TestClock()
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
            groups,
            feeds,
            clock,
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
            // A new LibrarySort must extend the table below, or it ships without coverage.
            assertEquals(LibrarySort.entries.toList(), SORT_ORDERS.map { it.first })
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

    /** A throwing repository write becomes a snackbar message — not an uncaught exception (01). */
    @Test
    fun markAllPlayedWriteFailurePostsAMessage() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            episodes.writeError = Exception("disk full")

            viewModel.markAllPlayed(7)
            advanceUntilIdle()

            assertTrue(episodes.calls.isEmpty())
            val message =
                viewModel.uiState.value.messages
                    .single()
            assertEquals(Res.string.write_failed, (message.text as UiText.Res).id)

            viewModel.onMessageShown(message.id)
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.messages
                    .isEmpty(),
            )
        }

    @Test
    fun unsubscribeWriteFailurePostsAMessage() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            podcasts.writeError = Exception("disk full")

            viewModel.unsubscribe(7)
            advanceUntilIdle()

            assertTrue(podcasts.calls.isEmpty())
            assertTrue(
                viewModel.uiState.value.messages
                    .isNotEmpty(),
            )
        }

    /** A settings `Outcome.Failure` posts the same message — the switch stays where it was. */
    @Test
    fun setSortFailurePostsAMessage() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            settings.failNextSet = SettingsError.WriteFailed

            viewModel.setSort(LibrarySort.MOST_UNPLAYED)
            advanceUntilIdle()

            assertEquals(listOf("set(appearance.library_sort)"), settings.calls)
            assertTrue(
                viewModel.uiState.value.messages
                    .isNotEmpty(),
            )
            assertEquals(LibrarySort.TITLE, settings.get(AppearanceSettingKeys.LIBRARY_SORT))
        }

    @Test fun groupAndUngroupedFiltersPersistAndTrackMemberships() =
        runTest {
            val tech = assertIs<Outcome.Success<Long>>(groups.create(GroupDraft("tech"), setOf(1L))).value
            val news = assertIs<Outcome.Success<Long>>(groups.create(GroupDraft("news"), setOf(1L, 2L))).value
            val uuid =
                groups.groups.value
                    .first { it.id == tech }
                    .uuid
            val a = testLibraryTile(1, "A")
            podcasts.tiles.value = listOf(a, testLibraryTile(2, "B"), testLibraryTile(3, "C"))
            podcasts.tilesByGroup.value = mapOf(tech to listOf(a))
            settings.set(AppearanceSettingKeys.LIBRARY_GROUP_FILTER, uuid)
            val vm = viewModel()
            collect(vm)
            advanceUntilIdle()
            assertEquals(uuid, vm.uiState.value.groupFilter)
            assertEquals(
                listOf(1L),
                vm.uiState.value.tiles
                    .map { it.podcastId },
            )
            assertEquals(
                listOf(tech, news),
                vm.uiState.value.groups
                    .map { it.id },
            )

            vm.setGroupFilter("ungrouped")
            advanceUntilIdle()
            assertEquals("ungrouped", settings.get(AppearanceSettingKeys.LIBRARY_GROUP_FILTER))
            assertEquals(
                listOf(3L),
                vm.uiState.value.tiles
                    .map { it.podcastId },
            )
            groups.setMembers(news, setOf(1L))
            advanceUntilIdle()
            assertEquals(
                listOf(2L, 3L),
                vm.uiState.value.tiles
                    .map { it.podcastId },
            )

            vm.setGroupFilter("")
            advanceUntilIdle()
            assertEquals(
                listOf(1L, 2L, 3L),
                vm.uiState.value.tiles
                    .map { it.podcastId },
            )
        }

    @Test fun deletedSelectedGroupSilentlyFallsBackToAll() =
        runTest {
            val id = assertIs<Outcome.Success<Long>>(groups.create(GroupDraft("tech"))).value
            val uuid =
                groups.groups.value
                    .single()
                    .uuid
            settings.set(AppearanceSettingKeys.LIBRARY_GROUP_FILTER, uuid)
            podcasts.tiles.value = listOf(testLibraryTile(1))
            val vm = viewModel()
            collect(vm)
            advanceUntilIdle()
            assertEquals(uuid, vm.uiState.value.groupFilter)
            assertTrue(
                vm.uiState.value.tiles
                    .isEmpty(),
            )

            groups.groups.value = emptyList()
            advanceUntilIdle()
            assertEquals("", settings.get(AppearanceSettingKeys.LIBRARY_GROUP_FILTER))
            assertEquals("", vm.uiState.value.groupFilter)
            assertEquals(
                listOf(1L),
                vm.uiState.value.tiles
                    .map { it.podcastId },
            )
            assertTrue(
                vm.uiState.value.messages
                    .isEmpty(),
            )
            assertFalse(vm.selectGroup(id))
        }

    @Test fun invalidStoredFilterFallsBackToAll() =
        runTest {
            settings.set(AppearanceSettingKeys.LIBRARY_GROUP_FILTER, "group:obsolete")
            val vm = viewModel()
            collect(vm)
            advanceUntilIdle()
            assertEquals("", settings.get(AppearanceSettingKeys.LIBRARY_GROUP_FILTER))
            assertEquals("", vm.uiState.value.groupFilter)
        }

    @Test fun groupTapWritesFeedsEncodingAndReportsFailedWrites() =
        runTest {
            val id = assertIs<Outcome.Success<Long>>(groups.create(GroupDraft("tech"))).value
            val vm = viewModel()
            collect(vm)
            advanceUntilIdle()
            settings.failNextSet = SettingsError.WriteFailed
            assertFalse(vm.selectGroup(id))
            assertEquals("all", settings.get(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE))
            assertTrue(vm.selectGroup(id))
            assertEquals(
                FeedSourceRef
                    .Group(
                        groups.groups.value
                            .single()
                            .uuid,
                    ).encode(),
                settings.get(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE),
            )
        }

    @Test fun selectionActionsUseExactlyTheSelectedPodcasts() =
        runTest {
            podcasts.subscribedIds.value = setOf(1L, 2L, 3L)
            podcasts.downloadedIds.value = mapOf(1L to listOf(10L), 2L to listOf(20L, 21L))
            val vm = viewModel()
            vm.refreshSelection(listOf(2L, 1L, 2L))
            assertEquals(listOf("refreshNow(${RefreshScope.Podcasts(listOf(2L, 1L))})"), refreshController.calls)
            assertEquals(3, vm.downloadedCount(listOf(2L, 1L)))
            vm.markSelectionPlayed(listOf(2L, 1L, 2L))
            vm.unsubscribeSelection(listOf(2L, 1L, 2L))
            advanceUntilIdle()
            assertEquals(
                listOf(
                    "markFeedPlayed(${FeedSource.Podcast(2)}, null)",
                    "markFeedPlayed(${FeedSource.Podcast(1)}, null)",
                ),
                episodes.calls,
            )
            assertEquals(listOf("downloadedEpisodeIds([2, 1])", "unsubscribe([2, 1])"), podcasts.calls)
            assertEquals(setOf(3L), podcasts.subscribedIds.value)
        }

    @Test fun groupTileCountsFollowTheFeedRepositoryWindowAndUpdates() =
        runTest {
            val tech = assertIs<Outcome.Success<Long>>(groups.create(GroupDraft("tech"))).value
            val news = assertIs<Outcome.Success<Long>>(groups.create(GroupDraft("news"))).value
            val initial = mapOf(news to FeedCounts(), tech to FeedCounts(14, 3))
            feeds.groupCounts.value = initial
            val vm = viewModel()
            collect(vm)
            advanceUntilIdle()

            assertEquals(listOf(groupCountsWindowStart(clock.now())), feeds.groupCountsSinceMs)
            assertEquals(initial, vm.uiState.value.groupCounts)

            val updated = mapOf(tech to FeedCounts(9, 1), news to FeedCounts(5, 2))
            feeds.groupCounts.value = updated
            advanceUntilIdle()
            assertEquals(updated, vm.uiState.value.groupCounts)

            feeds.groupCounts.value = mapOf(tech to FeedCounts())
            advanceUntilIdle()
            assertEquals(mapOf(tech to FeedCounts()), vm.uiState.value.groupCounts)
        }

    @Test fun segmentIsPersistedAndObserved() =
        runTest {
            settings.set(AppearanceSettingKeys.LIBRARY_SEGMENT, LibrarySegment.GROUPS)
            val vm = viewModel()
            collect(vm)
            advanceUntilIdle()
            assertEquals(LibrarySegment.GROUPS, vm.uiState.value.segment)
            vm.setSegment(LibrarySegment.PODCASTS)
            advanceUntilIdle()
            assertEquals(LibrarySegment.PODCASTS, settings.get(AppearanceSettingKeys.LIBRARY_SEGMENT))
            assertEquals(LibrarySegment.PODCASTS, vm.uiState.value.segment)
        }

    @Test fun shiftSelectionExtendsInVisibleOrder() {
        val visible = listOf(4L, 2L, 3L, 1L)
        assertEquals(listOf(2L, 3L, 1L), extendSelection(listOf(2L), 2L, 1L, visible))
        assertEquals(listOf(2L, 4L), extendSelection(listOf(2L), 2L, 4L, visible))
        assertEquals(listOf(2L, 1L), extendSelection(listOf(2L), null, 1L, visible))
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
