// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.PROTOCOL_VERSION
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `GET /.well-known/neutrodyne-sync` (10 Protocol › Discovery). */
class WellKnownRoutesTest {

    @Test
    fun `the discovery document matches the protocol`() = testApplication {
        application {
            installTestModule(loadValidConfig())
        }
        val response = client.get(WellKnownRoutes.WELL_KNOWN_PATH)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("application/json", response.headers["Content-Type"])

        val document = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(WellKnownRoutes.SERVER_DISPLAY_NAME, document.getValue("name").jsonPrimitive.content)
        assertEquals(TEST_SERVER_VERSION, document.getValue("serverVersion").jsonPrimitive.content)

        val protocol = document.getValue("protocol").jsonObject
        assertEquals(PROTOCOL_VERSION, protocol.getValue("min").jsonPrimitive.int)
        assertEquals(PROTOCOL_VERSION, protocol.getValue("max").jsonPrimitive.int)

        // The sse, link and password feature flags arrive with MS1 (10 Discovery).
        assertTrue(document.getValue("features").jsonArray.isEmpty())
        assertEquals(DiscoveryNumbers.MAX_BATCH, document.getValue("maxBatch").jsonPrimitive.int)
        assertEquals(DiscoveryNumbers.MAX_SKEW_MS, document.getValue("maxSkewMs").jsonPrimitive.long)
        assertEquals("2026-10-06T10:15:30.250Z", document.getValue("serverTime").jsonPrimitive.content)
    }
}
