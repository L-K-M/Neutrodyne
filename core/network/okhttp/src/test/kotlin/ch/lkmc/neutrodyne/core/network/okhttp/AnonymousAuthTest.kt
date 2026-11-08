// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.common.PublisherAuthPolicy
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnonymousAuthTest {
    @Test fun anonymousNativeRedirectsAndRetryNeverResolveTheProvider() {
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                first.start()
                second.start()
                first.enqueue(mockResponse(302, "", "Location" to "/same"))
                first.enqueue(mockResponse(302, "", "Location" to second.url("/other").toString()))
                second.enqueue(mockResponse(408, ""))
                second.enqueue(mockResponse())
                val clients = NetworkClients(newCoreClients(), AuthInterceptor { error("Anonymous provider access") })
                clients.media
                    .newCall(
                        Request
                            .Builder()
                            .url(first.url("/audio"))
                            .header(PublisherAuthPolicy.HEADER, PublisherAuthPolicy.ANONYMOUS.name)
                            .header("Authorization", "Bearer caller")
                            .header("Range", "bytes=1-3")
                            .header("X-Caller", "untouched")
                            .build(),
                    ).execute()
                    .use { assertEquals(200, it.code) }
                val requests =
                    listOf(first.takeRequest(), first.takeRequest(), second.takeRequest(), second.takeRequest())
                for (request in requests) {
                    assertNull(request.headers["Authorization"])
                    assertNull(request.headers[PublisherAuthPolicy.HEADER])
                    assertEquals("bytes=1-3", request.headers["Range"])
                    assertEquals("identity", request.headers["Accept-Encoding"])
                    assertEquals("untouched", request.headers["X-Caller"])
                }
            }
        }
    }

    @Test fun ordinaryMediaAndDownloadStillUseEachOriginsCredentials() {
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                first.start()
                second.start()
                val clients = newNetworkClients(CredentialLookup { "Basic stored-${it.port}" })
                for (kind in listOf(HttpClientKind.MEDIA, HttpClientKind.DOWNLOAD)) {
                    first.enqueue(mockResponse(302, "", "Location" to second.url("/enclosure").toString()))
                    second.enqueue(mockResponse())
                    clients[kind].newCall(Request.Builder().url(first.url("/private")).build()).execute().close()
                    assertEquals("Basic stored-${first.port}", first.takeRequest().headers["Authorization"])
                    assertEquals("Basic stored-${second.port}", second.takeRequest().headers["Authorization"])
                }
            }
        }
    }
}
