// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.dto.FeedCacheQuery
import ch.lkmc.neutrodyne.sync.protocol.dto.FeedCacheRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.FeedCacheResponse
import io.ktor.http.HttpStatusCode
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

    private companion object {
        const val PATH = "/api/v1/feed-cache"
    }
}
