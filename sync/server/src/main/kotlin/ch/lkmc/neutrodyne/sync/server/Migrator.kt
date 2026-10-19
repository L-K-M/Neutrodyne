// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.sync.server

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Numbered, atomic server migrations. Fixtures can exercise a real supported-old upgrade. */
internal class Migrator(
    private val migrations: List<String> = listOf(initialSql()),
) {
    fun migrate(
        connection: Connection,
        dataDir: Path,
    ) {
        val version = version(connection)
        check(version <= migrations.size) { "Database schema is newer than this server" }
        if (version == migrations.size) return

        if (version > 0) {
            val timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC).format(Instant.now())
            databaseBackup(
                connection,
                dataDir.resolve("backups/neutrodyne-server-pre-migration-v$version-$timestamp-${UUID.randomUUID()}.db"),
            )
        } else {
            connection.createStatement().use { it.execute("PRAGMA auto_vacuum = INCREMENTAL") }
        }
        connection.autoCommit = false
        try {
            for (index in version until migrations.size) {
                // Migration resources contain plain statements, not triggers or procedural SQL.
                val sql = migrations[index].lineSequence().map { it.substringBefore("--") }.joinToString("\n")
                sql.split(';').filter { it.isNotBlank() }.forEach { statement ->
                    connection.createStatement().use { it.execute(statement) }
                }
                connection.createStatement().use {
                    it.executeUpdate("DELETE FROM schema_version")
                    it.executeUpdate("INSERT INTO schema_version VALUES (${index + 1})")
                }
            }
            if (version == 0) {
                connection.prepareStatement("INSERT INTO server_metadata VALUES (?)").use {
                    it.setString(1, UUID.randomUUID().toString())
                    it.executeUpdate()
                }
            }
            connection.commit()
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    internal companion object {
        const val SCHEMA_VERSION = 1

        fun version(connection: Connection): Int {
            val exists = connection.metaData.getTables(null, null, "schema_version", null).use { it.next() }
            if (!exists) return 0
            return connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version FROM schema_version").use {
                    check(it.next()) { "Missing schema version" }
                    val version = it.getInt(1)
                    check(version > 0 && !it.next()) { "Invalid schema version" }
                    version
                }
            }
        }

        private fun initialSql(): String =
            checkNotNull(
                Migrator::class.java.getResourceAsStream("/db/001-initial.sql"),
            ).bufferedReader().use { it.readText() }
    }
}

/** VACUUM INTO cannot run inside a transaction. Publish only a checked, complete copy. */
internal fun databaseBackup(
    connection: Connection,
    destination: Path,
): Path {
    val target = destination.toAbsolutePath()
    Files.createDirectories(target.parent)
    val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.tmp")
    try {
        connection.createStatement().use {
            it.execute("VACUUM INTO '${temporary.toString().replace("'", "''")}'")
        }
        DriverManager.getConnection("jdbc:sqlite:$temporary").use(::quickCheck)
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        return target
    } finally {
        Files.deleteIfExists(temporary)
    }
}

internal fun quickCheck(connection: Connection) {
    connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA quick_check").use {
            check(it.next() && it.getString(1) == "ok" && !it.next()) { "Database integrity check failed" }
        }
    }
}
