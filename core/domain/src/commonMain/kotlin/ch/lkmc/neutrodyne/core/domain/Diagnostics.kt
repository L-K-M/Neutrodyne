// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsLine
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsReport
import ch.lkmc.neutrodyne.core.model.diagnostics.DiagnosticsSectionId
import kotlinx.coroutines.flow.Flow

/**
 * One module's contribution to the diagnostics report (09's contract): implementations
 * `@ContributesIntoSet` from `:core:data`, `:download:impl`, `:youtube:*`, `:sync:impl`,
 * the desktop system module and both shells, so no implementation module depends on
 * another. `collect` runs on IO with a 2 s budget; a throwing contributor degrades to one
 * "unavailable" line instead of failing the report.
 */
interface DiagnosticsContributor {
    val section: DiagnosticsSectionId

    suspend fun collect(): List<DiagnosticsLine>
}

/**
 * The Diagnostics screen's repository (09), implemented by `DiagnosticsRepositoryImpl`
 * in `:core:data`.
 */
interface DiagnosticsRepository {
    /** Collects every contributor concurrently; the report itself never throws. */
    suspend fun snapshot(): DiagnosticsReport

    /** Section order, log last, truncated to [maxBytes] from the log's oldest lines. */
    fun toPlainText(
        report: DiagnosticsReport,
        maxBytes: Int = 64 * 1024,
    ): String

    /**
     * Runs 02's diagnostics export (`VACUUM INTO` + `DiagExportScrub` + `VACUUM`). On
     * Android the scrubbed copy is produced in the shared `cache/export/` and the
     * returned string is its FileProvider `content:` URI for the share sheet — there
     * [destination] is ignored and may be `null`. On the desktop [destination] is the
     * `file:` URI the save dialog returned — the copy is written there and the string
     * is its path — and **only on the desktop** does `null` answer
     * [DiagnosticsError.CANCELLED] without producing anything.
     *
     * Implementation note 2026-11-06: 09's sketch has no `destination` parameter; the
     * desktop save dialog is UI-side (`FileSaver`), so the chosen URI arrives here.
     */
    suspend fun exportDatabaseCopy(destination: String? = null): Outcome<String, DiagnosticsError>

    /** The last 500 redacted log lines — the `RingBufferLogSink` (01). */
    fun observeLogLines(): Flow<List<String>>
}

/** `exportDatabaseCopy`'s failure vocabulary (09). */
enum class DiagnosticsError {
    NOT_ENOUGH_SPACE,
    DATABASE_BUSY,
    CANCELLED,
    FAILED,
}

/**
 * The diagnostics half of the YouTube engine (09's YOUTUBE card + 11's engine-child row),
 * bound by `:youtube:impl` once the engine updater lands. Until then `UnavailableEngine`
 * in `:core:data` answers honestly: nothing installed, no child.
 */
interface YouTubeEngineDiagnostics {
    /** The installed engine version, `null` when none is installed. */
    suspend fun installedVersion(): String?

    /** `ok`, `degraded` or `unavailable` — the engine's own last health verdict. */
    suspend fun health(): String

    /** Desktop only: the yt-dlp child process state; `null` on Android and when absent. */
    suspend fun childStatus(): String?
}
