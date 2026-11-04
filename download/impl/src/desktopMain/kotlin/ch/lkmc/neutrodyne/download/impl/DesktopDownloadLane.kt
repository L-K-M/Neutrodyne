// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.JobLane
import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.WaitReason
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.time.Instant

/** Tokens remain live through engine cleanup. Revision also refreshes time-based busy state. */
@SingleIn(AppScope::class)
@Inject
internal class DesktopDownloadActivity :
    DrainExit,
    DrainReporter {
    private val lock = Any()
    private val tokens = mutableSetOf<String>()
    private val revision = MutableStateFlow(0L)
    private val activeEpisodes = MutableStateFlow<Set<Long>>(emptySet())
    val changes = revision.asStateFlow()

    fun liveTokens(): Set<String> = synchronized(lock) { tokens.toSet() }

    fun hasActiveTransfers(): Boolean = activeEpisodes.value.isNotEmpty()

    fun changed() {
        revision.update { it + 1 }
    }

    override suspend fun register(
        token: RunnerToken,
        lanes: Set<DownloadLane>,
    ) {
        synchronized(lock) { check(tokens.add(token.value)) }
        changed()
    }

    // DesktopJobRunner retains a poke received during a run and reruns after its return.
    // Persisted retries are checked by its minute tick, so closing needs no idle runner/timer.
    override suspend fun tryClose(
        token: RunnerToken,
        outcome: DrainOutcome,
    ): Boolean = true

    override suspend fun unregister(token: RunnerToken) {
        synchronized(lock) { check(tokens.remove(token.value)) }
        changed()
    }

    override fun active(episodeIds: Set<Long>) {
        activeEpisodes.value = episodeIds.toSet()
        changed()
    }

    override fun bytes(
        downloaded: Long,
        total: Long?,
    ) = Unit
}

internal class DesktopDownloadScheduler(
    private val poker: () -> JobLanePoker,
    private val wakeEngine: (DownloadLane) -> Unit,
) : DownloadScheduler {
    override suspend fun ensureScheduled(lane: DownloadLane) {
        require(lane == DownloadLane.MANUAL) { "AUTO scheduling belongs to M6b" }
        wake(lane)
        poker().poke(DesktopDownloadLane.NAME)
    }

    override fun wake(lane: DownloadLane) {
        if (lane == DownloadLane.MANUAL) wakeEngine(lane)
    }

    override suspend fun scheduleWake(
        lane: DownloadLane,
        earliestAt: Long?,
        reason: WaitReason,
    ) {
        // nextAttemptAt is durable; the existing runner owns subsequent ticks.
        ensureScheduled(lane)
    }
}

internal class DesktopDownloadLane(
    private val reconcile: suspend () -> Unit,
    private val drain: suspend (DrainRequest) -> DrainOutcome,
    private val activity: DesktopDownloadActivity,
) : JobLane {
    override val name = NAME
    private val startup = Mutex()
    private var reconciled = false

    override suspend fun run(now: Instant) {
        startup.withLock {
            if (!reconciled) {
                reconcile()
                reconciled = true
            }
        }
        try {
            drain(
                DrainRequest(
                    setOf(DownloadLane.MANUAL),
                    RunnerToken("desk:MANUAL:${UUID.randomUUID()}"),
                    softDeadlineElapsed = null,
                    waitHorizonMs = WAIT_HORIZON_MS,
                    reporter = activity,
                    exit = activity,
                    cancellationIntent = { StopIntent.RunnerStopped(DownloadStopReasons.APP_QUIT) },
                ),
            )
        } finally {
            activity.changed()
        }
    }

    companion object {
        const val NAME = "downloads-manual"
        private const val WAIT_HORIZON_MS = 60_000L
    }
}
