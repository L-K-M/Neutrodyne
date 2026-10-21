// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.core

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.domain.PositionStore
import ch.lkmc.neutrodyne.core.domain.QueueRepository
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import ch.lkmc.neutrodyne.download.api.LocalMediaIndex
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Inputs captured together on the adapter's player thread; index may still have consumed prefix. */
data class PlayerWindow(
    val items: List<WindowEntry> = emptyList(),
    val currentIndex: Int = 0,
    val currentSinceMs: Long = 0,
    val positionMs: Long = 0,
    val isPlaying: Boolean = false,
)

enum class WindowPlayback { KEEP, LOAD_PAUSED }

/** ADVANCE leaves an already played item: capture its departure with PlaybackExit.MARKED_PLAYED. */
enum class CurrentAction { KEEP, ADVANCE, END }

data class WindowPlan(
    val generation: Long,
    val entries: List<WindowEntry>,
    val operations: List<WindowOp>,
    val playback: WindowPlayback,
    val currentAction: CurrentAction,
    val prewarmEpisodeIds: Set<Long>,
)

/** Player-thread confined. Adapters apply a returned plan before feeding another snapshot. */
@Inject
class QueueWindowPlanner(
    private val repository: QueueRepository,
    private val capabilities: YouTubeCapabilitiesSource,
    private val positions: PositionStore,
    private val localMedia: LocalMediaIndex,
    private val clock: Clock,
    private val diff: WindowDiff,
    private val startPosition: StartPositionRule,
    private val playedRule: PlayedRule,
) {
    private var appliedGeneration: Long? = null
    private var ownWritesInFlight = 0
    private var stashed: VirtualQueue? = null
    private var writeRevision = 0L
    private var handledMark: Triple<Long, Long, Long>? = null

    fun snapshots(): Flow<VirtualQueue> = repository.observeVirtualQueue(CONTEXT_SIZE)

    fun beginOwnWrite() {
        ownWritesInFlight++
        writeRevision++
    }

    /** Null generation means rollback. Only a committed value may advance the generation guard. */
    fun endOwnWrite(generation: Long?): VirtualQueue? {
        check(ownWritesInFlight > 0) { "No own write in flight" }
        ownWritesInFlight--
        writeRevision++
        if (generation != null) appliedGeneration = maxOf(appliedGeneration ?: generation, generation)
        if (ownWritesInFlight != 0) return null

        val pending = stashed
        stashed = null
        return pending?.takeIf { it.generation >= (appliedGeneration ?: it.generation) }
    }

    /** Called for a start projected synchronously, including an external controller's async apply. */
    fun appliedOwnGeneration(generation: Long) {
        writeRevision++
        appliedGeneration = maxOf(appliedGeneration ?: generation, generation)
    }

    /**
     * Metadata/capability emissions re-use the same generation. frozen is an extension seam for
     * MS2; its owner supplies a thread-safe membership check, not a sync implementation here.
     */
    suspend fun plan(
        snapshot: VirtualQueue,
        media: Map<Long, PlaybackMediaInfo>,
        player: PlayerWindow,
        frozen: (Long) -> Boolean = { false },
    ): WindowPlan? {
        if (ownWritesInFlight > 0 || snapshot.generation < (appliedGeneration ?: snapshot.generation)) {
            stashed = snapshot
            return null
        }
        val revision = writeRevision
        val foreign = appliedGeneration == null || snapshot.generation > appliedGeneration!!
        val current = snapshot.current
        val oldId =
            player.items
                .getOrNull(player.currentIndex)
                ?.item
                ?.episodeId
        val currentChanged = current?.episodeId != oldId
        val loadPaused = foreign && (currentChanged || !player.isPlaying)
        val start =
            if (current != null && (currentChanged || loadPaused)) {
                val stored = positions.observe(current.episodeId).first()
                startPosition.startPositionMs(
                    stored?.positionMs,
                    stored?.updatedAt,
                    playedRule.duration(
                        null,
                        media[current.episodeId]?.measuredDurationMs,
                        media[current.episodeId]?.durationMs,
                    ),
                    media[current.episodeId]?.playedAt != null || stored?.playedAt != null,
                    clock.now(),
                )
            } else {
                0
            }
        // Position observation can suspend while an own transition commits. Never return the
        // pre-write snapshot after that suspension, even if no write remains in flight.
        if (writeRevision != revision || snapshot.generation < (appliedGeneration ?: snapshot.generation)) {
            stashed = snapshot
            return null
        }
        val entries = window(snapshot, media)
        val operations =
            if (loadPaused && !currentChanged && current != null) {
                listOf(WindowOp.SetAll(entries.map { it.item }, start))
            } else {
                diff.diff(player.items, entries, player.currentIndex, start)
            }
        val playedAt = current?.let { media[it.episodeId]?.playedAt }
        val locallyPlayed =
            !foreign && !currentChanged && current != null && !frozen(current.episodeId) &&
                playedAt != null && playedAt >= player.currentSinceMs
        val mark =
            if (current != null && playedAt != null) {
                Triple(current.episodeId, player.currentSinceMs, playedAt)
            } else {
                null
            }
        val action =
            when {
                !locallyPlayed || mark == handledMark -> CurrentAction.KEEP
                entries.size > 1 -> CurrentAction.ADVANCE
                else -> CurrentAction.END
            }
        if (action != CurrentAction.KEEP) handledMark = mark
        val before = player.items.map { it.item.episodeId }.toSet()
        val nextId = entries.getOrNull(1)?.item?.episodeId
        val oldNextId =
            player.items
                .getOrNull(player.currentIndex + 1)
                ?.item
                ?.episodeId
        val prewarm =
            entries
                .filter {
                    it.media?.sourceType != null && it.media.sourceType != SourceType.RSS && it.localUri == null &&
                        (it.item.episodeId !in before || (it.item.episodeId == nextId && nextId != oldNextId))
                }.map { it.item.episodeId }
                .toSet()
        appliedGeneration = snapshot.generation
        return WindowPlan(
            snapshot.generation,
            entries,
            operations,
            if (loadPaused) WindowPlayback.LOAD_PAUSED else WindowPlayback.KEEP,
            action,
            prewarm,
        )
    }

    fun window(
        snapshot: VirtualQueue,
        media: Map<Long, PlaybackMediaInfo>,
    ): List<WindowEntry> {
        val current = snapshot.current ?: return emptyList()
        val caps = capabilities.capabilities.value
        val queued =
            snapshot.upNext
                .asSequence()
                .filter {
                    media[it.episodeId]?.refusal(caps) == null &&
                        it.episodeId in media
                }.take(UP_NEXT_SIZE)
        val tail =
            if (snapshot.upNext.size > UP_NEXT_SIZE) {
                emptySequence()
            } else {
                snapshot.contextTail
                    .asSequence()
                    .filter { media[it.episodeId]?.refusal(caps) == null && it.episodeId in media }
                    .take(CONTEXT_SIZE)
            }
        return (sequenceOf(current) + queued + tail)
            .distinctBy { it.episodeId }
            .map {
                WindowEntry(it, media[it.episodeId], localMedia.localUriOrNull(it.episodeId))
            }.toList()
    }

    companion object {
        const val CONTEXT_SIZE = 20
        const val UP_NEXT_SIZE = 100
    }
}
