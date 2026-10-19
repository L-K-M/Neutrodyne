// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebSessionsTest {
    @Test fun `authenticated and anonymous forms require cookie synchronizer and exact origin`() =
        runBlocking<Unit> {
            AuthFixture().use { f ->
                f.account(password = "correct password")
                val raw = f.services.auth.loginWeb("alice", "correct password", "Browser")
                val web =
                    WebSessions(
                        f.services.tokens,
                        f.config.copy(publicUrl = URI("https://sync.example.test")),
                        f.inputs,
                    )
                testApplication {
                    application {
                        f.install(this)
                        routing {
                            route("/forms") {
                                web.installFormProtection(this)
                                get {
                                    web.setCookie(call, raw)
                                    call.respondText(web.csrf(raw))
                                }
                                get("/anonymous") { call.respondText(web.preAuth(call)) }
                                post {
                                    web.authenticate(call)
                                    web.requireForm(call, call.receiveText())
                                    call.respondText("accepted")
                                }
                                post("/anonymous") {
                                    web.requireForm(call, call.receiveText(), FormSession.ANONYMOUS)
                                    call.respondText("accepted")
                                }
                            }
                        }
                    }
                    val form = client.get("/forms")
                    val cookie = form.headers[HttpHeaders.SetCookie]!!
                    assertTrue(cookie.contains("HttpOnly", true))
                    assertTrue(cookie.contains("Secure", true))
                    assertTrue(cookie.contains("SameSite=Strict", true))
                    assertTrue(cookie.contains("Path=/", true))
                    assertEquals("no-store", form.headers[HttpHeaders.CacheControl])
                    assertTrue(form.headers["Content-Security-Policy"]!!.contains("script-src 'none'"))
                    val token = form.bodyAsText()

                    suspend fun submit(
                        csrf: String,
                        origin: String?,
                        session: String = raw,
                    ) = client.post("/forms") {
                        header(HttpHeaders.Host, "sync.example.test")
                        header(HttpHeaders.Cookie, "nds_session=$session")
                        if (origin != null) header(HttpHeaders.Origin, origin)
                        setBody(csrf)
                    }
                    submit(
                        token,
                        "https://sync.example.test",
                    ).expect(HttpStatusCode.OK)
                    submit(
                        "wrong-token",
                        "https://sync.example.test",
                    ).expect(HttpStatusCode.Forbidden)
                    submit(token, null).expect(HttpStatusCode.Forbidden)
                    submit(
                        token,
                        "https://evil.example",
                    ).expect(HttpStatusCode.Forbidden)
                    submit(
                        token,
                        "https://sync.example.test/",
                    ).expect(HttpStatusCode.Forbidden)
                    val anonymous = client.get("/forms/anonymous")
                    val anonymousCookie = anonymous.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
                    client
                        .post("/forms/anonymous") {
                            header(HttpHeaders.Cookie, anonymousCookie)
                            header(HttpHeaders.Host, "sync.example.test")
                            header(HttpHeaders.Origin, "https://sync.example.test")
                            setBody(anonymous.bodyAsText())
                        }.expect(HttpStatusCode.OK)
                    client
                        .post("/forms/anonymous") {
                            header(HttpHeaders.Host, "sync.example.test")
                            header(HttpHeaders.Origin, "https://sync.example.test")
                            setBody(anonymous.bodyAsText())
                        }.expect(HttpStatusCode.Forbidden)
                }
            }
        }

    @Test fun `insecure LAN drops Secure and device tokens cannot authenticate admin pages`() =
        runBlocking<Unit> {
            AuthFixture().use { f ->
                val admin = f.account("admin", "correct password", AccountRole.ADMIN)
                val device = f.grant(admin)
                val browser = f.services.auth.loginWeb("admin", "correct password", null)
                val web =
                    WebSessions(f.services.tokens, f.config.copy(insecureLan = true), f.inputs)
                testApplication {
                    application {
                        f.install(this)
                        routing {
                            get("/admin-only") {
                                web.requireAdmin(call)
                                web.setCookie(call, browser)
                                call.respondText("admin")
                            }
                        }
                    }
                    client
                        .get("/admin-only") {
                            header(HttpHeaders.Authorization, "Bearer ${device.token}")
                        }.expect(HttpStatusCode.Unauthorized)
                    client
                        .get("/admin-only") {
                            header(HttpHeaders.Cookie, "nds_session=${device.token}")
                        }.expect(HttpStatusCode.Unauthorized)
                    val page =
                        client.get("/admin-only") {
                            header(HttpHeaders.Cookie, "nds_session=$browser")
                        }
                    page.expect(HttpStatusCode.OK)
                    assertFalse(page.headers[HttpHeaders.SetCookie]!!.contains("; Secure", true))
                }
            }
        }
}
