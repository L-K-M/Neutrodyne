// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.queue

import androidx.lifecycle.ViewModelStore
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.domain.AddPodcastError
import ch.lkmc.neutrodyne.core.domain.PodcastRepository
import ch.lkmc.neutrodyne.core.domain.QueueRepository
import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedPrefs
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.GroupDraft
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PlayContextInfo
import ch.lkmc.neutrodyne.core.model.PlaySessionInfo
import ch.lkmc.neutrodyne.core.model.QueueRemoval
import ch.lkmc.neutrodyne.core.model.UpNextItem
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeLiveStateSource
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeRepository
import ch.lkmc.neutrodyne.core.testing.FakeFeedRepository
import ch.lkmc.neutrodyne.core.testing.FakeGroupRepository
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePlatformInfo
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.FakeQueueRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeCapabilitiesSource
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.testEpisodeDetail
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import ch.lkmc.neutrodyne.core.testing.testNowPlaying
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_undo
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import ch.lkmc.neutrodyne.playback.api.UnplayableReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UpNextViewModelTest : MainDispatcherTest() {
    private val queue = FakeQueueRepository()
    private val playback = FakePlaybackController()
    private val episodes = FakeEpisodeRepository()
    private val feeds = FakeFeedRepository()
    private val groups = FakeGroupRepository()
    private val live = FakeEpisodeLiveStateSource()
    private val store = ViewModelStore()
    private val podcasts = FakePodcastRepository()

    private fun viewModel(
        repository: QueueRepository = queue,
        podcasts: PodcastRepository = this.podcasts,
    ) =
        UpNextViewModel(
            queue = repository,
            controller = playback,
            playback = playback.state,
            episodes = episodes,
            feeds = feeds,
            groups = groups,
            capabilities = FakeYouTubeCapabilitiesSource(),
            network = FakeNetworkMonitor(),
            liveSource = live,
            settings = FakeSettingsRepository(),
            platform = FakePlatformInfo(PlatformKind.ANDROID),
            podcasts = podcasts,
        ).also { store.put("queue", it) }

    @AfterTest fun close() = store.clear()

    @Test fun observesFixedCurrentCompleteQueueAndContext() =
        runTest {
            val context = PlayContextInfo(ContextType.GROUP, 3, "tech", FeedOrder.NEWEST_FIRST, 0, MediaFilter.ALL)
            queue.session.value = PlaySessionInfo(1, context, 7)
            playback.state.nowPlaying.value = testNowPlaying()
            queue.upNext.value = (1L..125L).map { UpNextItem(it, testEpisodeRow(it)) }
            val vm = viewModel()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.loaded)
            assertEquals(
                1L,
                vm.uiState.value.current
                    ?.episodeId,
            )
            assertEquals(124, vm.uiState.value.upNext.size)
            assertEquals(context, vm.uiState.value.context)
            assertTrue(queue.requestedWindows.isEmpty(), "UI must not use the bounded player window")
        }

    @Test fun durableCurrentIsVisibleBeforePlayerConnection() =
        runTest {
            queue.session.value = PlaySessionInfo(8, null, 1)
            episodes.episodes.value = mapOf(8L to testEpisodeDetail(8))
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(
                8L,
                vm.uiState.value.current
                    ?.episodeId,
            )
            assertFalse(
                vm.uiState.value.current!!
                    .isPlaying,
            )
        }

    @Test fun bufferingCurrentRowProjectsPauseAffordance() =
        runTest {
            queue.session.value = PlaySessionInfo(1, null, 1)
            playback.state.nowPlaying.value =
                testNowPlaying().copy(
                    isPlaying = false,
                    playWhenReady = true,
                    phase = PlayerPhase.BUFFERING,
                )
            val vm = viewModel()
            advanceUntilIdle()
            // playToggle pauses while a play is requested, so the row must offer Pause.
            assertTrue(
                vm.uiState.value.current!!
                    .isPlaying,
            )
        }

    @Test fun currentRowPausesOrResumesOtherRowStartsExplicitly() =
        runTest {
            val vm = viewModel()
            playback.playResult = PlayResult.Started
            playback.state.nowPlaying.value = testNowPlaying()
            vm.onAction(EpisodeAction.PlayToggle(1))
            advanceUntilIdle()
            playback.state.nowPlaying.value = testNowPlaying().copy(isPlaying = false, playWhenReady = false)
            vm.onAction(EpisodeAction.PlayToggle(1))
            advanceUntilIdle()
            vm.onAction(EpisodeAction.PlayToggle(2))
            vm.onAction(EpisodeAction.PlayNext(3))
            vm.onAction(EpisodeAction.PlayLast(4))
            advanceUntilIdle()
            assertEquals(
                listOf(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.PAUSE),
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.PLAY),
                    FakePlaybackController.Call.Episode(2),
                    FakePlaybackController.Call.Queue(FakePlaybackController.Placement.NEXT, listOf(3)),
                    FakePlaybackController.Call.Queue(FakePlaybackController.Placement.LAST, listOf(4)),
                ),
                playback.calls,
            )
        }

    @Test fun meteredConsentRetriesTheExactQueuedRowOnce() =
        runTest {
            val vm = viewModel()
            val refused = PlayResult.NeedsMeteredConsent(2)
            playback.playResult = refused
            // The gate parks the refused prompt for the dialog the way `startLocked` does.
            playback.state.pendingMeteredConsent.value = refused
            vm.onAction(EpisodeAction.PlayToggle(2))
            advanceUntilIdle()
            assertEquals(
                2L,
                vm.playbackActions.meteredConsent.value
                    ?.episodeId,
            )
            assertTrue(
                vm.uiState.value.messages
                    .isEmpty(),
            )
            playback.playResult = PlayResult.Started
            vm.playbackActions.consentOnce()
            // `grantMeteredStreaming` clears the parked prompt.
            playback.state.pendingMeteredConsent.value = null
            vm.playbackActions.consentOnce()
            advanceUntilIdle()
            assertEquals(
                listOf(
                    FakePlaybackController.Call.Episode(2),
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.CONSENT),
                    FakePlaybackController.Call.Episode(2),
                ),
                playback.calls,
            )
        }

    @Test fun passwordSubmitSavesWithoutReplayingTheRefusedStart() =
        runTest {
            episodes.episodes.value = mapOf(2L to testEpisodeDetail(2, podcastId = 9))
            val release = CompletableDeferred<Unit>()
            val delayedPodcasts =
                object : PodcastRepository by podcasts {
                    override suspend fun setCredentials(
                        podcastId: Long,
                        credentials: BasicCredentials,
                    ): Outcome<Unit, AddPodcastError> {
                        release.await()
                        return podcasts.setCredentials(podcastId, credentials)
                    }
                }
            val vm = viewModel(podcasts = delayedPodcasts)
            playback.playResult = PlayResult.NotPlayable(2, UnplayableReason.AuthRequired)
            vm.onAction(EpisodeAction.PlayToggle(2))
            advanceUntilIdle()
            vm.onPlaybackMessageAction(
                vm.uiState.value.messages
                    .single()
                    .id,
            )
            assertEquals(2L, vm.passwordEpisode.value)
            vm.submitPassword(BasicCredentials("listener", "password"))
            // The dialog closes at submit, like the sibling screens' credential commits.
            assertEquals(null, vm.passwordEpisode.value)
            // A newer start issued while the suspending save is parked must not be replaced.
            playback.playResult = PlayResult.Started
            vm.onAction(EpisodeAction.PlayToggle(5))
            advanceUntilIdle()
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals("setCredentials(9, listener)", podcasts.calls.single())
            assertEquals(
                listOf<FakePlaybackController.Call>(
                    FakePlaybackController.Call.Episode(2),
                    FakePlaybackController.Call.Episode(5),
                ),
                playback.calls,
            )
        }

    @Test fun completedPasswordSaveKeepsReopenedSameEpisodeDialog() =
        runTest {
            episodes.episodes.value = mapOf(2L to testEpisodeDetail(2, podcastId = 9))
            val release = CompletableDeferred<Unit>()
            val delayedPodcasts =
                object : PodcastRepository by podcasts {
                    override suspend fun setCredentials(
                        podcastId: Long,
                        credentials: BasicCredentials,
                    ): Outcome<Unit, AddPodcastError> {
                        release.await()
                        return podcasts.setCredentials(podcastId, credentials)
                    }
                }
            val vm = viewModel(podcasts = delayedPodcasts)
            playback.playResult = PlayResult.NotPlayable(2, UnplayableReason.AuthRequired)
            vm.onAction(EpisodeAction.PlayToggle(2))
            advanceUntilIdle()
            vm.onPlaybackMessageAction(
                vm.uiState.value.messages
                    .single()
                    .id,
            )
            vm.submitPassword(BasicCredentials("listener", "password"))
            // Cancel and re-trigger the same episode's prompt while the first save is parked.
            vm.dismissPassword()
            vm.onAction(EpisodeAction.PlayToggle(2))
            advanceUntilIdle()
            vm.onPlaybackMessageAction(
                vm.uiState.value.messages
                    .last()
                    .id,
            )
            assertEquals(2L, vm.passwordEpisode.value)
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(2L, vm.passwordEpisode.value)
            assertEquals("setCredentials(9, listener)", podcasts.calls.single())
        }

    @Test fun completedPasswordSaveKeepsReplacementDialog() =
        runTest {
            episodes.episodes.value =
                mapOf(
                    2L to testEpisodeDetail(2, podcastId = 9),
                    3L to testEpisodeDetail(3, podcastId = 10),
                )
            val release = CompletableDeferred<Unit>()
            val delayedPodcasts =
                object : PodcastRepository by podcasts {
                    override suspend fun setCredentials(
                        podcastId: Long,
                        credentials: BasicCredentials,
                    ): Outcome<Unit, AddPodcastError> {
                        release.await()
                        return podcasts.setCredentials(podcastId, credentials)
                    }
                }
            val vm = viewModel(podcasts = delayedPodcasts)
            playback.playResult = PlayResult.NotPlayable(2, UnplayableReason.AuthRequired)
            vm.onAction(EpisodeAction.PlayToggle(2))
            advanceUntilIdle()
            vm.onPlaybackMessageAction(
                vm.uiState.value.messages
                    .single()
                    .id,
            )
            vm.submitPassword(BasicCredentials("listener", "password"))
            // A different episode's prompt replaces the dialog while the first save is parked.
            playback.playResult = PlayResult.NotPlayable(3, UnplayableReason.AuthRequired)
            vm.onAction(EpisodeAction.PlayToggle(3))
            advanceUntilIdle()
            vm.onPlaybackMessageAction(
                vm.uiState.value.messages
                    .last()
                    .id,
            )
            assertEquals(3L, vm.passwordEpisode.value)
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(3L, vm.passwordEpisode.value)
            assertEquals("setCredentials(9, listener)", podcasts.calls.single())
        }

    @Test fun failedPasswordSaveLeavesAnActionableMessage() =
        runTest {
            episodes.episodes.value = mapOf(2L to testEpisodeDetail(2, podcastId = 9))
            podcasts.credentialsOutcome = Outcome.Failure(AddPodcastError.Http(403))
            val vm = viewModel()
            playback.playResult = PlayResult.NotPlayable(2, UnplayableReason.AuthRequired)
            vm.onAction(EpisodeAction.PlayToggle(2))
            advanceUntilIdle()
            vm.onPlaybackMessageAction(
                vm.uiState.value.messages
                    .single()
                    .id,
            )
            vm.submitPassword(BasicCredentials("listener", "password"))
            advanceUntilIdle()
            assertEquals("setCredentials(9, listener)", podcasts.calls.single())
            assertEquals(null, vm.passwordEpisode.value)
            assertEquals(
                UiText.Res(Res.string.write_failed),
                vm.uiState.value.messages
                    .last()
                    .text,
            )
        }

    @Test fun removeOffersOneShotUndoOfCommittedToken() =
        runTest {
            val token = QueueRemoval(listOf(2), listOf(1, 2, 3))
            val restored = mutableListOf<QueueRemoval>()
            val repository =
                object : QueueRepository by queue {
                    override suspend fun removeWithUndo(episodeIds: List<Long>): QueueRemoval {
                        assertEquals(listOf(2L), episodeIds)
                        return token
                    }

                    override suspend fun undoRemoval(removal: QueueRemoval) {
                        restored += removal
                    }
                }
            val vm = viewModel(repository)
            vm.remove(2)
            advanceUntilIdle()
            val message =
                vm.uiState.value.messages
                    .single()
            vm.undo(message.id)
            vm.undo(message.id)
            advanceUntilIdle()
            assertEquals(listOf(token), restored)
            vm.onMessageShown(message.id)
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.messages
                    .isEmpty(),
            )
        }

    @Test fun failedUndoRebindsTheSameTokenForRetry() =
        runTest {
            val token = QueueRemoval(listOf(2), listOf(1, 2, 3))
            var attempts = 0
            val restored = mutableListOf<QueueRemoval>()
            val repository =
                object : QueueRepository by queue {
                    override suspend fun removeWithUndo(episodeIds: List<Long>): QueueRemoval = token

                    override suspend fun undoRemoval(removal: QueueRemoval) {
                        attempts++
                        if (attempts == 1) throw IllegalStateException("disk full")
                        restored += removal
                    }
                }
            val vm = viewModel(repository)
            vm.remove(2)
            advanceUntilIdle()
            val first =
                vm.uiState.value.messages
                    .single()
            vm.undo(first.id)
            // A duplicate tap consumes nothing: the token left the map with the first click.
            vm.undo(first.id)
            // ShowUserMessages acknowledges the snackbar right after launching its action.
            vm.onMessageShown(first.id)
            advanceUntilIdle()
            assertEquals(1, attempts)
            val retry =
                vm.uiState.value.messages
                    .single()
            assertEquals(UiText.Res(Res.string.write_failed), retry.text)
            assertEquals(UiText.Res(Res.string.action_undo), retry.action)
            vm.undo(retry.id)
            vm.undo(retry.id)
            advanceUntilIdle()
            assertEquals(2, attempts)
            assertEquals(listOf(token), restored)
            vm.onMessageShown(retry.id)
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.messages
                    .isEmpty(),
            )
        }

    @Test fun clearTokenIsCapturedByRepositoryAndAckDiscardsUndo() =
        runTest {
            var cleared = 0
            var undone = 0
            val repository =
                object : QueueRepository by queue {
                    override suspend fun clearWithUndo(): QueueRemoval {
                        cleared++
                        return QueueRemoval(listOf(1, 2), listOf(1, 2))
                    }

                    override suspend fun undoRemoval(removal: QueueRemoval) {
                        undone++
                    }
                }
            val vm = viewModel(repository)
            vm.clear()
            advanceUntilIdle()
            vm.onMessageShown(
                vm.uiState.value.messages
                    .single()
                    .id,
            )
            vm.undo(0)
            advanceUntilIdle()
            assertEquals(1, cleared)
            assertEquals(0, undone)
        }

    @Test fun failureOffersNoUndoAndStopAfterOnlyClearsContext() =
        runTest {
            val vm = viewModel()
            queue.failure = IllegalStateException("disk full")
            vm.remove(2)
            advanceUntilIdle()
            assertEquals(
                null,
                vm.uiState.value.messages
                    .single()
                    .action,
            )
            queue.failure = null
            vm.stopAfter()
            vm.move(3, 0)
            advanceUntilIdle()
            assertEquals(FakeQueueRepository.Operation.CLEAR_CONTEXT, queue.calls[1].operation)
            assertEquals(FakeQueueRepository.Operation.MOVE, queue.calls[2].operation)
            assertEquals(0, queue.calls[2].toIndex)
            assertTrue(playback.calls.isEmpty())
        }

    @Test fun groupShortcutCarriesCurrentPreferencesAndUsesFirstThreeGroups() =
        runTest {
            repeat(5) { groups.create(GroupDraft("Group $it")) }
            val source = FeedSource.Group(groups.groups.value[0].id)
            feeds.prefs.value =
                mapOf(
                    source to
                        FeedPrefs(
                            filters = FeedFilters(inProgressOnly = true, downloadedOnly = true),
                            playOrder = FeedOrder.OLDEST_FIRST,
                        ),
                )
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(groups.groups.value.take(3), vm.uiState.value.groups)
            vm.playGroup(source.groupId)
            advanceUntilIdle()
            val call = playback.calls.single() as FakePlaybackController.Call.Feed
            assertEquals(source, call.source)
            assertEquals(FeedOrder.OLDEST_FIRST, call.order)
            assertTrue(call.filters.inProgressOnly)
            assertFalse(call.filters.downloadedOnly)
            assertEquals(null, call.startId)
        }
}
