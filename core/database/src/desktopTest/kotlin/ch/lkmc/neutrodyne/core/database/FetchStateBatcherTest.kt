// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.model.FeedErrorKind
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `FetchStateBatcher`'s flush barrier and deadline (02 Refresh selection and fetch-state writes):
 * buffer access, draining and the write itself are serialized, so a flush waits for every earlier
 * write and no concurrent `add` is lost; the 5 s limit is scheduled from the first pending
 * outcome rather than only being noticed by a later `add`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FetchStateBatcherTest {
    private fun row(failures: Int) =
        PodcastFetchState(
            id = 1,
            lastAttemptAt = null,
            lastSuccessAt = null,
            nextRefreshAt = null,
            failureCount = failures,
            lastErrorKind = FeedErrorKind.UNKNOWN,
            lastErrorDetail = null,
            gone = false,
            needsCredentials = false,
            etag = null,
            lastModified = null,
            lastFullFetchAt = null,
            lastParseOk = true,
        )

    @Test
    fun flushIsABarrierForEarlierWritesAndKeepsConcurrentAdds() =
        runTest {
            val writes = mutableListOf<List<PodcastFetchState>>()
            val writeEntered = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            var first = true
            val batcher =
                FetchStateBatcher(
                    write = { rows ->
                        if (first) {
                            first = false
                            writeEntered.complete(Unit)
                            releaseWrite.await()
                        }
                        writes += rows
                    },
                    clock = TestClock.from(testScheduler),
                    scope = backgroundScope,
                )

            val firstFlush =
                launch {
                    batcher.add(row(failures = 1))
                    batcher.flush()
                }
            writeEntered.await()

            // While the first write is suspended, a user action's add+flush must queue behind
            // it: the flush waits for the in-flight write, then persists the later add after it.
            val secondFlush =
                launch {
                    batcher.add(row(failures = 2))
                    batcher.flush()
                }
            runCurrent()
            try {
                assertTrue(secondFlush.isActive, "the second flush must wait for the in-flight write")
            } finally {
                // Always release — a failing assertion must not strand the NonCancellable write.
                releaseWrite.complete(Unit)
            }
            firstFlush.join()
            secondFlush.join()

            assertEquals(listOf(1, 2), writes.flatten().map { it.failureCount })
        }

    @Test
    fun theFiveSecondLimitIsScheduledFromTheFirstPendingOutcome() =
        runTest {
            val writes = mutableListOf<List<PodcastFetchState>>()
            val batcher =
                FetchStateBatcher(
                    write = { writes += it },
                    clock = TestClock.from(testScheduler),
                    scope = backgroundScope,
                )

            batcher.add(row(failures = 1))
            // No further outcome arrives (a stalled run): the scheduled flush still fires.
            advanceTimeBy(FetchStateBatcher.MAX_DELAY_MS - 1)
            runCurrent()
            assertTrue(writes.isEmpty(), "before the deadline the row is still buffered")
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(1), writes.flatten().map { it.failureCount })

            // The next batch arms a fresh deadline.
            batcher.add(row(failures = 2))
            advanceTimeBy(FetchStateBatcher.MAX_DELAY_MS)
            runCurrent()
            assertEquals(listOf(1, 2), writes.flatten().map { it.failureCount })
        }

    @Test
    fun aBackwardWallClockJumpCannotStrandTheDeadline() =
        runTest {
            // The deadline measures elapsed time: a wall-clock setback (NTP, DST, manual change)
            // must not stretch or drop the 5 s flush — it is armed on the monotonic clock.
            val clock = TestClock(nowMs = 1_000_000_000L, elapsedMs = 60_000L)
            val writes = mutableListOf<List<PodcastFetchState>>()
            val batcher =
                FetchStateBatcher(
                    write = { writes += it },
                    clock = clock,
                    scope = backgroundScope,
                )

            batcher.add(row(failures = 1))
            clock.nowMs -= 3_600_000L
            clock.elapsedMs += FetchStateBatcher.MAX_DELAY_MS
            advanceTimeBy(FetchStateBatcher.MAX_DELAY_MS)
            runCurrent()

            assertEquals(listOf(1), writes.flatten().map { it.failureCount })
        }

    @Test
    fun aCancelledAddStillCommitsTheTriggeredBatch() =
        runTest {
            val writes = mutableListOf<List<PodcastFetchState>>()
            val writeEntered = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            val batcher =
                FetchStateBatcher(
                    write = { rows ->
                        writeEntered.complete(Unit)
                        releaseWrite.await()
                        writes += rows
                    },
                    clock = TestClock.from(testScheduler),
                    scope = backgroundScope,
                )

            val run =
                launch { repeat(FetchStateBatcher.MAX_BATCH) { batcher.add(row(it)) } }
            writeEntered.await()
            run.cancel() // the refresh run dies mid-write
            runCurrent()
            releaseWrite.complete(Unit)
            run.join()

            assertEquals(FetchStateBatcher.MAX_BATCH, writes.flatten().size)
        }
}
