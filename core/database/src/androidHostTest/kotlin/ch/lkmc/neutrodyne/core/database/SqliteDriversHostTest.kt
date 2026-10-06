// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The framework driver under Robolectric, schema-free (09 Test infrastructure › Room; spike S4,
 * 2026-10-06): it opens on the host JVM, `foreign_keys` defaults to off on each fresh connection
 * (SQLite's default; Room's `onOpen` turns it on — 02 Conventions), and Robolectric's native
 * SQLite is above the 3.18 dialect baseline all SQL is written for (02 SQL dialect baseline).
 */
@RunWith(RobolectricTestRunner::class)
class SqliteDriversHostTest {
    @Test
    fun frameworkDriverOpensWithForeignKeysOffPerConnection() {
        val driver = AndroidSQLiteDriver()

        driver.open(":memory:").use { connection ->
            assertEquals(0L, pragmaLong(connection, "PRAGMA foreign_keys"))
            connection.prepare("PRAGMA foreign_keys = ON").use { it.step() }
            assertEquals(1L, pragmaLong(connection, "PRAGMA foreign_keys"))
        }

        // The pragma is per connection: a new connection starts at off again.
        driver.open(":memory:").use { connection ->
            assertEquals(0L, pragmaLong(connection, "PRAGMA foreign_keys"))
        }
    }

    @Test
    fun robolectricSqliteIsAboveThe318Baseline() {
        AndroidSQLiteDriver().open(":memory:").use { connection ->
            val version = connection.prepare("SELECT sqlite_version()").use { statement ->
                check(statement.step())
                statement.getText(0)
            }
            val parts = version.split('.')
            val major = parts[0].toInt()
            val minor = parts[1].toInt()
            assertTrue(major > 3 || (major == 3 && minor >= 18), "unexpected version $version")
        }
    }

    private fun pragmaLong(connection: SQLiteConnection, pragma: String): Long =
        connection.prepare(pragma).use { statement ->
            check(statement.step()) { "no row for $pragma" }
            statement.getLong(0)
        }
}
