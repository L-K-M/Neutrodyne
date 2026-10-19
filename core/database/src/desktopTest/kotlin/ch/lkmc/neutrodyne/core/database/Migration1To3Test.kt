// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ch.lkmc.neutrodyne.core.database.migration.ALL_MIGRATIONS
import ch.lkmc.neutrodyne.core.testing.database.MigrationInvariants
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class Migration1To3Test {
    private val dir = Files.createTempDirectory("sync-migrate1to3")

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
    fun populatedV1ChainEqualsFreshV3TriggersAndPreservesBookkeeping() =
        runTest {
            val before =
                helper.createDatabase(1).use { c ->
                    fixtureStatements().forEach { c.exec(it) }
                    MigrationInvariants.populateSync(c)
                    MigrationInvariants.capture(c)
                }
            val migrated =
                helper.runMigrationsAndValidate(3, ALL_MIGRATIONS.toList()).use { c ->
                    MigrationInvariants.assertCaptureInstalled(c)
                    MigrationInvariants.assertPreserved(before, MigrationInvariants.capture(c))
                    c.texts(
                        "SELECT sql FROM sqlite_master WHERE type='trigger'" +
                            " AND name GLOB 'sync_cap_*' ORDER BY name",
                    )
                }
            val freshPath = dir.resolve("fresh.db")
            val freshHelper =
                MigrationTestHelper(
                    schemaDirectoryPath = Path("schemas"),
                    databasePath = freshPath,
                    driver = BundledSQLiteDriver(),
                    databaseClass = NeutrodyneDatabase::class,
                )
            freshHelper.createDatabase(3).use { c ->
                // The helper does not execute callbacks on a fresh schema, so invoke the production hook.
                NeutrodyneDatabaseCallback(
                    ch.lkmc.neutrodyne.core.testing
                        .TestClock(),
                    true,
                ).onCreate(c)
                val fresh =
                    c.texts(
                        "SELECT sql FROM sqlite_master WHERE type='trigger'" +
                            " AND name GLOB 'sync_cap_*' ORDER BY name",
                    )
                assertEquals(migrated, fresh)
            }
        }
}
