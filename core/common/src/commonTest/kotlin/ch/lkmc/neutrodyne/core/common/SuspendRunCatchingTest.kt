// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SuspendRunCatchingTest {
    @Test
    fun `captures ordinary failures`() =
        runTest {
            val boom = IllegalStateException("boom")
            val result = suspendRunCatching { throw boom }
            assertTrue(result.isFailure)
            assertEquals(boom, result.exceptionOrNull())
        }

    @Test
    fun `wraps success`() =
        runTest {
            assertEquals(Result.success(7), suspendRunCatching { 7 })
        }

    @Test
    fun `rethrows cancellation instead of capturing it`() =
        runTest {
            val job =
                launch {
                    suspendRunCatching {
                        // suspends until the parent cancels — must escape the catch, not become a Result
                        kotlinx.coroutines.awaitCancellation()
                    }
                }
            yield()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }

    @Test
    fun `rethrows a directly thrown CancellationException`() =
        runTest {
            assertFailsWith<CancellationException> {
                suspendRunCatching { throw CancellationException("stop") }
            }
        }

    @Test
    fun `cancellation inside nested suspend work still propagates`() =
        runTest {
            var reached = false
            val job =
                launch {
                    suspendRunCatching {
                        yield()
                        kotlinx.coroutines.awaitCancellation()
                    }
                    reached = true
                }
            yield()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertTrue(!reached)
        }
}
