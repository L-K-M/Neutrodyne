// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.download.api.DeleteReason

/** One unique token per runner invocation, never reused on restart (uidt:/work:/desk: formats).
 * Register before claims, retain through child cleanup/final commits, unregister in finally.
 * Reconcile runs once before claims and never resets a registered live token. */
internal data class RunnerToken(
    val value: String,
)

internal data class Conditions(
    val connected: Boolean,
    val unmetered: Boolean,
    val charging: Boolean,
    val youtubeAllowed: Boolean,
    val validated: Boolean,
)

internal sealed interface StopIntent {
    data object Pause : StopIntent

    data class Cancel(
        val reason: DeleteReason,
    ) : StopIntent

    data class Delete(
        val reason: DeleteReason,
    ) : StopIntent

    data class Requeue(
        val waitReason: WaitReason,
        val stopReason: Int? = null,
    ) : StopIntent

    data class RunnerStopped(
        val platformStopReason: Int,
    ) : StopIntent
}

internal object DownloadStopReasons {
    const val PROCESS_DEATH = -1
    const val SOFT_DEADLINE = -2
    const val USER_PAUSE = -3
    const val USER_TASK_MANAGER = -4
    const val HANDOVER = -5
    const val APP_QUIT = -6
}

internal data class DrainRequest(
    val lanes: Set<DownloadLane>,
    val runnerToken: RunnerToken,
    val softDeadlineElapsed: Long?,
    val waitHorizonMs: Long,
    val reporter: DrainReporter,
    val exit: DrainExit,
)

internal sealed interface DrainOutcome {
    data object Empty : DrainOutcome

    data class Waiting(
        val earliest: Long,
    ) : DrainOutcome

    data class Blocked(
        val reason: WaitReason,
    ) : DrainOutcome

    data object DeadlineReached : DrainOutcome
}

internal interface DrainReporter {
    fun active(episodeIds: Set<Long>)

    fun bytes(
        downloaded: Long,
        total: Long?,
    )
}

internal interface DrainExit {
    suspend fun register(
        token: RunnerToken,
        lanes: Set<DownloadLane>,
    )

    /** Scheduler owns the lane mutex: enter CLOSING, re-read queue needs, arm wake/continuation,
     * recheck pokes and return false to drain again if a concurrent enqueue reopened the lane. */
    suspend fun tryClose(
        token: RunnerToken,
        outcome: DrainOutcome,
    ): Boolean

    suspend fun unregister(token: RunnerToken)
}

internal interface DownloadScheduler {
    suspend fun ensureScheduled(lane: DownloadLane)

    fun wake(lane: DownloadLane)

    /** Runner exit arms a delayed/constraint wake, never an idle runner. Desktop pokes its lane. */
    suspend fun scheduleWake(
        lane: DownloadLane,
        earliestAt: Long?,
        reason: WaitReason,
    )
}

/** Runner-facing port; platform lanes use a fake until W4 supplies the common engine. */
internal interface DownloadRunner {
    suspend fun drain(request: DrainRequest): DrainOutcome
}
