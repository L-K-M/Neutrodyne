// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.testing.MigrationTestHelper
import androidx.room3.withWriteTransaction
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ch.lkmc.neutrodyne.core.database.migration.ALL_MIGRATIONS
import ch.lkmc.neutrodyne.core.database.migration.MIGRATION_2_TO_3
import ch.lkmc.neutrodyne.core.testing.database.MigrationInvariants
import ch.lkmc.neutrodyne.core.testing.database.TestDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class Migration2To3Test {
    private val dir = Files.createTempDirectory("sync-migrate2to3")

    @get:Rule val helper =
        MigrationTestHelper(
            schemaDirectoryPath = Path("schemas"),
            databasePath = dir.resolve(NeutrodyneDatabase.FILE_NAME),
            driver = BundledSQLiteDriver(),
            databaseClass = NeutrodyneDatabase::class,
        )

    @After fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun populatedV2SurvivesAndProductionReopenCaptures() =
        runTest {
            val before =
                helper.createDatabase(2).use { c ->
                    fixtureStatements().forEach { c.exec(it) }
                    MigrationInvariants.populateSync(c)
                    MigrationInvariants.capture(c)
                }
            helper.runMigrationsAndValidate(3, ALL_MIGRATIONS.toList()).use { c ->
                MigrationInvariants.assertPreserved(before, MigrationInvariants.capture(c))
                MigrationInvariants.assertCaptureInstalled(c)
            }
            val db = TestDb.file(dir)
            try {
                val original = db.syncOutboxDao().rows()
                db.withWriteTransaction { db.syncOutboxDao().captureLiteral("setting", "after-migration", "v", "42") }
                assertEquals(original.size + 1, db.syncOutboxDao().count())
                assertEquals(original, db.syncOutboxDao().rows().filter { it.rid != "after-migration" })
                // A source write after production reopen must fire the installed SQL too.
                db.podcastDao().insertPodcast(
                    ch.lkmc.neutrodyne.core.testing.database
                        .podcastEntity(),
                )
                assertEquals(original.size + 2, db.syncOutboxDao().count())
            } finally {
                db.close()
            }
        }

    @Test
    fun rolledBackTriggerInstallPreservesStateAndRetries() =
        runTest {
            helper.createDatabase(2).use { c ->
                fixtureStatements().forEach { c.exec(it) }
                MigrationInvariants.populateSync(c)
                val before = MigrationInvariants.capture(c)
                c.exec("BEGIN IMMEDIATE")
                MIGRATION_2_TO_3.migrate(c)
                MigrationInvariants.assertCaptureInstalled(c)
                c.exec("ROLLBACK")
                assertEquals(
                    0L,
                    c.longQuery("SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name GLOB 'sync_cap_*'"),
                )
                MigrationInvariants.assertPreserved(before, MigrationInvariants.capture(c))
                c.exec("BEGIN IMMEDIATE")
                MIGRATION_2_TO_3.migrate(c)
                c.exec("COMMIT")
                MigrationInvariants.assertCaptureInstalled(c)
                MigrationInvariants.assertPreserved(before, MigrationInvariants.capture(c))
            }
        }
}
