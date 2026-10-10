// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ch.lkmc.neutrodyne.core.database.migration.MIGRATION_1_TO_2
import ch.lkmc.neutrodyne.core.testing.database.MigrationInvariants
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1 → V2 (`MIGRATION_1_TO_2`, 02 Migrations): the migration runs its real `migrate` body inside a
 * transaction on the frozen V1 fixture and must preserve every invariant while adding
 * `episode_guid_provenance` (empty) and `podcast.guidCoverageSince` (NULL on every migrated row —
 * erased V1 history must never be reconstructed as GUID coverage).
 *
 * This invokes the same `Migration.migrate(connection)` Room calls, but on the raw driver
 * connection rather than through `runMigrationsAndValidate`: it isolates what the migration
 * itself is responsible for — data preservation, the new table's shape, the coverage default,
 * and atomic retry after a rolled-back attempt — while `MigrateAllTest` covers the
 * Room-integrated path (per-table validation, production open, DAO reads).
 */
class Migration1To2Test {
    private val dir = Files.createTempDirectory("m1a-m1to2")

    @get:Rule
    val helper =
        MigrationTestHelper(
            schemaDirectoryPath = Path("schemas"),
            databasePath = dir.resolve(NeutrodyneDatabase.FILE_NAME),
            driver = BundledSQLiteDriver(),
            databaseClass = NeutrodyneDatabase::class,
        )

    @Test
    fun v1FixtureSurvivesTheMigrationAndProvenanceStartsEmpty() =
        runTest {
            helper.createDatabase(1).use { conn ->
                fixtureStatements().forEach { conn.exec(it) }
                val before = MigrationInvariants.capture(conn)

                conn.transaction { MIGRATION_1_TO_2.migrate(conn) }

                MigrationInvariants.assertPreserved(before, MigrationInvariants.capture(conn))

                assertEquals(0L, conn.longQuery("SELECT COUNT(*) FROM episode_guid_provenance"))
                assertEquals(
                    conn.longQuery("SELECT COUNT(*) FROM podcast"),
                    conn.longQuery("SELECT COUNT(*) FROM podcast WHERE guidCoverageSince IS NULL"),
                    "a migrated V1 library has no coverage: every GUID stays UNKNOWN",
                )

                // Table shape per 02 episode_guid_provenance: composite PK (podcastId, guid),
                // knowledge TEXT, and a real FK to podcast that cascades on unsubscribe.
                assertEquals(
                    listOf("podcastId", "guid", "knowledge"),
                    conn.texts("SELECT name FROM pragma_table_info('episode_guid_provenance')"),
                )
                assertEquals(
                    2L,
                    conn.longQuery(
                        "SELECT COUNT(*) FROM pragma_table_info('episode_guid_provenance') WHERE pk > 0",
                    ),
                )
                val fk =
                    conn.texts(
                        "SELECT \"table\" || '.' || \"to\" || ' ' || on_delete FROM pragma_foreign_key_list('episode_guid_provenance')",
                    )
                assertEquals(listOf("podcast.id CASCADE"), fk)
            }
        }

    @Test
    fun aRolledBackAttemptLeavesNoTraceAndRetriesCleanly() =
        runTest {
            helper.createDatabase(1).use { conn ->
                fixtureStatements().forEach { conn.exec(it) }
                val before = MigrationInvariants.capture(conn)

                // Abort after the migration body ran: the transaction boundary must discard both
                // statements, and the database must still look exactly like V1.
                conn.exec("BEGIN IMMEDIATE")
                MIGRATION_1_TO_2.migrate(conn)
                conn.exec("ROLLBACK")

                assertFalse(conn.tableExists("episode_guid_provenance"))
                assertFalse(conn.columnExists("podcast", "guidCoverageSince"))
                MigrationInvariants.assertPreserved(before, MigrationInvariants.capture(conn))

                // A partial failed upgrade retries cleanly (idempotent through the transaction,
                // 02 Migrations): the second attempt produces the finished V2 additions.
                conn.transaction { MIGRATION_1_TO_2.migrate(conn) }

                assertTrue(conn.tableExists("episode_guid_provenance"))
                assertTrue(conn.columnExists("podcast", "guidCoverageSince"))
                MigrationInvariants.assertPreserved(before, MigrationInvariants.capture(conn))
            }
        }

    private suspend fun SQLiteConnection.transaction(block: suspend () -> Unit) {
        exec("BEGIN IMMEDIATE")
        try {
            block()
            exec("COMMIT")
        } catch (t: Throwable) {
            exec("ROLLBACK")
            throw t
        }
    }

    private suspend fun SQLiteConnection.tableExists(table: String): Boolean =
        prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { stmt ->
            stmt.bindText(1, table)
            stmt.step()
        }

    private suspend fun SQLiteConnection.columnExists(
        table: String,
        column: String,
    ): Boolean = texts("SELECT name FROM pragma_table_info('$table')").contains(column)
}
