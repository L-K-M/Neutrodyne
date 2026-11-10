// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.sync.impl

import androidx.room3.withWriteTransaction
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

                playback.setPlaying(false)
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
     * closes. Failing the deadline means the boundary never settled.
     */
    private fun waitFor(
        description: String,
        check: suspend () -> Boolean,
    ) {
        val deadline = System.nanoTime() + WAIT_WINDOW_NS
        while (System.nanoTime() < deadline) {
            if (runBlocking { check() }) return
            Thread.sleep(10)
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
}
