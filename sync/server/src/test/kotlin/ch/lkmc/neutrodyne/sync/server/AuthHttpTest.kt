// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceRenameRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.InviteRedeemRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkApproveRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkApproveResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkConfirmRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkStartRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkStartResponse
import ch.lkmc.neutrodyne.sync.protocol.dto.LinkTokenRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.LoginRequest
import ch.lkmc.neutrodyne.sync.protocol.dto.OAuthErrorDto
import ch.lkmc.neutrodyne.sync.protocol.dto.TokenResponse
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthHttpTest {
    @Test fun `concurrent HTTP invite redemption is single use and stores only hashes`() =
        authApplication { f ->
            val owner = f.account()
            val code = f.services.invites.create(owner.id)
            val responses =
                coroutineScope {
                    (1..8)
                        .map {
                            async {
                                client.postApi(
                                    "/api/v1/auth/invite/redeem",
                                    InviteRedeemRequest(code.lowercase().replace("-", " "), deviceInfo()),
                                )
                            }
                        }.awaitAll()
                }
            assertEquals(1, responses.count { it.status == HttpStatusCode.OK })
            assertEquals(7, responses.count { it.status == HttpStatusCode.Unauthorized })
            val grant = responses.single { it.status == HttpStatusCode.OK }.decoded<TokenResponse>()
            client
                .postApi("/api/v1/auth/invite/redeem", InviteRedeemRequest(code, deviceInfo()))
                .expect(HttpStatusCode.Unauthorized)
            f.store.readAuth {
                assertEquals(1, devices(owner.id).size)
                assertEquals(1, tokens(owner.id, TokenKind.DEVICE).size)
                assertNotNull(tokens(owner.id, TokenKind.INVITE).single().consumedAt)
                assertEquals(TokenKind.DEVICE, token(AuthSecrets.hash(grant.token))!!.kind)
                kotlin.test.assertNull(token(grant.token))
                kotlin.test.assertNull(token(code))
            }
        }

    @Test fun `login generic errors disabled revoked expired and cookie scope`() =
        authApplication { f ->
            val alice = f.account(password = "correct password")
            val grant = f.grant(alice)
            val expired = f.grant(alice)
            f.store.writeAuth {
                val old = token(AuthSecrets.hash(expired.token))!!
                save(old.copy(expiresAt = f.now))
            }
            val browser = f.services.auth.loginWeb("alice", "correct password", "Firefox")
            val bad =
                client.postApi("/api/v1/auth/login", LoginRequest("alice", "wrong password", deviceInfo()))
            val unknown =
                client.postApi("/api/v1/auth/login", LoginRequest("unknown", "wrong password", deviceInfo()))
            bad.expect(HttpStatusCode.Unauthorized)
            assertEquals(bad.bodyAsText(), unknown.bodyAsText())
            client
                .postApi(
                    "/api/v1/auth/login",
                    LoginRequest("alice", "correct password", deviceInfo()),
                ).expect(HttpStatusCode.OK)
            client.get("/api/v1/account") { api(browser) }.expect(HttpStatusCode.Unauthorized)
            client
                .get("/api/v1/account") {
                    api()
                    header(HttpHeaders.Cookie, "nds_session=$browser")
                }.expect(HttpStatusCode.Unauthorized)
            client.get("/api/v1/account") { api(expired.token) }.expect(HttpStatusCode.Unauthorized)
            client.post("/api/v1/auth/logout") { api(grant.token) }.expect(HttpStatusCode.NoContent)
            client.get("/api/v1/account") { api(grant.token) }.expect(HttpStatusCode.Unauthorized)
            val disabled = f.grant(alice)
            f.services.auth.disable(alice.id)
            client.get("/api/v1/account") { api(disabled.token) }.expect(HttpStatusCode.Forbidden)
        }

    @Test fun `HTTP link supports all four errors consent preview and single use`() =
        authApplication { f ->
            val grant = f.grant(f.account())

            suspend fun start(): LinkStartResponse =
                SyncJson.decodeFromString(
                    client
                        .postApi("/api/v1/auth/link/start", LinkStartRequest(deviceInfo()))
                        .bodyAsText(),
                )

            suspend fun poll(code: String) = client.postApi("/api/v1/auth/link/token", LinkTokenRequest(code))
            val pending = start()
            assertEquals(
                "authorization_pending",
                poll(pending.deviceCode).decoded<OAuthErrorDto>().error,
            )
            assertEquals(
                "slow_down",
                poll(pending.deviceCode).decoded<OAuthErrorDto>().error,
            )
            val preview =
                client.postApi("/api/v1/auth/link/approve", LinkApproveRequest(pending.userCode), grant.token)
            assertEquals("device", preview.decoded<LinkApproveResponse>().purpose)
            client
                .postApi(
                    "/api/v1/auth/link/confirm",
                    LinkConfirmRequest(pending.userCode, true),
                    grant.token,
                ).expect(HttpStatusCode.NoContent)
            f.now += 10_000
            val token = poll(pending.deviceCode).decoded<TokenResponse>()
            assertEquals(grant.accountId, token.accountId)
            assertEquals(
                "expired_token",
                poll(pending.deviceCode).decoded<OAuthErrorDto>().error,
            )
            val denied = start()
            client.postApi("/api/v1/auth/link/approve", LinkApproveRequest(denied.userCode), grant.token)
            client.postApi("/api/v1/auth/link/confirm", LinkConfirmRequest(denied.userCode, false), grant.token)
            assertEquals(
                "access_denied",
                poll(denied.deviceCode).decoded<OAuthErrorDto>().error,
            )
            f.now += 600_000
            assertEquals(
                "expired_token",
                poll(denied.deviceCode).decoded<OAuthErrorDto>().error,
            )
        }

    @Test fun `device routes isolate accounts rename and revoke`() =
        authApplication { f ->
            val alice = f.grant(f.account())
            val bob = f.grant(f.account("bob"))
            val listed = client.get("/api/v1/devices") { api(alice.token) }
            assertTrue(listed.bodyAsText().contains(alice.deviceId))
            assertFalse(listed.bodyAsText().contains(bob.deviceId))
            client.delete("/api/v1/devices/${bob.deviceId}") { api(alice.token) }.expect(HttpStatusCode.NotFound)
            val renamed =
                client.patch("/api/v1/devices/${alice.deviceId}") {
                    api(alice.token)
                    contentType(ContentType.Application.Json)
                    setBody(SyncJson.encodeToString(DeviceRenameRequest(" New name ")))
                }
            renamed.expect(HttpStatusCode.OK)
            assertTrue(renamed.bodyAsText().contains("New name"))
            client.delete("/api/v1/devices/${alice.deviceId}") { api(alice.token) }.expect(HttpStatusCode.NoContent)
            client.get("/api/v1/account") { api(alice.token) }.expect(HttpStatusCode.Unauthorized)
            client.get("/api/v1/account") { api(bob.token) }.expect(HttpStatusCode.OK)
        }

    @Test fun `login and link start IP limits return Retry After`() =
        authApplication { f ->
            val owner = f.account()
            val invite = f.services.invites.create(owner.id)
            repeat(10) {
                client
                    .postApi(
                        "/api/v1/auth/invite/redeem",
                        InviteRedeemRequest("bad-code", deviceInfo()),
                    ).expect(HttpStatusCode.Unauthorized)
            }
            val limited =
                client.postApi("/api/v1/auth/invite/redeem", InviteRedeemRequest(invite, deviceInfo()))
            limited.expect(HttpStatusCode.TooManyRequests)
            assertNotNull(limited.headers[HttpHeaders.RetryAfter])
            repeat(20) {
                client
                    .postApi("/api/v1/auth/link/start", LinkStartRequest(deviceInfo()))
                    .expect(HttpStatusCode.OK)
            }
            val startLimit =
                client.postApi("/api/v1/auth/link/start", LinkStartRequest(deviceInfo()))
            startLimit.expect(HttpStatusCode.TooManyRequests)
            assertNotNull(startLimit.headers[HttpHeaders.RetryAfter])
        }

    @Test fun `approval failed entry budget returns 429 and Retry After`() =
        authApplication { f ->
            val grant = f.grant(f.account())
            repeat(5) {
                client
                    .postApi(
                        "/api/v1/auth/link/approve",
                        LinkApproveRequest("bad-code"),
                        grant.token,
                    ).expect(HttpStatusCode.NotFound)
            }
            val limited =
                client.postApi("/api/v1/auth/link/approve", LinkApproveRequest("bad-code"), grant.token)
            limited.expect(HttpStatusCode.TooManyRequests)
            assertEquals("900", limited.headers[HttpHeaders.RetryAfter])
        }
}
