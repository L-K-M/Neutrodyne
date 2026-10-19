// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.episode

import ch.lkmc.neutrodyne.core.model.BasicCredentials
import ch.lkmc.neutrodyne.core.model.settings.FeedsSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.ShowNotesImages
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.testing.FakeChapterRepository
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeRepository
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.FakePlaybackStateSource
import ch.lkmc.neutrodyne.core.testing.FakePodcastRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.testEpisodeDetail
import ch.lkmc.neutrodyne.core.testing.testNowPlaying
import ch.lkmc.neutrodyne.core.testing.testPlatformInfo
import ch.lkmc.neutrodyne.core.ui.EpisodeAction
import ch.lkmc.neutrodyne.core.ui.PlaybackMessageAction
import ch.lkmc.neutrodyne.core.ui.ShowNotesImageMode
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.action_copy_link
import ch.lkmc.neutrodyne.core.ui.resources.no_handler
import ch.lkmc.neutrodyne.core.ui.resources.write_failed
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.UnplayableReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `EpisodeViewModel` on the desktop JVM (09): episode/notes/offline state, the
 * `feeds.show_notes_images` × metered fold into [ShowNotesImageMode] (03 Images and links,
 * PO-21), and the played/favourite writes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeViewModelTest : MainDispatcherTest() {
    private val downloads =
        ch.lkmc.neutrodyne.core.testing
            .FakeDownloadController()
    private val liveSource =
        ch.lkmc.neutrodyne.core.testing
            .FakeEpisodeLiveStateSource()
    private val episodes = FakeEpisodeRepository()
    private val chapters =
        FakeChapterRepository()
    private val podcasts = FakePodcastRepository()
    private val settings = FakeSettingsRepository()
    private val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
    private val playbackController = FakePlaybackController()
    private val playbackStates = FakePlaybackStateSource()

    private fun viewModel(episodeId: Long = 5) =
        EpisodeViewModel(
            episodeId,
            episodes,
            chapters,
            podcasts,
            settings,
            playbackController,
            playbackStates,
            testPlatformInfo,
            network,
            downloadController = downloads,
            liveStateSource = liveSource,
        )

    private fun TestScope.collect(viewModel: EpisodeViewModel) {
        backgroundScope.launch { viewModel.uiState.collect {} }
    }

    @Test fun progressWindowTracksVisibilityAndClearsDuringTheSharingGrace() =
        runTest {
            val model = viewModel()
            val subscription = backgroundScope.launch { model.uiState.collect {} }
            runCurrent()
            assertEquals(emptySet(), liveSource.lastVisibleIds.value)

            model.onVisible()
            runCurrent()
            assertEquals(setOf(5L), liveSource.lastVisibleIds.value)

            subscription.cancel()
            model.onHidden()
            runCurrent()
            assertEquals(emptySet(), liveSource.lastVisibleIds.value)
        }

    @Test fun timestampSeeksCurrentAndStartsOtherEpisodeAtExactPosition() =
        runTest {
            val model = viewModel()
            collect(model)
            playbackStates.nowPlaying.value = testNowPlaying(5)
            runCurrent()
            model.onTimestamp(754_000)
            playbackStates.nowPlaying.value = testNowPlaying(6)
            model.onTimestamp(754_000)
            runCurrent()
            assertEquals(
                listOf(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.SEEK, 754_000),
                    FakePlaybackController.Call.Episode(5, 754_000),
                ),
                playbackController.calls,
            )
        }

    @Test
    fun initialStateBeforeSubscription() =
        runTest {
            assertEquals(EpisodeUiState(), viewModel().uiState.value)
        }

    @Test
    fun currentPlaybackStateUpdatesTheDetailAffordance() =
        runTest {
            val model = viewModel()
            collect(model)
            playbackStates.nowPlaying.value = testNowPlaying(5)
            advanceUntilIdle()
            assertTrue(model.uiState.value.isNowPlaying)
            assertTrue(model.uiState.value.isPlaying)
            playbackStates.nowPlaying.value = testNowPlaying(5).copy(isPlaying = false, playWhenReady = false)
            advanceUntilIdle()
            assertTrue(model.uiState.value.isNowPlaying)
            assertTrue(!model.uiState.value.isPlaying)
            playbackStates.nowPlaying.value = testNowPlaying(6)
            advanceUntilIdle()
            assertTrue(!model.uiState.value.isNowPlaying)
        }

    @Test
    fun episodeAndNotesLoad() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            episodes.episodes.value = mapOf(5L to testEpisodeDetail(5, title = "The Interview"))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.loaded)
            assertEquals("The Interview", state.episode?.title)
            assertTrue(!state.gone)
        }

    @Test
    fun loadedNullEpisodeIsGone() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.gone)
        }

    @Test
    fun offlineFlagTracksTheMonitor() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            episodes.episodes.value = mapOf(5L to testEpisodeDetail(5))
            advanceUntilIdle()
            assertTrue(!viewModel.uiState.value.offline)

            network.setStatus(FakeNetworkMonitor.OFFLINE)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.offline)
        }

    @Test
    fun imageModeFoldsTheSettingAndMetered() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            episodes.episodes.value = mapOf(5L to testEpisodeDetail(5))
            advanceUntilIdle()

            // TAP_TO_LOAD default setting → TAP_TO_LOAD even unmetered.
            assertEquals(ShowNotesImageMode.TAP_TO_LOAD, viewModel.uiState.value.imageMode)

            settings.set(FeedsSettingKeys.SHOW_NOTES_IMAGES, ShowNotesImages.ALWAYS)
            advanceUntilIdle()
            assertEquals(ShowNotesImageMode.SHOWN, viewModel.uiState.value.imageMode)

            // WIFI_ONLY shows images unmetered and blocks them on a metered link (PO-21): the
            // Wi-Fi-only setting has no tap-to-load escape (03 Images and links).
            settings.set(FeedsSettingKeys.SHOW_NOTES_IMAGES, ShowNotesImages.WIFI_ONLY)
            advanceUntilIdle()
            assertEquals(ShowNotesImageMode.SHOWN, viewModel.uiState.value.imageMode)

            network.setStatus(FakeNetworkMonitor.ONLINE.copy(isMetered = true))
            advanceUntilIdle()
            assertEquals(ShowNotesImageMode.BLOCKED, viewModel.uiState.value.imageMode)
        }

    @Test
    fun setPlayedWritesToRepository() =
        runTest {
            val viewModel = viewModel()
            viewModel.setPlayed(true)
            advanceUntilIdle()
            viewModel.setPlayed(false)
            advanceUntilIdle()
            assertEquals(listOf("setPlayed([5], true)", "setPlayed([5], false)"), episodes.calls)
        }

    @Test
    fun setFavoriteWritesToRepository() =
        runTest {
            val viewModel = viewModel()
            viewModel.setFavorite(true)
            advanceUntilIdle()
            assertEquals(listOf("setFavorite(5, true)"), episodes.calls)
        }

    @Test
    fun rowPlayedActionWritesToRepository() =
        runTest {
            val viewModel = viewModel()
            viewModel.onAction(EpisodeAction.SetPlayed(9, played = true))
            advanceUntilIdle()
            assertEquals(listOf("setPlayed([9], true)"), episodes.calls)
        }

    @Test
    fun playerActionsAreInertAtM1a() =
        runTest {
            val viewModel = viewModel()
            viewModel.onAction(EpisodeAction.PlayToggle(9))
            viewModel.onAction(EpisodeAction.DownloadToggle(9))
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

            viewModel.setPlayed(true)
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
    fun setFavoriteWriteFailurePostsAMessage() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            episodes.writeError = Exception("disk full")

            viewModel.setFavorite(true)
            advanceUntilIdle()

            assertTrue(episodes.calls.isEmpty())
            assertTrue(
                viewModel.uiState.value.messages
                    .isNotEmpty(),
            )
        }

    @Test
    fun failedLinksStayBoundToTheirMessageIdsUntilAcknowledged() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            viewModel.onLinkOpenFailed("https://example.com/first")
            viewModel.onLinkOpenFailed("https://example.com/second")
            advanceUntilIdle()

            val (first, second) = viewModel.uiState.value.messages
            assertEquals(UiText.Res(Res.string.no_handler), first.text)
            assertEquals(UiText.Res(Res.string.action_copy_link), first.action)
            assertEquals("https://example.com/first", viewModel.onMessageAction(first.id))
            assertEquals("https://example.com/second", viewModel.onMessageAction(second.id))
            assertNull(viewModel.onMessageAction(Long.MAX_VALUE))

            viewModel.onMessageShown(first.id)
            advanceUntilIdle()
            assertNull(viewModel.onMessageAction(first.id))
            assertEquals(listOf(second), viewModel.uiState.value.messages)
            assertEquals("https://example.com/second", viewModel.onMessageAction(second.id))

            viewModel.onMessageShown(second.id)
            advanceUntilIdle()
            assertNull(viewModel.onMessageAction(second.id))
            assertTrue(
                viewModel.uiState.value.messages
                    .isEmpty(),
            )
        }

    @Test
    fun playToggleStartsTheEpisode() =
        runTest {
            playbackController.playResult = PlayResult.Started
            val viewModel = viewModel()

            viewModel.onAction(EpisodeAction.PlayToggle(5))
            advanceUntilIdle()

            assertEquals(
                listOf<FakePlaybackController.Call>(FakePlaybackController.Call.Episode(5)),
                playbackController.calls,
            )
        }

    @Test
    fun playToggleOnThePlayingItemPausesWithoutANewStart() =
        runTest {
            playbackStates.nowPlaying.value = testNowPlaying(5)
            val viewModel = viewModel()

            viewModel.onAction(EpisodeAction.PlayToggle(5))
            advanceUntilIdle()

            assertEquals(
                listOf<FakePlaybackController.Call>(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.PAUSE),
                ),
                playbackController.calls,
            )
        }

    @Test
    fun playNextAndPlayLastEnqueueTheEpisode() =
        runTest {
            val viewModel = viewModel()

            viewModel.onAction(EpisodeAction.PlayNext(5))
            viewModel.onAction(EpisodeAction.PlayLast(5))
            advanceUntilIdle()

            assertEquals(
                listOf<FakePlaybackController.Call>(
                    FakePlaybackController.Call.Queue(FakePlaybackController.Placement.NEXT, listOf(5L)),
                    FakePlaybackController.Call.Queue(FakePlaybackController.Placement.LAST, listOf(5L)),
                ),
                playbackController.calls,
            )
        }

    @Test
    fun needsMeteredConsentOpensTheDialogAndOnceRetriesTheStart() =
        runTest {
            playbackController.playResult = PlayResult.NeedsMeteredConsent(5)
            val viewModel = viewModel()
            collect(viewModel)
            advanceUntilIdle()

            viewModel.onAction(EpisodeAction.PlayToggle(5))
            advanceUntilIdle()
            assertEquals(
                5L,
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
                    FakePlaybackController.Call.Episode(5),
                ),
                playbackController.calls,
            )
        }

    @Test
    fun consentAlwaysPersistsAllowThenRetries() =
        runTest {
            settings.set(PlaybackSettingKeys.STREAM_ON_METERED, StreamOnMetered.ASK)
            playbackController.playResult = PlayResult.NeedsMeteredConsent(5)
            val viewModel = viewModel()
            collect(viewModel)
            advanceUntilIdle()

            viewModel.onAction(EpisodeAction.PlayToggle(5))
            advanceUntilIdle()
            playbackController.calls.clear()
            playbackController.playResult = PlayResult.Started

            viewModel.playback.consentAlways()
            advanceUntilIdle()

            assertEquals(
                StreamOnMetered.ALLOW,
                settings.get(PlaybackSettingKeys.STREAM_ON_METERED),
            )
            assertTrue("set(playback.stream_on_metered)" in settings.calls)
            assertEquals(
                listOf<FakePlaybackController.Call>(
                    FakePlaybackController.Call.Transport(FakePlaybackController.Action.CONSENT),
                    FakePlaybackController.Call.Episode(5),
                ),
                playbackController.calls,
            )
        }

    @Test
    fun authRequiredOffersEnterPasswordAndCredentialsSaveForTheEpisodesPodcast() =
        runTest {
            playbackController.playResult =
                PlayResult.NotPlayable(5, UnplayableReason.AuthRequired)
            episodes.episodes.value = mapOf(5L to testEpisodeDetail(5, podcastId = 7))
            val viewModel = viewModel()
            collect(viewModel)
            advanceUntilIdle()

            viewModel.onAction(EpisodeAction.PlayToggle(5))
            advanceUntilIdle()

            val message =
                viewModel.uiState.value.messages
                    .single()
            assertEquals(
                PlaybackMessageAction.EnterPassword(5),
                viewModel.playback.onMessageAction(message.id),
            )

            viewModel.onEnterPassword(5)
            advanceUntilIdle()
            assertEquals(7L, viewModel.pendingCredentials.value)

            viewModel.onCredentials(BasicCredentials("user", "pw"))
            advanceUntilIdle()
            assertNull(viewModel.pendingCredentials.value)
            assertEquals(listOf("setCredentials(7, user)"), podcasts.calls)
        }

    @Test fun downloadToggleReachesTheManualRequestHandler() =
        runTest {
            val vm = viewModel()
            vm.onAction(EpisodeAction.DownloadToggle(5))
            runCurrent()
            assertEquals(
                ch.lkmc.neutrodyne.core.testing.FakeDownloadController.Action.REQUEST,
                downloads.calls.single().action,
            )
            assertEquals(listOf(5L), downloads.calls.single().ids)
        }
}
