// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.youtube.engine

import ch.lkmc.neutrodyne.core.testing.FakeYtxTransport
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YtDlpClientTest {
    @Test fun priorityFifoAdmissionAtTwoAndFourWorkers() =
        runTest {
            for (workers in listOf(2, 4)) {
                val clock = TestClock.from(testScheduler)
                val transport = FakeYtxTransport()
                transport.status.value = transport.status.value.copy(workers = workers)
                val client = YtDlpClient(transport, clock, backgroundScope)
                val releases = mutableMapOf<Long, CompletableDeferred<String>>()
                var active = 0
                var peak = 0
                transport.answer = { call ->
                    active++
                    peak = maxOf(peak, active)
                    try {
                        CompletableDeferred<String>().also { releases[call.id] = it }.await()
                    } finally {
                        active--
                    }
                }
                val deadline = YtxDeadline(clock.elapsedRealtime() + 10000)
                val initial = List(workers) { async { client.call(YtxMethod.FACTS, "{}", deadline) } }
                runCurrent()
                val methods =
                    listOf(
                        YtxMethod.TAB_OPEN,
                        YtxMethod.SEARCH_OPEN,
                        YtxMethod.RESOLVE,
                        YtxMethod.LOOKUP,
                        YtxMethod.RESOLVE,
                        YtxMethod.FACTS,
                    )
                val pending =
                    methods
                        .map { method -> async { client.call(method, "{}", deadline) } }
                runCurrent()
                assertEquals(workers, transport.calls.size)
                val expected =
                    listOf(
                        YtxMethod.RESOLVE,
                        YtxMethod.RESOLVE,
                        YtxMethod.SEARCH_OPEN,
                        YtxMethod.LOOKUP,
                        YtxMethod.TAB_OPEN,
                        YtxMethod.FACTS,
                    )
                repeat(expected.size) { index ->
                    releases.values.first { !it.isCompleted }.complete("{}")
                    runCurrent()
                    assertEquals(expected[index], transport.calls[workers + index].method)
                }
                releases.values.forEach { it.complete("{}") }
                runCurrent()
                val allJobs = initial + pending
                allJobs.forEach { it.await() }
                assertEquals(workers, peak)
            }
        }

    @Test fun queueTimeIsPartOfDeadlineAndExpiredWaiterNeverReachesHost() =
        runTest {
            val clock = TestClock.from(testScheduler)
            val transport = FakeYtxTransport()
            transport.status.value = transport.status.value.copy(workers = 1)
            val client = YtDlpClient(transport, clock, backgroundScope)
            val release = CompletableDeferred<String>()
            transport.answer = { release.await() }
            val first = async { client.call(YtxMethod.FACTS, "{}", YtxDeadline(1000)) }
            runCurrent()
            val queued =
                async {
                    assertFailsWith<YtxCallException> {
                        client.call(YtxMethod.RESOLVE, "{}", YtxDeadline(100))
                    }.code
                }
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals("TIMEOUT", queued.await())
            assertEquals(1, transport.calls.size)
            release.complete("{}")
            first.await()
            assertEquals(1, transport.calls.size)
        }

    @Test fun callerCancellationRemovesQueuedWaiterAndCancelsOnlyActiveHostId() =
        runTest {
            val transport = FakeYtxTransport()
            transport.status.value = transport.status.value.copy(workers = 1)
            val client = YtDlpClient(transport, TestClock.from(testScheduler), backgroundScope)
            transport.answer = { CompletableDeferred<String>().await() }
            val active = async { client.call(YtxMethod.FACTS, "{}", YtxDeadline(1000)) }
            runCurrent()
            val queued = async { client.call(YtxMethod.RESOLVE, "{}", YtxDeadline(1000)) }
            runCurrent()
            queued.cancelAndJoin()
            assertTrue(transport.cancelled.isEmpty())
            active.cancelAndJoin()
            assertEquals(listOf(1L), transport.cancelled)
            transport.answer = { "{}" }
            assertEquals("{}", client.call(YtxMethod.RESOLVE, "{}", YtxDeadline(1000)))
        }

    @Test fun deadlineDuringWireCallCancelsTransportAndRecoversPermit() =
        runTest {
            val transport = FakeYtxTransport()
            val client = YtDlpClient(transport, TestClock.from(testScheduler), backgroundScope)
            transport.answer = {
                delay(200)
                "{}"
            }
            val timed =
                async {
                    assertFailsWith<YtxCallException> {
                        client.call(YtxMethod.RESOLVE, "{}", YtxDeadline(100))
                    }.code
                }
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals("TIMEOUT", timed.await())
            assertEquals(listOf(1L), transport.cancelled)
            transport.answer = { "{}" }
            assertEquals("{}", client.call(YtxMethod.RESOLVE, "{}", YtxDeadline(1000)))
            assertEquals(YtxDeadline(1000), transport.calls.last().deadline)
        }

    @Test fun nonCooperativeLateAnswerCannotBeReturnedAsSuccess() =
        runTest {
            val transport = FakeYtxTransport()
            val client = YtDlpClient(transport, TestClock.from(testScheduler), backgroundScope)
            transport.answer = {
                withContext(NonCancellable) {
                    delay(200)
                    "{}"
                }
            }
            val result =
                async {
                    assertFailsWith<YtxCallException> {
                        client.call(YtxMethod.RESOLVE, "{}", YtxDeadline(100))
                    }.code
                }
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            assertEquals("TIMEOUT", result.await())
        }

    @Test fun changingWorkerCountDrainsQueueWithoutOverAdmission() =
        runTest {
            val transport = FakeYtxTransport()
            transport.status.value = transport.status.value.copy(workers = 1)
            val client = YtDlpClient(transport, TestClock.from(testScheduler), backgroundScope)
            val release = CompletableDeferred<String>()
            transport.answer = { release.await() }
            val jobs = List(5) { async { client.call(YtxMethod.FACTS, "{}", YtxDeadline(1000)) } }
            runCurrent()
            assertEquals(1, transport.calls.size)
            transport.status.value = transport.status.value.copy(workers = 4)
            runCurrent()
            assertEquals(4, transport.calls.size)
            transport.status.value = transport.status.value.copy(workers = 2)
            runCurrent()
            assertEquals(4, transport.calls.size)
            release.complete("{}")
            runCurrent()
            jobs.forEach { it.await() }
            assertEquals(5, transport.calls.size)
        }

    @Test fun nestedCallsShareAdmissionAndDeadlineAndReleaseAfterFailure() =
        runTest {
            val transport = FakeYtxTransport()
            transport.status.value = transport.status.value.copy(workers = 1)
            val client = YtDlpClient(transport, TestClock.from(testScheduler), backgroundScope)
            transport.answer = {
                if (it.method == YtxMethod.TAB_NEXT) throw YtxCallException("NETWORK")
                "{}"
            }
            assertFailsWith<YtxCallException> {
                client.operation(YtxPriority.BACKGROUND, YtxDeadline(1234)) {
                    call(YtxMethod.TAB_OPEN, "{}")
                    call(YtxMethod.TAB_NEXT, "{}")
                }
            }
            assertEquals(listOf(YtxDeadline(1234), YtxDeadline(1234)), transport.calls.map { it.deadline })
            assertEquals("{}", client.call(YtxMethod.RESOLVE, "{}", YtxDeadline(2000)))
        }

    @Test fun oversizedPayloadAndResultNeverEscapeBoundary() =
        runTest {
            val transport = FakeYtxTransport()
            val client = YtDlpClient(transport, TestClock.from(testScheduler), backgroundScope)
            assertFailsWith<YtxCallException> {
                client.call(YtxMethod.RESOLVE, " ".repeat(YtxLimits.PAYLOAD_BYTES + 1), YtxDeadline(1000))
            }
            assertTrue(transport.calls.isEmpty())
            transport.answer = { " ".repeat(YtxLimits.RESULT_BYTES + 1) }
            val failure =
                assertFailsWith<YtxCallException> {
                    client.call(YtxMethod.RESOLVE, "{}", YtxDeadline(1000))
                }
            assertEquals("EXTRACTION", failure.code)
            assertFalse(client.isReady)
            transport.status.value = transport.status.value.copy(state = HostState.READY)
            assertTrue(client.isReady)
        }

    @Test fun operationCannotReturnSuccessAfterItsAbsoluteDeadline() =
        runTest {
            val clock = TestClock()
            val transport = FakeYtxTransport()
            val client = YtDlpClient(transport, clock, backgroundScope)
            transport.answer = { "{}" }
            val failure =
                assertFailsWith<YtxCallException> {
                    client.operation(YtxPriority.PLAYBACK, YtxDeadline(1000)) {
                        call(YtxMethod.RESOLVE, "{}")
                        clock.elapsedMs = 1000
                        "late mapping result"
                    }
                }
            assertEquals("TIMEOUT", failure.code)
            assertEquals("{}", client.call(YtxMethod.RESOLVE, "{}", YtxDeadline(2000)))
        }

    @Test fun lateWireFailureStillObeysTheAbsoluteDeadline() =
        runTest {
            val clock = TestClock()
            val transport = FakeYtxTransport()
            val client = YtDlpClient(transport, clock, backgroundScope)
            transport.answer = {
                clock.elapsedMs = 1000
                throw YtxCallException("UNAVAILABLE")
            }
            val failure =
                assertFailsWith<YtxCallException> {
                    client.call(YtxMethod.RESOLVE, "{}", YtxDeadline(1000))
                }
            assertEquals("TIMEOUT", failure.code)
            transport.answer = { "{}" }
            assertEquals("{}", client.call(YtxMethod.RESOLVE, "{}", YtxDeadline(2000)))
        }
}
