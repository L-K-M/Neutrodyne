// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.discovery

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.DirectoryHit
import ch.lkmc.neutrodyne.core.model.ProviderId
import ch.lkmc.neutrodyne.core.model.ProviderStatus
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.feeds.discovery.DirectoryRecord
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
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

    @Test fun fusionConnectsGuidBridgesAndPreservesProviderRanks() {
        val guid = "917393e3-1b1e-5cef-ace4-edaa54e1f810"
        val apple = hit("https://a.example/feed", ProviderId.APPLE, 1).copy(artworkUrl = "https://a.example/art")
        val pi = hit("https://a.example/feed/", ProviderId.PODCAST_INDEX, 3).copy(podcastGuid = guid, episodeCount = 4)
        val fyyd = hit("http://b.example/feed", ProviderId.FYYD, 2).copy(podcastGuid = guid, episodeCount = 8)
        val merged =
            fuseDirectoryHits(
                mapOf(
                    ProviderId.FYYD to persistentListOf(fyyd),
                    ProviderId.APPLE to persistentListOf(apple),
                    ProviderId.PODCAST_INDEX to persistentListOf(pi),
                ),
            ).single()
        assertEquals(setOf(ProviderId.APPLE, ProviderId.PODCAST_INDEX, ProviderId.FYYD), merged.providers)
        assertEquals(mapOf(ProviderId.APPLE to 1, ProviderId.PODCAST_INDEX to 3, ProviderId.FYYD to 2), merged.ranks)
        assertEquals(8, merged.episodeCount)
        assertEquals(apple.artworkUrl, merged.artworkUrl)
    }

    @Test fun missingProviderTitleDoesNotMaskLaterRealTitle() {
        val url = "https://show.example/feed"
        val apple = directoryHit(DirectoryRecord(url, null, rank = 1), ProviderId.APPLE)!!
        val pi = directoryHit(DirectoryRecord(url, "Real title", rank = 1), ProviderId.PODCAST_INDEX)!!
        val merged =
            fuseDirectoryHits(
                mapOf(ProviderId.APPLE to persistentListOf(apple), ProviderId.PODCAST_INDEX to persistentListOf(pi)),
            ).single()
        assertEquals("Real title", merged.title)
    }

    @Test fun fusionScoresAndTiesAreDeterministicAndOtherMediumIsHalved() {
        val apple = hit("https://apple.example/f", ProviderId.APPLE, 1)
        val pi = hit("https://pi.example/f", ProviderId.PODCAST_INDEX, 1)
        val fyyd = hit("https://fyyd.example/f", ProviderId.FYYD, 1)
        val answers =
            mapOf(
                ProviderId.FYYD to persistentListOf(fyyd),
                ProviderId.PODCAST_INDEX to persistentListOf(pi),
                ProviderId.APPLE to persistentListOf(apple),
            )
        assertEquals(listOf(apple, pi, fyyd), fuseDirectoryHits(answers))
        assertEquals(listOf(apple, fyyd, pi), fuseDirectoryHits(answers, setOf(pi.feedUrl)))
    }

    @Test fun unsafeQueriesAndUrlsAreRejectedAtTheBoundary() {
        for (query in listOf(
            "",
            "https://private.example/feed?token=secret",
            "private.example/rss",
            "@someone",
            "localhost",
            "file:secret",
            "192.168.1.2",
            "check private.example/foo",
        )) {
            assertFalse(safeDirectoryQuery(query), query)
        }
        assertTrue(safeDirectoryQuery("the daily"))
        assertTrue(safeDirectoryQuery("Mr. Ballen"))
        assertTrue(validDirectoryUrl("HTTPS://public.example/f"))
        for (url in listOf(
            "file:///tmp/feed",
            "javascript:alert(1)",
            "https://u:p@private.example/f",
            "https:///missing",
        )) {
            assertFalse(validDirectoryUrl(url), url)
        }
    }

    private fun hit(
        url: String,
        id: ProviderId,
        rank: Int,
    ) = DirectoryHit(
        url,
        "Show",
        providers = persistentSetOf(id),
        ranks =
            persistentMapOf(id to rank),
    )
}
