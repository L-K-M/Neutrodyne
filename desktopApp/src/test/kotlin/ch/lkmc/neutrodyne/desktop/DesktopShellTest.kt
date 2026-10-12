// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop

import ch.lkmc.neutrodyne.core.common.ConnectionPoolEvictor
import ch.lkmc.neutrodyne.core.common.JobLane
import ch.lkmc.neutrodyne.core.common.PowerEvent
import ch.lkmc.neutrodyne.core.common.PowerMonitor
import ch.lkmc.neutrodyne.core.data.refresh.DesktopJobRunner
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Instant

/**
 * 11 Shutdown step 2's wiring into the clean shutdown: `DesktopShell.stopServices` gives
 * `DesktopJobRunner.stop(grace)` its window *before* the scopes hosting the lanes die — an
 * in-flight `NonCancellable` lane finalizer (the engine's buffered flush, 03 Engine run) must
 * commit instead of being cut by process exit. The lane itself is `DesktopJobRunnerTest`'s
 * `FinalizerLane` shape; here the order against the scope cancels is what counts.
 */
class DesktopShellTest {
    @Test
    fun `stopServices awaits the runner's lane finalizer before cancelling the scopes`() =
        runBlocking {
            val events = CopyOnWriteArrayList<String>()
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val laneStarted = CountDownLatch(1)
            val lane =
                object : JobLane {
                    override val name = "refresh"

                    override suspend fun run(now: Instant) {
                        laneStarted.countDown()
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                delay(FINALIZE_MS)
                                events += "finalizer"
                            }
                        }
                    }
                }
            val runner =
                DesktopJobRunner(
                    lanes = setOf(lane),
                    clock = TestClock(),
                    power = NoPower,
                    network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
                    poolEvictor = NoopPoolEvictor,
                    scope = appScope,
                )
            runner.start()
            assertThat(laneStarted.await(LANE_START_S, TimeUnit.SECONDS)).isTrue()

            val serveJob = Job().also { it.invokeOnCompletion { events += "serve" } }
            val handshakeScope =
                CoroutineScope(SupervisorJob()).also {
                    it.coroutineContext.job.invokeOnCompletion { events += "handshake" }
                }
            appScope.coroutineContext.job.invokeOnCompletion { events += "appscope" }

            DesktopShell.stopServices(
                jobRunner = { runner },
                appScope = appScope,
                serveJob = serveJob,
                handshakeScope = handshakeScope,
                closeHandshakeDispatcher = { events += "dispatcher" },
            )

            // The runner's grace ran its whole course first: the finalizer committed before any
            // scope cancel, and the hand-off pieces went down in the coordinator's order.
            assertThat(events)
                .containsExactly("finalizer", "serve", "handshake", "dispatcher", "appscope")
                .inOrder()
        }

    @Test
    fun `stopServices on a never-started runner still cancels the scopes`() =
        runBlocking {
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runner =
                DesktopJobRunner(
                    lanes = emptySet<JobLane>(),
                    clock = TestClock(),
                    power = NoPower,
                    network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
                    poolEvictor = NoopPoolEvictor,
                    scope = appScope,
                )

            // A quit before the band-200 initializer (or a SIGTERM racing start-up) must not
            // block: `stop` returns immediately for a runner that never started.
            DesktopShell.stopServices(
                jobRunner = { runner },
                appScope = appScope,
                serveJob = Job(),
                handshakeScope = CoroutineScope(SupervisorJob()),
                closeHandshakeDispatcher = {},
            )

            assertThat(appScope.coroutineContext.job.isCancelled).isTrue()
        }

    private object NoPower : PowerMonitor {
        override val events: Flow<PowerEvent> = emptyFlow()
    }

    private object NoopPoolEvictor : ConnectionPoolEvictor {
        override fun evict() = Unit
    }

    private companion object {
        const val FINALIZE_MS = 150L
        const val LANE_START_S = 15L
    }
}
