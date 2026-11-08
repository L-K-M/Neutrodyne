// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.DiagnosticsError
import ch.lkmc.neutrodyne.core.domain.DiagnosticsRepository
import ch.lkmc.neutrodyne.core.domain.YouTubeEngineDiagnostics
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** ViewModel tests' `DiagnosticsRepository` — scripted snapshot, recorded export calls. */
class FakeDiagnosticsRepository(
    var report: DiagnosticsReport = DiagnosticsReport(generatedAt = 0, sections = emptyList()),
) : DiagnosticsRepository {
    var exportResult: Outcome<String, DiagnosticsError> = Outcome.Success("file:///tmp/export.db")

    /** When set, [exportDatabaseCopy] throws this instead of answering. */
    var exportThrows: Throwable? = null
    val exportCalls = mutableListOf<String?>()

    /** When set, [snapshot] throws it — the "no report yet" path for ViewModel tests. */
    var snapshotError: Throwable? = null

    private val _logLines = MutableStateFlow<List<String>>(emptyList())

    var logLines: List<String>
        get() = _logLines.value
        set(value) {
            _logLines.value = value
        }

    override suspend fun snapshot(): DiagnosticsReport {
        snapshotError?.let { throw it }
        return report
    }

    override fun toPlainText(
        report: DiagnosticsReport,
        maxBytes: Int,
    ): String =
        report.sections.joinToString("\n") { section ->
            "== ${section.id.name} ==\n" +
                section.lines.joinToString("\n") { "${it.key}: ${it.value}" }
        }

    override suspend fun exportDatabaseCopy(destination: String?): Outcome<String, DiagnosticsError> {
        exportCalls += destination
        exportThrows?.let { throw it }
        return exportResult
    }

    override fun observeLogLines(): Flow<List<String>> = _logLines.asStateFlow()
}

/** ViewModel/contributor tests' engine state (09's YOUTUBE card). */
class FakeYouTubeEngineDiagnostics : YouTubeEngineDiagnostics {
    var version: String? = null
    var healthStatus: String = "unavailable"
    var child: String? = null

    override suspend fun installedVersion(): String? = version

    override suspend fun health(): String = healthStatus

    override suspend fun childStatus(): String? = child
}
