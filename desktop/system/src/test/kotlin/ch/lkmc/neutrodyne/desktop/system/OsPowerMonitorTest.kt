// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.PowerEvent
import ch.lkmc.neutrodyne.core.model.DesktopOs
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.native.NativeSessionEvent
import ch.lkmc.neutrodyne.playback.native.NativeSessionEventKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * [OsPowerMonitor]: every consumer sees every event (the runner, the network monitor and the
 * power policy all collect the same hot stream), the native shim's suspend/resume events map
 * onto [PowerEvent], a source failure is reported through `status`, and every unresolvable
 * source degrades to the documented no-notice binding instead of throwing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OsPowerMonitorTest {
    @Test
    fun everyCollectorSeesEveryEvent() =
        runTest {
            val source = MutableSharedFlow<PowerEvent>()
            val monitor = OsPowerMonitor.fromEvents(backgroundScope, source.asSharedFlow())
            // The bridge collector must be subscribed before the first emission — a
            // SharedFlow with no subscribers drops emissions. runCurrent (not
            // advanceUntilIdle: it also drains the virtual-time queue, on which the
            // bridge's upstream subscription parks here) starts exactly that launch.
            runCurrent()

            val seen = mutableListOf<PowerEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.events.toList(seen) }
            val second = mutableListOf<PowerEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.events.toList(second) }

            source.emit(PowerEvent.Suspending)
            source.emit(PowerEvent.Resumed)
            advanceUntilIdle()

            assertThat(seen).containsExactly(PowerEvent.Suspending, PowerEvent.Resumed).inOrder()
            assertThat(second).containsExactly(PowerEvent.Suspending, PowerEvent.Resumed).inOrder()
            assertThat(monitor.status.value).isNull()
        }

    @Test
    fun nativeSuspendAndResumeEventsMapToPowerEvents() =
        runTest {
            val source = MutableSharedFlow<NativeSessionEvent>()
            val monitor = OsPowerMonitor.fromEvents(backgroundScope, source.powerEvents())
            runCurrent()
            val seen = mutableListOf<PowerEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { monitor.events.toList(seen) }

            source.emit(NativeSessionEvent(NativeSessionEventKind.SUSPEND, 0))
            source.emit(NativeSessionEvent(NativeSessionEventKind.REMOVED, 0))
            source.emit(NativeSessionEvent(NativeSessionEventKind.RESUME, 0))

            advanceUntilIdle()
            assertThat(seen).containsExactly(PowerEvent.Suspending, PowerEvent.Resumed).inOrder()
        }

    @Test
    fun aFailingSourceIsReportedThroughStatus() =
        runTest {
            val monitor =
                OsPowerMonitor.fromEvents(
                    backgroundScope,
                    flow<PowerEvent> { throw IllegalStateException("bus vanished") },
                )

            withTimeout(5_000) {
                while (monitor.status.value == null) delay(1)
            }
            assertThat(monitor.status.value).contains("power notices ended")
        }

    @Test
    fun aFailingSourceCloseIsSwallowed() {
        val scope = CoroutineScope(SupervisorJob())
        val monitor =
            OsPowerMonitor.fromEvents(
                scope,
                emptyFlow(),
                source = AutoCloseable { throw IllegalStateException("bus teardown refused") },
            )

        monitor.close() // must not throw

        scope.cancel()
    }

    @Test
    fun createDegradesToNoNoticesWhenTheNativeSessionIsUnavailable() =
        runTest {
            val monitor = OsPowerMonitor.create(DesktopOs.WINDOWS, backgroundScope) { null }

            assertThat(monitor.status.value).contains("no native media session")
            assertThat(monitor.status.value).contains("wall-clock wake detection")
        }

    @Test
    fun createDegradesToNoNoticesWhenTheNativeSessionFactoryThrows() =
        runTest {
            val monitor =
                OsPowerMonitor.create(DesktopOs.MACOS, backgroundScope) {
                    throw IllegalStateException("ndmedia is missing")
                }

            assertThat(monitor.status.value).contains("native media session failed")
        }

    @Test
    fun createDegradesToNoNoticesWhenTheSessionIsNotNative() =
        runTest {
            val monitor = OsPowerMonitor.create(DesktopOs.WINDOWS, backgroundScope) { FakeSession }

            assertThat(monitor.status.value).contains("no native events")
        }

    @Test
    fun createNeverThrowsOnLinuxWhateverTheBusSays() =
        runTest {
            // Whatever the environment's system bus answers, construction succeeds; the
            // status records which of the two outcomes happened (logind live, or the
            // documented no-notice fallback).
            val monitor = OsPowerMonitor.create(DesktopOs.LINUX, backgroundScope) { FakeSession }

            val status = monitor.status.value
            assertThat(status == null || status.contains("logind system bus unavailable")).isTrue()
        }

    private object FakeSession : SystemMediaSession {
        override fun publish(
            nowPlaying: NowPlaying?,
            skipBackMs: Long,
            skipForwardMs: Long,
            speedPresets: List<Float>,
            artworkPath: String?,
        ) = Unit

        override val commands = emptyFlow<RemoteCommand>()

        override fun close() = Unit
    }
}
