// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.database

import androidx.room3.Room
import androidx.room3.RoomDatabase
import ch.lkmc.neutrodyne.core.common.AppDirs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * `<data>/neutrodyne.db` in 11's `AppDirs` (mode 0700 on macOS and Linux); the quarantine
 * directory is `<data>/quarantine/` and the marker `<data>/quarantine-requested`
 * (02 Database builder and connections).
 */
class DesktopDatabaseFactory(
    private val dirs: AppDirs,
) : DatabaseFactory {
    private val file: Path
        get() = dirs.data.resolve(NeutrodyneDatabase.FILE_NAME)

    override val databasePath: String
        get() = file.toString()

    override fun builder(): RoomDatabase.Builder<NeutrodyneDatabase> = Room.databaseBuilder(name = databasePath)

    override fun exists(): Boolean = Files.exists(file)

    override fun quarantine(stamp: String) {
        val dir = quarantineDir.resolve(stamp)
        Files.createDirectories(dir)
        for (suffix in SIDE_FILES) {
            val source = Path.of(databasePath + suffix)
            if (Files.exists(source)) {
                Files.move(
                    source,
                    dir.resolve(NeutrodyneDatabase.FILE_NAME + suffix),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
    }

    override fun pruneQuarantine(now: Long) {
        if (!Files.isDirectory(quarantineDir)) return
        val dirsToCheck = children(quarantineDir).filter { Files.isDirectory(it) }
        val keep =
            dirsToCheck
                .filter { isFresh(it, now) }
                .sortedByDescending { it.fileName.toString() }
                .take(1)
                .toSet()
        dirsToCheck.filter { it !in keep }.forEach { it.toFile().deleteRecursively() }
    }

    override var quarantineMarker: Boolean
        get() = Files.exists(markerFile)
        set(value) {
            if (value) {
                Files.createDirectories(markerFile.parent)
                Files.createFile(markerFile)
            } else {
                Files.deleteIfExists(markerFile)
            }
        }

    private val quarantineDir: Path
        get() = dirs.data.resolve(QUARANTINE_DIR)

    private val markerFile: Path
        get() = dirs.data.resolve(MARKER_FILE)

    /** The stamp is the quarantine's epoch-millis name; fall back to the newest child's mtime. */
    private fun isFresh(
        dir: Path,
        now: Long,
    ): Boolean {
        val stamp = dir.fileName.toString().toLongOrNull()
        if (stamp != null) return now - stamp <= QUARANTINE_KEEP_MS
        val children = children(dir)
        return children.any { Files.getLastModifiedTime(it).toMillis() >= now - QUARANTINE_KEEP_MS }
    }

    private fun children(dir: Path): List<Path> = Files.list(dir).use { stream -> stream.toList() }

    private companion object {
        const val QUARANTINE_DIR = "quarantine"
        const val MARKER_FILE = "quarantine-requested"

        /** 02: quarantine keeps only the newest copy, at most 14 days. */
        const val QUARANTINE_KEEP_MS = 14L * 24 * 60 * 60 * 1000
        val SIDE_FILES = listOf("", "-wal", "-shm")
    }
}
