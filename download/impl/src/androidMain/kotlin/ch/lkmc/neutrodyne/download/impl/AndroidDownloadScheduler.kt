// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.WaitReason
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/** Queue/exit decisions share a mutex per lane; synchronous framework startup never waits for Room. */
internal class AndroidDownloadScheduler(
    private val rows: suspend () -> List<DownloadEntity>,
    private val markForeground: suspend (List<Long>, WaitReason) -> Unit,
    private val conditions: () -> Conditions,
    private val visible: () -> Boolean,
    private val backgroundLimited: () -> Boolean,
    private val scheduleUidt: (List<DownloadEntity>) -> Boolean,
    private val enqueue: suspend (ExistingWorkPolicy, OneTimeWorkRequest) -> Unit,
    private val armWake: suspend (DownloadLane, OneTimeWorkRequest?) -> Unit,
    private val runtimeWake: (DownloadLane) -> Unit,
    private val waitingNotice: (Boolean) -> Unit,
    private val clock: Clock,
    val registry: LaneRegistry,
    private val sdk: Int,
) : DownloadScheduler {
    private val mutexes = DownloadLane.entries.associateWith { Mutex() }
    private val autoRegistry = LaneRegistry()

    internal fun registry(lane: DownloadLane): LaneRegistry =
        if (lane == DownloadLane.MANUAL) registry else autoRegistry

    fun liveTokens(): Set<String> = registry.liveTokens() + autoRegistry.liveTokens()

    override suspend fun ensureScheduled(lane: DownloadLane) {
        mutexes.getValue(lane).withLock { ensureLocked(lane) }
    }

    private suspend fun ensureLocked(lane: DownloadLane) {
        val registry = registry(lane)
        val queued = queued(lane)
        if (queued.isEmpty()) {
            if (lane == DownloadLane.MANUAL) waitingNotice(false)
            armWake(lane, null)
            return
        }
        val foreground = visible()
        if (lane == DownloadLane.MANUAL && foreground) {
            val held = queued.filter { it.waitReason == WaitReason.NEEDS_FOREGROUND }.map { it.episodeId }
            if (held.isNotEmpty()) markForeground(held, WaitReason.NONE)
            waitingNotice(false)
        }
        val due = queued.filter { due(it) }
        // A running job must never be replaced: schedule(job1001) would stop its transfers.
        val uidt =
            lane == DownloadLane.MANUAL && (
                registry.uidtRunning() ||
                    (sdk >= 34 && foreground && due.isNotEmpty() && scheduleUidt(due))
            )
        if (lane == DownloadLane.MANUAL && !uidt && !foreground && backgroundLimited() &&
            registry.phase() in setOf(LaneRegistry.Phase.IDLE, LaneRegistry.Phase.PENDING)
        ) {
            markForeground(due.map { it.episodeId }, WaitReason.NEEDS_FOREGROUND)
            waitingNotice(true)
            armWaiting(lane, queued.filter { !due(it) })
            return
        }
        when (registry.phase()) {
            LaneRegistry.Phase.ACTIVE -> {
                wake(lane)
            }

            LaneRegistry.Phase.CLOSING -> {
                registry.reopen()
            }

            LaneRegistry.Phase.PENDING -> {
                if (lane == DownloadLane.MANUAL && sdk < 31 && foreground && !registry.pendingExpedited()) {
                    enqueueLocked(lane, ExistingWorkPolicy.REPLACE)
                }
            }

            LaneRegistry.Phase.IDLE -> {
                if (!uidt && queued.any { runnable(it) }) enqueueLocked(lane)
            }
        }
        armWaiting(lane, queued.filter { if (uidt) !due(it) else !runnable(it) })
    }

    override fun wake(lane: DownloadLane) {
        runtimeWake(lane)
    }

    override suspend fun scheduleWake(
        lane: DownloadLane,
        earliestAt: Long?,
        reason: WaitReason,
    ) {
        mutexes.getValue(lane).withLock {
            // Re-read committed rows: a completed/cancelled lane must never leave ghost wake work.
            armWaiting(lane, queued(lane).filter { !runnable(it) })
        }
    }

    /** Engine owns register/tryClose/unregister. UIDT completion runs inside the exit mutex. */
    fun exit(
        lane: DownloadLane = DownloadLane.MANUAL,
        finish: () -> Unit = {},
    ): DrainExit {
        val mutex = mutexes.getValue(lane)
        val registry = registry(lane)
        return object : DrainExit {
            override suspend fun register(
                token: RunnerToken,
                lanes: Set<DownloadLane>,
            ) {
                require(lanes == setOf(lane))
                mutex.withLock { registry.register(token) }
            }

            override suspend fun tryClose(
                token: RunnerToken,
                outcome: DrainOutcome,
            ): Boolean {
                mutex.withLock {
                    if (!yieldToUidt(lane, token) && outcome != DrainOutcome.DeadlineReached &&
                        queued(lane).any { runnable(it) }
                    ) {
                        return false
                    }
                    registry.closing(token)
                }
                // Give an enqueue waiting on the mutex a chance to request reopen before completion.
                yield()
                return mutex.withLock {
                    if (registry.consumeReopen(token)) return@withLock false
                    val waiting = queued(lane)
                    if (!yieldToUidt(lane, token) && outcome != DrainOutcome.DeadlineReached &&
                        waiting.any { runnable(it) }
                    ) {
                        registry.reopen()
                        registry.consumeReopen(token)
                        return@withLock false
                    }
                    armWaiting(lane, waiting.filter { !runnable(it) })
                    if (!yieldToUidt(lane, token) &&
                        waiting.any { runnable(it) }
                    ) {
                        enqueueLocked(lane, ExistingWorkPolicy.APPEND_OR_REPLACE)
                    }
                    finish()
                    registry.unregister(token)
                    true
                }
            }

            override suspend fun unregister(token: RunnerToken) {
                mutex.withLock { registry.unregister(token) }
            }
        }
    }

    suspend fun workerStarted(
        token: RunnerToken,
        lane: DownloadLane = DownloadLane.MANUAL,
    ) {
        val registry = registry(lane)
        mutexes.getValue(lane).withLock {
            registry.workerStarted()
            registry.register(token)
        }
    }

    suspend fun finishStopped(
        token: RunnerToken,
        finish: () -> Unit,
    ) {
        mutexes.getValue(DownloadLane.MANUAL).withLock {
            finish()
            registry.unregister(token)
        }
    }

    private fun yieldToUidt(
        lane: DownloadLane,
        token: RunnerToken,
    ): Boolean = lane == DownloadLane.MANUAL && registry.uidtRunning() && !registry.isUidt(token)

    private suspend fun enqueueLocked(
        lane: DownloadLane,
        policy: ExistingWorkPolicy? = null,
    ) {
        val registry = registry(lane)
        val request = AndroidDownloadWork.lane(visible(), lane)
        val chosen =
            policy ?: if (registry.hasStartedWorker()) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        registry.pending(request.id, request.workSpec.expedited)
        try {
            enqueue(chosen, request)
        } catch (failure: Throwable) {
            registry.enqueueFailed(request.id)
            throw failure
        }
    }

    private suspend fun queued(lane: DownloadLane): List<DownloadEntity> =
        rows().filter {
            it.lane == lane && it.state == DownloadState.QUEUED && it.runnerToken == null &&
                it.waitReason != WaitReason.YOUTUBE_ENGINE_OFF &&
                when (it.sourceKind) {
                    ch.lkmc.neutrodyne.core.model.SourceKind.RSS_ENCLOSURE -> true
                    ch.lkmc.neutrodyne.core.model.SourceKind.YOUTUBE -> conditions().youtubeAllowed
                }
        }

    private fun due(row: DownloadEntity): Boolean = (row.nextAttemptAt ?: Long.MIN_VALUE) <= clock.now()

    private fun runnable(row: DownloadEntity): Boolean {
        val c = conditions()
        return due(row) && c.connected && c.validated && (row.allowMetered || c.unmetered) &&
            (!row.requireCharging || c.charging)
    }

    private companion object {
        const val PORTAL_RECHECK_MS = 60_000L
    }

    private suspend fun armWaiting(
        lane: DownloadLane,
        waiting: List<DownloadEntity>,
    ) {
        if (waiting.isEmpty()) {
            armWake(lane, null)
            return
        }
        var unmetered = waiting.none { it.allowMetered }
        var charging = waiting.all { it.requireCharging }
        var covered = waiting.filter { (unmetered || it.allowMetered) && (charging || !it.requireCharging) }
        // Orthogonal needs (Wi-Fi without charging, mobile with charging) cover no row with
        // the aggregate request. Pick one row's complete constraints instead of spinning.
        if (covered.isEmpty()) {
            val row = waiting.minBy { it.nextAttemptAt ?: clock.now() }
            unmetered = !row.allowMetered
            charging = row.requireCharging
            covered = listOf(row)
        }
        val earliest = covered.minOf { it.nextAttemptAt ?: clock.now() }
        val c = conditions()
        // The pre-28 CONNECTED fallback cannot express VALIDATED; avoid an immediate wake loop.
        val minimumDelay = if (sdk < 28 && c.connected && !c.validated) PORTAL_RECHECK_MS else 0L
        armWake(lane, AndroidDownloadWork.wake(maxOf(earliest - clock.now(), minimumDelay), unmetered, lane, charging))
    }
}
