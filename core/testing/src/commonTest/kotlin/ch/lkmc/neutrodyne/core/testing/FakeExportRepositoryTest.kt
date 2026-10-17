// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.ExportError
import ch.lkmc.neutrodyne.core.model.ExportLayout
import ch.lkmc.neutrodyne.core.model.ExportRequest
import ch.lkmc.neutrodyne.core.model.PreparedExport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fake records calls and returns fixtures (05 ExportRepository). */
class FakeExportRepositoryTest {
    @Test fun recordsPrepareAndSaveCalls() =
        runTest {
            val repo = FakeExportRepository()
            val export = PreparedExport("tech.opml", "text/x-opml", "content://x/1", 12, 1, 0)
            repo.prepareOutcome = Outcome.Success(export)
            val request = ExportRequest(groupId = 3L, layout = ExportLayout.FLAT)

            assertEquals(Outcome.Success(export), repo.prepare(request))
            assertEquals(Outcome.Success(Unit), repo.saveTo(export, "content://dest"))
            assertEquals(listOf(request), repo.prepareRequests)
            assertEquals(listOf("tech.opml" to "content://dest"), repo.savedTo)
        }

    @Test fun failuresPropagate() =
        runTest {
            val repo = FakeExportRepository()
            repo.prepareOutcome = Outcome.Failure(ExportError.Storage)
            repo.saveOutcome = Outcome.Failure(ExportError.DestinationUnavailable)

            assertEquals(Outcome.Failure(ExportError.Storage), repo.prepare(ExportRequest()))
            assertEquals(
                Outcome.Failure(ExportError.DestinationUnavailable),
                repo.saveTo(PreparedExport("x.opml", "text/x-opml", null, 0, 0, 0), "file:///x"),
            )
        }
}
