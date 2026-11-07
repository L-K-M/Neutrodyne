// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkStatus
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncOutboxEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncEvent
import ch.lkmc.neutrodyne.core.testing.FakeNetworkMonitor
import ch.lkmc.neutrodyne.core.testing.FakePlaybackSyncPort
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.sync.api.SyncRoundReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 10 Scheduling's shared trigger engine: the outbox debounce coalesces writes, unlinked and
 * offline states defer to `schedulePush` or nothing, `pos`/`session` rows wait for a playback
 * boundary or the 60-s playing tick, and a held group deletion re-fires the outbox path when
 * it comes of age. Signals that the collectors deliver are invoked directly for determinism;
 * the invalidation flow itself is covered by one end-to-end test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncSchedulerTest {
    private var db: NeutrodyneDatabase? = null

    @AfterTest
    fun closeDb() {
        db?.close()
    }

    // --- the outbox debounce -----------------------------------------------------------------

    @Test
    fun `an outbox invalidation debounces to one round`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.scheduler.start()
            runCurrent()

            // A real tracked write goes through Room's invalidation tracker — the only test in
            // the file that lets the flow deliver the signal itself.
            deps.writeOutbox(coll = "podcast", rid = "p1", field = "subscribed")
            deps.await { deps.rounds.size >= 1 }

            assertEquals(listOf(SyncRoundReason.OUTBOX), deps.rounds)
            assertTrue(deps.pushes.isEmpty())
        }

    @Test
    fun `a burst of changes coalesces into one round`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.writeOutbox(coll = "podcast", rid = "p1", field = "subscribed")

            val burstStart = testScheduler.currentTime
            // Room dispatches queries on real threads. Freeze the virtual scheduler while
            // delivering this burst, or runTest can advance a debounce during a query and
            // turn the fixture's five simultaneous changes into several spaced-out bursts.
            runBlocking {
                repeat(5) { deps.scheduler.onOutboxChanged() }
            }
            assertEquals(burstStart, testScheduler.currentTime)
            runCurrent()
            testScheduler.advanceTimeBy(OUTBOX_DEBOUNCE_MS)
            runCurrent()
            deps.await { deps.rounds.isNotEmpty() }

            assertEquals(listOf(SyncRoundReason.OUTBOX), deps.rounds)
        }

    @Test
    fun `nothing happens while unlinked`() =
        runTest {
            val deps = deps()
            // No `sync_state` row: the device was never linked.
            deps.writeOutbox(coll = "podcast", rid = "p1", field = "subscribed")
            deps.scheduler.onOutboxChanged()
            testScheduler.advanceTimeBy(OUTBOX_DEBOUNCE_MS * 2)
            runCurrent()
            deps.awaitQuiet()

            assertTrue(deps.rounds.isEmpty())
            assertTrue(deps.pushes.isEmpty())
        }

    @Test
    fun `a revoked link is no link`() =
        runTest {
            val deps = deps()
            deps.writeOutbox(coll = "podcast", rid = "p1", field = "subscribed")
            deps.link(lastError = SYNC_ERROR_REVOKED)

            deps.scheduler.onOutboxChanged()
            testScheduler.advanceTimeBy(OUTBOX_DEBOUNCE_MS * 2)
            runCurrent()
            deps.awaitQuiet()

            assertTrue(deps.rounds.isEmpty())
            assertTrue(deps.pushes.isEmpty())
        }

    @Test
    fun `sync disabled schedules nothing`() =
        runTest {
            val deps = deps()
            deps.writeOutbox(coll = "podcast", rid = "p1", field = "subscribed")
            deps.link(enabled = false)

            deps.scheduler.onOutboxChanged()
            testScheduler.advanceTimeBy(OUTBOX_DEBOUNCE_MS * 2)
            runCurrent()
            deps.awaitQuiet()

            assertTrue(deps.rounds.isEmpty())
            assertTrue(deps.pushes.isEmpty())
        }

    @Test
    fun `offline defers to the platform push`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.writeOutbox(coll = "podcast", rid = "p1", field = "subscribed")
            deps.network.setStatus(FakeNetworkMonitor.OFFLINE)

            deps.scheduler.onOutboxChanged()
            testScheduler.advanceTimeBy(OUTBOX_DEBOUNCE_MS * 2)
            runCurrent()
            deps.await { deps.pushes.isNotEmpty() }

            assertEquals(listOf(SyncRoundReason.OUTBOX), deps.pushes)
            assertTrue(deps.rounds.isEmpty())
        }

    @Test
    fun `a non-interactive platform always defers to its push`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.interactive = false
            deps.writeOutbox(coll = "podcast", rid = "p1", field = "subscribed")

            deps.scheduler.onOutboxChanged()
            testScheduler.advanceTimeBy(OUTBOX_DEBOUNCE_MS)
            runCurrent()
            deps.await { deps.pushes.isNotEmpty() }

            assertEquals(listOf(SyncRoundReason.OUTBOX), deps.pushes)
            assertTrue(deps.rounds.isEmpty())
        }

    @Test
    fun `a failed in-process round falls back to the push`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.roundFails = true
            deps.writeOutbox(coll = "podcast", rid = "p1", field = "subscribed")

            deps.scheduler.onOutboxChanged()
            testScheduler.advanceTimeBy(OUTBOX_DEBOUNCE_MS)
            runCurrent()
            deps.await { deps.pushes.isNotEmpty() }

            assertEquals(listOf(SyncRoundReason.OUTBOX), deps.pushes)
        }

    @Test
    fun `a network regain re-runs the outbox path`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.network.setStatus(FakeNetworkMonitor.OFFLINE)
            deps.scheduler.start()
            runCurrent()
            deps.writeOutbox(coll = "podcast", rid = "p1", field = "subscribed")

            deps.scheduler.onOutboxChanged()
            testScheduler.advanceTimeBy(OUTBOX_DEBOUNCE_MS)
            runCurrent()
            deps.await { deps.pushes.isNotEmpty() }
            assertTrue(deps.rounds.isEmpty())

            deps.network.setStatus(FakeNetworkMonitor.ONLINE)
            runCurrent()
            deps.await { deps.rounds.isNotEmpty() }

            assertEquals(listOf(SyncRoundReason.OUTBOX), deps.rounds)
        }

    // --- playback rows wait for a boundary ----------------------------------------------------

    @Test
    fun `pos-only changes never trigger the debounce path`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.writeOutbox(coll = "episode", rid = "e1", field = "pos")

            deps.scheduler.onOutboxChanged()
            testScheduler.advanceTimeBy(OUTBOX_DEBOUNCE_MS * 2)
            runCurrent()
            deps.awaitQuiet()

            assertTrue(deps.rounds.isEmpty())
            assertTrue(deps.pushes.isEmpty())
        }

    @Test
    fun `a playback boundary pushes pending pos rows`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.scheduler.start()
            runCurrent()
            deps.writeOutbox(coll = "episode", rid = "e1", field = "pos")

            deps.playback.emit(PlaybackSyncEvent.Paused)
            runCurrent()
            deps.await { deps.rounds.isNotEmpty() }

            assertEquals(listOf(SyncRoundReason.PLAYBACK), deps.rounds)
        }

    @Test
    fun `a playback boundary without pending rows stays quiet`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.scheduler.start()
            runCurrent()

            deps.playback.emit(PlaybackSyncEvent.Paused)
            runCurrent()
            deps.awaitQuiet()

            assertTrue(deps.rounds.isEmpty())
            assertTrue(deps.pushes.isEmpty())
        }

    @Test
    fun `the playing tick rounds every 60 s while rows are pending`() =
        runTest {
            val deps = deps()
            deps.link()
            deps.writeOutbox(coll = "episode", rid = "e1", field = "pos")
            deps.scheduler.start()
            runCurrent()

            // A playing player has an item loaded (06's `ActivePlayback` is null otherwise).
            deps.playback.load(episodeId = 1L, playing = true)
            runCurrent()
            deps.awaitQuiet()
            assertTrue(deps.rounds.isEmpty())

            testScheduler.advanceTimeBy(PLAYING_TICK_MS)
            runCurrent()
            deps.await { deps.rounds.size >= 1 }
            assertEquals(listOf(SyncRoundReason.PLAYBACK), deps.rounds)

            testScheduler.advanceTimeBy(PLAYING_TICK_MS)
            runCurrent()
            deps.await { deps.rounds.size >= 2 }
            assertEquals(listOf(SyncRoundReason.PLAYBACK, SyncRoundReason.PLAYBACK), deps.rounds)

            deps.playback.setPlaying(false)
            testScheduler.advanceTimeBy(PLAYING_TICK_MS * 3)
            runCurrent()
            deps.awaitQuiet()
            assertEquals(2, deps.rounds.size)
        }

    // --- the undo-window maturity poke ---------------------------------------------------------

    /**
     * 10 Mass-change guard rule 4: a playback boundary that ends playback or changes the item
     * releases the deferred removals; a pause (the item stays current and loaded) does not.
     */
    @Test
    fun `a stop or transition releases deferred removals but a pause does not`() =
        runTest {
            val deps = deps()
            deps.link()
            val releases = mutableListOf<Unit>()
            deps.releaseDeferred = { releases += Unit }
            deps.scheduler.start()
            runCurrent()

            deps.playback.emit(PlaybackSyncEvent.Paused)
            deps.awaitQuiet()
            assertTrue(releases.isEmpty(), "a pause keeps the deferral")

            deps.playback.emit(PlaybackSyncEvent.Stopped)
            deps.await { releases.size == 1 }

            deps.playback.emit(PlaybackSyncEvent.Transition(7L))
            deps.await { releases.size == 2 }
        }

    @Test
    fun `a held group deletion re-fires the outbox path when it comes of age`() =
        runTest {
            val deps = deps()
            deps.link()
            val capturedMs = deps.clockNow() - SYNC_UNDO_WINDOW_MS + 3_000
            deps.writeOutbox(
                coll = "group",
                rid = "g1",
                field = "deleted",
                value = "true",
                hlc = capturedMs shl 16,
            )

            deps.scheduler.onOutboxChanged()
            testScheduler.advanceTimeBy(OUTBOX_DEBOUNCE_MS)
            runCurrent()
            deps.await { deps.rounds.isNotEmpty() }
            assertEquals(1, deps.rounds.size)

            // +3 s the undo window lapses: the scheduler pokes the outbox path again so the
            // held delete is pushed when it becomes eligible.
            testScheduler.advanceTimeBy(3_000)
            runCurrent()
            deps.await { deps.rounds.size >= 2 }
            assertEquals(2, deps.rounds.size)
        }

    // --- fixture --------------------------------------------------------------------------------

    private class Deps(
        private val scope: TestScope,
        val db: NeutrodyneDatabase,
        val network: FakeNetworkMonitor,
        val playback: FakePlaybackSyncPort,
        val rounds: MutableList<SyncRoundReason>,
        val pushes: MutableList<SyncRoundReason>,
    ) {
        var interactive: Boolean = true
        var roundFails: Boolean = false
        var releaseDeferred: suspend () -> Unit = {}

        private val clock = scope.clock

        val scheduler =
            SyncScheduler(
                db = db,
                round = { reason ->
                    if (roundFails) throw RuntimeException("round failed")
                    rounds += reason
                },
                playback = playback,
                network = network,
                clock = clock,
                scope = scope.backgroundScope,
                hooks =
                    object : SyncScheduleHooks {
                        override fun interactive(): Boolean = this@Deps.interactive

                        override fun schedulePush(reason: SyncRoundReason) {
                            pushes += reason
                        }

                        override suspend fun onLinked() = Unit

                        override suspend fun onUnlinked() = Unit
                    },
                releaseDeferred = { this@Deps.releaseDeferred() },
            )

        fun clockNow(): Long = clock.now()

        suspend fun link(
            enabled: Boolean = true,
            lastError: String? = null,
        ) {
            db.withWriteTransaction {
                db.syncStateDao().upsert(
                    SyncStateEntity(
                        enabled = enabled,
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
            value: String? = null,
            hlc: Long = clock.now() shl 16,
        ) {
            db.withWriteTransaction {
                db.syncOutboxDao().upsert(
                    SyncOutboxEntity(
                        coll = coll,
                        rid = rid,
                        field = field,
                        hlc = hlc,
                        nodeId = "0000000000000001",
                        value = value,
                    ),
                )
            }
        }

        /**
         * Awaits a scheduler effect: pumps virtual time forward (the debounce, the maturity job
         * and the playing tick all `delay` on it) and lets the real Room work on
         * `Dispatchers.Default` land in between.
         */
        fun await(
            deadlineMs: Long = AWAIT_MS,
            condition: () -> Boolean,
        ) {
            val deadline = System.nanoTime() + deadlineMs * 1_000_000L
            while (!condition()) {
                scope.testScheduler.runCurrent()
                scope.testScheduler.advanceTimeBy(AWAIT_STEP_MS)
                if (System.nanoTime() > deadline) fail("scheduler effect did not arrive")
                Thread.sleep(1)
            }
            scope.testScheduler.runCurrent()
        }

        /** Proves no effect: runs the pending window and a short real-time guard. */
        fun awaitQuiet(guardMs: Long = QUIET_MS) {
            val deadline = System.nanoTime() + guardMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                scope.testScheduler.runCurrent()
                Thread.sleep(10)
            }
        }
    }

    private fun TestScope.deps(): Deps {
        val db = TestDb.inMemory().also { this@SyncSchedulerTest.db = it }
        return Deps(
            scope = this,
            db = db,
            network = FakeNetworkMonitor(FakeNetworkMonitor.ONLINE),
            playback = FakePlaybackSyncPort(),
            rounds = mutableListOf(),
            pushes = mutableListOf(),
        )
    }

    private companion object {
        const val AWAIT_MS = 5_000L
        const val AWAIT_STEP_MS = 50L
        const val QUIET_MS = 300L
    }
}

/** The shared virtual-time clock the scheduler and the assertions read. */
private val TestScope.clock: Clock
    @OptIn(ExperimentalCoroutinesApi::class)
    get() = TestClock.from(testScheduler)
