// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import java.io.File

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
        val dir = File(quarantineDir, stamp)
        dir.mkdirs()
        for (suffix in SIDE_FILES) {
            File(databasePath + suffix).renameTo(File(dir, NeutrodyneDatabase.FILE_NAME + suffix))
        }
    }

    override fun pruneQuarantine(now: Long) {
        val dirs = quarantineDir.listFiles()?.filter { it.isDirectory } ?: return
        val keep =
            dirs
                .filter { isFresh(it, now) }
                .sortedByDescending { it.name }
                .take(1)
                .toSet()
        dirs.filter { it !in keep }.forEach { it.deleteRecursively() }
    }

    override var quarantineMarker: Boolean
        get() = markerFile.exists()
        set(value) {
            if (value) {
                markerFile.parentFile?.mkdirs()
                markerFile.createNewFile()
            } else {
                markerFile.delete()
            }
        }

    private val quarantineDir: File
        get() = File(appContext.getDatabasePath(NeutrodyneDatabase.FILE_NAME).parentFile, QUARANTINE_DIR)

    private val markerFile: File
        get() = File(appContext.getDatabasePath(NeutrodyneDatabase.FILE_NAME).parentFile, MARKER_FILE)

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

        /** 02: quarantine keeps only the newest copy, at most 14 days. */
        const val QUARANTINE_KEEP_MS = 14L * 24 * 60 * 60 * 1000
        val SIDE_FILES = listOf("", "-wal", "-shm")
    }
}
