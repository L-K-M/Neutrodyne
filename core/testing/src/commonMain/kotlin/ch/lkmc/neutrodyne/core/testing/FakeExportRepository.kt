// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.ExportError
import ch.lkmc.neutrodyne.core.domain.ExportRepository
import ch.lkmc.neutrodyne.core.model.ExportRequest
import ch.lkmc.neutrodyne.core.model.PreparedExport

/** Records [ExportRepository] calls; results are fixtures. */
class FakeExportRepository : ExportRepository {
    var prepareOutcome: Outcome<PreparedExport, ExportError> =
        Outcome.Success(PreparedExport("neutrodyne-subscriptions.opml", "text/x-opml", null, 0, 0, 0))
    var saveOutcome: Outcome<Unit, ExportError> = Outcome.Success(Unit)

    val prepareRequests = mutableListOf<ExportRequest>()

    /** `(export.fileName, destinationUri)` of every [saveTo] call. */
    val savedTo = mutableListOf<Pair<String, String>>()

    override suspend fun prepare(request: ExportRequest): Outcome<PreparedExport, ExportError> {
        prepareRequests += request
        return prepareOutcome
    }

    override suspend fun saveTo(
        export: PreparedExport,
        destinationUri: String,
    ): Outcome<Unit, ExportError> {
        savedTo += export.fileName to destinationUri
        return saveOutcome
    }
}
