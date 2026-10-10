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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 02 Migration tests / MigrateAllTest: `MigrationTestHelper` creates the database at version 1
 * from the frozen `1.json`, `v1-fixture.sql` fills every table, `runMigrationsAndValidate` brings
 * the chain up to [NeutrodyneDatabase.VERSION] through Room's own per-table validation, and
 * [MigrationInvariants] verifies nothing was lost. The migrated file then opens through the
 * production builder and every DAO reads it.
 *
 * The runtime's `SchemaInfoUtil.readIndex` filters `PRAGMA index_xinfo` `key=0` rows
 * (vendored patch 0003, `RoomIndexInfoTest`), so a secondary index on the WITHOUT ROWID
 * `sync_outbox` validates as its declared two columns and not its physical five with the
 * implicit primary-key suffix. Without that filter Room's post-migration validation fails on
 * a table no migration touches; both validation paths exercised here depend on it.
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
                // V1 rows migrate with no provenance coverage or records: the marker stays null
                // and the derived table starts empty (02 episode_guid_provenance).
                assertNull(db.podcastDao().byId(1)!!.guidCoverageSince)

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

            BundledSQLiteDriver().open(dir.resolve(NeutrodyneDatabase.FILE_NAME).toString()).use { conn ->
                assertEquals(0L, conn.longQuery("SELECT COUNT(*) FROM episode_guid_provenance"))
            }
        }

    @Test
    fun aColumnBoundarySlideStillChangesTheDigest() =
        runTest {
            // Two adjacent TEXT columns whose values differ only by where the 0x01 byte lands
            // ("a\x01"/"b" vs "a"/"\x01b") would digest identically without the length prefix —
            // the invariant would report a real data change as preserved.
            helper.createDatabase(1).use { conn ->
                fixtureStatements().forEach { conn.exec(it) }
                conn.exec("UPDATE podcast SET feedKey = 'a' || char(1), feedUrl = 'b' WHERE id = 1")
                val before = MigrationInvariants.capture(conn)
                conn.exec("UPDATE podcast SET feedKey = 'a', feedUrl = char(1) || 'b' WHERE id = 1")
                val after = MigrationInvariants.capture(conn)

                val error =
                    assertFailsWith<IllegalStateException> {
                        MigrationInvariants.assertPreserved(before, after)
                    }
                assertTrue(
                    "subscriptions" in error.message.orEmpty(),
                    "the subscriptions projection must flag the slide, got ${error.message}",
                )
            }
        }
}
