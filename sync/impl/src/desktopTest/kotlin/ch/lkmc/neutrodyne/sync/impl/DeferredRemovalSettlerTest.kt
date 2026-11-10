// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.SyncHeldEntity
import ch.lkmc.neutrodyne.core.database.SyncStateEntity
import ch.lkmc.neutrodyne.core.testing.FakePlaybackSyncPort
import ch.lkmc.neutrodyne.core.testing.FakeSecretStore
import ch.lkmc.neutrodyne.core.testing.FakeSyncServerAddress
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.sync.protocol.Hlc
import ch.lkmc.neutrodyne.sync.protocol.NodeId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 10 Mass-change guard step 4: the app-scoped observer settles DEFERRED removals on the
 * player's own state changes — a stop or a new loaded item — locally, with no round and no
 * RPC, while HELD and STAGED rows never auto-apply and an unlinked or revoked profile
 * settles nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeferredRemovalSettlerTest {
    private val now = 1_800_000_000_000L
    private val host = "sync.example"
    private val serverUrl = "https://$host"
    private val podcastSyncId = "00000000-0000-4000-8000-00000000000a"
    private val otherSyncId = "00000000-0000-4000-8000-00000000000b"

    private companion object {
        const val WAIT_WINDOW_NS = 10_000_000_000L
        const val HOLD_BOUND_MS = 30_000L

        /**
         * `SyncStateDao.withApplying`'s flag restore — written only after the settle guard
         * has already evaluated the player state, and only the settle path runs
         * `withApplying` in these tests.
         */
        const val APPLYING_RESTORE_SQL = "UPDATE sync_state SET applying = ? WHERE id = 0"
    }

    @Test
    fun aStoppedPlayerSettlesTheDeferredRemoval() =
        runTest {
            val db = TestDb.inMemory()
            var job: kotlinx.coroutines.Job? = null
            try {
                val playback = FakePlaybackSyncPort()
                val settler = linkedSettler(db, playback)
                seedPodcast(db, podcastSyncId)
                val episodeId = episodeOf(db, podcastSyncId, "g:playing")
                stageDeferred(db, podcastSyncId)
                playback.setActive(episodeId, isPlaying = true)

                job = settler.start(backgroundScope)
                runCurrent()
                // A decline writes nothing: call settle directly so the check runs after it,
                // not after an arbitrary delay. The observer itself is exercised by the stop.
                settler.settle()
                assertNotNull(
                    db.podcastDao().bySyncId(podcastSyncId),
                    "the loaded, playing item's removal holds",
                )

                // A real stop unloads the item: `PlaybackSyncBridge.stopped` clears `active`
                // before its boundary, so the port reports "nothing loaded".
                playback.setActive(null, isPlaying = false)
                runCurrent()
                waitFor("the stop settles the removal offline — no round ran") {
                    db.podcastDao().bySyncId(podcastSyncId) == null
                }
                assertTrue(db.syncHeldDao().all().isEmpty())
            } finally {
                job?.cancelAndJoin()
                db.close()
            }
        }

    @Test
    fun aStopDuringAnInFlightSettleSettlesTheDeferredRemoval() =
        runTest {
            // The CI failure behind `waitFor`: the collector's first settle was still inside
            // its Room transaction when the stop landed, so its resumption queued on the test
            // scheduler after `runCurrent` — and a blocking wait starved that resumption
            // forever. Holding the real writer mid-transaction once forces the same
            // ordering deterministically.
            val gate = HeldApplyingWriteDriver(TestDb.bundledDriver())
            val db = TestDb.inMemory(driver = gate)
            var job: kotlinx.coroutines.Job? = null
            try {
                val playback = FakePlaybackSyncPort()
                val settler = linkedSettler(db, playback)
                seedPodcast(db, podcastSyncId)
                val episodeId = episodeOf(db, podcastSyncId, "g:inflight")
                stageDeferred(db, podcastSyncId)
                playback.setActive(episodeId, isPlaying = true)

                gate.arm()
                job = settler.start(backgroundScope)
                runCurrent()
                // The first settle declined with the item still loaded and now parks at
                // the post-guard restore write, so the collector's resumption cannot be
                // queued yet.
                gate.reached.await()
                playback.setActive(null, isPlaying = false)
                runCurrent()
                // Releasing the writer queues the collector's resumption strictly after
                // that last pump — the ordering a blocking wait cannot survive.
                gate.release()
                waitFor("the stop settles the removal offline — no round ran") {
                    db.podcastDao().bySyncId(podcastSyncId) == null
                }
                assertTrue(db.syncHeldDao().all().isEmpty())
            } finally {
                job?.cancelAndJoin()
                db.close()
            }
        }

    @Test
    fun aNewLoadedItemSettlesTheOtherDeferredRemoval() =
        runTest {
            val db = TestDb.inMemory()
            var job: kotlinx.coroutines.Job? = null
            try {
                val playback = FakePlaybackSyncPort()
                val settler = linkedSettler(db, playback)
                seedPodcast(db, podcastSyncId)
                seedPodcast(db, otherSyncId)
                val otherEpisode = episodeOf(db, otherSyncId, "g:other")
                stageDeferred(db, podcastSyncId)

                // The player loads and plays a different podcast's episode: the deferred
                // removal of the first podcast is no longer playing and settles.
                playback.setActive(otherEpisode, isPlaying = true)
                job = settler.start(backgroundScope)
                runCurrent()
                waitFor("a different loaded item settles the deferred removal") {
                    db.podcastDao().bySyncId(podcastSyncId) == null
                }
                assertNotNull(db.podcastDao().bySyncId(otherSyncId))
                assertTrue(db.syncHeldDao().all().isEmpty())
            } finally {
                job?.cancelAndJoin()
                db.close()
            }
        }

    @Test
    fun aPendingDeferredRowSettlesAtStartup() =
        runTest {
            val db = TestDb.inMemory()
            var job: kotlinx.coroutines.Job? = null
            try {
                val playback = FakePlaybackSyncPort()
                val settler = linkedSettler(db, playback)
                seedPodcast(db, podcastSyncId)
                stageDeferred(db, podcastSyncId)

                // Nothing plays: arming the observer already settles what a dead process's
                // round left behind — the initial state is itself a boundary.
                job = settler.start(backgroundScope)
                runCurrent()
                waitFor("the startup boundary settles the pending row") {
                    db.podcastDao().bySyncId(podcastSyncId) == null
                }
                assertTrue(db.syncHeldDao().all().isEmpty())
            } finally {
                job?.cancelAndJoin()
                db.close()
            }
        }

    @Test
    fun aPausedLoadedItemKeepsItsDeferredRemoval() =
        runTest {
            val db = TestDb.inMemory()
            var job: kotlinx.coroutines.Job? = null
            try {
                val playback = FakePlaybackSyncPort()
                val settler = linkedSettler(db, playback)
                seedPodcast(db, podcastSyncId)
                seedPodcast(db, otherSyncId)
                val episodeId = episodeOf(db, podcastSyncId, "g:playing")
                val otherEpisode = episodeOf(db, otherSyncId, "g:other")
                stageDeferred(db, podcastSyncId)
                playback.setActive(episodeId, isPlaying = true)

                job = settler.start(backgroundScope)
                runCurrent()
                settler.settle()
                assertNotNull(
                    db.podcastDao().bySyncId(podcastSyncId),
                    "the loaded, playing item's removal holds",
                )

                // 10's settle boundary is a stop or a new item — a pause unloads neither,
                // so the observer must not fire while the same episode stays loaded.
                playback.setPlaying(false)
                runCurrent()
                // The would-be settle runs on Room's real executor; give it a real window to
                // prove it never happens, then keep the negative check honest with a boundary.
                Thread.sleep(400)
                assertNotNull(
                    db.podcastDao().bySyncId(podcastSyncId),
                    "a pause is no settle boundary — the loaded item keeps its removal deferred",
                )
                assertTrue(db.syncHeldDao().all().isNotEmpty())

                // The row still settles on the next real boundary: a different loaded item.
                playback.setActive(otherEpisode, isPlaying = true)
                runCurrent()
                waitFor("the item change settles the deferred removal") {
                    db.podcastDao().bySyncId(podcastSyncId) == null
                }
            } finally {
                job?.cancelAndJoin()
                db.close()
            }
        }

    @Test
    fun anInitialPausedSnapshotKeepsItsDeferredRemoval() =
        runTest {
            val db = TestDb.inMemory()
            var job: kotlinx.coroutines.Job? = null
            try {
                val playback = FakePlaybackSyncPort()
                val settler = linkedSettler(db, playback)
                seedPodcast(db, podcastSyncId)
                val episodeId = episodeOf(db, podcastSyncId, "g:paused")
                stageDeferred(db, podcastSyncId)

                // An app restart lands the collector's first emission mid-pause — a loaded,
                // paused item. No unload or item change happened, so nothing may apply.
                playback.setActive(episodeId, isPlaying = false)
                job = settler.start(backgroundScope)
                runCurrent()
                // The would-be settle runs on Room's real executor; give it a real window to
                // prove it never happened before the real boundary check below.
                Thread.sleep(400)
                assertNotNull(
                    db.podcastDao().bySyncId(podcastSyncId),
                    "a paused snapshot is no release — the loaded item keeps its removal",
                )
                assertTrue(db.syncHeldDao().all().isNotEmpty())

                // The actual unload is a real boundary and applies the removal.
                playback.setActive(null, isPlaying = false)
                runCurrent()
                waitFor("the unload settles the deferred removal") {
                    db.podcastDao().bySyncId(podcastSyncId) == null
                }
            } finally {
                job?.cancelAndJoin()
                db.close()
            }
        }

    @Test
    fun heldAndStagedRowsNeverAutoApply() =
        runTest {
            val db = TestDb.inMemory()
            var job: kotlinx.coroutines.Job? = null
            try {
                val playback = FakePlaybackSyncPort()
                val settler = linkedSettler(db, playback)
                seedPodcast(db, podcastSyncId)
                seedPodcast(db, otherSyncId)
                db.withWriteTransaction {
                    MassChangeGuard.stagePodcast(db, 9L, podcastSyncId, removalClock())
                    db
                        .syncHeldDao()
                        .insert(
                            SyncHeldEntity(
                                batch = batchJson(HeldState.HELD, otherSyncId),
                                summary = "{}",
                                heldAt = 10L,
                            ),
                        )
                }

                job = settler.start(backgroundScope)
                runCurrent()
                settler.settle()

                assertNotNull(db.podcastDao().bySyncId(podcastSyncId), "STAGED rows are untouched")
                assertNotNull(db.podcastDao().bySyncId(otherSyncId), "HELD rows are untouched")
                assertTrue(db.syncHeldDao().all().isNotEmpty())
            } finally {
                job?.cancelAndJoin()
                db.close()
            }
        }

    @Test
    fun anUnlinkedProfileSettlesNothing() =
        runTest {
            val db = TestDb.inMemory()
            var job: kotlinx.coroutines.Job? = null
            try {
                val playback = FakePlaybackSyncPort()
                // No link state and no token: a copied or wiped profile never runs the old
                // profile's staged commands.
                val settler =
                    DeferredRemovalSettler(
                        db,
                        playback,
                        TestClock(now),
                        FakeSyncServerAddress(serverUrl = null),
                        SyncTokenStore(FakeSecretStore()),
                    )
                seedPodcast(db, podcastSyncId)
                stageDeferred(db, podcastSyncId)

                job = settler.start(backgroundScope)
                runCurrent()
                settler.settle()

                assertNotNull(db.podcastDao().bySyncId(podcastSyncId))
                assertTrue(db.syncHeldDao().all().isNotEmpty())
            } finally {
                job?.cancelAndJoin()
                db.close()
            }
        }

    @Test
    fun aDisabledProfileSettlesNothing() =
        runTest {
            val db = TestDb.inMemory()
            var job: kotlinx.coroutines.Job? = null
            try {
                val playback = FakePlaybackSyncPort()
                val settler = linkedSettler(db, playback)
                db.withWriteTransaction {
                    db
                        .syncStateDao()
                        .upsert(
                            db.syncStateDao().get()!!.copy(enabled = false, linkedAt = null),
                        )
                }
                seedPodcast(db, podcastSyncId)
                stageDeferred(db, podcastSyncId)

                job = settler.start(backgroundScope)
                runCurrent()
                settler.settle()

                assertNotNull(
                    db.podcastDao().bySyncId(podcastSyncId),
                    "a revoked or unfinished link never settles",
                )
                assertTrue(db.syncHeldDao().all().isNotEmpty())
            } finally {
                job?.cancelAndJoin()
                db.close()
            }
        }

    @Test
    fun startArmsOnlyOneCollector() =
        runTest {
            val db = TestDb.inMemory()
            var job: kotlinx.coroutines.Job? = null
            try {
                val playback = FakePlaybackSyncPort()
                val settler = linkedSettler(db, playback)

                job = settler.start(backgroundScope)
                assertNotNull(job)
                assertNull(settler.start(backgroundScope), "a re-run arms no duplicate collector")
            } finally {
                job?.cancelAndJoin()
                db.close()
            }
        }

    /**
     * `runCurrent` only pumps the test scheduler — the observer's `settle` commits on Room's
     * real dispatcher afterwards, so poll the database until that write lands or the window
     * closes. The wait itself must stay cooperative: the observer's resumptions are
     * scheduler tasks too, and a blocking wait starves them, so `delay` yields between
     * polls. Failing the deadline means the boundary never settled.
     */
    private suspend fun waitFor(
        description: String,
        check: suspend () -> Boolean,
    ) {
        val deadline = System.nanoTime() + WAIT_WINDOW_NS
        while (System.nanoTime() < deadline) {
            if (check()) return
            delay(10)
        }
        error("timed out waiting for: $description")
    }

    /** A linked settler: stored link state, server address and token. */
    private suspend fun linkedSettler(
        db: NeutrodyneDatabase,
        playback: FakePlaybackSyncPort,
    ): DeferredRemovalSettler {
        db.withWriteTransaction {
            db
                .syncStateDao()
                .upsert(
                    SyncStateEntity(
                        enabled = true,
                        serverUrl = serverUrl,
                        accountId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                        deviceId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                        nodeId = "0000000000000001",
                        linkedAt = now,
                    ),
                )
        }
        val tokens = SyncTokenStore(FakeSecretStore())
        tokens.save(host, "nds_test")
        return DeferredRemovalSettler(
            db,
            playback,
            TestClock(now),
            FakeSyncServerAddress(serverUrl = serverUrl),
            tokens,
        )
    }

    private suspend fun seedPodcast(
        db: NeutrodyneDatabase,
        syncId: String,
    ) {
        db.withWriteTransaction {
            db
                .podcastDao()
                .insertPodcast(
                    podcastEntity(
                        syncId = syncId,
                        feedUrl = "https://e.example/$syncId",
                        feedKey = "https://e.example/$syncId",
                    ),
                )
        }
    }

    private suspend fun episodeOf(
        db: NeutrodyneDatabase,
        syncId: String,
        identityKey: String,
    ): Long {
        val podcast = db.podcastDao().bySyncId(syncId)!!
        return db
            .ingestDao()
            .insertEpisodes(listOf(episodeEntity(podcastId = podcast.id, identityKey = identityKey)))
            .single()
    }

    /** A winning removal clock: no stored field or outbox row beats it in these tests. */
    private fun removalClock(): String = Hlc.of(now, 5, NodeId("0000000000000002")).toString()

    /** A `sync_held` batch payload in [state] holding one podcast removal of [syncId]. */
    private fun batchJson(
        state: HeldState,
        syncId: String,
    ): String = """{"state":"${state.name}","podcasts":[{"rid":"$syncId","clock":"${removalClock()}"}],"groups":[]}"""

    /** Stages then defers one podcast removal — the shape a playing round leaves behind. */
    private suspend fun stageDeferred(
        db: NeutrodyneDatabase,
        syncId: String,
    ) {
        db.withWriteTransaction {
            val clock = removalClock()
            MassChangeGuard.stagePodcast(db, 7L, syncId, clock)
            val row = db.syncHeldDao().forRound(7L).single()
            MassChangeGuard.defer(db, row.id, listOf(HeldRemoval(syncId, clock)))
        }
    }

    /**
     * A [SQLiteDriver] whose first armed `withApplying` restore write parks inside `step`:
     * [reached] reports the hold, [release] lets the real write through. That statement
     * only comes from `withApplying`'s `finally`, which only the settle path runs here —
     * and it executes strictly after `settleDeferred` has evaluated the live player state,
     * so holding it parks the settler coroutine inside its real Room transaction with the
     * decline already decided. Its resumption on the test scheduler then lands strictly
     * after the last `runCurrent`: the ordering that starved a blocking `waitFor` of the
     * observer it was waiting on. Holding earlier would let the parked settle observe the
     * stop. Room caches prepared statements per connection, so the arm is consumed at
     * `step`, not at `prepare`; every other statement and every unarmed step passes
     * straight through.
     */
    private class HeldApplyingWriteDriver(
        private val delegate: SQLiteDriver,
    ) : SQLiteDriver {
        private val holdNextApplyingWrite = AtomicBoolean(false)

        /** Completes once the held write reaches `step`. */
        val reached = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()

        override val hasConnectionPool: Boolean
            get() = delegate.hasConnectionPool

        /** The next restore write's `step` — and only that one — holds. */
        fun arm() {
            holdNextApplyingWrite.set(true)
        }

        /** Lets the held write step through; a no-op after the first call. */
        fun release() {
            released.complete(Unit)
        }

        override fun open(fileName: String): SQLiteConnection {
            val connection = delegate.open(fileName)
            return object : SQLiteConnection by connection {
                override fun prepare(sql: String): SQLiteStatement {
                    val statement = connection.prepare(sql)
                    if (sql != APPLYING_RESTORE_SQL) return statement
                    return object : SQLiteStatement by statement {
                        override fun step(): Boolean {
                            if (holdNextApplyingWrite.compareAndSet(true, false)) {
                                reached.complete(Unit)
                                // Bounded: an unreleased gate must not park a pool worker.
                                runBlocking {
                                    withTimeoutOrNull(HOLD_BOUND_MS) { released.await() }
                                }
                            }
                            return statement.step()
                        }
                    }
                }
            }
        }
    }
}
