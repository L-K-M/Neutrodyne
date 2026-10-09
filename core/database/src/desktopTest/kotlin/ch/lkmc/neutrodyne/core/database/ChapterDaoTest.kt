// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import ch.lkmc.neutrodyne.core.testing.database.episodeEntity
import ch.lkmc.neutrodyne.core.testing.database.podcastEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `ChapterDao.replace`'s `(episodeId, source)` pair contract: rows keyed to another pair are
 * rejected inside the transaction, so a misuse fails loudly and the delete rolls back.
 */
class ChapterDaoTest {
    @Test
    fun replaceRejectsRowsKeyedToAnotherPair() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val podcastId = db.podcastDao().insertPodcast(podcastEntity())
                val e1 =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(episodeEntity(podcastId = podcastId, identityKey = "g:1")),
                        ).single()
                val e2 =
                    db
                        .ingestDao()
                        .insertEpisodes(
                            listOf(episodeEntity(podcastId = podcastId, identityKey = "g:2")),
                        ).single()

                db.chapterDao().replace(
                    e1,
                    ChapterSource.PSC,
                    listOf(
                        ChapterEntity(e1, ChapterSource.PSC, ordinal = 0, startMs = 0),
                        ChapterEntity(e1, ChapterSource.PSC, ordinal = 1, startMs = 1_000),
                    ),
                )

                // A row keyed to another episode is rejected, atomically — the delete rolls back.
                assertIs<IllegalArgumentException>(
                    assertThrows {
                        db.chapterDao().replace(
                            e1,
                            ChapterSource.PSC,
                            listOf(ChapterEntity(e2, ChapterSource.PSC, ordinal = 0, startMs = 0)),
                        )
                    },
                )
                // And a row keyed to another source.
                assertIs<IllegalArgumentException>(
                    assertThrows {
                        db.chapterDao().replace(
                            e1,
                            ChapterSource.PSC,
                            listOf(ChapterEntity(e1, ChapterSource.ID3, ordinal = 0, startMs = 0)),
                        )
                    },
                )

                assertEquals(2, db.chapterDao().ofSource(e1, ChapterSource.PSC).size)
                assertTrue(db.chapterDao().ofSource(e2, ChapterSource.PSC).isEmpty())
                assertTrue(db.chapterDao().ofSource(e1, ChapterSource.ID3).isEmpty())
            } finally {
                db.close()
            }
        }
}
