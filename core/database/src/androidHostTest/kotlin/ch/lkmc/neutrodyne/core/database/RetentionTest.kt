// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.downloadEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Robolectric half of [RetentionTest]'s desktop suite (02 Testing: one schema, one test
 * suite per platform driver). Proves D23/AC11's retention half on the framework driver: the
 * candidate rules, the `withApplying` delete chain and "never a sync push".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionTest {
    private val now = 1_800_000_000_000L
    private val absentBefore = now - 90L * 86_400_000
    private val playedSince = now - 30L * 86_400_000

    private suspend fun NeutrodyneDatabase.candidates(): List<Long> =
        maintenanceDao().retentionBatch(absentBefore, playedSince, BATCH_LIMIT)

    private suspend fun NeutrodyneDatabase.podcast(feedKey: String): Long =
        podcastDao().insertPodcast(podcastEntity(feedKey = feedKey, syncId = "s-$feedKey"))

    private fun gone(
        podcastId: Long,
        key: String,
        ageDays: Long = 100,
        sortDate: Long = now - ageDays * 86_400_000,
    ): EpisodeEntity =
        episodeEntity(podcastId = podcastId, identityKey = key, sortDate = sortDate) {
            copy(inFeed = false, lastSeenAt = now - ageDays * 86_400_000)
        }

    @Test
    fun absentUnprotectedEpisodeIsSelectedInFeedAndRecentOnesAreNot() =
        runTest {
            val db = TestDb.inMemory(RuntimeEnvironment.getApplication())
            try {
                val pid = db.podcast("a")
                val candidate = db.episodeDao().insertStub(gone(pid, "gone"))
                db.episodeDao().insertStub(
                    episodeEntity(podcastId = pid, identityKey = "fresh") {
                        copy(inFeed = true, lastSeenAt = 1L)
                    },
                )
                db.episodeDao().insertStub(gone(pid, "recent", ageDays = 89))

                assertEquals(listOf(candidate), db.candidates())
            } finally {
                db.close()
            }
        }

    @Test
    fun everyProtectionRuleHolds() =
        runTest {
            val db = TestDb.inMemory(RuntimeEnvironment.getApplication())
            try {
                val pid = db.podcast("a")

                suspend fun seed(key: String) = db.episodeDao().insertStub(gone(pid, key))

                val downloaded = seed("dl")
                db.downloadDao().insert(downloadEntity(episodeId = downloaded, state = DownloadState.FAILED))

                val queued = seed("q")
                db.queueDao().insert(queueEntryEntity(episodeId = queued, orderKey = "a"))

                val current = seed("cur")
                db.useWriterConnection { conn ->
                    conn.exec(
                        "INSERT OR REPLACE INTO play_session(id, currentEpisodeId, updatedAt)" +
                            " VALUES (0, $current, $now)",
                    )
                }

                val favourite = seed("fav")
                db.episodeStateDao().ensure(episodeStateEntity(favourite) { copy(isFavorite = true) })

                val inProgress = seed("prog")
                db.episodeStateDao().ensure(
                    episodeStateEntity(inProgress) { copy(startedAt = now - 1_000) },
                )

                val playedRecently = seed("played29")
                db.episodeStateDao().ensure(
                    episodeStateEntity(playedRecently) { copy(playedAt = playedSince + 1) },
                )

                // Seeded older than its siblings so the newest-episode watermark cannot
                // protect it — only its position can.
                val positioned =
                    db.episodeDao().insertStub(
                        gone(pid, "pos", sortDate = now - 110L * 86_400_000),
                    )
                db.positionDao().upsert(
                    EpisodePositionEntity(
                        episodeId = positioned,
                        positionMs = 60_000,
                        positionSource = PositionSource.STREAM,
                        updatedAt = now - 1_000,
                    ),
                )

                assertEquals(emptyList(), db.candidates())
            } finally {
                db.close()
            }
        }

    @Test
    fun sweepDropsSyncClocksAndWritesNoOutboxRows() =
        runTest {
            val db = TestDb.inMemory(RuntimeEnvironment.getApplication())
            try {
                val pid = db.podcast("a")
                db.episodeDao().insertStub(
                    episodeEntity(podcastId = pid, identityKey = "newest", sortDate = now),
                )
                val gone1 = db.episodeDao().insertStub(gone(pid, "k1", sortDate = 5L))
                val gone2 = db.episodeDao().insertStub(gone(pid, "k2", sortDate = 4L))

                db.syncStateDao().upsert(
                    SyncStateEntity(enabled = true, serverUrl = "https://sync.example", linkedAt = now),
                )
                for (key in listOf("k1", "k2")) {
                    db
                        .syncClockDao()
                        .upsert(SyncClockEntity(coll = "episode", rid = "s-a$key", clocks = "{}"))
                }

                var applyingSeen = false
                db.withWriteTransaction {
                    db.syncStateDao().withApplying {
                        applyingSeen = db.syncStateDao().isApplying()
                        val ids = db.candidates()
                        assertEquals(listOf(gone1, gone2), ids)
                        val rids = db.maintenanceDao().episodeSyncRids(ids)
                        db.syncClockDao().deleteRids("episode", rids)
                        db.syncClockDao().deleteRids("upnext", rids)
                        db.maintenanceDao().deleteEpisodePersons(ids)
                        db.maintenanceDao().deleteEpisodeFunding(ids)
                        db.maintenanceDao().deleteEpisodes(ids)
                    }
                }

                assertTrue(applyingSeen)
                assertFalse(db.syncStateDao().isApplying())
                assertEquals(0, db.syncClockDao().count())
                // D23: never a sync push — the outbox stays empty.
                assertEquals(0, db.syncOutboxDao().count())
                assertEquals(1, db.maintenanceDao().episodeCount())
            } finally {
                db.close()
            }
        }

    @Test
    fun withApplyingClearsTheFlagUnderCancellation() =
        runTest {
            val db = TestDb.inMemory(RuntimeEnvironment.getApplication())
            try {
                db.syncStateDao().upsert(
                    SyncStateEntity(enabled = true, serverUrl = "https://sync.example", linkedAt = now),
                )
                val job = launch { db.syncStateDao().withApplying { awaitCancellation() } }
                while (!db.syncStateDao().isApplying()) yield()
                job.cancelAndJoin()
                assertFalse(db.syncStateDao().isApplying(), "a cancelled applier must clear the flag")
            } finally {
                db.close()
            }
        }

    private companion object {
        const val BATCH_LIMIT = 500
    }
}

private suspend fun androidx.room3.PooledConnection.exec(sql: String) {
    usePrepared(sql) { it.step() }
}
