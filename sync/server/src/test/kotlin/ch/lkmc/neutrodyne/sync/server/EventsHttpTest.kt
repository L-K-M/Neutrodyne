// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventsHttpTest {
    @Test fun `CIO streams retry heartbeat changes and revocation without gzip`() =
        authServices { f ->
            val grant = f.grant(f.account())
            val server =
                io.ktor.server.engine
                    .embeddedServer(io.ktor.server.cio.CIO, port = 0, host = "127.0.0.1") {
                        f.install(this)
                    }.start(wait = false)
            try {
                val port =
                    server.engine
                        .resolvedConnectors()
                        .single()
                        .port
                val connection =
                    java.net
                        .URI("http://127.0.0.1:$port/api/v1/events?hb=1")
                        .toURL()
                        .openConnection()
                        as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 35_000
                connection.setRequestProperty(RequestGuards.PROTOCOL_HEADER, "1")
                connection.setRequestProperty(HttpHeaders.Authorization, "Bearer ${grant.token}")
                connection.setRequestProperty(HttpHeaders.AcceptEncoding, "gzip")
                try {
                    assertEquals(200, connection.responseCode)
                    assertTrue(connection.contentType.startsWith("text/event-stream"))
                    assertEquals("no-store", connection.getHeaderField(HttpHeaders.CacheControl))
                    assertEquals("no", connection.getHeaderField("X-Accel-Buffering"))
                    assertNull(connection.getHeaderField(HttpHeaders.ContentEncoding))
                    connection.inputStream.bufferedReader().use { body ->
                        assertEquals("retry: 10000", body.readLine())
                        assertEquals("", body.readLine())
                        assertEquals(": hb", body.readLine())
                        assertEquals("", body.readLine())
                        val change = WriterFixture().apply { now = f.now }.group()
                        val pushed =
                            f.services.records.push(
                                f.principal(grant),
                                ch.lkmc.neutrodyne.sync.protocol.dto
                                    .SyncRequest(changes = listOf(change)),
                            )
                        assertEquals("event: changed", body.readLine())
                        assertTrue(body.readLine().contains(pushed.page.cursor))
                        assertEquals("", body.readLine())
                        f.services.tokens.logout(f.principal(grant))
                        assertNull(body.readLine())
                    }
                    assertEquals(0, f.events.streamCount())
                } finally {
                    connection.disconnect()
                }
            } finally {
                server.stop(0, 1000)
            }
        }

    @Test fun `SSE caps fail before response headers and invalid hb is rejected`() =
        authApplication { f ->
            val grant = f.grant(f.account())
            val p = f.principal(grant)
            val a = f.events.open(p)
            val b = f.events.open(p)
            val limited = client.get("/api/v1/events") { api(grant.token) }
            limited.expect(HttpStatusCode.TooManyRequests)
            assertEquals("10", limited.headers[HttpHeaders.RetryAfter])
            assertFalse(limited.headers[HttpHeaders.ContentType].orEmpty().contains("event-stream"))
            client.get("/api/v1/events?hb=bad") { api(grant.token) }.expect(HttpStatusCode.BadRequest)
            a.close()
            b.close()
        }
}
