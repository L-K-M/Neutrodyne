// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 02 Testing's smoke test: the database opens, every DAO's read works on an empty database, the
 * two `onCreate` singleton rows exist, foreign keys are on for the writer and a reader, and the
 * exported `1.json` declares AUTOINCREMENT for every `autoGenerate` primary key.
 */
class SchemaSmokeTest {
    @Test
    fun opensAndReadsEveryDaoOnAnEmptyDatabase() =
        runTest {
            val db = TestDb.inMemory()
            try {
                assertNull(db.podcastDao().byId(1))
                assertNull(db.episodeDao().byId(1))
                assertTrue(db.ingestDao().existing(1).isEmpty())
                assertNull(db.groupDao().groupById(1))
                assertNull(db.scopeSettingsDao().forPodcast(1))
                assertNull(db.episodeStateDao().byEpisode(1))
                assertNull(db.positionDao().byEpisode(1))
                assertTrue(db.queueDao().entries().isEmpty())
                assertNotNull(db.playSessionDao().get())
                assertNull(db.downloadDao().byEpisode(1))
                assertNull(db.artworkDao().byKey("u-x"))
                assertTrue(db.chapterDao().ofSource(1, ChapterSource.PSC).isEmpty())
                assertTrue(db.credentialDao().byOrigin("https://example.com").isEmpty())
                assertTrue(db.importDao().itemsOf(1).isEmpty())
                assertNotNull(db.backupDao())
                assertNotNull(db.maintenanceDao())
                assertNotNull(db.syncStateDao().get())
                assertTrue(db.syncOutboxDao().rows().isEmpty())
                assertNull(db.syncClockDao().get("podcast", "rid"))
                assertTrue(db.syncParkedDao().forPodcast("rid").isEmpty())
                assertEquals(0, db.syncHeldDao().count())

                // FeedDao.page is the one PagingSource read; an empty page is a valid load.
                val source =
                    db.feedDao().page(
                        FeedQueryBuilder.page(FeedSource.All, FeedFilters(), FeedOrder.NEWEST_FIRST),
                    )
                val result =
                    source.load(
                        androidx.paging.PagingSource.LoadParams.Refresh(
                            key = null,
                            loadSize = 10,
                            placeholdersEnabled = false,
                        ),
                    )
                assertTrue(result is androidx.paging.PagingSource.LoadResult.Page)
                assertTrue(result.data.isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun singletonRowsExistRightAfterCreate() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val session = db.playSessionDao().get()
                assertNotNull(session)
                assertEquals(0, session.id)
                assertEquals(0L, session.generation)
                assertNull(session.currentEpisodeId)

                val sync = db.syncStateDao().get()
                assertNotNull(sync)
                assertEquals(0, sync.id)
                assertFalse(sync.enabled)
                assertFalse(sync.applying)
                assertNull(sync.nodeId)

                // Nothing is playing: the convenience flow emits null rather than no row.
                assertNull(db.playSessionDao().observeCurrentEpisodeId().first())
            } finally {
                db.close()
            }
        }

    @Test
    fun foreignKeysOnWriterAndReader() =
        runTest {
            val db = TestDb.inMemory()
            try {
                val onWriter =
                    db.useWriterConnection { conn -> conn.longQuery("PRAGMA foreign_keys") }
                val onReader =
                    db.useReaderConnection { conn -> conn.longQuery("PRAGMA foreign_keys") }
                assertEquals(1L, onWriter, "writer connection must run with PRAGMA foreign_keys = 1")
                assertEquals(1L, onReader, "reader connection must run with PRAGMA foreign_keys = 1")
            } finally {
                db.close()
            }
        }

    @Test
    fun schemaJsonDeclaresAutoincrementForEveryAutoGenerateKey() {
        val schema =
            Path.of("schemas/ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase/1.json")
        assertTrue(Files.exists(schema), "exported $schema is missing")
        val text = Files.readString(schema)

        val autoincrementTables =
            listOf(
                "podcast",
                "credential",
                "podcast_group",
                "episode",
                "person",
                "funding",
                "queue_entry",
                "import_session",
                "sync_parked",
                "sync_held",
            )
        // Each entity block starts at `"tableName": "<name>"` and runs to the next entity.
        val starts =
            Regex("\"tableName\":\\s*\"(\\w+)\"")
                .findAll(text)
                .associate { it.groupValues[1] to it.range.first }
        val ordered = starts.entries.sortedBy { it.value }
        for (table in autoincrementTables) {
            val start = checkNotNull(starts[table]) { "$table missing from 1.json" }
            val end = ordered.firstOrNull { it.value > start }?.value ?: text.length
            assertTrue("AUTOINCREMENT" in text.substring(start, end), "$table must declare AUTOINCREMENT")
        }
        assertEquals(
            autoincrementTables.size,
            Regex("AUTOINCREMENT").findAll(text).count(),
            "only the autoGenerate tables may declare AUTOINCREMENT",
        )
    }
}
