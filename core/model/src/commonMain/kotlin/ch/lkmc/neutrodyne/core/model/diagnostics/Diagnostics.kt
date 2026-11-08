// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.model.diagnostics

/**
 * One diagnostics report (09's contract): immutable sections of key/value lines the
 * Diagnostics screen renders as cards and `toPlainText` serializes for the clipboard.
 * Keys are English, values are already redacted — diagnostics end up in public issues.
 */
data class DiagnosticsReport(
    val generatedAt: Long,
    val sections: List<DiagnosticsSection>,
)

data class DiagnosticsSection(
    val id: DiagnosticsSectionId,
    val lines: List<DiagnosticsLine>,
)

data class DiagnosticsLine(
    val key: String,
    val value: String,
    val severity: DiagnosticsSeverity = DiagnosticsSeverity.INFO,
)

/**
 * Card order on the screen (08 Diagnostics); `LOG` stays last so `toPlainText`'s 64 KB
 * truncation spends its budget on the bounded sections first.
 */
enum class DiagnosticsSectionId {
    APP,
    REFRESH,
    BACKGROUND,
    JOBS,
    DOWNLOADS,
    YOUTUBE,
    SYNC,
    DESKTOP,
    DATABASE,
    NOTIFICATIONS,
    PARSE_WARNINGS,
    LOG,
}

enum class DiagnosticsSeverity {
    INFO,
    WARNING,
    PROBLEM,
}

/**
 * The DESKTOP log-directory row's key. Its value is an absolute path under the user's home,
 * so the issue pre-fill omits the row (09's redaction surface) while the local report keeps it.
 */
const val DESKTOP_LOG_DIR_KEY = "logDir"
