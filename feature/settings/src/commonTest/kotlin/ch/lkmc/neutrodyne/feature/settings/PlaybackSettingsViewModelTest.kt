// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.core.model.settings.StreamOnMetered
import ch.lkmc.neutrodyne.core.testing.FakePlaybackMaintenance
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.MainDispatcherTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSettingsViewModelTest : MainDispatcherTest() {
    private val settings = FakeSettingsRepository()
    private val maintenance = FakePlaybackMaintenance()

    private fun TestScope.page(): PlaybackSettingsViewModel {
        val vm = PlaybackSettingsViewModel(settings, maintenance)
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    @Test fun writesAllEightM4KeysAndObservesThem() =
        runTest {
            val vm = page()
            vm.setSpeed(1.23f)
            vm.setSkipSilence(true)
            vm.setSpeedPresets("2.0,1.0")
            vm.setSkipBackMs(20_000)
            vm.setSkipForwardMs(45_000)
            vm.setPauseForNavigation(false)
            vm.setStreamOnMetered(StreamOnMetered.NEVER)
            vm.setStreamCacheMb(250)
            runCurrent()
            val state = vm.uiState.value
            assertEquals(1.25f, state.speed)
            assertTrue(state.skipSilence)
            assertEquals("1.0,2.0", state.speedPresets)
            assertEquals(20_000L, state.skipBackMs)
            assertEquals(45_000L, state.skipForwardMs)
            assertFalse(state.pauseForNavigation)
            assertEquals(StreamOnMetered.NEVER, state.streamOnMetered)
            assertEquals(250, state.streamCacheMb)
            assertEquals(8, settings.calls.size)
        }

    @Test fun rejectedAndFailedWritesDoNotChangeDisplayedValues() =
        runTest {
            val vm = page()
            settings.failNextSet = SettingsError.WriteFailed
            vm.setSkipSilence(true)
            runCurrent()
            vm.setSkipBackMs(123)
            runCurrent()
            assertFalse(vm.uiState.value.skipSilence)
            assertEquals(10_000L, vm.uiState.value.skipBackMs)
            assertEquals(2, vm.uiState.value.messages.size)
        }

    @Test fun presetValidationNeverWritesInvalidInput() =
        runTest {
            val vm = page()
            listOf("1.0", "NaN,1.0", "0.1,1.0", "1.03,2.0").forEach(vm::setSpeedPresets)
            vm.setSpeed(Float.NaN)
            runCurrent()
            assertTrue(settings.calls.isEmpty())
            assertEquals(PlaybackSettingKeys.DEFAULT_SPEED_PRESETS, vm.uiState.value.speedPresets)
        }

    @Test fun cacheUsageAndClearAreReportedFromMaintenance() =
        runTest {
            maintenance.bytes = 123_000_000
            val vm = page()
            assertEquals(123_000_000L, vm.uiState.value.cacheBytes)
            vm.clearStreamingCache()
            vm.clearStreamingCache()
            runCurrent()
            assertEquals(1, maintenance.clearCalls)
            assertEquals(0L, vm.uiState.value.cacheBytes)
            assertFalse(vm.uiState.value.cacheBusy)
            assertEquals(1, vm.uiState.value.messages.size)
        }

    @Test fun cacheReadAndClearFailuresAreExplicit() =
        runTest {
            maintenance.failure = IllegalStateException("disk")
            val vm = page()
            assertNull(vm.uiState.value.cacheBytes)
            assertEquals(1, vm.uiState.value.messages.size)
            vm.clearStreamingCache()
            runCurrent()
            assertEquals(2, vm.uiState.value.messages.size)
            assertFalse(vm.uiState.value.cacheBusy)
        }

    @Test fun cancelledClearDoesNotReportSuccessAndReleasesBusyFlag() =
        runTest {
            val vm = page()
            maintenance.failure = CancellationException("left")
            vm.clearStreamingCache()
            runCurrent()
            assertTrue(
                vm.uiState.value.messages
                    .isEmpty(),
            )
            assertFalse(vm.uiState.value.cacheBusy)
        }
}
