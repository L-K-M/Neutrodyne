// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.DownloadEntryRow
import ch.lkmc.neutrodyne.core.database.DownloadSourceRow
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.domain.DownloadRootRegistration
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.domain.SettingsRepository
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.ManualMeteredPolicy
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.download.api.DeleteReason
import ch.lkmc.neutrodyne.download.api.DownloadController
import ch.lkmc.neutrodyne.download.api.DownloadEntry
import ch.lkmc.neutrodyne.download.api.DownloadNotice
import ch.lkmc.neutrodyne.download.api.DownloadStatus
import ch.lkmc.neutrodyne.download.api.DownloadsOverview
import ch.lkmc.neutrodyne.download.api.FolderRejectReason
import ch.lkmc.neutrodyne.download.api.FolderRootResult
import ch.lkmc.neutrodyne.download.api.RejectReason
import ch.lkmc.neutrodyne.download.api.RequestResult
import ch.lkmc.neutrodyne.download.api.RootChangeMode
import ch.lkmc.neutrodyne.download.api.RootChangeResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Unbound until the engine/files owners supply the shared mutex, stop/join and disk actions.
 * stopTransfer must await the token owner's final commit without acquiring claimMutex. */
internal class DownloadControllerImpl(
    private val db: NeutrodyneDatabase,
    private val settings: SettingsRepository,
    private val network: NetworkMonitor,
    private val clock: Clock,
    private val roots: StorageRoots,
    private val scheduler: DownloadScheduler,
    private val claimMutex: Mutex,
    private val stopTransfer: suspend (Long, StopIntent) -> Unit,
    private val files: DownloadControllerFiles,
    private val mediaLookup: PlaybackMediaLookup,
    private val askNotificationPermission: suspend () -> Boolean,
    private val activity: Flow<Long> = flowOf(0L),
    private val hasActiveTransfers: () -> Boolean = { false },
    private val rootRegistration: DownloadRootRegistration? = null,
) : DownloadController {
    private val dao = db.downloadDao()

    override suspend fun request(
        episodeIds: List<Long>,
        trigger: DownloadLane,
        allowMetered: Boolean?,
    ): RequestResult =
        claimMutex.withLock { requestLocked(episodeIds.distinct(), trigger, allowMetered, RequestMode.NORMAL) }

    private suspend fun requestLocked(
        ids: List<Long>,
        trigger: DownloadLane,
        explicitMetered: Boolean?,
        mode: RequestMode,
    ): RequestResult {
        if (trigger !=
            DownloadLane.MANUAL
        ) {
            return RequestResult.Queued(0, 0, ids.associateWith { RejectReason.UNAVAILABLE }, false)
        }
        val sources = db.episodeDao().downloadSources(ids).associateBy { it.episodeId }
        val rejected = mutableMapOf<Long, RejectReason>()
        val accepted = mutableListOf<Source>()
        for (id in ids) {
            val row = sources[id]
            if (row == null) {
                rejected[id] = RejectReason.EPISODE_GONE
                continue
            }
            // Reuse playback's ranked, validated alternate selection rather than a second JSON parser.
            val alternate = if (row.isVideo) mediaLookup.get(id)?.audioAlternate else null
            val source =
                Source(
                    row,
                    alternate?.url ?: row.enclosureUrl,
                    alternate?.type ?: row.enclosureType,
                    alternate?.length ?: row.enclosureLength,
                )
            val reason = source.rejection()
            if (reason != null) rejected[id] = reason else accepted += source
        }
        val existing = accepted.associate { it.row.episodeId to dao.byEpisode(it.row.episodeId) }
        val needsConsent =
            accepted.filter { source ->
                val row = existing[source.row.episodeId]
                row == null || row.state in REQUEUE_STATES ||
                    (row.lane == DownloadLane.AUTO && row.state != DownloadState.COMPLETED)
            }
        val policy = settings.get(DownloadSettingKeys.MANUAL_METERED)
        val status = network.status.value
        if (explicitMetered == null && policy == ManualMeteredPolicy.ASK && status.isConnected && status.isMetered &&
            needsConsent.isNotEmpty()
        ) {
            val sizes = needsConsent.map { it.estimate() }
            return RequestResult.NeedsMeteredDecision(
                sizes.size,
                sizes.filterNotNull().fold(0L) { total, size -> total + size.coerceAtMost(Long.MAX_VALUE - total) },
                sizes.count { it == null },
            )
        }
        val metered = explicitMetered ?: (policy == ManualMeteredPolicy.ALWAYS)
        val root = if (accepted.any { existing[it.row.episodeId] == null }) roots.current() else null
        val now = clock.now()
        var queued = 0
        var present = 0
        db.withWriteTransaction {
            for ((index, source) in accepted.withIndex()) {
                val id = source.row.episodeId
                val current = dao.byEpisode(id)
                if (current?.state == DownloadState.COMPLETED) {
                    present++
                    continue
                }
                queued++
                if (current == null) {
                    dao.insertIfAbsent(
                        DownloadEntity(
                            episodeId = id,
                            lane = DownloadLane.MANUAL,
                            state = DownloadState.QUEUED,
                            priority = mode.priority,
                            requestedAt = now + index,
                            sourceKind = SourceKind.RSS_ENCLOSURE,
                            sourceRef = checkNotNull(source.url),
                            rootId = checkNotNull(root),
                            allowMetered = metered,
                            estimatedBytes = source.estimate(),
                        ),
                    )
                    continue
                }
                val restart = current.state in REQUEUE_STATES
                val updated =
                    current.requeuedForRequest(source).copy(
                        lane = DownloadLane.MANUAL,
                        priority = maxOf(current.priority, mode.priority),
                        requireCharging = false,
                        allowMetered =
                            if (explicitMetered != null || restart ||
                                current.lane == DownloadLane.AUTO
                            ) {
                                metered
                            } else {
                                current.allowMetered
                            },
                    )
                if (current.runnerToken == null) {
                    dao.updateUnclaimed(updated, current.state)
                } else {
                    dao.updatePolicy(id, updated.lane, updated.priority, updated.allowMetered, updated.requireCharging)
                }
            }
            accepted.map { it.row.episodeId }.chunked(BIND_CHUNK).forEach { db.episodeStateDao().clearDismissed(it) }
        }
        if (queued > 0) scheduler.ensureScheduled(DownloadLane.MANUAL)
        return RequestResult.Queued(queued, present, rejected, queued > 0 && askNotificationPermission())
    }

    override suspend fun promote(episodeId: Long): RequestResult =
        claimMutex.withLock {
            requestLocked(listOf(episodeId), DownloadLane.MANUAL, null, RequestMode.NEXT)
        }

    override suspend fun pause(episodeIds: Collection<Long>) =
        claimMutex.withLock {
            for (id in episodeIds.distinct()) {
                val row = dao.byEpisode(id) ?: continue
                if (row.runnerToken != null && row.state in ACTIVE_STATES) {
                    stopTransfer(id, StopIntent.Pause)
                } else if (row.state == DownloadState.QUEUED) {
                    dao.updateUnclaimed(
                        row.copy(
                            state = DownloadState.PAUSED,
                            waitReason = WaitReason.NONE,
                            lastStopReason = DownloadStopReasons.USER_PAUSE,
                        ),
                        row.state,
                    )
                }
            }
        }

    override suspend fun resume(episodeIds: Collection<Long>) =
        changeRows(episodeIds) { row ->
            if (row.state == DownloadState.PAUSED ||
                (row.state == DownloadState.QUEUED && row.waitReason == WaitReason.NEEDS_FOREGROUND)
            ) {
                row.copy(state = DownloadState.QUEUED, waitReason = WaitReason.NONE, nextAttemptAt = null)
            } else {
                row
            }
        }

    override suspend fun retry(episodeIds: Collection<Long>) =
        changeRows(episodeIds) { row ->
            when {
                row.state == DownloadState.FAILED -> {
                    row.copy(
                        state = DownloadState.QUEUED,
                        waitReason = WaitReason.NONE,
                        attempt = 0,
                        integrityFailures = 0,
                        lastError = null,
                        lastHttpStatus = null,
                        nextAttemptAt = null,
                    )
                }

                row.state == DownloadState.QUEUED && row.waitReason == WaitReason.BACKOFF -> {
                    row.copy(waitReason = WaitReason.NONE, nextAttemptAt = null)
                }

                else -> {
                    row
                }
            }
        }

    override suspend fun setAllowMetered(
        episodeIds: Collection<Long>,
        allowed: Boolean,
    ) = claimMutex.withLock {
        val lanes = mutableSetOf<DownloadLane>()
        for (id in episodeIds.distinct()) {
            val row = dao.byEpisode(id) ?: continue
            if (row.state == DownloadState.COMPLETED) continue
            dao.updatePolicy(id, row.lane, row.priority, allowed, row.requireCharging)
            if (row.state == DownloadState.QUEUED && row.waitReason == WaitReason.UNMETERED_NETWORK && allowed) {
                dao.markWait(listOf(id), WaitReason.NONE, row.nextAttemptAt)
            }
            lanes += row.lane
        }
        lanes.forEach { scheduler.ensureScheduled(it) }
    }

    private suspend fun changeRows(
        ids: Collection<Long>,
        transform: (DownloadEntity) -> DownloadEntity,
    ) = claimMutex.withLock {
        val lanes = mutableSetOf<DownloadLane>()
        db.withWriteTransaction {
            for (id in ids.distinct()) {
                val row = dao.byEpisode(id) ?: continue
                val updated = transform(row)
                if (dao.updateUnclaimed(updated, row.state) == 1 &&
                    updated.state == DownloadState.QUEUED
                ) {
                    lanes += updated.lane
                }
            }
        }
        lanes.forEach { scheduler.ensureScheduled(it) }
    }

    override suspend fun cancel(episodeIds: Collection<Long>) =
        remove(episodeIds, DeleteReason.USER, RemovalMode.CANCEL)

    override suspend fun delete(
        episodeIds: Collection<Long>,
        reason: DeleteReason,
    ) = remove(episodeIds, reason, RemovalMode.DELETE)

    private suspend fun remove(
        ids: Collection<Long>,
        reason: DeleteReason,
        mode: RemovalMode,
    ) = claimMutex.withLock {
        val rows = mutableListOf<DownloadEntity>()
        for (id in ids.distinct()) {
            val row = dao.byEpisode(id) ?: continue
            if (mode == RemovalMode.CANCEL && row.state == DownloadState.COMPLETED) continue
            if (row.runnerToken != null) {
                stopTransfer(
                    id,
                    if (mode ==
                        RemovalMode.CANCEL
                    ) {
                        StopIntent.Cancel(reason)
                    } else {
                        StopIntent.Delete(reason)
                    },
                )
            }
            val stopped = dao.byEpisode(id) ?: continue
            check(stopped.runnerToken == null) { "Transfer owner did not finish before removal: $id" }
            if (mode == RemovalMode.CANCEL && stopped.state == DownloadState.COMPLETED) continue
            rows += stopped
        }
        if (rows.isEmpty()) return@withLock
        files.prepareRemoval(rows)
        db.withWriteTransaction {
            for (row in rows) {
                if (dao.removeUnclaimed(listOf(row.episodeId)) == 0) continue
                if (reason == DeleteReason.USER) {
                    db.episodeStateDao().ensureAll(listOf(row.episodeId), clock.now())
                    db.episodeStateDao().setDismissed(listOf(row.episodeId), clock.now())
                }
            }
        }
        files.finishRemoval(rows)
    }

    override suspend fun pauseAll() = pause(dao.allRows().map { it.episodeId })

    override suspend fun resumeAll() = resume(dao.allRows().map { it.episodeId })

    override suspend fun reportFileMissing(episodeId: Long) = files.reportFileMissing(episodeId)

    override fun observe(episodeId: Long): Flow<DownloadStatus?> =
        dao.observeLiveFor(listOf(episodeId)).map { dao.byEpisode(episodeId)?.status() }.distinctUntilChanged()

    override fun observeAll(): Flow<DownloadsOverview> =
        combine(dao.observeEntries(), files.observeStorage()) { rows, storage ->
            val ordered =
                rows.sortedWith(
                    compareByDescending<DownloadEntryRow> { it.download.priority }
                        .thenBy { it.download.requestedAt }
                        .thenBy { it.download.episodeId },
                )
            DownloadsOverview(
                ordered.filter { it.download.state !in FINISHED_STATES }.map { it.entry() },
                rows
                    .filter { it.download.state == DownloadState.COMPLETED }
                    .sortedWith(
                        compareByDescending<DownloadEntryRow> { it.download.completedAt }
                            .thenByDescending { it.download.episodeId },
                    ).map { it.entry() },
                rows
                    .filter {
                        it.download.state in
                            setOf(
                                DownloadState.FAILED,
                                DownloadState.MISSING,
                            )
                    }.map { it.entry() },
                storage,
                buildSet {
                    if (storage.autoBlockedByCap) add(DownloadNotice.CAP_REACHED)
                    if (storage.orphanBytes > 0) add(DownloadNotice.ORPHAN_FILES)
                },
            )
        }.distinctUntilChanged()

    override fun observeStorage() = files.observeStorage()

    override fun observeFailedCount() = dao.observeFailedCount().distinctUntilChanged()

    override fun observeBusy(): Flow<Boolean> =
        combine(dao.observeEntries(), network.status, activity) { rows, status, _ ->
            hasActiveTransfers() ||
                rows.any { entry ->
                    val row = entry.download
                    row.state in ACTIVE_STATES || (
                        row.state == DownloadState.QUEUED && row.runnerToken == null &&
                            row.waitReason !in
                            setOf(
                                WaitReason.STORAGE,
                                WaitReason.NEEDS_FOREGROUND,
                                WaitReason.CHARGING,
                            ) &&
                            (row.nextAttemptAt?.let { it <= clock.now() } != false) && status.isConnected &&
                            (row.allowMetered || !status.isMetered)
                    )
                }
        }.distinctUntilChanged()

    override suspend fun storageRoots() = roots.list()

    override suspend fun registerFolderRoot(absolutePath: String) =
        rootRegistration?.registerFolderRoot(absolutePath) ?: FolderRootResult.Rejected(FolderRejectReason.UNSUPPORTED)

    override suspend fun changeRoot(
        rootId: String,
        mode: RootChangeMode,
    ): RootChangeResult =
        rootRegistration?.changeRoot(rootId, mode)
            ?: if (mode ==
                RootChangeMode.MOVE_EXISTING
            ) {
                RootChangeResult.Unsupported
            } else {
                files.changeRoot(rootId, mode)
            }

    override suspend fun deleteOrphanFiles() = files.deleteOrphanFiles()

    override suspend fun shareableFile(episodeId: Long) = files.shareableFile(episodeId)

    private fun DownloadEntity.requeuedForRequest(source: Source): DownloadEntity =
        when (state) {
            DownloadState.PAUSED -> {
                copy(state = DownloadState.QUEUED, waitReason = WaitReason.NONE, nextAttemptAt = null)
            }

            DownloadState.FAILED -> {
                copy(
                    state = DownloadState.QUEUED,
                    waitReason = WaitReason.NONE,
                    nextAttemptAt = null,
                    attempt = 0,
                    integrityFailures = 0,
                    lastError = null,
                    lastHttpStatus = null,
                )
            }

            DownloadState.MISSING -> {
                copy(
                    state = DownloadState.QUEUED,
                    waitReason = WaitReason.NONE,
                    nextAttemptAt = null,
                    sourceRef = checkNotNull(source.url),
                    tempPath = null,
                    relativePath = null,
                    finalUri = null,
                    totalBytes = null,
                    downloadedBytes = 0,
                    etag = null,
                    lastModified = null,
                    mimeType = null,
                    resolvedItag = null,
                    completedAt = null,
                    attempt = 0,
                    integrityFailures = 0,
                    lastError = null,
                    lastHttpStatus = null,
                    estimatedBytes = source.estimate(),
                )
            }

            else -> {
                this
            }
        }

    private data class Source(
        val row: DownloadSourceRow,
        val url: String?,
        val mime: String?,
        val length: Long?,
    ) {
        fun rejection(): RejectReason? {
            val extension =
                url
                    ?.substringBefore('?')
                    ?.substringBefore('#')
                    ?.substringAfterLast('.')
                    ?.lowercase()
            val type = mime?.substringBefore(';')?.trim()?.lowercase()
            return when {
                row.sourceType != SourceType.RSS -> RejectReason.YOUTUBE_NOT_SUPPORTED
                row.availability != Availability.AVAILABLE -> RejectReason.UNAVAILABLE
                url.isNullOrBlank() -> RejectReason.NOT_DOWNLOADABLE
                type in HLS_TYPES || extension in HLS_EXTENSIONS -> RejectReason.UNSUPPORTED_STREAM
                else -> null
            }
        }

        fun estimate(): Long? =
            length?.takeIf { it >= MIN_ENCLOSURE_BYTES }
                ?: row.durationMs?.takeIf { it > 0 }?.let {
                    it.coerceAtMost(Long.MAX_VALUE / BYTES_PER_MILLISECOND) *
                        BYTES_PER_MILLISECOND
                }
    }

    private fun DownloadEntity.status() =
        DownloadStatus(
            episodeId,
            lane,
            state,
            waitReason,
            downloadedBytes,
            totalBytes,
            estimatedBytes,
            null,
            nextAttemptAt,
            lastError,
            lastHttpStatus,
            allowMetered,
            requireCharging,
            sourceKind,
            completedAt,
        )

    private fun DownloadEntryRow.entry() =
        DownloadEntry(
            download.status(),
            podcastId,
            podcastTitle,
            episodeTitle,
            ArtworkRef(artworkKey, artworkUrl, artworkVersion),
            sortDate,
            playedAt != null,
            isFavorite,
        )

    private enum class RequestMode(
        val priority: Int,
    ) {
        NORMAL(MANUAL_PRIORITY),
        NEXT(NEXT_PRIORITY),
    }

    private enum class RemovalMode { CANCEL, DELETE }

    private companion object {
        const val MANUAL_PRIORITY = 100
        const val NEXT_PRIORITY = 200
        const val BIND_CHUNK = 500
        const val MIN_ENCLOSURE_BYTES = 100_000L
        const val BYTES_PER_MILLISECOND = 16L
        val HLS_TYPES =
            setOf(
                "application/x-mpegurl",
                "application/vnd.apple.mpegurl",
                "application.x-mpegurl",
                "audio/x-mpegurl",
                "audio/mpegurl",
            )
        val HLS_EXTENSIONS = setOf("m3u8", "m3u")
        val REQUEUE_STATES = setOf(DownloadState.PAUSED, DownloadState.FAILED, DownloadState.MISSING)
        val ACTIVE_STATES = setOf(DownloadState.RESOLVING, DownloadState.DOWNLOADING, DownloadState.VERIFYING)
        val FINISHED_STATES = setOf(DownloadState.COMPLETED, DownloadState.FAILED, DownloadState.MISSING)
    }
}
