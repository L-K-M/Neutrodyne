// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.network.okhttp

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import mockwebserver3.MockWebServer
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.Request
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DirectoryAuthTest {
    @get:Rule val rule = MockWebServerRule()

    @Test fun directoryHeadersAreOriginScopedAndMarkerNeverReachesTheWire() {
        val first = rule.server
        val second = MockWebServer()
        second.start()
        try {
            first.enqueue(mockResponse(302, "", "Location" to second.url("/other").toString()))
            second.enqueue(mockResponse())
            val clients = newNetworkClients(CredentialLookup { "Basic publisher" })
            val origin = "http://${first.hostName}:${first.port}"
            val request =
                Request
                    .Builder()
                    .url(first.url("/lookup"))
                    .header("X-Neutrodyne-Directory-Origin", origin)
                    .header("X-Auth-Key", "key")
                    .header("X-Auth-Date", "123")
                    .header("Authorization", "sha1")
                    .build()
            clients.api
                .newCall(request)
                .execute()
                .close()
            val start = first.takeRequest()
            val end = second.takeRequest()
            assertEquals("key", start.headers["X-Auth-Key"])
            assertEquals("sha1", start.headers["Authorization"])
            assertNull(start.headers["X-Neutrodyne-Directory-Origin"])
            assertNull(end.headers["X-Neutrodyne-Directory-Origin"])
            assertNull(end.headers["X-Auth-Key"])
            assertNull(end.headers["X-Auth-Date"])
            assertNull(end.headers["Authorization"])
        } finally {
            second.close()
        }
    }

    @Test fun directoryRequestNeverBorrowsStoredPublisherCredentials() {
        val server = rule.server
        server.enqueue(mockResponse())
        // The lookup answers every origin with a publisher credential; a directory request must
        // not pick it up (03: directory requests never borrow a publisher's stored credentials).
        val clients = newNetworkClients(CredentialLookup { "Basic publisher" })
        val origin = "http://${server.hostName}:${server.port}"
        val request =
            Request
                .Builder()
                .url(server.url("/search"))
                .header("X-Neutrodyne-Directory-Origin", origin)
                .build()
        clients.api
            .newCall(request)
            .execute()
            .close()
        val wire = server.takeRequest()
        assertNull(wire.headers["Authorization"])
        assertNull(wire.headers["X-Neutrodyne-Directory-Origin"])
    }
}
