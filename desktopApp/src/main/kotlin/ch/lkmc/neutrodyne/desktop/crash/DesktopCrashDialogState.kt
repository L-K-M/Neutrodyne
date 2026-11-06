// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.desktop.crash

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.model.InstallKind
import java.awt.Desktop
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class DesktopCrashDialogState(
    private val dirs: AppDirs,
    private val buildInfo: BuildInfo,
    private val clock: Clock,
    private val showInFolder: (Path) -> Boolean = ::defaultShowInFolder,
    private val openMailto: (String) -> Boolean = ::defaultOpenMailto,
) {
    private val maxAgeMs = 30L * 24L * 60L * 60L * 1000L
    private val maxFiles = 10
    private val maxMailtoBody = 1800

    fun pendingCrashes(): List<PendingCrash> {
        val desktop = buildInfo.desktop
        if (desktop?.installKind == InstallKind.DEV) return emptyList()
        if (buildInfo.acraMailto.isBlank()) return emptyList()
        if (!crashReporterAvailable()) return emptyList()

        val stateDir = dirs.state
        if (!Files.exists(stateDir)) return emptyList()

        val cutoff = clock.now() - maxAgeMs
        val files =
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

    fun sendByEmail(pending: PendingCrash): Boolean {
        val mailto = buildMailto(pending)
        val result = openMailto(mailto)
        if (result) {
            markSent(pending.path)
        }
        return result
    }

    fun dismiss(pending: PendingCrash): Boolean = markDismissed(pending.path)

    private fun crashReporterAvailable(): Boolean {
        // Delegate availability check to BuildInfo rules - same logic as DesktopCrashReporter
        val desktop = buildInfo.desktop
        if (desktop?.installKind == InstallKind.DEV) return false
        return buildInfo.acraMailto.isNotBlank()
    }

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
        val encodedTo = URLEncoder.encode(to, StandardCharsets.UTF_8).replace("+", "%20")
        val encodedSubject = URLEncoder.encode(subject, StandardCharsets.UTF_8).replace("+", "%20")
        val encodedBody = URLEncoder.encode(body, StandardCharsets.UTF_8).replace("+", "%20")
        return "mailto:$to?subject=${URLEncoder.encode(
            subject,
            StandardCharsets.UTF_8,
        ).replace("+", "%20")}&body=${URLEncoder.encode(body, StandardCharsets.UTF_8).replace("+", "%20")}"
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
