// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import androidx.lifecycle.ViewModelStore
import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.domain.QueueRepository
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.UpNextItem
import ch.lkmc.neutrodyne.core.model.settings.AppearanceSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.PlayerTimeDisplay
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.testing.FakePlatformInfo
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController.Action
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController.Call
import ch.lkmc.neutrodyne.core.testing.FakeQueueRepository
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.testEpisodeRow
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.PlaybackEvent
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import ch.lkmc.neutrodyne.playback.api.ScopeWriteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest : MainDispatcherTest() {
    private val controller = FakePlaybackController()
    private val settings = FakeSettingsRepository()
    private val queue = FakeQueueRepository()
    private val store = ViewModelStore()

    private fun TestScope.model(
        repository: SettingsRepository = settings,
        queueRepository: QueueRepository = queue,
    ): PlayerViewModel {
        val vm =
            PlayerViewModel(
                controller,
                controller.state,
                repository,
                TestClock(),
                FakePlatformInfo(PlatformKind.ANDROID),
                queueRepository,
            )
        store.put("player", vm)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.position.collect() }
        return vm
    }

    @AfterTest fun close() = store.clear()

    @Test fun creationDoesNotStartOrInheritAnotherScreensConsent() =
        runTest {
            controller.state.pendingMeteredConsent.value = PlayResult.NeedsMeteredConsent(1)
            val vm = model()
            advanceUntilIdle()
            assertNull(vm.uiState.value.consent)
            assertTrue(controller.calls.isEmpty())
        }

    @Test fun bufferingPausesAndPausedCurrentResumesThroughTheGate() =
        runTest {
            controller.state.nowPlaying.value = sampleNow().copy(phase = PlayerPhase.BUFFERING, playWhenReady = true)
            controller.playResult = PlayResult.Started
            val vm = model()
            vm.togglePlayPause()
            controller.state.nowPlaying.value = sampleNow()
            vm.togglePlayPause()
            advanceUntilIdle()
            assertEquals(listOf<Call>(Call.Transport(Action.PAUSE), Call.Transport(Action.PLAY)), controller.calls)
        }

    @Test fun staleTicksAndStaleSeekCannotTargetTheNewCurrent() =
        runTest {
            controller.state.nowPlaying.value = sampleNow()
            val vm = model()
            runCurrent()
            controller.state.positionTicks.emit(samplePosition().copy(positionMs = 20_000))
            runCurrent()
            assertEquals(20_000L, vm.position.value?.positionMs)
            controller.state.nowPlaying.value = sampleNow(2)
            runCurrent()
            controller.state.positionTicks.emit(samplePosition().copy(positionMs = 30_000))
            runCurrent()
            assertEquals(2L, vm.position.value?.episodeId)
            assertEquals(12_000L, vm.position.value?.positionMs)
            vm.seekTo(5_000, 1)
            vm.seekTo(90_000, 2)
            assertEquals(listOf<Call>(Call.Transport(Action.SEEK, 60_000)), controller.calls)
        }

    @Test fun queueCountMatchesTheListAndExcludesCurrent() =
        runTest {
            controller.state.nowPlaying.value = sampleNow()
            queue.upNext.value = (1L..3L).map { UpNextItem(it, testEpisodeRow(it)) }
            val vm = model()
            advanceUntilIdle()
            assertEquals(2, vm.uiState.value.upNextCount)
            assertTrue(queue.requestedWindows.isEmpty())
        }

    @Test fun queueReadFailuresRecoverWithoutFreezingOtherStateOrFloodingMessages() =
        runTest {
            controller.state.nowPlaying.value = sampleNow()
            queue.upNext.value = (1L..3L).map { UpNextItem(it, testEpisodeRow(it)) }
            var subscriptions = 0
            val recoveringQueue =
                object : QueueRepository by queue {
                    override fun observeUpNext(): Flow<List<UpNextItem>> =
                        flow {
                            if (++subscriptions <= 2) error("Queue read failed")
                            emitAll(queue.observeUpNext())
                        }
                }
            val vm = model(queueRepository = recoveringQueue)
            runCurrent()
            assertEquals(1, subscriptions)
            assertEquals(
                1L,
                vm.uiState.value.now
                    ?.episodeId,
            )
            assertEquals(1, vm.uiState.value.messages.size)

            controller.state.nowPlaying.value = sampleNow(2)
            runCurrent()
            assertEquals(
                2L,
                vm.uiState.value.now
                    ?.episodeId,
            )
            advanceUntilIdle()
            assertEquals(2, vm.uiState.value.upNextCount)
            assertEquals(3, subscriptions)
            assertEquals(1, vm.uiState.value.messages.size)

            queue.upNext.value = (3L..6L).map { UpNextItem(it, testEpisodeRow(it)) }
            runCurrent()
            assertEquals(4, vm.uiState.value.upNextCount)
        }

    @Test fun resumedCurrentCannotBeDismissedByAnEarlierSwipe() =
        runTest {
            controller.state.nowPlaying.value = sampleNow()
            val vm = model()
            vm.dismissPaused()
            controller.state.nowPlaying.value = sampleNow(playing = true)
            advanceUntilIdle()
            assertTrue(controller.calls.isEmpty())
            controller.state.nowPlaying.value = sampleNow()
            vm.dismissPaused()
            advanceUntilIdle()
            assertEquals(listOf<Call>(Call.Transport(Action.DISMISS)), controller.calls)
        }

    @Test fun nextIsDisabledAtTheEndAndOtherTransportsAreExplicit() =
        runTest {
            controller.state.nowPlaying.value = sampleNow().copy(hasNext = false)
            val vm = model()
            vm.next()
            vm.previous()
            vm.skipBack()
            vm.skipForward()
            assertEquals(
                listOf<Call>(
                    Call.Transport(Action.PREVIOUS),
                    Call.Transport(Action.BACK),
                    Call.Transport(Action.FORWARD),
                ),
                controller.calls,
            )
        }

    @Test fun rootRefusalOwnsOneConsentAndStaleContinueDoesNotResumeAgain() =
        runTest {
            val vm = model()
            runCurrent()
            val prompt = PlayResult.NeedsMeteredConsent(4)
            controller.state.pendingMeteredConsent.value = prompt
            controller.state.events.emit(PlaybackEvent.StartRefused(prompt))
            runCurrent()
            assertEquals(
                4L,
                vm.uiState.value.consent
                    ?.episodeId,
            )
            controller.playResult = PlayResult.Started
            vm.continueOnMobileData()
            controller.state.pendingMeteredConsent.value = null
            advanceUntilIdle()
            val accepted = controller.calls.toList()
            assertEquals(listOf(Call.Transport(Action.CONSENT), Call.Transport(Action.PLAY)), accepted)
            vm.continueOnMobileData()
            advanceUntilIdle()
            assertEquals(accepted, controller.calls, "A queued click on the old dialog cannot start playback")
        }

    @Test fun delayedAlwaysCannotGrantAReplacementPromptWithTheSameEpisode() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val repository =
                object : SettingsRepository by settings {
                    override suspend fun <T : Any> set(
                        key: SettingKey<T>,
                        value: T,
                    ): Outcome<Unit, SettingsError> {
                        if (key == PlaybackSettingKeys.STREAM_ON_METERED) release.await()
                        return settings.set(key, value)
                    }
                }
            val vm = model(repository)
            runCurrent()
            val prompt = PlayResult.NeedsMeteredConsent(4)
            controller.state.pendingMeteredConsent.value = prompt
            controller.state.events.emit(PlaybackEvent.StartRefused(prompt))
            runCurrent()
            vm.alwaysOnMobileData()
            runCurrent()
            controller.state.pendingMeteredConsent.value = null
            controller.state.pendingMeteredConsent.value = PlayResult.NeedsMeteredConsent(4)
            runCurrent()
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(StreamOnMetered.ALLOW, settings.get(PlaybackSettingKeys.STREAM_ON_METERED))
            assertTrue(controller.calls.isEmpty())
            assertNull(vm.uiState.value.consent)
        }

    @Test fun failedAlwaysDoesNotGrantOrResume() =
        runTest {
            settings.set(PlaybackSettingKeys.STREAM_ON_METERED, StreamOnMetered.ASK)
            val vm = model()
            runCurrent()
            val prompt = PlayResult.NeedsMeteredConsent(4)
            controller.state.pendingMeteredConsent.value = prompt
            controller.state.events.emit(PlaybackEvent.StartRefused(prompt))
            runCurrent()
            settings.failNextSet = SettingsError.WriteFailed
            vm.alwaysOnMobileData()
            advanceUntilIdle()
            assertTrue(controller.calls.isEmpty())
            assertEquals(StreamOnMetered.ASK, settings.get(PlaybackSettingKeys.STREAM_ON_METERED))
            assertTrue(
                vm.uiState.value.messages
                    .isNotEmpty(),
            )
        }

    @Test fun timeLabelPersistsAndSerializesRapidToggles() =
        runTest {
            val release = CompletableDeferred<Unit>()
            var reads = 0
            val repository =
                object : SettingsRepository by settings {
                    override suspend fun <T : Any> get(key: SettingKey<T>): T {
                        val value = settings.get(key)
                        if (key == AppearanceSettingKeys.PLAYER_TIME && ++reads == 1) release.await()
                        return value
                    }
                }
            val vm = model(repository)
            vm.toggleTimeDisplay()
            vm.toggleTimeDisplay()
            runCurrent()
            assertEquals(1, reads)
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(PlayerTimeDisplay.REMAINING, settings.get(AppearanceSettingKeys.PLAYER_TIME))
            vm.toggleTimeDisplay()
            advanceUntilIdle()
            assertEquals(PlayerTimeDisplay.TOTAL, vm.uiState.value.timeDisplay)
        }

    @Test fun timeReadFailureIsReportedWithoutAnUncaughtCoroutine() =
        runTest {
            val repository =
                object : SettingsRepository by settings {
                    override suspend fun <T : Any> get(key: SettingKey<T>): T = error("Settings read failed")
                }
            val vm = model(repository)
            vm.toggleTimeDisplay()
            advanceUntilIdle()
            assertEquals(PlayerTimeDisplay.REMAINING, vm.uiState.value.timeDisplay)
            assertTrue(
                vm.uiState.value.messages
                    .isNotEmpty(),
            )
        }

    @Test fun failedTimeWriteKeepsTheVisiblePreference() =
        runTest {
            val vm = model()
            settings.failNextSet = SettingsError.WriteFailed
            vm.toggleTimeDisplay()
            advanceUntilIdle()
            assertEquals(PlayerTimeDisplay.REMAINING, vm.uiState.value.timeDisplay)
            assertTrue(
                vm.uiState.value.messages
                    .isNotEmpty(),
            )
        }

    @Test fun invalidPresetsAndScopeFailuresDoNotClaimSuccess() =
        runTest {
            controller.scopeResult = ScopeWriteResult.NO_CONTEXT_GROUP
            val vm = model()
            vm.setPresets("not speeds")
            vm.setSpeed(Float.NaN, SettingScope.GLOBAL)
            vm.setSkipSilence(true, SettingScope.GROUP)
            advanceUntilIdle()
            assertEquals(listOf<Call>(Call.Silence(true, SettingScope.GROUP)), controller.calls)
            assertEquals(ScopeWriteResult.NO_CONTEXT_GROUP, vm.scopeWriteResult.value)
            assertFalse(
                vm.uiState.value.messages
                    .isEmpty(),
            )
            assertTrue(settings.calls.isEmpty())
        }
}
