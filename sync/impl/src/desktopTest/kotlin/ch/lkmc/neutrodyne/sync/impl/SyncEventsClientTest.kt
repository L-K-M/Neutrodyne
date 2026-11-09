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
import ch.lkmc.neutrodyne.sync.protocol.dto.DiscoveryDocument
import ch.lkmc.neutrodyne.sync.protocol.dto.ProtocolRange
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

        val client =
            SyncEventsClient(
                db = db,
                transport = transport.transport(),
                discover = { _ ->
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
                onRemoteChanged = { pokes += Unit },
                onRevoked = { revoked = true },
                random = Random(1),
            )

        fun start(heartbeatSeconds: Int = 60) {
            client.start(allowed, heartbeatSeconds)
            allowed.value = true
        }

        /** Writes the live-link row + token the supervisor's gate reads, then wakes it. */
        suspend fun link() {
            db.withWriteTransaction {
                db.syncStateDao().upsert(
                    SyncStateEntity(
                        enabled = true,
                        serverUrl = "https://sync.example",
                        accountId = "acc",
                        deviceId = "dev-1",
                        nodeId = "0000000000000001",
                        linkedAt = 1_791_072_000_000L,
                    ),
                )
            }
            tokens.save(hostOf("https://sync.example"), "tok")
            client.reevaluate()
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
