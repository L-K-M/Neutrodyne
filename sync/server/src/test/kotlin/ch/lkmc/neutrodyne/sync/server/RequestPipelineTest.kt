// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RequestPipelineTest {
    @Test fun `safe problems and security headers reach responses`() =
        testApplication {
            application {
                installTestModule(loadValidConfig())
                routing { get("/failure") { error("secret-token http://private.example/feed") } }
            }
            val failure = client.get("/failure") { header("X-Request-Id", "safe_id-42") }
            assertEquals(HttpStatusCode.InternalServerError, failure.status)
            assertEquals("safe_id-42", failure.headers["X-Request-Id"])
            assertFalse(failure.bodyAsText().contains("secret"))
            assertFalse(failure.bodyAsText().contains("private.example"))
            assertEquals("nosniff", failure.headers["X-Content-Type-Options"])
            assertEquals("no-store", failure.headers[HttpHeaders.CacheControl])
            assertEquals("no-referrer", failure.headers["Referrer-Policy"])
            assertTrue(failure.headers["Content-Security-Policy"]!!.contains("script-src 'none'"))
            val unsafeId = "bad/request?token=secret"
            val generated = client.get("/healthz") { header("X-Request-Id", unsafeId) }.headers["X-Request-Id"]
            assertNotEquals(unsafeId, generated)
            assertTrue(generated!!.length <= 64)
            assertEquals(HttpStatusCode.UpgradeRequired, client.post("/api/v1/sync").status)
        }

    @Test fun `discovery public limiter returns problem and Retry After`() =
        testApplication {
            application { installTestModule(loadValidConfig()) }
            repeat(60) { assertEquals(HttpStatusCode.OK, client.get(WellKnownRoutes.WELL_KNOWN_PATH).status) }
            val response = client.get(WellKnownRoutes.WELL_KNOWN_PATH)
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertTrue(response.headers[HttpHeaders.RetryAfter]!!.toInt() in 1..60)
            assertTrue(response.bodyAsText().contains("rate_limited"))
            assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
        }

    @Test fun `account and IP limits use independent account keys`() =
        testApplication {
            application {
                installTestModule(loadValidConfig())
                intercept(ApplicationCallPipeline.Plugins) {
                    // Authentication is injected only by the test; production W5 owns this attribute.
                    call.attributes.put(
                        RateLimits.PRINCIPAL,
                        AccountPrincipal(
                            call.request.headers["test-account"]!!.toLong(),
                            AccountRole.USER,
                            TokenKind.WEB_SESSION,
                            "hash",
                            null,
                        ),
                    )
                }
                routing {
                    rateLimit(RateLimits.SYNC_IP) {
                        rateLimit(RateLimits.SYNC_ACCOUNT) {
                            get("/limited") { call.respondText("ok") }
                        }
                    }
                }
            }
            repeat(300) {
                val response = client.get("/limited") { header("test-account", "1") }
                assertEquals(HttpStatusCode.OK, response.status)
            }
            assertEquals(HttpStatusCode.TooManyRequests, client.get("/limited") { header("test-account", "1") }.status)
            repeat(299) {
                val response = client.get("/limited") { header("test-account", "2") }
                assertEquals(HttpStatusCode.OK, response.status)
            }
            assertEquals(HttpStatusCode.TooManyRequests, client.get("/limited") { header("test-account", "3") }.status)
        }
}
