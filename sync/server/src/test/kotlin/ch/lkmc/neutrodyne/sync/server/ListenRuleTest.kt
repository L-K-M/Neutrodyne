// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The listen rule of N13 and AC16's two branches (10 TLS stance and insecure LAN mode):
 * loopback is always allowed; a non-loopback listener needs an `https://` public URL or
 * `--insecure-lan`; behind an `https://` URL plain requests from untrusted peers get `421`.
 */
class ListenRuleTest {
    @Test
    fun `a loopback listener is always allowed`() {
        assertEquals(
            ListenAddress("127.0.0.1", 8787),
            loadValidConfig().listen,
        )
    }

    @Test
    fun `a loopback IPv6 listener is allowed`() {
        val config = loadValidConfig(env = mapOf(ServerEnv.LISTEN to "[::1]:8787"))
        assertEquals("[::1]:8787", config.listen.toString())
    }

    @Test
    fun `non-loopback without https public URL or insecure-lan is refused`() {
        val errors = loadInvalidConfig(env = mapOf(ServerEnv.LISTEN to "0.0.0.0:8787"))
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("refusing to listen on 0.0.0.0:8787"), errors.single())
        assertTrue(errors.single().contains(ServerEnv.PUBLIC_URL), errors.single())
        assertTrue(errors.single().contains("--insecure-lan"), errors.single())
    }

    @Test
    fun `a plain http public URL does not allow a non-loopback listener`() {
        val errors =
            loadInvalidConfig(
                env =
                    mapOf(
                        ServerEnv.LISTEN to "192.168.1.10:8787",
                        ServerEnv.PUBLIC_URL to "http://sync.example.org",
                    ),
            )
        assertTrue(errors.single().contains("refusing to listen"), errors.single())
    }

    @Test
    fun `an https public URL allows a non-loopback listener`() {
        val config =
            loadValidConfig(
                env =
                    mapOf(
                        ServerEnv.LISTEN to "0.0.0.0:8787",
                        ServerEnv.PUBLIC_URL to "https://sync.example.org",
                    ),
            )
        assertTrue(config.publicUrlIsHttps)
        assertTrue(!config.listen.isLoopback)
    }

    @Test
    fun `insecure-lan allows a non-loopback listener`() {
        val config =
            loadValidConfig(
                env = mapOf(ServerEnv.LISTEN to "0.0.0.0:8787"),
                flags = ServeFlags(insecureLan = true),
            )
        assertTrue(config.insecureLan)
        assertTrue(!config.listen.isLoopback)
    }

    @Test
    fun `insecure-lan does not silence the https transport guard`() =
        testApplication {
            application {
                installTestModule(
                    loadValidConfig(
                        env =
                            mapOf(
                                ServerEnv.LISTEN to "0.0.0.0:8787",
                                ServerEnv.PUBLIC_URL to "https://sync.example.org",
                            ),
                        flags = ServeFlags(insecureLan = true),
                    ),
                )
            }
            // 10 Request pipeline step 1 keys off the https public URL alone.
            val response =
                client.get(WellKnownRoutes.WELL_KNOWN_PATH) {
                    headers.append("X-Forwarded-For", "203.0.113.7")
                }
            assertEquals(InsecureTransportGuard.MISDIRECTED_REQUEST, response.status)
        }

    @Test
    fun `behind an https public URL a plain request from an untrusted peer gets 421`() =
        testApplication {
            application {
                installTestModule(loadValidConfig(env = mapOf(ServerEnv.PUBLIC_URL to "https://sync.example.org")))
            }
            // The test host's loopback peer is a trusted proxy; the forwarded client is not loopback
            // and reports no https, so the request is plain from an untrusted source.
            val response =
                client.get(WellKnownRoutes.WELL_KNOWN_PATH) {
                    headers.append("X-Forwarded-For", "203.0.113.7")
                }
            assertEquals(InsecureTransportGuard.MISDIRECTED_REQUEST, response.status)
            assertTrue(response.bodyAsText().contains("insecure_transport"))
        }

    @Test
    fun `behind an https public URL health routes still answer`() =
        testApplication {
            application {
                installTestModule(loadValidConfig(env = mapOf(ServerEnv.PUBLIC_URL to "https://sync.example.org")))
            }
            assertEquals(HttpStatusCode.OK, client.get(HealthRoutes.HEALTH_PATH).status)
            assertEquals(HttpStatusCode.OK, client.get(HealthRoutes.READY_PATH).status)
        }

    @Test
    fun `a trusted proxy reporting https is served`() =
        testApplication {
            application {
                installTestModule(loadValidConfig(env = mapOf(ServerEnv.PUBLIC_URL to "https://sync.example.org")))
            }
            val response =
                client.get(WellKnownRoutes.WELL_KNOWN_PATH) {
                    headers.append("X-Forwarded-For", "203.0.113.7")
                    headers.append("X-Forwarded-Proto", "https")
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `without an https public URL plain requests are served`() =
        testApplication {
            application {
                installTestModule(loadValidConfig())
            }
            assertEquals(HttpStatusCode.OK, client.get(WellKnownRoutes.WELL_KNOWN_PATH).status)
        }

    @Test
    fun `a loopback client behind a trusted proxy is served`() =
        testApplication {
            application {
                installTestModule(loadValidConfig(env = mapOf(ServerEnv.PUBLIC_URL to "https://sync.example.org")))
            }
            val response =
                client.get(WellKnownRoutes.WELL_KNOWN_PATH) {
                    headers.append("X-Forwarded-For", "127.0.0.1")
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }
}
