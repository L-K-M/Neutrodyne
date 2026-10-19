// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.CredentialLookup
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.CleanupPlanner
import ch.lkmc.neutrodyne.core.domain.CleanupReason
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.download.api.DeleteReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.IOException
import kotlin.math.pow
import kotlin.random.Random

/** One process-wide engine. Controllers share claimMutex; scheduler exit locks stay outside it.
 * resetOrphanedRunners is W5's startup barrier, including its scheduler/live-token lock.
 * Platform graphs supply current conditions and may gate worker claims during UIDT takeover. */
internal class DownloadEngine(
    private val db: NeutrodyneDatabase,
    private val clock: Clock,
    private val credentials: CredentialLookup,
    private val conditions: StateFlow<Conditions>,
    private val sources: Set<TransferSource>,
    private val fileSystem: DownloadFileSystem,
    private val roots: StorageRoots,
    private val verification: DownloadFiles,
    private val controllerFiles: DownloadControllerFiles,
    private val index: LocalMediaIndexStore,
    private val scheduler: DownloadScheduler,
    private val progress: DownloadProgressHub,
    val claimMutex: Mutex,
    private val resetOrphanedRunners: suspend () -> Unit,
    private val onCompleted: suspend (DownloadEntity) -> Unit,
    private val cleanup: CleanupPlanner,
    private val mayClaim: (RunnerToken) -> Boolean = { true },
    private val jitter: () -> Double = { Random.nextDouble(MIN_JITTER, MAX_JITTER) },
) : DownloadRunner {
    private val dao = db.downloadDao()
    private val slots = DownloadSlots()
    private val active = ActiveTransfers()
    private val initialization = Mutex()
    private var initialized = false
    private val changes = MutableStateFlow(0L)
    private val stopping = MutableStateFlow<Map<String, StopIntent>>(emptyMap())

    fun wake(lane: DownloadLane) {
        // A revision, rather than a rendezvous channel, retains a wake received during a query.
        changes.update { it + 1 }
    }

    /** User commands hold claimMutex. Join without taking it, then apply a queued pause if
     * an earlier system stop already won the owner's final transition. */
    suspend fun stopTransfer(
        episodeId: Long,
        intent: StopIntent,
    ) {
        active.stop(episodeId, intent)
        if (intent != StopIntent.Pause) return
        val row = dao.byEpisode(episodeId) ?: return
        if (row.state != DownloadState.QUEUED || row.runnerToken != null) return
        dao.updateUnclaimed(
            row.copy(
                state = DownloadState.PAUSED,
                waitReason = WaitReason.NONE,
                nextAttemptAt = null,
                lastStopReason = DownloadStopReasons.USER_PAUSE,
            ),
            DownloadState.QUEUED,
        )
    }

    fun stopRunner(
        token: RunnerToken,
        intent: StopIntent,
    ) {
        stopping.update { it + (token.value to intent) }
        active.snapshot.filter { it.row.runnerToken == token.value }.forEach { it.stop(intent) }
        changes.update { it + 1 }
    }

    override suspend fun drain(request: DrainRequest): DrainOutcome =
        coroutineScope {
            val runnerScope = this
            require(request.lanes.isNotEmpty() && request.waitHorizonMs >= 0 && request.runnerToken.value.isNotBlank())
            initialization.withLock {
                if (!initialized) {
                    resetOrphanedRunners()
                    initialized = true
                }
            }
            credentials.awaitLoaded()
            var registered = false
            val monitor =
                launch(start = CoroutineStart.LAZY) {
                    conditions.collect { value ->
                        for (entry in own(request)) {
                            val row = dao.byEpisode(entry.row.episodeId) ?: continue
                            blocked(row, value)?.let { entry.stop(StopIntent.Requeue(it)) }
                        }
                        changes.update { it + 1 }
                    }
                }
            val reporting =
                launch(start = CoroutineStart.LAZY) {
                    progress.snapshots.collect { values ->
                        val ids = own(request).map { it.row.episodeId }.toSet()
                        val rows = values.filterKeys { it in ids }.values
                        request.reporter.bytes(
                            rows.sumOf { it.downloadedBytes },
                            rows
                                .takeIf {
                                    it.isNotEmpty() &&
                                        it.all { row ->
                                            row.totalBytes != null
                                        }
                                }?.sumOf { it.totalBytes!! },
                        )
                    }
                }
            try {
                withContext(NonCancellable) {
                    request.exit.register(request.runnerToken, request.lanes)
                    registered = true
                }
                monitor.start()
                reporting.start()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val revision = changes.value
                    val deadline = request.softDeadlineElapsed
                    if (deadline != null && clock.elapsedRealtime() >= deadline) {
                        stopRunner(
                            request.runnerToken,
                            StopIntent.Requeue(WaitReason.SYSTEM, DownloadStopReasons.SOFT_DEADLINE),
                        )
                        own(request).forEach { it.finished.await() }
                        request.exit.tryClose(request.runnerToken, DrainOutcome.DeadlineReached)
                        return@coroutineScope DrainOutcome.DeadlineReached
                    }
                    if (request.runnerToken.value in stopping.value) {
                        own(request).forEach { it.finished.await() }
                        val outcome = DrainOutcome.Blocked(WaitReason.SYSTEM)
                        request.exit.tryClose(request.runnerToken, outcome)
                        return@coroutineScope outcome
                    }
                    if (mayClaim(request.runnerToken)) {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            if (request.runnerToken.value in stopping.value || !mayClaim(request.runnerToken) ||
                                (deadline != null && clock.elapsedRealtime() >= deadline)
                            ) {
                                break
                            }
                            val admitted =
                                claimMutex.withLock {
                                    // A cancelled DAO call may already have committed its claim. Complete
                                    // registration atomically so its ATOMIC child can always release it.
                                    withContext(NonCancellable) {
                                        val row = claimOne(request) ?: return@withContext false
                                        val lease = slots.acquire(row)
                                        val entry = active.register(row)
                                        stopping.value[request.runnerToken.value]?.let(entry::stop)

                                        @OptIn(
                                            kotlinx.coroutines.DelicateCoroutinesApi::class,
                                            ExperimentalCoroutinesApi::class,
                                        )
                                        val child =
                                            runnerScope.launch(
                                                start = CoroutineStart.ATOMIC,
                                            ) { runTransfer(entry, lease, request.cancellationIntent) }
                                        entry.attach(child)
                                        true
                                    }
                                }
                            if (!admitted) break
                        }
                    }
                    val own = own(request)
                    request.reporter.active(own.map { it.row.episodeId }.toSet())
                    val outcome = queueOutcome(request)
                    val retryAt =
                        (outcome as? DrainOutcome.Waiting)?.earliest
                            ?: if (own.isNotEmpty()) {
                                request.lanes.mapNotNull { dao.queuedNeeds(it, clock.now()).earliestRetry }.minOrNull()
                            } else {
                                null
                            }
                    val retryDelay = retryAt?.let { (it - clock.now()).coerceIn(0, MAX_BACKOFF_MS) }
                    val withinHorizon = retryDelay != null && retryDelay <= request.waitHorizonMs
                    if (own.isEmpty() && !withinHorizon) {
                        if (request.exit.tryClose(request.runnerToken, outcome)) return@coroutineScope outcome
                        continue
                    }
                    val untilDeadline = deadline?.let { (it - clock.elapsedRealtime()).coerceAtLeast(0) }
                    val wait = listOfNotNull(retryDelay, untilDeadline).minOrNull()
                    if (wait == null) {
                        changes.first { it != revision }
                    } else {
                        withTimeoutOrNull(wait.coerceAtLeast(1)) { changes.first { it != revision } }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                DrainOutcome.Empty
            } finally {
                withContext(NonCancellable) {
                    monitor.cancelAndJoin()
                    reporting.cancelAndJoin()
                    // The caller's cancellation already cancelled its children; retain token through commits.
                    own(request).forEach { entry ->
                        entry.stop(
                            entry.intent ?: request.cancellationIntent()
                                ?: StopIntent.RunnerStopped(UNKNOWN_RUNNER_STOP_REASON),
                        )
                        entry.finished.await()
                    }
                    request.reporter.active(emptySet())
                    try {
                        if (registered) request.exit.unregister(request.runnerToken)
                    } finally {
                        stopping.update { it - request.runnerToken.value }
                    }
                }
            }
        }

    private fun own(request: DrainRequest) = active.snapshot.filter { it.row.runnerToken == request.runnerToken.value }

    private suspend fun claimOne(request: DrainRequest): DownloadEntity? {
        val c = conditions.value
        if (!c.connected) return null
        val skipped = mutableListOf<Long>()
        var claimed: DownloadEntity? = null
        for (page in 0 until MAX_CANDIDATE_PAGES) {
            var examined = 0
            claimed =
                dao.claimNext(
                    request.lanes.toList(),
                    clock.now(),
                    c.unmetered,
                    c.charging,
                    c.youtubeAllowed,
                    request.runnerToken.value,
                    page * PAGE_SIZE,
                ) { row ->
                    examined++
                    if (row.waitReason == WaitReason.NEEDS_FOREGROUND || active.contains(row.episodeId)) {
                        return@claimNext false
                    }
                    slots.canStart(row).also { if (!it) skipped += row.episodeId }
                }
            if (claimed != null || examined < PAGE_SIZE) break
        }
        if (skipped.isNotEmpty()) dao.markWait(skipped, WaitReason.SLOT, null)
        return claimed
    }

    private suspend fun queueOutcome(request: DrainRequest): DrainOutcome {
        var rows =
            dao.allRows().filter {
                it.lane in request.lanes && it.state == DownloadState.QUEUED &&
                    it.runnerToken == null
            }
        if (rows.isEmpty()) {
            if (own(request).isEmpty()) return DrainOutcome.Empty
            return DrainOutcome.Blocked(WaitReason.SLOT)
        }
        rows =
            rows.map { row ->
                val next = row.nextAttemptAt
                if (next != null && next - clock.now() > CLOCK_SKEW_LIMIT_MS) {
                    val clamped = clock.now() + MAX_BACKOFF_MS
                    dao.markWait(listOf(row.episodeId), row.waitReason, clamped)
                    row.copy(nextAttemptAt = clamped)
                } else {
                    row
                }
            }
        val c = conditions.value
        val eligible = rows.filter { blocked(it, c) == null && it.waitReason != WaitReason.NEEDS_FOREGROUND }
        val future = eligible.mapNotNull { it.nextAttemptAt }.filter { it > clock.now() }.minOrNull()
        if (future != null && eligible.none { (it.nextAttemptAt ?: Long.MIN_VALUE) <= clock.now() }) {
            return DrainOutcome.Waiting(future)
        }
        for (row in rows) {
            val reason = blocked(row, c) ?: continue
            dao.markWait(listOf(row.episodeId), reason, row.nextAttemptAt)
        }
        val reason =
            rows.firstNotNullOfOrNull { blocked(it, c) }
                ?: rows.firstOrNull { it.waitReason == WaitReason.NEEDS_FOREGROUND }?.waitReason
                ?: WaitReason.SLOT
        return DrainOutcome.Blocked(reason)
    }

    private fun blocked(
        row: DownloadEntity,
        c: Conditions,
    ): WaitReason? =
        when {
            !c.connected -> WaitReason.NETWORK
            !row.allowMetered && !c.unmetered -> WaitReason.UNMETERED_NETWORK
            row.lane == DownloadLane.AUTO && row.requireCharging && !c.charging -> WaitReason.CHARGING
            row.sourceKind == SourceKind.YOUTUBE && !c.youtubeAllowed -> WaitReason.BACKOFF
            else -> null
        }

    private suspend fun runTransfer(
        entry: ActiveTransfers.Entry,
        lease: DownloadSlots.Lease,
        cancellationIntent: () -> StopIntent?,
    ) {
        val transfer = Attempt(entry.row)
        var end: TransferEnd = TransferEnd.Unknown
        progress.begin(entry.row.episodeId, entry.row.downloadedBytes, entry.row.totalBytes)
        try {
            if (entry.intent != null) throw CancellationException("Download stopped before transfer")
            currentCoroutineContext().ensureActive()
            val result = transfer.run()
            if (result is TransferResult.Wait && result.reason == WaitReason.STORAGE) {
                try {
                    cleanup.run(CleanupReason.STORAGE)
                } catch (_: IOException) {
                    // Full/unmounted storage can also prevent journaling cleanup. Retain the
                    // actionable STORAGE wait and its retry instead of failing this transfer.
                }
            }
            currentCoroutineContext().ensureActive()
            end = TransferEnd.Finished(result)
        } catch (cancelled: CancellationException) {
            end =
                TransferEnd.Stopped(
                    entry.intent ?: cancellationIntent() ?: StopIntent.RunnerStopped(UNKNOWN_RUNNER_STOP_REASON),
                )
            throw cancelled
        } finally {
            withContext(NonCancellable) {
                try {
                    transfer.finish(end)
                } finally {
                    try {
                        transfer.part?.close()
                    } finally {
                        progress.end(entry.row.episodeId)
                        lease.release()
                        active.remove(entry)
                        changes.update { it + 1 }
                        DownloadLane.entries.forEach(scheduler::wake)
                    }
                }
            }
        }
    }

    /** This mutable checkpoint belongs to one child. Retries never persist byte ticks. */
    private inner class Attempt(
        private var row: DownloadEntity,
    ) : ProgressSink {
        var part: PartFile? = null
        private val token = checkNotNull(row.runnerToken)
        private var prepared: Prepared.Go? = null
        private var completed: Prepared.Adopted? = null
        private var state = DownloadState.RESOLVING
        private var retries = 0
        private var restarts = 0
        private var restartBudgetExhausted = false

        override suspend fun started(metadata: TransferMetadata) {
            row =
                row.copy(
                    state = DownloadState.DOWNLOADING,
                    totalBytes = metadata.total,
                    downloadedBytes = metadata.offset,
                    mimeType = metadata.mimeType,
                    etag = metadata.etag,
                    lastModified = metadata.lastModified,
                    resolvedItag = metadata.resolvedItag,
                )
            if (state == DownloadState.RESOLVING) {
                // SQLite may commit before cancellation reaches the suspended DAO caller.
                withContext(NonCancellable) {
                    if (dao.transition(row, state, token) == 0) throw CancellationException("Stale download owner")
                    state = DownloadState.DOWNLOADING
                }
            }
            bytes(metadata.offset, metadata.total)
        }

        override fun bytes(
            downloaded: Long,
            total: Long?,
        ) {
            progress.update(row.episodeId, DownloadState.DOWNLOADING, downloaded, total)
        }

        suspend fun run(): TransferResult {
            val source =
                sources.singleOrNull { it.kind == row.sourceKind }
                    ?: return TransferResult.Permanent(DownloadError.UNSUPPORTED_STREAM, null)
            var retryUrl: String? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                blocked(row, conditions.value)?.let { return TransferResult.Wait(it, null, null) }
                restartBudgetExhausted = false
                val exchange =
                    try {
                        exchange(source, retryUrl)
                    } catch (
                        failure: IOException,
                    ) {
                        storageFailure(failure)
                    }
                val result =
                    if (exchange is TransferResult.Restart && restarts == MAX_RESTARTS) {
                        // Exhausted server restarts are transient failures, not failed integrity checks.
                        restartBudgetExhausted = true
                        TransferResult.Transient(DownloadError.SIZE_MISMATCH, null, null)
                    } else {
                        exchange
                    }
                when (result) {
                    is TransferResult.Complete -> {
                        return if (completed != null) result else verifyAndFinish(result)
                    }

                    is TransferResult.Restart -> {
                        restarts++
                        checkNotNull(part).truncate(0)
                        row = row.copy(totalBytes = null, downloadedBytes = 0, etag = null, lastModified = null)
                        retryUrl = null
                    }

                    is TransferResult.Transient -> {
                        if (result.error == DownloadError.SIZE_MISMATCH && !restartBudgetExhausted) return result
                        if (!conditions.value.connected) return TransferResult.Wait(WaitReason.NETWORK, null, null)
                        if (retries == RETRY_DELAYS.size) return result
                        val wait = result.retryAfterMs ?: RETRY_DELAYS[retries]
                        retries++
                        delay(wait.coerceIn(0, MAX_IN_RUNNER_RETRY_MS))
                        retryUrl = result.retryUrl
                    }

                    else -> {
                        return result
                    }
                }
            }
        }

        private suspend fun exchange(
            source: TransferSource,
            retryUrl: String?,
        ): TransferResult {
            val handle = part ?: fileSystem.openPart(row.rootId, row.tempPath ?: DownloadPaths.part(row.episodeId))
            part = handle
            return when (val next = source.prepare(row, handle)) {
                is Prepared.Adopted -> {
                    completed = next
                    next.complete
                }

                is Prepared.Fail -> {
                    TransferResult.Permanent(next.error, next.httpStatus)
                }

                is Prepared.Wait -> {
                    TransferResult.Wait(next.reason, next.error, next.untilMs, next.countsAsAttempt)
                }

                is Prepared.Go -> {
                    prepared = next
                    if (next.rootId != handle.rootId || next.tempPath != handle.path) {
                        handle.close()
                        part = fileSystem.openPart(next.rootId, next.tempPath)
                    }
                    row = row.copy(rootId = next.rootId, tempPath = next.tempPath, lastStopReason = null)
                    val plan = if (retryUrl == null) next.plan else next.plan.copy(url = retryUrl)
                    source.transfer(plan, checkNotNull(part), this)
                }
            }
        }

        private suspend fun verifyAndFinish(result: TransferResult.Complete): TransferResult {
            currentCoroutineContext().ensureActive()
            if (dao.byEpisode(row.episodeId)?.runnerToken != token) {
                throw CancellationException("Stale download owner")
            }
            progress.update(row.episodeId, DownloadState.VERIFYING, result.total, result.total)
            val plan = checkNotNull(prepared).plan
            return try {
                val verified = verification.verify(checkNotNull(part), result.total, result.mimeType, plan.integrity)
                if (verified !is TransferResult.Complete) return verified
                val source =
                    db.episodeDao().downloadSources(listOf(row.episodeId)).singleOrNull()
                        ?: return TransferResult.Permanent(DownloadError.HTTP_NOT_FOUND, null)
                // Retain the final path even if cancellation arrives just after atomic rename.
                withContext(NonCancellable) {
                    completed = verification.finish(checkNotNull(part), source, plan, result)
                }
                result
            } catch (failure: IOException) {
                storageFailure(failure)
            }
        }

        private suspend fun storageFailure(failure: IOException): TransferResult =
            when (
                roots.classify(
                    failure,
                    part?.rootId ?: row.rootId,
                )
            ) {
                StorageFailure.TOO_LARGE -> {
                    TransferResult.Permanent(DownloadError.STORAGE_UNAVAILABLE, null)
                }

                StorageFailure.IO -> {
                    TransferResult.Transient(DownloadError.NETWORK_IO, null, null)
                }

                StorageFailure.FULL -> {
                    TransferResult.Wait(
                        WaitReason.STORAGE,
                        DownloadError.STORAGE_FULL,
                        clock.now() + STORAGE_WAIT_MS,
                    )
                }

                StorageFailure.UNAVAILABLE -> {
                    TransferResult.Wait(
                        WaitReason.STORAGE,
                        DownloadError.STORAGE_UNAVAILABLE,
                        clock.now() + STORAGE_WAIT_MS,
                    )
                }
            }

        suspend fun finish(end: TransferEnd) {
            val current = dao.byEpisode(row.episodeId) ?: return
            if (current.runnerToken != token) return
            val intent = (end as? TransferEnd.Stopped)?.intent
            if (intent is StopIntent.Cancel || intent is StopIntent.Delete) {
                val reason = if (intent is StopIntent.Cancel) intent.reason else (intent as StopIntent.Delete).reason
                val removal =
                    row.copy(
                        rootId = part?.rootId ?: row.rootId,
                        tempPath = part?.path ?: row.tempPath,
                        relativePath = completed?.relativePath ?: row.relativePath,
                        finalUri = completed?.finalUri ?: row.finalUri,
                    )
                part?.close()
                controllerFiles.prepareRemoval(listOf(removal))
                db.withWriteTransaction {
                    if (dao.removeOwned(row.episodeId, token) == 1 &&
                        reason == DeleteReason.USER
                    ) {
                        db.episodeStateDao().ensureAll(listOf(row.episodeId), clock.now())
                        db.episodeStateDao().setDismissed(listOf(row.episodeId), clock.now())
                    }
                }
                controllerFiles.finishRemoval(listOf(removal))
                return
            }
            val length =
                completed?.complete?.total ?: try {
                    fileSystem.stat(row.rootId, row.tempPath ?: DownloadPaths.part(row.episodeId))?.bytes
                        ?: row.downloadedBytes
                } catch (_: IOException) {
                    row.downloadedBytes
                }
            var final = row.copy(downloadedBytes = length, runnerToken = null, nextAttemptAt = null)
            final =
                when (end) {
                    is TransferEnd.Stopped -> stopped(final, end.intent)
                    is TransferEnd.Finished -> resultRow(final, end.result)
                    TransferEnd.Unknown -> final.copy(state = DownloadState.FAILED, lastError = DownloadError.UNKNOWN)
                }
            if (dao.transition(final, state, token) == 0) return
            if (final.state == DownloadState.COMPLETED) {
                index.put(final.episodeId, checkNotNull(final.finalUri))
                onCompleted(final)
            }
            if (final.state == DownloadState.QUEUED) {
                // Policy/lane may have changed while this owner was running.
                scheduler.ensureScheduled(dao.byEpisode(row.episodeId)?.lane ?: final.lane)
            }
        }

        private fun stopped(
            row: DownloadEntity,
            intent: StopIntent,
        ): DownloadEntity =
            when (intent) {
                StopIntent.Pause -> {
                    row.copy(
                        state = DownloadState.PAUSED,
                        waitReason = WaitReason.NONE,
                        lastStopReason = DownloadStopReasons.USER_PAUSE,
                    )
                }

                is StopIntent.Requeue -> {
                    row.copy(
                        state = DownloadState.QUEUED,
                        waitReason = intent.waitReason,
                        lastStopReason = intent.stopReason,
                    )
                }

                is StopIntent.RunnerStopped -> {
                    val paused =
                        intent.mode == RunnerStopMode.PAUSE ||
                            intent.platformStopReason == DownloadStopReasons.USER_TASK_MANAGER
                    row.copy(
                        state = if (paused) DownloadState.PAUSED else DownloadState.QUEUED,
                        waitReason = if (paused) WaitReason.NONE else WaitReason.SYSTEM,
                        lastError = DownloadError.CANCELLED_BY_SYSTEM,
                        lastStopReason = intent.platformStopReason,
                    )
                }

                else -> {
                    error("Removal handled separately")
                }
            }

        private suspend fun resultRow(
            row: DownloadEntity,
            result: TransferResult,
        ): DownloadEntity =
            when (result) {
                is TransferResult.Complete -> {
                    val adopted = checkNotNull(completed)
                    row.copy(
                        state = DownloadState.COMPLETED,
                        waitReason = WaitReason.NONE,
                        relativePath = adopted.relativePath,
                        finalUri = adopted.finalUri,
                        totalBytes = result.total,
                        downloadedBytes = result.total,
                        completedAt = clock.now(),
                        tempPath = null,
                        lastError = null,
                        lastHttpStatus = null,
                        lastStopReason = null,
                        attempt = 0,
                        etag = result.etag,
                        lastModified = result.lastModified,
                        mimeType = result.mimeType,
                    )
                }

                is TransferResult.Permanent -> {
                    row.copy(
                        state = DownloadState.FAILED,
                        waitReason = WaitReason.NONE,
                        lastError = result.error,
                        lastHttpStatus = result.httpStatus,
                    )
                }

                is TransferResult.Transient -> {
                    if (result.error == DownloadError.SIZE_MISMATCH && !restartBudgetExhausted) {
                        part?.delete()
                        val failures = row.integrityFailures + 1
                        row.copy(
                            state =
                                if (failures <
                                    MAX_INTEGRITY_FAILURES
                                ) {
                                    DownloadState.QUEUED
                                } else {
                                    DownloadState.FAILED
                                },
                            waitReason = WaitReason.NONE,
                            integrityFailures = failures,
                            downloadedBytes = 0,
                            etag = null,
                            lastModified = null,
                            lastError = result.error,
                            lastHttpStatus = result.httpStatus,
                        )
                    } else {
                        backoff(row, result.error, result.httpStatus, null)
                    }
                }

                is TransferResult.Wait -> {
                    if (result.countsAsAttempt) {
                        backoff(row, result.error, result.httpStatus, result.untilMs)
                    } else {
                        row.copy(
                            state = DownloadState.QUEUED,
                            waitReason = result.reason,
                            nextAttemptAt = result.untilMs?.coerceAtMost(clock.now() + MAX_BACKOFF_MS),
                            lastError = result.error,
                            lastHttpStatus = result.httpStatus,
                        )
                    }
                }

                is TransferResult.Restart -> {
                    error("Restarts are bounded in the attempt")
                }
            }

        private fun backoff(
            row: DownloadEntity,
            error: DownloadError?,
            status: Int?,
            until: Long?,
        ): DownloadEntity {
            val attempt = row.attempt + 1
            val exhausted = attempt >= MAX_ATTEMPTS
            val base = BASE_BACKOFF_MS * 2.0.pow(attempt.coerceAtMost(MAX_ATTEMPTS))
            val delay = base.coerceAtMost(MAX_BACKOFF_MS.toDouble()) * jitter().coerceIn(MIN_JITTER, MAX_JITTER)
            val next = (until ?: (clock.now() + delay.toLong())).coerceAtMost(clock.now() + MAX_BACKOFF_MS)
            return row.copy(
                state = if (exhausted) DownloadState.FAILED else DownloadState.QUEUED,
                waitReason = if (exhausted) WaitReason.NONE else WaitReason.BACKOFF,
                attempt = attempt,
                nextAttemptAt = if (exhausted) null else next,
                lastError = error,
                lastHttpStatus = status,
            )
        }
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val MAX_CANDIDATE_PAGES = 10
        const val MAX_RESTARTS = 2
        const val MAX_ATTEMPTS = 8
        const val MAX_INTEGRITY_FAILURES = 2
        const val BASE_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 6L * 60 * 60 * 1000
        const val CLOCK_SKEW_LIMIT_MS = 15L * 60 * 60 * 1000
        const val STORAGE_WAIT_MS = 30L * 60 * 1000
        const val MAX_IN_RUNNER_RETRY_MS = 30_000L
        const val UNKNOWN_RUNNER_STOP_REASON = 0
        const val MIN_JITTER = 0.8
        const val MAX_JITTER = 1.2
        val RETRY_DELAYS = longArrayOf(2_000, 8_000, 30_000)
    }
}
