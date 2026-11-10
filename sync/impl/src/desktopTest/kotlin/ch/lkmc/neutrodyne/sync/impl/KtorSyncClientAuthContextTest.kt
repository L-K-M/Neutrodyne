// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import ch.lkmc.neutrodyne.core.domain.SyncServerAddress
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.network.okhttp.AuthInterceptor
import ch.lkmc.neutrodyne.core.network.okhttp.CoreClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.core.network.okhttp.UserAgentInterceptor
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSyncServerAddress
import ch.lkmc.neutrodyne.sync.protocol.dto.DeviceInfoDto
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The saved-address A-to-B race on the production [SyncBindings.syncClient] wiring: the binding's
 * `baseUrlProvider` and `tokenProvider` each read `SyncServerAddress.serverUrl()` independently,
 * so an address switch between the two reads pairs one server's URL with the other's token.
 * These tests stand up two loopback `MockWebServer`s with a stored token each and a seam whose
 * first `serverUrl()` read returns A's address while every later read returns B — exactly the
 * interleaving a relink produces. Every authenticated call must send A's request only A's token:
 * B's token must never reach A, and a URL with no stored token sends no `Authorization` at all.
 *
 * Tokens are fake fixture strings; assertions compare them as opaque values, never as secrets.
 */
class KtorSyncClientAuthContextTest {
    private var clients: OkHttpNeutrodyneHttpClients? = null
    private val rigs = mutableListOf<Rig>()

    private val device = DeviceInfoDto("dev-1", "desk", "desktop", "1.0", "node-1")

    @AfterTest
    fun tearDown() {
        clients?.close()
        rigs.forEach { it.close() }
    }

    /**
     * GET (`devices`) twice across the switch: the first call resolves A and must reach A with
     * A's token even though the saved address now reads B; the second resolves B and must reach
     * B with B's token — the per-request context is resolved fresh, not cached.
     */
    @Test
    fun eachCallPairsTheResolvedUrlWithThatUrlsToken() =
        runTest {
            val rig = rig()
            rig.serverA.enqueue(json("[]"))
            rig.serverB.enqueue(json("[]"))

            rig.client.devices()
            rig.client.devices()

            val reqA = rig.serverA.takeRequest(TAKE_SECONDS, TimeUnit.SECONDS)
            val reqB = rig.serverB.takeRequest(TAKE_SECONDS, TimeUnit.SECONDS)
            assertNotNull(reqA, "the first call must reach server A")
            assertNotNull(reqB, "the second call must reach server B")
            assertEquals("Bearer $TOKEN_A", reqA.headers["Authorization"])
            assertEquals("Bearer $TOKEN_B", reqB.headers["Authorization"])
        }

    /**
     * Every authenticated verb takes the coupled-context path; a fresh seam per call gives each
     * the same first-read-A-then-B interleaving. POST (`logout`/`sync`/`reset`), GET (`account`/
     * `devices`/`changes`), PATCH (`renameDevice`) and DELETE (`revokeDevice`) must all reach A
     * with `TOKEN_A` — and no request may reach B.
     */
    @Test
    fun everyAuthenticatedCallSendsTheResolvedUrlsOwnToken() =
        runTest {
            val failures = mutableListOf<String>()
            for (call in calls()) {
                val rig = rig()
                rig.serverA.enqueue(call.response)
                runCatching { call.invoke(rig.client) }
                val req = rig.serverA.takeRequest(TAKE_SECONDS, TimeUnit.SECONDS)
                if (req == null) {
                    failures += "${call.name}: no request reached server A"
                    continue
                }
                if (req.method != call.method) failures += "${call.name}: method ${req.method}"
                if (!req.url.encodedPath.endsWith(call.path)) {
                    failures += "${call.name}: path ${req.url.encodedPath}"
                }
                if (req.headers["Authorization"] != "Bearer $TOKEN_A") {
                    failures += "${call.name}: A saw Authorization=${req.headers["Authorization"]}"
                }
                if (rig.serverB.takeRequest(NO_HIT_SECONDS, TimeUnit.SECONDS) != null) {
                    failures += "${call.name}: request also reached server B"
                }
            }
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        }

    /**
     * A resolved URL with no stored token sends no `Authorization` — never another host's token:
     * only B has a token here, so A's request must go out bare rather than borrow `TOKEN_B`.
     */
    @Test
    fun aUrlWithoutAStoredTokenSendsNoBearer() =
        runTest {
            val rig = rig(saveTokenA = false)
            rig.serverA.enqueue(json("[]"))

            rig.client.devices()

            val req = rig.serverA.takeRequest(TAKE_SECONDS, TimeUnit.SECONDS)
            assertNotNull(req, "the call must reach server A")
            assertNull(req.headers["Authorization"], "no stored token for A must not leak B's")
            assertNull(rig.serverB.takeRequest(NO_HIT_SECONDS, TimeUnit.SECONDS))
        }

    /**
     * Explicit-address calls keep their own semantics: `login` targets the URL it is given with
     * no saved-token read at all, and `linkApprove` carries only its explicit token — neither
     * consults the saved address, which still reads A-then-B underneath them.
     */
    @Test
    fun explicitAddressCallsNeverReadTheSavedToken() =
        runTest {
            val rig = rig()
            rig.serverB.enqueue(json(TOKEN_JSON))
            rig.serverB.enqueue(json(APPROVE_JSON))

            rig.client.login(rig.urlB, "alice", "secret", device)
            rig.client.linkApprove(rig.urlB, "link-tok", "WDJB-MJHT")

            val login = rig.serverB.takeRequest(TAKE_SECONDS, TimeUnit.SECONDS)
            val approve = rig.serverB.takeRequest(TAKE_SECONDS, TimeUnit.SECONDS)
            assertNotNull(login)
            assertNotNull(approve)
            assertNull(login.headers["Authorization"], "login never authenticates")
            assertEquals("Bearer link-tok", approve.headers["Authorization"])
            assertNull(rig.serverA.takeRequest(NO_HIT_SECONDS, TimeUnit.SECONDS))
        }

    // --- verbs under audit --------------------------------------------------------------------

    private class Call(
        val name: String,
        val method: String,
        val path: String,
        val response: MockResponse,
        val invoke: suspend (SyncClient) -> Unit,
    )

    private fun calls(): List<Call> =
        listOf(
            Call("account", "GET", "/api/v1/account", json(ACCOUNT_JSON)) { it.account() },
            Call("devices", "GET", "/api/v1/devices", json("[]")) { it.devices() },
            Call("changes", "GET", "/api/v1/changes", json(PAGE_JSON)) { it.changes("c1", 10) },
            Call("logout", "POST", "/api/v1/auth/logout", noContent()) { it.logout() },
            Call("sync", "POST", "/api/v1/sync", json(SYNC_JSON)) { it.sync(null, emptyList()) },
            Call("reset", "POST", "/api/v1/account/reset", json(ACCOUNT_JSON)) {
                it.reset("RESET", "all", null)
            },
            Call("renameDevice", "PATCH", "/api/v1/devices/d1", json(DEVICE_JSON)) {
                it.renameDevice("d1", "desk")
            },
            Call("revokeDevice", "DELETE", "/api/v1/devices/d1", noContent()) {
                it.revokeDevice("d1")
            },
        )

    // --- rig ------------------------------------------------------------------------------------

    /**
     * Two servers, a `SyncTokenStore` holding a fake token per host, and the production
     * [SyncBindings.syncClient] binding against an address seam that reads A once and B ever
     * after — the relink interleaving the old split providers raced with.
     */
    private class Rig(
        val client: SyncClient,
        val serverA: MockWebServer,
        val serverB: MockWebServer,
    ) : AutoCloseable {
        val urlA: String = serverA.url("/").toString()
        val urlB: String = serverB.url("/").toString()

        override fun close() {
            serverA.close()
            serverB.close()
        }
    }

    private suspend fun rig(saveTokenA: Boolean = true): Rig {
        val serverA = MockWebServer().apply { start() }
        val serverB = MockWebServer().apply { start() }
        val urlA = serverA.url("/").toString()
        val urlB = serverB.url("/").toString()
        val tokens = SyncTokenStore(FakeSecretStore())
        if (saveTokenA) tokens.save(hostOf(urlA), TOKEN_A)
        tokens.save(hostOf(urlB), TOKEN_B)
        val client =
            SyncBindings.syncClient(
                httpClients(),
                tokens,
                FirstThenAddress(urlA, urlB),
            )
        return Rig(client, serverA, serverB).also { rigs += it }
    }

    /** First `serverUrl()` read answers [first]; every later read answers [rest]. */
    private class FirstThenAddress(
        private val first: String,
        private val rest: String,
    ) : SyncServerAddress by FakeSyncServerAddress(serverUrl = first) {
        private var reads = 0

        override suspend fun serverUrl(): String? = if (reads++ == 0) first else rest
    }

    private fun httpClients(): OkHttpNeutrodyneHttpClients =
        clients
            ?: run {
                val platform = fakePlatform()
                val userAgent = UserAgentProvider(platform, "0.1.0", "https://example.com/repo")
                OkHttpNeutrodyneHttpClients(
                    NetworkClients(
                        CoreClients(
                            ua = UserAgentInterceptor(userAgent),
                            lanGuard = LocalNetworkGuard(LocalNetworkAccess(), platform),
                            hints = DnsFamilyHints(),
                            debugInterceptors = emptySet(),
                        ),
                        AuthInterceptor { CredentialLookup.None },
                    ),
                    userAgent,
                ).also { clients = it }
            }

    private fun noContent(): MockResponse = MockResponse.Builder().code(204).build()

    private fun json(body: String): MockResponse =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun fakePlatform() =
        object : PlatformInfo {
            override val kind = PlatformKind.DESKTOP
            override val userAgentPlatform = "Linux; x64"
            override val androidSdkInt: Int? = null
            override val regionCode = "DE"
        }

    private companion object {
        const val TAKE_SECONDS = 5L
        const val NO_HIT_SECONDS = 1L
        const val TOKEN_A = "fake-token-for-server-a"
        const val TOKEN_B = "fake-token-for-server-b"
        const val TOKEN_JSON = """{"token":"t","accountId":"a","deviceId":"d","username":"u"}"""
        const val APPROVE_JSON =
            """{"deviceName":"Anna's phone","platform":"android","appVersion":"0.9.3",""" +
                """"requestedAt":"2026-11-04T10:00:00Z","purpose":"device","sameNetwork":false}"""
        const val DEVICE_JSON =
            """{"id":"d1","name":"desk","platform":"desktop","appVersion":"1.0",""" +
                """"lastSeenAt":null,"current":false,"stale":false}"""
        const val ACCOUNT_JSON =
            """{"username":"u","counts":{"podcasts":0,"groups":0,"episodes":0,""" +
                """"upnext":0,"settings":0},"devices":1,"quotaRecords":100,"records":0}"""
        const val SYNC_JSON =
            """{"results":[],"records":[],"cursor":"c","hasMore":false,"serverTime":"t"}"""
        const val PAGE_JSON = SYNC_JSON
    }
}
