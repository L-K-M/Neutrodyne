// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.network

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.HttpClientKind
import ch.lkmc.neutrodyne.core.common.PublisherAuthPolicy
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.Test
import kotlin.test.assertEquals

class AnonymousDownloadTest {
    @Test fun anonymousDownloadRedirectsAndRepeatedRequestsNeverBorrowCredentials() =
        runBlocking {
            MockWebServer().use { first ->
                MockWebServer().use { second ->
                    first.start()
                    second.start()
                    var lookups = 0
                    val clients =
                        newHttpClients(
                            newNetworkClients(
                                CredentialLookup { origin ->
                                    lookups++
                                    "Basic stored-${origin.port}"
                                },
                            ),
                        )
                    try {
                        val requests = mutableListOf<mockwebserver3.RecordedRequest>()
                        repeat(2) { attempt ->
                            first.enqueue(mockResponse(302, "", "Location" to "/same"))
                            first.enqueue(mockResponse(302, "", "Location" to second.url("/other").toString()))
                            second.enqueue(mockResponse(206, "bcd", "Content-Range" to "bytes 1-3/6"))
                            val response =
                                clients.client(HttpClientKind.DOWNLOAD).get(first.url("/audio").toString()) {
                                    publisherAuth(PublisherAuthPolicy.ANONYMOUS)
                                    header("Range", "bytes=1-3")
                                    header("X-Caller", "untouched")
                                    if (attempt == 1) header("Authorization", "Bearer caller")
                                }
                            assertEquals("bcd", response.bodyAsText())
                            requests += first.takeRequest()
                            requests += first.takeRequest()
                            requests += second.takeRequest()
                        }
                        assertEquals(List(6) { "bytes=1-3" }, requests.map { it.headers["Range"] })
                        assertEquals(List(6) { "untouched" }, requests.map { it.headers["X-Caller"] })
                        assertEquals(List(6) { "identity" }, requests.map { it.headers["Accept-Encoding"] })
                        assertEquals(List<String?>(6) { null }, requests.map { it.headers["Authorization"] })
                        assertEquals(
                            List<String?>(6) { null },
                            requests.map { it.headers[PublisherAuthPolicy.HEADER] },
                        )
                        assertEquals(0, lookups)
                        first.enqueue(mockResponse())
                        clients.client(HttpClientKind.DOWNLOAD).get(first.url("/private").toString()).bodyAsText()
                        assertEquals("Basic stored-${first.port}", first.takeRequest().headers["Authorization"])
                        assertEquals(1, lookups)
                    } finally {
                        clients.closeAll()
                    }
                }
            }
        }
}
