// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePlaybackSyncPort
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSyncServerAddress
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.sync.protocol.dto.DiscoveryDocument
import ch.lkmc.neutrodyne.sync.protocol.dto.ProtocolRange
import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes

/**
 * 10 Live updates on Android (MS3 acceptance 4's client half): the events stream is allowed
 * while the UI is at least `STARTED` or playback runs, and it closes 60 s after both became
 * false — a backgrounding blip keeps the connection. `start` arms the client with `hb=90`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEventsGateTest {
    private val app = RuntimeEnvironment.getApplication()
    private val playback = FakePlaybackSyncPort()
    private lateinit var db: NeutrodyneDatabase
    private lateinit var workScheduler: WorkManagerSyncScheduler
    private lateinit var gate: SyncEventsGate

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private fun TestScope.fixture() {
        db = TestDb.inMemory(app)
        workScheduler = WorkManagerSyncScheduler(application = app, db = db, playback = playback)
        gate =
            SyncEventsGate(
                workScheduler = Provider { workScheduler },
                playback = playback,
                events = Provider { error("the gate test never resolves the client") },
            )
    }

    /** Collects [SyncEventsGate.sseAllowed] into a list on the test scheduler. */
    private fun TestScope.collectAllowed(): MutableList<Boolean> {
        val collected = mutableListOf<Boolean>()
        backgroundScope.launch { gate.sseAllowed().toList(collected) }
        testScheduler.runCurrent()
        return collected
    }

    @Test
    fun `the gate is closed while backgrounded and silent`() =
        runTest {
            fixture()
            val collected = collectAllowed()
            testScheduler.advanceTimeBy(120_000)
            testScheduler.runCurrent()

            assertThat(collected).containsExactly(false)
        }

    @Test
    fun `the gate opens while the UI is visible`() =
        runTest {
            fixture()
            val collected = collectAllowed()

            workScheduler.uiVisible.value = true
            testScheduler.runCurrent()

            assertThat(collected).containsExactly(false, true)
        }

    @Test
    fun `the gate closes 60 s after backgrounding without playback`() =
        runTest {
            fixture()
            val collected = collectAllowed()

            workScheduler.uiVisible.value = true
            testScheduler.runCurrent()
            workScheduler.uiVisible.value = false
            testScheduler.runCurrent()
            // Inside the grace window the stream stays up.
            testScheduler.advanceTimeBy(59_000)
            testScheduler.runCurrent()
            assertThat(collected).containsExactly(false, true)

            testScheduler.advanceTimeBy(2_000)
            testScheduler.runCurrent()
            assertThat(collected).containsExactly(false, true, false)
        }

    @Test
    fun `re-foregrounding inside the grace window keeps the stream`() =
        runTest {
            fixture()
            val collected = collectAllowed()

            workScheduler.uiVisible.value = true
            testScheduler.runCurrent()
            workScheduler.uiVisible.value = false
            testScheduler.advanceTimeBy(30_000)
            workScheduler.uiVisible.value = true
            testScheduler.advanceTimeBy(90_000)
            testScheduler.runCurrent()

            assertThat(collected).containsExactly(false, true)
        }

    @Test
    fun `playback alone keeps the gate open`() =
        runTest {
            fixture()
            val collected = collectAllowed()

            playback.load(episodeId = 1L, playing = true)
            testScheduler.runCurrent()
            workScheduler.uiVisible.value = false
            testScheduler.advanceTimeBy(120_000)
            testScheduler.runCurrent()

            assertThat(collected).containsExactly(false, true)
        }

    @Test
    fun `playback stopping while backgrounded closes the gate 60 s later`() =
        runTest {
            fixture()
            val collected = collectAllowed()

            playback.load(episodeId = 1L, playing = true)
            testScheduler.runCurrent()
            playback.setPlaying(false)
            testScheduler.runCurrent()

            testScheduler.advanceTimeBy(61_000)
            testScheduler.runCurrent()

            assertThat(collected).containsExactly(false, true, false)
        }

    @Test
    fun `start arms the client with the Android heartbeat`() =
        // Room and the stream's discovery ride real dispatchers — on a loaded build machine the
        // default 1-minute runTest timeout can elapse before the real work completes.
        runTest(timeout = 3.minutes) {
            fixture()
            linkDb()
            val tokens = SyncTokenStore(FakeSecretStore())
            tokens.save("sync.example", "nds_test")
            val transport = FakeSyncEventTransport()
            transport.thenOpen(idleEventSession())
            val events =
                SyncEventsClient(
                    db = db,
                    transport = transport,
                    discover = { discovery() },
                    address = FakeSyncServerAddress(serverUrl = "https://sync.example"),
                    tokens = tokens,
                    network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
                    scope = backgroundScope,
                    onRemoteChanged = {},
                    onRevoked = {},
                )
            val gate =
                SyncEventsGate(
                    workScheduler = Provider { workScheduler },
                    playback = playback,
                    events = Provider { events },
                )

            gate.start()
            workScheduler.uiVisible.value = true

            // Room runs on a real dispatcher — pump virtual and real time together.
            val deadline = System.nanoTime() + 30_000_000_000L
            while (transport.opens.isEmpty()) {
                testScheduler.runCurrent()
                testScheduler.advanceTimeBy(50)
                if (System.nanoTime() > deadline) error("the stream never opened")
                Thread.sleep(1)
            }
            assertThat(transport.opens.single().heartbeatSeconds).isEqualTo(90)
        }

    private suspend fun linkDb() {
        db.withWriteTransaction {
            db.syncStateDao().upsert(
                SyncStateEntity(
                    enabled = true,
                    serverUrl = "https://sync.example",
                    accountId = "acc",
                    deviceId = "dev",
                    nodeId = "0000000000000001",
                    linkedAt = 1,
                ),
            )
        }
    }

    private fun discovery(): DiscoveryDocument =
        DiscoveryDocument(
            name = "test",
            serverVersion = "1.0.0",
            protocol = ProtocolRange(min = 1, max = 1),
            features = listOf("sse", "link"),
            maxBatch = 1_000,
            maxSkewMs = 300_000,
            serverTime = "2026-11-06T00:00:00Z",
        )
}
