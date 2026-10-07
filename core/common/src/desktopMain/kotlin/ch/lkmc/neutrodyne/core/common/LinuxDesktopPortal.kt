// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.common

/**
 * The D-Bus portal port (11 Runner contract): implemented by `DbusDesktopPortal` in
 * `:desktop:system` (dbus-java) and bound `null` off Linux. Shared modules (e.g. `:core:ui`'s
 * `RevealInFolder`) use it so they never touch D-Bus themselves.
 */
interface LinuxDesktopPortal {
    /** `org.freedesktop.FileManager1.ShowItems` — reveals [file] in the OS file manager. */
    suspend fun showItems(file: String): Boolean

    /**
     * Portal `org.freedesktop.portal.FileChooser.OpenFile` with `directory: true`; [title] is the
     * dialog title, [start] an optional initial directory. `null` = no portal answered.
     */
    suspend fun chooseDirectory(
        title: String,
        start: String?,
    ): String?
}
