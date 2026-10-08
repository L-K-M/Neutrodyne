// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import ch.lkmc.neutrodyne.core.common.Clock

/**
 * The open callbacks of 02 Database builder and connections. [onCreate] inserts the two singleton
 * rows (`play_session`, `sync_state`; the `sync_cap_*` capture triggers arrive with MS0's
 * `SyncTriggers.create`). [onOpen] runs `PRAGMA optimize` (`=0x10002` under the bundled driver —
 * [optimizeMask] — plain under the framework driver) and defensively resets `sync_state.applying`.
 * Callbacks touch only the [connection] they receive — never the database instance (02 Opening).
 */
class NeutrodyneDatabaseCallback(
    private val clock: Clock,
    private val optimizeMask: Boolean,
) : RoomDatabase.Callback() {
    /** Set by [onCreate] — the `created` bit of `OpenResult`. */
    @Volatile
    var created: Boolean = false
        private set

    override suspend fun onCreate(connection: SQLiteConnection) {
        created = true
        exec(connection, "INSERT OR IGNORE INTO play_session(id, generation, updatedAt) VALUES (0, 0, ?)") {
            bindLong(1, clock.now())
        }
        exec(connection, "INSERT OR IGNORE INTO sync_state(id) VALUES (0)")
    }

    override suspend fun onOpen(connection: SQLiteConnection) {
        exec(connection, if (optimizeMask) "PRAGMA optimize=0x10002" else "PRAGMA optimize")
        exec(connection, "UPDATE sync_state SET applying = 0 WHERE id = 0 AND applying <> 0")
    }

    private suspend fun exec(
        connection: SQLiteConnection,
        sql: String,
        bind: SQLiteStatement.() -> Unit = {},
    ) {
        connection.prepare(sql).use { stmt ->
            stmt.bind()
            stmt.step()
        }
    }
}
