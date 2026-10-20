// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.artwork

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkSyncSchedulerTest {
    @Test fun importHintsDebounceAndContinuationIsImmediate() =
        runTest {
            var enqueues = 0
            val scheduler =
                ArtworkSyncScheduler(
                    object : ArtworkWorkScheduler {
                        override suspend fun enqueue() {
                            enqueues++
                        }
                    },
                    backgroundScope,
                )
            repeat(300) { scheduler.request() }
            runCurrent()
            advanceTimeBy(1_999)
            assertEquals(0, enqueues)
            scheduler.request()
            runCurrent()
            advanceTimeBy(1_999)
            assertEquals(0, enqueues)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, enqueues)
            scheduler.enqueueNow()
            assertEquals(2, enqueues)
        }
}
