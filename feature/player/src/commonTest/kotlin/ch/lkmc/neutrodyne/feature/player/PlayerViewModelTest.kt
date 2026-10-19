// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import ch.lkmc.neutrodyne.core.domain.EpisodeRepository
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.ArtworkColors
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.Effective
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.EpisodeChapter
import ch.lkmc.neutrodyne.core.model.ExternalReason
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.ShowNoteBlock
import ch.lkmc.neutrodyne.core.model.ShowNoteSpan
import ch.lkmc.neutrodyne.core.model.ShowNotes
import ch.lkmc.neutrodyne.core.model.SleepTimerMode
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeArtworkRepository
import ch.lkmc.neutrodyne.core.testing.FakeChapterRepository
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeRepository
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController.Action
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController.Call
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeYouTubeExternalOpen
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.testEpisodeDetail
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.feature.player.resources.Res
import ch.lkmc.neutrodyne.feature.player.resources.player_audio_gone
import ch.lkmc.neutrodyne.feature.player.resources.player_auth_required
import ch.lkmc.neutrodyne.feature.player.resources.player_no_media
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.PlaybackEvent
import ch.lkmc.neutrodyne.playback.api.ScopeWriteResult
import ch.lkmc.neutrodyne.playback.api.UnplayableReason
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest : MainDispatcherTest() {
    private val controller = FakePlaybackController()
    private val artwork =
        FakeArtworkRepository()
    private val settings = FakeSettingsRepository()
    private val chapters =
        FakeChapterRepository()
    private val episodes = FakeEpisodeRepository()
    private val clock = TestClock(elapsedMs = 5_000)

    private fun TestScope.player(episodeRepo: EpisodeRepository = episodes): PlayerViewModel {
        val vm =
            PlayerViewModel(
                controller,
                controller.state,
                settings,
                clock,
                Provider { chapters },
                Provider { episodeRepo },
                FakeNetworkMonitor(),
                artwork = artwork,
                externalOpenService = Provider { FakeYouTubeExternalOpen() },
            )
        backgroundScope.launch { vm.uiState.collect {} }
        backgroundScope.launch { vm.position.collect {} }
        runCurrent()
        return vm
    }

    @Test fun tintFollowsCurrentArtworkAndClearsOnSwitchOrDismiss() =
        runTest {
            val first =
                sampleNow().copy(
                    artwork =
                        ArtworkRef("first", null, 1),
                )
            val second =
                first.copy(
                    episodeId = first.episodeId + 1,
                    artwork =
                        ArtworkRef("second", null, 2),
                )
            val red =
                ArtworkColors("first", 1, 0xFFDD4400.toInt(), 0xFFAA4400.toInt())
            artwork.colors.value = mapOf("first" to red)
            controller.state.nowPlaying.value = first
            val vm = player()
            runCurrent()
            assertEquals(red, vm.uiState.value.colors)
            controller.state.nowPlaying.value = second
            runCurrent()
            assertNull(vm.uiState.value.colors)
            artwork.colors.value = mapOf("first" to red.copy(version = 2))
            runCurrent()
            assertNull(vm.uiState.value.colors)
            val mono =
                ArtworkColors("second", 2, null, 0xFF888888.toInt())
            artwork.colors.value = mapOf("first" to red, "second" to mono)
            runCurrent()
            assertEquals(mono, vm.uiState.value.colors)
            controller.state.nowPlaying.value = null
            runCurrent()
            assertNull(vm.uiState.value.colors)
        }

    @Test fun externalFallbackUsesRefusedEpisodeEvenWithoutCurrentPlayback() =
        runTest {
            episodes.episodes.value =
                mapOf(
                    8L to
                        testEpisodeDetail(
                            id = 8,
                            sourceType = SourceType.YOUTUBE_CHANNEL,
                            externalMediaId = "dQw4w9WgXcQ",
                        ),
                )
            val vm = player()
            val reason = UnplayableReason.YouTubeExternal(ExternalReason.NOT_YET_AVAILABLE)
            controller.state.events.emit(PlaybackEvent.StartRefused(PlayResult.NotPlayable(8, reason)))
            runCurrent()
            val message =
                vm.uiState.value.messages
                    .single()
            assertEquals(PlayerMessageAction.WATCH_ON_YOUTUBE, vm.onMessageAction(message.id))
            val episodeId = vm.watchEpisode(message.id)
            assertEquals(8L, episodeId)
            vm.onMessageShown(message.id)
            assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", vm.watchLink(requireNotNull(episodeId)))
        }

    @Test fun sleepPersistsOnlyValidMinuteChoicesAndReportsWriteFailure() =
        runTest {
            controller.state.nowPlaying.value = sampleNow()
            val vm = player()
            vm.setSleepTimer(
                SleepTimerMode
                    .Minutes(0),
            )
            vm.setSleepTimer(
                SleepTimerMode
                    .Minutes(241),
            )
            assertTrue(controller.calls.isEmpty())
            vm.setSleepTimer(
                SleepTimerMode
                    .Minutes(45),
            )
            runCurrent()
            assertEquals(45, vm.uiState.value.lastSleepMinutes)
            vm.setSleepTimer(SleepTimerMode.EndOfEpisode)
            vm.setSleepTimer(SleepTimerMode.Off)
            vm.extendSleepTimer(5)
            runCurrent()
            assertEquals(45, vm.uiState.value.lastSleepMinutes)
            assertEquals(4, controller.calls.size)
            settings.failNextSet = SettingsError.WriteFailed
            vm.setSleepTimer(
                SleepTimerMode
                    .Minutes(15),
            )
            runCurrent()
            assertTrue(
                vm.uiState.value.messages
                    .isNotEmpty(),
            )
            controller.state.nowPlaying.value = null
            vm.setSleepTimer(
                SleepTimerMode
                    .Minutes(30),
            )
            assertEquals(5, controller.calls.size)
        }

    @Test fun chaptersFollowEpisodeAndTransportFallsBackToEpisodes() =
        runTest {
            controller.state.nowPlaying.value = sampleNow()
            val chapter =
                EpisodeChapter(
                    0,
                    null,
                    "Opening",
                    null,
                    null,
                    ChapterSource.PSC,
                    false,
                )
            chapters.rows.value = mapOf(1L to listOf(chapter))
            val vm = player()
            vm.previous()
            vm.next()
            vm.nextEpisode()
            assertEquals(
                listOf(Action.CHAPTER_PREV, Action.CHAPTER_NEXT, Action.NEXT),
                controller.calls.map {
                    (it as Call.Transport).action
                },
            )
            controller.calls.clear()
            controller.state.nowPlaying.value = sampleNow(2)
            runCurrent()
            assertTrue(
                vm.uiState.value.chapters
                    .isEmpty(),
            )
            vm.previous()
            vm.next()
            assertEquals(listOf(Action.PREVIOUS, Action.NEXT), controller.calls.map { (it as Call.Transport).action })
        }

    @Test fun notesObserveOnlyVisibleTabAndSwitchWithCurrentEpisode() =
        runTest {
            controller.state.nowPlaying.value = sampleNow()
            val first =
                ShowNotes(
                    listOf(
                        ShowNoteBlock.Paragraph(
                            listOf(
                                ShowNoteSpan
                                    .Timestamp("12:34", 754_000),
                            ),
                        ),
                    ),
                )
            episodes.notes.value = mapOf(1L to first)
            val observed = mutableListOf<Long>()
            val repo =
                object : EpisodeRepository by episodes {
                    override fun observeShowNotes(episodeId: Long): Flow<ShowNotes?> =
                        episodes
                            .observeShowNotes(episodeId)
                            .onStart { observed += episodeId }
                            .onCompletion { observed.remove(episodeId) }
                }
            val vm = player(repo)
            val collection = backgroundScope.launch { vm.notes.collect {} }
            runCurrent()
            assertTrue(observed.isEmpty())
            assertNull(vm.notes.value.notes)
            vm.showNotes(true)
            runCurrent()
            assertEquals(first, vm.notes.value.notes)
            assertEquals(listOf(1L), observed)
            controller.state.nowPlaying.value = sampleNow(2)
            runCurrent()
            assertNull(vm.notes.value.notes)
            assertEquals(listOf(2L), observed)
            vm.showNotes(false)
            runCurrent()
            assertNull(vm.notes.value.notes)
            assertTrue(observed.isEmpty())
            vm.showNotes(true)
            runCurrent()
            collection.cancel()
            runCurrent()
            assertTrue(observed.isEmpty())
        }

    @Test fun interpolationDoesNotPublishFramesOrReadWallTime() =
        runTest {
            controller.state.nowPlaying.value = sampleNow(playing = true)
            val vm = player()
            val snapshot = requireNotNull(vm.position.value)
            val metadata = vm.uiState.value
            assertEquals(16_000L, vm.positionAt(snapshot))
            clock.elapsedMs += 1_000
            clock.nowMs -= 999_000
            runCurrent()
            assertEquals(17_000L, vm.positionAt(snapshot))
            assertEquals(snapshot, vm.position.value)
            assertEquals(metadata, vm.uiState.value)
        }

    @Test fun dismissCannotCloseAnEpisodeThatResumedBeforeDispatch() =
        runTest {
            controller.state.nowPlaying.value = sampleNow()
            val vm = player()
            vm.dismissPaused()
            controller.state.nowPlaying.value = sampleNow(playing = true)
            runCurrent()
            assertTrue(controller.calls.isEmpty())
        }

    @Test fun seekCannotUseAnOldEpisodesPositionBounds() =
        runTest {
            controller.state.nowPlaying.value = sampleNow()
            val vm = player()
            controller.state.nowPlaying.value = sampleNow(3)
            vm.seekTo(50_000)
            assertTrue(controller.calls.isEmpty())
        }

    @Test fun clearingRetainedConsentCannotResurrectTheControllerResult() =
        runTest {
            controller.state.pendingMeteredConsent.value = PlayResult.NeedsMeteredConsent(8)
            controller.playResult = PlayResult.NeedsMeteredConsent(8)
            val vm = player()
            vm.togglePlayPause()
            runCurrent()
            controller.state.pendingMeteredConsent.value = null
            runCurrent()
            assertNull(vm.uiState.value.consent)
        }

    @Test fun rendersCurrentEffectiveSettingsAndLiveIncrements() =
        runTest {
            val vm = player()
            val now = sampleNow()
            val effective =
                EffectivePlayback(
                    Effective(1.5f, SettingSource.Group("tech", "Tech")),
                    Effective(true, SettingSource.PodcastOverride),
                )
            controller.state.nowPlaying.value = now
            controller.state.effectivePlayback.value = effective
            settings.set(PlaybackSettingKeys.SKIP_BACK_MS, 20_000L)
            settings.set(PlaybackSettingKeys.SKIP_FORWARD_MS, 45_000L)
            runCurrent()
            assertEquals(now, vm.uiState.value.now)
            assertEquals(effective, vm.uiState.value.effective)
            assertEquals(20_000L, vm.uiState.value.skipBackMs)
            assertEquals(45_000L, vm.uiState.value.skipForwardMs)
        }

    @Test fun ticksNeverCrossEpisodeBoundariesOrReprojectMetadata() =
        runTest {
            controller.state.nowPlaying.value = sampleNow()
            val vm = player()
            val metadata = vm.uiState.value
            controller.state.positionTicks.emit(samplePosition().copy(positionMs = 25_000))
            runCurrent()
            assertEquals(25_000L, vm.position.value?.positionMs)
            assertEquals(metadata, vm.uiState.value)
            controller.state.nowPlaying.value = sampleNow(3)
            runCurrent()
            controller.state.positionTicks.emit(samplePosition().copy(positionMs = 59_000))
            runCurrent()
            assertEquals(3L, vm.position.value?.episodeId)
            assertEquals(12_000L, vm.position.value?.positionMs)
            controller.state.nowPlaying.value = null
            runCurrent()
            assertNull(vm.position.value)
        }

    @Test fun playRefusalIsVisibleAndPauseDoesNotStart() =
        runTest {
            controller.state.nowPlaying.value = sampleNow(playing = true)
            val vm = player()
            vm.togglePlayPause()
            assertEquals(listOf<Call>(Call.Transport(Action.PAUSE)), controller.calls)
            controller.state.nowPlaying.value = sampleNow()
            vm.togglePlayPause()
            runCurrent()
            assertEquals<Call>(Call.Transport(Action.PLAY), controller.calls.last())
            assertEquals(1, vm.uiState.value.messages.size)
        }

    @Test fun fireAndForgetRefusalsAreNotLost() =
        runTest {
            val vm = player()
            controller.state.events.emit(PlaybackEvent.StartRefused(PlayResult.Offline))
            runCurrent()
            assertEquals(1, vm.uiState.value.messages.size)
            vm.onMessageShown(
                vm.uiState.value.messages
                    .single()
                    .id,
            )
            runCurrent()
            assertTrue(
                vm.uiState.value.messages
                    .isEmpty(),
            )
        }

    @Test fun transportAndSeekUseControllerAndCurrentBounds() =
        runTest {
            controller.state.nowPlaying.value = sampleNow()
            val vm = player()
            vm.skipBack()
            vm.skipForward()
            vm.previous()
            vm.next()
            vm.seekTo(-5)
            vm.seekTo(90_000)
            assertEquals(
                listOf<Call>(
                    Call.Transport(Action.BACK),
                    Call.Transport(Action.FORWARD),
                    Call.Transport(Action.PREVIOUS),
                    Call.Transport(Action.NEXT),
                    Call.Transport(Action.SEEK, 0),
                    Call.Transport(Action.SEEK, 60_000),
                ),
                controller.calls,
            )
            controller.state.nowPlaying.value = sampleNow().copy(hasNext = false)
            vm.next()
            assertEquals(6, controller.calls.size)
        }

    @Test fun dismissRechecksPausedStateAfterGesture() =
        runTest {
            controller.state.nowPlaying.value = sampleNow(playing = true)
            val vm = player()
            vm.dismissPaused()
            controller.state.nowPlaying.value = sampleNow().copy(playWhenReady = true)
            vm.dismissPaused()
            runCurrent()
            assertTrue(controller.calls.isEmpty())
            controller.state.nowPlaying.value = sampleNow()
            vm.dismissPaused()
            runCurrent()
            assertEquals(listOf<Call>(Call.Transport(Action.DISMISS)), controller.calls)
        }

    @Test fun explicitStopPausesThenDismisses() =
        runTest {
            val vm = player()
            vm.stopAndClose()
            runCurrent()
            assertEquals(listOf<Call>(Call.Transport(Action.PAUSE), Call.Transport(Action.DISMISS)), controller.calls)
        }

    @Test fun writesSpeedAndSilenceToChosenScopeWithoutDirectSettingsWrites() =
        runTest {
            val vm = player()
            controller.scopeResult = ScopeWriteResult.APPLIED
            vm.setSpeed(9f, SettingScope.GLOBAL)
            vm.setSkipSilence(true, SettingScope.PODCAST)
            runCurrent()
            assertEquals(
                listOf<Call>(Call.Speed(3f, SettingScope.GLOBAL), Call.Silence(true, SettingScope.PODCAST)),
                controller.calls,
            )
            assertTrue(settings.calls.isEmpty())
            assertEquals(ScopeWriteResult.APPLIED, vm.scopeWriteResult.value)
        }

    @Test fun failedOrStaleScopeWritesAreReported() =
        runTest {
            val vm = player()
            controller.scopeResult = ScopeWriteResult.NO_CONTEXT_GROUP
            vm.setSpeed(1.2f, SettingScope.GROUP)
            runCurrent()
            assertEquals(ScopeWriteResult.NO_CONTEXT_GROUP, vm.scopeWriteResult.value)
            controller.scopeResult = ScopeWriteResult.FAILED
            vm.setSkipSilence(true, SettingScope.PODCAST)
            runCurrent()
            assertEquals(2, vm.uiState.value.messages.size)
        }

    @Test fun presetsValidateAndStorageFailuresRemainVisible() =
        runTest {
            val vm = player()
            vm.setPresets("0.1")
            runCurrent()
            assertTrue(settings.calls.isEmpty())
            vm.setPresets("1.0,1.5")
            runCurrent()
            assertEquals("1.0,1.5", vm.uiState.value.presets)
            settings.failNextSet = SettingsError.WriteFailed
            vm.setPresets("1.0,2.0")
            runCurrent()
            assertEquals("1.0,1.5", vm.uiState.value.presets)
            assertEquals(2, vm.uiState.value.messages.size)
        }

    @Test fun meteredConsentSurvivesStateCollectionAndDeclineDoesNotPlay() =
        runTest {
            controller.state.pendingMeteredConsent.value = PlayResult.NeedsMeteredConsent(8)
            val vm = player()
            assertEquals(
                8L,
                vm.uiState.value.consent
                    ?.episodeId,
            )
            vm.declineMobileData()
            runCurrent()
            assertNull(vm.uiState.value.consent)
            assertTrue(controller.calls.isEmpty())
            controller.state.pendingMeteredConsent.value = null
            runCurrent()
            controller.state.pendingMeteredConsent.value = PlayResult.NeedsMeteredConsent(9)
            runCurrent()
            assertEquals(
                9L,
                vm.uiState.value.consent
                    ?.episodeId,
            )
            controller.playResult = PlayResult.Started
            vm.continueOnMobileData()
            runCurrent()
            assertEquals(listOf<Call>(Call.Transport(Action.CONSENT), Call.Transport(Action.PLAY)), controller.calls)
        }

    @Test fun returnedConsentCanBeAcceptedBeforeThereIsANowPlaying() =
        runTest {
            val vm = player()
            controller.playResult = PlayResult.NeedsMeteredConsent(5)
            vm.togglePlayPause()
            runCurrent()
            assertEquals(
                5L,
                vm.uiState.value.consent
                    ?.episodeId,
            )
            assertNull(vm.uiState.value.now)
        }

    @Test fun markPlayedAndSkipWritesOnlyTheEpisode() =
        runTest {
            val episodes = FakeEpisodeRepository()
            val vm = PlayerEpisodeViewModel(episodes)
            vm.markPlayed(1)
            advanceUntilIdle()
            assertEquals(listOf("setPlayed([1], true)"), episodes.calls)
            assertFalse(vm.writeFailed.value)
            assertTrue(controller.calls.isEmpty())
        }

    @Test fun markPlayedFailureIsExplicitAndCancellationPropagates() =
        runTest {
            val episodes = FakeEpisodeRepository()
            val vm = PlayerEpisodeViewModel(episodes)
            episodes.writeError = IllegalStateException("disk")
            vm.markPlayed(1)
            advanceUntilIdle()
            assertTrue(vm.writeFailed.value)
            vm.acknowledgeFailure()
            episodes.writeError = CancellationException("left")
            vm.markPlayed(1)
            advanceUntilIdle()
            assertFalse(vm.writeFailed.value)
            assertTrue(episodes.calls.isEmpty())
        }

    @Test fun refusalsPreserveActionableReasons() =
        runTest {
            val vm = player()
            val cases =
                listOf(
                    UnplayableReason.NoMedia to Res.string.player_no_media,
                    UnplayableReason.AuthRequired to Res.string.player_auth_required,
                    UnplayableReason.Http(410) to Res.string.player_audio_gone,
                )
            for ((reason, text) in cases) {
                controller.playResult = PlayResult.NotPlayable(1, reason)
                vm.togglePlayPause()
                runCurrent()
                assertEquals(
                    UiText.Res(text),
                    vm.uiState.value.messages
                        .last()
                        .text,
                )
            }
        }
}
