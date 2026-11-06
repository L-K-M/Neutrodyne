// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSyncServerAddress
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.sync.protocol.dto.DiscoveryDocument
import ch.lkmc.neutrodyne.sync.protocol.dto.ProtocolRange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 10 Live updates' supervisor: the gate opens one stream per gate-open period; `changed`
 * signals poke the scheduler's round path (remote only — the own echo and the event cursor
 * are ignored); clean ends wait the server `retry` hint; failures back off to the cap; `429`
 * waits `Retry-After`; a `401` records `Revoked` and stops everything; a stalled stream's
 * watchdog reconnects; and a missing `sse` feature never opens a stream.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEventsClientTest {
    private var db: NeutrodyneDatabase? = null

    @AfterTest
    fun closeDb() {
        db?.close()
    }

    @Test
    fun `a closed gate never opens a stream`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.start()

            deps.awaitQuiet()

            assertTrue(deps.transport.opens.isEmpty())
            assertTrue(!deps.events.connected.value)
        }

    @Test
    fun `an open gate connects once and carries the device token and hb`() =
        runTest {
            val deps = deps()
            deps.link(deviceId = "dev-B", token = "nds_secret")
            deps.transport.thenOpen(idleEventSession())
            deps.start(hb = 90)
            deps.allowed.value = true

            deps.await { deps.transport.opens.isNotEmpty() }

            val open = deps.transport.opens.single()
            assertEquals("https://sync.example", open.url)
            assertEquals("nds_secret", open.token)
            assertEquals(90, open.heartbeatSeconds)
            assertTrue(deps.events.connected.value)
        }

    @Test
    fun `a remote changed pokes while the own echo and the event cursor are ignored`() =
        runTest {
            val deps = deps()
            deps.link(deviceId = "dev-B")
            deps.transport.thenOpen(
                FakeSyncEventSession(
                    flowOf(
                        SyncStreamEvent.Changed(by = "dev-A"),
                        SyncStreamEvent.Changed(by = "dev-B"),
                        SyncStreamEvent.Changed(by = null),
                    ),
                ),
            )
            deps.transport.thenOpen(idleEventSession())
            deps.start()
            deps.allowed.value = true

            // The connect's catch-up, dev-A's signal and the unknown `by`: 3 pokes; dev-B's
            // own echo adds none.
            deps.await { deps.remotePokes.size >= 3 }
            assertEquals(3, deps.remotePokes.size)

            // The scripted stream ended: the reconnect's catch-up is the next poke.
            deps.await { deps.remotePokes.size >= 4 }
            assertEquals(4, deps.remotePokes.size)
        }

    @Test
    fun `a clean end reconnects after the server retry hint`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.transport.thenOpen(FakeSyncEventSession(flowOf(SyncStreamEvent.Comment)))
            deps.transport.thenOpen(idleEventSession())
            deps.start()
            deps.allowed.value = true

            deps.await { deps.transport.opens.size >= 1 }
            testScheduler.advanceTimeBy(9_000)
            testScheduler.runCurrent()
            assertEquals(1, deps.transport.opens.size)

            deps.await { deps.transport.opens.size >= 2 }
        }

    @Test
    fun `a retry line replaces the reconnect wait`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.transport.thenOpen(
                FakeSyncEventSession(flowOf(SyncStreamEvent.Retry(delayMs = 4_000))),
            )
            deps.transport.thenOpen(idleEventSession())
            deps.start()
            deps.allowed.value = true

            deps.await { deps.transport.opens.size >= 1 }
            testScheduler.advanceTimeBy(4_500)
            testScheduler.runCurrent()

            deps.await { deps.transport.opens.size >= 2 }
            // The server's 4-s hint applied — well under the 10-s default.
            assertTrue(testScheduler.currentTime < 10_000)
        }

    @Test
    fun `repeated open failures back off exponentially`() =
        runTest {
            val deps = deps()
            deps.link()
            val connect = SyncTransportException(0, null, "connect refused")
            repeat(4) { deps.transport.thenFail(connect) }
            deps.transport.thenOpen(idleEventSession())
            deps.start()
            deps.allowed.value = true

            deps.await { deps.transport.opens.size >= 1 }
            // Failure 1 waits 10 s ± 20 %: nothing new before 8 s.
            testScheduler.advanceTimeBy(6_000)
            testScheduler.runCurrent()
            assertEquals(1, deps.transport.opens.size)

            deps.await { deps.transport.opens.size >= 2 }
            // Failure 2 waits 20 s ± 20 %: nothing new before 16 s.
            testScheduler.advanceTimeBy(14_000)
            testScheduler.runCurrent()
            assertEquals(2, deps.transport.opens.size)

            deps.await { deps.transport.opens.size >= 3 }
        }

    @Test
    fun `a 429 waits Retry-After exactly`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.transport.thenFail(
                SyncTransportException(429, null, "throttled", retryAfterSeconds = 42),
            )
            deps.transport.thenOpen(idleEventSession())
            deps.start()
            deps.allowed.value = true

            deps.await { deps.transport.opens.size >= 1 }
            testScheduler.advanceTimeBy(40_000)
            testScheduler.runCurrent()
            assertEquals(1, deps.transport.opens.size)

            deps.await { deps.transport.opens.size >= 2 }
            assertTrue(deps.events.connected.value)
        }

    @Test
    fun `a 401 marks revoked, stops the stream and stops rescheduling`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.transport.thenFail(SyncTransportException(401, null, "revoked"))
            deps.start()
            deps.allowed.value = true

            deps.await { deps.revoked.isNotEmpty() }

            assertEquals(
                SYNC_ERROR_REVOKED,
                deps.db
                    .syncStateDao()
                    .get()
                    ?.lastError,
            )
            assertTrue(!deps.events.connected.value)
            deps.awaitQuiet()
            assertEquals(1, deps.transport.opens.size, "no reconnect after a 401")
        }

    @Test
    fun `a discover 401 revokes the same way`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.discoverFails = SyncTransportException(401, null, "revoked")
            deps.start()
            deps.allowed.value = true

            deps.await { deps.revoked.isNotEmpty() }
            assertEquals(
                SYNC_ERROR_REVOKED,
                deps.db
                    .syncStateDao()
                    .get()
                    ?.lastError,
            )
            assertTrue(deps.transport.opens.isEmpty())
        }

    @Test
    fun `a server without sse never opens a stream`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.features = listOf("link")
            deps.start()
            deps.allowed.value = true

            testScheduler.advanceTimeBy(60_000)
            testScheduler.runCurrent()
            deps.awaitQuiet()

            assertTrue(deps.transport.opens.isEmpty())
            assertTrue(deps.discovers.isNotEmpty())
        }

    @Test
    fun `a stalled stream reconnects after the watchdog`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.transport.thenOpen(idleEventSession())
            deps.transport.thenOpen(idleEventSession())
            deps.start(hb = 60)
            deps.allowed.value = true

            deps.await { deps.transport.opens.size >= 1 }
            // The watchdog fires after 2 × 60 + 15 s of silence: nothing before it.
            testScheduler.advanceTimeBy(120_000)
            testScheduler.runCurrent()
            assertEquals(1, deps.transport.opens.size)

            deps.await { deps.transport.opens.size >= 2 }
        }

    @Test
    fun `an unlink closes the stream at once`() =
        runTest {
            val deps = deps()
            deps.link()
            val session = idleEventSession()
            deps.transport.thenOpen(session)
            deps.start()
            deps.allowed.value = true
            deps.await { deps.transport.opens.size >= 1 }

            deps.unlink()
            deps.events.reevaluate()

            deps.await { session.isClosed }
            assertTrue(!deps.events.connected.value)
        }

    @Test
    fun `losing the network closes the stream and regain reopens it`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.transport.thenOpen(idleEventSession())
            deps.transport.thenOpen(idleEventSession())
            deps.start()
            deps.allowed.value = true
            deps.await { deps.transport.opens.size >= 1 }

            deps.network.setStatus(FakeNetworkMonitor.OFFLINE)
            deps.await { !deps.events.connected.value }

            deps.network.setStatus(FakeNetworkMonitor.ONLINE)
            deps.await { deps.transport.opens.size >= 2 }
            assertTrue(deps.events.connected.value)
        }

    @Test
    fun `the gate closing and reopening cycles the stream`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.transport.thenOpen(idleEventSession())
            deps.transport.thenOpen(idleEventSession())
            deps.start()
            deps.allowed.value = true
            deps.await { deps.transport.opens.size >= 1 }

            deps.allowed.value = false
            deps.await { !deps.events.connected.value }

            deps.allowed.value = true
            deps.await { deps.transport.opens.size >= 2 }
        }

    // --- fixture -----------------------------------------------------------------------------

    private class Deps(
        private val scope: TestScope,
        val db: NeutrodyneDatabase,
        val network: FakeNetworkMonitor,
        val transport: FakeSyncEventTransport,
        val remotePokes: MutableList<Unit>,
        val revoked: MutableList<Unit>,
        val discovers: MutableList<Unit>,
    ) {
        val allowed = MutableStateFlow(false)
        var features = listOf("sse", "link")
        var discoverFails: Throwable? = null

        private val clock = scope.clock
        val tokens = SyncTokenStore(FakeSecretStore())
        val address = FakeSyncServerAddress(serverUrl = "https://sync.example")

        val events =
            SyncEventsClient(
                db = db,
                transport = transport,
                discover = {
                    discovers += Unit
                    discoverFails?.let { throw it }
                    discovery(features)
                },
                address = address,
                tokens = tokens,
                network = network,
                scope = scope.backgroundScope,
                onRemoteChanged = { remotePokes += Unit },
                onRevoked = { revoked += Unit },
                random = Random(7),
            )

        fun start(hb: Int = 60) = events.start(allowed, hb)

        suspend fun link(
            deviceId: String = "dev-B",
            token: String = "nds_test",
        ) {
            db.withWriteTransaction {
                db.syncStateDao().upsert(
                    SyncStateEntity(
                        enabled = true,
                        serverUrl = "https://sync.example",
                        accountId = "acc",
                        deviceId = deviceId,
                        nodeId = "0000000000000001",
                        linkedAt = clock.now(),
                    ),
                )
            }
            tokens.save("sync.example", token)
            events.reevaluate()
        }

        suspend fun unlink() {
            db.withWriteTransaction {
                db.syncStateDao().upsert(
                    SyncStateEntity(
                        enabled = false,
                        serverUrl = "https://sync.example",
                    ),
                )
            }
            address.updateServerUrl(null)
        }

        /** Pumps virtual time forward while Room's real-dispatcher reads land in between. */
        fun await(
            deadlineMs: Long = 5_000,
            condition: () -> Boolean,
        ) {
            val deadline = System.nanoTime() + deadlineMs * 1_000_000L
            while (!condition()) {
                scope.testScheduler.runCurrent()
                scope.testScheduler.advanceTimeBy(50)
                if (System.nanoTime() > deadline) fail("events effect did not arrive")
                Thread.sleep(1)
            }
            scope.testScheduler.runCurrent()
        }

        /** Proves no effect: runs the pending window and a short real-time guard. */
        fun awaitQuiet(guardMs: Long = 300) {
            val deadline = System.nanoTime() + guardMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                scope.testScheduler.runCurrent()
                Thread.sleep(10)
            }
        }
    }

    private fun TestScope.deps(): Deps {
        val db = TestDb.inMemory().also { this@SyncEventsClientTest.db = it }
        return Deps(
            scope = this,
            db = db,
            network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
            transport = FakeSyncEventTransport(),
            remotePokes = mutableListOf(),
            revoked = mutableListOf(),
            discovers = mutableListOf(),
        )
    }

    private companion object {
        const val SERVER_RETRY_MS = 10_000L
    }
}

/** The shared virtual-time clock. */
private val TestScope.clock: Clock
    @OptIn(ExperimentalCoroutinesApi::class)
    get() = TestClock.from(testScheduler)

private fun discovery(features: List<String>): DiscoveryDocument =
    DiscoveryDocument(
        name = "test",
        serverVersion = "1.0.0",
        protocol = ProtocolRange(min = 1, max = 1),
        features = features,
        maxBatch = 1_000,
        maxSkewMs = 300_000,
        serverTime = "2026-11-06T00:00:00Z",
    )
