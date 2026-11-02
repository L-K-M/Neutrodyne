// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers.IO
import ch.lkmc.neutrodyne.core.common.PlatformKind
import ch.lkmc.neutrodyne.core.database.AutoDownloadCandidate
import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.DownloadSourceRow
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.datastore.SettingStore
import ch.lkmc.neutrodyne.core.domain.AutoDownloadPlanner
import ch.lkmc.neutrodyne.core.domain.EffectiveSettingsResolver
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.EffectiveAutoDownload
import ch.lkmc.neutrodyne.core.model.NetworkPolicy
import ch.lkmc.neutrodyne.core.model.SettingSource
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.WaitReason
import ch.lkmc.neutrodyne.core.model.settings.DownloadSettingKeys
import ch.lkmc.neutrodyne.download.api.DeleteReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Unbound W2 owner. W5/W7 supply the shared claim mutex and trigger this after reconciliation.
 * The planner mutex serializes full passes; claimMutex also excludes manual commands and claims.
 * Stop/join and file journaling stay outside write transactions, as in the manual controller. */
internal class AutoDownloadPlannerImpl(
    private val db: NeutrodyneDatabase,
    private val resolver: EffectiveSettingsResolver,
    private val quota: DownloadQuota,
    private val device: SettingStore,
    private val clock: Clock,
    private val roots: StorageRoots,
    private val scheduler: DownloadScheduler,
    private val claimMutex: Mutex,
    private val stopTransfer: suspend (Long, StopIntent) -> Unit,
    private val files: DownloadControllerFiles,
    private val media: PlaybackMediaLookup,
    private val platform: PlatformKind,
    @param:Dispatcher(IO) private val dispatcher: CoroutineDispatcher,
) : AutoDownloadPlanner {
    private val plannerMutex = Mutex()
    private val dao = db.downloadDao()

    override suspend fun plan(podcastIds: Collection<Long>) =
        withContext(dispatcher) {
            plannerMutex.withLock {
                val fullPolicy = resolver.observeAutoDownload().first()
                val watermarks = watermarkPass(fullPolicy)
                val requested = podcastIds.toSet()
                val policies = fullPolicy.filterKeys { it in requested }
                claimMutex.withLock {
                    val wanted = policies.mapValues { (id, policy) -> candidates(id, policy, watermarks[id]) }
                    removeUnwanted(policies, wanted)
                    val pending = mutableListOf<Pending>()
                    for ((podcastId, policy) in policies) {
                        if (!policy.enabled || policy.source == SettingSource.NotSupported) continue
                        val sources = db.episodeDao().downloadSources(wanted.getValue(podcastId).map { it.id })
                        for (source in sources) {
                            // YouTube transfers belong to M9a/MD3, even if a future resolver enables them.
                            if (source.sourceType != SourceType.RSS) continue
                            val enclosure = source.enclosureUrl ?: continue
                            val alternate = if (source.isVideo) media.get(source.episodeId)?.audioAlternate else null
                            pending +=
                                Pending(
                                    podcastId,
                                    source,
                                    alternate?.url ?: enclosure,
                                    downloadEstimate(alternate?.length ?: source.enclosureLength, source.durationMs),
                                    policy,
                                )
                        }
                    }
                    val root = if (pending.isEmpty()) null else roots.current()
                    quota.resetAdmission()
                    db.withWriteTransaction {
                        // Playback/sync can change eligibility while source lookup suspends. Refresh it
                        // under the writer before inserts so a new tombstone cannot be overwritten.
                        val stillWanted =
                            policies
                                .flatMap { (id, policy) ->
                                    candidates(id, policy, watermarks[id]).map { it.id }
                                }.toSet()
                        val eligible = pending.filter { it.source.episodeId in stillWanted }
                        for (item in eligible) updateExisting(item)
                        val newItems = eligible.filter { dao.byEpisode(it.source.episodeId) == null }
                        val rows = dao.allRows().toMutableList()
                        val now = clock.now()
                        for ((index, item) in roundRobin(newItems).withIndex()) {
                            if (!quota.admitsAuto(rows, item.estimate)) break
                            val row =
                                DownloadEntity(
                                    episodeId = item.source.episodeId,
                                    lane = DownloadLane.AUTO,
                                    state = DownloadState.QUEUED,
                                    priority = AUTO_PRIORITY,
                                    requestedAt = now + index,
                                    sourceKind = SourceKind.RSS_ENCLOSURE,
                                    sourceRef = item.url,
                                    rootId = checkNotNull(root),
                                    estimatedBytes = item.estimate,
                                    allowMetered = allowMetered(item.policy),
                                    requireCharging = requireCharging(item.policy),
                                )
                            if (dao.insertIfAbsent(row) != -1L) rows += row
                        }
                    }
                }
                // Scheduler may take a lane mutex: never call it while holding claimMutex.
                if (dao.queuedNeeds(DownloadLane.AUTO, clock.now()).queued > 0) {
                    scheduler.ensureScheduled(DownloadLane.AUTO)
                }
            }
        }

    private suspend fun watermarkPass(policies: Map<Long, EffectiveAutoDownload>): Map<Long, Long?> {
        val passStart = clock.now()
        val baseline = device.get(DownloadSettingKeys.PLANNER_BASELINE_AT)
        val result = mutableMapOf<Long, Long?>()
        db.withWriteTransaction {
            val changed = mutableMapOf<Long?, MutableList<Long>>()
            for (row in db.podcastDao().autoDownloadWatermarks(policies.keys)) {
                val policy = policies.getValue(row.id)
                val next =
                    when {
                        policy.source == SettingSource.NotSupported -> row.autoDownloadEligibleAfter
                        !policy.enabled -> null
                        row.autoDownloadEligibleAfter != null -> row.autoDownloadEligibleAfter
                        row.subscribedAt > baseline -> row.subscribedAt
                        else -> passStart
                    }
                result[row.id] = next
                if (next != row.autoDownloadEligibleAfter) changed.getOrPut(next) { mutableListOf() } += row.id
            }
            for ((value, ids) in changed) db.podcastDao().setAutoDownloadWatermarks(ids, value)
        }
        // A failed device write leaves the previous baseline. Committed watermarks survive a kill.
        device.set(DownloadSettingKeys.PLANNER_BASELINE_AT, passStart)
        return result
    }

    private suspend fun candidates(
        id: Long,
        policy: EffectiveAutoDownload,
        watermark: Long?,
    ): List<AutoDownloadCandidate> {
        if (!policy.enabled || policy.source == SettingSource.NotSupported || watermark == null) return emptyList()
        return dao.autoCandidates(id, watermark, policy.keepLatest, policy.includeVideo, youtubeDownloads = false)
    }

    private suspend fun removeUnwanted(
        policies: Map<Long, EffectiveAutoDownload>,
        wanted: Map<Long, List<AutoDownloadCandidate>>,
    ) {
        val removable = mutableListOf<DownloadEntity>()
        for (ids in policies.keys.toList().chunked(BIND_CHUNK)) {
            val sources = db.episodeDao().downloadSources(dao.downloadedEpisodeIds(ids)).associateBy { it.episodeId }
            for ((id, source) in sources) {
                val policy = policies.getValue(source.podcastId)
                if (policy.source == SettingSource.NotSupported) continue
                if (wanted.getValue(source.podcastId).any { it.id == id }) continue
                val row = dao.byEpisode(id) ?: continue
                if (row.lane != DownloadLane.AUTO || row.state in USER_RETAINED_STATES) continue
                if (row.runnerToken != null) stopTransfer(id, StopIntent.Delete(DeleteReason.LIFECYCLE))
                val stopped = dao.byEpisode(id) ?: continue
                if (stopped.lane != DownloadLane.AUTO || stopped.state in USER_RETAINED_STATES) continue
                check(stopped.runnerToken == null) { "Transfer owner did not finish before removal: $id" }
                removable += stopped
            }
        }
        if (removable.isEmpty()) return
        files.prepareRemoval(removable)
        db.withWriteTransaction {
            removable.map { it.episodeId }.chunked(BIND_CHUNK).forEach { dao.removeUnclaimed(it) }
        }
        files.finishRemoval(removable)
    }

    private suspend fun updateExisting(item: Pending) {
        val row = dao.byEpisode(item.source.episodeId) ?: return
        if (row.lane != DownloadLane.AUTO || row.runnerToken != null) return
        val updated =
            when {
                row.state == DownloadState.QUEUED -> {
                    row.copy(
                        allowMetered = allowMetered(item.policy),
                        requireCharging = requireCharging(item.policy),
                    )
                }

                row.state == DownloadState.MISSING && row.lastError != DownloadError.STORAGE_UNAVAILABLE -> {
                    row.copy(
                        state = DownloadState.QUEUED,
                        waitReason = WaitReason.NONE,
                        sourceRef = item.url,
                        tempPath = null,
                        relativePath = null,
                        finalUri = null,
                        totalBytes = null,
                        downloadedBytes = 0,
                        estimatedBytes = item.estimate,
                        etag = null,
                        lastModified = null,
                        mimeType = null,
                        resolvedItag = null,
                        completedAt = null,
                        attempt = 0,
                        integrityFailures = 0,
                        lastError = null,
                        lastHttpStatus = null,
                        nextAttemptAt = null,
                        allowMetered = allowMetered(item.policy),
                        requireCharging = requireCharging(item.policy),
                    )
                }

                else -> {
                    return
                }
            }
        dao.updateUnclaimed(updated, row.state)
    }

    private fun allowMetered(policy: EffectiveAutoDownload) =
        platform == PlatformKind.DESKTOP || policy.network == NetworkPolicy.ANY

    private fun requireCharging(policy: EffectiveAutoDownload) =
        platform != PlatformKind.DESKTOP && policy.requireCharging

    private fun roundRobin(items: List<Pending>): List<Pending> {
        val groups =
            items
                .groupBy { it.podcastId }
                .values
                .map { group ->
                    group.sortedWith(
                        compareByDescending<Pending> { it.source.sortDate }.thenByDescending { it.source.episodeId },
                    )
                }.sortedWith(
                    compareByDescending<List<Pending>> { it.first().source.sortDate }.thenBy { it.first().podcastId },
                )
        return buildList {
            for (index in 0 until (groups.maxOfOrNull { it.size } ?: 0)) {
                for (group in groups) group.getOrNull(index)?.let { add(it) }
            }
        }
    }

    private data class Pending(
        val podcastId: Long,
        val source: DownloadSourceRow,
        val url: String,
        val estimate: Long?,
        val policy: EffectiveAutoDownload,
    )

    private companion object {
        const val AUTO_PRIORITY = 0
        const val BIND_CHUNK = 500
        val USER_RETAINED_STATES = setOf(DownloadState.PAUSED, DownloadState.COMPLETED)
    }
}
