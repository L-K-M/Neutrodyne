// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.domain.ContextEligibility
import ch.lkmc.neutrodyne.core.domain.ContextRetention
import ch.lkmc.neutrodyne.core.domain.Departure
import ch.lkmc.neutrodyne.core.domain.PlayContextResolver
import ch.lkmc.neutrodyne.core.domain.PlaySessionStore
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.domain.PositionStore
import ch.lkmc.neutrodyne.core.domain.QueueRepository
import ch.lkmc.neutrodyne.core.domain.SessionStart
import ch.lkmc.neutrodyne.core.domain.StartGate
import ch.lkmc.neutrodyne.core.domain.StartMembership
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.PlayContextSpec
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import ch.lkmc.neutrodyne.download.api.LocalMediaIndex
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.UnplayableReason
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

sealed interface StartRequest {
    data class Episode(
        val episodeId: Long,
        val positionMs: Long? = null,
        /** Non-null means an external list start, replacing context even if current is queued. */
        val externalQueue: List<Long>? = null,
    ) : StartRequest

    data class Feed(
        val source: FeedSource,
        val filters: FeedFilters,
        val order: FeedOrder,
        val startEpisodeId: Long? = null,
        val positionMs: Long? = null,
        val membership: StartMembership = StartMembership.ANY,
    ) : StartRequest

    data object Resume : StartRequest
}

/** Read-only planning/gates precede all writes. Adapters bracket this call with planner own writes. */
@Inject
class PlayStarter(
    private val contexts: PlayContextResolver,
    private val queues: QueueRepository,
    private val sessions: PlaySessionStore,
    private val media: PlaybackMediaLookup,
    private val positions: PositionStore,
    private val capabilities: YouTubeCapabilitiesSource,
    private val localMedia: LocalMediaIndex,
    private val network: NetworkMonitor,
    private val clock: Clock,
    private val startPosition: StartPositionRule,
    private val playedRule: PlayedRule,
    private val gates: Set<StartGate> = emptySet(),
) {
    private data class Selection(
        val id: Long,
        val spec: PlayContextSpec?,
        val retention: ContextRetention,
        val anchorId: Long?,
        val explicitPositionMs: Long?,
        val alreadyCurrent: Boolean = false,
        val resumeOrigin: QueueOrigin? = null,
        val queueHead: List<Long> = emptyList(),
        val membership: StartMembership = StartMembership.ANY,
    )

    /** No pull-before-play in M4. MS3's UI-only pull belongs before this operation. */
    suspend fun start(
        request: StartRequest,
        loadedEpisodeId: Long? = null,
        captureDeparture: () -> Departure? = { null },
    ): StartOutcome =
        try {
            startChecked(request, loadedEpisodeId, captureDeparture)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            StartOutcome.Refused(PlayResult.ServiceUnavailable)
        }

    private suspend fun startChecked(
        request: StartRequest,
        loadedEpisodeId: Long?,
        captureDeparture: () -> Departure?,
    ): StartOutcome {
        val queue = queues.observeVirtualQueue(QueueWindowPlanner.CONTEXT_SIZE).first()
        val selected =
            when (request) {
                is StartRequest.Episode -> {
                    require(request.positionMs == null || request.positionMs >= 0)
                    require(request.externalQueue == null || request.externalQueue.size < 100)
                    val rest =
                        request.externalQueue
                            .orEmpty()
                            .distinct()
                            .filter { it != request.episodeId }
                    for (id in rest) {
                        require(id > 0)
                        val row =
                            media.get(id)
                                ?: return StartOutcome.Refused(PlayResult.NotPlayable(id, UnplayableReason.NoMedia))
                        row
                            .refusal(
                                capabilities.capabilities.value,
                            )?.let { return StartOutcome.Refused(PlayResult.NotPlayable(id, it)) }
                    }
                    val keep =
                        request.externalQueue == null && (
                            queue.current?.episodeId == request.episodeId ||
                                queue.upNext.any { it.episodeId == request.episodeId }
                        )
                    Selection(
                        request.episodeId,
                        null,
                        if (keep) ContextRetention.KEEP else ContextRetention.REPLACE,
                        null,
                        request.positionMs,
                        request.externalQueue == null && queue.current?.episodeId == request.episodeId,
                        queueHead = rest,
                    )
                }

                is StartRequest.Feed -> {
                    require(request.positionMs == null || request.positionMs >= 0)
                    val spec = contexts.spec(request.source, request.filters, request.order)
                    val queued = if (request.startEpisodeId == null) firstPlayable(queue) else null
                    val id = request.startEpisodeId ?: queued ?: contexts.startItem(spec)
                    if (id == null) return nothingToPlay(queue, spec)
                    Selection(
                        id,
                        spec,
                        ContextRetention.REPLACE,
                        if (queued == null) id else null,
                        request.positionMs,
                        membership = request.membership,
                    )
                }

                StartRequest.Resume -> {
                    val queued = if (queue.current == null) firstPlayable(queue) else null
                    val context =
                        if (queue.current == null && queued == null) {
                            firstPlayable(queue.contextTail)
                        } else {
                            null
                        }
                    val id = queue.current?.episodeId ?: queued ?: context
                    if (id == null) return nothingToPlay(queue, null)
                    Selection(
                        id,
                        null,
                        ContextRetention.KEEP,
                        null,
                        null,
                        queue.current != null,
                        if (context != null) QueueOrigin.CONTEXT else null,
                    )
                }
            }
        val item =
            media.get(selected.id)
                ?: return StartOutcome.Refused(PlayResult.NotPlayable(selected.id, UnplayableReason.NoMedia))
        val local = localMedia.localUriOrNull(selected.id)
        coreRefusal(item, local)?.let { return StartOutcome.Refused(it) }
        for (gate in gates) {
            gate.check(item, local)?.let { return StartOutcome.Refused(it) }
        }
        if (selected.alreadyCurrent && loadedEpisodeId == selected.id) {
            val latest =
                existingQueue(queue, selected.id)
                    ?: return StartOutcome.Refused(PlayResult.ServiceUnavailable)
            coreRefusal(item, localMedia.localUriOrNull(selected.id))?.let { return StartOutcome.Refused(it) }
            return StartOutcome.AlreadyCurrent(latest, selected.explicitPositionMs)
        }

        // Resolve progress before commit: a failed read must never leave a partly accepted start.
        val stored = positions.observe(selected.id).first()
        val startMs =
            selected.explicitPositionMs ?: startPosition.startPositionMs(
                stored?.positionMs,
                stored?.updatedAt,
                playedRule.duration(null, item.measuredDurationMs, item.durationMs),
                item.playedAt != null || stored?.playedAt != null,
                clock.now(),
            )
        if (selected.alreadyCurrent) {
            val latest =
                existingQueue(queue, selected.id)
                    ?: return StartOutcome.Refused(PlayResult.ServiceUnavailable)
            coreRefusal(item, localMedia.localUriOrNull(selected.id))?.let { return StartOutcome.Refused(it) }
            return StartOutcome.Committed(latest, startMs)
        }
        coreRefusal(item, localMedia.localUriOrNull(selected.id))?.let { return StartOutcome.Refused(it) }
        val outgoing = captureDeparture()?.takeUnless { it.episodeId == selected.id }
        val generation =
            if (selected.resumeOrigin == QueueOrigin.CONTEXT) {
                sessions.onTransition(outgoing, selected.id, QueueOrigin.CONTEXT, clock.now())
            } else {
                sessions.commitStart(
                    SessionStart(
                        selected.id,
                        selected.spec,
                        selected.retention,
                        selected.anchorId,
                        if (selected.anchorId != null) item.sortDate else null,
                        outgoing,
                        selected.queueHead,
                        selected.membership,
                    ),
                    clock.now(),
                )
            }
        val committed = sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
        // A concurrent restore must not be played as if it were our accepted selection.
        if (committed.generation != generation || committed.current?.episodeId != selected.id) {
            return StartOutcome.Refused(PlayResult.ServiceUnavailable)
        }
        return StartOutcome.Committed(committed, startMs)
    }

    private suspend fun existingQueue(
        before: VirtualQueue,
        episodeId: Long,
    ): VirtualQueue? {
        val latest = sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
        return latest.takeIf { it.generation == before.generation && it.current?.episodeId == episodeId }
    }

    private fun coreRefusal(
        item: PlaybackMediaInfo,
        localUri: String?,
    ): PlayResult? {
        item.refusal(capabilities.capabilities.value)?.let { return PlayResult.NotPlayable(item.episodeId, it) }
        if (localUri == null && !network.status.value.isConnected) return PlayResult.Offline

        return null
    }

    private suspend fun firstPlayable(queue: VirtualQueue): Long? = firstPlayable(queue.upNext)

    private suspend fun firstPlayable(items: List<QueueItem>): Long? {
        for (item in items) {
            val info = media.get(item.episodeId) ?: continue
            if (info.refusal(capabilities.capabilities.value) == null) return item.episodeId
        }
        return null
    }

    private suspend fun nothingToPlay(
        queue: VirtualQueue,
        spec: PlayContextSpec?,
    ): StartOutcome {
        if (capabilities.capabilities.value.inAppPlayback) return StartOutcome.Refused(PlayResult.NothingToPlay())
        val externalQueued =
            (queue.upNext + queue.contextTail).any {
                val info = media.get(it.episodeId)
                info?.availability == Availability.AVAILABLE &&
                    info.refusal(capabilities.capabilities.value) is UnplayableReason.YouTubeExternal
            }
        val externalContext =
            spec != null &&
                contexts.startItem(spec, ContextEligibility.INCLUDE_EXTERNAL_YOUTUBE) != null
        return StartOutcome.Refused(PlayResult.NothingToPlay(externalQueued || externalContext))
    }
}
