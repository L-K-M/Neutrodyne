// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.Effective
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeEpisodeRepository
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController.Action
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController.Call
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.ui.UiText
import ch.lkmc.neutrodyne.feature.player.resources.Res
import ch.lkmc.neutrodyne.feature.player.resources.player_audio_gone
import ch.lkmc.neutrodyne.feature.player.resources.player_auth_required
import ch.lkmc.neutrodyne.feature.player.resources.player_no_media
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.PlaybackEvent
import ch.lkmc.neutrodyne.playback.api.ScopeWriteResult
import ch.lkmc.neutrodyne.playback.api.UnplayableReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private val settings = FakeSettingsRepository()
    private val clock = TestClock(elapsedMs = 5_000)

    private fun TestScope.player(): PlayerViewModel {
        val vm = PlayerViewModel(controller, controller.state, settings, clock)
        backgroundScope.launch { vm.uiState.collect {} }
        backgroundScope.launch { vm.position.collect {} }
        runCurrent()
        return vm
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
