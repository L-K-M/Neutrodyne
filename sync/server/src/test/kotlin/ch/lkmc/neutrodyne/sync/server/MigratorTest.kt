// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MigratorTest {
    @Test fun `populated supported old schema is backed up before atomic upgrade`() {
        val dir = tempDataDir()
        SqliteSyncStore(dir).close()
        accountFixture(dir)
        dbFixture(dir) { connection ->
            Migrator(
                listOf(initialSql(), "ALTER TABLE account ADD COLUMN fixture TEXT DEFAULT 'preserved';"),
            ).migrate(connection, dir)
            assertEquals(2, Migrator.version(connection))
            connection.createStatement().executeQuery("SELECT username,fixture FROM account").use {
                assertTrue(it.next())
                assertEquals("user1", it.getString(1))
                assertEquals("preserved", it.getString(2))
            }
        }
        val backups = Files.list(dir.resolve("backups")).use { it.toList() }
        assertEquals(1, backups.size)
        DriverManager.getConnection("jdbc:sqlite:${backups.single()}").use { connection ->
            quickCheck(connection)
            assertEquals(1, Migrator.version(connection))
            connection.createStatement().executeQuery("SELECT username FROM account").use {
                assertTrue(it.next())
                assertEquals("user1", it.getString(1))
            }
        }
    }

    @Test fun `failed upgrade preserves old schema data version and readable backup`() {
        val dir = tempDataDir()
        SqliteSyncStore(dir).close()
        accountFixture(dir)
        dbFixture(dir) { connection ->
            assertFailsWith<java.sql.SQLException> {
                Migrator(
                    listOf(
                        initialSql(),
                        "ALTER TABLE account ADD COLUMN doomed TEXT; DELETE FROM account; INVALID SQL;",
                    ),
                ).migrate(connection, dir)
            }
            assertEquals(1, Migrator.version(connection))
            connection.createStatement().executeQuery("SELECT username FROM account").use {
                assertTrue(it.next())
                assertEquals("user1", it.getString(1))
            }
            assertFailsWith<java.sql.SQLException> {
                connection.createStatement().executeQuery(
                    "SELECT doomed FROM account",
                )
            }
        }
        SqliteSyncStore(dir).close()
        val backup = Files.list(dir.resolve("backups")).use { it.toList().single() }
        DriverManager.getConnection("jdbc:sqlite:$backup").use(::quickCheck)
    }

    @Test fun `newer schema refuses startup without changing records`() {
        val dir = tempDataDir()
        SqliteSyncStore(dir).close()
        accountFixture(dir)
        dbFixture(dir) { connection -> connection.createStatement().execute("UPDATE schema_version SET version=99") }
        assertFailsWith<IllegalStateException> { SqliteSyncStore(dir) }
        dbFixture(dir) { connection ->
            assertEquals(99, Migrator.version(connection))
            connection.createStatement().executeQuery("SELECT username FROM account").use {
                assertTrue(it.next())
                assertEquals("user1", it.getString(1))
            }
        }
        assertTrue(!Files.exists(dir.resolve("backups")))
    }

    private fun initialSql() =
        checkNotNull(javaClass.getResourceAsStream("/db/001-initial.sql")).bufferedReader().use {
            it.readText()
        }
}
