// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObservabilityTest {
    @Test fun `metrics is absent from public listener and present only on separate listener`() =
        authApplication { f ->
            client.get("/metrics").expect(io.ktor.http.HttpStatusCode.NotFound)
            val port = ServerSocket(0).use { it.localPort }
            f.services.metrics.listen(ListenAddress("127.0.0.1", port)).use {
                HttpClient(CIO).use { metricsClient ->
                    val response = metricsClient.get("http://127.0.0.1:$port/metrics")
                    assertEquals(200, response.status.value)
                    assertTrue(response.bodyAsText().contains("neutrodyne_accounts 0"))
                    assertTrue(response.headers["Content-Type"]!!.contains("version=0.0.4"))
                }
            }
        }

    @Test fun `metrics counters gauges and backup GC hooks use bounded labels`() =
        authServices { f ->
            f.account()
            val metrics = f.services.metrics
            metrics.request("/api/v1/devices/{id}", 404)
            metrics.changes(listOf("applied", "applied", "stale", "merged", "rejected"))
            metrics.returned(17)
            metrics.streamOpened()
            metrics.backupFinished(f.now - 12_000, BackupResult.SUCCESS)
            metrics.gcPurged(9)
            var text = metrics.prometheus(f.now)
            assertTrue(text.contains("route=\"/api/v1/devices/{id}\",status=\"404\"} 1"))
            assertTrue(text.contains("status=\"applied\"} 2"))
            assertTrue(text.contains("neutrodyne_records_returned_total 17"))
            assertTrue(text.contains("neutrodyne_sse_streams 1"))
            assertTrue(text.contains("neutrodyne_accounts 1"))
            assertTrue(text.contains("neutrodyne_last_backup_age_seconds 12"))
            assertTrue(text.contains("neutrodyne_last_backup_success 1"))
            assertTrue(text.contains("neutrodyne_gc_purges_total 9"))
            metrics.streamClosed()
            metrics.backupFinished(f.now, BackupResult.FAILURE)
            text = metrics.prometheus(f.now)
            assertTrue(text.contains("neutrodyne_sse_streams 0"))
            assertTrue(text.contains("neutrodyne_last_backup_success 0"))
        }

    @Test fun `HealthCheck exact process exit is zero only on readiness 200 and never follows redirects`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var status = 200
        server.createContext("/readyz") { exchange ->
            exchange.responseHeaders.add("Location", "/healthz")
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        try {
            val environment = mapOf(ServerEnv.LISTEN to "0.0.0.0:${server.address.port}")
            for (code in listOf(200, 503, 302)) {
                status = code
                val process =
                    ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("neutrodyne.server.testClasspath"),
                        HealthCheck::class.java.name,
                    )
                process.environment().putAll(environment)
                val child = process.start()
                assertTrue(child.waitFor(10, TimeUnit.SECONDS))
                assertEquals(if (code == 200) 0 else 1, child.exitValue())
            }
            assertEquals(1, HealthCheck.check(mapOf(ServerEnv.LISTEN to "invalid")))
        } finally {
            server.stop(0)
        }
        assertEquals(1, HealthCheck.check(mapOf(ServerEnv.LISTEN to "127.0.0.1:${server.address.port}")))
    }

    @Test fun `audit records login success and failure with IP and enforces admin web session`() =
        authServices { f ->
            val owner = f.account("admin", "correct password", AccountRole.ADMIN)
            val raw = f.services.auth.loginWeb("admin", "correct password", null, "192.0.2.9")
            runCatching { f.services.auth.loginWeb("missing", "incorrect password", null, "192.0.2.10") }
            val actor = f.services.tokens.authenticate(raw, TokenKind.WEB_SESSION)
            val entries = f.services.audit.recent(actor)
            assertTrue(entries.any { it.event == "login" && it.ip == "192.0.2.9" })
            assertTrue(entries.any { it.event == "login_failed" && it.ip == "192.0.2.10" })
            val grant = f.grant(owner)
            assertTrue(runCatching { f.services.audit.recent(f.principal(grant)) }.isFailure)
            assertFalse(entries.any { it.event.contains("correct password") })
        }
}
