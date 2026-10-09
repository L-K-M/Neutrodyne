// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.sqlite.SQLiteConnection

/** Executes one statement, discarding the result (migration/rebuild test plumbing). */
internal suspend fun SQLiteConnection.exec(sql: String) {
    prepare(sql).use { it.step() }
}

internal suspend fun SQLiteConnection.longQuery(sql: String): Long =
    prepare(sql).use { stmt ->
        check(stmt.step()) { "no row for $sql" }
        stmt.getLong(0)
    }

internal suspend fun SQLiteConnection.stringQuery(sql: String): String =
    prepare(sql).use { stmt ->
        check(stmt.step()) { "no row for $sql" }
        stmt.getText(0)
    }

internal suspend fun SQLiteConnection.texts(sql: String): List<String> =
    prepare(sql).use { stmt ->
        buildList {
            while (stmt.step()) add(stmt.getText(0))
        }
    }

// The same helpers for `useWriterConnection`/`useReaderConnection` blocks, whose parameter is a
// `PooledConnection` (the pooled wrapper, not a raw `SQLiteConnection`).
internal suspend fun androidx.room3.PooledConnection.exec(sql: String) {
    usePrepared(sql) { it.step() }
}

internal suspend fun androidx.room3.PooledConnection.longQuery(sql: String): Long =
    usePrepared(sql) { stmt ->
        check(stmt.step()) { "no row for $sql" }
        stmt.getLong(0)
    }

internal suspend fun androidx.room3.PooledConnection.stringQuery(sql: String): String =
    usePrepared(sql) { stmt ->
        check(stmt.step()) { "no row for $sql" }
        stmt.getText(0)
    }

/**
 * The `db/v1-fixture.sql` resource as individual statements: `--` comments stripped, split on
 * the statement terminator (the fixture contains no `;` inside literals).
 */
internal fun fixtureStatements(): List<String> =
    checkNotNull(object {}.javaClass.getResource("/db/v1-fixture.sql")) {
        "db/v1-fixture.sql missing from desktopTest resources"
    }.readText()
        .lineSequence()
        .filterNot { it.trimStart().startsWith("--") }
        .joinToString("\n")
        .split(";")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/** `assertFailsWith` is not suspend-aware; this helper is. */
internal suspend fun assertThrows(block: suspend () -> Unit): Throwable {
    try {
        block()
    } catch (t: Throwable) {
        return t
    }
    throw AssertionError("expected an exception but none was thrown")
}
