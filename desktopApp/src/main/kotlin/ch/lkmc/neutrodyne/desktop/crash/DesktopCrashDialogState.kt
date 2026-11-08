// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.crash

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.CrashReporter
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.model.BuildInfo
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * The next-start crash offer's scan (11 Crash files and the email dialog): unsent
 * `crash-*.txt` files newest-first, bounded to 10 files within 30 days. The offer exists only
 * while [crashReporter] reports available — `InstallKind.DEV` and an empty mailbox never ask
 * (D62, PO-10), which is the reporter's own rule.
 */
class DesktopCrashDialogState(
    private val dirs: AppDirs,
    private val buildInfo: BuildInfo,
    private val clock: Clock,
    private val crashReporter: CrashReporter,
    private val showInFolder: (Path) -> Boolean = ::defaultShowInFolder,
    private val openMailto: (String) -> Boolean = ::defaultOpenMailto,
) {
    private val maxAgeMs = 30L * 24L * 60L * 60L * 1000L
    private val maxFiles = 10
    private val maxMailtoBody = 1800

    fun pendingCrashes(): List<PendingCrash> {
        if (!crashReporter.isAvailable) return emptyList()

        val stateDir = dirs.state
        if (!Files.exists(stateDir)) return emptyList()

        val cutoff = clock.now() - maxAgeMs
        val files =
            try {
                Files.list(stateDir).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) }
                        .filter { it.fileName.toString().endsWith(".txt") }
                        .filter { it.fileName.toString().startsWith("crash-") }
                        .filter { !it.fileName.toString().endsWith(".sent") }
                        .filter { !it.fileName.toString().endsWith(".dismissed") }
                        .filter { fileTime(it) >= cutoff }
                        .sorted(compareByDescending<Path> { fileTime(it) }.thenBy { it.fileName.toString() })
                        .toList()
                }
            } catch (e: IOException) {
                // An unreadable state directory must not take the start-up scan (and the EDT) down.
                Log.w(TAG) { "crash scan failed: ${e.javaClass.simpleName}" }
                return emptyList()
            }

        val pruned = files.drop(maxFiles)
        pruned.forEach { markDismissed(it) }

        return files.take(maxFiles).map { path ->
            val name = path.fileName.toString()
            PendingCrash(
                fileName = name,
                path = path,
                displayName = name,
            )
        }
    }

    fun showReport(pending: PendingCrash): Boolean = showInFolder(pending.path)

    /**
     * Opens the bounded `mailto:` and marks the file `.sent`. True only when the rename
     * succeeded, so the caller advances the offer only for a file no longer pending.
     */
    fun sendByEmail(pending: PendingCrash): Boolean {
        if (!openMailto(buildMailto(pending))) return false
        return markSent(pending.path)
    }

    fun dismiss(pending: PendingCrash): Boolean = markDismissed(pending.path)

    private fun buildMailto(pending: PendingCrash): String {
        val to = buildInfo.acraMailto
        val subject = "Neutrodyne crash ${buildInfo.versionName}"
        val content = readContent(pending.path)
        val body =
            if (content.length > maxMailtoBody) {
                val truncated = content.take(maxMailtoBody)
                "$truncated\n\n[truncated to $maxMailtoBody characters — attach the full file ${pending.fileName}]"
            } else {
                content
            }

        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
        return "mailto:$to?subject=${encode(subject)}&body=${encode(body)}"
    }

    private fun readContent(path: Path): String =
        try {
            Files.readString(path, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            ""
        }

    private fun markSent(path: Path): Boolean = renameHandled(path, ".sent")

    private fun markDismissed(path: Path): Boolean = renameHandled(path, ".dismissed")

    private fun renameHandled(
        path: Path,
        suffix: String,
    ): Boolean =
        try {
            val name = path.fileName.toString()
            val base = name.removeSuffix(".txt")
            val target = path.resolveSibling("$base$suffix")
            Files.move(path, target)
            true
        } catch (e: Exception) {
            false
        }

    private fun fileTime(path: Path): Long =
        try {
            Files.getLastModifiedTime(path).toMillis()
        } catch (e: Exception) {
            0L
        }

    companion object {
        private const val TAG = "Crash"

        fun defaultShowInFolder(path: Path): Boolean =
            try {
                val file = path.toFile()
                val desktop = Desktop.getDesktop()
                if (Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.OPEN)) {
                    // Try to open parent folder - some platforms support opening folder
                    val parent = file.parentFile
                    if (parent != null && Desktop.isDesktopSupported() &&
                        desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)
                    ) {
                        desktop.browseFileDirectory(file)
                        true
                    } else if (parent != null) {
                        desktop.open(parent)
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }

        fun defaultOpenMailto(mailto: String): Boolean =
            try {
                val desktop = Desktop.getDesktop()
                if (Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.MAIL)) {
                    desktop.mail(java.net.URI(mailto))
                    true
                } else if (Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(java.net.URI(mailto))
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }
    }
}

data class PendingCrash(
    val fileName: String,
    val path: Path,
    val displayName: String,
)
