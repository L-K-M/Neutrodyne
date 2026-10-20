// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.BackupError
import ch.lkmc.neutrodyne.core.domain.ImportReportPoster
import ch.lkmc.neutrodyne.core.model.ImportItemStatus

/** Records the notifications an `ImportFetchRunner`/`RestoreRunner` run posted (05 §8). */
class FakeImportReportPoster : ImportReportPoster {
    data class Ready(
        val sessionId: Long,
        val itemCount: Int,
        val sourceName: String?,
    )

    class Done(
        val sessionId: Long,
        val counts: Map<ImportItemStatus, Int>,
    )

    class RestoreFailed(
        val sessionId: Long,
        val auto: Boolean,
        val error: BackupError,
    )

    val readyPosts = mutableListOf<Ready>()

    override suspend fun postReady(
        sessionId: Long,
        itemCount: Int,
        sourceName: String?,
    ) {
        readyPosts += Ready(sessionId, itemCount, sourceName)
    }

    val posts = mutableListOf<Done>()
    val restoreFailures = mutableListOf<RestoreFailed>()
    var foreignPosts = 0
        private set
    var onPostDone: suspend (Long, Map<ImportItemStatus, Int>) -> Unit = { _, _ -> }

    override suspend fun postDone(
        sessionId: Long,
        counts: Map<ImportItemStatus, Int>,
    ) {
        posts += Done(sessionId, counts)
        onPostDone(sessionId, counts)
    }

    override suspend fun postRestoreFailed(
        sessionId: Long,
        auto: Boolean,
        error: BackupError,
    ) {
        restoreFailures += RestoreFailed(sessionId, auto, error)
    }

    override suspend fun postForeignSnapshotPending() {
        foreignPosts++
    }
}
