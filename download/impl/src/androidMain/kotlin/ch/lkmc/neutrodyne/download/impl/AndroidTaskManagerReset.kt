// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import android.os.Build
import ch.lkmc.neutrodyne.core.database.DownloadQueries
import ch.lkmc.neutrodyne.core.domain.ExitReasonProbe
import ch.lkmc.neutrodyne.core.domain.ProcessExitReason
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState

internal class AndroidTaskManagerReset(
    private val dao: DownloadQueries,
    private val exitReasons: ExitReasonProbe,
    private val jobs: JobSchedulerFacade,
) {
    /** Called under claimMutex, before the generic reset can erase stale UIDT ownership. */
    suspend fun reset(liveTokens: Set<String>) {
        if (Build.VERSION.SDK_INT < 34) return

        val exit = exitReasons.latest() ?: return
        if (exit.reason != ProcessExitReason.USER_REQUESTED) return
        if (jobs.pendingManualJob() != null) return

        val stoppedUidt =
            dao.allRows().any { row ->
                val token = row.runnerToken
                if (row.lane != DownloadLane.MANUAL || row.state !in ACTIVE_STATES ||
                    token == null || token in liveTokens || !token.startsWith(UIDT_PREFIX)
                ) {
                    return@any false
                }

                // Current tokens append an invocation UUID; accept the original timestamp-only form too.
                val startedAt = token.removePrefix(UIDT_PREFIX).substringBefore(':').toLongOrNull()
                startedAt != null && startedAt >= 0 && exit.timestampMs > startedAt
            }
        if (!stoppedUidt) return

        dao.resetTaskManagerStoppedRunners(liveTokens, DownloadStopReasons.USER_TASK_MANAGER)
    }

    private companion object {
        const val UIDT_PREFIX = "uidt:"
        val ACTIVE_STATES = setOf(DownloadState.RESOLVING, DownloadState.DOWNLOADING, DownloadState.VERIFYING)
    }
}
