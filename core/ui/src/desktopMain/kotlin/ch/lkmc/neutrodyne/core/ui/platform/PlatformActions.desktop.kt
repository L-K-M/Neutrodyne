// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import ch.lkmc.neutrodyne.core.common.LinuxDesktopPortal
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.Component
import java.awt.Desktop
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import java.net.URI
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The desktop [PlatformActions] (08 Modules). `NeutrodyneWindow` calls this with the
 * `LinuxDesktopPortal` bound in `DesktopAppGraph` (null off Linux) so the Linux file chooser and
 * "Show in Files" use the D-Bus portal without `:core:ui` depending on dbus-java. URLs go through
 * `java.awt.Desktop`; file dialogs use the OS dialogs (`FileDialog` on macOS, `JFileChooser` on
 * Windows, the portal on Linux where available — 11 Desktop downloads and storage).
 *
 * File-open uses `FileDialog` on every desktop OS; [FilePicker.openFile] supplies no filter
 * so providers without an OPML mapping still show every supported import format.
 * [RevealInFolder] opens the parent folder where `ShowItems` is unavailable.
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
            ): String? {
                val pending = PendingDialog()
                return awaitEdt(onCancel = { pending.dismiss() }) {
                    showFileDialog(pending, extensions, directoriesOnly = false)
                }
            }

            override suspend fun pickFolder(title: String): String? {
                portal?.let { p ->
                    val chosen = p.chooseDirectory(title, null)
                    if (chosen != null) return chosen
                }
                val pending = PendingDialog()
                return awaitEdt(onCancel = { pending.dismiss() }) {
                    showFileDialog(pending, emptyList(), directoriesOnly = true)
                }
            }
        }

    override val saver: FileSaver =
        FileSaver { suggestedName, mimeType ->
            val pending = PendingDialog()
            awaitEdt(onCancel = { pending.dismiss() }) {
                showSaveDialog(pending, suggestedName, mimeType)
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

/**
 * Runs [block] on the Swing EDT and suspends for its result. A throw inside [block] resumes the
 * caller exceptionally instead of dying on the EDT and stranding the continuation forever.
 * Cancelling the caller skips a still-queued block; when the block is already inside a modal
 * dialog, [onCancel] runs on the EDT (a modal dialog pumps queued runnables) to dismiss it — a
 * dead composition never leaves a dialog up or a continuation hanging.
 */
internal suspend fun <T> awaitEdt(
    onCancel: () -> Unit = {},
    block: () -> T,
): T =
    suspendCancellableCoroutine { cont ->
        SwingUtilities.invokeLater {
            if (cont.isActive) {
                try {
                    val result = block()
                    if (cont.isActive) cont.resume(result)
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }
        cont.invokeOnCancellation {
            SwingUtilities.invokeLater { onCancel() }
        }
    }

/** The dialog an EDT block is about to open, so a cancelled caller can still dismiss it there. */
private class PendingDialog {
    var dialog: Dialog? = null

    fun dismiss() {
        dialog?.dispose()
    }
}

private fun isLinux(): Boolean =
    System
        .getProperty("os.name")
        .orEmpty()
        .lowercase()
        .contains("linux")

/** OS file-open dialog; [extensions] without the dot filter the listing ("" = all files). */
private fun showFileDialog(
    pending: PendingDialog,
    extensions: List<String>,
    directoriesOnly: Boolean,
): String? {
    if (directoriesOnly && !isMacOs()) {
        // Windows/Linux Swing chooser in directories-only mode (11's per-OS split). The chooser
        // builds its dialog internally, so it is captured through createDialog for dismissal.
        val chooser =
            object : JFileChooser() {
                override fun createDialog(parent: Component?): JDialog =
                    super.createDialog(parent).also { pending.dialog = it }
            }
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
        return chooser.selectedFile?.absolutePath
    }
    val mode = if (directoriesOnly) FileDialogMode.DIRECTORY else FileDialogMode.FILE
    return withFileDialogMode(mode) {
        val dialog = FileDialog(null as Frame?, null, FileDialog.LOAD)
        pending.dialog = dialog
        if (extensions.isNotEmpty()) {
            dialog.filenameFilter =
                FilenameFilter { _, name ->
                    extensions.any { name.endsWith(".$it", ignoreCase = true) }
                }
        }
        try {
            dialog.isVisible = true
            val file = dialog.file ?: return@withFileDialogMode null
            if (directoriesOnly) File(dialog.directory, file).absolutePath else openedFileUri(dialog.directory, file)
        } finally {
            pending.dialog = null
            dialog.dispose()
        }
    }
}

private fun isMacOs(): Boolean =
    System
        .getProperty("os.name")
        .orEmpty()
        .lowercase()
        .contains("mac")

private fun showSaveDialog(
    pending: PendingDialog,
    suggestedName: String,
    mimeType: String,
): String? {
    val dialog = FileDialog(null as Frame?, null, FileDialog.SAVE)
    pending.dialog = dialog
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

internal fun openedFileUri(
    directory: String,
    chosenName: String,
): String = File(directory, chosenName).toPath().toUri().toASCIIString()

internal enum class FileDialogMode { FILE, DIRECTORY }

// A folder chooser must not leave subsequent file dialogs in directories-only mode.
internal fun <T> withFileDialogMode(
    mode: FileDialogMode,
    block: () -> T,
): T {
    val key = "apple.awt.fileDialogForDirectories"
    val previous = System.getProperty(key)
    System.setProperty(key, (mode == FileDialogMode.DIRECTORY).toString())
    try {
        return block()
    } finally {
        if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
    }
}
