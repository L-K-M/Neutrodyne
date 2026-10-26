// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import ch.lkmc.neutrodyne.core.model.PlayContextInfo
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.PlaybackEvent
import ch.lkmc.neutrodyne.playback.api.PlaybackIssue
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import ch.lkmc.neutrodyne.playback.api.PositionSnapshot
import ch.lkmc.neutrodyne.playback.api.ScopeWriteResult
import ch.lkmc.neutrodyne.playback.api.StreamKind
import ch.lkmc.neutrodyne.playback.core.PlaybackExit
import ch.lkmc.neutrodyne.playback.core.PositionSaveResult
import ch.lkmc.neutrodyne.playback.core.PreviousAction
import ch.lkmc.neutrodyne.playback.core.QueueWindowPlanner
import ch.lkmc.neutrodyne.playback.core.RecoveryBudget
import ch.lkmc.neutrodyne.playback.core.StartOutcome
import ch.lkmc.neutrodyne.playback.core.StartPositionRule
import ch.lkmc.neutrodyne.playback.core.StartRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** All player reads and command decisions are confined to the service's main dispatcher. */
internal class AndroidServiceBridge(
    private val player: ExoPlayer,
    private val ports: PlaybackPorts,
    private val access: PlaybackServiceAccess,
    private val scope: CoroutineScope,
    isForeground: () -> Boolean,
    private val stopService: () -> Unit,
) : ServiceBridge,
    Player.Listener {
    private val tracker =
        PositionTracker(
            ports.saver,
            access.clock,
            scope,
            access.applicationScope,
            streamSource = { id ->
                if (access.resolver.streamKind(id) ==
                    StreamKind.LOCAL
                ) {
                    PositionSource.DOWNLOAD
                } else {
                    PositionSource.STREAM
                }
            },
        )
    private val applier = EffectivePlaybackApplier(ports.effective, scope)
    private val projector =
        QueueProjector(
            ports.planner,
            ports.sessions,
            ports.queues,
            ports.lookup,
            ports.positions,
            access.capabilities,
            access.index,
            access.resolver,
            ports.items,
            tracker,
            applier,
            access.history,
            access.clock,
            // Schedule initial collection after attach so a pending session start can reserve it.
            CoroutineScope(scope.coroutineContext + Dispatchers.Main),
            access.io,
        )
    private val recovery =
        ErrorRecovery(
            RecoveryBudget(access.clock),
            access.network,
            access.clock,
            access.resolver,
            access.index,
            projector,
            scope,
            isForeground,
            access.hub::event,
        )
    private val arbiter = EndOfItemPauseArbiter(player)
    private val current = MutableStateFlow<NowPlaying?>(null)
    override val nowPlaying = current.asStateFlow()
    override val effectivePlayback = ports.effective.effectivePlayback
    override val isPlaying: Boolean get() = player.isPlaying
    private val commands = Mutex()
    private var context: PlayContextInfo? = null
    private var serviceIssue: PlaybackIssue? = null
    private var boundaryPaused = false
    private var pending: StartOutcome.Committed? = null
    private var pendingTimeout: Job? = null
    private var startReserved = false
    private var reservationTimeout: Job? = null
    private val jobs = mutableListOf<Job>()
    private var detached = false

    fun attach(hasPendingStart: Boolean = false) {
        tracker.attach(player)
        projector.attach(player)
        if (hasPendingStart) reserveStart()
        applier.attach(player)
        player.addListener(this)
        recovery.attach(player)
        jobs +=
            scope.launch {
                combine(
                    access.network.status,
                    access.prefs.current,
                ) {
                    _,
                    _,
                    ->
                    Unit
                }.collect { updateBoundary() }
            }
        jobs +=
            scope.launch {
                ports.queues.observeSession().collect {
                    context = it?.context
                    publish()
                }
            }
        jobs += scope.launch { recovery.issue.collect { publish() } }
        updateBoundary()
        publish()
    }

    /** A callback can arrive before database opening. Reserve before the first paused projection. */
    fun reserveStart() {
        if (startReserved) return
        startReserved = true
        projector.beginOwnWrite()
        reservationTimeout =
            scope.launch {
                delay(PENDING_START_TIMEOUT_MS)
                releaseReservation()
            }
    }

    private fun releaseReservation() {
        reservationTimeout?.cancel()
        reservationTimeout = null
        if (!startReserved) return
        startReserved = false
        projector.endOwnWrite(null)
    }

    override fun pause() {
        tracker.flush(PositionFlushReason.PAUSE)
        player.pause()
    }

    override suspend fun pauseBeforeUnsubscribe(podcastIds: List<Long>) {
        val id = player.episodeIdOrNull() ?: return
        if (access.index[id]?.podcastId !in podcastIds) return
        player.pause()
        flush()
    }

    override suspend fun start(
        request: StartRequest,
        origin: StartOrigin,
    ): PlayResult =
        commands.withLock {
            startLocked(request, origin, StartApplication.COMMAND)
        }

    override suspend fun setMediaItems(
        request: StartRequest,
        origin: StartOrigin,
    ): MediaSession.MediaItemsWithStartPosition =
        commands.withLock {
            val result = startLocked(request, origin, StartApplication.MEDIA3)
            if (result != PlayResult.Started) throw UnsupportedOperationException("Playback refused: $result")
            resumeLocked(origin)
        }

    private suspend fun startLocked(
        request: StartRequest,
        origin: StartOrigin,
        application: StartApplication,
    ): PlayResult {
        cancelPending()
        access.gate.clearPending()
        if (request == StartRequest.Resume && boundaryPaused) {
            boundaryRefusal(origin)?.let {
                releaseReservation()
                return it
            }
        }
        projector.beginOwnWrite()
        releaseReservation()
        var handedOff = false
        var generation: Long? = null
        try {
            val outcome =
                access.gate.withOrigin(origin) {
                    ports.starter.start(request, player.episodeIdOrNull()) { tracker.capture()?.departure }
                }
            return when (outcome) {
                is StartOutcome.Refused -> {
                    outcome.result
                }

                is StartOutcome.AlreadyCurrent -> {
                    if (application == StartApplication.MEDIA3) {
                        hold(
                            StartOutcome.Committed(
                                outcome.queue,
                                outcome.seekPositionMs ?: player.currentPosition.coerceAtLeast(0),
                            ),
                        )
                        handedOff = true
                    } else {
                        outcome.seekPositionMs?.let(player::seekTo)
                    }
                    PlayResult.Started
                }

                is StartOutcome.Committed -> {
                    generation = outcome.queue.generation
                    if (player.mediaItemCount == 0 || application == StartApplication.MEDIA3) {
                        // Only true resumption/onSetMediaItems may install an empty player's window.
                        hold(outcome)
                        handedOff = true
                    } else {
                        projector.applyStart(outcome)
                    }
                    boundaryPaused = false
                    serviceIssue = null
                    PlayResult.Started
                }
            }
        } finally {
            if (!handedOff) projector.endOwnWrite(generation)
        }
    }

    private fun hold(outcome: StartOutcome.Committed) {
        pending = outcome
        pendingTimeout =
            scope.launch {
                delay(PENDING_START_TIMEOUT_MS)
                cancelPending()
            }
    }

    private fun cancelPending() {
        pendingTimeout?.cancel()
        pendingTimeout = null
        if (pending != null) {
            pending = null
            projector.endOwnWrite(null)
        }
    }

    override suspend fun resume(origin: StartOrigin): MediaSession.MediaItemsWithStartPosition =
        commands.withLock {
            resumeLocked(origin)
        }

    private suspend fun resumeLocked(origin: StartOrigin): MediaSession.MediaItemsWithStartPosition {
        val held = pending
        pending = null
        pendingTimeout?.cancel()
        pendingTimeout = null
        if (held == null) projector.beginOwnWrite()
        releaseReservation()
        try {
            val outcome = held ?: access.gate.withOrigin(origin) { ports.starter.start(StartRequest.Resume) }
            val committed =
                outcome as? StartOutcome.Committed ?: run {
                    if (outcome is StartOutcome.Refused) reportRefusal(outcome.result)
                    throw UnsupportedOperationException("Nothing resumable: $outcome")
                }
            val id = checkNotNull(committed.queue.current).episodeId
            // A grant/policy/network can change between a UI command and Media3's callback.
            access.gate.check(id, access.local.localUriOrNull(id) != null, origin)?.let {
                reportRefusal(it)
                throw UnsupportedOperationException("Playback refused: $it")
            }
            val built = projector.prepareExternalStart(committed)
            serviceIssue = null
            return MediaSession.MediaItemsWithStartPosition(built, 0, committed.startPositionMs)
        } catch (failure: Throwable) {
            projector.endOwnWrite(null)
            throw failure
        }
        // QueueProjector closes the write bracket when Media3 installs this exact timeline.
    }

    override suspend fun beforePlay(origin: StartOrigin): Boolean =
        commands.withLock {
            val id = player.episodeIdOrNull() ?: return@withLock false
            val refusal =
                if (boundaryPaused) {
                    boundaryRefusal(origin)
                } else {
                    access.gate.check(id, access.local.localUriOrNull(id) != null, origin)
                }
            if (refusal != null) {
                reportRefusal(refusal)
                return@withLock false
            }
            serviceIssue = null
            boundaryPaused = false
            updateBoundary()
            true
        }

    private fun reportRefusal(result: PlayResult) {
        val problem =
            when (result) {
                PlayResult.MeteredBlocked -> PlaybackIssue.METERED_BLOCKED
                is PlayResult.NeedsMeteredConsent -> PlaybackIssue.METERED_PAUSE
                PlayResult.Offline -> PlaybackIssue.NETWORK_LOST
                else -> PlaybackIssue.PLAYER_ERROR
            }
        issue(problem)
        access.hub.event(PlaybackEvent.StartRefused(result))
    }

    private fun boundaryRefusal(origin: StartOrigin): PlayResult? {
        val next = player.nextMediaItemIndex
        if (next == C.INDEX_UNSET) return null
        val item = player.getMediaItemAt(next)
        val id = item.mediaId.removePrefix("episode:").toLongOrNull() ?: return PlayResult.ServiceUnavailable
        return access.gate.check(
            id,
            item.requestMetadata.extras?.getBoolean(MediaItemFactory.LOCAL_HINT) == true,
            origin,
        )
    }

    private fun updateBoundary() {
        val next = player.nextMediaItemIndex
        arbiter.blockBeforeNext = next != C.INDEX_UNSET &&
            access.gate.blockBeforeNext(
                player
                    .getMediaItemAt(next)
                    .requestMetadata.extras
                    ?.getBoolean(MediaItemFactory.LOCAL_HINT) == true,
            )
    }

    override fun onPlayWhenReadyChanged(
        playWhenReady: Boolean,
        reason: Int,
    ) {
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
            arbiter.blockBeforeNext
        ) {
            boundaryPaused = true
            issue(PlaybackIssue.METERED_PAUSE)
        }
    }

    override fun onEvents(
        player: Player,
        events: Player.Events,
    ) {
        updateBoundary()
        publish()
    }

    override suspend fun previous() =
        commands.withLock {
            when (val action = access.history.previous(player.currentPosition)) {
                PreviousAction.Restart -> {
                    player.seekTo(0)
                }

                is PreviousAction.GoBack -> {
                    val departure = tracker.capture(PlaybackExit.SEEK)?.departure ?: return@withLock
                    projector.beginOwnWrite()
                    var generation: Long? = null
                    try {
                        generation = ports.sessions.goBack(action.item, departure, access.clock.now())
                        val queue = ports.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
                        check(queue.generation == generation && queue.current?.episodeId == action.item.episodeId)
                        val stored = ports.positions.observe(action.item.episodeId).first()
                        val row = ports.lookup.get(action.item.episodeId)
                        val position =
                            StartPositionRule().startPositionMs(
                                stored?.positionMs,
                                stored?.updatedAt,
                                row?.measuredDurationMs ?: row?.durationMs,
                                row?.playedAt != null,
                                access.clock.now(),
                            )
                        projector.applyStart(StartOutcome.Committed(queue, position))
                        access.history.consume(action.item)
                    } finally {
                        projector.endOwnWrite(generation)
                    }
                }
            }
        }

    override suspend fun stop() =
        commands.withLock {
            player.pause()
            try {
                flush()
            } finally {
                player.stop()
                stopService()
            }
        }

    override suspend fun dismiss() =
        commands.withLock {
            cancelPending()
            player.pause()
            projector.beginOwnWrite()
            var generation: Long? = null
            try {
                generation =
                    ports.sessions.clearCurrent(
                        tracker.capture()?.departure?.copy(markPlayed = false),
                        access.clock.now(),
                    )
                tracker.clear()
                player.clearMediaItems()
                access.resolver.clearPins()
                access.gate.clearPending()
                serviceIssue = null
                stopService()
            } finally {
                projector.endOwnWrite(generation)
            }
        }

    private suspend fun flush() {
        val snapshot = tracker.capture() ?: return
        val result = ports.saver.flush(snapshot)
        if (result is PositionSaveResult.Failed) throw result.cause
    }

    override suspend fun setSpeed(
        speed: Float,
        scope: SettingScope,
    ): ScopeWriteResult =
        ports.effective.setSpeed(
            speed,
            scope,
        )

    override suspend fun setSkipSilence(
        enabled: Boolean,
        scope: SettingScope,
    ): ScopeWriteResult =
        ports.effective.setSkipSilence(
            enabled,
            scope,
        )

    override suspend fun cycleSpeed(): ScopeWriteResult = ports.effective.cycleSpeed()

    fun issue(value: PlaybackIssue) {
        serviceIssue = value
        publish()
    }

    private fun publish() {
        if (detached) return
        val id = player.episodeIdOrNull()
        val row = id?.let { access.index[it] }
        current.value =
            row?.let {
                val phase =
                    when {
                        player.playerError != null -> PlayerPhase.ERROR
                        player.playbackState == Player.STATE_BUFFERING -> PlayerPhase.BUFFERING
                        player.playbackState == Player.STATE_READY -> PlayerPhase.READY
                        player.playbackState == Player.STATE_ENDED -> PlayerPhase.ENDED
                        else -> PlayerPhase.NOT_LOADED
                    }
                NowPlaying(
                    row.episodeId,
                    row.podcastId,
                    row.title,
                    row.podcastTitle,
                    row.artwork,
                    row.sourceType,
                    row.isVideo,
                    phase,
                    player.isPlaying,
                    player.playWhenReady,
                    PositionSnapshot(
                        row.episodeId,
                        player.currentPosition.coerceAtLeast(0),
                        player.bufferedPosition.coerceAtLeast(0),
                        player.knownDuration() ?: row.measuredDurationMs ?: row.durationMs,
                        player.playbackParameters.speed,
                        player.isPlaying,
                        access.clock.elapsedRealtime(),
                    ),
                    player.hasNextMediaItem(),
                    access.resolver.streamKind(row.episodeId),
                    serviceIssue ?: recovery.issue.value,
                    context,
                )
            }
        if (player.mediaItemCount == 0 && player.playbackState == Player.STATE_ENDED) stopService()
    }

    fun detach() {
        if (detached) return
        detached = true
        val snapshot = tracker.flush(PositionFlushReason.DESTROY)
        cancelPending()
        releaseReservation()
        jobs.forEach(Job::cancel)
        projector.detach()
        recovery.detach()
        player.removeListener(this)
        tracker.detach(snapshot)
        access.applicationScope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { applier.detach() }
        }
    }

    private enum class StartApplication { COMMAND, MEDIA3 }

    private companion object {
        const val PENDING_START_TIMEOUT_MS = 10_000L
    }
}
