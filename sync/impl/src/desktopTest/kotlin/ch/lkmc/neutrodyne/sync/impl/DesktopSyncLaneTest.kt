// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.sync.api.SyncReport
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * 10 Scheduling on the desktop — the `sync` lane: a tick or poke rounds only while linked and
 * online; an event poke carries its reason; a bare tick infers it from `sync_outbox` or falls
 * back to the 15-min pull.
 */
class DesktopSyncLaneTest {
    private var db: NeutrodyneDatabase? = null

    @AfterTest
    fun closeDb() {
        db?.close()
    }

    @Test
    fun `an outbox poke runs the push round it carried`() =
        runTest {
            val deps = deps()
            deps.link()

            deps.lane.poke(SyncRoundReason.OUTBOX)
            assertEquals(listOf(SYNC_LANE_NAME), deps.pokes)

            deps.run()
            assertEquals(listOf(SyncRoundReason.OUTBOX), deps.rounds)
        }

    @Test
    fun `a boundary poke flushes pos rows even right after a round`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.writeOutbox(coll = "episode", rid = "e1", field = "pos")

            deps.lane.poke(SyncRoundReason.PLAYBACK)
            deps.run()
            // A second boundary 5 s later still rounds — the cadence caps only the inferred
            // tick path, never an event the scheduler already judged due.
            deps.clock.elapsedMs += 5_000
            deps.writeOutbox(coll = "episode", rid = "e1", field = "pos")
            deps.lane.poke(SyncRoundReason.PLAYBACK)
            deps.run()

            assertEquals(listOf(SyncRoundReason.PLAYBACK, SyncRoundReason.PLAYBACK), deps.rounds)
        }

    @Test
    fun `a bare tick infers outbox then playback then periodic`() =
        runTest {
            val deps = deps()
            deps.link()

            deps.writeOutbox(coll = "podcast", rid = "p1", field = "subscribed")
            deps.run()
            assertEquals(listOf(SyncRoundReason.OUTBOX), deps.rounds)

            deps.writeOutbox(coll = "episode", rid = "e1", field = "pos")
            // Only the playback row remains.
            db!!.withWriteTransaction { db!!.syncOutboxDao().deleteField("podcast", "p1", "subscribed") }
            deps.run()
            assertEquals(listOf(SyncRoundReason.OUTBOX, SyncRoundReason.PLAYBACK), deps.rounds)

            db!!.withWriteTransaction { db!!.syncOutboxDao().deleteField("episode", "e1", "pos") }
            deps.clock.nowMs += 20 * 60_000
            deps.run()
            assertEquals(
                listOf(SyncRoundReason.OUTBOX, SyncRoundReason.PLAYBACK, SyncRoundReason.PERIODIC),
                deps.rounds,
            )
        }

    @Test
    fun `the first tick after startup runs the periodic pull`() =
        runTest {
            val deps = deps()
            deps.link()

            deps.run()
            assertEquals(listOf(SyncRoundReason.PERIODIC), deps.rounds)
        }

    @Test
    fun `a tick inside the pull interval does nothing`() =
        runTest {
            val deps = deps()
            deps.link()

            deps.run()
            deps.clock.nowMs += 5 * 60_000
            deps.run()

            assertEquals(listOf(SyncRoundReason.PERIODIC), deps.rounds)
        }

    @Test
    fun `offline ticks and pokes cost nothing and keep the reason`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.network.setStatus(FakeNetworkMonitor.OFFLINE)

            deps.lane.poke(SyncRoundReason.OUTBOX)
            deps.run()
            assertTrue(deps.rounds.isEmpty())

            // Regain: the runner re-pokes; the remembered reason still drives the round.
            deps.network.setStatus(FakeNetworkMonitor.ONLINE)
            deps.run()
            assertEquals(listOf(SyncRoundReason.OUTBOX), deps.rounds)
        }

    @Test
    fun `unlinked costs nothing and drops the remembered reason`() =
        runTest {
            val deps = deps()
            // Never linked at all.
            deps.lane.poke(SyncRoundReason.OUTBOX)
            deps.run()
            assertTrue(deps.rounds.isEmpty())
            assertTrue(deps.pokes.isNotEmpty())

            // Linked once, then revoked: still nothing.
            deps.link(lastError = SYNC_ERROR_REVOKED)
            deps.lane.poke(SyncRoundReason.PLAYBACK)
            deps.run()
            assertTrue(deps.rounds.isEmpty())
        }

    // --- plumbing --------------------------------------------------------------------------------

    private class Deps(
        val db: NeutrodyneDatabase,
        val network: FakeNetworkMonitor,
        val clock: TestClock,
        val rounds: MutableList<SyncRoundReason>,
        val pokes: MutableList<String>,
    ) {
        val lane =
            DesktopSyncLane(
                db = db,
                round = { reason ->
                    rounds += reason
                    SyncReport(0, 0, 0, 0, 0, 0, 0)
                },
                network = network,
                clock = clock,
                poker = Provider { JobLanePoker { name -> pokes += name } },
            )

        suspend fun run() = lane.run(Instant.fromEpochMilliseconds(clock.now()))

        suspend fun link(lastError: String? = null) {
            db.withWriteTransaction {
                db.syncStateDao().upsert(
                    SyncStateEntity(
                        enabled = true,
                        serverUrl = "https://sync.example",
                        accountId = "acc",
                        deviceId = "dev",
                        nodeId = "0000000000000001",
                        linkedAt = clock.now(),
                        lastError = lastError,
                    ),
                )
            }
        }

        suspend fun writeOutbox(
            coll: String,
            rid: String,
            field: String,
        ) {
            db.withWriteTransaction {
                db.syncOutboxDao().upsert(
                    SyncOutboxEntity(
                        coll = coll,
                        rid = rid,
                        field = field,
                        hlc = clock.now() shl 16,
                        nodeId = "0000000000000001",
                    ),
                )
            }
        }
    }

    private fun deps(): Deps {
        val db = TestDb.inMemory().also { this.db = it }
        return Deps(db, FakeNetworkMonitor(FakeNetworkMonitor.ONLINE), TestClock(), mutableListOf(), mutableListOf())
    }
}
