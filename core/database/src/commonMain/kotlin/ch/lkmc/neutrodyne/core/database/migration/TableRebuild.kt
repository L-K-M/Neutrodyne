// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database.migration

import androidx.sqlite.SQLiteConnection

/**
 * The table-rebuild procedure every manual migration uses (02 Writing migrations): Room's own
 * rebuild has the two documented hazards — `ON DELETE` actions firing with `foreign_keys = ON`,
 * and the `AUTOINCREMENT` high-water mark resetting — so rebuilds run this instead.
 *
 * [newTableSql] is the `CREATE TABLE` DDL of the new table written under the temporary name
 * `new_<table>` (exactly the new schema JSON's `createSql` with the name replaced); [columnMap]
 * maps each new column to the `SELECT` expression over the old table (usually the same name);
 * [indexSql] recreates every index of the table from the schema JSON.
 */
object TableRebuild {
    /**
     * Runs the documented order on [connection] (already inside Room's migration transaction, with
     * `PRAGMA foreign_keys = 0`): drop capture triggers → read `sqlite_sequence` → create
     * `new_<table>` → copy rows → drop and rename → recreate indices → restore the high-water
     * mark → `PRAGMA foreign_key_check`. Fails — and therefore fails the migration — when foreign
     * keys are on or the final check finds a violation.
     */
    suspend fun run(
        connection: SQLiteConnection,
        table: String,
        newTableSql: String,
        columnMap: Map<String, String>,
        indexSql: List<String> = emptyList(),
    ) {
        check(foreignKeysOff(connection)) {
            "TableRebuild requires PRAGMA foreign_keys = 0 (the migration connection)"
        }
        dropSyncCaptureTriggers(connection)

        val oldSeq = readSequence(connection, table)
        val newTable = "new_$table"
        val columns = columnMap.keys.joinToString(", ")
        val expressions = columnMap.values.joinToString(", ")

        exec(connection, newTableSql)
        exec(connection, "INSERT INTO $newTable ($columns) SELECT $expressions FROM $table")
        exec(connection, "DROP TABLE $table")
        exec(connection, "ALTER TABLE $newTable RENAME TO $table")
        indexSql.forEach { exec(connection, it) }

        if (oldSeq != null) restoreSequence(connection, table, oldSeq)
        check(foreignKeyCheckEmpty(connection)) {
            "PRAGMA foreign_key_check failed after rebuilding $table"
        }
    }

    private suspend fun foreignKeysOff(connection: SQLiteConnection): Boolean {
        connection.prepare("PRAGMA foreign_keys").use { stmt ->
            return stmt.step() && stmt.getLong(0) == 0L
        }
    }

    /**
     * M1a has no `SyncTriggers` class (MS0), but step 2 of the procedure must still remove any
     * `sync_cap_*` trigger — its body can name the dropped table and block the rename.
     */
    private suspend fun dropSyncCaptureTriggers(connection: SQLiteConnection) {
        val names = mutableListOf<String>()
        connection
            .prepare("SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'sync_cap_%'")
            .use { stmt ->
                while (stmt.step()) names += stmt.getText(0)
            }
        names.forEach { exec(connection, "DROP TRIGGER IF EXISTS $it") }
    }

    private suspend fun readSequence(
        connection: SQLiteConnection,
        table: String,
    ): Long? {
        connection.prepare("SELECT seq FROM sqlite_sequence WHERE name = ?").use { stmt ->
            stmt.bindText(1, table)
            return if (stmt.step()) stmt.getLong(0) else null
        }
    }

    private suspend fun restoreSequence(
        connection: SQLiteConnection,
        table: String,
        oldSeq: Long,
    ) {
        exec(connection, "UPDATE sqlite_sequence SET seq = MAX(seq, $oldSeq) WHERE name = '$table'")
        if (changes(connection) != 0) return
        // `sqlite_sequence` has no unique constraint — never INSERT OR REPLACE (02).
        exec(connection, "INSERT INTO sqlite_sequence(name, seq) VALUES ('$table', $oldSeq)")
    }

    private suspend fun changes(connection: SQLiteConnection): Int {
        connection.prepare("SELECT changes()").use { stmt ->
            return if (stmt.step()) stmt.getInt(0) else 0
        }
    }

    private suspend fun foreignKeyCheckEmpty(connection: SQLiteConnection): Boolean {
        connection.prepare("PRAGMA foreign_key_check").use { stmt ->
            return !stmt.step()
        }
    }

    private suspend fun exec(
        connection: SQLiteConnection,
        sql: String,
    ) {
        connection.prepare(sql).use { stmt -> stmt.step() }
    }
}
