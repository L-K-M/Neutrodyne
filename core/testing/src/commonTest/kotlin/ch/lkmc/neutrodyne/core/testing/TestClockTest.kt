// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TestClockTest {
    @Test
    fun `default instant is the documented fixed epoch`() {
        // 2026-10-04T00:00:00Z
        assertEquals(1_791_072_000_000L, TestClock.DEFAULT_NOW)
        assertEquals(TestClock.DEFAULT_NOW, TestClock().now())
        assertEquals(0L, TestClock().elapsedRealtime())
    }

    @Test
    fun `advanceBy moves both clocks`() {
        val clock = TestClock()
        clock.advanceBy(5.minutes)
        assertEquals(TestClock.DEFAULT_NOW + 5.minutes.inWholeMilliseconds, clock.now())
        assertEquals(5.minutes.inWholeMilliseconds, clock.elapsedRealtime())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `scheduler-backed clock tracks virtual time`() =
        runTest {
            val clock = TestClock.from(testScheduler)
            assertEquals(TestClock.DEFAULT_NOW, clock.now())
            assertEquals(0L, clock.elapsedRealtime())

            var woke = false
            launch {
                kotlinx.coroutines.delay(90.seconds)
                woke = true
            }
            testScheduler.advanceTimeBy(90.seconds.inWholeMilliseconds)
            testScheduler.runCurrent()

            assertTrue(woke)
            assertEquals(TestClock.DEFAULT_NOW + 90.seconds.inWholeMilliseconds, clock.now())
            assertEquals(90.seconds.inWholeMilliseconds, clock.elapsedRealtime())
        }
}

class FakeNetworkMonitorTest {
    @Test
    fun `starts offline and reports every setStatus`() =
        runTest {
            val monitor = FakeNetworkMonitor()
            assertEquals(FakeNetworkMonitor.OFFLINE, monitor.status.value)

            monitor.setStatus(FakeNetworkMonitor.ONLINE)
            assertEquals(FakeNetworkMonitor.ONLINE, monitor.status.value)

            val metered = FakeNetworkMonitor.ONLINE.copy(isMetered = true)
            monitor.setStatus(metered)
            assertEquals(metered, monitor.status.value)
        }

    @Test
    fun `implements the NetworkMonitor contract`() {
        val monitor: ch.lkmc.neutrodyne.core.common.NetworkMonitor = FakeNetworkMonitor()
        assertTrue(!monitor.status.value.isConnected)
    }
}

class MainDispatcherTestTest : MainDispatcherTest() {
    @Test
    fun `Dispatchers_Main is the installed test dispatcher`() =
        runTest(dispatcher) {
            // Without the installed dispatcher, Dispatchers.Main has no delegate on a bare JVM.
            var ran = false
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { ran = true }
            assertTrue(ran)
        }
}
