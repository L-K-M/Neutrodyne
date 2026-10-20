// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.database

import app.cash.turbine.test
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.episodeStateEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import ch.lkmc.neutrodyne.core.testing.database.queueEntryEntity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadCleanupQueriesTest {
    @Test fun rollingQueryIncludesProtectedOccupantsAndChunksDistinctPodcastIdsAtFiveHundred() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val podcasts = (0..500).map { db.podcastDao().insertPodcast(podcastEntity()) }
                val ids =
                    podcasts.mapIndexed { n, podcast ->
                        val id = seed(db, podcast, n.toLong())
                        if (n == 0) db.episodeStateDao().upsert(episodeStateEntity(id) { copy(startedAt = 1) })
                        if (n == 1) db.episodeStateDao().upsert(episodeStateEntity(id) { copy(isFavorite = true) })
                        id
                    }
                val newest = seed(db, podcasts[0], 999, DownloadLane.AUTO)
                val manual = seed(db, podcasts[0], 1000, DownloadLane.MANUAL)
                val played = seed(db, podcasts[0], 1001)
                db.episodeStateDao().upsert(episodeStateEntity(played, 1))
                val rows = db.downloadDao().unplayedAutoCompleted(podcasts + podcasts, 3)
                assertEquals(502, rows.size)
                assertEquals(listOf(newest, ids[0]), rows.filter { it.podcastId == podcasts[0] }.map { it.episodeId })
                assertTrue(rows.single { it.episodeId == ids[0] }.protected)
                assertTrue(rows.single { it.episodeId == ids[1] }.protected)
                assertFalse(rows.single { it.episodeId == newest }.protected)
                assertTrue(rows.none { it.episodeId == manual || it.episodeId == played })
                assertTrue(db.downloadDao().unplayedAutoCompleted(emptyList(), 3).isEmpty())
            } finally {
                db.close()
            }
        }

    @Test fun playedCompletedObserverTracksPlayedAndCompletionTransitionsOnly() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val podcastId = db.podcastDao().insertPodcast(podcastEntity())
                val first = seed(db, podcastId, 1)
                val second = seed(db, podcastId, 2)
                val dao = db.downloadDao()
                dao.observePlayedCompletedIds().distinctUntilChanged().test {
                    assertEquals(emptyList(), awaitItem())
                    db.episodeStateDao().upsert(episodeStateEntity(first, 1))
                    assertEquals(listOf(first), awaitItem())
                    db.episodeStateDao().upsert(episodeStateEntity(second, 2))
                    assertEquals(listOf(first, second), awaitItem())
                    val row = checkNotNull(dao.byEpisode(second))
                    dao.updateUnclaimed(row.copy(state = DownloadState.MISSING), row.state)
                    assertEquals(listOf(first), awaitItem())
                    dao.updateUnclaimed(row, DownloadState.MISSING)
                    assertEquals(listOf(first, second), awaitItem())
                    db.episodeStateDao().upsert(episodeStateEntity(first))
                    assertEquals(listOf(second), awaitItem())
                    dao.removeUnclaimed(listOf(second))
                    assertEquals(emptyList(), awaitItem())
                    cancelAndIgnoreRemainingEvents()
                }
            } finally {
                db.close()
            }
        }

    private suspend fun seed(
        db: NeutrodyneDatabase,
        podcastId: Long,
        date: Long,
        lane: DownloadLane = DownloadLane.AUTO,
    ): Long {
        val id =
            db.episodeDao().insertStub(
                episodeEntity(podcastId = podcastId, sortDate = date) { copy(guid = "g$date") },
            )
        db.downloadDao().insert(
            DownloadEntity(
                episodeId = id,
                lane = lane,
                state = DownloadState.COMPLETED,
                priority = 0,
                requestedAt = 1,
                sourceKind = SourceKind.RSS_ENCLOSURE,
                sourceRef = "https://example.org/$id.mp3",
                rootId = "old",
                allowMetered = true,
            ),
        )
        return id
    }
}
