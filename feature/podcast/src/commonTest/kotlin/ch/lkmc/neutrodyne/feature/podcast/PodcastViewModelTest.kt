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
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.NetError
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.core.model.ShowType
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeLiveStateSource
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeRepository
import ch.lkmc.neutrodyne.core.testing.FakeFeedRepository
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeNewEpisodePoster
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.FakePlaybackStateSource
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.FakeRefreshController
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import ch.lkmc.neutrodyne.core.testing.testFeedInfo
import ch.lkmc.neutrodyne.core.testing.testNowPlaying
import ch.lkmc.neutrodyne.core.testing.testPlatformInfo
import ch.lkmc.neutrodyne.core.testing.testPodcastDetail
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.EpisodeSelectionAction
import ch.lkmc.neutrodyne.core.ui.MarkPlayedOption
import ch.lkmc.neutrodyne.core.ui.PlaybackMessageAction
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_undo
import ch.lkmc.neutrodyne.core.ui.resources.podcast_removed_from_group
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import ch.lkmc.neutrodyne.playback.api.PlayResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/**
 * `PodcastViewModel` on the desktop JVM (09): detail/feed-info state, the episode page keyed by
 * `FeedSource.Podcast`, 05's serial/episode order defaults, and every action the screen exposes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PodcastViewModelTest : MainDispatcherTest() {
    private val podcasts = FakePodcastRepository()
    private val feeds = FakeFeedRepository()
    private val episodes = FakeEpisodeRepository()
    private val groups = FakeGroupRepository()
    private val liveSource = FakeEpisodeLiveStateSource()
    private val refreshController = FakeRefreshController()
    private val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
    private val clock = TestClock()
    private val settings = FakeSettingsRepository()
    private val playbackController = FakePlaybackController()
    private val playbackStates = FakePlaybackStateSource()

    private fun viewModel(
        podcastId: Long = 7,
        playbackController: FakePlaybackController = this.playbackController,
    ) = PodcastViewModel(
        podcastId,
        podcasts,
        feeds,
        episodes,
        groups,
        refreshController,
        UnsubscribeUseCase(podcasts, FakeNewEpisodePoster()),
        settings,
        playbackController,
        playbackStates,
        testPlatformInfo,
        network,
        liveSource,
        clock,
    )

    private fun TestScope.collect(viewModel: PodcastViewModel) {
        backgroundScope.launch { viewModel.uiState.collect {} }
    }

    private suspend fun FakeGroupRepository.createId(name: String): Long =
        (create(GroupDraft(name)) as Outcome.Success).value

    @Test
    fun episodeSelectionUsesTheSharedBulkPlayedService() =
        runTest {
            val model = viewModel()
            model.onRowAction(EpisodeAction.Select(2))
            model.episodeSelection.extend(4, listOf(1, 2, 3, 4))
            model.episodeSelection.perform(EpisodeSelectionAction.MARK_UNPLAYED)
            advanceUntilIdle()
            assertEquals(listOf("setPlayed([2, 3, 4], false)"), episodes.calls)
            assertTrue(
                model.episodeSelection.ids.value
                    .isEmpty(),
            )
        }

    @Test
    fun liveRowsFollowVisibleWindowAndKeepPagedPlayedState() =
        runTest {
            val viewModel = viewModel()
            val row = testEpisodeRow(1, playedAt = 123)
            feeds.rows.value = listOf(row)
            podcasts.detail.value = testPodcastDetail(7)
            backgroundScope.launch { viewModel.live.collect {} }
            val overlay = RowLive(positionMs = 500, durationMs = 1_000)
            liveSource.live.value = mapOf(1L to overlay, 2L to RowLive(isNowPlaying = true))
            viewModel.onVisibleIds(setOf(1))
            advanceUntilIdle()
            assertEquals(mapOf(1L to overlay), viewModel.live.value)
            assertEquals(
                row,
                viewModel.feed
                    .take(1)
                    .asSnapshot()
                    .single(),
            )

            viewModel.onVisibleIds(setOf(2))
            advanceUntilIdle()
            assertEquals(mapOf(2L to RowLive(isNowPlaying = true)), viewModel.live.value)
            assertNull(viewModel.live.value[1])

            liveSource.live.value = mapOf(2L to RowLive(isNowPlaying = true, isPlaying = true))
            advanceUntilIdle()
            assertEquals(true, viewModel.live.value[2]?.isPlaying)

            viewModel.onVisibleIds(emptySet())
            advanceUntilIdle()
            assertEquals(emptyMap(), viewModel.live.value)
        }

    @Test
    fun transientQueriesKeepInProgressAndGateDownloaded() =
        runTest {
            val model = viewModel()
            collect(model)
            val effective = FeedFilters(unplayedOnly = true, inProgressOnly = true)
            model.onFiltersChange(effective.copy(downloadedOnly = true))
            model.feed.take(1).asSnapshot()
            advanceUntilIdle()
            assertEquals(effective, feeds.lastRequest?.second)
            assertEquals(effective, model.uiState.value.filters)
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

            collect(viewModel)
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
    fun countedMarkPlayedOpensOnThePodcastSource() =
        runTest {
            val viewModel = viewModel()
            podcasts.detail.value = testPodcastDetail(7, displayTitle = "Serial Box")
            collect(viewModel)
            advanceUntilIdle()
            val source = FeedSource.Podcast(7)
            feeds.unplayedCounts[source to null] = 4
            episodes.unplayedCounts[source to null] = 4

            viewModel.markAllPlayed()
            advanceUntilIdle()

            val opened = viewModel.markPlayed.state.value!!
            assertEquals(source, opened.source)
            assertEquals(UiText.Raw("Serial Box"), opened.title)
            assertEquals(MarkPlayedOption.ALL, opened.option)
            assertEquals(4, opened.count)

            viewModel.markPlayed.confirm()
            advanceUntilIdle()
            assertNull(viewModel.markPlayed.state.value)
            assertEquals(listOf("markFeedPlayed($source, null, 4)"), episodes.calls)
        }

    @Test
    fun countedMarkPlayedFreezesCutoffAndReconfirmsChangedSelection() =
        runTest {
            val viewModel = viewModel()
            podcasts.detail.value = testPodcastDetail(7)
            collect(viewModel)
            advanceUntilIdle()
            val source = FeedSource.Podcast(7)
            val before = clock.now() - 7.days.inWholeMilliseconds
            feeds.unplayedCounts[source to before] = 2
            episodes.unplayedCounts[source to before] = 3

            viewModel.markAllPlayed()
            viewModel.markPlayed.select(MarkPlayedOption.WEEK)
            advanceUntilIdle()
            assertEquals(
                2,
                viewModel.markPlayed.state.value
                    ?.count,
            )
            assertTrue(episodes.calls.isEmpty())

            // The cutoff stays frozen at select() time even as the wall clock moves on.
            clock.advanceBy(2.days)
            viewModel.markPlayed.confirm()
            advanceUntilIdle()
            val changed = viewModel.markPlayed.state.value!!
            assertEquals(before, changed.sortDateBefore)
            assertEquals(3, changed.count)
            assertTrue(changed.selectionChanged)
            assertTrue(episodes.calls.isEmpty())

            // A repeat confirm while WRITING is inert; the second real confirm lands.
            viewModel.markPlayed.confirm()
            viewModel.markPlayed.confirm()
            advanceUntilIdle()
            assertNull(viewModel.markPlayed.state.value)
            assertEquals(listOf("markFeedPlayed($source, $before, 3)"), episodes.calls)
        }

    @Test
    fun markAllPlayedWithoutDetailOpensNothing() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            advanceUntilIdle()

            viewModel.markAllPlayed()
            advanceUntilIdle()

            assertNull(viewModel.markPlayed.state.value)
            assertTrue(episodes.calls.isEmpty())
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
    fun setCredentialsFaultPostsAMessage() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            podcasts.writeError = Exception("disk full")

            assertNull(viewModel.setCredentials(BasicCredentials("u", "p")))
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.messages
                    .isNotEmpty(),
            )
        }

    @Test
    fun unsubscribeWriteFailurePostsAMessage() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            podcasts.writeError = Exception("disk full")

            viewModel.unsubscribe()
            advanceUntilIdle()

            assertTrue(podcasts.calls.isEmpty())
            assertTrue(
                viewModel.uiState.value.messages
                    .isNotEmpty(),
            )
        }

    @Test
    fun feedOrderWriteFailurePostsAMessage() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            feeds.writeError = Exception("disk full")

            viewModel.setOrder(FeedOrder.OLDEST_FIRST)
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.messages
                    .isNotEmpty(),
            )
        }

    /** The header's membership chips follow `observeGroupsOf(podcastId)` (05 Membership). */
    @Test
    fun membershipsFlowIntoTheHeaderChips() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            podcasts.detail.value = testPodcastDetail(7)
            val tech = groups.createId("tech")
            val news = groups.createId("news")
            groups.members.value = mapOf(tech to setOf(7L), news to setOf(1L))
            advanceUntilIdle()

            assertEquals(
                listOf("tech"),
                viewModel.uiState.value.groups
                    .map { it.name },
            )

            groups.members.value = mapOf(tech to setOf(7L), news to setOf(1L, 7L))
            advanceUntilIdle()
            assertEquals(
                listOf("tech", "news"),
                viewModel.uiState.value.groups
                    .map { it.name },
            )
        }

    /** The chip's trailing "x" applies just that membership's removal and offers its Undo (D6). */
    @Test
    fun removeFromGroupDropsOnlyThatMembershipAndOffersUndo() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            val tech = groups.createId("tech")
            val news = groups.createId("news")
            groups.members.value = mapOf(tech to setOf(7L), news to setOf(7L))
            advanceUntilIdle()

            viewModel.removeFromGroup(groups.groups.value.first { it.id == tech })
            advanceUntilIdle()

            assertEquals(emptySet(), groups.members.value[tech])
            assertEquals(setOf(7L), groups.members.value[news])
            val message =
                viewModel.uiState.value.messages
                    .single()
            val text = message.text as UiText.Res
            assertEquals(Res.string.podcast_removed_from_group, text.id)
            assertEquals(listOf("tech"), text.args)
            assertEquals(Res.string.action_undo, (message.action as UiText.Res).id)
        }

    /** Undo re-adds exactly the membership the snackbar reported — nothing neighbouring. */
    @Test
    fun undoRestoresOnlyTheRemovedMembership() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            val tech = groups.createId("tech")
            val news = groups.createId("news")
            groups.members.value = mapOf(tech to setOf(7L), news to setOf(7L))
            advanceUntilIdle()

            viewModel.removeFromGroup(groups.groups.value.first { it.id == tech })
            advanceUntilIdle()
            val undoId =
                viewModel.uiState.value.messages
                    .single()
                    .id

            viewModel.onMessageAction(undoId)
            advanceUntilIdle()
            assertEquals(setOf(7L), groups.members.value[tech])
            assertEquals(setOf(7L), groups.members.value[news])

            // A spent Undo id is a no-op, as is one for a dismissed snackbar.
            viewModel.onMessageAction(undoId)
            viewModel.onMessageAction(undoId + 999)
            advanceUntilIdle()
            assertEquals(setOf(7L), groups.members.value[tech])
        }

    /**
     * A group deleted between render and tap (05's NotFound race) needs no snackbar: the
     * requested end state already holds and the observed flow has dropped the chip.
     */
    @Test
    fun removeFromAGoneGroupStaysQuiet() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            advanceUntilIdle()
            groups.createId("stale")
            val stale = groups.groups.value.single()
            groups.groups.value = emptyList()
            viewModel.removeFromGroup(stale)
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.messages
                    .isEmpty(),
            )
        }

    @Test
    fun markAllPlayedWriteFailureStaysInlineInTheDialog() =
        runTest {
            val viewModel = viewModel()
            podcasts.detail.value = testPodcastDetail(7)
            collect(viewModel)
            advanceUntilIdle()
            val source = FeedSource.Podcast(7)
            feeds.unplayedCounts[source to null] = 1
            episodes.unplayedCounts[source to null] = 1
            episodes.writeError = Exception("disk full")

            viewModel.markAllPlayed()
            advanceUntilIdle()
            viewModel.markPlayed.confirm()
            advanceUntilIdle()

            // The guarded write's failure reports inside the dialog — no snackbar — and
            // keeps the count so a retry can land.
            assertTrue(episodes.calls.isEmpty())
            assertTrue(
                viewModel.uiState.value.messages
                    .isEmpty(),
            )
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
            advanceUntilIdle()
            assertNull(viewModel.markPlayed.state.value)
            assertEquals(listOf("markFeedPlayed($source, null, 1)"), episodes.calls)
        }

    @Test
    fun rowPlayStartsThePodcastFeedAnchoredAtTheRow() =
        runTest {
            val viewModel = viewModel()
            podcasts.detail.value = testPodcastDetail(7, episodeOrder = FeedOrder.OLDEST_FIRST)
            playbackController.playResult = PlayResult.Started
            collect(viewModel)
            advanceUntilIdle()

            val filters = FeedFilters(unplayedOnly = true, media = MediaFilter.AUDIO)
            viewModel.onFiltersChange(filters)
            viewModel.onRowAction(EpisodeAction.PlayToggle(42))
            advanceUntilIdle()

            assertEquals(
                listOf<FakePlaybackController.Call>(
                    FakePlaybackController.Call.Feed(
                        FeedSource.Podcast(7),
                        filters,
                        FeedOrder.OLDEST_FIRST,
                        42L,
                    ),
                ),
                playbackController.calls,
            )
        }

    @Test
    fun playToggleOnThePlayingItemPausesWithoutANewStart() =
        runTest {
            playbackStates.nowPlaying.value = testNowPlaying(42)
            val viewModel = viewModel()

            viewModel.onRowAction(EpisodeAction.PlayToggle(42))
            advanceUntilIdle()

            assertEquals(
                listOf<FakePlaybackController.Call>(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.PAUSE),
                ),
                playbackController.calls,
            )
        }

    @Test
    fun playNextAndPlayLastEnqueueTheRow() =
        runTest {
            val viewModel = viewModel()

            viewModel.onRowAction(EpisodeAction.PlayNext(42))
            viewModel.onRowAction(EpisodeAction.PlayLast(43))
            advanceUntilIdle()

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
        runTest {
            val viewModel = viewModel()
            podcasts.detail.value = testPodcastDetail(7)
            val refused = PlayResult.NeedsMeteredConsent(42)
            playbackController.playResult = refused
            playbackStates.pendingMeteredConsent.value = refused
            collect(viewModel)
            advanceUntilIdle()

            viewModel.onRowAction(EpisodeAction.PlayToggle(42))
            advanceUntilIdle()
            assertEquals(
                42L,
                viewModel.playback.meteredConsent.value
                    ?.episodeId,
            )

            playbackController.calls.clear()
            playbackController.playResult = PlayResult.Started
            viewModel.playback.consentOnce()
            advanceUntilIdle()

            assertNull(viewModel.playback.meteredConsent.value)
            assertEquals(
                listOf<FakePlaybackController.Call>(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.CONSENT),
                    FakePlaybackController.Call.Feed(
                        FeedSource.Podcast(7),
                        FeedFilters(),
                        FeedOrder.NEWEST_FIRST,
                        42L,
                    ),
                ),
                playbackController.calls,
            )
        }

    @Test
    fun meteredBlockedPostsTheSettingsAction() =
        runTest {
            val viewModel = viewModel()
            podcasts.detail.value = testPodcastDetail(7)
            playbackController.playResult = PlayResult.MeteredBlocked
            collect(viewModel)
            advanceUntilIdle()

            viewModel.onRowAction(EpisodeAction.PlayToggle(42))
            advanceUntilIdle()

            val message =
                viewModel.uiState.value.messages
                    .single()
            assertEquals(
                PlaybackMessageAction.OpenPlaybackSettings,
                viewModel.playback.onMessageAction(message.id),
            )
        }
}
