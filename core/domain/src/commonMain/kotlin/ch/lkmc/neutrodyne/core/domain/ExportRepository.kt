// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.model.ExportRequest
import ch.lkmc.neutrodyne.core.model.PreparedExport

/** OPML and NewPipe JSON export (05 OPML export). Implemented by `OpmlExporter` in M3. */
interface ExportRepository {
    /** Builds the export under `<cache>/export/{fileName}` (05 Destinations). */
    suspend fun prepare(request: ExportRequest): Outcome<PreparedExport, ExportError>

    /** Android: SAF copy with `"wt"`; desktop: atomic replace of a `file:` URI (05). */
    suspend fun saveTo(
        export: PreparedExport,
        destinationUri: String,
    ): Outcome<Unit, ExportError>
}

/** Why an export write failed (05 Error handling and failure modes). */
sealed interface ExportError {
    data object Io : ExportError

    data object Storage : ExportError

    data object DestinationUnavailable : ExportError
}
