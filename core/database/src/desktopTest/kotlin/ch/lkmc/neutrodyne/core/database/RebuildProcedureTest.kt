// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ch.lkmc.neutrodyne.core.database.migration.TableRebuild
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 02 `TableRebuild`: rebuilds run under `PRAGMA foreign_keys = 0` inside an exclusive transaction —
 * children of the rebuilt table survive (no cascade), capture triggers are dropped first,
 * `sqlite_sequence` keeps its high-water mark, indices are recreated, and `foreign_key_check`
 * runs at the end. The procedure refuses to run with foreign keys on.
 */
class RebuildProcedureTest {
    private val dir = Files.createTempDirectory("m1a-rebuild")

    @get:Rule
    val helper =
        MigrationTestHelper(
            schemaDirectoryPath = Path("schemas"),
            databasePath = dir.resolve(NeutrodyneDatabase.FILE_NAME),
            driver = BundledSQLiteDriver(),
            databaseClass = NeutrodyneDatabase::class,
        )

    @Test
    fun rebuildPreservesChildrenSequenceAndDropsCaptureTriggers() =
        runTest {
            helper.createDatabase(1).use { conn ->
                fixtureStatements().forEach { conn.exec(it) }
                conn.exec(
                    "CREATE TRIGGER sync_cap_podcast_upd AFTER UPDATE ON podcast" +
                        " BEGIN SELECT 1; END",
                )
                conn.exec(
                    "CREATE TRIGGER sync_cap_episode_ins AFTER INSERT ON episode" +
                        " BEGIN SELECT 1; END",
                )

                val childTables =
                    listOf(
                        "episode",
                        "episode_state",
                        "queue_entry",
                        "download",
                        "podcast_url_alias",
                        "podcast_settings",
                        "podcast_group_member",
                        "import_item",
                    )
                val before =
                    childTables.associateWith { conn.longQuery("SELECT COUNT(*) FROM $it") }
                val seqBefore = conn.longQuery("SELECT seq FROM sqlite_sequence WHERE name = 'podcast'")
                val podcastRows = conn.longQuery("SELECT COUNT(*) FROM podcast")

                // The rebuild runs with FK off inside an exclusive transaction (the migration
                // connection's state, S3).
                conn.exec("PRAGMA foreign_keys = 0")
                conn.exec("BEGIN EXCLUSIVE")
                try {
                    val ddl =
                        conn.stringQuery(
                            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'podcast'",
                        )
                    val columns =
                        conn.texts("SELECT name FROM pragma_table_info('podcast')")
                    val indexSql =
                        conn.texts(
                            "SELECT sql FROM sqlite_master WHERE type = 'index'" +
                                " AND tbl_name = 'podcast' AND sql IS NOT NULL",
                        )
                    TableRebuild.run(
                        connection = conn,
                        table = "podcast",
                        newTableSql = ddl.replaceFirst("`podcast`", "`new_podcast`"),
                        columnMap = columns.associateWith { it },
                        indexSql = indexSql,
                    )
                    conn.exec("COMMIT")
                } catch (t: Throwable) {
                    conn.exec("ROLLBACK")
                    throw t
                }

                // Children survive: foreign_keys = 0 meant no cascade fired on DROP.
                for ((table, n) in before) {
                    assertEquals(n, conn.longQuery("SELECT COUNT(*) FROM $table"), "$table rows changed")
                }
                assertEquals(podcastRows, conn.longQuery("SELECT COUNT(*) FROM podcast"))

                // The AUTOINCREMENT high-water mark is preserved.
                assertEquals(
                    seqBefore,
                    conn.longQuery("SELECT seq FROM sqlite_sequence WHERE name = 'podcast'"),
                )

                // Both sync_cap_* triggers are gone; the table's indices were recreated.
                val triggers =
                    conn.texts("SELECT name FROM sqlite_master WHERE type = 'trigger'")
                assertFalse(triggers.any { it.startsWith("sync_cap_") }, "capture triggers survived: $triggers")
                val indices =
                    conn.texts(
                        "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'podcast'",
                    )
                assertTrue(indices.isNotEmpty(), "podcast indices were not recreated")
                assertTrue(
                    conn.texts("PRAGMA foreign_key_check").isEmpty(),
                    "foreign_key_check found violations after the rebuild",
                )
            }
        }

    @Test
    fun refusesToRunWithForeignKeysOn() =
        runTest {
            helper.createDatabase(1).use { conn ->
                conn.exec("PRAGMA foreign_keys = 1")
                conn.exec("BEGIN")
                val error =
                    try {
                        TableRebuild.run(
                            connection = conn,
                            table = "episode",
                            newTableSql = "CREATE TABLE new_episode(id INTEGER PRIMARY KEY)",
                            columnMap = mapOf("id" to "id"),
                        )
                        null
                    } catch (t: IllegalStateException) {
                        t
                    } finally {
                        conn.exec("ROLLBACK")
                    }
                checkNotNull(error) { "TableRebuild ran with PRAGMA foreign_keys = 1" }
            }
        }
}
