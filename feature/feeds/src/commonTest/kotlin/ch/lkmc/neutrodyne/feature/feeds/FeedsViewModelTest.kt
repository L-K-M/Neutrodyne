// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.lifecycle.ViewModelStore
import androidx.paging.testing.asSnapshot
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.FeedRepository
import ch.lkmc.neutrodyne.core.domain.GroupRepository
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.RefreshStatus
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.AddResult
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.FeedCounts
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedPrefs
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.FeedSourceRef
import ch.lkmc.neutrodyne.core.model.FeedTab
import ch.lkmc.neutrodyne.core.model.Group
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.GroupEdit
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.RejectReason
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.core.model.VirtualCounts
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeLiveStateSource
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeRepository
import ch.lkmc.neutrodyne.core.testing.FakeFeedRepository
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.testEpisodeDetail
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import ch.lkmc.neutrodyne.core.testing.testLibraryTile
import ch.lkmc.neutrodyne.core.testing.testNowPlaying
import ch.lkmc.neutrodyne.core.testing.testPlatformInfo
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.EpisodeSelectionAction
import ch.lkmc.neutrodyne.core.ui.MarkPlayedOption
import ch.lkmc.neutrodyne.core.ui.PlaybackMessageAction
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_copy_link
import ch.lkmc.neutrodyne.core.ui.resources.date_today
import ch.lkmc.neutrodyne.core.ui.resources.date_yesterday
import ch.lkmc.neutrodyne.core.ui.resources.feed_group_deleted
import ch.lkmc.neutrodyne.core.ui.resources.feed_group_unknown
import ch.lkmc.neutrodyne.core.ui.resources.no_handler
import ch.lkmc.neutrodyne.core.ui.resources.offline_banner
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.UnplayableReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `FeedsViewModel` on the desktop JVM (09 Test matrix): feed paging against
 * [FakeFeedRepository]'s `PagingData.from` pages, persisted pager/selection state, visits and the
 * row/mark-all actions, with repository calls asserted on the fakes' logs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedsViewModelTest : MainDispatcherTest() {
    private val downloads =
        ch.lkmc.neutrodyne.core.testing
            .FakeDownloadController()
    private val feeds = FakeFeedRepository()
    private val episodes = FakeEpisodeRepository()
    private val liveSource = FakeEpisodeLiveStateSource()
    private val refreshController = FakeRefreshController()
    private val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
    private val podcasts = FakePodcastRepository()
    private val clock = TestClock()

    private val groups = FakeGroupRepository()
    private val settings = FakeSettingsRepository()
    private val playbackController = FakePlaybackController()
    private val store = ViewModelStore()
    private var vmNumber = 0

    private fun viewModel(
        repository: FeedRepository = feeds,
        groupRepository: GroupRepository = groups,
        settingsRepository: SettingsRepository = settings,
        playbackController: FakePlaybackController = this.playbackController,
    ) = FeedsViewModel(
        repository,
        episodes,
        refreshController,
        network,
        podcasts,
        clock,
        liveSource,
        groups = groupRepository,
        settings = settingsRepository,
        playbackController = playbackController,
        playbackStates = playbackController.state,
        platformInfo = testPlatformInfo,
        defaultDispatcher = dispatcher,
        downloadController = downloads,
    ).also { store.put("feeds${vmNumber++}", it) }

    private fun viewModelTest(block: suspend TestScope.() -> Unit) =
        runTest {
            try {
                block()
            } finally {
                // cachedIn keeps the hourly preferences flow alive beyond UI collection. Clear
                // before runTest drains its scheduler, rather than in JUnit's later teardown.
                store.clear()
            }
        }

    private fun TestScope.collect(viewModel: FeedsViewModel) {
        backgroundScope.launch { viewModel.uiState.collect {} }
    }

    @Test
    fun swipePlayedOffersUndoThroughTheExistingService() =
        viewModelTest {
            val model = viewModel()
            collect(model)
            model.onRowAction(EpisodeAction.SetPlayed(7, true, ch.lkmc.neutrodyne.core.ui.PlayedActionOrigin.SWIPE))
            runCurrent()
            val message =
                model.uiState.value.messages
                    .single()
            model.undoSwipe(message.id)
            runCurrent()
            assertEquals(listOf("setPlayed([7], true)", "setPlayed([7], false)"), episodes.calls)
            model.onMessageShown(message.id)
            model.undoSwipe(message.id)
            runCurrent()
            assertEquals(2, episodes.calls.size)
        }

    @Test
    fun episodeSelectionTogglesExtendsFromLastToggleAndClears() =
        viewModelTest {
            val model = viewModel()
            model.onRowAction(EpisodeAction.Select(2))
            assertEquals(setOf(2L), model.episodeSelection.ids.value)
            model.episodeSelection.extend(4, listOf(1, 2, 3, 4, 5))
            assertEquals(setOf(2L, 3L, 4L), model.episodeSelection.ids.value)
            model.onRowAction(EpisodeAction.Select(3))
            model.episodeSelection.extend(1, listOf(1, 2, 3, 4, 5))
            assertEquals(setOf(1L, 2L, 3L, 4L), model.episodeSelection.ids.value)
            model.episodeSelection.clear()
            assertTrue(
                model.episodeSelection.ids.value
                    .isEmpty(),
            )
            model.episodeSelection.extend(5, listOf(1, 5))
            assertEquals(setOf(5L), model.episodeSelection.ids.value)
        }

    @Test
    fun bulkEpisodeActionsDispatchOnlySelectedIdsAndClearOnSuccess() =
        viewModelTest {
            val model = viewModel()
            model.episodeSelection.selectAll(listOf(3, 1))
            model.episodeSelection.perform(EpisodeSelectionAction.MARK_PLAYED)
            runCurrent()
            assertEquals(listOf("setPlayed([3, 1], true)"), episodes.calls)
            assertTrue(
                model.episodeSelection.ids.value
                    .isEmpty(),
            )
            model.onRowAction(EpisodeAction.Select(2))
            model.episodeSelection.perform(EpisodeSelectionAction.MARK_UNPLAYED)
            runCurrent()
            assertEquals("setPlayed([2], false)", episodes.calls.last())
        }

    @Test
    fun failedBulkActionRetainsSelectionAndReportsWriteFailure() =
        viewModelTest {
            val model = viewModel()
            collect(model)
            episodes.writeError = IllegalStateException("disk full")
            model.onRowAction(EpisodeAction.Select(9))
            model.episodeSelection.perform(EpisodeSelectionAction.MARK_PLAYED)
            runCurrent()
            assertEquals(setOf(9L), model.episodeSelection.ids.value)
            assertFalse(model.episodeSelection.busy.value)
            assertEquals(
                UiText.Res(Res.string.write_failed),
                model.uiState.value.messages
                    .single()
                    .text,
            )
        }

    @Test
    fun swipePreferencesRetainDefaultsAndFollowChanges() =
        viewModelTest {
            val model = viewModel()
            collect(model)
            runCurrent()
            assertNull(model.uiState.value.swipe)
            settings.set(AppearanceSettingKeys.FEEDS_ROW_SWIPE, true)
            runCurrent()
            assertEquals(
                ch.lkmc.neutrodyne.core.model.SwipeAction.ADD_UP_NEXT,
                model.uiState.value.swipe
                    ?.startToEnd,
            )
            assertEquals(
                ch.lkmc.neutrodyne.core.model.SwipeAction.MARK_PLAYED,
                model.uiState.value.swipe
                    ?.endToStart,
            )
            settings.set(AppearanceSettingKeys.SWIPE_START_ACTION, ch.lkmc.neutrodyne.core.model.SwipeAction.NONE)
            runCurrent()
            assertEquals(
                ch.lkmc.neutrodyne.core.model.SwipeAction.NONE,
                model.uiState.value.swipe
                    ?.startToEnd,
            )
        }

    @Test
    fun liveRowsUseUnionOfComposedPageWindowsAndReleaseDisposedPages() =
        viewModelTest {
            val viewModel = viewModel()
            backgroundScope.launch { viewModel.live.collect {} }
            val overlay = RowLive(positionMs = 500, durationMs = 1_000)
            liveSource.live.value = mapOf(1L to overlay, 2L to RowLive(isNowPlaying = true), 3L to overlay)
            viewModel.onVisibleIds(FeedSource.All, setOf(1, 2))
            viewModel.onVisibleIds(FeedSource.Group(7), setOf(2, 3))
            runCurrent()
            assertEquals(setOf(1L, 2L, 3L), viewModel.live.value.keys)
            assertEquals(overlay, viewModel.live.value[1])

            // Removing All retains the shared ID still visible in the group page.
            viewModel.onVisibleIds(FeedSource.All, emptySet())
            runCurrent()
            assertEquals(setOf(2L, 3L), viewModel.live.value.keys)

            liveSource.live.value = mapOf(3L to RowLive(durationMs = 2_000))
            runCurrent()
            assertEquals(mapOf(3L to RowLive(durationMs = 2_000)), viewModel.live.value)
            assertEquals(null, viewModel.live.value[2])

            viewModel.onVisibleIds(FeedSource.Group(7), emptySet())
            runCurrent()
            assertEquals(emptyMap(), viewModel.live.value)
        }

    @Test
    fun initialStateBeforeSubscription() =
        viewModelTest {
            val viewModel = viewModel()
            assertEquals(FeedsUiState(), viewModel.uiState.value)
        }

    @Test
    fun feedPagesAllSourceNewestFirst() =
        viewModelTest {
            val viewModel = viewModel()
            feeds.rows.value = listOf(testEpisodeRow(1), testEpisodeRow(2))

            val items = viewModel.pagingFor(FeedSource.All).take(1).asSnapshot()
            runCurrent()

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
    fun downloadedAndInProgressPreserveStoredFlags() =
        viewModelTest {
            val group = group()
            val stored = FeedFilters(downloadedOnly = true, inProgressOnly = true, unplayedOnly = true)
            val model = viewModel()
            collect(model)
            for (source in listOf(FeedSource.All, FeedSource.Ungrouped, FeedSource.Group(group.id))) {
                feeds.setFilters(source, stored)
                model.pagingFor(source).take(1).asSnapshot()
                runCurrent()
                assertEquals(stored, feeds.lastRequest?.second)
                assertEquals(
                    stored,
                    model.prefsFor(source).first().filters,
                )
                assertEquals(
                    stored,
                    feeds.prefs.value
                        .getValue(source)
                        .filters,
                )
            }
            assertEquals(stored, model.uiState.value.filters)
        }

    @Test
    fun downloadedPreferenceChangesRebuildThePager() =
        viewModelTest {
            val model = viewModel()
            backgroundScope.launch { model.pagingFor(FeedSource.All).collect {} }
            runCurrent()
            feeds.setFilters(FeedSource.All, FeedFilters(downloadedOnly = true))
            runCurrent()
            assertEquals(2, feeds.pagingRequests.size)
            assertEquals(FeedFilters(downloadedOnly = true), feeds.lastRequest?.second)
            feeds.setFilters(FeedSource.All, FeedFilters(downloadedOnly = true, inProgressOnly = true))
            runCurrent()
            assertEquals(3, feeds.pagingRequests.size)
            assertEquals(FeedFilters(downloadedOnly = true, inProgressOnly = true), feeds.lastRequest?.second)
        }

    @Test
    fun filtersFlowIntoThePageRequest() =
        viewModelTest {
            val viewModel = viewModel()
            viewModel.onFiltersChange(FeedFilters(unplayedOnly = true))

            viewModel.pagingFor(FeedSource.All).take(1).asSnapshot()
            runCurrent()

            assertEquals(FeedFilters(unplayedOnly = true), feeds.lastRequest?.second)
        }

    @Test
    fun feedInsertsOneHeaderPerDay() =
        viewModelTest {
            val viewModel = viewModel()
            feeds.rows.value =
                listOf(
                    testEpisodeRow(1, sortDate = TestClock.DEFAULT_NOW),
                    testEpisodeRow(2, sortDate = TestClock.DEFAULT_NOW - 2 * HOUR),
                    // 36 h earlier is always a previous local day (max TZ offset is 14 h).
                    testEpisodeRow(3, sortDate = TestClock.DEFAULT_NOW - 36 * HOUR),
                )

            val items = viewModel.pagingFor(FeedSource.All).take(1).asSnapshot()
            runCurrent()

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
    fun dayLabelsUseClockAtEachFeedEmission() =
        viewModelTest {
            val viewModel = viewModel()
            feeds.rows.value = listOf(testEpisodeRow(1, sortDate = clock.now()))
            val first =
                viewModel
                    .pagingFor(FeedSource.All)
                    .take(1)
                    .asSnapshot()
                    .filterIsInstance<FeedItem.Day>()
                    .single()
            assertEquals(UiText.Res(Res.string.date_today), first.label)

            clock.advanceBy(1.days)
            // A filter change requests a new generation in the same ViewModel after midnight.
            viewModel.onFiltersChange(FeedFilters(unplayedOnly = true))
            runCurrent()
            val next =
                viewModel
                    .pagingFor(FeedSource.All)
                    .take(1)
                    .asSnapshot()
                    .filterIsInstance<FeedItem.Day>()
                    .single()
            assertEquals(UiText.Res(Res.string.date_yesterday), next.label)
        }

    @Test
    fun uiStateReflectsFiltersAndConnectivity() =
        viewModelTest {
            val viewModel = viewModel()
            podcasts.tiles.value = listOf(testLibraryTile(1))
            backgroundScope.launch { viewModel.uiState.collect {} }
            runCurrent()

            assertEquals(
                FeedFilters(),
                viewModel.uiState.value.filters,
            )

            network.setStatus(FakeNetworkMonitor.OFFLINE)
            runCurrent()
            assertTrue(viewModel.uiState.value.offline)

            val filtered = FeedFilters(unplayedOnly = true)
            viewModel.onFiltersChange(filtered)
            runCurrent()
            assertEquals(filtered, viewModel.uiState.value.filters)
        }

    @Test
    fun emptyLibraryDisablesSubscriptions() =
        viewModelTest {
            val viewModel = viewModel()
            backgroundScope.launch { viewModel.uiState.collect {} }
            runCurrent()

            // The fake has no tiles: the onboarding empty state is the visible outcome.
            assertTrue(!viewModel.uiState.value.hasSubscriptions)

            podcasts.tiles.value = listOf(testLibraryTile(7))
            runCurrent()
            assertTrue(viewModel.uiState.value.hasSubscriptions)
        }

    @Test
    fun refreshTracksRunningAndCapsAtThirtySeconds() =
        viewModelTest {
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
        viewModelTest {
            val viewModel = viewModel()
            viewModel.onRefresh()
            assertEquals(listOf("refreshFeed(All)"), refreshController.calls)
        }

    @Test
    fun countedMarkPlayedFreezesCutoffAndReconfirmsChangedSelection() =
        viewModelTest {
            val viewModel = viewModel()
            collect(viewModel)
            val group = group()
            runCurrent()
            val source = FeedSource.Group(group.id)
            val before = clock.now() - 7.days.inWholeMilliseconds
            feeds.unplayedCounts[source to before] = 2
            episodes.unplayedCounts[source to before] = 3
            viewModel.markAllPlayed(source)
            viewModel.markPlayed.select(MarkPlayedOption.WEEK)
            runCurrent()
            assertEquals(
                2,
                viewModel.markPlayed.state.value
                    ?.count,
            )
            assertEquals(
                UiText.Raw("tech"),
                viewModel.markPlayed.state.value
                    ?.title,
            )
            assertTrue(episodes.calls.isEmpty())

            clock.advanceBy(2.days)
            viewModel.markPlayed.confirm()
            runCurrent()
            val changed = viewModel.markPlayed.state.value!!
            assertEquals(before, changed.sortDateBefore)
            assertEquals(3, changed.count)
            assertTrue(changed.selectionChanged)
            assertTrue(episodes.calls.isEmpty())

            viewModel.markPlayed.confirm()
            viewModel.markPlayed.confirm()
            runCurrent()
            assertNull(viewModel.markPlayed.state.value)
            assertEquals(listOf("markFeedPlayed($source, $before, 3)"), episodes.calls)
        }

    @Test
    fun countedMarkPlayedUsesEachVirtualSourceAndCancelWritesNothing() =
        viewModelTest {
            val viewModel = viewModel()
            for (source in listOf(FeedSource.All, FeedSource.Ungrouped)) {
                feeds.unplayedCounts[source to null] = 1
                episodes.unplayedCounts[source to null] = 1
                viewModel.markAllPlayed(source)
                runCurrent()
                assertEquals(
                    source,
                    viewModel.markPlayed.state.value
                        ?.source,
                )
                assertEquals(
                    1,
                    viewModel.markPlayed.state.value
                        ?.count,
                )
                viewModel.markPlayed.dismiss()
                assertTrue(episodes.calls.isEmpty())
                viewModel.markAllPlayed(source)
                runCurrent()
                viewModel.markPlayed.confirm()
                runCurrent()
                assertNull(viewModel.markPlayed.state.value)
                assertEquals("markFeedPlayed($source, null, 1)", episodes.calls.last())
                episodes.calls.clear()
            }
        }

    @Test
    fun rowPlayedActionWritesToRepository() =
        viewModelTest {
            val viewModel = viewModel()
            viewModel.onRowAction(EpisodeAction.SetPlayed(42, played = true))
            runCurrent()
            assertEquals(listOf("setPlayed([42], true)"), episodes.calls)
        }

    @Test
    fun downloadAndPlayDispatchToTheirControllers() =
        viewModelTest {
            playbackController.playResult = PlayResult.Started
            val viewModel = viewModel()
            collect(viewModel)
            viewModel.onRowAction(EpisodeAction.PlayToggle(42))
            viewModel.onRowAction(EpisodeAction.DownloadToggle(42))
            runCurrent()
            assertTrue(episodes.calls.isEmpty())
            assertEquals(
                listOf<FakePlaybackController.Call>(
                    FakePlaybackController.Call.Feed(
                        FeedSource.All,
                        FeedFilters(),
                        FeedOrder.NEWEST_FIRST,
                        42L,
                    ),
                ),
                playbackController.calls,
            )
            assertEquals(
                ch.lkmc.neutrodyne.core.testing.FakeDownloadController.Action.REQUEST,
                downloads.calls.single().action,
            )
            // The fake rejects download admission; the shared handler reports that rejection.
            assertEquals(1, viewModel.uiState.value.messages.size)
        }

    /** 08's row table: a refused offline start answers with the offline snackbar. */
    @Test
    fun offlinePlayTogglePostsTheOfflineMessage() =
        viewModelTest {
            playbackController.playResult = PlayResult.Offline
            val viewModel = viewModel()
            collect(viewModel)
            network.setStatus(FakeNetworkMonitor.OFFLINE)
            runCurrent()

            viewModel.onRowAction(EpisodeAction.PlayToggle(42))
            runCurrent()

            val message =
                viewModel.uiState.value.messages
                    .single()
            assertEquals(Res.string.offline_banner, (message.text as UiText.Res).id)
        }

    /**
     * The result mapping does not depend on uiState: a tap landing before the first combine
     * emission still surfaces the controller's answer.
     */
    @Test
    fun offlinePlayToggleBeforeUiStateEmissionPostsTheMessage() =
        viewModelTest {
            playbackController.playResult = PlayResult.Offline
            val viewModel = viewModel()
            network.setStatus(FakeNetworkMonitor.OFFLINE)

            // Nothing collects uiState yet: its value is still the offline=false initial state.
            viewModel.onRowAction(EpisodeAction.PlayToggle(42))
            collect(viewModel)
            runCurrent()

            val message =
                viewModel.uiState.value.messages
                    .single()
            assertEquals(Res.string.offline_banner, (message.text as UiText.Res).id)
        }

    /** A downloaded episode's offline tap reaches the controller and starts silently. */
    @Test
    fun downloadedEpisodePlayToggleStartsPlaybackOffline() =
        viewModelTest {
            playbackController.playResult = PlayResult.Started
            val viewModel = viewModel()
            collect(viewModel)
            network.setStatus(FakeNetworkMonitor.OFFLINE)
            runCurrent()

            viewModel.onRowAction(EpisodeAction.PlayToggle(42))
            runCurrent()

            assertEquals(
                listOf<FakePlaybackController.Call>(
                    FakePlaybackController.Call.Feed(
                        FeedSource.All,
                        FeedFilters(),
                        FeedOrder.NEWEST_FIRST,
                        42L,
                    ),
                ),
                playbackController.calls,
            )
            assertTrue(
                viewModel.uiState.value.messages
                    .isEmpty(),
            )
        }

    /** A NO_HANDLER open posts the copy-link snackbar; the action tap resolves by id (08). */
    @Test
    fun noHandlerPostsACopyLinkMessage() =
        viewModelTest {
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onNoHandler("https://www.youtube.com/watch?v=abc")
            runCurrent()

            val message =
                viewModel.uiState.value.messages
                    .single()
            assertEquals(Res.string.no_handler, (message.text as UiText.Res).id)
            assertEquals(Res.string.action_copy_link, (message.action as UiText.Res).id)
            assertEquals(
                "https://www.youtube.com/watch?v=abc",
                viewModel.consumeCopyLink(message.id),
            )
            // One-shot, like 01's acknowledgement contract: the id resolves exactly once.
            assertEquals(null, viewModel.consumeCopyLink(message.id))
        }

    /** A throwing repository write becomes a snackbar message — not an uncaught exception (01). */
    @Test
    fun setPlayedWriteFailurePostsAMessage() =
        viewModelTest {
            val viewModel = viewModel()
            collect(viewModel)
            episodes.writeError = Exception("disk full")

            viewModel.onRowAction(EpisodeAction.SetPlayed(42, played = true))
            runCurrent()

            assertTrue(episodes.calls.isEmpty())
            val message =
                viewModel.uiState.value.messages
                    .single()
            assertEquals(Res.string.write_failed, (message.text as UiText.Res).id)

            viewModel.onMessageShown(message.id)
            runCurrent()
            assertTrue(
                viewModel.uiState.value.messages
                    .isEmpty(),
            )
        }

    @Test
    fun markAllPlayedWriteFailureKeepsCountForRetry() =
        viewModelTest {
            val viewModel = viewModel()
            feeds.unplayedCounts[FeedSource.All to null] = 1
            episodes.unplayedCounts[FeedSource.All to null] = 1
            episodes.writeError = Exception("disk full")
            viewModel.markAllPlayed()
            runCurrent()
            viewModel.markPlayed.confirm()
            runCurrent()
            assertTrue(episodes.calls.isEmpty())
            assertEquals(
                1,
                viewModel.markPlayed.state.value
                    ?.count,
            )
            assertEquals(
                UiText.Res(Res.string.write_failed),
                viewModel.markPlayed.state.value
                    ?.error,
            )
            episodes.writeError = null
            viewModel.markPlayed.confirm()
            runCurrent()
            assertNull(viewModel.markPlayed.state.value)
        }

    private suspend fun group(showAsTab: Boolean = true): Group {
        val id = (groups.create(GroupDraft("tech", showAsTab = showAsTab)) as Outcome.Success).value
        val group = groups.groups.value.single { it.id == id }
        feeds.tabs.value = listOf(allTab) + if (showAsTab) listOf(group.tab()) else emptyList()
        return group
    }

    private fun Group.tab() = FeedTab(FeedSource.Group(id), name, colorArgb, iconKey, uuid)

    @Test
    fun selectionSurvivesNewViewModelOverPersistedSettings() =
        viewModelTest {
            val group = group()
            val ref = FeedSourceRef.Group(group.uuid)
            val first = viewModel()
            collect(first)
            runCurrent()
            first.onPageSettled(ref)
            runCurrent()
            assertEquals(ref.encode(), settings.get(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE))
            store.clear()

            val recreated = viewModel()
            collect(recreated)
            runCurrent()
            assertEquals(ref, recreated.uiState.value.selected)
            assertEquals(
                FeedSource.Group(group.id),
                recreated.uiState.value.tabs
                    .single { it.ref == ref }
                    .tab.source,
            )
        }

    @Test
    fun restoreWaitsForBothTabsAndGroupsAndObservesExternalSelections() =
        viewModelTest {
            val group = group()
            val ref = FeedSourceRef.Group(group.uuid)
            settings.set(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE, ref.encode())
            val delayedTabs = MutableStateFlow<List<FeedTab>?>(null)
            val delayedGroups = MutableStateFlow<List<Group>?>(null)
            val repository =
                object : FeedRepository by feeds {
                    override fun observeTabs() = delayedTabs.filterNotNull()
                }
            val groupRepository =
                object : GroupRepository by groups {
                    override fun observeGroups() = delayedGroups.filterNotNull()
                }
            val viewModel = viewModel(repository, groupRepository)
            collect(viewModel)
            runCurrent()
            assertTrue(
                viewModel.uiState.value.tabs
                    .isEmpty(),
            )
            delayedTabs.value = feeds.tabs.value
            runCurrent()
            assertTrue(
                viewModel.uiState.value.tabs
                    .isEmpty(),
            )
            assertEquals(ref.encode(), settings.get(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE))

            delayedGroups.value = listOf(group)
            runCurrent()
            assertEquals(ref, viewModel.uiState.value.selected)
            assertTrue(
                viewModel.uiState.value.messages
                    .isEmpty(),
            )
            settings.set(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE, "all")
            runCurrent()
            assertEquals(FeedSourceRef.All, viewModel.uiState.value.selected)
        }

    @Test
    fun deletedSelectedGroupFallsBackOnceAndUndoDoesNotReselect() =
        viewModelTest {
            val group = group()
            val ref = FeedSourceRef.Group(group.uuid)
            settings.set(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE, ref.encode())
            val viewModel = viewModel()
            collect(viewModel)
            runCurrent()
            viewModel.pagingFor(FeedSource.Group(group.id))
            viewModel.onVisibleIds(FeedSource.Group(group.id), setOf(1))
            groups.groups.value = emptyList()
            feeds.tabs.value = listOf(allTab)
            runCurrent()

            assertEquals(FeedSourceRef.All, viewModel.uiState.value.selected)
            assertEquals("all", settings.get(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE))
            assertEquals(
                UiText.Res(Res.string.feed_group_deleted, listOf("tech")),
                viewModel.uiState.value.messages
                    .single()
                    .text,
            )
            groups.groups.value = listOf(group)
            feeds.tabs.value = listOf(allTab, group.tab())
            runCurrent()
            assertEquals(FeedSourceRef.All, viewModel.uiState.value.selected)
            assertEquals(1, viewModel.uiState.value.messages.size)
        }

    @Test
    fun unknownRouteUuidFallsBackWithSnackbar() =
        viewModelTest {
            val viewModel = viewModel()
            collect(viewModel)
            runCurrent()
            // D2's route consumer writes this same key; no root routing is needed for the state test.
            settings.set(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE, "group:12345678-1234-1234-1234-123456789abc")
            runCurrent()
            assertEquals(FeedSourceRef.All, viewModel.uiState.value.selected)
            assertEquals("all", settings.get(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE))
            assertEquals(
                UiText.Res(Res.string.feed_group_unknown),
                viewModel.uiState.value.messages
                    .single()
                    .text,
            )
        }

    @Test
    fun hiddenGroupCatalogKeepsPickerAvailableWithoutAddingTabs() =
        viewModelTest {
            val viewModel = viewModel()
            collect(viewModel)
            runCurrent()
            assertFalse(viewModel.uiState.value.hasGroups)

            group(showAsTab = false)
            runCurrent()
            assertTrue(viewModel.uiState.value.hasGroups)
            assertEquals(
                listOf(FeedSourceRef.All),
                viewModel.uiState.value.tabs
                    .map { it.ref },
            )

            groups.groups.value = emptyList()
            runCurrent()
            assertFalse(viewModel.uiState.value.hasGroups)
        }

    @Test
    fun hiddenGroupGetsTransientTabUntilAnotherPageSettles() =
        viewModelTest {
            val group = group(showAsTab = false)
            val ref = FeedSourceRef.Group(group.uuid)
            val viewModel = viewModel()
            collect(viewModel)
            runCurrent()
            viewModel.onPageSettled(ref)
            runCurrent()
            assertEquals(ref, viewModel.uiState.value.selected)
            val transient =
                viewModel.uiState.value.tabs
                    .last()
            assertEquals(ref, transient.ref)
            assertEquals(group.tab(), transient.tab)
            assertTrue(transient.transient)
            assertTrue(
                viewModel.uiState.value.messages
                    .isEmpty(),
            )

            viewModel.onPageSettled(FeedSourceRef.All)
            runCurrent()
            assertEquals(
                listOf(FeedSourceRef.All),
                viewModel.uiState.value.tabs
                    .map { it.ref },
            )
        }

    @Test
    fun hidingSelectedGroupPreservesSelectionAndUngroupedDisappearanceIsSilent() =
        viewModelTest {
            val group = group()
            val ref = FeedSourceRef.Group(group.uuid)
            settings.set(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE, ref.encode())
            val viewModel = viewModel()
            collect(viewModel)
            runCurrent()
            groups.update(group.id, GroupEdit.ShowAsTab(false))
            feeds.tabs.value = listOf(allTab)
            runCurrent()
            assertEquals(ref, viewModel.uiState.value.selected)
            assertTrue(
                viewModel.uiState.value.tabs
                    .last()
                    .transient,
            )
            feeds.tabs.value += FeedTab(FeedSource.Ungrouped, "Ungrouped", null, null, null)
            viewModel.onPageSettled(FeedSourceRef.Ungrouped)
            runCurrent()
            feeds.tabs.value = listOf(allTab)
            runCurrent()
            assertEquals(FeedSourceRef.All, viewModel.uiState.value.selected)
            assertTrue(
                viewModel.uiState.value.messages
                    .isEmpty(),
            )
        }

    @Test
    fun tabBadgesJoinGroupAndVirtualCountsAndDefaultMissingGroupsToZero() =
        viewModelTest {
            val group = group()
            feeds.tabs.value += FeedTab(FeedSource.Ungrouped, "Ungrouped", null, null, null)
            feeds.groupCounts.value = mapOf(group.id to FeedCounts(12, 3))
            feeds.virtualCounts.value = VirtualCounts(FeedCounts(25, 6), FeedCounts(5, 1))
            val viewModel = viewModel()
            collect(viewModel)
            runCurrent()
            assertEquals(
                listOf(25 to 6, 12 to 3, 5 to 1),
                viewModel.uiState.value.tabs
                    .map { it.unplayed to it.newCount },
            )
            feeds.groupCounts.value = emptyMap()
            runCurrent()
            assertEquals(
                0,
                viewModel.uiState.value.tabs[1]
                    .unplayed,
            )
            assertEquals(
                0,
                viewModel.uiState.value.tabs[1]
                    .newCount,
            )
        }

    @Test
    fun leastRecentlyUsedEntryCancelsPagingAndIsRecreatedOnReturn() =
        viewModelTest {
            val viewModel = viewModel()
            runCurrent()
            val all = viewModel.pagingFor(FeedSource.All)
            val ungrouped = viewModel.pagingFor(FeedSource.Ungrouped)
            val group = viewModel.pagingFor(FeedSource.Group(1))
            for (page in listOf(all, ungrouped, group)) backgroundScope.launch { page.collect {} }
            backgroundScope.launch { viewModel.live.collect {} }
            runCurrent()
            assertEquals(3, feeds.pagingRequests.size)
            viewModel.onVisibleIds(FeedSource.Ungrouped, setOf(2))
            assertSame(all, viewModel.pagingFor(FeedSource.All))
            viewModel.pagingFor(FeedSource.Group(2))
            runCurrent()
            assertEquals(listOf(FeedSource.Ungrouped), feeds.cancelledPagingRequests.map { it.first })
            assertEquals(emptySet(), liveSource.lastVisibleIds.value)
            feeds.setFilters(FeedSource.Ungrouped, FeedFilters(unplayedOnly = true))
            runCurrent()
            assertEquals(1, feeds.pagingRequests.count { it.first == FeedSource.Ungrouped })

            val returned = viewModel.pagingFor(FeedSource.Ungrouped)
            assertNotSame(ungrouped, returned)
            backgroundScope.launch { returned.collect {} }
            runCurrent()
            assertEquals(2, feeds.pagingRequests.count { it.first == FeedSource.Ungrouped })
            assertEquals(FeedFilters(unplayedOnly = true), feeds.lastRequest?.second)
            store.clear()
            runCurrent()
            assertEquals(0, feeds.prefs.subscriptionCount.value)
        }

    @Test
    fun filtersReplaceOnlyTheirSourcePagerAndVisitsKeepTheGeneration() =
        viewModelTest {
            val group = group()
            val source = FeedSource.Group(group.id)
            val viewModel = viewModel()
            collect(viewModel)
            val all = viewModel.pagingFor(FeedSource.All)
            val page = viewModel.pagingFor(source)
            backgroundScope.launch { all.collect {} }
            backgroundScope.launch { page.collect {} }
            runCurrent()
            viewModel.onPageSettled(FeedSourceRef.Group(group.uuid))
            viewModel.onFilters(source, FeedFilters(unplayedOnly = true, media = MediaFilter.VIDEO))
            runCurrent()
            assertEquals(1, feeds.pagingRequests.count { it.first == FeedSource.All })
            assertEquals(2, feeds.pagingRequests.count { it.first == source })
            assertEquals(
                FeedFilters(unplayedOnly = true, media = MediaFilter.VIDEO),
                viewModel.uiState.value.prefs
                    ?.filters,
            )
            assertEquals(FeedFilters(), viewModel.uiState.value.filters)
            feeds.markVisited(source, clock.now())
            feeds.prefs.value += source to
                feeds.prefs.value
                    .getValue(source)
                    .copy(playOrder = FeedOrder.OLDEST_FIRST)
            runCurrent()
            assertEquals(2, feeds.pagingRequests.count { it.first == source })
            assertSame(page, viewModel.pagingFor(source))
        }

    @Test
    fun shortVisitDoesNotMarkAndLongVisitFreezesBaselineUntilNextStart() =
        viewModelTest {
            val group = group()
            val source = FeedSource.Group(group.id)
            feeds.prefs.value = mapOf(source to FeedPrefs(lastViewedAt = 100))
            val viewModel = viewModel()
            collect(viewModel)
            runCurrent()
            viewModel.onVisitStart(source)
            runCurrent()
            clock.advanceBy(999.milliseconds)
            viewModel.onVisitEnd(source)
            runCurrent()
            assertEquals(100, feeds.prefs.value[source]?.lastViewedAt)
            assertEquals(100, viewModel.uiState.value.visitBaselines[source])

            viewModel.onVisitStart(source)
            runCurrent()
            clock.advanceBy(500.milliseconds)
            viewModel.onVisitStart(source) // Lifecycle/page notifications can overlap.
            clock.advanceBy(500.milliseconds)
            val leftAt = clock.now()
            viewModel.onVisitEnd(source)
            viewModel.onVisitEnd(source)
            runCurrent()
            assertEquals(leftAt, feeds.prefs.value[source]?.lastViewedAt)
            assertEquals(100, viewModel.uiState.value.visitBaselines[source])
            viewModel.onVisitStart(source)
            runCurrent()
            assertEquals(leftAt, viewModel.uiState.value.visitBaselines[source])
        }

    @Test
    fun firstGroupVisitUsesCreatedAtAndDurationUsesMonotonicClock() =
        viewModelTest {
            val group = group()
            val source = FeedSource.Group(group.id)
            val viewModel = viewModel()
            collect(viewModel)
            runCurrent()
            viewModel.onVisitStart(source)
            runCurrent()
            assertEquals(group.createdAt, viewModel.uiState.value.visitBaselines[source])
            clock.advanceBy(1.seconds)
            clock.nowMs -= 10_000 // A wall-clock correction cannot turn a long visit into a short one.
            viewModel.onVisitEnd(source)
            runCurrent()
            assertEquals(clock.now(), feeds.prefs.value[source]?.lastViewedAt)
        }

    @Test
    fun liveMapsAreScopedToEachPagesIdsAndOverlapIsRetained() =
        viewModelTest {
            val viewModel = viewModel()
            backgroundScope.launch { viewModel.liveBySource.collect {} }
            liveSource.live.value = mapOf(1L to RowLive(positionMs = 100), 2L to RowLive(positionMs = 200))
            viewModel.onVisibleIds(FeedSource.All, setOf(1, 2))
            viewModel.onVisibleIds(FeedSource.Ungrouped, setOf(2))
            runCurrent()
            assertEquals(setOf(1L, 2L), liveSource.lastVisibleIds.value)
            assertEquals(setOf(2L), viewModel.liveBySource.value[FeedSource.Ungrouped]?.keys)
            viewModel.onVisibleIds(FeedSource.All, emptySet())
            runCurrent()
            assertEquals(setOf(2L), liveSource.lastVisibleIds.value)
            assertNull(viewModel.liveBySource.value[FeedSource.All])
        }

    @Test
    fun scopedRefreshAndPreferenceFailureUseExistingServicesAndMessages() =
        viewModelTest {
            val viewModel = viewModel()
            collect(viewModel)
            viewModel.onRefresh(FeedSource.Group(42))
            assertEquals(listOf("refreshFeed(Group(groupId=42))"), refreshController.calls)
            feeds.writeError = Exception("disk full")
            viewModel.onFilters(FeedSource.All, FeedFilters(unplayedOnly = true))
            runCurrent()
            assertEquals(
                UiText.Res(Res.string.write_failed),
                viewModel.uiState.value.messages
                    .single()
                    .text,
            )
            assertEquals(FeedFilters(), viewModel.uiState.value.filters)
        }

    @Test
    fun selectionAndTabsAlwaysDescribeTheSameSnapshot() =
        viewModelTest {
            val group = group(showAsTab = false)
            val viewModel = viewModel()
            val emissions = mutableListOf<FeedsUiState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect { emissions += it }
            }
            runCurrent()
            viewModel.onPageSettled(FeedSourceRef.Group(group.uuid))
            runCurrent()
            viewModel.onPageSettled(FeedSourceRef.All)
            runCurrent()
            assertTrue(emissions.all { state -> state.tabs.isEmpty() || state.tabs.any { it.ref == state.selected } })
        }

    @Test
    fun restoredHiddenGroupKeepsItsFiltersAndAllChipsRemainIndependent() =
        viewModelTest {
            val group = group(showAsTab = false)
            val source = FeedSource.Group(group.id)
            val ref = FeedSourceRef.Group(group.uuid)
            feeds.setFilters(source, FeedFilters(media = MediaFilter.AUDIO))
            feeds.setFilters(FeedSource.All, FeedFilters(unplayedOnly = true))
            settings.set(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE, ref.encode())
            val viewModel = viewModel()
            collect(viewModel)
            runCurrent()
            assertEquals(ref, viewModel.uiState.value.selected)
            assertTrue(
                viewModel.uiState.value.tabs
                    .last()
                    .transient,
            )
            assertEquals(
                FeedFilters(media = MediaFilter.AUDIO),
                viewModel.uiState.value.prefs
                    ?.filters,
            )
            assertEquals(FeedFilters(unplayedOnly = true), viewModel.uiState.value.filters)
        }

    @Test
    fun failedSelectionWriteKeepsSelectionAndReportsFailure() =
        viewModelTest {
            val group = group()
            val viewModel = viewModel()
            collect(viewModel)
            runCurrent()
            settings.failNextSet = SettingsError.WriteFailed
            viewModel.onPageSettled(FeedSourceRef.Group(group.uuid))
            runCurrent()
            assertEquals("all", settings.get(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE))
            assertEquals(FeedSourceRef.All, viewModel.uiState.value.selected)
            assertEquals(
                UiText.Res(Res.string.write_failed),
                viewModel.uiState.value.messages
                    .single()
                    .text,
            )
        }

    @Test
    fun undoWhileFallbackIsPersistingDoesNotReselectDeletedGroup() =
        viewModelTest {
            val group = group()
            val ref = FeedSourceRef.Group(group.uuid)
            settings.set(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE, ref.encode())
            val releaseFallback = CompletableDeferred<Unit>()
            val delayedSettings =
                object : SettingsRepository by settings {
                    override suspend fun <T : Any> set(
                        key: SettingKey<T>,
                        value: T,
                    ): Outcome<Unit, SettingsError> {
                        if (key == AppearanceSettingKeys.FEEDS_SELECTED_SOURCE && value == "all") {
                            releaseFallback.await()
                        }
                        return settings.set(key, value)
                    }
                }
            val viewModel = viewModel(settingsRepository = delayedSettings)
            collect(viewModel)
            runCurrent()
            groups.groups.value = emptyList()
            feeds.tabs.value = listOf(allTab)
            runCurrent()
            assertEquals(FeedSourceRef.All, viewModel.uiState.value.selected)
            assertEquals(ref.encode(), settings.get(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE))

            groups.groups.value = listOf(group)
            feeds.tabs.value = listOf(allTab, group.tab())
            runCurrent()
            assertEquals(FeedSourceRef.All, viewModel.uiState.value.selected)
            releaseFallback.complete(Unit)
            runCurrent()
            assertEquals("all", settings.get(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE))
            assertEquals(1, viewModel.uiState.value.messages.size)
        }

    @Test
    fun newlyCreatedHiddenGroupIsResolvedFromFreshDataAfterRouteSelection() =
        viewModelTest {
            var groupReads = 0
            val laggingGroups =
                object : GroupRepository by groups {
                    override fun observeGroups(): Flow<List<Group>> =
                        if (groupReads++ == 0) flowOf(emptyList()) else groups.observeGroups()
                }
            val viewModel = viewModel(groupRepository = laggingGroups)
            collect(viewModel)
            runCurrent()
            val group = group(showAsTab = false)
            val ref = FeedSourceRef.Group(group.uuid)
            // The create transaction finished, but the old group observer has not delivered it.
            settings.set(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE, ref.encode())
            runCurrent()
            assertEquals(ref, viewModel.uiState.value.selected)
            assertTrue(
                viewModel.uiState.value.tabs
                    .last()
                    .transient,
            )
            assertTrue(
                viewModel.uiState.value.messages
                    .isEmpty(),
            )
        }

    @Test
    fun hiddenPageDisposalReleasesIdsAfterItsTransientTabIsRemoved() =
        viewModelTest {
            val group = group(showAsTab = false)
            val ref = FeedSourceRef.Group(group.uuid)
            val viewModel = viewModel()
            collect(viewModel)
            backgroundScope.launch { viewModel.live.collect {} }
            viewModel.onPageSettled(ref)
            runCurrent()
            viewModel.onVisibleIds(ref, setOf(42))
            runCurrent()
            assertEquals(setOf(42L), liveSource.lastVisibleIds.value)
            viewModel.onPageSettled(FeedSourceRef.All)
            runCurrent()
            viewModel.onVisibleIds(ref, emptySet())
            runCurrent()
            assertEquals(emptySet(), liveSource.lastVisibleIds.value)
        }

    @Test
    fun explicitResettleRetriesTheSameStoredRefAfterFailedFallbackAndUndo() =
        viewModelTest {
            val group = group()
            val ref = FeedSourceRef.Group(group.uuid)
            settings.set(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE, ref.encode())
            val viewModel = viewModel()
            collect(viewModel)
            runCurrent()
            settings.failNextSet = SettingsError.WriteFailed
            groups.groups.value = emptyList()
            feeds.tabs.value = listOf(allTab)
            runCurrent()
            assertEquals(FeedSourceRef.All, viewModel.uiState.value.selected)
            assertEquals(ref.encode(), settings.get(AppearanceSettingKeys.FEEDS_SELECTED_SOURCE))
            groups.groups.value = listOf(group)
            feeds.tabs.value = listOf(allTab, group.tab())
            runCurrent()
            viewModel.onPageSettled(ref)
            runCurrent()
            assertEquals(ref, viewModel.uiState.value.selected)
        }

    @Test
    fun rapidRevisitWaitsForPreviousVisitWriteBeforeCapturingBaseline() =
        viewModelTest {
            val group = group()
            val source = FeedSource.Group(group.id)
            feeds.prefs.value = mapOf(source to FeedPrefs(lastViewedAt = 100))
            val releaseWrite = CompletableDeferred<Unit>()
            val delayedFeeds =
                object : FeedRepository by feeds {
                    override suspend fun markVisited(
                        source: FeedSource,
                        leftAt: Long,
                    ) {
                        releaseWrite.await()
                        feeds.markVisited(source, leftAt)
                    }
                }
            val viewModel = viewModel(repository = delayedFeeds)
            collect(viewModel)
            runCurrent()
            viewModel.onVisitStart(source)
            runCurrent()
            clock.advanceBy(1.seconds)
            val leftAt = clock.now()
            viewModel.onVisitEnd(source)
            viewModel.onVisitStart(source)
            runCurrent()
            releaseWrite.complete(Unit)
            runCurrent()
            assertEquals(leftAt, viewModel.uiState.value.visitBaselines[source])
        }

    @Test
    fun headerPlayStartsThePageSourceWithItsOwnFiltersAndPlayOrder() =
        viewModelTest {
            val group = group()
            val source = FeedSource.Group(group.id)
            val filters = FeedFilters(unplayedOnly = true, media = MediaFilter.AUDIO)
            feeds.prefs.value =
                mapOf(
                    source to
                        FeedPrefs(
                            feedOrder = FeedOrder.NEWEST_FIRST,
                            playOrder = FeedOrder.OLDEST_FIRST,
                            filters = filters,
                        ),
                )
            playbackController.playResult = PlayResult.Started
            val model = viewModel()
            collect(model)
            runCurrent()

            model.playFeed(source)
            runCurrent()

            assertEquals(
                listOf<FakePlaybackController.Call>(
                    FakePlaybackController.Call.Feed(source, filters, FeedOrder.OLDEST_FIRST, null),
                ),
                playbackController.calls,
            )
        }

    @Test
    fun rowPlayAnchorsTheSelectedSourceAtTheEpisode() =
        viewModelTest {
            playbackController.playResult = PlayResult.Started
            val model = viewModel()
            collect(model)
            runCurrent()

            model.onRowAction(EpisodeAction.PlayToggle(42))
            runCurrent()

            assertEquals(
                listOf<FakePlaybackController.Call>(
                    FakePlaybackController.Call.Feed(
                        FeedSource.All,
                        FeedFilters(),
                        FeedOrder.NEWEST_FIRST,
                        42L,
                    ),
                ),
                playbackController.calls,
            )
        }

    @Test
    fun rowStartUsesItsPageInsteadOfTheSelectedTab() =
        viewModelTest {
            val source = FeedSource.Group(9)
            val filters = FeedFilters(inProgressOnly = true, media = MediaFilter.AUDIO)
            feeds.prefs.value = mapOf(source to FeedPrefs(filters = filters, playOrder = FeedOrder.OLDEST_FIRST))
            playbackController.playResult = PlayResult.Started
            val model = viewModel()
            collect(model)
            runCurrent()
            model.playEpisode(source, 42)
            runCurrent()
            val call = assertIs<FakePlaybackController.Call.Feed>(playbackController.calls.single())
            assertEquals(source, call.source)
            assertEquals(filters, call.filters)
            assertEquals(FeedOrder.OLDEST_FIRST, call.order)
            assertEquals(42L, call.startId)
        }

    @Test
    fun queueSelectionKeepsRejectedRowsAndCompletesAcceptedRows() =
        viewModelTest {
            val model = viewModel()
            collect(model)
            model.episodeSelection.toggle(1)
            model.episodeSelection.toggle(2)
            playbackController.addResult = AddResult.Rejected(RejectReason.UNAVAILABLE)
            model.playSelectionNext()
            runCurrent()
            assertEquals(setOf(1L, 2L), model.episodeSelection.ids.value)
            playbackController.addResult = AddResult.Added(2)
            model.playSelectionLast()
            model.episodeSelection.toggle(3)
            runCurrent()
            assertEquals(setOf(3L), model.episodeSelection.ids.value)
        }

    @Test
    fun playToggleOnThePlayingItemPausesWithoutANewStart() =
        viewModelTest {
            playbackController.state.nowPlaying.value = testNowPlaying(42)
            val model = viewModel()
            collect(model)
            runCurrent()

            model.onRowAction(EpisodeAction.PlayToggle(42))
            runCurrent()

            assertEquals(
                listOf<FakePlaybackController.Call>(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.PAUSE),
                ),
                playbackController.calls,
            )
        }

    @Test
    fun playToggleOnThePausedCurrentItemResumes() =
        viewModelTest {
            playbackController.state.nowPlaying.value =
                testNowPlaying(42).copy(isPlaying = false, playWhenReady = false)
            playbackController.playResult = PlayResult.Started
            val model = viewModel()
            collect(model)
            runCurrent()

            model.onRowAction(EpisodeAction.PlayToggle(42))
            runCurrent()

            assertEquals(
                listOf<FakePlaybackController.Call>(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.PLAY),
                ),
                playbackController.calls,
            )
        }

    @Test
    fun playNextAndPlayLastEnqueueTheRow() =
        viewModelTest {
            val model = viewModel()

            model.onRowAction(EpisodeAction.PlayNext(42))
            model.onRowAction(EpisodeAction.PlayLast(43))
            runCurrent()

            assertEquals(
                listOf<FakePlaybackController.Call>(
                    FakePlaybackController.Call.Queue(FakePlaybackController.Placement.NEXT, listOf(42L)),
                    FakePlaybackController.Call.Queue(FakePlaybackController.Placement.LAST, listOf(43L)),
                ),
                playbackController.calls,
            )
        }

    @Test
    fun needsMeteredConsentOpensTheDialogAndOnceRetriesTheStart() =
        viewModelTest {
            playbackController.playResult = PlayResult.NeedsMeteredConsent(42)
            val model = viewModel()
            collect(model)
            runCurrent()

            model.playFeed(FeedSource.All)
            runCurrent()
            assertEquals(
                42L,
                model.playback.meteredConsent.value
                    ?.episodeId,
            )

            playbackController.calls.clear()
            playbackController.playResult = PlayResult.Started
            model.playback.consentOnce()
            runCurrent()

            assertNull(model.playback.meteredConsent.value)
            assertEquals(
                listOf<FakePlaybackController.Call>(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.CONSENT),
                    FakePlaybackController.Call.Feed(
                        FeedSource.All,
                        FeedFilters(),
                        FeedOrder.NEWEST_FIRST,
                        null,
                    ),
                ),
                playbackController.calls,
            )
        }

    @Test
    fun consentAlwaysPersistsAllowThenRetries() =
        viewModelTest {
            settings.set(PlaybackSettingKeys.STREAM_ON_METERED, StreamOnMetered.ASK)
            playbackController.playResult = PlayResult.NeedsMeteredConsent(42)
            val model = viewModel()
            collect(model)
            runCurrent()

            model.playFeed(FeedSource.All)
            runCurrent()
            playbackController.calls.clear()
            playbackController.playResult = PlayResult.Started

            model.playback.consentAlways()
            runCurrent()

            assertEquals(
                StreamOnMetered.ALLOW,
                settings.get(PlaybackSettingKeys.STREAM_ON_METERED),
            )
            assertTrue("set(playback.stream_on_metered)" in settings.calls)
            assertEquals(
                listOf<FakePlaybackController.Call>(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.CONSENT),
                    FakePlaybackController.Call.Feed(
                        FeedSource.All,
                        FeedFilters(),
                        FeedOrder.NEWEST_FIRST,
                        null,
                    ),
                ),
                playbackController.calls,
            )
        }

    @Test
    fun meteredBlockedPostsTheSettingsAction() =
        viewModelTest {
            playbackController.playResult = PlayResult.MeteredBlocked
            val model = viewModel()
            collect(model)
            runCurrent()

            model.playFeed(FeedSource.All)
            runCurrent()

            val message =
                model.uiState.value.messages
                    .single()
            assertEquals(
                PlaybackMessageAction.OpenPlaybackSettings,
                model.playback.onMessageAction(message.id),
            )
            // The action resolves once.
            assertNull(model.playback.onMessageAction(message.id))
        }

    @Test
    fun authRequiredOffersEnterPasswordAndCredentialsSaveForTheEpisodesPodcast() =
        viewModelTest {
            playbackController.playResult =
                PlayResult.NotPlayable(42, UnplayableReason.AuthRequired)
            episodes.episodes.value = mapOf(42L to testEpisodeDetail(42, podcastId = 7))
            val model = viewModel()
            collect(model)
            runCurrent()

            model.playFeed(FeedSource.All)
            runCurrent()

            val message =
                model.uiState.value.messages
                    .single()
            assertEquals(
                PlaybackMessageAction.EnterPassword(42),
                model.playback.onMessageAction(message.id),
            )

            model.onEnterPassword(42)
            runCurrent()
            assertEquals(7L, model.pendingCredentials.value)

            model.onCredentials(BasicCredentials("user", "pw"))
            runCurrent()
            assertNull(model.pendingCredentials.value)
            assertEquals(listOf("setCredentials(7, user)"), podcasts.calls)
        }

    @Test fun downloadToggleReachesTheManualRequestHandler() =
        viewModelTest {
            val vm = viewModel()
            vm.onRowAction(EpisodeAction.DownloadToggle(5))
            runCurrent()
            assertEquals(
                ch.lkmc.neutrodyne.core.testing.FakeDownloadController.Action.REQUEST,
                downloads.calls.single().action,
            )
            assertEquals(listOf(5L), downloads.calls.single().ids)
        }

    private companion object {
        val allTab = FeedTab(FeedSource.All, "All", null, null, null)
        const val HOUR = 3_600_000L
    }
}
