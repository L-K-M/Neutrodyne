// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.common.PowerEvent
import ch.lkmc.neutrodyne.core.common.PowerMonitor
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.sync.api.SyncReport
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * 10 Scheduling on the desktop's own triggers: a refocus after more than 60 s away pokes a
 * `FOREGROUND` round, closer refocuses collapse, and a power resume does the same so the pull
 * runs instead of waiting out the 15-min check.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopSyncTriggersTest {
    private var db: NeutrodyneDatabase? = null

    @AfterTest
    fun closeDb() {
        db?.close()
    }

    @Test
    fun `a refocus after 60 s pokes a foreground round`() =
        runTest {
            val deps = deps()
            deps.link()

            deps.triggers.onWindowFocus()
            assertEquals(listOf(SYNC_LANE_NAME), deps.pokes)

            deps.run()
            assertEquals(listOf(SyncRoundReason.FOREGROUND), deps.rounds)
        }

    @Test
    fun `refocuses inside the 60 s window collapse`() =
        runTest {
            val deps = deps()
            deps.link()

            deps.triggers.onWindowFocus()
            deps.clock.elapsedMs += 30_000
            deps.triggers.onWindowFocus()
            assertEquals(1, deps.pokes.size)

            deps.clock.elapsedMs += 31_000
            deps.triggers.onWindowFocus()
            assertEquals(2, deps.pokes.size)
        }

    @Test
    fun `a power resume pokes a foreground round`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.triggers.start()
            runCurrent()

            deps.powerEvents.emit(PowerEvent.Resumed)
            runCurrent()
            assertEquals(listOf(SYNC_LANE_NAME), deps.pokes)

            deps.run()
            assertEquals(listOf(SyncRoundReason.FOREGROUND), deps.rounds)
        }

    @Test
    fun `a suspend event alone pokes nothing`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.triggers.start()
            runCurrent()

            deps.powerEvents.emit(PowerEvent.Suspending)
            runCurrent()
            assertEquals(emptyList(), deps.pokes)
        }

    @Test
    fun `suspend closes the stream gate and resume reopens it with a round`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.triggers.start()
            runCurrent()
            assertTrue(deps.triggers.awake.value)

            // 10 Live updates: the stream must not outlive the machine's sleep.
            deps.powerEvents.emit(PowerEvent.Suspending)
            runCurrent()
            assertFalse(deps.triggers.awake.value)
            assertEquals(emptyList(), deps.pokes)

            deps.powerEvents.emit(PowerEvent.Resumed)
            runCurrent()
            assertTrue(deps.triggers.awake.value)
            assertEquals(listOf(SYNC_LANE_NAME), deps.pokes)
        }

    // --- plumbing --------------------------------------------------------------------------------

    private class Deps(
        scope: TestScope,
        val db: NeutrodyneDatabase,
        val clock: TestClock,
        val pokes: MutableList<String>,
        val rounds: MutableList<SyncRoundReason>,
        val powerEvents: MutableSharedFlow<PowerEvent>,
    ) {
        private val lane =
            DesktopSyncLane(
                db = db,
                round = { reason ->
                    rounds += reason
                    SyncReport(0, 0, 0, 0, 0, 0, 0)
                },
                network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
                clock = clock,
                poker = Provider { JobLanePoker { name -> pokes += name } },
            )

        val triggers =
            DesktopSyncTriggers(
                lane = Provider { lane },
                power =
                    object : PowerMonitor {
                        override val events: Flow<PowerEvent> = powerEvents
                    },
                clock = clock,
                scope = scope.backgroundScope,
            )

        suspend fun run() = lane.run(Instant.fromEpochMilliseconds(clock.now()))

        suspend fun link() {
            db.withWriteTransaction {
                db.syncStateDao().upsert(
                    SyncStateEntity(
                        enabled = true,
                        serverUrl = "https://sync.example",
                        accountId = "acc",
                        deviceId = "dev",
                        nodeId = "0000000000000001",
                        linkedAt = clock.now(),
                    ),
                )
            }
        }
    }

    private fun TestScope.deps(): Deps {
        val db = TestDb.inMemory().also { this@DesktopSyncTriggersTest.db = it }
        return Deps(
            scope = this,
            db = db,
            clock = TestClock(),
            pokes = mutableListOf(),
            rounds = mutableListOf(),
            powerEvents = MutableSharedFlow(extraBufferCapacity = 4),
        )
    }
}
