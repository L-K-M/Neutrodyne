// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import ch.lkmc.neutrodyne.core.common.LinuxDesktopPortal
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.coroutines.resume

/**
 * The desktop [PlatformActions] (08 Modules). `NeutrodyneWindow` calls this with the
 * `LinuxDesktopPortal` bound in `DesktopAppGraph` (null off Linux) so the Linux file chooser and
 * "Show in Files" use the D-Bus portal without `:core:ui` depending on dbus-java. URLs go through
 * `java.awt.Desktop`; file dialogs use the OS dialogs (`FileDialog` on macOS, `JFileChooser` on
 * Windows, the portal on Linux where available — 11 Desktop downloads and storage).
 *
 * M0a simplification recorded for S9: while the portal's file-open call is not yet needed (imports
 * arrive in M5), [FilePicker.pickFile] uses `FileDialog` on every desktop OS, and [RevealInFolder]
 * falls back to opening the parent folder where `ShowItems` is unavailable.
 */
@Composable
public fun rememberDesktopPlatformActions(portal: LinuxDesktopPortal? = null): PlatformActions {
    val scope = rememberCoroutineScope()
    return remember(portal, scope) { DesktopPlatformActions(portal, scope) }
}

private class DesktopPlatformActions(
    private val portal: LinuxDesktopPortal?,
    private val scope: kotlinx.coroutines.CoroutineScope,
) : PlatformActions {
    override val urls: ExternalUrlOpener =
        ExternalUrlOpener { url ->
            try {
                val desktop = Desktop.getDesktop()
                val uri = URI(url)
                when {
                    url.startsWith("mailto:") && desktop.isSupported(Desktop.Action.MAIL) -> {
                        desktop.mail(uri)
                    }

                    desktop.isSupported(Desktop.Action.BROWSE) -> {
                        desktop.browse(uri)
                    }

                    else -> {
                        return@ExternalUrlOpener OpenResult.NO_HANDLER
                    }
                }
                OpenResult.OPENED
            } catch (e: Exception) {
                // Headless environments and unsupported actions both land here (e.g. IllegalArgumentException).
                OpenResult.NO_HANDLER
            }
        }

    // The desktop shows "Copy link" / "Show in folder" instead (08 Capability differences).
    override val share: ShareSheet? = null

    override val files: FilePicker =
        object : FilePicker {
            override suspend fun pickFile(
                mimeTypes: List<String>,
                extensions: List<String>,
            ): String? =
                suspendCancellableCoroutine { cont ->
                    SwingUtilities.invokeLater {
                        cont.resume(showFileDialog(extensions, directoriesOnly = false))
                    }
                }

            override suspend fun pickFolder(title: String): String? {
                portal?.let { p ->
                    val chosen = p.chooseDirectory(title, null)
                    if (chosen != null) return chosen
                }
                return suspendCancellableCoroutine { cont ->
                    SwingUtilities.invokeLater {
                        cont.resume(showFileDialog(emptyList(), directoriesOnly = true))
                    }
                }
            }
        }

    override val saver: FileSaver =
        FileSaver { suggestedName, mimeType ->
            suspendCancellableCoroutine { cont ->
                SwingUtilities.invokeLater {
                    cont.resume(showSaveDialog(suggestedName, mimeType))
                }
            }
        }

    override val reveal: RevealInFolder =
        RevealInFolder { path ->
            val file = localFile(path) ?: return@RevealInFolder false
            // Linux has the portal's ShowItems (selects the file); elsewhere open the parent folder.
            if (portal != null && isLinux()) {
                scope.launch { portal.showItems(file.absolutePath) }
                true
            } else {
                try {
                    val desktop = Desktop.getDesktop()
                    val parent = if (file.isDirectory) file else file.parentFile
                    if (parent != null && desktop.isSupported(Desktop.Action.OPEN)) {
                        desktop.open(parent)
                        true
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    false
                }
            }
        }

    // macOS asks at the first notification; there is no prompt of ours (08 Permission prompts).
    override val notifications: NotificationPermissionRequester? = null
}

private fun isLinux(): Boolean =
    System
        .getProperty("os.name")
        .orEmpty()
        .lowercase()
        .contains("linux")

/** OS file-open dialog; [extensions] without the dot filter the listing ("" = all files). */
private fun showFileDialog(
    extensions: List<String>,
    directoriesOnly: Boolean,
): String? {
    if (directoriesOnly && !isMacOs()) {
        // Windows/Linux Swing chooser in directories-only mode (11's per-OS split).
        val chooser = JFileChooser()
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
        return chooser.selectedFile?.absolutePath
    }
    val dialog = FileDialog(null as Frame?, null, FileDialog.LOAD)
    if (directoriesOnly) {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        dialog.mode = FileDialog.LOAD
    }
    if (extensions.isNotEmpty()) {
        dialog.filenameFilter =
            FilenameFilter { _, name ->
                extensions.any { name.endsWith(".$it", ignoreCase = true) }
            }
    }
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return File(dialog.directory, file).absolutePath
}

private fun isMacOs(): Boolean =
    System
        .getProperty("os.name")
        .orEmpty()
        .lowercase()
        .contains("mac")

private fun showSaveDialog(
    suggestedName: String,
    mimeType: String,
): String? {
    val dialog = FileDialog(null as Frame?, null, FileDialog.SAVE)
    dialog.file = suggestedName
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return savedFileUri(dialog.directory, file, mimeType)
}

/**
 * The deterministic half of [showSaveDialog] (05 Destinations): a chosen name without an
 * extension gets the type's default, and the result is the escaped local `file:` URI
 * `FileExportDestination` accepts — never a bare path, so `file:` and `content:` callers stay
 * distinguishable.
 */
internal fun savedFileUri(
    directory: String,
    chosenName: String,
    mimeType: String,
): String {
    val extension = defaultExtension(mimeType)
    val name =
        if (extension != null && '.' !in chosenName) {
            chosenName + extension
        } else {
            chosenName
        }
    return File(directory, name).toURI().toASCIIString()
}

/** The extension [FileSaver.create] appends to an extensionless name, per 05's export types. */
internal fun defaultExtension(mimeType: String): String? =
    when (mimeType) {
        "text/x-opml" -> ".opml"
        "application/json" -> ".json"
        "application/zip" -> ".zip"
        else -> null
    }

/** [path] may arrive as a raw path (pickers) or the saver's escaped `file:` URI. */
private fun localFile(path: String): File? =
    try {
        if (path.startsWith("file:")) File(URI(path)) else File(path)
    } catch (e: Exception) {
        null
    }
