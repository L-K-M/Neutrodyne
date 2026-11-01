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

/** Queue/exit decisions share one mutex; synchronous framework startup never waits for Room. */
internal class AndroidDownloadScheduler(
    private val rows: suspend () -> List<DownloadEntity>,
    private val markForeground: suspend (List<Long>, WaitReason) -> Unit,
    private val conditions: () -> Conditions,
    private val visible: () -> Boolean,
    private val backgroundLimited: () -> Boolean,
    private val scheduleUidt: (List<DownloadEntity>) -> Boolean,
    private val enqueue: suspend (ExistingWorkPolicy, OneTimeWorkRequest) -> Unit,
    private val armWake: suspend (OneTimeWorkRequest?) -> Unit,
    private val runtimeWake: (DownloadLane) -> Unit,
    private val waitingNotice: (Boolean) -> Unit,
    private val clock: Clock,
    val registry: LaneRegistry,
    private val sdk: Int,
) : DownloadScheduler {
    private val mutex = Mutex()

    override suspend fun ensureScheduled(lane: DownloadLane) {
        require(lane == DownloadLane.MANUAL) { "AUTO scheduling belongs to M6b" }
        mutex.withLock { ensureLocked() }
    }

    private suspend fun ensureLocked() {
        val queued = queued()
        if (queued.isEmpty()) {
            waitingNotice(false)
            armWake(null)
            return
        }
        val foreground = visible()
        if (foreground) {
            val held = queued.filter { it.waitReason == WaitReason.NEEDS_FOREGROUND }.map { it.episodeId }
            if (held.isNotEmpty()) markForeground(held, WaitReason.NONE)
            waitingNotice(false)
        }
        val due = queued.filter { due(it) }
        // A running job must never be replaced: schedule(job1001) would stop its transfers.
        val uidt =
            registry.uidtRunning() ||
                (sdk >= 34 && foreground && due.isNotEmpty() && scheduleUidt(due))
        if (!uidt && !foreground && backgroundLimited() &&
            registry.phase() in setOf(LaneRegistry.Phase.IDLE, LaneRegistry.Phase.PENDING)
        ) {
            markForeground(due.map { it.episodeId }, WaitReason.NEEDS_FOREGROUND)
            waitingNotice(true)
            armWaiting(queued.filter { !due(it) })
            return
        }
        when (registry.phase()) {
            LaneRegistry.Phase.ACTIVE -> {
                wake(DownloadLane.MANUAL)
            }

            LaneRegistry.Phase.CLOSING -> {
                registry.reopen()
            }

            LaneRegistry.Phase.PENDING -> {
                if (sdk < 31 && foreground && !registry.pendingExpedited()) {
                    enqueueLocked(ExistingWorkPolicy.REPLACE)
                }
            }

            LaneRegistry.Phase.IDLE -> {
                if (!uidt && queued.any { runnable(it) }) enqueueLocked()
            }
        }
        armWaiting(queued.filter { if (uidt) !due(it) else !runnable(it) })
    }

    override fun wake(lane: DownloadLane) {
        runtimeWake(lane)
    }

    override suspend fun scheduleWake(
        lane: DownloadLane,
        earliestAt: Long?,
        reason: WaitReason,
    ) {
        require(lane == DownloadLane.MANUAL)
        mutex.withLock {
            val waiting = queued().filter { !runnable(it) }
            if (waiting.isNotEmpty()) {
                armWaiting(waiting)
            } else if (earliestAt !=
                null
            ) {
                armWake(AndroidDownloadWork.wake(earliestAt - clock.now(), reason == WaitReason.UNMETERED_NETWORK))
            } else {
                armWake(null)
            }
        }
    }

    /** Engine owns register/tryClose/unregister. UIDT completion runs inside the exit mutex. */
    fun exit(finish: () -> Unit = {}): DrainExit =
        object : DrainExit {
            override suspend fun register(
                token: RunnerToken,
                lanes: Set<DownloadLane>,
            ) {
                require(lanes == setOf(DownloadLane.MANUAL))
                mutex.withLock { registry.register(token) }
            }

            override suspend fun tryClose(
                token: RunnerToken,
                outcome: DrainOutcome,
            ): Boolean {
                mutex.withLock {
                    if (!yieldToUidt(token) && outcome != DrainOutcome.DeadlineReached &&
                        queued().any { runnable(it) }
                    ) {
                        return false
                    }
                    registry.closing(token)
                }
                // Give an enqueue waiting on the mutex a chance to request reopen before completion.
                yield()
                return mutex.withLock {
                    if (registry.consumeReopen(token)) return@withLock false
                    val waiting = queued()
                    if (!yieldToUidt(token) && outcome != DrainOutcome.DeadlineReached &&
                        waiting.any { runnable(it) }
                    ) {
                        registry.reopen()
                        registry.consumeReopen(token)
                        return@withLock false
                    }
                    armWaiting(waiting.filter { !runnable(it) })
                    if (!yieldToUidt(token) &&
                        waiting.any { runnable(it) }
                    ) {
                        enqueueLocked(ExistingWorkPolicy.APPEND_OR_REPLACE)
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

    suspend fun workerStarted(token: RunnerToken) {
        mutex.withLock {
            registry.workerStarted()
            registry.register(token)
        }
    }

    suspend fun finishStopped(
        token: RunnerToken,
        finish: () -> Unit,
    ) {
        mutex.withLock {
            finish()
            registry.unregister(token)
        }
    }

    private fun yieldToUidt(token: RunnerToken): Boolean = registry.uidtRunning() && !registry.isUidt(token)

    private suspend fun enqueueLocked(policy: ExistingWorkPolicy? = null) {
        val request = AndroidDownloadWork.lane(visible())
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

    private suspend fun queued(): List<DownloadEntity> =
        rows().filter {
            it.lane == DownloadLane.MANUAL && it.state == DownloadState.QUEUED && it.runnerToken == null &&
                it.sourceKind == ch.lkmc.neutrodyne.core.model.SourceKind.RSS_ENCLOSURE
        }

    private fun due(row: DownloadEntity): Boolean = (row.nextAttemptAt ?: Long.MIN_VALUE) <= clock.now()

    private fun runnable(row: DownloadEntity): Boolean {
        val c = conditions()
        return due(row) && c.connected && c.validated && (row.allowMetered || c.unmetered)
    }

    private companion object {
        const val PORTAL_RECHECK_MS = 60_000L
    }

    private suspend fun armWaiting(waiting: List<DownloadEntity>) {
        if (waiting.isEmpty()) {
            armWake(null)
            return
        }
        val unmetered = waiting.none { it.allowMetered }
        val covered = waiting.filter { unmetered || it.allowMetered }
        val earliest = covered.minOf { it.nextAttemptAt ?: clock.now() }
        val c = conditions()
        // The pre-28 CONNECTED fallback cannot express VALIDATED; avoid an immediate wake loop.
        val minimumDelay = if (sdk < 28 && c.connected && !c.validated) PORTAL_RECHECK_MS else 0L
        armWake(AndroidDownloadWork.wake(maxOf(earliest - clock.now(), minimumDelay), unmetered))
    }
}
