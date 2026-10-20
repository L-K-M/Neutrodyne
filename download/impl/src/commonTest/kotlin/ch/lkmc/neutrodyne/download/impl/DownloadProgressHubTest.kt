// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadProgressHubTest {
    @Test fun rapidBytesAndStateChangesPublishAtMostFourTimesPerSecond() =
        runTest {
            val clock = TestClock.from(testScheduler)
            val hub = DownloadProgressHub(backgroundScope, clock)
            val emissions = mutableListOf<Pair<Long, Long?>>()
            backgroundScope.launch {
                hub.observe(setOf(1)).collect {
                    emissions +=
                        clock.elapsedRealtime() to it[1]?.downloadedBytes
                }
            }
            runCurrent()
            hub.begin(1, 0, 10000)
            hub.begin(2, 0, null)
            repeat(1000) { n ->
                hub.update(1, if (n % 2 == 0) DownloadState.DOWNLOADING else DownloadState.VERIFYING, n.toLong(), 10000)
                advanceTimeBy(1)
            }
            runCurrent()
            assertEquals(listOf(0L, 250L, 500L, 750L, 1000L), emissions.map { it.first })
            assertEquals(999L, emissions.last().second)
            assertEquals(setOf(1L, 2L), hub.snapshots.value.keys)
            hub.end(1)
            hub.end(2)
            advanceTimeBy(250)
            runCurrent()
            assertTrue(hub.snapshots.value.isEmpty())
            val count = emissions.size
            advanceTimeBy(10000)
            runCurrent()
            assertEquals(count, emissions.size, "idle ticker emits nothing")
        }

    @Test fun speedUsesElapsedTimeAndResetDoesNotCreateNegativeSpeed() =
        runTest {
            val hub = DownloadProgressHub(backgroundScope, TestClock.from(testScheduler))
            hub.begin(1, 0, null)
            runCurrent()
            advanceTimeBy(250)
            runCurrent()
            hub.update(1, DownloadState.DOWNLOADING, 250, null)
            advanceTimeBy(250)
            runCurrent()
            assertEquals(1000L, hub.snapshots.value[1]?.bytesPerSecond)
            hub.update(1, DownloadState.DOWNLOADING, 0, null)
            advanceTimeBy(250)
            runCurrent()
            assertNull(hub.snapshots.value[1]?.bytesPerSecond)
        }

    @Test fun randomizedUpdatesRemovalsAndResubscriptionsKeepBoundedSnapshots() =
        runTest {
            val clock = TestClock.from(testScheduler)
            val hub = DownloadProgressHub(backgroundScope, clock)
            val random = Random(9)
            val ticks = mutableListOf<Long>()
            backgroundScope.launch { hub.observe((1L..8L).toSet()).collect { ticks += clock.elapsedRealtime() } }
            runCurrent()
            repeat(1000) {
                val id = random.nextLong(1, 9)
                when (random.nextInt(3)) {
                    0 -> hub.begin(id, 0, null)
                    1 -> hub.update(id, DownloadState.DOWNLOADING, random.nextLong(10000), null)
                    else -> hub.end(id)
                }
                advanceTimeBy(random.nextLong(1, 20))
            }
            runCurrent()
            assertTrue(ticks.zipWithNext().all { (a, b) -> b - a >= 250 })
        }
}
