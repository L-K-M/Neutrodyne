// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.JobLane
import ch.lkmc.neutrodyne.core.common.JobLanePoker
import ch.lkmc.neutrodyne.core.datastore.SettingStore
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val activeEpisodes = MutableStateFlow<Map<DownloadLane, Set<Long>>>(emptyMap())
    val changes = revision.asStateFlow()

    fun liveTokens(): Set<String> = synchronized(lock) { tokens.toSet() }

    fun hasActiveTransfers(): Boolean = activeEpisodes.value.values.any { it.isNotEmpty() }

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

    override fun active(episodeIds: Set<Long>) = active(DownloadLane.MANUAL, episodeIds)

    private fun active(
        lane: DownloadLane,
        episodeIds: Set<Long>,
    ) {
        activeEpisodes.update { it + (lane to episodeIds.toSet()) }
        changed()
    }

    fun reporter(lane: DownloadLane): DrainReporter =
        object : DrainReporter {
            override fun active(episodeIds: Set<Long>) = active(lane, episodeIds)

            override fun bytes(
                downloaded: Long,
                total: Long?,
            ) = Unit
        }

    fun observeBusy(settings: SettingStore): Flow<Boolean> =
        combine(activeEpisodes, settings.observe(DownloadSettingKeys.MOVE_TARGET)) { episodes, target ->
            episodes.values.any { it.isNotEmpty() } || target.isNotEmpty()
        }.distinctUntilChanged()

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
        wake(lane)
        poker().poke(DesktopDownloadLane.name(lane))
    }

    override fun wake(lane: DownloadLane) {
        wakeEngine(lane)
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

/** Both lanes share the gate, so AUTO cannot claim before crash recovery and the watermark pass. */
internal class DesktopDownloadStartup(
    private val pass: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private var complete = false

    suspend fun ensureStarted() =
        mutex.withLock {
            if (complete) return@withLock
            pass()
            complete = true
        }
}

internal class DesktopDownloadLane(
    private val lane: DownloadLane,
    private val startup: DesktopDownloadStartup,
    private val daily: suspend (Instant) -> Unit,
    private val connected: () -> Boolean,
    private val drain: suspend (DrainRequest) -> DrainOutcome,
    private val activity: DesktopDownloadActivity,
) : JobLane {
    override val name = name(lane)

    override suspend fun run(now: Instant) {
        startup.ensureStarted()
        if (lane == DownloadLane.AUTO) daily(now)
        if (!connected()) return

        try {
            drain(
                DrainRequest(
                    setOf(lane),
                    RunnerToken("desk:$lane:${UUID.randomUUID()}"),
                    softDeadlineElapsed = null,
                    waitHorizonMs = WAIT_HORIZON_MS,
                    reporter = activity.reporter(lane),
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
        const val AUTO_NAME = "downloads-auto"

        fun name(lane: DownloadLane) = if (lane == DownloadLane.MANUAL) NAME else AUTO_NAME

        private const val WAIT_HORIZON_MS = 60_000L
    }
}

/** Persist only a completed pass: failures and cancellation remain due on the next tick. */
internal class DesktopDownloadDaily(
    private val settings: SettingStore,
    private val pass: suspend () -> Unit,
) {
    private val mutex = Mutex()

    suspend fun run(now: Instant) =
        mutex.withLock {
            val at = now.toEpochMilliseconds()
            val last = settings.get(DownloadSettingKeys.CLEANUP_LAST_AT)
            if (last != 0L && at - last < DAILY_INTERVAL_MS) return@withLock
            pass()
            settings.set(DownloadSettingKeys.CLEANUP_LAST_AT, at)
        }

    companion object {
        const val DAILY_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
