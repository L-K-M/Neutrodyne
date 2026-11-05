// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.model.settings.HardwareButtons
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.FakePlaybackStateSource
import ch.lkmc.neutrodyne.core.testing.testNowPlaying
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [MediaSessionCoordinator]: the shared state reaches the session as publishes, every remote
 * command maps to the shared controller's action with `playback.hardware_buttons` read live,
 * and seeks are rate-limited to 10 per second (11 Remote commands).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaSessionCoordinatorTest {
    private val controller = FakePlaybackController()
    private val state = FakePlaybackStateSource()
    private val session = FakeSystemMediaSession()
    private val hardware = MutableStateFlow(HardwareButtons.EPISODE)

    private fun TestScope.coordinator(
        nanos: () -> Long = { 0L },
        setRate: (suspend (Float) -> Unit)? = null,
    ) = MediaSessionCoordinator(
        scope = backgroundScope,
        state = state,
        controller = controller,
        session = session,
        skipBackMs = MutableStateFlow(10_000L),
        skipForwardMs = MutableStateFlow(30_000L),
        speedPresets = MutableStateFlow(listOf(1f, 2f)),
        hardwareButtons = hardware,
        setExternalRate = setRate,
        nanos = nanos,
    )

    @Test
    fun theSharedStateReachesTheSessionAsPublishes() =
        runTest {
            coordinator().start()
            runCurrent()

            assertThat(session.published.single().nowPlaying).isNull()

            state.nowPlaying.value = testNowPlaying()
            runCurrent()

            val latest = session.published.last()
            assertThat(latest.nowPlaying?.title).isEqualTo(testNowPlaying().title)
            assertThat(latest.skipBackMs).isEqualTo(10_000L)
            assertThat(latest.skipForwardMs).isEqualTo(30_000L)
            assertThat(latest.speedPresets).containsExactly(1f, 2f).inOrder()
        }

    @Test
    fun remoteCommandsMapToTheSharedController() =
        runTest {
            coordinator().start()
            runCurrent()

            session.emit(RemoteCommand.Play)
            session.emit(RemoteCommand.Pause)
            session.emit(RemoteCommand.Toggle)
            session.emit(RemoteCommand.Next)
            session.emit(RemoteCommand.Previous)
            session.emit(RemoteCommand.SkipForward)
            session.emit(RemoteCommand.SkipBack)
            session.emit(RemoteCommand.SeekTo(42_000))
            runCurrent()

            val actions = controller.calls.filterIsInstance<FakePlaybackController.Call.Transport>().map { it.action }
            assertThat(actions).containsExactly(
                FakePlaybackController.Action.PLAY,
                FakePlaybackController.Action.PAUSE,
                FakePlaybackController.Action.TOGGLE,
                FakePlaybackController.Action.NEXT,
                FakePlaybackController.Action.PREVIOUS,
                FakePlaybackController.Action.FORWARD,
                FakePlaybackController.Action.BACK,
                FakePlaybackController.Action.SEEK,
            ).inOrder()
            assertThat(
                controller.calls
                    .filterIsInstance<FakePlaybackController.Call.Transport>()
                    .single { it.action == FakePlaybackController.Action.SEEK }
                    .positionMs,
            ).isEqualTo(42_000)
        }

    @Test
    fun nextAndPreviousFollowTheHardwareButtonsSettingLive() =
        runTest {
            coordinator().start()
            runCurrent()

            hardware.value = HardwareButtons.SKIP
            session.emit(RemoteCommand.Next)
            session.emit(RemoteCommand.Previous)
            runCurrent()

            hardware.value = HardwareButtons.EPISODE
            session.emit(RemoteCommand.Next)
            session.emit(RemoteCommand.Previous)
            runCurrent()

            val actions = controller.calls.filterIsInstance<FakePlaybackController.Call.Transport>().map { it.action }
            assertThat(actions).containsExactly(
                FakePlaybackController.Action.FORWARD,
                FakePlaybackController.Action.BACK,
                FakePlaybackController.Action.NEXT,
                FakePlaybackController.Action.PREVIOUS,
            ).inOrder()
        }

    @Test
    fun seeksAreRateLimitedToTenPerSecond() =
        runTest {
            var now = 0L
            coordinator(nanos = { now }).start()
            runCurrent()

            // The gate reads the clock when the collector dispatches, so each emit is
            // processed before the fake clock moves on.
            now = 0L
            session.emit(RemoteCommand.SeekTo(1_000))
            runCurrent()
            now = 50_000_000L
            session.emit(RemoteCommand.SeekTo(2_000)) // 50 ms later: dropped
            runCurrent()
            now = 101_000_000L
            session.emit(RemoteCommand.SeekTo(3_000)) // 101 ms after the first: allowed
            runCurrent()

            val seeks =
                controller.calls
                    .filterIsInstance<FakePlaybackController.Call.Transport>()
                    .filter { it.action == FakePlaybackController.Action.SEEK }
            assertThat(seeks.map { it.positionMs }).containsExactly(1_000L, 3_000L).inOrder()
        }

    @Test
    fun anExternalRateReachesTheSeamNormalized() =
        runTest {
            val rates = mutableListOf<Float>()
            coordinator(setRate = { rates += it }).start()
            runCurrent()

            session.emit(RemoteCommand.SetRate(2.7f))
            session.emit(RemoteCommand.SetRate(9f))
            runCurrent()

            assertThat(rates).containsExactly(2.7f, 3.0f).inOrder()
        }

    private class FakeSystemMediaSession : SystemMediaSession {
        data class Published(
            val nowPlaying: NowPlaying?,
            val skipBackMs: Long,
            val skipForwardMs: Long,
            val speedPresets: List<Float>,
        )

        val published = mutableListOf<Published>()
        private val sink = MutableSharedFlow<RemoteCommand>(extraBufferCapacity = 64)

        fun emit(command: RemoteCommand) {
            check(sink.tryEmit(command)) { "command buffer is full" }
        }

        override fun publish(
            nowPlaying: NowPlaying?,
            skipBackMs: Long,
            skipForwardMs: Long,
            speedPresets: List<Float>,
        ) {
            published += Published(nowPlaying, skipBackMs, skipForwardMs, speedPresets)
        }

        override val commands = sink.asSharedFlow()

        override fun close() = Unit
    }
}
