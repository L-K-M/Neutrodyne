// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import ch.lkmc.neutrodyne.core.common.LoginItemException
import ch.lkmc.neutrodyne.core.common.LoginItemRegistrar
import ch.lkmc.neutrodyne.core.model.LoginItemState
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 11's Linux start at login: `$XDG_CONFIG_HOME/autostart/ch.lkmc.neutrodyne.desktop` written
 * through the shared [LinuxDesktopEntryWriter], so `Exec`/`TryExec` quoting matches the
 * applications-menu entry. `TryExec` keeps desktops skipping the entry once the app is
 * uninstalled.
 */
class XdgAutostartRegistrar(
    private val autostartDir: Path,
    private val launcher: String?,
) : LoginItemRegistrar {
    private val entryFile: Path
        get() = autostartDir.resolve(ENTRY_NAME)

    override fun status(): LoginItemState =
        when {
            Files.isRegularFile(entryFile) -> LoginItemState.REGISTERED
            launcher == null -> LoginItemState.UNSUPPORTED
            else -> LoginItemState.NOT_REGISTERED
        }

    override fun register() {
        val path = launcher ?: throw LoginItemException("no launcher path (dev run without jpackage.app-path)")
        if (!LinuxDesktopEntryWriter.executablePathAllowed(path)) {
            throw LoginItemException("the launcher path cannot go into a desktop entry")
        }
        try {
            Files.createDirectories(autostartDir)
            writeEntry(LinuxDesktopEntryWriter.autostartEntry(path))
        } catch (e: IOException) {
            throw LoginItemException("cannot write $entryFile: ${e.message}")
        }
    }

    override fun unregister() {
        try {
            Files.deleteIfExists(entryFile)
        } catch (e: IOException) {
            throw LoginItemException("cannot remove $entryFile: ${e.message}")
        }
    }

    /** Same-directory temp file plus rename — a torn write never leaves half an entry. */
    private fun writeEntry(content: String) {
        val tmp = Files.createTempFile(autostartDir, ENTRY_NAME, ".tmp")
        try {
            Files.writeString(tmp, content)
            moveIntoPlace(tmp)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun moveIntoPlace(tmp: Path) {
        try {
            Files.move(tmp, entryFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, entryFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val ENTRY_NAME = "ch.lkmc.neutrodyne.desktop"
    }
}
