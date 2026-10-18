// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.ImportReportPoster
import ch.lkmc.neutrodyne.core.model.ImportItemStatus

/** Records the done notifications an `ImportFetchRunner` run posted (05 §8). */
class FakeImportReportPoster : ImportReportPoster {
    class Done(
        val sessionId: Long,
        val counts: Map<ImportItemStatus, Int>,
    )

    val posts = mutableListOf<Done>()
    var onPostDone: suspend (Long, Map<ImportItemStatus, Int>) -> Unit = { _, _ -> }

    override suspend fun postDone(
        sessionId: Long,
        counts: Map<ImportItemStatus, Int>,
    ) {
        posts += Done(sessionId, counts)
        onPostDone(sessionId, counts)
    }
}
