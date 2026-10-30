// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerReadRecoveryTest {
    @Test fun aSuccessfulReadRearmsTheFailureNotice() =
        runTest {
            var subscriptions = 0
            var notices = 0
            val values = mutableListOf<String?>()
            val source =
                flow<String?> {
                    when (++subscriptions) {
                        1 -> {
                            error("First read failed")
                        }

                        2 -> {
                            emit("Recovered")
                            error("Later read failed")
                        }

                        else -> {
                            emit("Recovered again")
                        }
                    }
                }
            source.retryPlayerRead(null) { notices++ }.collect { values += it }
            assertEquals(listOf(null, "Recovered", null, "Recovered again"), values)
            assertEquals(2, notices)
        }

    @Test fun cancellationStopsReadRetries() =
        runTest {
            var subscriptions = 0
            val source =
                flow<Unit> {
                    subscriptions++
                    error("Read failed")
                }
            val collector = launch { source.retryPlayerRead(Unit) {}.collect() }
            runCurrent()
            assertEquals(1, subscriptions)
            collector.cancelAndJoin()
            advanceUntilIdle()
            assertEquals(1, subscriptions)
        }
}
