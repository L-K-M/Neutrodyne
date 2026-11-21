// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import ch.lkmc.neutrodyne.sync.protocol.dto.FeedCacheQuery
import ch.lkmc.neutrodyne.sync.protocol.dto.FeedCacheRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.FeedCacheResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedCacheBatchHttpTest {
    @Test
    fun accountReceivesOnlyItsOwnUpdatesInOneResponse() =
        authApplication { fixture ->
            val alice = fixture.account()
            val bob = fixture.account("bob")
            val aliceGrant = fixture.grant(alice)
            val bobGrant = fixture.grant(bob)
            val writer = WriterFixture(fixture.store, fixture.principal(aliceGrant))
            val urls = listOf("https://a.example/rss", "https://b.example/private?t=opaque")
            writer.push(writer.podcast(A, urls.first()), writer.podcast(B, urls.last()))
            val bytes = "<rss><channel><title>New episode</title></channel></rss>".encodeToByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
            val fetched =
                java.util.concurrent.ConcurrentHashMap
                    .newKeySet<String>()
            val publisher =
                ServerFeedFetcher { url, _, _, _ ->
                    fetched.add(url)
                    ServerFeedFetcher.ServerFeedFetch.Changed(bytes, url, null, null)
                }
            FeedCacheService(fixture.store, fixture.store, fixture.inputs, publisher).refreshAll()
            assertEquals(urls.toSet(), fetched)
            val request = FeedCacheRequest(listOf(FeedCacheQuery(urls.first(), hash), FeedCacheQuery(urls.last())))
            client.postApi(PATH, request).expect(HttpStatusCode.Unauthorized)
            assertTrue(
                client
                    .postApi(PATH, request, bobGrant.token)
                    .decoded<FeedCacheResponse>()
                    .feeds
                    .isEmpty(),
            )
            val updates =
                client
                    .postApi(
                        PATH,
                        request,
                        aliceGrant.token,
                    ).decoded<FeedCacheResponse>()
                    .feeds
                    .associateBy { it.url }
            assertEquals(urls.toSet(), updates.keys)
            assertNull(updates.getValue(urls.first()).bodyBase64)
            assertEquals(Base64.getEncoder().encodeToString(bytes), updates.getValue(urls.last()).bodyBase64)
        }

    @Test
    fun replacingTheCredentialOriginStampWithdrawsThePreviouslyAuthorizedDocument() =
        authApplication { fixture ->
            val account = fixture.account()
            val grant = fixture.grant(account)
            val writer = WriterFixture(fixture.store, fixture.principal(grant))
            val url = "https://publisher.example/feed"
            val origin = JsonPrimitive("https://publisher.example")
            val key = key(SyncCollection.PODCAST, A)
            writer.push(writer.podcast(A, url))
            writer.push(
                writer.change(
                    key,
                    mapOf(
                        "credentialOrigin" to origin,
                        "auth" to JsonObject(mapOf("u" to JsonPrimitive("listener"), "p" to JsonPrimitive("example"))),
                    ),
                    writer.clock(1),
                ),
            )
            val service =
                FeedCacheService(
                    fixture.store,
                    fixture.store,
                    fixture.inputs,
                    ServerFeedFetcher { _, auth, _, _ ->
                        checkNotNull(auth)
                        ServerFeedFetcher.ServerFeedFetch.Changed("<rss/>".encodeToByteArray(), url, null, null)
                    },
                )
            val request = FeedCacheRequest(listOf(FeedCacheQuery(url)))
            service.refreshAll()
            assertEquals(
                1,
                client
                    .postApi(PATH, request, grant.token)
                    .decoded<FeedCacheResponse>()
                    .feeds.size,
            )

            writer.push(writer.change(key, mapOf("credentialOrigin" to origin), writer.clock(2)))
            assertTrue(service.enumerate(account.id).isEmpty())
            assertTrue(
                client
                    .postApi(PATH, request, grant.token)
                    .decoded<FeedCacheResponse>()
                    .feeds
                    .isEmpty(),
            )
        }

    private companion object {
        const val PATH = "/api/v1/feed-cache"
    }
}
