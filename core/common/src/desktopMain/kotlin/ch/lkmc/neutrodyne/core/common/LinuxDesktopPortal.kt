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
     * dialog title, [start] an optional initial directory. The outcome distinguishes the three
     * cases 11's folder-chooser table needs: a picked folder, the user's dismissal (no dialog
     * may open afterwards), and no portal answering (the caller falls back to `JFileChooser`).
     */
    suspend fun chooseDirectory(
        title: String,
        start: String?,
    ): DirectoryChoice
}

/** The outcome of [LinuxDesktopPortal.chooseDirectory]. */
sealed interface DirectoryChoice {
    /** The user picked [path]. */
    data class Chosen(
        val path: String,
    ) : DirectoryChoice

    /** The portal answered, but the user dismissed the dialog; nothing else may open. */
    data object Cancelled : DirectoryChoice

    /** No portal answered (no service, no bus, transport error) — callers use their fallback. */
    data object Unavailable : DirectoryChoice
}
