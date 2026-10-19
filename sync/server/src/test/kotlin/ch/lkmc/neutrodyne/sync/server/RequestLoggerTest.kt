// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestLoggerTest {
    @Test fun `text and JSON log only fixed templates status and sanitized identifiers`() {
        for (format in LogFormat.entries) {
            val output = mutableListOf<String>()
            testApplication {
                application {
                    ServerModule(
                        loadValidConfig().copy(dataDir = tempDataDir(), logFormat = format),
                        TEST_SERVER_VERSION,
                        requestLog = output::add,
                    ).install(this)
                    routing {
                        get("/hostile/{id}") { error("secret-password https://secret-feed.test") }
                        get("/api/v1/devices/{id}") { call.respondText("unused") }
                    }
                }
                client
                    .get("/hostile/secret-path?password=secret-query") {
                        header(
                            "X-Request-Id",
                            "nds_" + "z".repeat(43),
                        )
                    }.expect(HttpStatusCode.InternalServerError)
                client.get("/api/v1/devices/secret-device?token=secret-query") {
                    api()
                }
                val logs = output.joinToString("\n")
                for (secret in listOf(
                    "secret-password",
                    "secret-feed",
                    "secret-path",
                    "secret-query",
                    "secret-device",
                    "z".repeat(43),
                    "/hostile/",
                )) {
                    assertFalse(logs.contains(secret))
                }
                assertEquals(2, output.size)
                assertTrue(logs.contains("unmatched"))
                assertTrue(logs.contains("/api/v1/devices/{id}"))
                if (format == LogFormat.JSON) {
                    val entry = SyncJson.parseToJsonElement(output.first()).jsonObject
                    assertEquals("500", entry.getValue("status").jsonPrimitive.content)
                    assertEquals(
                        setOf(
                            "time",
                            "request_id",
                            "route",
                            "status",
                            "duration_ms",
                            "changes_pushed",
                            "records_returned",
                        ),
                        entry.keys,
                    )
                }
            }
        }
    }
}
