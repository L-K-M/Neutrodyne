// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.RoomDatabase

/**
 * Platform file handling for [NeutrodyneDatabase] (02 Database builder and connections): the
 * database path, the Room builder, the quarantine directory and the quarantine marker.
 * The opener uses this boundary for platform file handling.
 */
interface DatabaseFactory {
    /** Absolute path of `neutrodyne.db`. */
    val databasePath: String

    fun builder(): RoomDatabase.Builder<NeutrodyneDatabase>

    fun exists(): Boolean

    /** Moves `neutrodyne.db`, `-wal` and `-shm` into `<quarantine>/<stamp>/`. */
    fun quarantine(stamp: String)

    /** Keeps only the newest quarantined copy, at most 14 days; never the [pendingQuarantine] destination. */
    fun pruneQuarantine(now: Long)

    /** File `quarantine-requested` beside the database; clearing must fail loudly, never silently. */
    var quarantineMarker: Boolean

    /**
     * File `quarantine-pending` beside the database holding the stamp of an in-flight
     * quarantine: written before the first move so a restart finishes the moves into the same
     * destination, cleared once the quarantine and its marker are done. Reads return `null`
     * when the file is absent or empty; writes and removals are verified (they throw on
     * failure).
     */
    var pendingQuarantine: String?
}
