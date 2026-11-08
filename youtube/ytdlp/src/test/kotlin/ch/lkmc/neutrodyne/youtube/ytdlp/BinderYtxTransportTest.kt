// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.ytdlp

import ch.lkmc.neutrodyne.core.testing.FakeEngineSettings
import ch.lkmc.neutrodyne.core.testing.FakeEngineStore
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.youtube.api.PrewarmReason
import ch.lkmc.neutrodyne.youtube.engine.EnginePreparation
import ch.lkmc.neutrodyne.youtube.engine.HostExit
import ch.lkmc.neutrodyne.youtube.engine.HostState
import ch.lkmc.neutrodyne.youtube.engine.YtxCallException
import ch.lkmc.neutrodyne.youtube.engine.YtxContext
import ch.lkmc.neutrodyne.youtube.engine.YtxControlRequest
import ch.lkmc.neutrodyne.youtube.engine.YtxDeadline
import ch.lkmc.neutrodyne.youtube.engine.YtxLimits
import ch.lkmc.neutrodyne.youtube.engine.YtxMethod
import ch.lkmc.neutrodyne.youtube.ytdlp.ytx.IYtxCallback
import ch.lkmc.neutrodyne.youtube.ytdlp.ytx.IYtxEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BinderYtxTransportTest {
    @Test fun deadlineKillsOnlyTheEngineAndFailsOtherCalls() =
        runTest {
            val f = Fixture(this)
            val first = async { runCatching { f.call(1000) }.exceptionOrNull() }
            val other = async { runCatching { f.call(20_000) }.exceptionOrNull() }
            runCurrent()
            advanceTimeBy(6001)
            assertEquals("TIMEOUT", (first.await() as YtxCallException).code)
            assertEquals("ENGINE_UNAVAILABLE", (other.await() as YtxCallException).code)
            assertEquals(HostExit.HANG_KILL, f.transport.status.value.lastExit)
            assertEquals(1, f.connection.stops)
            f.transport.shutdown()
        }

    @Test fun cancellationKeepsTheWatchdogForAnUnresponsiveHost() =
        runTest {
            val f = Fixture(this)
            val call = async { f.call(1000) }
            runCurrent()
            call.cancelAndJoin()
            assertEquals(listOf(1L), f.endpoint.cancelled)
            advanceTimeBy(6001)
            assertEquals(HostExit.HANG_KILL, f.transport.status.value.lastExit)
        }

    @Test fun deathFailsInFlightAndNextCallStartsAnotherHost() =
        runTest {
            val f = Fixture(this)
            val call = async { runCatching { f.call(1000) }.exceptionOrNull() }
            runCurrent()
            f.connection.died()
            assertEquals("ENGINE_UNAVAILABLE", (call.await() as YtxCallException).code)
            f.endpoint.answer = true
            assertEquals("{}", f.call(1000))
            assertEquals(2, f.connection.binds)
            f.transport.shutdown()
        }

    @Test fun idleStopsAfterThreeMinutesAndPrewarmResetsTheTimer() =
        runTest {
            val f = Fixture(this)
            f.endpoint.answer = true
            f.call(1000)
            advanceTimeBy(120_000)
            f.transport.prewarm(PrewarmReason.SCREEN)
            runCurrent()
            advanceTimeBy(120_000)
            assertEquals(HostState.READY, f.transport.status.value.state)
            advanceTimeBy(60_001)
            assertEquals(HostExit.IDLE_STOP, f.transport.status.value.lastExit)
        }

    @Test fun missingPingFailsStartupWithoutWaitingForCallDeadline() =
        runTest {
            val f = Fixture(this)
            f.endpoint.ping = false
            val call = async { runCatching { f.call(40_000) }.exceptionOrNull() }
            advanceTimeBy(YtxLimits.START_MS + 1)
            assertEquals("ENGINE_UNAVAILABLE", (call.await() as YtxCallException).code)
            assertEquals(HostExit.START_FAILED, f.transport.status.value.lastExit)
        }

    @Test fun deathBeforeStartupPingIsAnEngineFailureAndAllowsRestart() =
        runTest {
            val f = Fixture(this)
            f.endpoint.ping = false
            val call = async { runCatching { f.call(40_000) }.exceptionOrNull() }
            runCurrent()
            f.connection.died()
            assertEquals("ENGINE_UNAVAILABLE", (call.await() as? YtxCallException)?.code)
            f.endpoint.ping = true
            f.endpoint.answer = true
            assertEquals("{}", f.call(1000))
            assertEquals(2, f.connection.binds)
        }

    @Test fun unpreparedPlaybackNeverStartsOrCompilesTheHost() =
        runTest {
            val f = Fixture(this)
            f.store.preparation.value = EnginePreparation.UNPREPARED
            assertFailsWith<YtxCallException> { f.call(1000) }
            assertEquals(0, f.connection.binds)
        }

    @Test fun disabledSettingsNeverBind() =
        runTest {
            val f = Fixture(this)
            f.settings.state.value =
                f.settings.state.value
                    .copy(enabled = false)
            assertFailsWith<YtxCallException> { f.call(1000) }
            assertEquals(0, f.connection.binds)
        }

    @Test fun binderBoundsIncludeStringFraming() =
        runTest {
            val f = Fixture(this)
            val failure =
                assertFailsWith<YtxCallException> {
                    f.transport.call(YtxMethod.RESOLVE, "x".repeat(YtxLimits.PAYLOAD_BYTES / 2), YtxDeadline(1000))
                }
            assertEquals("EXTRACTION", failure.code)
            assertEquals(0, f.connection.binds)
        }

    @Test fun cancelledCallsKeepTheirHostSlotsUntilTheHostReplies() =
        runTest {
            val f = Fixture(this)
            val first = async { f.call(1000) }
            val second = async { f.call(1000) }
            runCurrent()
            first.cancelAndJoin()
            second.cancelAndJoin()
            val third = async { f.call(20_000) }
            try {
                runCurrent()
                assertEquals(2, f.endpoint.calls.size)
                assertEquals(2, f.transport.status.value.inFlight.size)
            } finally {
                third.cancelAndJoin()
                f.transport.shutdown()
            }
        }

    private class Fixture(
        scope: TestScope,
    ) {
        val settings = FakeEngineSettings()
        val endpoint = Endpoint()
        val connection = Connection(endpoint)
        val store =
            FakeEngineStore().apply {
                preparation.value = EnginePreparation.COMPILED
                libDir = "/prepared/lib".toPath()
            }
        private val clock = TestClock.from(scope.testScheduler)
        val transport =
            BinderYtxTransport(
                connection,
                store,
                clock,
                scope.backgroundScope,
                YtxControlRequest(YtxContext("en", "US", "test")),
                settings,
            )

        suspend fun call(budget: Long) =
            transport.call(
                YtxMethod.RESOLVE,
                "{}",
                YtxDeadline(clock.elapsedRealtime() + budget),
            )
    }

    private class Connection(
        private val endpoint: Endpoint,
    ) : YtxConnection {
        var binds = 0
        var stops = 0
        var died: () -> Unit = {}

        override suspend fun bind(died: () -> Unit): IYtxEngine {
            this.died = died
            binds++
            return endpoint
        }

        override fun stop(pid: Long?) {
            stops++
        }
    }

    private class Endpoint : IYtxEngine.Stub() {
        var answer = false
        var ping = true
        val cancelled = mutableListOf<Long>()
        val calls = mutableListOf<Long>()

        override fun status() = """{"pid":1234}"""

        override fun call(
            id: Long,
            method: String?,
            payload: String?,
            deadline: Long,
            callback: IYtxCallback?,
        ) {
            if (method != "ping") calls += id
            if (method == "ping" && ping) callback?.onResult(id, """{"ready":true}""")
            if (method != "ping" && answer) callback?.onResult(id, "{}")
        }

        override fun cancel(id: Long) {
            cancelled += id
        }
    }
}
