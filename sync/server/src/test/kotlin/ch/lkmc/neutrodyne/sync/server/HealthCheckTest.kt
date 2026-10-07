// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
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
    fun `readiness fails below the free-space floor`() {
        val routes = HealthRoutes(tempDataDir(), usableSpace = { _ -> HealthRoutes.MIN_FREE_DISK_BYTES - 1 })
        assertFalse(routes.isReady())
    }

    @Test
    fun `readiness fails when the data directory is missing`() {
        val missing = Files.createTempDirectory("neutrodyne-server-test").resolve("missing")
        val routes = HealthRoutes(missing)
        assertFalse(routes.isReady())
    }

    @Test
    fun `readiness holds at the free-space floor`() {
        val routes = HealthRoutes(tempDataDir(), usableSpace = { _ -> HealthRoutes.MIN_FREE_DISK_BYTES })
        assertTrue(routes.isReady())
    }
}
