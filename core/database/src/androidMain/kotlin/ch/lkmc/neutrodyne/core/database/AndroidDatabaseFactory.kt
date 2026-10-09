// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import java.io.File
import java.io.IOException

/**
 * `databases/` in credential-encrypted storage (02 Database builder and connections); the
 * quarantine directory is `databases/quarantine/` and the marker file
 * `databases/quarantine-requested`.
 */
class AndroidDatabaseFactory(
    context: Context,
) : DatabaseFactory {
    private val appContext = context.applicationContext

    override val databasePath: String
        get() = appContext.getDatabasePath(NeutrodyneDatabase.FILE_NAME).absolutePath

    override fun builder(): RoomDatabase.Builder<NeutrodyneDatabase> =
        Room.databaseBuilder(context = appContext, name = databasePath)

    override fun exists(): Boolean = File(databasePath).exists()

    override fun quarantine(stamp: String) {
        // The stamp becomes a directory name and is read back from `quarantine-pending`;
        // an epoch-millis shape keeps it inside the quarantine directory.
        require(stamp.toLongOrNull() != null) { "quarantine stamp must be epoch millis: $stamp" }
        val dir = File(quarantineDir, stamp)
        check(dir.isDirectory || dir.mkdirs()) { "cannot create quarantine directory $dir" }
        // Sidecars first, the main file last: a mid-sequence failure leaves the main file in
        // place, so the retried move can complete instead of opening next to orphaned -wal/-shm.
        // `renameTo` reports failure only through its return value — check it.
        for (suffix in SIDE_FILES) {
            val source = File(databasePath + suffix)
            if (source.exists() && !source.renameTo(File(dir, NeutrodyneDatabase.FILE_NAME + suffix))) {
                throw IOException("quarantine move failed: $source")
            }
        }
    }

    override fun pruneQuarantine(now: Long) {
        // A pending destination still awaits the rest of its files — never prune it.
        val pending = pendingQuarantine
        val dirs = quarantineDir.listFiles()?.filter { it.isDirectory && it.name != pending } ?: return
        // Numeric order, not lexicographic ("abc" sorts above "1700000000000"): the stamp is
        // the quarantine's epoch-millis name, with mtime as the fallback for other names.
        val keep =
            dirs
                .filter { isFresh(it, now) }
                .sortedByDescending { it.name.toLongOrNull() ?: it.lastModified() }
                .take(1)
                .toSet()
        dirs.filter { it !in keep }.forEach { it.deleteRecursively() }
    }

    // File.delete() reports failure only through its return value — check it: a surviving
    // marker or pending stamp must propagate so the next launch resumes the same quarantine
    // instead of re-quarantining a healthy replacement.
    override var quarantineMarker: Boolean
        get() = markerFile.exists()
        set(value) {
            if (value) {
                markerFile.parentFile?.mkdirs()
                markerFile.createNewFile()
            } else if (markerFile.exists() && !markerFile.delete()) {
                throw IOException("cannot delete $markerFile")
            }
        }

    override var pendingQuarantine: String?
        get() =
            pendingFile
                .takeIf { it.isFile }
                ?.readText()
                ?.trim()
                ?.ifEmpty { null }
        set(value) {
            if (value == null) {
                if (pendingFile.exists() && !pendingFile.delete()) {
                    throw IOException("cannot delete $pendingFile")
                }
            } else {
                pendingFile.parentFile?.mkdirs()
                pendingFile.writeText(value)
            }
        }

    private val quarantineDir: File
        get() = File(appContext.getDatabasePath(NeutrodyneDatabase.FILE_NAME).parentFile, QUARANTINE_DIR)

    private val markerFile: File
        get() = File(appContext.getDatabasePath(NeutrodyneDatabase.FILE_NAME).parentFile, MARKER_FILE)

    private val pendingFile: File
        get() = File(appContext.getDatabasePath(NeutrodyneDatabase.FILE_NAME).parentFile, PENDING_FILE)

    /** The stamp is the quarantine's epoch-millis name; fall back to the newest child's mtime. */
    private fun isFresh(
        dir: File,
        now: Long,
    ): Boolean {
        val stamp = dir.name.toLongOrNull()
        if (stamp != null) return now - stamp <= QUARANTINE_KEEP_MS
        return dir.listFiles()?.any { now - it.lastModified() <= QUARANTINE_KEEP_MS } == true
    }

    private companion object {
        const val QUARANTINE_DIR = "quarantine"
        const val MARKER_FILE = "quarantine-requested"
        const val PENDING_FILE = "quarantine-pending"

        /** 02: quarantine keeps only the newest copy, at most 14 days. */
        const val QUARANTINE_KEEP_MS = 14L * 24 * 60 * 60 * 1000
        val SIDE_FILES = listOf("-wal", "-shm", "")
    }
}
