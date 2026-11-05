// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.window

import ch.lkmc.neutrodyne.core.model.settings.CloseBehaviour
import ch.lkmc.neutrodyne.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 11's `Hidden --> Quitting` transition on virtual time: a hidden window quits after 10
 * continuous idle minutes under `QUIT_WHEN_IDLE`; a busy edge, an unhide or `KEEP_RUNNING`
 * cancels the wait.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HiddenIdleQuitTest {
    private val hidden = MutableStateFlow(true)
    private val behaviour = MutableStateFlow(CloseBehaviour.QUIT_WHEN_IDLE)
    private val busy = MutableStateFlow(false)
    private var quits = 0

    @Test
    fun `ten continuous idle minutes quit the hidden window`() =
        runTest {
            val clock = TestClock.from(testScheduler)
            val job = launch { hiddenIdleQuit(clock, hidden, behaviour, busy) { quits++ } }

            advanceTimeBy(HIDDEN_IDLE_QUIT_MS - 1)
            assertThat(quits).isEqualTo(0)
            advanceTimeBy(2)
            assertThat(quits).isEqualTo(1)
            job.cancel()
        }

    @Test
    fun `a busy edge restarts the ten minutes`() =
        runTest {
            val clock = TestClock.from(testScheduler)
            val job = launch { hiddenIdleQuit(clock, hidden, behaviour, busy) { quits++ } }

            advanceTimeBy(HIDDEN_IDLE_QUIT_MS / 2)
            busy.value = true
            runCurrent() // let the collector see the edge — same-instant writes conflate
            busy.value = false
            runCurrent()
            advanceTimeBy(HIDDEN_IDLE_QUIT_MS - 1)
            assertThat(quits).isEqualTo(0)
            advanceTimeBy(2)
            assertThat(quits).isEqualTo(1)
            job.cancel()
        }

    @Test
    fun `an unhide cancels the countdown`() =
        runTest {
            val clock = TestClock.from(testScheduler)
            val job = launch { hiddenIdleQuit(clock, hidden, behaviour, busy) { quits++ } }

            advanceTimeBy(HIDDEN_IDLE_QUIT_MS / 2)
            hidden.value = false
            advanceTimeBy(HIDDEN_IDLE_QUIT_MS * 2)
            assertThat(quits).isEqualTo(0)
            job.cancel()
        }

    @Test
    fun `keep-running never arms the countdown`() =
        runTest {
            val clock = TestClock.from(testScheduler)
            behaviour.value = CloseBehaviour.KEEP_RUNNING
            val job = launch { hiddenIdleQuit(clock, hidden, behaviour, busy) { quits++ } }

            advanceTimeBy(HIDDEN_IDLE_QUIT_MS * 3)
            assertThat(quits).isEqualTo(0)
            job.cancel()
        }

    @Test
    fun `hidden while busy starts the clock at the idle edge`() =
        runTest {
            val clock = TestClock.from(testScheduler)
            busy.value = true
            val job = launch { hiddenIdleQuit(clock, hidden, behaviour, busy) { quits++ } }

            advanceTimeBy(HIDDEN_IDLE_QUIT_MS * 2)
            assertThat(quits).isEqualTo(0)

            busy.value = false
            advanceTimeBy(HIDDEN_IDLE_QUIT_MS + 1)
            assertThat(quits).isEqualTo(1)
            job.cancel()
        }
}
