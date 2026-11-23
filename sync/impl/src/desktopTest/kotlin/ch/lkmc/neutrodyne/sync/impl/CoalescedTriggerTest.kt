// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The trigger's own bookkeeping, beyond what `SyncSchedulerTest` drives through the scheduler. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoalescedTriggerTest {
    @Test
    fun aRunThatThrowsLeavesNoQueuedRunForTheNextSignal() =
        runTest {
            val failures = mutableListOf<Throwable>()
            val scope =
                CoroutineScope(
                    StandardTestDispatcher(testScheduler) + SupervisorJob() +
                        CoroutineExceptionHandler { _, failure -> failures += failure },
                )
            val gate = CompletableDeferred<Unit>()
            var runs = 0
            val trigger =
                CoalescedTrigger(scope, QUIET_MS) {
                    runs++
                    if (runs == 1) {
                        gate.await()
                        error("round failed")
                    }
                }
            try {
                signalAndWait(trigger)
                assertEquals(1, runs)
                // Queued behind the running first run, which then throws before its tail.
                signalAndWait(trigger)
                gate.complete(Unit)
                runCurrent()
                assertEquals(1, failures.size)

                signalAndWait(trigger)
                assertEquals(2, runs, "the next signal runs once, not once more for the stale request")
            } finally {
                scope.cancel()
            }
        }

    private suspend fun TestScope.signalAndWait(trigger: CoalescedTrigger) {
        trigger.signal()
        advanceTimeBy(QUIET_MS + 1)
        runCurrent()
    }

    private companion object {
        const val QUIET_MS = 100L
    }
}
