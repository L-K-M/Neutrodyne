// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSyncServerAddress
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.sync.api.SyncStreamState
import ch.lkmc.neutrodyne.sync.protocol.dto.DiscoveryDocument
import ch.lkmc.neutrodyne.sync.protocol.dto.ProtocolRange
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * 10 Live updates' supervisor: gates on link/network/platform/discovery, drops the own-device
 * echo, pokes the scheduler on remote changes and reconnects, honors `retry:`/`Retry-After`,
 * watchdogs silent streams and persists `401` as the same revoked write the engine performs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEventsClientTest {
    private var db: NeutrodyneDatabase? = null

    @AfterTest
    fun closeDb() {
        db?.close()
    }

    @Test
    fun `the stream opens only once the link and the gate allow it`() =
        runTest {
            val deps = deps()
            deps.start()
            runCurrent()
            assertEquals(emptyList(), deps.transport.opens)

            deps.link()
            runCurrent()
            assertEquals(1, deps.transport.opens.size)
            val open = deps.transport.opens.single()
            assertEquals("https://sync.example", open.baseUrl)
            assertEquals("tok", open.token)
            assertEquals(60, open.heartbeatSeconds)
        }

    @Test
    fun `a server without the sse feature never sees an open`() =
        runTest {
            val deps = deps(features = emptyList())
            deps.start()
            deps.link()
            runCurrent()
            assertEquals(emptyList(), deps.transport.opens)

            // The capability re-check picks the feature up without a restart.
            deps.features = listOf("sse")
            advanceTimeBy(300_000)
            runCurrent()
            assertEquals(1, deps.transport.opens.size)
        }

    @Test
    fun `the own-device echo is dropped and a missing by counts as remote`() =
        runTest {
            val deps = deps()
            deps.start()
            deps.link()
            runCurrent()
            val session = deps.transport.sessions.single()
            val base = deps.pokes.size

            session.emit(SyncStreamEvent.Changed(by = "dev-1"))
            runCurrent()
            assertEquals(base, deps.pokes.size)

            session.emit(SyncStreamEvent.Changed(by = "dev-2"))
            session.emit(SyncStreamEvent.Changed(by = null))
            runCurrent()
            assertEquals(base + 2, deps.pokes.size)
        }

    @Test
    fun `every reconnect pokes one round because the bus does not replay`() =
        runTest {
            val deps = deps()
            deps.start()
            deps.link()
            runCurrent()
            assertEquals(1, deps.pokes.size)

            deps.transport.sessions
                .single()
                .end()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(2, deps.transport.opens.size)
            assertEquals(2, deps.pokes.size)
        }

    @Test
    fun `a server retry hint sets the reconnect delay`() =
        runTest {
            val deps = deps()
            deps.start()
            deps.link()
            runCurrent()
            val session = deps.transport.sessions.single()

            session.emit(SyncStreamEvent.Retry(30_000))
            session.end()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, deps.transport.opens.size)

            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(2, deps.transport.opens.size)
        }

    @Test
    fun `a silent stream watchdogs into a backed-off reconnect`() =
        runTest {
            val deps = deps()
            deps.start()
            deps.link()
            runCurrent()
            val session = deps.transport.sessions.single()

            // 2 x 60 s + 15 s of silence trips the watchdog; the reconnect rides the backoff.
            advanceTimeBy(135_000)
            runCurrent()
            session.awaitClosed()
            assertEquals(1, deps.transport.opens.size)

            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(2, deps.transport.opens.size)
        }

    @Test
    fun `a 429 waits out its Retry-After`() =
        runTest {
            val deps = deps()
            deps.transport.next(
                FakeSyncEventTransport.Outcome.Reject(
                    SyncTransportException(429, null, "slow down", retryAfterSeconds = 30),
                ),
            )
            deps.start()
            deps.link()
            runCurrent()
            assertEquals(1, deps.transport.opens.size)

            deps.transport.next(FakeSyncEventTransport.Outcome.Stream)
            advanceTimeBy(29_000)
            runCurrent()
            assertEquals(1, deps.transport.opens.size)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, deps.transport.opens.size)
        }

    @Test
    fun `a 401 persists the revoked write and stops the supervisor`() =
        runTest {
            val deps = deps()
            deps.transport.next(
                FakeSyncEventTransport.Outcome.Reject(
                    SyncTransportException(401, null, "revoked"),
                ),
            )
            deps.start()
            deps.link()
            runCurrent()

            val state = deps.db.syncStateDao().get()!!
            assertEquals(SYNC_ERROR_REVOKED, state.lastError)
            assertFalse(state.enabled)
            assertTrue(deps.revoked)

            // The gate reads dead now: nothing re-opens.
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals(1, deps.transport.opens.size)
        }

    @Test
    fun aStaleOpen401CannotRevokeAReplacementAccount() =
        runTest {
            val deps = deps()
            deps.beforeOpen = {
                deps.beforeOpen = null
                // The response can arrive between committing B and notifying the scheduler.
                deps.link(accountId = "replacement", token = "replacement-token", notify = false)
                throw SyncTransportException(401, null, "old token revoked")
            }
            deps.start()
            deps.link()
            runCurrent()

            val state = deps.db.syncStateDao().get()!!
            assertEquals("replacement", state.accountId)
            assertTrue(state.enabled, "The old stream must not disable the replacement link")
            assertFalse(state.lastError == SYNC_ERROR_REVOKED)
            assertFalse(deps.revoked, "Old responses must not cancel the replacement's scheduled work")
        }

    @Test
    fun aStaleRead401CannotRevokeAReplacementAccount() =
        runTest {
            val deps = deps()
            deps.start()
            deps.link()
            runCurrent()
            val oldSession = deps.transport.sessions.single()

            deps.link(accountId = "replacement", token = "replacement-token", notify = false)
            oldSession.fail(SyncTransportException(401, null, "old token revoked"))
            runCurrent()

            val state = deps.db.syncStateDao().get()!!
            assertTrue(state.enabled)
            assertEquals("replacement", state.accountId)
            assertFalse(deps.revoked)
            assertEquals(
                "replacement-token",
                deps.transport.opens
                    .last()
                    .token,
            )
        }

    @Test
    fun aCurrentRead401RevokesItsOwnLinkAndClosesTheSession() =
        runTest {
            val deps = deps()
            deps.start()
            deps.link()
            runCurrent()
            val session = deps.transport.sessions.single()

            session.fail(SyncTransportException(401, null, "token revoked"))
            runCurrent()

            assertFalse(
                deps.db
                    .syncStateDao()
                    .get()!!
                    .enabled,
            )
            assertEquals(
                SYNC_ERROR_REVOKED,
                deps.db
                    .syncStateDao()
                    .get()!!
                    .lastError,
            )
            assertTrue(deps.revoked)
            val closed = backgroundScope.async { session.awaitClosed() }
            runCurrent()
            assertTrue(closed.isCompleted)
            assertFalse(deps.client.connected.value)
        }

    @Test
    fun revocationCleanupFinishesWhenItsGateClosesTheCurrentStream() =
        runTest {
            val deps = deps()
            deps.beforeRevokedComplete = {
                deps.client.reevaluate()
                kotlinx.coroutines.delay(10)
            }
            deps.start()
            deps.link()
            runCurrent()

            deps.transport.sessions
                .single()
                .fail(SyncTransportException(401, null, "revoked"))
            runCurrent()
            advanceTimeBy(10)
            runCurrent()

            assertTrue(deps.revoked, "Closing the stream gate must not interrupt its owned cleanup")
            assertFalse(deps.client.connected.value)
        }

    @Test
    fun replacingALiveAccountClosesItsStreamAndUsesTheNewToken() =
        runTest {
            val deps = deps()
            deps.start()
            deps.link()
            runCurrent()
            val oldSession = deps.transport.sessions.single()

            deps.link(accountId = "replacement", token = "replacement-token")
            runCurrent()

            assertEquals(2, deps.transport.opens.size, "A link identity change must replace the open stream")
            assertEquals(
                "replacement-token",
                deps.transport.opens
                    .last()
                    .token,
            )
            val closed = backgroundScope.async { oldSession.awaitClosed() }
            runCurrent()
            assertTrue(closed.isCompleted, "The previous account's stream must be closed")
            assertTrue(deps.client.connected.value)
        }

    @Test
    fun `the allowed gate closes the stream and reopening reconnects`() =
        runTest {
            val deps = deps()
            deps.start()
            deps.link()
            runCurrent()
            val session = deps.transport.sessions.single()
            assertTrue(deps.client.connected.value)

            deps.allowed.value = false
            runCurrent()
            session.awaitClosed()
            assertFalse(deps.client.connected.value)

            deps.allowed.value = true
            runCurrent()
            assertEquals(2, deps.transport.opens.size)
            assertTrue(deps.client.connected.value)
        }

    @Test
    fun `losing the network closes the stream and its return reconnects`() =
        runTest {
            val deps = deps()
            deps.start()
            deps.link()
            runCurrent()
            val session = deps.transport.sessions.single()

            deps.network.setStatus(FakeNetworkMonitor.OFFLINE)
            runCurrent()
            session.awaitClosed()

            deps.network.setStatus(FakeNetworkMonitor.ONLINE)
            runCurrent()
            assertEquals(2, deps.transport.opens.size)
        }

    @Test
    fun `relinking after a revoke opens a fresh stream`() =
        runTest {
            val deps = deps()
            deps.transport.next(
                FakeSyncEventTransport.Outcome.Reject(
                    SyncTransportException(401, null, "revoked"),
                ),
            )
            deps.start()
            deps.link()
            runCurrent()
            assertTrue(deps.revoked)
            assertEquals(1, deps.transport.opens.size)

            deps.transport.next(FakeSyncEventTransport.Outcome.Stream)
            deps.link()
            runCurrent()
            assertEquals(2, deps.transport.opens.size)
        }

    @Test
    fun desktopLaneRevocationClosesAnAlreadyOpenStream() =
        runTest {
            val deps = deps()
            deps.start()
            deps.link()
            runCurrent()
            assertTrue(deps.client.connected.value)
            val rejection = SyncTransportException(401, null, "revoked")
            val lane =
                DesktopSyncLane(
                    db = deps.db,
                    round = {
                        // The real engine persists revocation before reporting its HTTP failure.
                        deps.db.syncStateDao().markRevoked(SYNC_ERROR_REVOKED)
                        throw rejection
                    },
                    network = deps.network,
                    clock = TestClock(),
                    poker = Provider { JobLanePoker {} },
                    onRevoked = { deps.client.reevaluate() },
                )
            val failure =
                try {
                    lane.run(Instant.fromEpochMilliseconds(1_791_072_000_000L))
                    null
                } catch (failure: SyncTransportException) {
                    failure
                }
            assertSame(rejection, failure)
            runCurrent()
            assertFalse(deps.client.connected.value)
            deps.transport.sessions
                .single()
                .awaitClosed()
        }

    // --- plumbing --------------------------------------------------------------------------------

    @Test
    fun discoveryAndStreamStateReachTheLocalDiagnosticsWithoutAnotherRequest() =
        runTest {
            val deps = deps()
            deps.start()
            deps.link()
            runCurrent()
            val profile =
                deps.db
                    .syncStateDao()
                    .get()
                    .diagnosticProfile("https://sync.example")
            val snapshot = deps.runtime.snapshot(profile)
            assertEquals("1.0", snapshot.serverVersion)
            assertEquals(SyncStreamState.CONNECTED, snapshot.streamState)
            assertEquals(0, snapshot.reconnectCount)
            assertEquals(1, deps.transport.opens.size)
            assertEquals(1, deps.discoveries)
        }

    @Test
    fun reconnectsAndGateClosureHaveDistinctDiagnostics() =
        runTest {
            val deps = deps()
            deps.start()
            deps.link()
            runCurrent()
            val profile =
                deps.db
                    .syncStateDao()
                    .get()
                    .diagnosticProfile("https://sync.example")
            deps.transport.sessions
                .single()
                .end()
            runCurrent()
            assertEquals(SyncStreamState.WAITING, deps.runtime.snapshot(profile).streamState)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, deps.runtime.snapshot(profile).reconnectCount)
            assertEquals(SyncStreamState.CONNECTED, deps.runtime.snapshot(profile).streamState)
            deps.allowed.value = false
            runCurrent()
            assertEquals(SyncStreamState.STOPPED, deps.runtime.snapshot(profile).streamState)
        }

    @Test
    fun cancellationFromTheInitialPokeClosesTheOpenedSession() =
        runTest {
            val deps = deps()
            deps.pokeFailure = CancellationException("cancel initial poke")
            deps.start()
            deps.link()
            runCurrent()
            val session = deps.transport.sessions.single()
            val closed = backgroundScope.async { session.awaitClosed() }
            runCurrent()
            assertTrue(closed.isCompleted, "the session must close even when the initial callback cancels")
            assertFalse(deps.client.connected.value)
        }

    private class Deps(
        scope: TestScope,
        val db: NeutrodyneDatabase,
        var features: List<String>,
    ) {
        val transport = FakeSyncEventTransport()
        val address = FakeSyncServerAddress("https://sync.example")
        val secrets = FakeSecretStore()
        val tokens = SyncTokenStore(secrets)
        val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
        val allowed = MutableStateFlow(false)
        val pokes = mutableListOf<Unit>()
        var revoked = false
        val runtime = SyncRuntimeDiagnostics()
        var discoveries = 0
        var pokeFailure: CancellationException? = null
        var beforeOpen: (suspend () -> Unit)? = null
        var beforeRevokedComplete: (suspend () -> Unit)? = null

        val client =
            SyncEventsClient(
                db = db,
                transport =
                    object : SyncEventTransport by transport.transport() {
                        override suspend fun open(
                            baseUrl: String,
                            token: String,
                            heartbeatSeconds: Int,
                        ): SyncEventSession {
                            beforeOpen?.invoke()
                            return transport.transport().open(baseUrl, token, heartbeatSeconds)
                        }
                    },
                discover = { _ ->
                    discoveries++
                    DiscoveryDocument(
                        name = "test",
                        serverVersion = "1.0",
                        protocol = ProtocolRange(min = 1, max = 1),
                        features = features,
                        maxBatch = 200,
                        maxSkewMs = 60_000,
                        serverTime = "2026-01-01T00:00:00Z",
                    )
                },
                address = address,
                tokens = tokens,
                network = network,
                scope = scope.backgroundScope,
                onRemoteChanged = {
                    pokeFailure?.let { throw it }
                    pokes += Unit
                },
                onRevoked = {
                    beforeRevokedComplete?.invoke()
                    revoked = true
                },
                random = Random(1),
                diagnosticsRuntime = runtime,
            )

        fun start(heartbeatSeconds: Int = 60) {
            client.start(allowed, heartbeatSeconds)
            allowed.value = true
        }

        /** Writes the live-link row + token the supervisor's gate reads, then wakes it. */
        suspend fun link(
            accountId: String = "acc",
            token: String = "tok",
            notify: Boolean = true,
        ) {
            db.withWriteTransaction {
                db.syncStateDao().upsert(
                    SyncStateEntity(
                        enabled = true,
                        serverUrl = "https://sync.example",
                        accountId = accountId,
                        deviceId = "dev-1",
                        nodeId = "0000000000000001",
                        linkedAt = 1_791_072_000_000L,
                    ),
                )
            }
            tokens.save(hostOf("https://sync.example"), token)
            runtime.activate(db.syncStateDao().get().diagnosticProfile("https://sync.example"))
            if (notify) client.reevaluate()
        }
    }

    private fun TestScope.deps(features: List<String> = listOf("sse")): Deps {
        // The supervisor's gate re-reads `sync_state` per attempt: keep those queries on the
        // test scheduler so `runCurrent`/`advanceTimeBy` cover them like SyncSchedulerTest.
        val db =
            TestDb
                .inMemory(
                    queryContext = StandardTestDispatcher(testScheduler),
                ).also { this@SyncEventsClientTest.db = it }
        return Deps(scope = this, db = db, features = features)
    }
}
