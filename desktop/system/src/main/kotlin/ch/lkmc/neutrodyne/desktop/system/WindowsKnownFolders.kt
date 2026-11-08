// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.system

import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util

/**
 * The one known folder the Windows identity work needs — `FOLDERID_Programs`, the
 * per-user `Start Menu\Programs` — behind a seam so unit tests exercise the logic
 * off Windows (the same shape as `AppDirs`' `WindowsLocalAppData`, 11 Data
 * directories). The path comes from `SHGetKnownFolderPath`, never a guessed or
 * localized `Start Menu` literal.
 */
fun interface WindowsKnownFolders {
    /** The absolute path, or `null` when the OS cannot answer. */
    fun programsFolder(): String?
}

/** The JNA `SHGetKnownFolderPath` implementation; called only on Windows. */
object JnaWindowsKnownFolders : WindowsKnownFolders {
    override fun programsFolder(): String? =
        runCatching { Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Programs) }.getOrNull()
}
