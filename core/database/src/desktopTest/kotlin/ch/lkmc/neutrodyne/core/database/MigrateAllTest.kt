// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ch.lkmc.neutrodyne.core.database.migration.ALL_MIGRATIONS
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.testing.database.MigrationInvariants
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 02 Migration tests / MigrateAllTest: `MigrationTestHelper` creates the database at version 1
 * from the frozen `1.json`, `v1-fixture.sql` fills every table, `runMigrationsAndValidate` brings
 * the chain up to [NeutrodyneDatabase.VERSION] (the chain is empty at v1 — the test still proves
 * the harness, the fixture and the invariants), and [MigrationInvariants] verifies nothing was
 * lost. The migrated file then opens through the production builder and every DAO reads it.
 */
class MigrateAllTest {
    private val dir = Files.createTempDirectory("m1a-migrate")

    @get:Rule
    val helper =
        MigrationTestHelper(
            schemaDirectoryPath = Path("schemas"),
            databasePath = dir.resolve(NeutrodyneDatabase.FILE_NAME),
            driver = BundledSQLiteDriver(),
            databaseClass = NeutrodyneDatabase::class,
        )

    @Test
    fun v1FixtureSurvivesTheMigrationChainAndRoomReadsIt() =
        runTest {
            val before =
                helper.createDatabase(1).use { conn ->
                    fixtureStatements().forEach { conn.exec(it) }
                    MigrationInvariants.capture(conn)
                }

            val after =
                helper
                    .runMigrationsAndValidate(
                        NeutrodyneDatabase.VERSION,
                        ALL_MIGRATIONS.toList(),
                    ).use { conn ->
                        MigrationInvariants.capture(conn)
                    }

            MigrationInvariants.assertPreserved(before, after)

            // The migrated file opens through the production builder; one read per DAO proves the
            // fixture rows map cleanly onto the entities.
            val db = TestDb.file(dir)
            try {
                assertNotNull(db.podcastDao().byId(1))
                assertNotNull(db.episodeDao().byId(1))
                assertTrue(db.ingestDao().existing(1).isNotEmpty())
                assertNotNull(db.groupDao().groupById(1))
                assertNotNull(db.scopeSettingsDao().forPodcast(1))
                assertNotNull(db.episodeStateDao().byEpisode(1))
                assertNotNull(db.positionDao().byEpisode(1))
                assertTrue(db.queueDao().entries().isNotEmpty())
                assertNotNull(db.playSessionDao().get())
                assertNotNull(db.downloadDao().byEpisode(1))
                assertNotNull(db.artworkDao().byKey("u-cover-1"))
                assertTrue(db.chapterDao().ofSource(1, ChapterSource.PSC).isNotEmpty())
                assertTrue(db.credentialDao().byOrigin("feeds.example.com").isNotEmpty())
                assertTrue(db.importDao().itemsOf(1).isNotEmpty())
                assertNotNull(db.backupDao())
                assertNotNull(db.maintenanceDao())
                assertNotNull(db.syncStateDao().get())
                assertTrue(db.syncOutboxDao().rows().isNotEmpty())
                assertNotNull(db.syncClockDao().get("podcast", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
                assertTrue(db.syncParkedDao().forPodcast("dddddddd-dddd-4ddd-8ddd-dddddddddddd").isNotEmpty())
                assertTrue(db.syncHeldDao().count() > 0)

                val page =
                    db
                        .feedDao()
                        .page(FeedQueryBuilder.page(FeedSource.All, FeedFilters(), FeedOrder.NEWEST_FIRST))
                        .load(
                            androidx.paging.PagingSource.LoadParams.Refresh(
                                key = null,
                                loadSize = 10,
                                placeholdersEnabled = false,
                            ),
                        )
                assertTrue(page is androidx.paging.PagingSource.LoadResult.Page)
            } finally {
                db.close()
            }
        }
}
