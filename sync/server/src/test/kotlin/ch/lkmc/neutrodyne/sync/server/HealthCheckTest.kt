// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `/healthz` and `/readyz` of the M0b skeleton (10 Account and health endpoints; AC16). */
class HealthCheckTest {
    @Test
    fun `healthz answers 200 ok while the process serves`() =
        testApplication {
            application {
                installTestModule(loadValidConfig())
            }
            val response = client.get(HealthRoutes.HEALTH_PATH)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(HealthRoutes.BODY_OK, response.bodyAsText())
        }

    @Test
    fun `probe succeeds against the real CIO server over HTTP 1_1`() =
        authServices { f ->
            val server =
                embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                    f.install(this)
                }.start(wait = false)
            try {
                val port =
                    server.engine
                        .resolvedConnectors()
                        .single()
                        .port
                // Regression: with the default HTTP/2 preference the probe's h2c Upgrade
                // header makes the CIO body read hang and the probe times out (exit 1).
                assertEquals(ExitCodes.OK, HealthCheck.check(mapOf(ServerEnv.LISTEN to "127.0.0.1:$port")))
            } finally {
                server.stop(0, 1000)
            }
        }

    @Test
    fun `readyz answers 200 ok with a writable data directory`() =
        testApplication {
            application {
                installTestModule(loadValidConfig())
            }
            val response = client.get(HealthRoutes.READY_PATH)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(HealthRoutes.BODY_OK, response.bodyAsText())
        }

    @Test
    fun `readiness fails below the free-space floor`() =
        runBlocking {
            val routes = HealthRoutes(tempDataDir(), usableSpace = { _ -> HealthRoutes.MIN_FREE_DISK_BYTES - 1 })
            assertFalse(routes.isReady())
        }

    @Test
    fun `readiness fails when the data directory is missing`() =
        runBlocking {
            val missing = tempDataDir().resolve("missing")
            val routes = HealthRoutes(missing)
            assertFalse(routes.isReady())
        }

    @Test
    fun `readiness holds at the free-space floor`() =
        runBlocking {
            val routes = HealthRoutes(tempDataDir(), usableSpace = { _ -> HealthRoutes.MIN_FREE_DISK_BYTES })
            assertTrue(routes.isReady())
        }

    @Test
    fun `a readyz burst shares one write probe for a short interval`() =
        runBlocking {
            var probes = 0
            val routes =
                HealthRoutes(tempDataDir(), writeProbe = {
                    probes++
                    true
                })
            repeat(10) { assertTrue(routes.isReady()) }
            assertEquals(1, probes)
        }

    @Test
    fun `readiness fails on a database write failure`() =
        runBlocking {
            assertFalse(HealthRoutes(tempDataDir(), writeProbe = { false }).isReady())
            assertFalse(HealthRoutes(tempDataDir(), writeProbe = { error("write failed") }).isReady())
        }
}
