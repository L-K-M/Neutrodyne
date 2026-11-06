// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.diagnostics

import ch.lkmc.neutrodyne.core.common.AppDirs
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.data.refresh.DesktopJobRunner
import ch.lkmc.neutrodyne.core.domain.DiagnosticsContributor
import ch.lkmc.neutrodyne.core.domain.YouTubeEngineDiagnostics
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsLine
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSectionId
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSeverity
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject

/**
 * The desktop DESKTOP card (11 Runner diagnostics): the log directory, one row per runner
 * lane (`LaneStatus`), the last detected wake and the engine child status.
 */
@ContributesIntoSet(AppScope::class)
internal class DesktopDiagnosticsContributor
    @Inject
    constructor(
        private val dirs: AppDirs,
        private val runner: DesktopJobRunner,
        private val engine: YouTubeEngineDiagnostics,
    ) : DiagnosticsContributor {
        override val section = DiagnosticsSectionId.DESKTOP

        override suspend fun collect(): List<DiagnosticsLine> {
            val lines = mutableListOf(DiagnosticsLine("logDir", dirs.logs.toString()))
            val wake = runner.lastWakeAt.value
            lines += DiagnosticsLine("lastWakeAt", wake?.toEpochMilliseconds()?.toString() ?: "none yet")
            lines += DiagnosticsLine("engineChild", engine.childStatus() ?: "not running")
            for ((name, status) in runner.status.value) {
                lines +=
                    DiagnosticsLine(
                        "lane.$name",
                        laneSummary(status),
                        if (status.lastError != null) DiagnosticsSeverity.PROBLEM else DiagnosticsSeverity.INFO,
                    )
            }
            return lines
        }

        private fun laneSummary(status: ch.lkmc.neutrodyne.core.data.refresh.LaneStatus): String =
            buildString {
                append(if (status.running) "running" else "idle")
                append(" runs=").append(status.runs)
                if (status.failures > 0) append(" failures=").append(status.failures)
                status.lastStartAt?.let { append(" lastStart=").append(it.toEpochMilliseconds()) }
                status.lastEndAt?.let { append(" lastEnd=").append(it.toEpochMilliseconds()) }
                status.lastError?.let { append(" error=").append(it) }
                status.backoffUntil?.let { append(" backoffUntil=").append(it.toEpochMilliseconds()) }
            }
    }
