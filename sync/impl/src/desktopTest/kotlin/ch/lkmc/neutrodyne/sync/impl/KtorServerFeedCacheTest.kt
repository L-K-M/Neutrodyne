// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.sync.protocol.ProtocolLimits
import ch.lkmc.neutrodyne.sync.protocol.dto.CachedFeed
import ch.lkmc.neutrodyne.sync.protocol.dto.FeedCacheQuery
import ch.lkmc.neutrodyne.sync.protocol.dto.FeedCacheResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KtorServerFeedCacheTest {
    private val now = 1_791_072_000_000L
    private val body = "<rss><channel><title>New episode</title></channel></rss>".encodeToByteArray()
    private val sha = body.toByteString().sha256().hex()

    @Test
    fun anUnlinkedDeviceMakesNoRequest() =
        runTest {
            val client = FakeSyncClient()
            val cache = cache(client, linked = { false })

            val result = cache.lookup(mapOf(URL to sha))

            assertTrue(client.calls.isEmpty())
            assertTrue(result.isEmpty())
        }

    @Test
    fun aMissingServerUrlMakesNoRequest() =
        runTest {
            val client = FakeSyncClient()
            val cache = cache(client, serverUrl = null)

            val result = cache.lookup(mapOf(URL to sha))

            assertTrue(client.calls.isEmpty())
            assertTrue(result.isEmpty())
        }

    @Test
    fun aChangedBodyIsHashedAndReturned() =
        runTest {
            val client = FakeSyncClient()
            client.handler = { queries ->
                FeedCacheResponse(
                    queries.map {
                        CachedFeed(
                            it.url,
                            finalUrl = URL,
                            sha256 = sha,
                            checkedAt = now,
                            bodyBase64 = Base64.encode(body),
                        )
                    },
                )
            }
            val cache = cache(client)

            val result = cache.lookup(mapOf(URL to "0".repeat(64)))

            assertEquals(setOf(URL), result.keys)
            val snapshot = result.getValue(URL)
            assertEquals(URL, snapshot.finalUrl)
            assertEquals(sha, snapshot.sha256)
            assertEquals(now, snapshot.checkedAt)
            assertContentEquals(body, snapshot.body)
            assertEquals(listOf(URL), client.calls.single().map { it.url })
            assertEquals(
                "0".repeat(64),
                client.calls
                    .single()
                    .single()
                    .sha256,
            )
        }

    @Test
    fun aMatchingKnownHashNeedsNoBody() =
        runTest {
            val client = FakeSyncClient()
            client.handler = {
                FeedCacheResponse(listOf(CachedFeed(URL, URL, sha, now, bodyBase64 = null)))
            }
            val cache = cache(client)

            val result = cache.lookup(mapOf(URL to sha))

            assertEquals(sha, result.getValue(URL).sha256)
            assertEquals(null, result.getValue(URL).body)
        }

    @Test
    fun aChangedHashWithoutABodyIsDropped() =
        runTest {
            val client = FakeSyncClient()
            client.handler = {
                FeedCacheResponse(listOf(CachedFeed(URL, URL, sha, now, bodyBase64 = null)))
            }
            val cache = cache(client)

            val result = cache.lookup(mapOf(URL to "0".repeat(64)))

            assertTrue(result.isEmpty())
        }

    @Test
    fun aHashMismatchIsDropped() =
        runTest {
            val client = FakeSyncClient()
            client.handler = {
                FeedCacheResponse(
                    listOf(CachedFeed(URL, URL, "0".repeat(64), now, bodyBase64 = Base64.encode(body))),
                )
            }
            val cache = cache(client)

            val result = cache.lookup(mapOf(URL to sha))

            assertTrue(result.isEmpty())
        }

    @Test
    fun anOversizeBodyIsDroppedBeforeDecoding() =
        runTest {
            val client = FakeSyncClient()
            val encoded = "A".repeat(ProtocolLimits.MAX_FEED_BYTES * 4 / 3 + 9)
            client.handler = {
                FeedCacheResponse(listOf(CachedFeed(URL, URL, sha, now, bodyBase64 = encoded)))
            }
            val cache = cache(client)

            val result = cache.lookup(mapOf(URL to sha))

            assertTrue(result.isEmpty())
        }

    @Test
    fun aStaleOrFutureCheckTimeIsDropped() =
        runTest {
            val client = FakeSyncClient()
            client.handler = {
                FeedCacheResponse(
                    listOf(
                        CachedFeed("$URL?stale", "$URL?stale", sha, now - MAX_FRESH_AGE_MS - MAX_SKEW_MS - 1, null),
                        CachedFeed("$URL?future", "$URL?future", sha, now + MAX_SKEW_MS + 1, null),
                    ),
                )
            }
            val cache = cache(client)

            val result =
                cache.lookup(
                    mapOf(
                        "$URL?stale" to sha,
                        "$URL?future" to sha,
                    ),
                )

            assertTrue(result.isEmpty())
        }

    @Test
    fun anUnsolicitedUrlIsDropped() =
        runTest {
            val client = FakeSyncClient()
            client.handler = {
                FeedCacheResponse(listOf(CachedFeed("https://other.example/rss", URL, sha, now, Base64.encode(body))))
            }
            val cache = cache(client)

            val result = cache.lookup(mapOf(URL to sha))

            assertTrue(result.isEmpty())
        }

    @Test
    fun anInvalidFinalUrlIsDropped() =
        runTest {
            val client = FakeSyncClient()
            client.handler = {
                FeedCacheResponse(listOf(CachedFeed(URL, "not a url", sha, now, Base64.encode(body))))
            }
            val cache = cache(client)

            val result = cache.lookup(mapOf(URL to sha))

            assertTrue(result.isEmpty())
        }

    @Test
    fun anOldServerFallsBackToEmpty() =
        runTest {
            val client = FakeSyncClient()
            client.handler = { throw SyncTransportException(404, "not_found", "missing") }
            val cache = cache(client)

            val result = cache.lookup(mapOf(URL to sha))

            assertTrue(result.isEmpty())
        }

    @Test
    fun aNetworkFailureFallsBackToEmpty() =
        runTest {
            val client = FakeSyncClient()
            client.handler = { throw java.io.IOException("offline") }
            val cache = cache(client)

            val result = cache.lookup(mapOf(URL to sha))

            assertTrue(result.isEmpty())
        }

    @Test
    fun cancellationPropagates() =
        runTest {
            val client = FakeSyncClient()
            client.handler = { throw CancellationException("stopped") }
            val cache = cache(client)

            assertFailsWith<CancellationException> { cache.lookup(mapOf(URL to sha)) }
        }

    @Test
    fun aLargerLibraryIsAskedInChunksAndMerged() =
        runTest {
            val client = FakeSyncClient()
            client.handler = { queries ->
                FeedCacheResponse(queries.map { CachedFeed(it.url, it.url, sha, now, bodyBase64 = null) })
            }
            val cache = cache(client)
            val feeds = (0 until 1001).associate { "https://feed.example/$it" to sha }

            val result = cache.lookup(feeds)

            assertEquals(2, client.calls.size)
            assertEquals(1000, client.calls[0].size)
            assertEquals(1, client.calls[1].size)
            assertEquals(feeds.keys, result.keys)
        }

    @Test
    fun aMalformedKnownHashIsSentAsAbsent() =
        runTest {
            val client = FakeSyncClient()
            client.handler = { FeedCacheResponse(emptyList()) }
            val cache = cache(client)

            cache.lookup(mapOf(URL to "not-a-hash"))

            assertEquals(
                null,
                client.calls
                    .single()
                    .single()
                    .sha256,
            )
        }

    private fun cache(
        client: FakeSyncClient,
        serverUrl: String? = SERVER_URL,
        linked: suspend () -> Boolean = { true },
    ) = KtorServerFeedCache(TestClock(now), linked) {
        if (serverUrl == null || !linked()) null else FeedCacheSession(client, linked)
    }

    private class FakeSyncClient : SyncClient by ScriptedSyncClient() {
        val calls = mutableListOf<List<FeedCacheQuery>>()
        var handler: suspend (List<FeedCacheQuery>) -> FeedCacheResponse = { FeedCacheResponse(emptyList()) }

        override suspend fun feedCache(queries: List<FeedCacheQuery>): FeedCacheResponse {
            calls += queries
            return handler(queries)
        }
    }

    private companion object {
        const val URL = "https://feed.example/rss"
        const val SERVER_URL = "https://sync.example"
        const val MAX_FRESH_AGE_MS = 30 * 60_000L
        const val MAX_SKEW_MS = 300_000L
    }
}
