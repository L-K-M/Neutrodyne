// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.FieldState
import ch.lkmc.neutrodyne.sync.protocol.RecordId
import ch.lkmc.neutrodyne.sync.protocol.RecordState
import ch.lkmc.neutrodyne.sync.protocol.SyncCollection
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminWebTest {
    @Test fun `pages enforce browser roles and headers`() =
        authApplication { f ->
            f.account("user", "correct password")
            val admin = f.account("admin", "correct password", AccountRole.ADMIN)
            val userWeb = f.services.auth.loginWeb("user", "correct password", null)
            val adminWeb = f.services.auth.loginWeb("admin", "correct password", null)
            val device = f.grant(admin)
            for (path in listOf("/link", "/devices", "/library", "/account", "/admin/accounts", "/admin/server")) {
                client.get(path).expect(HttpStatusCode.Unauthorized)
                client
                    .get(
                        path,
                    ) { header(HttpHeaders.Authorization, "Bearer ${device.token}") }
                    .expect(HttpStatusCode.Unauthorized)
                client
                    .get(
                        path,
                    ) { header(HttpHeaders.Cookie, "nds_session=${device.token}") }
                    .expect(HttpStatusCode.Unauthorized)
                val response = client.get(path) { header(HttpHeaders.Cookie, "nds_session=$adminWeb") }
                response.expect(HttpStatusCode.OK)
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                assertEquals("no-referrer", response.headers["Referrer-Policy"])
                assertEquals("nosniff", response.headers["X-Content-Type-Options"])
                assertTrue(response.headers["Content-Security-Policy"]!!.contains("script-src 'none'"))
                val user = client.get(path) { header(HttpHeaders.Cookie, "nds_session=$userWeb") }
                user.expect(if (path.startsWith("/admin")) HttpStatusCode.Forbidden else HttpStatusCode.OK)
            }
            client.get("/setup").expect(HttpStatusCode.NotFound)
            client.get("/").expect(HttpStatusCode.OK)
            client.get("/style.css").expect(HttpStatusCode.OK)
            f.services.auth.disable(admin.id)
            client
                .get(
                    "/admin/server",
                ) { header(HttpHeaders.Cookie, "nds_session=$adminWeb") }
                .expect(HttpStatusCode.Forbidden)
        }

    @Test fun `setup and login enforce anonymous CSRF`() =
        authApplication { f ->
            val setup = Files.readString(f.services.setup.initialize()!!).trim()
            val browser = Browser(createClient { followRedirects = false })
            val page = browser.get("/setup")
            val token = page.csrf()
            val data = mapOf("csrf" to token, "code" to setup, "username" to "admin", "password" to "correct password")
            browser.post("/setup", data, origin = null).expect(HttpStatusCode.Forbidden)
            browser.post("/setup", data, origin = "http://evil.test").expect(HttpStatusCode.Forbidden)
            browser.post("/setup", data + ("csrf" to "wrong")).expect(HttpStatusCode.Forbidden)
            Browser(browser.client).post("/setup", data).expect(HttpStatusCode.Forbidden)
            browser.post("/setup", data + ("extra" to "x".repeat(16_384))).expect(HttpStatusCode.PayloadTooLarge)
            browser.post("/setup", data).expect(HttpStatusCode.Found)
            browser.get("/admin/server").expect(HttpStatusCode.OK)
            assertFalse(Files.exists(f.config.dataDir.resolve("setup-token")))
            val other = Browser(browser.client)
            val login = other.get("/")
            other
                .post(
                    "/login",
                    mapOf("csrf" to login.csrf(), "username" to "admin", "password" to "correct password"),
                ).expect(HttpStatusCode.Found)
            other.get("/account").expect(HttpStatusCode.OK)
        }

    @Test fun `mutation forms enforce Origin and CSRF`() =
        authApplication { f ->
            f.account("admin", "correct password", AccountRole.ADMIN)
            val raw = f.services.auth.loginWeb("admin", "correct password", null)
            val browser = Browser(createClient { followRedirects = false }, raw)
            val paths =
                listOf(
                    "/logout",
                    "/link/preview",
                    "/link/confirm",
                    "/devices/rename",
                    "/devices/revoke",
                    "/sessions/revoke",
                    "/account/password",
                    "/account/delete",
                    "/admin/accounts/create",
                    "/admin/accounts/action",
                )
            for (path in paths) {
                browser.post(path, emptyMap()).expect(HttpStatusCode.Forbidden)
                browser.post(path, mapOf("csrf" to "wrong")).expect(HttpStatusCode.Forbidden)
                browser
                    .post(
                        path,
                        mapOf("csrf" to f.services.sessions.csrf(raw)),
                        origin = null,
                    ).expect(HttpStatusCode.Forbidden)
                browser
                    .post(
                        path,
                        mapOf("csrf" to f.services.sessions.csrf(raw)),
                        origin = "http://evil.test",
                    ).expect(HttpStatusCode.Forbidden)
            }
        }

    @Test fun `browser code polling consumes only once`() =
        authApplication { f ->
            val owner = f.account()
            val grant = f.grant(owner)
            val browser = Browser(createClient { followRedirects = false })
            val login = browser.get("/")
            browser.post("/login/code", mapOf("csrf" to login.csrf())).expect(HttpStatusCode.Found)
            val pending = browser.cookies.getValue("nds_link")
            val userCode = browser.cookies.getValue("nds_link_code")
            val waiting = browser.get("/").bodyAsText()
            assertTrue(waiting.contains("http-equiv=\"refresh\""))
            assertTrue(waiting.contains(userCode))
            assertFalse(waiting.contains(pending))
            browser.get("/") // Early manual reload increases the polling interval.
            assertEquals("10", browser.cookies["nds_link_interval"])
            val principal = f.principal(grant)
            f.services.links.preview(principal, userCode, "127.0.0.1")
            f.services.links.confirm(principal, userCode, true)
            f.now += 10_000
            browser.get("/").expect(HttpStatusCode.Found)
            browser.get("/devices").expect(HttpStatusCode.OK)
            assertTrue(browser.cookies.getValue("nds_session").startsWith("ndw_"))
            assertEquals(LinkPoll.Error("expired_token"), f.services.links.pollWeb(pending))
        }

    @Test fun `web consent permits denial and warns on network`() =
        authApplication { f ->
            f.account(password = "correct password")
            val raw = f.services.auth.loginWeb("alice", "correct password", null)
            val browser = Browser(createClient { followRedirects = false }, raw)
            val link = f.services.links.startWeb("192.0.2.17")
            val csrf = f.services.sessions.csrf(raw)
            val preview = browser.post("/link/preview", mapOf("csrf" to csrf, "code" to link.userCode)).bodyAsText()
            assertTrue(preview.contains("different network"))
            assertTrue(preview.contains("signs a browser in"))
            assertEquals(LinkPoll.Error("authorization_pending"), f.services.links.pollWeb(link.deviceCode))
            browser
                .post(
                    "/link/confirm",
                    mapOf("csrf" to csrf, "code" to link.userCode, "decision" to "deny"),
                ).expect(HttpStatusCode.Found)
            assertEquals(LinkPoll.Error("access_denied"), f.services.links.pollWeb(link.deviceCode))
        }

    @Test fun `stored XSS is escaped and feed URLs stay private`() =
        authApplication { f ->
            val owner = f.account(password = "correct password")
            val payload = "<script>alert('x')</script>\"&"
            f.grant(owner, deviceInfo(payload))
            val raw = f.services.auth.loginWeb("alice", "correct password", payload)
            val key =
                RecordKey(
                    SyncCollection.PODCAST,
                    RecordId.fromRid(SyncCollection.PODCAST, UUID.randomUUID().toString()),
                )
            f.store.write(owner.id) {
                put(
                    StoredRecord(
                        key,
                        RecordState(
                            SyncCollection.PODCAST,
                            mapOf(
                                "customTitle" to FieldState(JsonPrimitive(payload), null),
                                "feedUrl" to FieldState(JsonPrimitive("https://secret-feed.test/private"), null),
                            ),
                        ),
                        nextSequence(),
                        null,
                        f.now,
                        null,
                    ),
                )
            }
            for (path in listOf("/devices", "/library")) {
                val html = Browser(client, raw).get(path).bodyAsText()
                assertFalse(html.contains("<script>"))
                assertTrue(html.contains("&lt;script&gt;"))
                assertFalse(html.contains("secret-feed.test"))
            }
        }

    @Test fun `admin account actions enforce typed deletion`() =
        authApplication { f ->
            f.account("admin", "correct password", AccountRole.ADMIN)
            val raw = f.services.auth.loginWeb("admin", "correct password", null)
            val browser = Browser(createClient { followRedirects = false }, raw)
            val csrf = f.services.sessions.csrf(raw)
            val created =
                browser.post(
                    "/admin/accounts/create",
                    mapOf("csrf" to csrf, "username" to "bob", "role" to "user"),
                )
            created.expect(HttpStatusCode.OK)
            assertTrue(created.bodyAsText().contains("Shown once"))
            val bob = f.services.admin.accountId("bob")

            suspend fun action(
                action: String,
                confirm: String = "",
            ) = browser.post(
                "/admin/accounts/action",
                mapOf("csrf" to csrf, "id" to "$bob", "action" to action, "confirm" to confirm),
            )
            action("disable").expect(HttpStatusCode.Found)
            assertTrue(f.store.readAuth { account(bob)!!.disabledAt != null })
            action("enable").expect(HttpStatusCode.Found)
            assertEquals(null, f.store.readAuth { account(bob)!!.disabledAt })
            action("delete", "DELETE").expect(HttpStatusCode.BadRequest)
            action("delete", "bob").expect(HttpStatusCode.Found)
            assertEquals(null, f.store.readAuth { account(bob) })
        }

    @Test fun `account mutations enforce password and ownership`() =
        authApplication { f ->
            val alice = f.account(password = "correct password")
            f.account("bob", "correct password")
            val raw = f.services.auth.loginWeb("alice", "correct password", null)
            val other = f.services.auth.loginWeb("bob", "correct password", null)
            val browser = Browser(createClient { followRedirects = false }, raw)
            val csrf = f.services.sessions.csrf(raw)
            browser
                .post(
                    "/sessions/revoke",
                    mapOf("csrf" to csrf, "id" to AuthSecrets.hash(other)),
                ).expect(HttpStatusCode.NotFound)
            browser
                .post(
                    "/account/password",
                    mapOf(
                        "csrf" to csrf,
                        "current" to "wrong",
                        "password" to "replacement password",
                    ),
                ).expect(HttpStatusCode.Unauthorized)
            browser
                .post(
                    "/account/password",
                    mapOf(
                        "csrf" to csrf,
                        "current" to "correct password",
                        "password" to "replacement password",
                    ),
                ).expect(HttpStatusCode.Found)
            browser
                .post(
                    "/account/delete",
                    mapOf("csrf" to csrf, "confirm" to "delete"),
                ).expect(HttpStatusCode.BadRequest)
            browser.post("/account/delete", mapOf("csrf" to csrf, "confirm" to "DELETE")).expect(HttpStatusCode.Found)
            assertTrue(f.store.readAuth { tokens(alice.id, TokenKind.WEB_SESSION).all { !it.active(f.now) } })
        }

    @Test fun `admin self reset atomically returns an invite after revocation`() =
        authApplication { f ->
            val owner = f.account("admin", "correct password", AccountRole.ADMIN)
            val raw = f.services.auth.loginWeb("admin", "correct password", null)
            val actor = f.services.tokens.authenticate(raw, TokenKind.WEB_SESSION)
            val browser = Browser(createClient { followRedirects = false }, raw)
            val response =
                browser.post(
                    "/admin/accounts/action",
                    mapOf(
                        "csrf" to f.services.sessions.csrf(raw),
                        "id" to owner.id.toString(),
                        "action" to "reset_password",
                    ),
                )
            response.expect(HttpStatusCode.OK)
            val code = checkNotNull(Regex("[A-Z]{4}(?:-[A-Z]{4}){3}").find(response.bodyAsText())).value
            assertFalse(f.services.tokens.valid(actor))
            assertEquals(null, f.store.readAuth { account(owner.id)!!.passwordHash })
            assertTrue(
                f.services.invites
                    .redeem(code, deviceInfo())
                    .token
                    .startsWith("nds_"),
            )
        }

    @Test fun `users cannot mutate admin accounts and device forms enforce ownership`() =
        authApplication { f ->
            val alice = f.account(password = "correct password")
            val bob = f.account("bob")
            val own = f.grant(alice)
            val foreign = f.grant(bob)
            val raw = f.services.auth.loginWeb("alice", "correct password", null)
            val browser = Browser(createClient { followRedirects = false }, raw)
            val csrf = f.services.sessions.csrf(raw)
            browser
                .post(
                    "/admin/accounts/create",
                    mapOf("csrf" to csrf, "username" to "mallory", "role" to "admin"),
                ).expect(HttpStatusCode.Forbidden)
            browser
                .post(
                    "/admin/accounts/action",
                    mapOf(
                        "csrf" to csrf,
                        "id" to bob.id.toString(),
                        "action" to "delete",
                        "confirm" to "bob",
                    ),
                ).expect(HttpStatusCode.Forbidden)
            browser
                .post(
                    "/devices/revoke",
                    mapOf("csrf" to csrf, "id" to foreign.deviceId),
                ).expect(HttpStatusCode.NotFound)
            browser
                .post(
                    "/devices/rename",
                    mapOf("csrf" to csrf, "id" to own.deviceId, "name" to "Renamed phone"),
                ).expect(HttpStatusCode.Found)
            assertTrue(browser.get("/devices").bodyAsText().contains("Renamed phone"))
            browser.post("/devices/revoke", mapOf("csrf" to csrf, "id" to own.deviceId)).expect(HttpStatusCode.Found)
            assertTrue(runCatching { f.principal(own) }.isFailure)
        }

    @Test fun `account keeps password date after the recent notice expires`() =
        authApplication { f ->
            val owner = f.account(password = "correct password")
            f.now += 31 * DAY_MS
            val raw = f.services.auth.loginWeb("alice", "correct password", null)
            val html = Browser(client, raw).get("/account").bodyAsText()
            val expected =
                kotlin.time.Instant
                    .fromEpochMilliseconds(checkNotNull(owner.passwordSetAt))
                    .toString()
            assertTrue(html.contains("Password set on $expected"))
        }

    @Test fun `insecure LAN pages carry the banner`() {
        AuthFixture(loadValidConfig().copy(dataDir = tempDataDir(), insecureLan = true)).use { f ->
            testApplication {
                application { f.install(this) }
                val setup = client.get("/setup")
                for (path in listOf(
                    "/",
                    "/setup",
                )) {
                    assertTrue(client.get(path).bodyAsText().contains("This server is not using HTTPS"))
                }
                assertFalse(setup.headers.getAll(HttpHeaders.SetCookie)!!.any { it.contains("; Secure", true) })
            }
        }
    }
}

private class Browser(
    val client: HttpClient,
    raw: String? = null,
) {
    val cookies = mutableMapOf<String, String>()

    init {
        raw?.let { cookies[WebSessions.COOKIE] = it }
    }

    suspend fun get(path: String): HttpResponse =
        capture(
            client.get(path) {
                header(HttpHeaders.Host, "localhost")
                header(HttpHeaders.Cookie, cookieHeader())
            },
        )

    suspend fun post(
        path: String,
        values: Map<String, String>,
        origin: String? = "http://localhost",
    ): HttpResponse =
        capture(
            client.post(path) {
                header(HttpHeaders.Host, "localhost")
                header(HttpHeaders.Cookie, cookieHeader())
                if (origin != null) header(HttpHeaders.Origin, origin)
                setBody(FormDataContent(Parameters.build { values.forEach { (key, value) -> append(key, value) } }))
            },
        )

    private fun cookieHeader() = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun capture(response: HttpResponse): HttpResponse {
        response.headers.getAll(HttpHeaders.SetCookie).orEmpty().forEach { header ->
            val pair = header.substringBefore(';')
            cookies[pair.substringBefore('=')] = pair.substringAfter('=')
        }
        return response
    }
}

private suspend fun HttpResponse.csrf(): String {
    val pattern = Regex("name=\"csrf\"[^>]*value=\"([^\"]+)\"")
    return checkNotNull(pattern.find(bodyAsText())).groupValues[1]
}
