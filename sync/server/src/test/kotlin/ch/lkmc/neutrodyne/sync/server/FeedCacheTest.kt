// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.dto.AccountResetRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.FeedCacheQuery
import ch.lkmc.neutrodyne.sync.protocol.dto.FeedCacheRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.FeedCacheResponse
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedCacheTest {
    @Test fun `batch returns fresh matching entries and omits a body only when the sha matches`() =
        authApplication { f ->
            val alice = f.grant(f.account("alice"))
            val bob = f.grant(f.account("bob"))
            val urlOne = "https://example.com/one"
            val urlTwo = "https://example.com/two"
            val urlStale = "https://example.com/stale"
            val writerA = WriterFixture(f.store, f.principal(alice)).apply { now = f.now }
            val writerB = WriterFixture(f.store, f.principal(bob)).apply { now = f.now }
            writerA.push(writerA.podcast(A, urlOne))
            writerB.push(writerB.podcast(A, urlOne))
            val bodyOne = "one".toByteArray()
            val bodyTwo = "two".toByteArray()
            val bodyStale = "stale".toByteArray()
            f.store.write(writerA.principal.accountId) {
                putFeedCache(entry(urlOne, sha256 = sha256Hex(bodyOne), body = bodyOne, checkedAtMs = f.now))
                putFeedCache(entry(urlTwo, sha256 = sha256Hex(bodyTwo), body = bodyTwo, checkedAtMs = f.now))
                putFeedCache(
                    entry(
                        urlStale,
                        sha256 = sha256Hex(bodyStale),
                        body = bodyStale,
                        checkedAtMs = f.now - FeedCacheConfig.DEFAULT_TTL_MS - 1,
                    ),
                )
            }
            val bobBody = "bob".toByteArray()
            f.store.write(writerB.principal.accountId) {
                putFeedCache(entry(urlOne, sha256 = sha256Hex(bobBody), body = bobBody, checkedAtMs = f.now))
            }

            val response =
                client
                    .postApi(
                        "/api/v1/feed-cache",
                        FeedCacheRequest(
                            listOf(
                                FeedCacheQuery(urlOne, sha256Hex(bodyOne)),
                                FeedCacheQuery(urlTwo, "0".repeat(64)),
                                FeedCacheQuery(urlStale),
                                FeedCacheQuery("https://unknown.example/feed"),
                            ),
                        ),
                        alice.token,
                    ).decoded<FeedCacheResponse>()
            assertEquals(2, response.feeds.size)
            val one = response.feeds.single { it.url == urlOne }
            assertNull(one.bodyBase64)
            assertEquals(sha256Hex(bodyOne), one.sha256)
            val two = response.feeds.single { it.url == urlTwo }
            assertEquals(
                bodyTwo.toString(Charsets.UTF_8),
                two.bodyBase64?.let { String(Base64.getDecoder().decode(it)) },
            )

            // Account isolation: Bob's body for the same URL never reaches Alice, and vice versa.
            val bobResponse =
                client
                    .postApi(
                        "/api/v1/feed-cache",
                        FeedCacheRequest(listOf(FeedCacheQuery(urlOne))),
                        bob.token,
                    ).decoded<FeedCacheResponse>()
            val bobFeed = bobResponse.feeds.single()
            assertEquals(bobBody.toString(Charsets.UTF_8), String(Base64.getDecoder().decode(bobFeed.bodyBase64!!)))
        }

    @Test fun `batch requires authentication, the protocol header and valid queries`() =
        authApplication { f ->
            val grant = f.grant(f.account())
            client
                .postApi("/api/v1/feed-cache", FeedCacheRequest(listOf(FeedCacheQuery("https://example.com/feed"))))
                .expect(HttpStatusCode.Unauthorized)
            client
                .post("/api/v1/feed-cache") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer ${grant.token}")
                    setBody("""{"feeds":[{"url":"https://example.com/feed"}]}""")
                }.expect(HttpStatusCode.UpgradeRequired)
            client
                .postApi("/api/v1/feed-cache", FeedCacheRequest(emptyList()), grant.token)
                .expect(HttpStatusCode.BadRequest)
            client
                .postApi(
                    "/api/v1/feed-cache",
                    FeedCacheRequest(
                        List(FeedCacheConfig.DEFAULT_MAX_QUERIES + 1) { FeedCacheQuery("https://example.com/f") },
                    ),
                    grant.token,
                ).expect(HttpStatusCode.BadRequest)
            client
                .postApi(
                    "/api/v1/feed-cache",
                    FeedCacheRequest(listOf(FeedCacheQuery("ftp://example.com/feed"))),
                    grant.token,
                ).expect(HttpStatusCode.BadRequest)
            client
                .postApi(
                    "/api/v1/feed-cache",
                    FeedCacheRequest(listOf(FeedCacheQuery("https://example.com/feed", "not-hex"))),
                    grant.token,
                ).expect(HttpStatusCode.BadRequest)
        }

    @Test fun `refresh enumerates subscribed feeds and sends synchronized basic auth`() =
        authServices { f ->
            val account = f.account()
            val grant = f.grant(account)
            val principal = f.principal(grant)
            val writer = WriterFixture(store = f.store, principal = principal).apply { now = f.now }
            writer.push(
                writer.change(
                    podcastA,
                    mapOf(
                        "feedUrl" to JsonPrimitive(FEED),
                        "subscribed" to JsonPrimitive(true),
                        "subscribedAt" to JsonPrimitive(1L),
                        "sourceType" to JsonPrimitive("RSS"),
                        "needsCredentials" to JsonPrimitive(false),
                        "credentialOrigin" to JsonPrimitive("https://example.com"),
                        "auth" to
                            JsonObject(
                                mapOf("u" to JsonPrimitive("user"), "p" to JsonPrimitive("pass")),
                            ),
                    ),
                ),
            )
            val fetcher =
                RecordingFetcher {
                    ServerFeedFetcher.ServerFeedFetch.Changed(
                        body = BODY.toByteArray(),
                        finalUrl = "$FEED?final=1",
                        etag = "etag-1",
                        lastModified = "Mon, 01 Jan 2026 00:00:00 GMT",
                    )
                }
            val service = FeedCacheService(f.store, f.store, f.inputs, fetcher)
            service.refreshAccount(account.id)

            assertEquals(1, fetcher.calls.size)
            val call = fetcher.calls.single()
            assertEquals(FEED, call.url)
            assertEquals("Basic dXNlcjpwYXNz", call.authorization)
            assertNull(call.etag)
            assertNull(call.lastModified)
            val stored = f.store.read(account.id) { feedCache(listOf(FEED)) }.single()
            assertEquals(BODY, stored.body.toString(Charsets.UTF_8))
            assertEquals(sha256Hex(BODY.toByteArray()), stored.sha256)
            assertEquals("$FEED?final=1", stored.finalUrl)
            assertEquals("etag-1", stored.etag)
            assertEquals("Mon, 01 Jan 2026 00:00:00 GMT", stored.lastModified)

            // A 304 keeps the body, refreshes checkedAt and replays the stored validators.
            f.now += 60_000
            fetcher.result = { ServerFeedFetcher.ServerFeedFetch.Unchanged }
            service.refreshAccount(account.id)
            assertEquals(2, fetcher.calls.size)
            assertEquals("etag-1", fetcher.calls[1].etag)
            assertEquals(stored.lastModified, fetcher.calls[1].lastModified)
            val refreshed = f.store.read(account.id) { feedCache(listOf(FEED)) }.single()
            assertEquals(f.now, refreshed.checkedAtMs)
            assertContentEquals(stored.body, refreshed.body)
        }

    @Test fun `refresh skips unsubscribed and credential-less feeds and supports youtube atom`() =
        authServices { f ->
            val account = f.account()
            val grant = f.grant(account)
            val principal = f.principal(grant)
            val writer = WriterFixture(store = f.store, principal = principal).apply { now = f.now }
            writer.push(
                writer.change(
                    podcastA,
                    mapOf(
                        "feedUrl" to JsonPrimitive(YOUTUBE_FEED),
                        "subscribed" to JsonPrimitive(true),
                        "subscribedAt" to JsonPrimitive(1L),
                        "sourceType" to JsonPrimitive("YOUTUBE_CHANNEL"),
                        "youtubeChannelId" to JsonPrimitive(YOUTUBE_FEED.substringAfter("channel_id=")),
                    ),
                ),
            )
            writer.push(
                writer.change(
                    key(SyncCollection.PODCAST, B),
                    mapOf(
                        "feedUrl" to JsonPrimitive("https://example.com/private"),
                        "subscribed" to JsonPrimitive(true),
                        "subscribedAt" to JsonPrimitive(1L),
                        "sourceType" to JsonPrimitive("RSS"),
                        "needsCredentials" to JsonPrimitive(true),
                    ),
                ),
            )
            writer.push(
                writer.change(
                    key(SyncCollection.PODCAST, C),
                    mapOf(
                        "feedUrl" to JsonPrimitive("https://example.com/gone"),
                        "subscribed" to JsonPrimitive(false),
                        "subscribedAt" to JsonPrimitive(1L),
                        "sourceType" to JsonPrimitive("RSS"),
                    ),
                ),
            )
            val service = FeedCacheService(f.store, f.store, f.inputs, RecordingFetcher())
            assertEquals(
                listOf(
                    YOUTUBE_FEED.replace("channel_id=UC", "playlist_id=UULF"),
                    YOUTUBE_FEED,
                ),
                service.enumerate(account.id).map {
                    it.url
                },
            )
        }

    @Test fun `an unshared password never replaces a private document with a guest feed`() =
        authServices { fixture ->
            val account = fixture.account()
            val writer = WriterFixture(fixture.store, fixture.principal(fixture.grant(account)))
            writer.push(writer.podcast(url = FEED))
            writer.push(
                writer.write(podcastA, "credentialOrigin", JsonPrimitive("https://example.com"), writer.clock(1)),
            )
            val publisher = RecordingFetcher()
            FeedCacheService(fixture.store, fixture.store, fixture.inputs, publisher).refreshAccount(account.id)
            assertTrue(publisher.calls.isEmpty())
        }

    @Test fun `podcast changes invalidate the cached body and unrelated changes do not`() =
        authServices { f ->
            val account = f.account()
            val grant = f.grant(account)
            val principal = f.principal(grant)
            val writer = WriterFixture(store = f.store, principal = principal).apply { now = f.now }
            writer.push(writer.podcast(url = FEED))
            val body = "cached".toByteArray()
            f.store.write(account.id) {
                putFeedCache(entry(FEED, sha256 = sha256Hex(body), body = body, checkedAtMs = f.now))
            }

            writer.push(writer.group())
            assertEquals(1, f.store.read(account.id) { feedCache(listOf(FEED)).size })

            writer.push(writer.write(podcastA, "feedUrl", JsonPrimitive(FEED_REPLACEMENT), writer.clock(1)))
            assertTrue(f.store.read(account.id) { feedCache(listOf(FEED)) }.isEmpty())
        }

    @Test fun `podcast metadata changes keep the cached body and validators`() =
        authServices { f ->
            val account = f.account()
            val grant = f.grant(account)
            val principal = f.principal(grant)
            val writer = WriterFixture(store = f.store, principal = principal).apply { now = f.now }
            writer.push(writer.podcast(url = FEED))
            val body = "cached".toByteArray()
            f.store.write(account.id) {
                putFeedCache(
                    entry(FEED, sha256 = sha256Hex(body), body = body, checkedAtMs = f.now, etag = "etag-1"),
                )
            }

            // A metadata-only edit and an identical re-push change no polling input.
            writer.push(writer.write(podcastA, "title", JsonPrimitive("Renamed"), writer.clock(1)))
            writer.push(writer.write(podcastA, "title", JsonPrimitive("Renamed"), writer.clock(1)))

            val stored = f.store.read(account.id) { feedCache(listOf(FEED)).single() }
            assertContentEquals(body, stored.body)
            assertEquals("etag-1", stored.etag)
            assertEquals(f.now, stored.checkedAtMs)
        }

    @Test fun `a poll finishing after unsubscribe cannot restore the cached feed`() =
        authServices { fixture ->
            val account = fixture.account()
            val writer = WriterFixture(fixture.store, fixture.principal(fixture.grant(account)))
            writer.push(writer.podcast(url = FEED))
            val publisher =
                RecordingFetcher {
                    writer.push(writer.write(podcastA, "subscribed", JsonPrimitive(false), writer.clock(1)))
                    ServerFeedFetcher.ServerFeedFetch.Changed(BODY.encodeToByteArray(), FEED, null, null)
                }
            FeedCacheService(fixture.store, fixture.store, fixture.inputs, publisher).refreshAccount(account.id)
            assertEquals(1, publisher.calls.size)
            assertTrue(fixture.store.read(account.id) { feedCache(listOf(FEED)).isEmpty() })
        }

    @Test fun `a poll finishing after the feed URL changes cannot restore the old cache`() =
        authServices { fixture ->
            val account = fixture.account()
            val writer = WriterFixture(fixture.store, fixture.principal(fixture.grant(account)))
            writer.push(writer.podcast(url = FEED))
            val publisher =
                RecordingFetcher {
                    writer.push(
                        writer.write(podcastA, "feedUrl", JsonPrimitive(FEED_REPLACEMENT), writer.clock(1)),
                    )
                    ServerFeedFetcher.ServerFeedFetch.Changed(BODY.encodeToByteArray(), FEED, null, null)
                }
            FeedCacheService(fixture.store, fixture.store, fixture.inputs, publisher).refreshAccount(account.id)
            assertEquals(1, publisher.calls.size)
            assertTrue(fixture.store.read(account.id) { feedCache(listOf(FEED)).isEmpty() })
            assertTrue(fixture.store.read(account.id) { feedCache(listOf(FEED_REPLACEMENT)).isEmpty() })
        }

    @Test fun `one failing account does not stop later accounts from refreshing`() =
        authServices { f ->
            val failing = f.account("alice")
            val healthy = f.account("bob")
            assertTrue(failing.id < healthy.id)
            val failingWriter = WriterFixture(f.store, f.principal(f.grant(failing))).apply { now = f.now }
            failingWriter.push(failingWriter.podcast(url = FEED))
            val healthyWriter = WriterFixture(f.store, f.principal(f.grant(healthy))).apply { now = f.now }
            healthyWriter.push(healthyWriter.podcast(url = FEED_REPLACEMENT))
            val fetcher =
                RecordingFetcher {
                    ServerFeedFetcher.ServerFeedFetch.Changed(BODY.toByteArray(), it, null, null)
                }
            val store = FailingAccountStore(f.store, failing.id)
            FeedCacheService(store, f.store, f.inputs, fetcher).refreshAll()
            assertEquals(setOf(FEED_REPLACEMENT), fetcher.calls.map { it.url }.toSet())
            assertTrue(fetcher.calls.none { it.url == FEED })
        }

    @Test fun `library reset invalidates the account feed cache`() =
        authServices { f ->
            val account = f.account()
            val grant = f.grant(account)
            val principal = f.principal(grant)
            val writer = WriterFixture(store = f.store, principal = principal).apply { now = f.now }
            writer.push(writer.podcast(url = FEED))
            val body = "cached".toByteArray()
            f.store.write(account.id) {
                putFeedCache(entry(FEED, sha256 = sha256Hex(body), body = body, checkedAtMs = f.now))
            }
            val cursor = f.store.read(account.id) { this.account.cursor.wire() }
            f.services.records.resetLibrary(principal, AccountResetRequest("DELETE", "library", cursor))
            assertTrue(f.store.read(account.id) { feedCache(listOf(FEED)) }.isEmpty())
        }

    @Test fun `response and entry bounds are enforced`() =
        authServices { f ->
            val account = f.account()
            val grant = f.grant(account)
            val principal = f.principal(grant)
            val writer = WriterFixture(store = f.store, principal = principal).apply { now = f.now }
            writer.push(writer.podcast(url = FEED))
            writer.push(writer.podcast(id = B, url = FEED_REPLACEMENT))
            val body = "123456".toByteArray()
            f.store.write(account.id) {
                putFeedCache(entry(FEED, sha256 = sha256Hex(body), body = body, checkedAtMs = f.now))
            }
            val service =
                FeedCacheService(
                    f.store,
                    f.store,
                    f.inputs,
                    RecordingFetcher(),
                    FeedCacheConfig(maxResponseBodyBytes = 4),
                )
            val tooLarge =
                service.batch(principal, FeedCacheRequest(listOf(FeedCacheQuery(FEED, "0".repeat(64)))))
            assertTrue(tooLarge.feeds.isEmpty())
            // A matching sha omits the body, so it still fits the response bound.
            val matching =
                service.batch(principal, FeedCacheRequest(listOf(FeedCacheQuery(FEED, sha256Hex(body)))))
            assertEquals(1, matching.feeds.size)
            assertNull(matching.feeds.single().bodyBase64)

            val oversized =
                FeedCacheService(
                    f.store,
                    f.store,
                    f.inputs,
                    RecordingFetcher {
                        ServerFeedFetcher.ServerFeedFetch.Changed("12345".toByteArray(), FEED, null, null)
                    },
                    FeedCacheConfig(maxEntryBytes = 4),
                )
            oversized.refreshAccount(account.id)
            assertTrue(f.store.read(account.id) { feedCache(listOf(FEED_REPLACEMENT)) }.isEmpty())
        }

    @Test fun `the oldest cached entries are evicted at the account bound`() =
        runBlocking {
            val dir = tempDataDir()
            SqliteSyncStore(dir, feedCacheMaxEntryBytes = 4, feedCacheMaxAccountBytes = 10).use { store ->
                accountFixture(dir)
                val writer = WriterFixture(store = store)
                writer.seed(podcastA, writer.state(podcastA, "subscribed", JsonPrimitive(true)))

                suspend fun put(
                    url: String,
                    checkedAtMs: Long,
                ) {
                    store.write(1) {
                        putFeedCache(
                            entry(url, sha256 = "0".repeat(64), body = "1234".toByteArray(), checkedAtMs = checkedAtMs),
                        )
                    }
                }
                put("https://a/1", 1)
                put("https://a/2", 2)
                put("https://a/3", 3)
                val urls =
                    store.read(1) {
                        feedCache(listOf("https://a/1", "https://a/2", "https://a/3")).map { it.url }
                    }
                assertEquals(listOf("https://a/2", "https://a/3"), urls.sorted())
            }
        }

    private companion object {
        const val FEED = "https://example.com/feed"
        const val FEED_REPLACEMENT = "https://example.com/feed2"
        const val YOUTUBE_FEED = "https://www.youtube.com/feeds/videos.xml?channel_id=UCBR8-60-B28hp2BmDPdntcQ"
        const val BODY = "feed body"
    }
}

private fun entry(
    url: String,
    finalUrl: String = url,
    sha256: String,
    body: ByteArray,
    checkedAtMs: Long,
    etag: String? = null,
    lastModified: String? = null,
) = StoredFeedCache(url, finalUrl, sha256, etag, lastModified, body, checkedAtMs, A)

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        it.toUByte().toString(16).padStart(2, '0')
    }

private class RecordingFetcher(
    var result: suspend (String) -> ServerFeedFetcher.ServerFeedFetch? = { null },
) : ServerFeedFetcher {
    val calls = mutableListOf<FetchCall>()

    override suspend fun fetch(
        url: String,
        authorization: String?,
        etag: String?,
        lastModified: String?,
    ): ServerFeedFetcher.ServerFeedFetch? {
        calls += FetchCall(url, authorization, etag, lastModified)
        return result(url)
    }
}

private class FailingAccountStore(
    private val delegate: SyncStore,
    private val failingAccountId: Long,
) : SyncStore by delegate {
    override suspend fun <T> read(
        accountId: Long,
        block: SyncRead.() -> T,
    ): T {
        if (accountId == failingAccountId) error("account store unavailable")
        return delegate.read(accountId, block)
    }
}

private data class FetchCall(
    val url: String,
    val authorization: String?,
    val etag: String?,
    val lastModified: String?,
)
