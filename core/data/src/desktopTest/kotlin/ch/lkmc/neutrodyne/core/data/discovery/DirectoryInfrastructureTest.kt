// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.discovery

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.testing.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class DirectoryInfrastructureTest {
    @Test fun rollingWindowIsSharedAndConcurrentCallsDoNotQueue() =
        runTest {
            val clock = TestClock()
            val bucket = TokenBucket(clock)
            assertEquals(20, (1..50).map { async { bucket.take() } }.awaitAll().count { it })
            clock.advanceBy(3000.milliseconds)
            assertFalse(bucket.take())
            clock.advanceBy(56999.milliseconds)
            assertFalse(bucket.take())
            clock.advanceBy(1.milliseconds)
            assertTrue(bucket.take())
            bucket.cooldown()
            clock.advanceBy(59999.milliseconds)
            assertFalse(bucket.take())
            clock.advanceBy(1.milliseconds)
            assertTrue(bucket.take())
        }

    @Test fun cacheCoalescesExpiresAndEvictsLeastRecentlyUsed() =
        runTest {
            val clock = TestClock()
            val cache = DirectoryCache<Int>(clock, 2, 1000)
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            val first =
                async {
                    cache.load("a") {
                        calls++
                        gate.await()
                        Outcome.Success(1)
                    }
                }
            yield()
            val duplicate = async { cache.load("a") { error("duplicate load") } }
            yield()
            gate.complete(Unit)
            assertEquals(first.await(), duplicate.await())
            assertEquals(1, calls)
            cache.load("b") { Outcome.Success(2) }
            cache.load("a") { error("cache miss") }
            cache.load("c") { Outcome.Success(3) }
            assertEquals(Outcome.Success(4), cache.load("b") { Outcome.Success(4) })
            clock.advanceBy(1000.milliseconds)
            assertEquals(Outcome.Success(5), cache.load("b") { Outcome.Success(5) })
        }

    @Test fun failuresAreNotCachedAndCancelledLoadsCanRetry() =
        runTest {
            val cache = DirectoryCache<Int>(TestClock(), 2, 1000)
            assertIs<Outcome.Failure<ProviderStatus>>(cache.load("a") { Outcome.Failure(ProviderStatus.RateLimited) })
            assertEquals(Outcome.Success(1), cache.load("a") { Outcome.Success(1) })
            val started = CompletableDeferred<Unit>()
            val job =
                launch {
                    cache.load("b") {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
            started.await()
            job.cancelAndJoin()
            assertEquals(Outcome.Success(2), cache.load("b") { Outcome.Success(2) })
        }

    @Test fun cancelledOwnerDoesNotCancelCoalescedCaller() =
        runTest {
            val cache = DirectoryCache<Int>(TestClock(), 2, 1000)
            val started = CompletableDeferred<Unit>()
            val owner =
                launch {
                    cache.load("shared") {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
            started.await()
            val waiting = async { cache.load("shared") { Outcome.Success(3) } }
            yield()
            owner.cancelAndJoin()
            assertEquals(Outcome.Success(3), waiting.await())
        }
}
