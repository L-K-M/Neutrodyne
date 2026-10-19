// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.playback

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.database.EpisodeStateEntity
import ch.lkmc.neutrodyne.core.database.FeedQueryBuilder
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PlaySessionEntity
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.domain.ContextRetention
import ch.lkmc.neutrodyne.core.domain.Departure
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.domain.PlaySessionStore
import ch.lkmc.neutrodyne.core.domain.SessionStart
import ch.lkmc.neutrodyne.core.domain.StartMembership
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PlayContextSpec
import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** The database transaction is the serialization point for playback and restore writers alike. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class SessionWriter(
    private val db: NeutrodyneDatabase,
    private val orderKeys: OrderKeys,
    private val capabilities: YouTubeCapabilitiesSource,
) : PlaySessionStore {
    override suspend fun virtualQueue(k: Int): VirtualQueue {
        require(k >= 0)
        return db.withReadTransaction {
            val session = db.playSessionDao().get()
            val current = session?.currentEpisodeId
            val upNext = db.queueDao().entries().map { QueueItem(it.episodeId, QueueOrigin.UP_NEXT) }
            val spec = session?.contextSpec()
            // Older backups can retain a date after an anchor reference could not be resolved.
            val anchor = session?.contextAnchorEpisodeId?.let { id -> session.contextAnchorSortDate?.let { id to it } }
            val tail =
                if (k == 0 || spec == null || spec.type == ContextType.EXTERNAL) {
                    emptyList()
                } else {
                    db
                        .feedDao()
                        .contextTail(
                            FeedQueryBuilder.contextTail(
                                spec,
                                anchor?.first,
                                anchor?.second,
                                if (k == Int.MAX_VALUE) k else k + 1,
                                capabilities.capabilities.value.inAppPlayback,
                            ),
                        ).asSequence()
                        .filter { it.episodeId != current }
                        .take(k)
                        .map { QueueItem(it.episodeId, QueueOrigin.CONTEXT) }
                        .toList()
                }
            VirtualQueue(session?.generation ?: 0, current?.let { QueueItem(it, QueueOrigin.CURRENT) }, upNext, tail)
        }
    }

    override suspend fun commitStart(
        start: SessionStart,
        now: Long,
    ): Long =
        mutate(now) { session ->
            if (start.membership == StartMembership.CONTEXT) {
                val spec = requireNotNull(start.context)
                when (spec.type) {
                    ContextType.GROUP -> requireNotNull(db.groupDao().groupById(requireNotNull(spec.contextId)))
                    ContextType.PODCAST -> requireNotNull(db.podcastDao().byId(requireNotNull(spec.contextId)))
                    else -> throw IllegalArgumentException("Unsupported browse context")
                }
                require(
                    db
                        .feedDao()
                        .contextList(
                            FeedQueryBuilder.contextList(
                                spec,
                                1,
                                capabilities.capabilities.value.inAppPlayback,
                                start.currentEpisodeId,
                            ),
                        ).isNotEmpty(),
                ) {
                    "Episode left browse context before start"
                }
            }
            saveDeparture(start.departure, now)
            promote(start.currentEpisodeId, now)
            if (start.queueHead.isNotEmpty()) {
                val ids = start.queueHead.distinct().filter { it != start.currentEpisodeId }
                val rows = db.episodeDao().queueEligibility(ids)
                require(
                    rows.size == ids.size &&
                        rows.all {
                            it.availability == Availability.AVAILABLE &&
                                (
                                    it.sourceType == SourceType.RSS ||
                                        capabilities.capabilities.value.inAppPlayback
                                )
                        },
                ) { "External queue changed before start" }
                val entries = db.queueDao().entries()
                val desired = ids + entries.filter { it.episodeId !in ids }.map { it.episodeId }
                db.applyQueueWrites(planQueueWrites(entries, desired, orderKeys), now)
            }
            val current = session.copy(currentEpisodeId = start.currentEpisodeId)
            if (start.contextRetention == ContextRetention.KEEP) return@mutate current

            require((start.anchorEpisodeId == null) == (start.anchorSortDate == null))
            val context = start.context
            current.copy(
                contextType = context?.type ?: ContextType.EXTERNAL,
                contextId = context?.contextId,
                contextOrder = context?.order ?: FeedOrder.NEWEST_FIRST,
                contextFilterFlags = context?.filterFlags ?: 0,
                contextMediaFilter = context?.mediaFilter ?: MediaFilter.ALL,
                contextMinSortDate = context?.minSortDate,
                contextAnchorEpisodeId = start.anchorEpisodeId,
                contextAnchorSortDate = start.anchorSortDate,
            )
        }

    override suspend fun onTransition(
        departure: Departure?,
        arrivedEpisodeId: Long,
        arrivedOrigin: QueueOrigin,
        now: Long,
    ): Long =
        mutate(now) { session ->
            saveDeparture(departure, now)
            promote(arrivedEpisodeId, now)
            session.copy(currentEpisodeId = arrivedEpisodeId).anchorFor(arrivedEpisodeId, arrivedOrigin)
        }

    override suspend fun goBack(
        previous: QueueItem,
        departure: Departure,
        now: Long,
    ): Long =
        mutate(now) { session ->
            require(previous.episodeId != departure.episodeId)
            saveDeparture(departure, now)
            promote(previous.episodeId, now)
            prepend(departure.episodeId, now)
            session.copy(currentEpisodeId = previous.episodeId).anchorFor(previous.episodeId, previous.origin)
        }

    override suspend fun endSession(
        departure: Departure?,
        now: Long,
    ): Long = clear(departure, now)

    override suspend fun clearCurrent(
        departure: Departure?,
        now: Long,
    ): Long = clear(departure, now)

    private suspend fun clear(
        departure: Departure?,
        now: Long,
    ): Long =
        mutate(now) { session ->
            saveDeparture(departure, now)
            PlaySessionEntity(generation = session.generation, updatedAt = now)
        }

    /** Ensure must be the first statement, including when a restored database lacks the singleton. */
    private suspend fun mutate(
        now: Long,
        change: suspend (PlaySessionEntity) -> PlaySessionEntity,
    ): Long {
        require(now >= 0)
        return db.withWriteTransaction {
            db.playSessionDao().ensureNow(now)
            val session = checkNotNull(db.playSessionDao().get())
            val updated = change(session).copy(generation = session.generation + 1, updatedAt = now)
            db.playSessionDao().write(updated)
            updated.generation
        }
    }

    private suspend fun promote(
        episodeId: Long,
        now: Long,
    ) {
        requireNotNull(db.episodeDao().byId(episodeId)) { "Playback episode no longer exists" }
        db.queueDao().removeEpisodes(listOf(episodeId))
        db.episodeStateDao().ensure(EpisodeStateEntity(episodeId, updatedAt = now))
        db.episodeStateDao().touchLastPlayed(episodeId, now)
    }

    private suspend fun PlaySessionEntity.anchorFor(
        episodeId: Long,
        origin: QueueOrigin,
    ): PlaySessionEntity {
        if (origin != QueueOrigin.CONTEXT) return this
        val episode = requireNotNull(db.episodeDao().byId(episodeId))
        return copy(contextAnchorEpisodeId = episodeId, contextAnchorSortDate = episode.sortDate)
    }

    private suspend fun saveDeparture(
        departure: Departure?,
        now: Long,
    ) {
        departure ?: return
        require(departure.positionMs >= 0 && departure.pinStartedAt >= 0)
        val durationMs = departure.durationMs
        require(durationMs == null || durationMs >= 0)
        if (departure.writePosition) {
            db.savePosition(
                departure.episodeId,
                departure.positionMs,
                durationMs,
                departure.source,
                now,
                departure.pinStartedAt,
            )
        }
        if (departure.markPlayed) db.markEpisodesPlayed(listOf(departure.episodeId), now)
    }

    private suspend fun prepend(
        episodeId: Long,
        now: Long,
    ) {
        if (db.episodeDao().byId(episodeId) == null) return
        val entries = db.queueDao().entries()
        val desired = listOf(episodeId) + entries.filter { it.episodeId != episodeId }.map { it.episodeId }
        db.applyQueueWrites(planQueueWrites(entries, desired, orderKeys), now)
    }
}

private fun PlaySessionEntity.contextSpec(): PlayContextSpec? =
    contextType?.let {
        PlayContextSpec(it, contextId, contextOrder, contextFilterFlags, contextMediaFilter, contextMinSortDate, false)
    }
