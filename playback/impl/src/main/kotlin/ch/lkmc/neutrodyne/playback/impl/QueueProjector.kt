// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.domain.PlaySessionStore
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.domain.PositionStore
import ch.lkmc.neutrodyne.core.domain.QueueRepository
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.core.model.QueueItem
import ch.lkmc.neutrodyne.core.model.QueueOrigin
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import ch.lkmc.neutrodyne.playback.core.CurrentAction
import ch.lkmc.neutrodyne.playback.core.PlaybackExit
import ch.lkmc.neutrodyne.playback.core.PlaybackHistory
import ch.lkmc.neutrodyne.playback.core.PlayerWindow
import ch.lkmc.neutrodyne.playback.core.PositionCapture
import ch.lkmc.neutrodyne.playback.core.QueueWindowPlanner
import ch.lkmc.neutrodyne.playback.core.StartOutcome
import ch.lkmc.neutrodyne.playback.core.StartPositionRule
import ch.lkmc.neutrodyne.playback.core.WindowEntry
import ch.lkmc.neutrodyne.playback.core.WindowOp
import ch.lkmc.neutrodyne.playback.core.WindowPlayback
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * W8 constructs this with the SAME tracker/applier it attaches, after awaiting database open on IO.
 * Attach tracker first, then this projector, then state/session listeners. Calls and playerScope run
 * on the player's application thread. Detach before releasing the player. This class never plays.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class QueueProjector(
    private val planner: QueueWindowPlanner,
    private val sessions: PlaySessionStore,
    private val queue: QueueRepository,
    private val lookup: PlaybackMediaLookup,
    private val positions: PositionStore,
    private val capabilities: YouTubeCapabilitiesSource,
    private val index: EpisodeSourceIndex,
    private val resolver: EpisodeResolver,
    private val items: MediaItemFactory,
    private val tracker: PositionTracker,
    private val applier: EffectivePlaybackApplier,
    private val history: PlaybackHistory,
    private val clock: Clock,
    private val playerScope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val beforeAdvance: (MediaItem) -> Boolean,
    private val reportFailure: (Exception) -> Unit = { android.util.Log.w(TAG, "Queue projection failed") },
) : Player.Listener {
    private var player: Player? = null
    private var observing: Job? = null
    private val writes = mutableSetOf<Job>()
    private val mutex = Mutex()
    private var entries = emptyList<WindowEntry>()
    private var applying = false
    private var revision = 0L
    private var skipExit: PlaybackExit? = null
    private var ending = false
    private var external: ExternalApply? = null
    private var externalTimeout: Job? = null
    private var latest: Input? = null
    private var knownGeneration = 0L
    private var failedArrival: FailedArrival? = null

    private data class FailedArrival(
        val episodeId: Long,
        val generation: Long,
    )

    private data class Input(
        val queue: VirtualQueue,
        val media: Map<Long, PlaybackMediaInfo>,
        val groupId: Long?,
        val readRevision: Long? = null,
        // Identity only: a collected emission can supersede a refresh without a bracket.
        val readEmission: Input? = null,
    )

    private data class ExternalApply(
        val generation: Long,
        val mediaIds: List<String>,
        val entries: List<WindowEntry>,
    )

    fun attach(player: Player) {
        check(this.player == null)
        checkPlayerThread(player)
        this.player = player
        player.addListener(this)
        observing =
            playerScope.launch {
                try {
                    combine(
                        planner.snapshots(),
                        queue.observeSession(),
                        capabilities.capabilities.map { it.inAppPlayback }.distinctUntilChanged(),
                    ) { snapshot, session, _ ->
                        snapshot to session
                    }.flatMapLatest { (snapshot, session) ->
                        val ids =
                            (listOfNotNull(snapshot.current) + snapshot.upNext + snapshot.contextTail)
                                .map { it.episodeId }
                                .toSet()
                        lookup.observe(ids).map { media ->
                            Input(snapshot, media, session?.context?.takeIf { it.type == ContextType.GROUP }?.id)
                        }
                    }.conflate()
                        .collect { input ->
                            latest = input
                            try {
                                project(input)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                reportFailure(failure)
                            }
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    reportFailure(failure)
                }
            }
    }

    /** Bracket the durable command BEFORE invoking PlayStarter or an external set/add write. */
    fun beginOwnWrite() {
        checkNotNull(player).let(::checkPlayerThread)
        revision++
        planner.beginOwnWrite()
    }

    fun endOwnWrite(generation: Long?) {
        if (generation != null) knownGeneration = maxOf(knownGeneration, generation)
        revision++
        if (planner.endOwnWrite(generation) != null) refresh()
    }

    /** Use inside the command's begin/end bracket. Preparing does not grant autoplay. */
    suspend fun applyStart(outcome: StartOutcome.Committed) =
        mutex.withLock {
            val live = checkNotNull(player)
            checkPlayerThread(live)
            val input = input(outcome.queue)
            val desired = planner.window(input.queue, input.media)
            val built = build(desired)
            applier.prepareWindow(desired.mapNotNull { it.media }, input.groupId)
            install(desired, built, outcome.startPositionMs, WindowPlayback.KEEP)
            planner.appliedOwnGeneration(outcome.queue.generation)
            knownGeneration = maxOf(knownGeneration, outcome.queue.generation)
            failedArrival = null
        }

    /**
     * DB-first external set/resumption path. The caller has begun a write and returns these items
     * to Media3, rather than also setting them on the player. Completion closes that SAME bracket.
     */
    suspend fun prepareExternalStart(outcome: StartOutcome.Committed): List<MediaItem> =
        prepareExternalWindow(outcome.queue)

    /** Return the complete expected window; the add callback returns only its newly added items. */
    suspend fun prepareExternalEdit(snapshot: VirtualQueue): List<MediaItem> = prepareExternalWindow(snapshot)

    private suspend fun prepareExternalWindow(snapshot: VirtualQueue): List<MediaItem> =
        mutex.withLock {
            check(external == null) { "External playlist apply already pending" }
            val input = input(snapshot)
            val desired = planner.window(input.queue, input.media)
            val built = build(desired)
            applier.prepareWindow(desired.mapNotNull { it.media }, input.groupId)
            index.putAll(desired.mapNotNull { it.media })
            applier.refreshFor(desired.firstOrNull()?.item?.episodeId)
            expectExternalApply(snapshot.generation, built.map { it.mediaId }, desired)
            built
        }

    /** Also supports external add callbacks: pass the COMPLETE expected timeline, not just adds. */
    private fun expectExternalApply(
        generation: Long,
        mediaIds: List<String>,
        window: List<WindowEntry>,
    ) {
        check(external == null)
        external = ExternalApply(generation, mediaIds.toList(), window.toList())
        externalTimeout =
            playerScope.launch {
                delay(EXTERNAL_APPLY_TIMEOUT_MS)
                finishExternalApply(matched = false)
            }
        if (player?.mediaIds() == mediaIds) finishExternalApply(matched = true)
    }

    override fun onTimelineChanged(
        timeline: androidx.media3.common.Timeline,
        reason: Int,
    ) {
        if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
        val pending = external
        if (pending != null && player?.mediaIds() == pending.mediaIds) {
            launchWrite { if (external === pending) finishExternalApply(matched = true) }
        } else if (!applying && pending == null && latest != null) {
            refresh()
        }
    }

    private fun finishExternalApply(matched: Boolean) {
        val pending = external ?: return
        external = null
        externalTimeout?.cancel()
        externalTimeout = null
        if (matched) {
            val currentChanged =
                entries.firstOrNull()?.item?.episodeId !=
                    pending.entries
                        .firstOrNull()
                        ?.item
                        ?.episodeId
            releaseDeparted(entries, pending.entries)
            entries = pending.entries
            if (currentChanged) {
                pending.entries.firstOrNull()?.let {
                    tracker.load(
                        it.item.episodeId,
                        it.media?.playedAt != null,
                        it.media?.measuredDurationMs ?: it.media?.durationMs,
                    )
                }
            }
            if (currentChanged) tracker.startPositionApplied()
            planner.appliedOwnGeneration(pending.generation)
        }
        endOwnWrite(if (matched) pending.generation else null)
        if (!matched && latest != null) refresh()
    }

    private suspend fun input(snapshot: VirtualQueue): Input {
        val ids =
            (
                listOfNotNull(
                    snapshot.current,
                ) + snapshot.upNext + snapshot.contextTail
            ).map { it.episodeId }.toSet()
        val media = withContext(io) { ids.mapNotNull { lookup.get(it) }.associateBy { it.episodeId } }
        val session = queue.observeSession().first()
        return Input(snapshot, media, session?.context?.takeIf { it.type == ContextType.GROUP }?.id)
    }

    private suspend fun project(input: Input) =
        mutex.withLock {
            val live = player ?: return@withLock
            val before = input.readRevision ?: revision
            if (before != revision || (input.readRevision != null && input.readEmission !== latest)) {
                // A newer own write or collected emission superseded the read.
                // Re-read durable state; never replay the captured emission's content.
                if (latest != null) refresh()
                return@withLock
            }
            var snapshot = input.queue
            var media = input.media
            failedArrival?.let { failed ->
                if (live.episodeIdOrNull() != failed.episodeId || snapshot.generation > failed.generation) {
                    failedArrival = null
                } else {
                    // A failed transaction must not reload its durable predecessor. Keep projecting
                    // the live arrival until a later transition or foreign generation supersedes it.
                    val row = media[failed.episodeId] ?: withContext(io) { lookup.get(failed.episodeId) }
                    if (row == null) {
                        failedArrival = null
                    } else {
                        snapshot = snapshot.copy(current = QueueItem(failed.episodeId, QueueOrigin.CURRENT))
                        media = media + (failed.episodeId to row)
                    }
                }
            }
            // Unsubscribe can invalidate media before the coherent empty-session emission.
            if (snapshot.current?.episodeId?.let { it !in media } == true) return@withLock
            val beforeIds = live.mediaIds()
            val beforeIndex = live.currentMediaItemIndex.coerceAtLeast(0)
            val window = playerWindow(live)
            val plan = planner.plan(snapshot, media, window) ?: return@withLock
            // Pause a foreign load before IO yields, so a discarded plan cannot later autoplay.
            if (plan.playback == WindowPlayback.LOAD_PAUSED && before == revision) live.pause()
            val built = build(plan.entries)
            applier.prepareWindow(plan.entries.mapNotNull { it.media }, input.groupId)
            if (player !== live || before != revision ||
                live.mediaIds() != beforeIds ||
                live.currentMediaItemIndex.coerceAtLeast(0) != beforeIndex
            ) {
                if (latest != null) refresh()
                return@withLock
            }
            index.putAll(plan.entries.mapNotNull { it.media })
            val byId = built.associateBy { it.mediaId }
            applying = true
            try {
                for (operation in plan.operations) {
                    when (operation) {
                        is WindowOp.SetAll -> {
                            install(plan.entries, built, operation.startPositionMs, plan.playback)
                        }

                        WindowOp.Clear -> {
                            live.stop()
                            live.clearMediaItems()
                            tracker.clear()
                            applier.applyFor(null)
                        }

                        is WindowOp.Remove -> {
                            live.removeMediaItem(operation.index)
                        }

                        is WindowOp.Move -> {
                            live.moveMediaItem(operation.fromIndex, operation.toIndex)
                        }

                        is WindowOp.Add -> {
                            live.addMediaItem(operation.index, byId.getValue("episode:${operation.item.episodeId}"))
                        }

                        is WindowOp.ReplaceTail -> {
                            live.removeMediaItems(1, live.mediaItemCount)
                            entries.drop(1).forEach { resolver.unpin(it.item.episodeId) }
                            live.addMediaItems(built.drop(1))
                        }

                        is WindowOp.UpdateMetadata -> {
                            // Keep LocalConfiguration exactly, even when current's enclosure changes.
                            val old = live.getMediaItemAt(operation.index)
                            val metadata = byId.getValue("episode:${operation.item.episodeId}").mediaMetadata
                            live.replaceMediaItem(operation.index, old.buildUpon().setMediaMetadata(metadata).build())
                        }

                        is WindowOp.Reload -> {
                            live.removeMediaItem(operation.index)
                            resolver.unpin(operation.item.episodeId)
                            live.addMediaItem(operation.index, byId.getValue("episode:${operation.item.episodeId}"))
                        }
                    }
                }
            } finally {
                applying = false
            }
            releaseDeparted(entries, plan.entries)
            entries = plan.entries
            knownGeneration = maxOf(knownGeneration, plan.generation)
            if (plan.operations.none { it is WindowOp.SetAll }) applier.refreshFor(live.episodeIdOrNull())
            when (plan.currentAction) {
                CurrentAction.KEEP -> Unit
                CurrentAction.ADVANCE -> advance(PlaybackExit.MARKED_PLAYED)
                CurrentAction.END -> end(tracker.capture(PlaybackExit.MARKED_PLAYED))
            }
        }

    private suspend fun build(window: List<WindowEntry>): List<MediaItem> =
        withContext(io) {
            window.map { entry -> items.build(requireNotNull(entry.media) { "Current episode metadata is missing" }) }
        }

    private fun install(
        window: List<WindowEntry>,
        built: List<MediaItem>,
        startMs: Long,
        playback: WindowPlayback,
    ) {
        val live = checkNotNull(player)
        val wasApplying = applying
        applying = true
        try {
            if (playback == WindowPlayback.LOAD_PAUSED) live.pause()
            index.putAll(window.mapNotNull { it.media })
            tracker.coreSeek { live.setMediaItems(built, 0, startMs) }
            window.firstOrNull()?.let {
                tracker.load(
                    it.item.episodeId,
                    it.media?.playedAt != null,
                    it.media?.measuredDurationMs ?: it.media?.durationMs,
                )
                tracker.startPositionApplied()
                applier.applyFor(it.item.episodeId)
            }
            if (playback != WindowPlayback.LOAD_PAUSED) live.prepare()
            releaseDeparted(entries, window)
            entries = window
        } finally {
            applying = wasApplying
        }
    }

    private fun playerWindow(live: Player): PlayerWindow {
        val known = entries.associateBy { it.item.episodeId }
        val actual = live.mediaIds().mapNotNull { id -> known[id.removePrefix("episode:").toLongOrNull()] }
        // Trusted raw playlist edits are overwritten from durable state; unknown IDs need SetAll.
        if (actual.size != live.mediaItemCount) return PlayerWindow()
        return PlayerWindow(
            actual,
            live.currentMediaItemIndex.coerceAtLeast(0),
            tracker.currentSinceMs,
            live.currentPosition.coerceAtLeast(0),
            live.isPlaying,
        )
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        val outgoing = tracker.takeOutgoing()
        val changed = oldPosition.mediaItem?.mediaId != newPosition.mediaItem?.mediaId
        if (changed) {
            // Own replacements also end the old pin, even when the item stays in Up next.
            val departedId =
                oldPosition.mediaItem
                    ?.mediaId
                    ?.removePrefix("episode:")
                    ?.toLongOrNull()
            departedId?.let(resolver::unpin)
        }
        if (applying || external != null || !changed) return
        val live = player ?: return
        val arrivalId = live.episodeIdOrNull() ?: return
        val arrival = entries.firstOrNull { it.item.episodeId == arrivalId } ?: return
        val departed = entries.firstOrNull { "episode:${it.item.episodeId}" == oldPosition.mediaItem?.mediaId }
        val exit = skipExit
        skipExit = null
        val capture = if (exit != null) outgoing?.departure?.copy(markPlayed = false) else outgoing?.departure
        tracker.load(
            arrivalId,
            arrival.media?.playedAt != null,
            arrival.media?.measuredDurationMs ?: arrival.media?.durationMs,
        )
        beginOwnWrite()
        val since = tracker.currentSinceMs
        launchWrite {
            var generation: Long? = null
            try {
                generation =
                    retryTransition { sessions.onTransition(capture, arrivalId, arrival.item.origin, clock.now()) }
                if (generation != null) departed?.let { history.record(it.item) }
                if (generation == null && player === live && live.episodeIdOrNull() == arrivalId) {
                    failedArrival = FailedArrival(arrivalId, knownGeneration)
                }
                if (player === live && live.episodeIdOrNull() == arrivalId && tracker.currentSinceMs == since) {
                    val stored = positions.observe(arrivalId).first()
                    val row = arrival.media
                    val startMs =
                        StartPositionRule().startPositionMs(
                            stored?.positionMs,
                            stored?.updatedAt,
                            row?.measuredDurationMs ?: row?.durationMs,
                            row?.playedAt != null || stored?.playedAt != null,
                            clock.now(),
                        )
                    tracker.coreSeek { live.seekTo(startMs) }
                    tracker.startPositionApplied()
                    applier.applyFor(arrivalId)
                    if (live.playerError != null || live.playbackState == Player.STATE_IDLE) live.prepare()
                }
            } finally {
                endOwnWrite(generation)
            }
        }
    }

    /** Error skips must not finish an item even if it failed near its end. */
    fun advance(exit: PlaybackExit = PlaybackExit.ERROR_SKIP) {
        val live = player ?: return
        if (!live.hasNextMediaItem()) return
        if (!beforeAdvance(live.getMediaItemAt(live.nextMediaItemIndex))) return
        skipExit = exit
        live.seekToNextMediaItem()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED && !applying && external == null) end(tracker.capture(PlaybackExit.END))
    }

    private fun end(capture: PositionCapture?) {
        if (ending) return
        ending = true
        beginOwnWrite()
        launchWrite {
            var generation: Long? = null
            try {
                generation = retryTransition { sessions.endSession(capture?.departure, clock.now()) }
            } finally {
                ending = false
                endOwnWrite(generation)
            }
        }
    }

    private suspend fun retryTransition(write: suspend () -> Long): Long? {
        repeat(2) { attempt ->
            try {
                return write()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (attempt == 1) reportFailure(failure) else yield()
            }
        }
        return null
    }

    /**
     * Re-project the freshest durable queue. A skipped emission is only a signal:
     * its captured snapshot can be older than the write that just closed, so it is
     * never replayed. The revision captured before the read is carried into the
     * project's serialized fence, so a bracket spanning the read, the input
     * resolution or the mutex wait discards and re-arms instead of applying or
     * dropping the reconciliation.
     */
    private fun refresh() {
        launchWrite {
            val before = revision
            val emission = latest
            val snapshot = withContext(io) { sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE) }
            project(input(snapshot).copy(readRevision = before, readEmission = emission))
        }
    }

    private fun launchWrite(block: suspend () -> Unit) {
        val job =
            playerScope.launch {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    reportFailure(failure)
                }
            }
        writes += job
        job.invokeOnCompletion { writes.remove(job) }
    }

    private fun releaseDeparted(
        before: List<WindowEntry>,
        after: List<WindowEntry>,
    ) {
        val retained = after.map { it.item.episodeId }.toSet()
        before.filter { it.item.episodeId !in retained }.forEach {
            resolver.unpin(it.item.episodeId)
            index.remove(it.item.episodeId)
        }
    }

    fun detach() {
        player?.let {
            checkPlayerThread(it)
            it.removeListener(this)
        }
        player = null
        observing?.cancel()
        observing = null
        externalTimeout?.cancel()
        externalTimeout = null
        external = null
        writes.toList().forEach { it.cancel() }
        writes.clear()
        entries.forEach { index.remove(it.item.episodeId) }
        entries = emptyList()
        resolver.clearPins()
    }

    private companion object {
        const val EXTERNAL_APPLY_TIMEOUT_MS = 2_000L
        const val TAG = "QueueProjector"
    }
}

private fun Player.mediaIds(): List<String> = (0 until mediaItemCount).map { getMediaItemAt(it).mediaId }
