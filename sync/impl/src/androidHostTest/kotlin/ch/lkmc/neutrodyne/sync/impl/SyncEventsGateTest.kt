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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes

/**
 * 10 Live updates' Android gate: the stream is allowed while the UI is `STARTED` or playback
 * runs, stays open for 60 s after both stop — and a `false` still reaches the supervisor at
 * once when the app starts backgrounded (an un-emitted gate would leave the stream's initial
 * state undefined for a whole grace period).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEventsGateTest {
    private val app = RuntimeEnvironment.getApplication()
    private var db: NeutrodyneDatabase? = null

    @After
    fun closeDb() {
        db?.close()
    }

    @Test
    fun `a backgrounded start emits false at once, not after the grace`() =
        runTest(timeout = 3.minutes) {
            val deps = deps()
            // No emissions for 60 s would be the defect: the gate must answer immediately.
            assertThat(deps.gate.sseAllowed().first()).isFalse()
        }

    @Test
    fun `the ui or playback allows the stream, both stopping starts the 60 s grace`() =
        runTest(timeout = 3.minutes) {
            val deps = deps()
            val emissions = mutableListOf<Boolean>()
            val job =
                backgroundScope
                    .launch {
                        deps.gate.sseAllowed().collect { emissions += it }
                    }
            runCurrent()
            assertThat(emissions).isEqualTo(listOf(false))

            deps.workScheduler.uiVisible.value = true
            runCurrent()
            assertThat(emissions).isEqualTo(listOf(false, true))

            // UI gone but playback now playing: still allowed.
            deps.workScheduler.uiVisible.value = false
            deps.playback.setPlaying(true)
            runCurrent()
            assertThat(emissions).isEqualTo(listOf(false, true))

            // Playback stops too: the grace keeps the stream open for 60 s.
            deps.playback.setPlaying(false)
            advanceTimeBy(59_000)
            runCurrent()
            assertThat(emissions).isEqualTo(listOf(false, true))
            advanceTimeBy(1_000)
            runCurrent()
            assertThat(emissions).isEqualTo(listOf(false, true, false))

            job.cancel()
        }

    @Test
    fun `returning inside the grace cancels the pending false`() =
        runTest(timeout = 3.minutes) {
            val deps = deps()
            val emissions = mutableListOf<Boolean>()
            val job =
                backgroundScope
                    .launch {
                        deps.gate.sseAllowed().collect { emissions += it }
                    }
            runCurrent()

            deps.workScheduler.uiVisible.value = true
            runCurrent()
            deps.workScheduler.uiVisible.value = false
            advanceTimeBy(30_000)
            runCurrent()
            deps.workScheduler.uiVisible.value = true
            advanceTimeBy(60_000)
            runCurrent()

            // The pending false died with the re-activation; the gate stayed open the whole
            // time — the supervisor sees no flap.
            assertThat(emissions).isEqualTo(listOf(false, true))
            job.cancel()
        }

    @Test
    fun `the supervisor takes the gate and heartbeat on start`() =
        runTest(timeout = 3.minutes) {
            val deps = deps()
            deps.gate.start()
            deps.link()
            runCurrent()

            assertThat(deps.transport.opens).isEmpty()
            deps.workScheduler.uiVisible.value = true
            runCurrent()
            assertThat(
                deps.transport.opens
                    .single()
                    .heartbeatSeconds,
            ).isEqualTo(SSE_HEARTBEAT_ANDROID_SECONDS)
        }

    // --- plumbing ---------------------------------------------------------------------------

    private class Deps(
        scope: TestScope,
        val db: NeutrodyneDatabase,
    ) {
        val playback = FakePlaybackSyncPort()
        val transport = FakeSyncEventTransport()
        val address = FakeSyncServerAddress("https://sync.example")
        val tokens = SyncTokenStore(FakeSecretStore())
        val network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE)
        val pokes = mutableListOf<Unit>()

        val workScheduler =
            WorkManagerSyncScheduler(
                application = RuntimeEnvironment.getApplication(),
                db = db,
                playback = playback,
            )

        val events =
            SyncEventsClient(
                db = db,
                transport = transport.transport(),
                discover = { _ ->
                    DiscoveryDocument(
                        name = "test",
                        serverVersion = "1.0",
                        protocol = ProtocolRange(min = 1, max = 1),
                        features = listOf("sse"),
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
                onRevoked = {},
            )

        val gate =
            SyncEventsGate(
                scheduler = Provider { workScheduler },
                playback = playback,
                events = Provider { events },
            )

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
            events.reevaluate()
        }
    }

    private fun TestScope.deps(): Deps {
        // The supervisor re-reads `sync_state` per attempt — keep those queries on the test
        // scheduler so `runCurrent`/`advanceTimeBy` cover them.
        val db =
            TestDb
                .inMemory(
                    app,
                    queryContext = StandardTestDispatcher(testScheduler),
                ).also { this@SyncEventsGateTest.db = it }
        return Deps(scope = this, db = db)
    }
}
