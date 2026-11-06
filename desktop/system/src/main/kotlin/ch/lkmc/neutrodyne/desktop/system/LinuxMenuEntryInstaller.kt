// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.Log
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * "Add to applications menu" for tar.gz extractions (11 Links and files from the OS):
 * writes the per-user desktop entry `~/.local/share/applications/ch.lkmc.neutrodyne.desktop`
 * through the shared [LinuxDesktopEntryWriter] and the per-user hicolor icons it points
 * at, both strictly under [dataHome] (the XDG data home, never `/usr/share`), starting no
 * process — desktops pick the entry up themselves (01 DC4). Re-running it after the
 * archive moved rewrites entry and icons: stale paths are refreshed, never duplicated.
 *
 * [launcher] reads `jpackage.app-path` and [iconsSource] the packaged image's resources
 * directory (`desktop-integration/hicolor`); without either (a dev run) the state is
 * [LinuxMenuEntryState.UNSUPPORTED] and nothing is written.
 */
class LinuxMenuEntryInstaller(
    private val dataHome: Path,
    private val launcher: () -> String?,
    private val iconsSource: () -> Path?,
) {
    /** The Settings row's state: no launcher (dev) reads unsupported, not "absent". */
    fun status(): LinuxMenuEntryState {
        if (launcher() == null) return LinuxMenuEntryState.UNSUPPORTED
        return if (Files.isRegularFile(entryFile())) LinuxMenuEntryState.INSTALLED
        else LinuxMenuEntryState.NOT_INSTALLED
    }

    /** Writes entry and icons; `false` (logged) when the launcher or images are missing. */
    fun install(): Boolean {
        val path = launcher() ?: return fail("no launcher path (dev run without jpackage.app-path)")
        if (!LinuxDesktopEntryWriter.executablePathAllowed(path)) {
            return fail("the launcher path cannot go into a desktop entry")
        }
        val icons = copyIcons() ?: return fail("no packaged icons found to install")
        return try {
            Files.createDirectories(entryFile().parent)
            writeEntry(LinuxDesktopEntryWriter.menuEntry(path, icons.toString()))
            true
        } catch (e: IOException) {
            fail("cannot write ${entryFile()}: ${e.message}")
        }
    }

    private fun entryFile(): Path = dataHome.resolve("applications").resolve(LinuxDesktopEntryWriter.ENTRY_FILE_NAME)


    /**
     * Copies every packaged `hicolor/<size>x<size>/apps/neutrodyne.png` under
     * `<data>/icons/hicolor/` and returns the entry's icon: 256×256, or the largest
     * available when 256 is not — 11's example pins a 256 pixel icon.
     */
    private fun copyIcons(): Path? {
        val source = iconsSource()?.resolve(HICOLOR) ?: return null
        if (!Files.isDirectory(source)) return null
        val target = dataHome.resolve("icons").resolve(HICOLOR)
        return try {
            var chosen: Path? = null
            Files.list(source).use { sizes ->
                for (sizeDir in sizes.filter(Files::isDirectory)) {
                    val png = sizeDir.resolve("apps").resolve(ICON_NAME)
                    if (!Files.isRegularFile(png)) continue
                    val destination = target.resolve(sizeDir.fileName.toString()).resolve("apps").resolve(ICON_NAME)
                    Files.createDirectories(destination.parent)
                    Files.copy(png, destination, StandardCopyOption.REPLACE_EXISTING)
                    if (chosen == null || iconSize(png) > iconSize(chosen!!)) chosen = destination
                }
            }
            // Prefer 256 when present, else the largest copied.
            val preferred = target.resolve("256x256").resolve("apps").resolve(ICON_NAME)
            when {
                Files.isRegularFile(preferred) -> preferred

                chosen != null -> chosen

                else -> null
            }
        } catch (e: IOException) {
            fail("cannot copy icons under $target: ${e.message}")
            null
        }
    }

    /** `hicolor/256x256/...` → 256; 0 when the name is unexpected. */
    private fun iconSize(icon: Path): Int =
        icon.parent.parent.fileName.toString().substringBefore('x').toIntOrNull() ?: 0

    /** Same-directory temp file plus rename — a torn write never leaves half an entry. */
    private fun writeEntry(content: String) {
        val target = entryFile()
        val tmp = Files.createTempFile(target.parent, LinuxDesktopEntryWriter.ENTRY_FILE_NAME, ".tmp")
        try {
            Files.writeString(tmp, content)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun fail(reason: String): Boolean {
        Log.w(TAG) { "applications-menu install failed: $reason" }
        return false
    }

    private companion object {
        const val TAG = "MenuEntry"
        const val HICOLOR = "hicolor"
        const val ICON_NAME = "neutrodyne.png"
    }
}

/** The Settings › Desktop row over [LinuxMenuEntryInstaller]. */
enum class LinuxMenuEntryState {
    UNSUPPORTED,
    NOT_INSTALLED,
    INSTALLED,
}
