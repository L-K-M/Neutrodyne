// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.model.ImportFormat
import ch.lkmc.neutrodyne.core.model.ImportItemKind
import ch.lkmc.neutrodyne.core.model.ImportItemStatus
import ch.lkmc.neutrodyne.core.model.ImportState
import ch.lkmc.neutrodyne.core.model.PodcastStatus
import ch.lkmc.neutrodyne.core.testing.TestClock
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PLAN M1 acceptance 11: with no sync server configured, the `sync_*` groundwork tables stay
 * empty and `sync_state` stays at its `onCreate` defaults through every DAO-drivable M1 journey —
 * subscribe, refresh (insert/update/fetch-state), import, restore stub and unsubscribe. And every
 * insert path stamps a fresh unique `podcast.syncId`.
 */
class SyncInertTest {
    @Test
    fun m1JourneysLeaveTheSyncTablesEmpty() =
        runTest {
            val db = TestDb.inMemory()
            try {
                // Subscribe (03's insert path) — two rows, two fresh UUIDv4 syncIds.
                val a = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-a"))
                val b = db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-b"))

                // Refresh (03): episodes, feed-field updates, fetch-state writes.
                val ep =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(episodeEntity(podcastId = a, identityKey = "g:1")),
                        ).single()
                db.ingestDao().setInFeed(listOf(ep), inFeed = false)
                db.ingestDao().touchSeen(a, TestClock.DEFAULT_NOW + 100)
                FetchStateBatcher(db.podcastDao(), TestClock()).apply {
                    add(
                        PodcastFetchState(
                            id = a,
                            lastAttemptAt = 1,
                            lastSuccessAt = 1,
                            nextRefreshAt = 10,
                            failureCount = 0,
                            lastErrorKind = null,
                            lastErrorDetail = null,
                            gone = false,
                            needsCredentials = false,
                            etag = "e",
                            lastModified = null,
                            lastFullFetchAt = 1,
                            lastParseOk = true,
                        ),
                    )
                    flush()
                }

                // Import (05): a session with items.
                val session =
                    db.importDao().insertSession(
                        ImportSessionEntity(
                            createdAt = 1,
                            sourceFormat = ImportFormat.OPML,
                            state = ImportState.DONE,
                        ),
                    )
                db.importDao().insertItems(
                    listOf(
                        ImportItemEntity(
                            sessionId = session,
                            ordinal = 0,
                            originalUrl = "https://feed/c",
                            kind = ImportItemKind.RSS,
                            groupNamesJson = "[]",
                            selected = true,
                            status = ImportItemStatus.SUBSCRIBED,
                            podcastId = b,
                        ),
                    ),
                )

                // Restore (05): an inFeed=0 stub that a later refresh claims.
                db.episodeDao().insertStub(
                    episodeEntity(podcastId = b, identityKey = "g:stub") { copy(inFeed = false) },
                )

                // Unsubscribe (02): the full cascade.
                db.podcastDao().deleteCascade(b, now = 2)
                assertNull(db.podcastDao().byId(b))

                // The groundwork stays inert.
                assertEquals(0, db.syncOutboxDao().count())
                assertEquals(0, db.syncClockDao().count())
                assertEquals(0, db.syncParkedDao().count())
                assertEquals(0, db.syncHeldDao().count())

                val state = assertNotNull(db.syncStateDao().get())
                assertEquals(
                    SyncStateEntity(),
                    state,
                    "sync_state must still be the onCreate default row",
                )

                // Every insert path stamped a lowercase UUIDv4 syncId.
                val syncId = assertNotNull(db.podcastDao().byId(a)).syncId
                assertTrue(syncId.matches(UUID_V4), "syncId $syncId is not a lowercase UUIDv4")
            } finally {
                db.close()
            }
        }

    @Test
    fun everyInsertPathAssignsAUniqueSyncId() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val ids =
                    List(3) {
                        db.podcastDao().insertPodcast(podcastEntity(feedKey = "k-$it"))
                    }
                val syncIds = ids.map { assertNotNull(db.podcastDao().byId(it)).syncId }
                assertTrue(syncIds.all { it.matches(UUID_V4) })
                assertEquals(3, syncIds.toSet().size, "syncIds must be unique per podcast")
            } finally {
                db.close()
            }
        }

    private companion object {
        val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}
