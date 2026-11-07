// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.OwnerType
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D23 / AC11's retention half (02 Retention policy): [MaintenanceDao.retentionBatch] selects
 * exactly the unprotected `inFeed = 0` episodes absent for 90 days, and the delete chain —
 * `SyncStateDao.withApplying`, `SyncClockDao.deleteRids`, person/funding orphans, cascade —
 * never produces a sync push. `DbMaintenance`'s step composition is covered separately; here
 * the query contract itself is proven on both drivers.
 */
class RetentionTest {
    private val now = 1_800_000_000_000L
    private val absentBefore = now - 90L * 86_400_000
    private val playedSince = now - 30L * 86_400_000

    /** What [MaintenanceDao.retentionBatch] would return for the seeded library. */
    private suspend fun NeutrodyneDatabase.candidates(): List<Long> =
        maintenanceDao().retentionBatch(absentBefore, playedSince, BATCH_LIMIT)

    /** The production delete chain of `DbMaintenance` step 1, minus its deadline. */
    private suspend fun NeutrodyneDatabase.retentionSweep(): Int =
        withWriteTransaction {
            syncStateDao().withApplying {
                var deleted = 0
                while (true) {
                    val ids = candidates()
                    if (ids.isEmpty()) break
                    val rids = maintenanceDao().episodeSyncRids(ids)
                    syncClockDao().deleteRids("episode", rids)
                    syncClockDao().deleteRids("upnext", rids)
                    maintenanceDao().deleteEpisodePersons(ids)
                    maintenanceDao().deleteEpisodeFunding(ids)
                    // A batch that selects rows but deletes none would spin forever.
                    val removed = maintenanceDao().deleteEpisodes(ids)
                    check(removed > 0) { "retention selected ${ids.size} rows but deleted none" }
                    deleted += removed
                }
                deleted
            }
        }

    private suspend fun NeutrodyneDatabase.podcast(
        feedKey: String,
        syncId: String = "s-$feedKey",
    ): Long = podcastDao().insertPodcast(podcastEntity(feedKey = feedKey, syncId = syncId))

    /** An episode candidate: out of feed and unseen for [ageDays] (default 100). */
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
            val db = TestDb.inMemory()
            try {
                val pid = db.podcast("a")
                val candidate = db.episodeDao().insertStub(gone(pid, "gone"))
                // Still in the feed: never a candidate, however old lastSeenAt is.
                db.episodeDao().insertStub(
                    episodeEntity(podcastId = pid, identityKey = "fresh") {
                        copy(inFeed = true, lastSeenAt = 1L)
                    },
                )
                // Out of feed but seen inside the window.
                db.episodeDao().insertStub(gone(pid, "recent", ageDays = 89))

                assertEquals(listOf(candidate), db.candidates())
            } finally {
                db.close()
            }
        }

    @Test
    fun everyProtectionRuleHolds() =
        runTest {
            val db = TestDb.inMemory()
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
    fun playedLongAgoAndPositionClearedByPlayedAreCandidates() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val pid = db.podcast("a")
                // Played, but outside the 30-day window → deletable.
                val playedOld =
                    db.episodeDao().insertStub(gone(pid, "played31", sortDate = now - 100L * 86_400_000))
                db.episodeStateDao().ensure(
                    episodeStateEntity(playedOld) { copy(playedAt = playedSince - 1) },
                )
                // A stale position does not protect an episode that was played.
                val playedWithPosition =
                    db.episodeDao().insertStub(gone(pid, "posdone", sortDate = now - 101L * 86_400_000))
                db.episodeStateDao().ensure(
                    episodeStateEntity(playedWithPosition) { copy(playedAt = playedSince - 2) },
                )
                db.positionDao().upsert(
                    EpisodePositionEntity(
                        episodeId = playedWithPosition,
                        positionMs = 10_000,
                        positionSource = PositionSource.DOWNLOAD,
                        updatedAt = now - 1_000,
                    ),
                )
                // The newest episode of its podcast is protected; these two are older.
                db.episodeDao().insertStub(
                    episodeEntity(podcastId = pid, identityKey = "newest", sortDate = now),
                )

                assertEquals(
                    listOf(playedOld, playedWithPosition).sorted(),
                    db.candidates(),
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun newestEpisodeWatermarkIsPerPodcastAndFollowsSortDateThenId() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val p1 = db.podcast("a")
                val p2 = db.podcast("b")
                // Only row of p2: it is its podcast's newest, hence protected even at 100 days.
                db.episodeDao().insertStub(gone(p2, "only", sortDate = 1L))
                // p1: two stale rows; the higher sortDate survives.
                val older = db.episodeDao().insertStub(gone(p1, "old", sortDate = 10L))
                db.episodeDao().insertStub(gone(p1, "new", sortDate = 20L))
                // Same sortDate: the higher id is "newest".
                val p3 = db.podcast("c")
                val lower = db.episodeDao().insertStub(gone(p3, "tie-low", sortDate = 5L))
                db.episodeDao().insertStub(gone(p3, "tie-high", sortDate = 5L))

                assertEquals(listOf(older, lower).sorted(), db.candidates())
            } finally {
                db.close()
            }
        }

    @Test
    fun batchesCapAtTheLimitAndTheSweepLoopsUntilEmpty() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val pid = db.podcast("a")
                // Watermark row survives by being newest.
                db.episodeDao().insertStub(
                    episodeEntity(podcastId = pid, identityKey = "newest", sortDate = now),
                )
                val ids =
                    (1..(BATCH_LIMIT + 7)).map {
                        db
                            .episodeDao()
                            .insertStub(gone(pid, "e$it", sortDate = 10_000L + it))
                    }
                val first = db.maintenanceDao().retentionBatch(absentBefore, playedSince, BATCH_LIMIT)
                assertEquals(BATCH_LIMIT, first.size)
                assertEquals(ids.take(BATCH_LIMIT), first)

                assertEquals(ids.size, db.retentionSweep())
                // Only the watermark "newest" row survives the sweep.
                assertEquals(1, db.maintenanceDao().episodeCount())
                assertEquals(1, db.maintenanceDao().podcastCount())
            } finally {
                db.close()
            }
        }

    @Test
    fun sweepCascadesStatePositionPeopleAndDropsSyncClocksWithoutOutboxWrites() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val pid = db.podcast("a", syncId = "sync-pod-1")
                db.episodeDao().insertStub(
                    episodeEntity(podcastId = pid, identityKey = "newest", sortDate = now),
                )
                val gone1 = db.episodeDao().insertStub(gone(pid, "k1", sortDate = 5L))
                val gone2 = db.episodeDao().insertStub(gone(pid, "k2", sortDate = 4L))
                val keep =
                    db.episodeDao().insertStub(
                        episodeEntity(podcastId = pid, identityKey = "keep", sortDate = 3L) {
                            copy(inFeed = false, lastSeenAt = now - 10L * 86_400_000)
                        },
                    )
                db.episodeStateDao().ensure(episodeStateEntity(gone1) { copy(playedAt = playedSince - 5) })
                db.positionDao().upsert(
                    EpisodePositionEntity(
                        episodeId = gone1,
                        positionMs = 0,
                        positionSource = PositionSource.STREAM,
                        updatedAt = now - 60_000,
                    ),
                )
                db.useWriterConnection { conn ->
                    conn.exec(
                        "INSERT INTO person(ownerType, ownerId, name) VALUES ('EPISODE', $gone1, 'Host')",
                    )
                    conn.exec(
                        "INSERT INTO funding(ownerType, ownerId, url) VALUES ('EPISODE', $gone2, 'https://fund')",
                    )
                }
                // Linked-device fixture: clock rows for both doomed episodes and the kept one.
                db.syncStateDao().upsert(
                    SyncStateEntity(enabled = true, serverUrl = "https://sync.example", linkedAt = now),
                )
                for ((id, key) in listOf(gone1 to "k1", gone2 to "k2", keep to "keep")) {
                    db
                        .syncClockDao()
                        .upsert(SyncClockEntity(coll = "episode", rid = "sync-pod-1$key", clocks = "{}"))
                    assertEquals("sync-pod-1$key", db.maintenanceDao().episodeSyncRids(listOf(id)).single())
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
                // Clock rows of deleted episodes are gone; the kept episode's survives.
                assertEquals(1, db.syncClockDao().count())
                // D23: never a sync push — the outbox stays empty.
                assertEquals(0, db.syncOutboxDao().count())
                // Episode rows and their children are gone; the watermark and the kept row stay.
                assertEquals(setOf("keep", "newest"), episodeIds(db))
                assertEquals(0, personCount(db, OwnerType.EPISODE))
            } finally {
                db.close()
            }
        }

    @Test
    fun orphanSweepsRemoveDanglingRowsAndParkedExpiryHonours180Days() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val pid = db.podcast("a")
                val eid =
                    db.episodeDao().insertStub(
                        episodeEntity(podcastId = pid, identityKey = "e", sortDate = now),
                    )
                db.useWriterConnection { conn ->
                    conn.exec("INSERT INTO person(ownerType, ownerId, name) VALUES ('EPISODE', $eid, 'ok')")
                    conn.exec("INSERT INTO person(ownerType, ownerId, name) VALUES ('EPISODE', 999999, 'gone')")
                    conn.exec("INSERT INTO person(ownerType, ownerId, name) VALUES ('PODCAST', 999998, 'gone')")
                    conn.exec(
                        "INSERT INTO funding(ownerType, ownerId, url) VALUES ('PODCAST', $pid, 'https://f')",
                    )
                    conn.exec(
                        "INSERT INTO funding(ownerType, ownerId, url) VALUES ('PODCAST', 999997, 'https://x')",
                    )
                }
                assertEquals(2, db.maintenanceDao().deleteOrphanedPersons())
                assertEquals(1, db.maintenanceDao().deleteOrphanedFunding())
                assertEquals(1, personCount(db, OwnerType.PODCAST) + personCount(db, OwnerType.EPISODE))

                // Step 3a: parked rows older than 180 days go; younger stay.
                db
                    .syncParkedDao()
                    .park(
                        SyncParkedEntity(
                            podcastSyncId = "s",
                            identityKey = "k",
                            record = "{}",
                            receivedAt = now - 181L * 86_400_000,
                        ),
                    )
                db
                    .syncParkedDao()
                    .park(
                        SyncParkedEntity(
                            podcastSyncId = "s",
                            identityKey = "k2",
                            record = "{}",
                            receivedAt = now - 10L * 86_400_000,
                        ),
                    )
                assertEquals(1, db.syncParkedDao().expire(now - 180L * 86_400_000))
                assertEquals(1, db.syncParkedDao().count())
            } finally {
                db.close()
            }
        }

    @Test
    fun withApplyingClearsTheFlagUnderCancellation() =
        runTest {
            val db = TestDb.inMemory()
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

    private suspend fun episodeIds(db: NeutrodyneDatabase): Set<String> =
        db.useWriterConnection { conn ->
            conn.usePrepared("SELECT identityKey FROM episode") { stmt ->
                buildSet {
                    while (stmt.step()) add(stmt.getText(0))
                }
            }
        }

    private suspend fun personCount(
        db: NeutrodyneDatabase,
        owner: OwnerType,
    ): Int =
        db.useWriterConnection { conn ->
            conn.usePrepared("SELECT COUNT(*) FROM person WHERE ownerType = '${owner.name}'") { stmt ->
                stmt.step()
                stmt.getLong(0).toInt()
            }
        }

    private companion object {
        const val BATCH_LIMIT = 500
    }
}
