// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.RoomDatabase

/**
 * Platform file handling for [NeutrodyneDatabase] (02 Database builder and connections): the
 * database path, the Room builder, the quarantine directory and the quarantine marker.
 * [DatabaseOpener] stays common.
 */
interface DatabaseFactory {
    /** Absolute path of `neutrodyne.db`. */
    val databasePath: String

    fun builder(): RoomDatabase.Builder<NeutrodyneDatabase>

    fun exists(): Boolean

    /** Moves `neutrodyne.db`, `-wal` and `-shm` into `<quarantine>/<stamp>/`. */
    fun quarantine(stamp: String)

    /** Keeps only the newest quarantined copy, at most 14 days. */
    fun pruneQuarantine(now: Long)

    /** File `quarantine-requested` beside the database. */
    var quarantineMarker: Boolean
}
