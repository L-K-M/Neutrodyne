// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.playback

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.data.repo.toEpisodeRow
import ch.lkmc.neutrodyne.core.database.NeutrodyneDatabase
import ch.lkmc.neutrodyne.core.database.PlaySessionEntity
import ch.lkmc.neutrodyne.core.database.QueueEntryEntity
import ch.lkmc.neutrodyne.core.domain.OrderKeys
import ch.lkmc.neutrodyne.core.domain.PlaySessionStore
import ch.lkmc.neutrodyne.core.domain.QueueRepository
import ch.lkmc.neutrodyne.core.model.AddResult
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.Availability
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.EpisodeRow
import ch.lkmc.neutrodyne.core.model.PlayContextInfo
import ch.lkmc.neutrodyne.core.model.PlaySessionInfo
import ch.lkmc.neutrodyne.core.model.QueueRemoval
import ch.lkmc.neutrodyne.core.model.RejectReason
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.UpNextItem
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

/**
 * 06's durable Up next edits over 02's `queue_entry`/`play_session` (the repository contract in
 * `QueueRepository`): every mutation is one Room write transaction, validation and writes share
 * it, so a rejected batch or a failed write leaves the stored order untouched. Queue edits never
 * write `play_session` (generation is the projector's restart signal); `clearContext` is the one
 * exception, it ensures the singleton and increments generation exactly once.
 *
 * Reads are transactionally coherent: [observeUpNext] is one Room flow over the 02 Observe query,
 * [observeSession] and [observeVirtualQueue] re-run their reads after low-churn invalidations,
 * and, for the virtual queue, after `inAppPlayback` flips, which are runtime state rather than a
 * table. `episode_position` is never observed or joined.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class QueueRepositoryImpl(
    private val db: NeutrodyneDatabase,
    private val sessions: PlaySessionStore,
    private val orderKeys: OrderKeys,
    private val clock: Clock,
    private val capabilities: YouTubeCapabilitiesSource,
) : QueueRepository {
    override fun observeUpNext(): Flow<List<UpNextItem>> =
        db.queueDao().observeUpNext().map { rows ->
            rows.map { UpNextItem(entryId = it.entryId, row = it.episode.toEpisodeRow()) }
        }

    override fun observeSession(): Flow<PlaySessionInfo?> =
        db
            .invalidationTracker
            .createFlow(*SESSION_TABLES)
            .map { db.withReadTransaction { readSession() } }
            .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeVirtualQueue(k: Int): Flow<VirtualQueue> {
        require(k >= 0)
        // A capability flip is runtime state, not an invalidation, so it is a second trigger for
        // the same coherent read; the planner-supplied k already bounds the tail.
        return combine(
            db.invalidationTracker.createFlow(*VIRTUAL_QUEUE_TABLES),
            capabilities.capabilities.map { it.inAppPlayback }.distinctUntilChanged(),
        ) { _, _ -> Unit }
            .mapLatest { sessions.virtualQueue(k) }
            .distinctUntilChanged()
    }

    override suspend fun addNext(episodeIds: List<Long>): AddResult = add(episodeIds, QueueEnd.FRONT)

    override suspend fun addLast(episodeIds: List<Long>): AddResult = add(episodeIds, QueueEnd.BACK)

    /**
     * Whole-batch validation then the reorder plan, in one transaction. The batch is distinct ids
     * in first-occurrence order; already-queued ids become moves and keep their row (`id`,
     * `addedAt`), new ids get `clock.now()` once.
     */
    private suspend fun add(
        episodeIds: List<Long>,
        end: QueueEnd,
    ): AddResult {
        val distinct = episodeIds.distinct()
        if (distinct.isEmpty()) return AddResult.Added(0)
        return db.withWriteTransaction {
            val currentId = db.playSessionDao().get()?.currentEpisodeId
            if (currentId != null && currentId in distinct) {
                return@withWriteTransaction AddResult.Rejected(RejectReason.ALREADY_PLAYING)
            }
            val eligibility = db.episodeDao().queueEligibility(distinct).associateBy { it.id }
            for (id in distinct) {
                val row = eligibility[id]
                if (row == null || row.availability != Availability.AVAILABLE) {
                    return@withWriteTransaction AddResult.Rejected(RejectReason.UNAVAILABLE)
                }
            }
            val inAppPlayback = capabilities.capabilities.value.inAppPlayback
            for (id in distinct) {
                val row = eligibility.getValue(id)
                // The engine is the only YouTube path, both YOUTUBE_* source types.
                if (row.sourceType != SourceType.RSS) {
                    if (!inAppPlayback) {
                        return@withWriteTransaction AddResult.Rejected(RejectReason.YOUTUBE_EXTERNAL)
                    }
                    if (row.externalMediaId.isNullOrBlank()) {
                        return@withWriteTransaction AddResult.Rejected(RejectReason.UNAVAILABLE)
                    }
                }
            }

            val entries = db.queueDao().entries()
            if (distinct.size == 1 && entries.any { it.episodeId == distinct.single() }) {
                moveEntry(entries, distinct.single(), if (end == QueueEnd.FRONT) 0 else entries.lastIndex)
                return@withWriteTransaction AddResult.Added(1)
            }
            val requested = distinct.toSet()
            val rest = entries.map { it.episodeId }.filter { it !in requested }
            val desired = if (end == QueueEnd.FRONT) distinct + rest else rest + distinct
            db.applyQueueWrites(planQueueWrites(entries, desired, orderKeys), clock.now())
            AddResult.Added(distinct.size)
        }
    }

    override suspend fun move(
        episodeId: Long,
        toIndex: Int,
    ) {
        db.withWriteTransaction {
            val entries = db.queueDao().entries()
            moveEntry(entries, episodeId, toIndex)
        }
    }

    private suspend fun moveEntry(
        entries: List<QueueEntryEntity>,
        episodeId: Long,
        toIndex: Int,
    ) {
        val index = entries.indexOfFirst { it.episodeId == episodeId }
        if (index < 0) return
        val clamped = toIndex.coerceIn(0, entries.lastIndex)
        if (clamped == index) return
        val moved = entries[index]
        val remaining = entries.toMutableList().apply { removeAt(index) }
        val lower = remaining.getOrNull(clamped - 1)?.orderKey
        val upper = remaining.getOrNull(clamped)?.orderKey
        val key = if (lower != null && upper != null && lower >= upper) null else orderKeys.between(lower, upper)
        if (key != null && key.length <= OrderKeys.MAX_LENGTH) {
            // Write the dragged row, even when moving its neighbour would give the same order.
            db.queueDao().updateOrderKey(moved.id, key)
        } else {
            remaining.add(clamped, moved)
            db.applyQueueWrites(rewriteQueue(entries, remaining.map { it.episodeId }, orderKeys), clock.now())
        }
    }

    override suspend fun remove(episodeIds: List<Long>) {
        if (episodeIds.isEmpty()) return
        db.withWriteTransaction {
            for (chunk in episodeIds.distinct().chunked(PLAYBACK_BIND_CHUNK)) {
                db.queueDao().removeEpisodes(chunk)
            }
        }
    }

    override suspend fun clearUpNext() {
        db.withWriteTransaction { db.queueDao().clear() }
    }

    override suspend fun removeWithUndo(episodeIds: List<Long>): QueueRemoval =
        db.withWriteTransaction {
            val originalOrder = db.queueDao().entries().map { it.episodeId }
            val requested = episodeIds.toSet()
            val removed = originalOrder.filter { it in requested }
            for (chunk in removed.chunked(PLAYBACK_BIND_CHUNK)) {
                db.queueDao().removeEpisodes(chunk)
            }
            QueueRemoval(removed, originalOrder)
        }

    override suspend fun clearWithUndo(): QueueRemoval =
        db.withWriteTransaction {
            val originalOrder = db.queueDao().entries().map { it.episodeId }
            db.queueDao().clear()
            QueueRemoval(originalOrder, originalOrder)
        }

    /**
     * Undo is a membership restore, not a re-add and not a rollback: the stored order at undo time
     * stays fixed and each restorable id is inserted between the neighbours its token still finds.
     * The eligibility read is `mediaInfo` (existence + `playedAt`); the add path's availability and
     * YouTube-capability gates deliberately do not apply, so a row that turned unavailable or
     * external since removal comes back.
     */
    override suspend fun undoRemoval(removal: QueueRemoval) {
        if (removal.removedIds.isEmpty()) return
        db.withWriteTransaction {
            val entries = db.queueDao().entries()
            val queued = entries.map { it.episodeId }.toHashSet()
            val currentId = db.playSessionDao().get()?.currentEpisodeId
            val candidates =
                removal.removedIds.distinct().filter { it !in queued && it != currentId }
            if (candidates.isEmpty()) return@withWriteTransaction
            val media = db.episodeDao().mediaInfo(candidates).associateBy { it.id }
            val positions =
                removal.originalOrder.withIndex().associate { (index, id) -> id to index }
            val restorable =
                candidates
                    .filter { it in media && media.getValue(it).playedAt == null }
                    .sortedBy { positions[it] ?: Int.MAX_VALUE }
            if (restorable.isEmpty()) return@withWriteTransaction

            val desired = entries.map { it.episodeId }.toMutableList()
            val present = desired.toMutableSet()
            var afterPrevious = 0
            for (id in restorable) {
                // Concurrent moves can reverse the surviving anchors; restored rows still keep their order.
                val index = maxOf(afterPrevious, undoIndex(removal.originalOrder, positions, desired, present, id))
                desired.add(index, id)
                afterPrevious = index + 1
                present += id
            }
            db.applyQueueWrites(planQueueWrites(entries, desired, orderKeys), clock.now())
        }
    }

    /**
     * The insertion index for one restored id: before its nearest original successor still queued,
     * else after its closest original predecessor still queued (rows restored earlier in this pass
     * count), else its old index clamped to the list; ids foreign to the token's order append.
     */
    private fun undoIndex(
        originalOrder: List<Long>,
        positions: Map<Long, Int>,
        desired: List<Long>,
        present: Set<Long>,
        episodeId: Long,
    ): Int {
        val originalIndex = positions[episodeId] ?: return desired.size
        for (i in originalIndex + 1 until originalOrder.size) {
            val anchor = originalOrder[i]
            if (anchor in present) return desired.indexOf(anchor)
        }
        for (i in originalIndex - 1 downTo 0) {
            val anchor = originalOrder[i]
            if (anchor in present) return desired.indexOf(anchor) + 1
        }
        return minOf(originalIndex, desired.size)
    }

    /** "Stop after Up next": the only queue-path write that touches `play_session`. */
    override suspend fun clearContext() {
        val now = clock.now()
        db.withWriteTransaction {
            db.playSessionDao().ensureNow(now)
            db.playSessionDao().clearContext(now)
        }
    }

    private suspend fun readSession(): PlaySessionInfo? {
        val session = db.playSessionDao().get() ?: return null
        val type = session.contextType
        val title =
            when (type) {
                ContextType.GROUP -> {
                    session.contextId?.let { db.groupDao().groupById(it)?.name }
                }

                ContextType.PODCAST -> {
                    session.contextId?.let { db.podcastDao().byId(it) }?.let { it.customTitle ?: it.title }
                }

                ContextType.ALL -> {
                    "All"
                }

                ContextType.UNGROUPED -> {
                    "Ungrouped"
                }

                ContextType.DOWNLOADS -> {
                    "Downloads"
                }

                ContextType.EXTERNAL, null -> {
                    null
                }
            }
        val id = if (type == ContextType.GROUP || type == ContextType.PODCAST) session.contextId else null
        val context =
            if (type != null && title != null) {
                PlayContextInfo(
                    type,
                    id,
                    title,
                    session.contextOrder,
                    session.contextFilterFlags,
                    session.contextMediaFilter,
                )
            } else {
                null
            }
        return PlaySessionInfo(session.currentEpisodeId, context, session.generation)
    }

    private enum class QueueEnd { FRONT, BACK }

    private companion object {
        /** `play_session` plus the title sources (group name, podcast title). */
        val SESSION_TABLES = arrayOf("play_session", "podcast_group", "podcast")

        /**
         * Every table `PlaySessionStore.virtualQueue` may read: session and queue, then the
         * context-tail tables (episode/podcast + state/download joins and the membership predicate
         *, `episode_position` and `artwork` are deliberately absent).
         */
        val VIRTUAL_QUEUE_TABLES =
            arrayOf(
                "play_session",
                "queue_entry",
                "episode",
                "podcast",
                "podcast_group_member",
                "episode_state",
                "download",
            )
    }
}
