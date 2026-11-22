// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import androidx.lifecycle.ViewModelStore
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.EpisodeRepository
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.RowLive
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeLiveStateSource
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeRepository
import ch.lkmc.neutrodyne.core.testing.FakeFeedRepository
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.testLibraryTile
import ch.lkmc.neutrodyne.core.testing.testNowPlaying
import ch.lkmc.neutrodyne.core.testing.testPlatformInfo
import ch.lkmc.neutrodyne.core.ui.radio.RadioEpisode
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackIssue
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import ch.lkmc.neutrodyne.playback.api.PositionSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RadioViewModelTest : MainDispatcherTest() {
    private val groups = FakeGroupRepository()
    private val episodes = FakeEpisodeRepository()
    private val controller = FakePlaybackController().apply { playResult = PlayResult.Started }
    private val live = FakeEpisodeLiveStateSource()
    private val settings = FakeSettingsRepository()
    private val feeds = FakeFeedRepository()
    private val podcasts = FakePodcastRepository()
    private val rows = (1L..3L).map { RadioEpisode(it, "Episode $it", "Podcast", 60_000) }

    private suspend fun TestScope.withRadio(
        repository: ch.lkmc.neutrodyne.core.domain.GroupRepository = groups,
        episodeRepository: EpisodeRepository = episodes,
        playbackController: PlaybackController = controller,
        block: suspend (RadioViewModel, Long) -> Unit,
    ) {
        val id = (groups.create(GroupDraft("First"), setOf(1)) as Outcome.Success).value
        podcasts.tiles.value = listOf(testLibraryTile(1))
        val model =
            RadioViewModel(
                repository,
                feeds,
                podcasts,
                episodeRepository,
                live,
                playbackController,
                controller.state,
                settings,
                testPlatformInfo,
            )
        val store = ViewModelStore().apply { put("radio", model) }
        try {
            runCurrent()
            model.onRows(id, rows)
            model.onVisibleIds(rows.map { it.id }.toSet())
            runCurrent()
            block(model, id)
        } finally {
            store.clear()
        }
    }

    @Test fun selectingSameEpisodeIsInertAndOtherSelectionUsesSavedPosition() =
        runTest {
            live.live.value = mapOf(2L to RowLive(positionMs = 12_000))
            withRadio { model, _ ->
                val before = controller.calls.size
                model.selectEpisode(1)
                assertEquals(before, controller.calls.size)
                model.selectEpisode(2)
                assertEquals(12_000, model.state.value.elapsedMs)
                assertFalse(model.state.value.playing)
                assertEquals(before, controller.calls.size)
            }
        }

    @Test fun playUsesGroupContextAndPendingNeedleSeek() =
        runTest {
            withRadio { model, id ->
                model.seek(25_000)
                assertTrue(
                    controller.calls.none {
                        it is FakePlaybackController.Call.Transport &&
                            it.action == FakePlaybackController.Action.SEEK
                    },
                )
                model.togglePlay()
                runCurrent()
                val start = controller.calls.filterIsInstance<FakePlaybackController.Call.Feed>().single()
                assertEquals(FeedSource.Group(id), start.source)
                assertEquals(1L, start.startId)
                assertTrue(start.filters.unplayedOnly)
                assertEquals(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.SEEK, 25_000),
                    controller.calls.last(),
                )
            }
        }

    /**
     * A needle seek parked for an episode that is not loaded belongs to the radio's own start.
     * When Up next or a notification loads that episode instead, the dial must follow its ticks,
     * and resuming must not jump back to the parked position.
     */
    @Test fun aParkedSeekYieldsWhenTheEpisodeStartsElsewhere() =
        runTest {
            withRadio { model, _ ->
                model.seek(25_000)
                controller.state.nowPlaying.value = testNowPlaying(1)
                runCurrent()
                controller.state.positionTicks.emit(PositionSnapshot(1, 40_000, 50_000, 60_000, 1f, true, 0))
                runCurrent()
                assertEquals(40_000, model.state.value.elapsedMs)

                controller.state.nowPlaying.value = testNowPlaying(1).copy(isPlaying = false, playWhenReady = false)
                runCurrent()
                val before = controller.calls.size
                model.togglePlay()
                runCurrent()
                val after = controller.calls.drop(before)
                assertTrue(after.none { it is FakePlaybackController.Call.Feed }, "resumes, not restarts: $after")
                assertTrue(
                    after.any {
                        it is FakePlaybackController.Call.Transport &&
                            it.action == FakePlaybackController.Action.PLAY
                    },
                    "resumes playback: $after",
                )
                assertTrue(
                    after.none {
                        it is FakePlaybackController.Call.Transport &&
                            it.action == FakePlaybackController.Action.SEEK
                    },
                    "no stale seek: $after",
                )
            }
        }

    @Test fun aSlipsPlayKeyPlaysThatEpisodeAndPausesItWhilePlaying() =
        runTest {
            withRadio { model, id ->
                // A silent radio: the key selects the slip and starts it.
                model.playEpisode(2)
                runCurrent()
                assertEquals(2L, model.state.value.selectedId)
                val start = controller.calls.filterIsInstance<FakePlaybackController.Call.Feed>().last()
                assertEquals(FeedSource.Group(id), start.source)
                assertEquals(2L, start.startId)

                // The playing slip's key pauses it.
                controller.state.nowPlaying.value = testNowPlaying(2).copy(isPlaying = true)
                runCurrent()
                assertTrue(model.state.value.playing)
                model.playEpisode(2)
                assertEquals(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.PAUSE),
                    controller.calls.last(),
                )

                // Another slip's key, while the radio plays, switches to that episode.
                controller.state.nowPlaying.value = testNowPlaying(2).copy(isPlaying = true)
                runCurrent()
                model.playEpisode(3)
                runCurrent()
                assertEquals(3L, model.state.value.selectedId)
                assertEquals(
                    3L,
                    controller.calls
                        .filterIsInstance<FakePlaybackController.Call.Feed>()
                        .last()
                        .startId,
                )
            }
        }

    @Test fun seekUsesTheSameDurationAsTheRadioScale() =
        runTest {
            withRadio { model, _ ->
                val now = testNowPlaying(1)
                controller.state.nowPlaying.value = now.copy(position = now.position.copy(durationMs = -1))
                runCurrent()
                model.seek(90_000)
                assertEquals(60_000L, model.state.value.elapsedMs)

                controller.state.nowPlaying.value = now.copy(position = now.position.copy(durationMs = 75_000))
                runCurrent()
                model.seek(90_000)
                assertEquals(75_000L, model.state.value.elapsedMs)
            }
        }

    @Test fun skipKeysMoveByTheListenersIntervalsAndStopAtTheEnds() =
        runTest {
            settings.set(PlaybackSettingKeys.SKIP_BACK_MS, 15_000L)
            settings.set(PlaybackSettingKeys.SKIP_FORWARD_MS, 45_000L)
            withRadio { model, _ ->
                assertEquals(15_000L, model.state.value.skipBackMs)
                assertEquals(45_000L, model.state.value.skipForwardMs)

                // Not loaded yet: the position waits for play, like a needle seek.
                model.skipForward()
                assertEquals(45_000L, model.state.value.elapsedMs)
                model.skipForward()
                assertEquals(60_000L, model.state.value.elapsedMs)
                model.skipBack()
                assertEquals(45_000L, model.state.value.elapsedMs)

                // Loaded at 10 s: one skip back stops at the start and seeks the player there.
                controller.state.nowPlaying.value = testNowPlaying(1)
                runCurrent()
                model.skipBack()
                assertEquals(0L, model.state.value.elapsedMs)
                assertEquals(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.SEEK, 0),
                    controller.calls.last(),
                )
            }
        }

    @Test fun discardCompletionPreservesANewerSelectionAndSeek() =
        runTest {
            val releaseWrite = CompletableDeferred<Unit>()
            val delayedEpisodes =
                object : EpisodeRepository by episodes {
                    override suspend fun setPlayed(
                        episodeIds: List<Long>,
                        played: Boolean,
                    ) {
                        releaseWrite.await()
                        episodes.setPlayed(episodeIds, played)
                    }
                }
            withRadio(episodeRepository = delayedEpisodes) { model, _ ->
                model.discard(1)
                runCurrent()
                model.selectEpisode(3)
                controller.state.nowPlaying.value =
                    testNowPlaying(
                        3,
                    ).copy(isPlaying = false, phase = PlayerPhase.ERROR, issue = PlaybackIssue.NETWORK_LOST)
                runCurrent()
                model.seek(17_500)
                val calls = controller.calls.toList()
                releaseWrite.complete(Unit)
                runCurrent()
                assertEquals(3L, model.state.value.selectedId)
                assertEquals(17_500L, model.state.value.elapsedMs)
                assertEquals(PlayerPhase.ERROR, model.state.value.phase)
                assertEquals(PlaybackIssue.NETWORK_LOST, model.state.value.issue)
                assertEquals(calls, controller.calls)
                assertEquals(rows.drop(1), model.state.value.episodes)
                assertEquals(rows.first(), model.state.value.removed)
            }
        }

    @Test fun radioShowsAndCancelsAStartBeforeTheEngineLoads() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val slow =
                object : PlaybackController by controller {
                    override suspend fun playFeed(
                        source: FeedSource,
                        filters: FeedFilters,
                        order: FeedOrder,
                        startEpisodeId: Long?,
                    ): PlayResult {
                        release.await()
                        return controller.playFeed(source, filters, order, startEpisodeId)
                    }
                }
            withRadio(playbackController = slow) { model, _ ->
                model.togglePlay()
                runCurrent()
                assertTrue(model.state.value.switchOn)
                assertTrue(model.state.value.starting)
                assertFalse(model.state.value.playing)
                model.togglePlay()
                release.complete(Unit)
                runCurrent()
                assertFalse(model.state.value.switchOn)
                assertEquals(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.PAUSE),
                    controller.calls.single(),
                )
            }
        }

    @Test fun secondTapDuringBufferingPausesInsteadOfStartingAgain() =
        runTest {
            withRadio { model, _ ->
                controller.state.nowPlaying.value =
                    testNowPlaying(1).copy(isPlaying = false, playWhenReady = true, phase = PlayerPhase.BUFFERING)
                runCurrent()
                model.togglePlay()
                runCurrent()
                assertEquals(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.PAUSE),
                    controller.calls.last(),
                )
            }
        }

    @Test fun transportPhaseAndIssueReachTheRadioState() =
        runTest {
            withRadio { model, _ ->
                controller.state.nowPlaying.value =
                    testNowPlaying(1).copy(isPlaying = false, phase = PlayerPhase.BUFFERING)
                runCurrent()
                assertEquals(PlayerPhase.BUFFERING, model.state.value.phase)
                controller.state.nowPlaying.value =
                    testNowPlaying(1)
                        .copy(isPlaying = false, phase = PlayerPhase.ERROR, issue = PlaybackIssue.NETWORK_LOST)
                runCurrent()
                assertEquals(PlayerPhase.ERROR, model.state.value.phase)
                assertEquals(PlaybackIssue.NETWORK_LOST, model.state.value.issue)
                controller.state.nowPlaying.value = null
                runCurrent()
                assertNull(model.state.value.phase)
                assertNull(model.state.value.issue)
            }
        }

    @Test fun aPagingSnapshotDoesNotKeepAStaleTransportPhase() =
        runTest {
            withRadio { model, id ->
                controller.state.nowPlaying.value =
                    testNowPlaying(1).copy(phase = PlayerPhase.ERROR, issue = PlaybackIssue.PLAYER_ERROR)
                runCurrent()
                assertEquals(PlayerPhase.ERROR, model.state.value.phase)
                controller.state.nowPlaying.value = null
                runCurrent()
                model.onRows(id, rows)
                runCurrent()
                assertNull(model.state.value.phase)
                assertNull(model.state.value.issue)
            }
        }

    @Test fun discardSelectedAdvancesWhileOtherRemovalLeavesPlayerAloneAndUndoDoesNotRewind() =
        runTest {
            withRadio { model, _ ->
                controller.state.nowPlaying.value = testNowPlaying(1).copy(isPlaying = true)
                runCurrent()
                model.discard(1)
                runCurrent()
                assertEquals(2L, model.state.value.selectedId)
                assertEquals(
                    2L,
                    controller.calls
                        .filterIsInstance<FakePlaybackController.Call.Feed>()
                        .last()
                        .startId,
                )
                assertEquals("setPlayed([1], true)", episodes.calls.last())
                model.undoDiscard()
                runCurrent()
                assertEquals(
                    listOf(1L, 2L, 3L),
                    model.state.value.episodes
                        .map { it.id },
                )
                assertEquals(2L, model.state.value.selectedId)
                assertEquals("setPlayed([1], false)", episodes.calls.last())
                val calls = controller.calls.size
                model.discard(3)
                runCurrent()
                assertEquals(calls, controller.calls.size)
            }
        }

    @Test fun removingTheLastProgrammeRowKeepsUnrelatedPlayback() =
        runTest {
            withRadio { model, id ->
                model.onRows(id, rows.take(1))
                controller.state.nowPlaying.value = testNowPlaying(999).copy(isPlaying = true)
                runCurrent()
                val calls = controller.calls.toList()
                model.discard(1)
                runCurrent()
                assertEquals(calls, controller.calls)
                assertEquals(999L, model.state.value.selectedId)
                assertTrue(model.state.value.playing)
                assertTrue(
                    model.state.value.episodes
                        .isEmpty(),
                )
            }
        }

    @Test fun removingLastStopsAndUndoLeavesSelectionEmpty() =
        runTest {
            withRadio { model, id ->
                model.onRows(id, rows.take(1))
                model.discard(1)
                runCurrent()
                assertNull(model.state.value.selectedId)
                assertFalse(model.state.value.playing)
                assertEquals(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.DISMISS),
                    controller.calls.last(),
                )
                model.undoDiscard()
                runCurrent()
                assertNull(model.state.value.selectedId)
                assertEquals(
                    listOf(1L),
                    model.state.value.episodes
                        .map { it.id },
                )
            }
        }

    @Test fun switchingPausesSelectsFirstAndScopesRemovalFeedback() =
        runTest {
            withRadio { model, first ->
                model.discard(3)
                runCurrent()
                val second = (groups.create(GroupDraft("Second"), setOf(1)) as Outcome.Success).value
                runCurrent()
                model.selectPlaylist(second)
                model.onRows(second, rows.reversed())
                runCurrent()
                assertEquals(3L, model.state.value.selectedId)
                assertFalse(model.state.value.playing)
                assertNull(model.state.value.removed)
                model.selectPlaylist(first)
                model.onRows(first, rows.take(2))
                runCurrent()
                assertEquals(1L, model.state.value.selectedId)
                assertEquals(
                    3L,
                    model.state.value.removed
                        ?.id,
                )
            }
        }

    @Test fun queueAutoAdvanceOutsideGroupStillDrivesRadioAndResumesContext() =
        runTest {
            withRadio { model, id ->
                controller.state.nowPlaying.value =
                    testNowPlaying(99).copy(
                        context =
                            ch.lkmc.neutrodyne.core.model.PlayContextInfo(
                                ch.lkmc.neutrodyne.core.model.ContextType.GROUP,
                                id,
                                "First",
                                ch.lkmc.neutrodyne.core.model.FeedOrder.NEWEST_FIRST,
                                0,
                                ch.lkmc.neutrodyne.core.model.MediaFilter.ALL,
                            ),
                    )
                runCurrent()
                model.onRows(id, rows)
                assertEquals(99L, model.state.value.selectedId)
                assertEquals(
                    99L,
                    model.state.value.selected
                        ?.id,
                )
            }
        }

    @Test fun playbackStartedOnAnotherScreenUsesTheRadioTransport() =
        runTest {
            withRadio { model, _ ->
                val external = testNowPlaying(99).copy(isPlaying = true, context = null)
                controller.state.nowPlaying.value = external
                runCurrent()
                assertEquals(99L, model.state.value.selectedId)
                assertTrue(model.state.value.playing)
                model.togglePlay()
                assertEquals(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.PAUSE),
                    controller.calls.last(),
                )
                controller.state.nowPlaying.value = external.copy(isPlaying = false, playWhenReady = false)
                runCurrent()
                model.togglePlay()
                runCurrent()
                assertEquals(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.PLAY),
                    controller.calls.last(),
                )
            }
        }

    @Test fun theRadioIsUntunedOnlyOnceTheRepositoryReportsNoPlaylist() =
        runTest {
            withRadio { model, id ->
                assertTrue(model.state.value.playlistsLoaded)
                assertFalse(model.state.value.untuned)
                groups.delete(id)
                runCurrent()
                assertTrue(model.state.value.untuned)
            }
        }

    @Test fun playbackRemainsControllableBeforeAnyPlaylistExists() =
        runTest {
            withRadio { model, id ->
                groups.delete(id)
                runCurrent()
                assertNull(model.state.value.playlistId)
                controller.state.nowPlaying.value = testNowPlaying(99).copy(isPlaying = true, context = null)
                runCurrent()
                assertEquals(
                    99L,
                    model.state.value.selected
                        ?.id,
                )
                model.togglePlay()
                assertEquals(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.PAUSE),
                    controller.calls.last(),
                )
            }
        }

    @Test fun incomingFeedSelectionOnlyNavigatesAndExplicitSwitchPauses() =
        runTest {
            withRadio { model, first ->
                val second = (groups.create(GroupDraft("Second"), setOf(1)) as Outcome.Success).value
                runCurrent()
                controller.state.nowPlaying.value = testNowPlaying(1).copy(isPlaying = true, context = null)
                runCurrent()
                val callsBefore = controller.calls.size
                val uuid =
                    groups
                        .observeGroups()
                        .first()
                        .first { it.id == second }
                        .uuid
                settings.set(
                    ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys.FEEDS_SELECTED_SOURCE,
                    ch.lkmc.neutrodyne.core.model.FeedSourceRef
                        .Group(uuid)
                        .encode(),
                )
                runCurrent()
                model.onRows(second, listOf(RadioEpisode(20, "Other programme", "Podcast")))
                assertEquals(callsBefore, controller.calls.size)
                assertEquals(second, model.state.value.playlistId)
                assertEquals(1L, model.state.value.selectedId)
                model.selectPlaylist(first)
                model.onRows(first, rows)
                assertEquals(1L, model.state.value.selectedId)
                assertFalse(model.state.value.playing)
                assertEquals(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.PAUSE),
                    controller.calls.last(),
                )
                runCurrent()
                settings.set(
                    ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys.FEEDS_SELECTED_SOURCE,
                    ch.lkmc.neutrodyne.core.model.FeedSourceRef
                        .Group(uuid)
                        .encode(),
                )
                runCurrent()
                controller.state.nowPlaying.value =
                    testNowPlaying(1).copy(isPlaying = false, playWhenReady = false, context = null)
                runCurrent()
                model.onRows(second, listOf(RadioEpisode(20, "Other programme", "Podcast")))
                assertEquals(20L, model.state.value.selectedId)
                assertFalse(model.state.value.playing)
            }
        }

    @Test fun failedSelectionKeepsAnExistingSessionControllable() =
        runTest {
            withRadio { model, _ ->
                controller.state.nowPlaying.value = testNowPlaying(1).copy(isPlaying = true)
                runCurrent()
                controller.playResult = PlayResult.NeedsMeteredConsent(2)
                model.selectEpisode(2)
                runCurrent()
                assertEquals(1L, model.state.value.selectedId)
                assertTrue(model.state.value.playing)
            }
        }

    @Test fun failedMeteredAlwaysDoesNotGrantConsent() =
        runTest {
            withRadio { model, _ ->
                val prompt = PlayResult.NeedsMeteredConsent(1)
                controller.playResult = prompt
                controller.state.pendingMeteredConsent.value = prompt
                model.togglePlay()
                runCurrent()
                settings.failNextSet = ch.lkmc.neutrodyne.core.domain.SettingsError.WriteFailed
                model.playback.consentAlways()
                runCurrent()
                assertTrue(
                    controller.calls.none {
                        it ==
                            FakePlaybackController.Call.Transport(FakePlaybackController.Action.CONSENT)
                    },
                )
            }
        }

    @Test fun incompletePageShowsRepositoryCountAndUndoKeepsChronology() =
        runTest {
            withRadio { model, id ->
                feeds.unplayedCounts[FeedSource.Group(id) to null] = 500
                val dated = rows.map { it.copy(sortDate = 100 - it.id) }
                model.onRows(id, dated, complete = false)
                runCurrent()
                assertEquals(500, model.state.value.episodeCount)
                assertFalse(model.state.value.complete)
                model.discard(2)
                runCurrent()
                val newest = RadioEpisode(99, "New", "Podcast", 60_000, 200)
                model.onRows(id, listOf(newest) + dated.filterNot { it.id == 2L })
                model.undoDiscard()
                runCurrent()
                assertEquals(
                    listOf(99L, 1L, 2L, 3L),
                    model.state.value.episodes
                        .map { it.id },
                )
            }
        }

    @Test fun undoAppliesAPagingSnapshotThatArrivedDuringTheWrite() =
        runTest {
            val releaseUndo = CompletableDeferred<Unit>()
            val delayedEpisodes =
                object : EpisodeRepository by episodes {
                    override suspend fun setPlayed(
                        episodeIds: List<Long>,
                        played: Boolean,
                    ) {
                        if (!played) releaseUndo.await()
                        episodes.setPlayed(episodeIds, played)
                    }
                }
            withRadio(episodeRepository = delayedEpisodes) { model, id ->
                val dated = rows.map { it.copy(sortDate = 100 - it.id) }
                model.onRows(id, dated)
                runCurrent()
                model.discard(2)
                runCurrent()
                assertEquals(
                    listOf(1L, 3L),
                    model.state.value.episodes
                        .map { it.id },
                )

                model.undoDiscard()
                runCurrent()
                // A newer page lands while the un-play write is still pending; it must not be dropped.
                val newest = RadioEpisode(4, "Episode 4", "Podcast", 60_000, sortDate = 200)
                model.onRows(id, listOf(newest) + dated.filterNot { it.id == 2L })
                releaseUndo.complete(Unit)
                runCurrent()

                assertEquals(
                    listOf(4L, 1L, 2L, 3L),
                    model.state.value.episodes
                        .map { it.id },
                )
            }
        }

    @Test fun pagingAccessUsesAbsoluteOffsetsAndIgnoresStaleRows() {
        assertEquals(401, radioPageIndex(42, 400, listOf(41, 42, 43)))
        assertEquals(1, radioPageIndex(42, 0, listOf(41, 42, 43)))
        assertNull(radioPageIndex(1, 400, listOf(41, 42, 43)))
    }

    @Test fun deletingAndUndoingTheActiveGroupDoesNotReselectIt() =
        runTest {
            withRadio { model, id ->
                val uuid =
                    groups
                        .observeGroups()
                        .first()
                        .first { it.id == id }
                        .uuid
                settings.set(
                    ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys.FEEDS_SELECTED_SOURCE,
                    ch.lkmc.neutrodyne.core.model.FeedSourceRef
                        .Group(uuid)
                        .encode(),
                )
                val second = (groups.create(GroupDraft("Second"), setOf(1)) as Outcome.Success).value
                runCurrent()
                val token = (groups.delete(id) as Outcome.Success).value
                runCurrent()
                assertEquals(second, model.state.value.playlistId)
                assertEquals(
                    ch.lkmc.neutrodyne.core.model.FeedSourceRef.All
                        .encode(),
                    settings.get(ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys.FEEDS_SELECTED_SOURCE),
                )
                groups.undoDelete(token)
                runCurrent()
                assertEquals(second, model.state.value.playlistId)
            }
        }

    @Test fun failedFallbackWriteDoesNotLetGroupUndoRestoreSelection() =
        runTest {
            withRadio { model, id ->
                val uuid =
                    groups
                        .observeGroups()
                        .first()
                        .first { it.id == id }
                        .uuid
                settings.set(
                    ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys.FEEDS_SELECTED_SOURCE,
                    ch.lkmc.neutrodyne.core.model.FeedSourceRef
                        .Group(uuid)
                        .encode(),
                )
                val second = (groups.create(GroupDraft("Second"), setOf(1)) as Outcome.Success).value
                runCurrent()
                settings.failNextSet = ch.lkmc.neutrodyne.core.domain.SettingsError.WriteFailed
                val token = (groups.delete(id) as Outcome.Success).value
                runCurrent()
                groups.undoDelete(token)
                runCurrent()
                assertEquals(second, model.state.value.playlistId)
                model.selectPlaylist(id)
                runCurrent()
                assertEquals(id, model.state.value.playlistId)
            }
        }

    @Test fun droppingEarlierPagesKeepsThePausedSelectionAndItsResumePosition() =
        runTest {
            live.live.value = mapOf(2L to RowLive(positionMs = 12_000))
            withRadio { model, id ->
                model.selectEpisode(2)
                model.onRows(id, (100L..200L).map { RadioEpisode(it, "Episode $it", "Podcast") }, complete = false)
                runCurrent()
                assertEquals(2L, model.state.value.selectedId)
                assertEquals(
                    "Episode 2",
                    model.state.value.selected
                        ?.title,
                )
                assertEquals(12_000L, model.state.value.elapsedMs)
            }
        }

    @Test fun loadedPagesDoNotBecomeAnOffscreenLiveQueryWindow() =
        runTest {
            val id = (groups.create(GroupDraft("Bounded"), setOf(1)) as Outcome.Success).value
            val model =
                RadioViewModel(
                    groups,
                    feeds,
                    podcasts,
                    episodes,
                    live,
                    controller,
                    controller.state,
                    settings,
                    testPlatformInfo,
                )
            val store = ViewModelStore().apply { put("radio", model) }
            try {
                runCurrent()
                model.onRows(id, (1L..200L).map { RadioEpisode(it, "Episode $it", "Podcast") })
                runCurrent()
                assertTrue(live.lastVisibleIds.value.isEmpty())
                model.onVisibleIds(setOf(100, 101, 102))
                runCurrent()
                assertEquals(setOf(1L, 100L, 101L, 102L), live.lastVisibleIds.value)
                model.selectEpisode(100)
                runCurrent()
                assertEquals(setOf(100L, 101L, 102L), live.lastVisibleIds.value)
                model.onVisibleIds(emptySet())
                runCurrent()
                assertTrue(live.lastVisibleIds.value.isEmpty())
            } finally {
                store.clear()
            }
        }

    @Test fun failedDiscardDoesNotRemoveAnythingOrOfferUndo() =
        runTest {
            withRadio { model, _ ->
                episodes.writeError = IllegalStateException("write refused")
                model.discard(1)
                runCurrent()
                assertEquals(rows, model.state.value.episodes)
                assertNull(model.state.value.removed)
                assertEquals(1L, model.state.value.selectedId)
            }
        }
}
