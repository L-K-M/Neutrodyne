// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.downloadEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 02 Identity keys: `(podcastId, identityKey)` is unique with an ABORT insert — a second row with
 * the same key never exists, and `rekey` moves the key while the row id and every user-state row
 * survive.
 */
class IdentityStorageTest {
    @Test
    fun duplicateIdentityKeyAbortsTheInsert() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val podcastId = db.podcastDao().insertPodcast(podcastEntity())
                val first = episodeEntity(podcastId = podcastId, identityKey = "guid:abc")
                db.ingestDao().insertEpisodes(listOf(first))

                // A duplicate inside a batch aborts the whole insert: none of the new rows survives.
                val batch =
                    listOf(
                        episodeEntity(podcastId = podcastId, identityKey = "guid:new1"),
                        episodeEntity(podcastId = podcastId, identityKey = "guid:abc", title = "dup"),
                        episodeEntity(podcastId = podcastId, identityKey = "guid:new2"),
                    )
                val error = assertThrows { db.ingestDao().insertEpisodes(batch) }
                assertTrue(
                    "unique" in (error.message ?: "").lowercase(),
                    "expected a UNIQUE constraint failure, got $error",
                )
                assertEquals(1, db.ingestDao().existing(podcastId).size)
            } finally {
                db.close()
            }
        }

    @Test
    fun rekeyMovesTheIdentityAndKeepsUserState() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val podcastId = db.podcastDao().insertPodcast(podcastEntity())
                val episodeId =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(episodeEntity(podcastId = podcastId, identityKey = "url:enc-old")),
                        ).single()

                db.episodeStateDao().upsert(episodeStateEntity(episodeId, playedAt = 1_700_000_000_000L))
                db.positionDao().upsert(
                    EpisodePositionEntity(
                        episodeId,
                        positionMs = 42_000,
                        positionSource = PositionSource.STREAM,
                        updatedAt = 1,
                    ),
                )
                db.queueDao().insert(queueEntryEntity(episodeId, orderKey = "a0"))
                db.downloadDao().insert(downloadEntity(episodeId))

                db.ingestDao().rekey(episodeId, key = "guid:new-guid", guid = "new-guid")

                val moved = assertNotNull(db.episodeDao().byId(episodeId))
                assertEquals("guid:new-guid", moved.identityKey)
                assertEquals("new-guid", moved.guid)
                assertNotNull(db.episodeStateDao().byEpisode(episodeId))
                assertEquals(42_000, db.positionDao().byEpisode(episodeId)?.positionMs)
                assertEquals(1, db.queueDao().entries().size)
                assertNotNull(db.downloadDao().byEpisode(episodeId))
            } finally {
                db.close()
            }
        }
}
