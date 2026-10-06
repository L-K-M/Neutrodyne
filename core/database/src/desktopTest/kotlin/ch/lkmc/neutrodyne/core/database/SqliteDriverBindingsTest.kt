// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The production driver binding (PLAN D9): `SqliteDriverBindings` provides a `BundledSQLiteDriver`
 * whose fresh connections keep SQLite's `foreign_keys` default (off) — Room's generated `onOpen`
 * turns it on once the database declares foreign keys (02 Conventions; spikes S3/S10, 2026-10-06).
 */
class SqliteDriverBindingsTest {
    @Test
    fun providesABundledDriverWhoseConnectionsDefaultForeignKeysOff() {
        val driver = SqliteDriverBindings.sqliteDriver()

        assertIs<BundledSQLiteDriver>(driver)
        driver.open(":memory:").use { connection ->
            assertEquals(0L, connection.queryPragmaLong("PRAGMA foreign_keys"))
            // The dialect baseline of all SQL (02): the bundled SQLite is far newer than 3.18.
            val version = connection.queryPragmaText("SELECT sqlite_version()")
            assertTrue(version.startsWith("3."), "unexpected sqlite version $version")
        }
    }
}

private fun SQLiteConnection.queryPragmaLong(sql: String): Long =
    prepare(sql).use { statement ->
        check(statement.step()) { "no row for $sql" }
        statement.getLong(0)
    }

private fun SQLiteConnection.queryPragmaText(sql: String): String =
    prepare(sql).use { statement ->
        check(statement.step()) { "no row for $sql" }
        statement.getText(0)
    }
