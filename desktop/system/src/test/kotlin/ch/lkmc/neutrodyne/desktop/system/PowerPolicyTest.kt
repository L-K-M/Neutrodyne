// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.PowerEvent
import ch.lkmc.neutrodyne.core.common.PowerMonitor
import ch.lkmc.neutrodyne.core.testing.FakePlaybackController
import ch.lkmc.neutrodyne.core.testing.FakePlaybackStateSource
import ch.lkmc.neutrodyne.core.testing.testNowPlaying
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 11 Power's policy over fakes (the `PowerPolicyTest` row of 11 Testing): `Suspending`
 * pauses and settles on the paused publication — the one the real controller emits after its
 * event position save — or gives up at the documented bound; `Resumed` starts nothing; and
 * the idle-sleep inhibitor is held exactly while playing (MD2 AC2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PowerPolicyTest {
    private val controller = FakePlaybackController()
    private val state = FakePlaybackStateSource()
    private val inhibitor = RecordingInhibitor()
    private val power = MutableSharedFlow<PowerEvent>(extraBufferCapacity = 8)

    private fun policy(scope: kotlinx.coroutines.CoroutineScope) =
        PowerPolicy(
            scope = scope,
            power = power.asPowerMonitor(),
            controller = controller,
            state = state,
            inhibitor = inhibitor,
        )

    @Test
    fun suspendingPausesAndWaitsForThePauseToLand() =
        runTest {
            state.nowPlaying.value = testNowPlaying()
            val policy = policy(backgroundScope)
            policy.start()
            runCurrent()

            power.emit(PowerEvent.Suspending)
            runCurrent()

            // The pause was dispatched, and the handler stays inside its settle wait until
            // the paused publication (which follows the position save in the real
            // controller) arrives — that is the ordering MD2 AC2 asks for.
            assertThat(controller.pauseCalls).isEqualTo(1)
            assertThat(policy.suspendSettled.value).isNull()

            state.nowPlaying.value = testNowPlaying().copy(isPlaying = false)
            runCurrent()

            assertThat(policy.suspendSettled.value).isTrue()
        }

    @Test
    fun aSuspendThatOutlivesTheBoundGivesUpToThePeriodicSave() =
        runTest {
            state.nowPlaying.value = testNowPlaying()
            val policy = policy(backgroundScope)
            policy.start()
            runCurrent()

            power.emit(PowerEvent.Suspending)
            advanceTimeBy(2_500)
            runCurrent()

            // Still "playing" (the freeze won): the settle reports its bound, not a lie.
            assertThat(policy.suspendSettled.value).isFalse()
            assertThat(controller.pauseCalls).isEqualTo(1)
        }

    @Test
    fun suspendingWithNothingLoadedSettlesAtOnce() =
        runTest {
            val policy = policy(backgroundScope)
            policy.start()
            runCurrent()

            power.emit(PowerEvent.Suspending)
            runCurrent()

            assertThat(controller.pauseCalls).isEqualTo(1)
            assertThat(policy.suspendSettled.value).isTrue()
        }

    @Test
    fun resumedStartsNothing() =
        runTest {
            state.nowPlaying.value = testNowPlaying().copy(isPlaying = false)
            policy(backgroundScope).start()
            runCurrent()

            power.emit(PowerEvent.Resumed)
            runCurrent()

            assertThat(controller.calls).isEmpty()
        }

    @Test
    fun theInhibitorIsHeldExactlyWhilePlaying() =
        runTest {
            policy(backgroundScope).start()
            runCurrent()
            assertThat(inhibitor.holds).isEmpty()

            state.nowPlaying.value = testNowPlaying()
            runCurrent()
            assertThat(inhibitor.holds).containsExactly("Neutrodyne is playing")

            // A pause, an end and an error all pass through isPlaying = false.
            state.nowPlaying.value = testNowPlaying().copy(isPlaying = false)
            runCurrent()
            assertThat(inhibitor.holds).isEmpty()

            // Playing again re-acquires without an intervening release.
            state.nowPlaying.value = testNowPlaying()
            runCurrent()
            assertThat(inhibitor.holds).containsExactly("Neutrodyne is playing")
        }

    @Test
    fun clearingTheItemReleasesTheInhibitorAndCloseReleasesToo() =
        runTest {
            state.nowPlaying.value = testNowPlaying()
            val policy = policy(backgroundScope)
            policy.start()
            runCurrent()
            assertThat(inhibitor.holds).isNotEmpty()

            state.nowPlaying.value = null
            runCurrent()
            assertThat(inhibitor.holds).isEmpty()

            state.nowPlaying.value = testNowPlaying()
            runCurrent()
            policy.close()

            assertThat(inhibitor.holds).isEmpty()
        }

    private fun MutableSharedFlow<PowerEvent>.asPowerMonitor(): PowerMonitor {
        val flow = this
        return object : PowerMonitor {
            override val events: Flow<PowerEvent> = flow
        }
    }

    private class RecordingInhibitor : IdleSleepInhibitor {
        val holds = mutableListOf<String>()

        override fun acquire(reason: String) {
            holds += reason
        }

        override fun release() {
            holds.clear()
        }
    }
}
