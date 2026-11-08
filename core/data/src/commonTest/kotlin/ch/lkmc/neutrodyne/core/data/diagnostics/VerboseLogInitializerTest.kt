// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.diagnostics

import ch.lkmc.neutrodyne.core.common.LogLevel
import ch.lkmc.neutrodyne.core.model.settings.DiagnosticsSettingKeys
import ch.lkmc.neutrodyne.core.testing.FakeLevelMutableLogSink
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 09's "Detailed log for 24 hours": every `LevelMutableLogSink` must hold DEBUG for the
 * window and INFO again after it — on both platforms from this shared observer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VerboseLogInitializerTest {
    @Test
    fun sinksFollowTheVerboseWindowAndReturnToInfo() =
        runTest {
            val settings = FakeSettingsRepository()
            val clock = TestClock.from(testScheduler)
            val ring = FakeLevelMutableLogSink()
            val file = FakeLevelMutableLogSink()
            VerboseLogInitializer(settings, setOf(ring, file), clock, backgroundScope).run()
            runCurrent()
            assertEquals(LogLevel.INFO, ring.minLevel)
            assertEquals(LogLevel.INFO, file.minLevel)

            settings.set(DiagnosticsSettingKeys.VERBOSE_LOG_UNTIL, clock.now() + 90_000)
            runCurrent()
            assertEquals(LogLevel.DEBUG, ring.minLevel)
            assertEquals(LogLevel.DEBUG, file.minLevel)

            // Mid-window the ticker re-asserts DEBUG; past the deadline it flips to INFO.
            advanceTimeBy(61_000)
            assertEquals(LogLevel.DEBUG, ring.minLevel)
            advanceTimeBy(60_000)
            assertEquals(LogLevel.INFO, ring.minLevel)
            assertEquals(LogLevel.INFO, file.minLevel)
        }

    @Test
    fun disablingTheSwitchDropsToInfoImmediately() =
        runTest {
            val settings = FakeSettingsRepository()
            val clock = TestClock.from(testScheduler)
            val sink = FakeLevelMutableLogSink()
            settings.set(DiagnosticsSettingKeys.VERBOSE_LOG_UNTIL, clock.now() + 86_400_000)
            VerboseLogInitializer(settings, setOf(sink), clock, backgroundScope).run()
            runCurrent()
            assertEquals(LogLevel.DEBUG, sink.minLevel)

            settings.set(DiagnosticsSettingKeys.VERBOSE_LOG_UNTIL, 0)
            runCurrent()
            assertEquals(LogLevel.INFO, sink.minLevel)
        }
}
