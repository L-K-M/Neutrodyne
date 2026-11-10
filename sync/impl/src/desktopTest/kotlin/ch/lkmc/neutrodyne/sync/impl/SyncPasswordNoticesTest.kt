// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.common.LocalNetworkAccess
import ch.lkmc.neutrodyne.core.common.PlatformInfo
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.common.UserAgentProvider
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.SettingsError
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.settings.SettingKey
import ch.lkmc.neutrodyne.core.model.settings.SyncSettingKeys
import ch.lkmc.neutrodyne.core.network.OkHttpNeutrodyneHttpClients
import ch.lkmc.neutrodyne.core.network.okhttp.AuthInterceptor
import ch.lkmc.neutrodyne.core.network.okhttp.CoreClients
import ch.lkmc.neutrodyne.core.network.okhttp.DnsFamilyHints
import ch.lkmc.neutrodyne.core.network.okhttp.LocalNetworkGuard
import ch.lkmc.neutrodyne.core.network.okhttp.NetworkClients
import ch.lkmc.neutrodyne.core.network.okhttp.UserAgentInterceptor
import ch.lkmc.neutrodyne.core.testing.FakeLocalNetworkGate
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSettingsRepository
import ch.lkmc.neutrodyne.core.testing.FakeSyncServerAddress
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.sync.api.SyncNotice
import ch.lkmc.neutrodyne.sync.protocol.SyncJson
import ch.lkmc.neutrodyne.sync.protocol.dto.AccountSummary
import ch.lkmc.neutrodyne.sync.protocol.dto.LibraryCountsDto
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncPasswordNoticesTest {
    @Test
    fun anOrdinaryBusKeepsItsImmediateSubscriptionContract() =
        runTest {
            val bus = SyncNoticeBus()
            val notice = SyncNotice.PasswordSet("2026-10-03")
            val received =
                backgroundScope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    bus.notices.firstOrNull()
                }
            bus.post(notice)
            runCurrent()
            assertTrue(
                received.isCompleted,
                "Adding startup notices must not lose an already-subscribed ordinary event",
            )
            assertEquals(notice, received.await())
        }

    @Test
    fun anAccount401UsesTheControllersOwnedRevocationCleanup() =
        runTest {
            val h = Harness(this, TestClock.DEFAULT_NOW)
            try {
                h.seedLinked()
                val rejected =
                    object : SyncClient by h.client {
                        override fun withCredentials(
                            serverUrl: String,
                            token: String,
                        ): SyncClient? = null

                        override suspend fun account(): AccountSummary =
                            throw SyncTransportException(401, "unauthorized", "")
                    }
                val checker =
                    SyncBindings.passwordNotices(
                        h.db,
                        rejected,
                        h.address,
                        h.tokens,
                        FakeSettingsRepository(),
                        h.clock,
                        FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
                        h.gate,
                        h.identity,
                        backgroundScope,
                        Provider { h.controller },
                    )
                assertEquals(emptyList(), checker.notices.toListForTest())
                val state = requireNotNull(h.db.syncStateDao().get())
                assertFalse(state.enabled)
                assertEquals(SYNC_ERROR_REVOKED, state.lastError)
            } finally {
                h.close()
            }
        }

    @Test
    fun anOldAccount401CannotRevokeItsReplacement() =
        runTest {
            val h = Harness(this, TestClock.DEFAULT_NOW)
            try {
                h.seedLinked()
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val rejected =
                    object : SyncClient by h.client {
                        override fun withCredentials(
                            serverUrl: String,
                            token: String,
                        ): SyncClient? = null

                        override suspend fun account(): AccountSummary {
                            started.complete(Unit)
                            release.await()
                            throw SyncTransportException(401, "unauthorized", "")
                        }
                    }
                val checker =
                    SyncBindings.passwordNotices(
                        h.db,
                        rejected,
                        h.address,
                        h.tokens,
                        FakeSettingsRepository(),
                        h.clock,
                        FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
                        h.gate,
                        h.identity,
                        backgroundScope,
                        Provider { h.controller },
                    )
                val result = async { checker.notices.toListForTest() }
                started.await()
                h.identity.withLock {
                    val old = requireNotNull(h.db.syncStateDao().get())
                    h.db.syncStateDao().upsert(old.copy(accountId = "replacement", linkedAt = 2L))
                }
                release.complete(Unit)
                assertEquals(emptyList(), result.await())
                val state = requireNotNull(h.db.syncStateDao().get())
                assertTrue(state.enabled)
                assertNull(state.lastError)
                assertEquals("replacement", state.accountId)
            } finally {
                h.close()
            }
        }

    @Test
    fun theDailyCheckReadsTheRealAuthenticatedAccountEndpoint() =
        runTest {
            val server = MockWebServer()
            server.start()
            val http = httpClients()
            val fixture = Fixture(backgroundScope)
            try {
                fixture.link()
                val url = server.url("/").toString().trimEnd('/')
                fixture.address.updateServerUrl(url)
                val state = requireNotNull(fixture.db.syncStateDao().get())
                fixture.db.syncStateDao().upsert(state.copy(serverUrl = url))
                fixture.tokens.save(hostOf(url), "fixture-token")
                fixture.gate.setPlainHttpAllowed(true)
                server.enqueue(
                    MockResponse
                        .Builder()
                        .addHeader("Content-Type", "application/json")
                        .body(SyncJson.encodeToString(fixture.reply()))
                        .build(),
                )
                val client = SyncBindings.syncClient(http, fixture.tokens, fixture.address)
                assertEquals(
                    listOf(SyncNotice.PasswordSet("2026-10-03")),
                    fixture.checker(client).notices.toListForTest(),
                )
                val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("GET", request.method)
                assertEquals("/api/v1/account", request.url.encodedPath)
                assertEquals("Bearer fixture-token", request.headers["Authorization"])
                assertEquals(emptyList(), fixture.checker(client).notices.toListForTest())
                assertEquals(1, server.requestCount)
            } finally {
                fixture.close()
                http.close()
                server.close()
            }
        }

    @Test
    fun theProductionBusLazilyChecksAndDeliversThePasswordNotice() =
        runTest {
            val fixture = Fixture(backgroundScope)
            try {
                fixture.link()
                var resolutions = 0
                val checker = fixture.checker()
                val bus =
                    SyncBindings.syncNoticeBus(
                        Provider {
                            resolutions++
                            checker
                        },
                    )
                assertEquals(0, resolutions, "Constructing the bus must not resolve database-backed work")
                // Room uses real IO; a virtual-time timeout could expire before its reply.
                val notice =
                    withContext(Dispatchers.Default) {
                        withTimeoutOrNull(5_000) { bus.notices.firstOrNull() }
                    }
                assertEquals(SyncNotice.PasswordSet("2026-10-03"), notice)
                assertEquals(1, resolutions)
                assertEquals(1, fixture.calls)
                val stored = fixture.settings.get(SyncSettingKeys.ACCOUNT_NOTICE_STATE)
                assertFalse(stored.contains("fixture-token"))
                assertFalse(stored.contains("https://sync.example"))
                assertFalse(stored.contains("account-one"))
            } finally {
                fixture.close()
            }
        }

    @Test
    fun restartWithinADayDoesNotRequestAgainAndReceiptsSurviveLaterChecks() =
        runTest {
            val fixture = Fixture(backgroundScope)
            try {
                fixture.link()
                assertEquals(listOf(SyncNotice.PasswordSet("2026-10-03")), fixture.checker().notices.toListForTest())
                assertEquals(emptyList(), fixture.checker().notices.toListForTest())
                assertEquals(1, fixture.calls)
                fixture.clock.nowMs += 86_400_000L
                assertEquals(emptyList(), fixture.checker().notices.toListForTest())
                assertEquals(2, fixture.calls)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun anUnlinkedProfileNeverRequestsAccountInformation() =
        runTest {
            val fixture = Fixture(backgroundScope)
            try {
                assertEquals(emptyList(), fixture.checker().notices.toListForTest())
                assertEquals(0, fixture.calls)
                assertEquals("", fixture.settings.get(SyncSettingKeys.ACCOUNT_NOTICE_STATE))
            } finally {
                fixture.close()
            }
        }

    @Test
    fun anUnreadableDailyJournalCannotIssueAnUnthrottledRequest() =
        runTest {
            val backing = FakeSettingsRepository()
            val unavailable =
                object : SettingsRepository by backing {
                    override suspend fun <T : Any> getOrThrow(key: SettingKey<T>): T =
                        throw IOException("journal unavailable")
                }
            val fixture = Fixture(backgroundScope, unavailable)
            try {
                fixture.link()
                assertEquals(emptyList(), fixture.checker().notices.toListForTest())
                assertEquals(0, fixture.calls, "No request without a trustworthy daily throttle")
            } finally {
                fixture.close()
            }
        }

    @Test
    fun cancellingAWindowDoesNotLoseTheAppOwnedPendingNotice() =
        runTest {
            val fixture = Fixture(backgroundScope)
            try {
                fixture.link()
                val started = CompletableDeferred<Unit>()
                val reply = CompletableDeferred<AccountSummary>()
                val summary = fixture.reply()
                fixture.reply = {
                    started.complete(Unit)
                    reply.await()
                }
                val checker = fixture.checker()
                val oldWindow = async { checker.notices.toListForTest() }
                started.await()
                oldWindow.cancelAndJoin()
                reply.complete(summary)
                assertEquals(listOf(SyncNotice.PasswordSet("2026-10-03")), checker.notices.toListForTest())
                assertEquals(1, fixture.calls)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun aLateReplyForAReplacedAccountCannotPublishANotice() =
        runTest {
            val fixture = Fixture(backgroundScope)
            try {
                fixture.link()
                val started = CompletableDeferred<Unit>()
                val reply = CompletableDeferred<AccountSummary>()
                val summary = fixture.reply()
                fixture.reply = {
                    started.complete(Unit)
                    reply.await()
                }
                val pending = async { fixture.checker().notices.toListForTest() }
                started.await()
                fixture.identity.withLock {
                    val old = requireNotNull(fixture.db.syncStateDao().get())
                    fixture.db.syncStateDao().upsert(old.copy(accountId = "replacement", linkedAt = 2L))
                }
                reply.complete(summary)
                assertEquals(emptyList(), pending.await())
            } finally {
                fixture.close()
            }
        }

    @Test
    fun simultaneousCollectorsShareTheRequestAndDeliverOnlyOnce() =
        runTest {
            val fixture = Fixture(backgroundScope)
            try {
                fixture.link()
                val checker = fixture.checker()
                val first = async { checker.notices.toListForTest() }
                val second = async { checker.notices.toListForTest() }
                assertEquals(listOf(SyncNotice.PasswordSet("2026-10-03")), first.await() + second.await())
                assertEquals(1, fixture.calls)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun aFailedAttemptStillConsumesItsDailySlotAcrossRestart() =
        runTest {
            val fixture = Fixture(backgroundScope)
            try {
                fixture.link()
                fixture.reply = { throw IOException("fixture offline") }
                assertEquals(emptyList(), fixture.checker().notices.toListForTest())
                assertEquals(emptyList(), fixture.checker().notices.toListForTest())
                assertEquals(1, fixture.calls)
                fixture.clock.nowMs -= 1_000
                assertEquals(emptyList(), fixture.checker().notices.toListForTest())
                assertEquals(1, fixture.calls, "A backward clock must not reopen the daily slot")
            } finally {
                fixture.close()
            }
        }

    @Test
    fun aFailedThrottleWriteCannotReachTheServer() =
        runTest {
            val store = FakeSettingsRepository().apply { failNextSet = SettingsError.WriteFailed }
            val fixture = Fixture(backgroundScope, store)
            try {
                fixture.link()
                assertEquals(emptyList(), fixture.checker().notices.toListForTest())
                assertEquals(0, fixture.calls)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun offlineAndDeniedLocalAccessDoNotConsumeTheDailyBudget() =
        runTest {
            val fixture = Fixture(backgroundScope)
            try {
                fixture.link()
                fixture.network.setStatus(FakeNetworkMonitor.OFFLINE)
                assertEquals(emptyList(), fixture.checker().notices.toListForTest())
                fixture.network.setStatus(FakeNetworkMonitor.ONLINE)
                fixture.gate.setLocation(ch.lkmc.neutrodyne.core.domain.LocalNetworkLocation.LOCAL)
                fixture.gate.setPermissionGranted(false)
                assertEquals(emptyList(), fixture.checker().notices.toListForTest())
                assertEquals(0, fixture.calls)
                assertEquals("", fixture.settings.get(SyncSettingKeys.ACCOUNT_NOTICE_STATE))
            } finally {
                fixture.close()
            }
        }

    @Test
    fun equivalentInstantsAreDeduplicatedAndANewPasswordGetsANewNotice() =
        runTest {
            val fixture = Fixture(backgroundScope)
            try {
                fixture.link()
                val first = fixture.reply()
                assertEquals(listOf(SyncNotice.PasswordSet("2026-10-03")), fixture.checker().notices.toListForTest())
                fixture.clock.nowMs += 86_400_000L
                fixture.reply = { first.copy(passwordSetAt = "2026-10-03T14:34:56+02:00") }
                assertEquals(emptyList(), fixture.checker().notices.toListForTest())
                fixture.clock.nowMs += 86_400_000L
                fixture.reply = { first.copy(passwordSetAt = "2026-10-05T00:00:00Z") }
                assertEquals(listOf(SyncNotice.PasswordSet("2026-10-05")), fixture.checker().notices.toListForTest())
                assertEquals(3, fixture.calls)
            } finally {
                fixture.close()
            }
        }

    private suspend fun kotlinx.coroutines.flow.Flow<SyncNotice>.toListForTest(): List<SyncNotice> {
        val notices = mutableListOf<SyncNotice>()
        collect { notices += it }
        return notices
    }

    private class Fixture(
        private val scope: CoroutineScope,
        val settings: SettingsRepository = FakeSettingsRepository(),
    ) {
        val db = TestDb.inMemory()
        val clock = TestClock()
        val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
        val gate = FakeLocalNetworkGate()
        val address = FakeSyncServerAddress(serverUrl = "https://sync.example")
        val tokens = SyncTokenStore(FakeSecretStore())
        val identity = SyncIdentityLock()
        var calls = 0
        var reply: suspend () -> AccountSummary = {
            AccountSummary("alice", LibraryCountsDto(0, 0, 0, 0, 0), 1, 10_000, 0, "2026-10-03T12:34:56Z")
        }
        private val unsupported =
            Proxy.newProxyInstance(
                SyncClient::class.java.classLoader,
                arrayOf(SyncClient::class.java),
            ) { _, method, _ -> error("Unscripted client method " + method.name) } as SyncClient
        private val client =
            object : SyncClient by unsupported {
                override fun withCredentials(
                    serverUrl: String,
                    token: String,
                ): SyncClient? = null

                override suspend fun account(): AccountSummary {
                    calls++
                    return reply()
                }
            }

        suspend fun link() {
            db.syncStateDao().upsert(
                SyncStateEntity(
                    enabled = true,
                    serverUrl = "https://sync.example",
                    accountId = "account-one",
                    deviceId = "device-one",
                    nodeId = "0000000000000001",
                    linkedAt = 1L,
                ),
            )
            tokens.save("sync.example", "fixture-token")
        }

        fun checker(transport: SyncClient = client) =
            SyncPasswordNotices(
                db,
                transport,
                address,
                tokens,
                settings,
                clock,
                network,
                gate,
                identity,
                scope,
            )

        fun close() {
            db.close()
        }
    }

    private fun httpClients(): OkHttpNeutrodyneHttpClients {
        val platform =
            object : PlatformInfo {
                override val kind = PlatformKind.DESKTOP
                override val userAgentPlatform = "Linux; x64"
                override val androidSdkInt: Int? = null
                override val regionCode = "DE"
            }
        val userAgent = UserAgentProvider(platform, "0.1.0", "https://example.com/repo")
        return OkHttpNeutrodyneHttpClients(
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
        )
    }
}
