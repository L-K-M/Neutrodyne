// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.desktop

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.domain.SessionRevision
import ch.lkmc.neutrodyne.core.model.AddResult
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.CurrentChapter
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.PlayContextInfo
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.SleepTimerMode
import ch.lkmc.neutrodyne.core.model.SleepTimerState
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlayResult
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackEvent
import ch.lkmc.neutrodyne.playback.api.PlaybackIssue
import ch.lkmc.neutrodyne.playback.api.PlaybackMaintenance
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import ch.lkmc.neutrodyne.playback.api.PositionSnapshot
import ch.lkmc.neutrodyne.playback.api.ScopeWriteResult
import ch.lkmc.neutrodyne.playback.api.StreamKind
import ch.lkmc.neutrodyne.playback.core.CurrentAction
import ch.lkmc.neutrodyne.playback.core.PlaybackExit
import ch.lkmc.neutrodyne.playback.core.PlaybackHistory
import ch.lkmc.neutrodyne.playback.core.PlayerWindow
import ch.lkmc.neutrodyne.playback.core.PositionSaveResult
import ch.lkmc.neutrodyne.playback.core.PreviousAction
import ch.lkmc.neutrodyne.playback.core.QueueWindowPlanner
import ch.lkmc.neutrodyne.playback.core.SeekOrigin
import ch.lkmc.neutrodyne.playback.core.StartOutcome
import ch.lkmc.neutrodyne.playback.core.StartPositionRule
import ch.lkmc.neutrodyne.playback.core.StartRequest
import ch.lkmc.neutrodyne.playback.core.WindowOp
import ch.lkmc.neutrodyne.playback.core.WindowPlayback
import ch.lkmc.neutrodyne.playback.engine.EngineError
import ch.lkmc.neutrodyne.playback.engine.EngineEvent
import ch.lkmc.neutrodyne.playback.engine.EngineState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process desktop adapter. A mutex spans suspending repository calls, since a single dispatcher
 * alone would still allow a queue emission or a second command to interleave a start transaction.
 * Construction is storage-free; startup calls initialize after band 100 opens the database.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DesktopPlaybackController(
    appScope: CoroutineScope,
    private val clock: Clock,
    private val runtimeFactory: () -> DesktopPlaybackRuntime,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlaybackController,
    PlaybackStateSource,
    PlaybackMaintenance {
    private val lifetime = SupervisorJob(appScope.coroutineContext[Job])
    private val scope = CoroutineScope(appScope.coroutineContext + lifetime + dispatcher)
    private val mutex = Mutex()

    // Intent arrival, rather than mutex acquisition, cancels a start waiting on storage.
    private val intent = AtomicLong()
    private var runtime: DesktopPlaybackRuntime? = null
    private var projector: DesktopQueueProjector? = null
    private var following: Job? = null
    private var context: PlayContextInfo? = null
    private var since = 0L
    private var durationMs: Long? = null

    /**
     * The engine's loaded item. It advances on the engine's own automatic
     * transition before the projected window catches up, so the projector and
     * the planner diff against the engine's real current, not `entries.first()`.
     */
    private var engineCurrentId: Long? = null
    private var prepared = false
    private var wantPlaying = false
    private var closed = false
    private var skipBackMs = DEFAULT_SKIP_BACK_MS
    private var skipForwardMs = DEFAULT_SKIP_FORWARD_MS
    private val history = PlaybackHistory()
    private val current = MutableStateFlow<NowPlaying?>(null)
    private val ticks = MutableSharedFlow<PositionSnapshot>(replay = 1)
    private val effective = MutableStateFlow<EffectivePlayback?>(null)
    private val notifications = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = EVENT_CAPACITY)
    override val nowPlaying = current.asStateFlow()
    override val positionTicks = ticks.asSharedFlow()
    override val effectivePlayback = effective.asStateFlow()
    override val pendingMeteredConsent = MutableStateFlow<PlayResult.NeedsMeteredConsent?>(null).asStateFlow()
    override val events = notifications.asSharedFlow()

    // Completion of these features, including end-of-item arbitration and chapter enrichment, is MD1b.
    override val sleepTimer = MutableStateFlow<SleepTimerState>(SleepTimerState.Off).asStateFlow()
    override val currentChapter = MutableStateFlow<CurrentChapter?>(null).asStateFlow()

    suspend fun initialize() {
        serial { ensureRuntime() }
    }

    private suspend fun ensureRuntime(): DesktopPlaybackRuntime {
        check(!closed) { "Desktop playback is shut down" }
        runtime?.let { return it }
        val value = withContext(ioDispatcher) { runtimeFactory() }
        runtime = value
        projector = DesktopQueueProjector(value.engine, value.resolver)
        scope.launch {
            combine(
                value.planner.snapshots(),
                value.queue.observeSession(),
                value.capabilities.capabilities,
            ) { queue, session, _ ->
                queue to session?.context
            }.flatMapLatest { (queue, info) ->
                val ids = (listOfNotNull(queue.current) + queue.upNext + queue.contextTail).map { it.episodeId }.toSet()
                value.media.observe(ids).map { Triple(queue, info, it) }
            }.collect { (queue, info, media) ->
                guarded {
                    val projection = requireNotNull(projector)
                    val plan = value.planner.plan(queue, media, playerWindow()) ?: return@guarded
                    context = info
                    val oldId = current.value?.episodeId
                    if (plan.playback == WindowPlayback.LOAD_PAUSED) {
                        value.engine.pause()
                        wantPlaying = false
                    }
                    val replacement = plan.operations.filterIsInstance<WindowOp.SetAll>().singleOrNull()
                    // Restore metadata without preparing output. The first play uses PlayStarter.
                    if (!prepared) {
                        restore(queue, media)
                        return@guarded
                    }
                    if (replacement != null && oldId != queue.current?.episodeId) flushPause()
                    if (replacement != null) prepareSettings(plan.entries)
                    projection.apply(queue, plan.entries, plan.operations, engineCurrentId)
                    if (replacement != null || plan.operations.any { it == WindowOp.Clear }) {
                        loaded(replacement?.startPositionMs ?: 0)
                    }
                    publish()
                    if (plan.currentAction != CurrentAction.KEEP) advance(PlaybackExit.MARKED_PLAYED)
                }
            }
        }
        scope.launch { value.engine.state.collect { state -> guarded { engineState(state) } } }
        // SharedFlow has no replay. Subscribe before setWindow can emit preparation metadata.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            value.engine.events.collect { event -> guarded { engineEvent(event) } }
        }
        scope.launch {
            value.settings.observePreferences().collect { preferences ->
                guarded {
                    skipBackMs = preferences.skipBackMs
                    skipForwardMs = preferences.skipForwardMs
                    withContext(ioDispatcher) { value.cache.setMaxBytes(preferences.streamCacheMb * BYTES_PER_MB) }
                }
            }
        }
        scope.launch {
            while (true) {
                delay(TICK_MS)
                guarded {
                    if (!prepared) return@guarded
                    // StateFlow can coalesce a whole short item while the preceding save suspends.
                    // Reconcile its terminal state even when the intervening Ready was not observed.
                    if (value.engine.state.value == EngineState.Ended) engineState(EngineState.Ended)
                    publish()
                    val snapshot = current.value?.position ?: return@guarded
                    checkSave(value.saver.onTick(snapshot.positionMs, snapshot.durationMs, snapshot.advancing))
                }
            }
        }
        return value
    }

    private suspend fun restore(
        queue: VirtualQueue,
        media: Map<Long, ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo>,
    ) {
        val row = queue.current?.episodeId?.let(media::get)
        if (row == null) {
            engineCurrentId = null
            current.value = null
            return
        }
        engineCurrentId = row.episodeId
        val saved = requireNotNull(runtime).positions.observe(row.episodeId).first()
        val snapshot =
            PositionSnapshot(
                row.episodeId,
                saved?.positionMs ?: 0,
                0,
                row.measuredDurationMs ?: row.durationMs,
                1f,
                false,
                clock.elapsedRealtime(),
            )
        current.value =
            NowPlaying(
                row.episodeId,
                row.podcastId,
                row.title,
                row.podcastTitle,
                row.artwork,
                row.sourceType,
                row.isVideo,
                PlayerPhase.NOT_LOADED,
                false,
                false,
                snapshot,
                queue.upNext.isNotEmpty() || queue.contextTail.isNotEmpty(),
                null,
                null,
                context,
            )
        ticks.emit(snapshot)
    }

    override suspend fun playEpisode(episodeId: Long): PlayResult = start(StartRequest.Episode(episodeId))

    override suspend fun playEpisodeAt(
        episodeId: Long,
        positionMs: Long,
    ): PlayResult = start(StartRequest.Episode(episodeId, positionMs))

    override suspend fun playFeed(
        source: FeedSource,
        filters: FeedFilters,
        order: FeedOrder,
        startEpisodeId: Long?,
    ): PlayResult = start(StartRequest.Feed(source, filters, order, startEpisodeId))

    override suspend fun play(): PlayResult = start(StartRequest.Resume)

    private suspend fun start(request: StartRequest): PlayResult {
        val ticket = intent.incrementAndGet()
        return serial { startLocked(request, ticket) }
    }

    private fun requireIntent(ticket: Long) {
        if (ticket != intent.get()) throw CancellationException("Desktop playback start superseded")
    }

    private suspend fun startLocked(
        request: StartRequest,
        ticket: Long,
    ): PlayResult {
        requireIntent(ticket)
        val value = ensureRuntime()
        requireIntent(ticket)
        val outcome =
            value.starter.start(request, current.value?.episodeId?.takeIf { prepared }, captureDeparture = {
                value.saver.capture(position(), durationMs, PlaybackExit.NEW_START)?.departure
            })
        requireIntent(ticket)
        return when (outcome) {
            is StartOutcome.Refused -> {
                outcome.result
            }

            is StartOutcome.AlreadyCurrent -> {
                if (!sessionMatches(outcome.queue)) return PlayResult.ServiceUnavailable
                requireIntent(ticket)
                outcome.seekPositionMs?.let { seek(it) }
                requireIntent(ticket)
                wantPlaying = true
                value.engine.play()
                publish()
                PlayResult.Started
            }

            is StartOutcome.Committed -> {
                val old = projector?.entries?.firstOrNull()?.item
                if (!projectStart(outcome.queue, outcome.startPositionMs, ticket)) return PlayResult.ServiceUnavailable
                requireIntent(ticket)
                if (old != null && old.episodeId != outcome.queue.current?.episodeId) history.record(old)
                wantPlaying = true
                value.engine.play()
                publish()
                PlayResult.Started
            }
        }
    }

    private suspend fun projectStart(
        queue: VirtualQueue,
        startMs: Long,
        ticket: Long? = null,
    ): Boolean {
        val value = requireNotNull(runtime)
        val ids = (listOfNotNull(queue.current) + queue.upNext + queue.contextTail).map { it.episodeId }.toSet()
        val media = value.media.observe(ids).first()
        context =
            value.queue
                .observeSession()
                .first()
                ?.context
        val entries = value.planner.window(queue, media)
        require(entries.firstOrNull()?.media != null) { "Current episode is missing" }
        value.engine.pause()
        wantPlaying = false
        prepareSettings(entries)
        // Repository/settings reads suspend. A restore may have replaced the accepted session.
        if (!sessionMatches(queue)) return false
        ticket?.let(::requireIntent)
        requireNotNull(projector).apply(
            queue,
            entries,
            listOf(WindowOp.SetAll(entries.map { it.item }, startMs)),
            queue.current?.episodeId,
        )
        value.planner.appliedOwnGeneration(queue.generation)
        loaded(startMs)
        return sessionMatches(queue)
    }

    private suspend fun sessionMatches(queue: VirtualQueue): Boolean {
        val latest = requireNotNull(runtime).sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
        return latest.generation == queue.generation && latest.current?.episodeId == queue.current?.episodeId
    }

    private suspend fun prepareSettings(entries: List<ch.lkmc.neutrodyne.playback.core.WindowEntry>) {
        val value = requireNotNull(runtime)
        val row = entries.firstOrNull()?.media ?: return
        val group = context?.takeIf { it.type == ContextType.GROUP }?.id
        val settings =
            value.settings
                .prepareWindow(
                    entries.mapNotNull { it.media?.podcastId },
                    group,
                ).getValue(row.podcastId)
        // Decode may prime the ring immediately after setWindow, even while output is paused.
        value.engine.setSpeed(settings.speed.value)
        value.engine.setSkipSilence(settings.skipSilence.value)
    }

    private suspend fun loaded(startMs: Long) {
        val value = requireNotNull(runtime)
        val row = projector?.entries?.firstOrNull()?.media
        following?.cancelAndJoin()
        following = null
        value.saver.clear()
        prepared = row != null
        engineCurrentId = row?.episodeId
        durationMs = row?.let { it.measuredDurationMs ?: it.durationMs }
        since = clock.now()
        if (row == null) {
            value.settings.followCurrent(null, null)
            effective.value = null
            current.value = null
            return
        }
        val group = context?.takeIf { it.type == ContextType.GROUP }?.id
        val settings = requireNotNull(value.settings.cached(row.podcastId, group))
        effective.value = settings
        value.engine.setSpeed(settings.speed.value)
        value.engine.setSkipSilence(settings.skipSilence.value)
        val source =
            if (projector?.entries?.firstOrNull()?.localUri !=
                null
            ) {
                PositionSource.DOWNLOAD
            } else {
                PositionSource.STREAM
            }
        value.saver.load(row.episodeId, since, source, replay = row.playedAt != null)
        value.saver.startPositionApplied()
        followSettings(row, group)
        publish(startMs)
    }

    /**
     * Follows the current item's effective settings, replacing the previous
     * item's follow. An automatic transition changes the current item, so it
     * must re-follow or a different podcast's settings would reach the engine.
     */
    private suspend fun followSettings(
        row: ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo,
        group: Long?,
    ) {
        val value = requireNotNull(runtime)
        following?.cancelAndJoin()
        following =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                value.settings.followCurrent(row.podcastId, group)
            }
        scope
            .launch {
                // Values are serialized with all other controller work; no player mutation from UI.
                value.settings.effectivePlayback.collect { settings ->
                    guarded {
                        if (settings == null) return@guarded
                        effective.value = settings
                        value.engine.setSpeed(settings.speed.value)
                        value.engine.setSkipSilence(settings.skipSilence.value)
                        publish()
                    }
                }
            }.also { settingsObserver ->
                following?.invokeOnCompletion { settingsObserver.cancel() }
            }
    }

    override fun pause() {
        intent.incrementAndGet()
        command {
            wantPlaying = false
            runtime?.engine?.pause()
            flushPause()
            publish()
        }
    }

    override fun togglePlayPause() {
        val ticket = intent.incrementAndGet()
        command {
            requireIntent(ticket)
            if (wantPlaying) {
                wantPlaying = false
                runtime?.engine?.pause()
                flushPause()
                publish()
            } else {
                reportStart(startLocked(StartRequest.Resume, ticket))
            }
        }
    }

    override fun seekTo(positionMs: Long) = command { seek(positionMs) }

    override fun skipBack() = command { seek((position() - skipBackMs).coerceAtLeast(0)) }

    override fun skipForward() = command { seek(position() + skipForwardMs) }

    private suspend fun seek(requestedMs: Long) {
        if (!prepared) return
        val value = requireNotNull(runtime)
        val target = requestedMs.coerceAtLeast(0).let { durationMs?.let(it::coerceAtMost) ?: it }
        value.engine.seekTo(target)
        checkSave(value.saver.onSeek(target, durationMs, SeekOrigin.USER))
        publish(target)
    }

    override fun skipToNext() = command { advance(PlaybackExit.SEEK) }

    override fun skipToPrevious() =
        command {
            if (!prepared) return@command
            when (val action = history.previous(position())) {
                PreviousAction.Restart -> {
                    seek(0)
                }

                is PreviousAction.GoBack -> {
                    val value = requireNotNull(runtime)
                    val departure =
                        value.saver.capture(position(), durationMs, PlaybackExit.SEEK)?.departure ?: return@command
                    val playing = wantPlaying
                    value.sessions.goBack(action.item, departure, clock.now())
                    if (!projectStart(value.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE), 0)) return@command
                    history.consume(action.item)
                    wantPlaying = playing
                    if (playing) value.engine.play()
                }
            }
        }

    private suspend fun advance(exit: PlaybackExit) {
        if (!prepared) return
        val value = requireNotNull(runtime)
        val projection = requireNotNull(projector)
        val currentId = engineCurrentId ?: return
        val current = projection.entries.firstOrNull { it.item.episodeId == currentId }?.item ?: return
        val revision = SessionRevision(current.episodeId, projection.generation)
        // Re-read the live tail before deciding, including edits not yet collected by the projector.
        val queue = value.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
        if (queue.generation != revision.generation || queue.current?.episodeId != current.episodeId) return
        val ids = (listOfNotNull(queue.current) + queue.upNext + queue.contextTail).map { it.episodeId }.toSet()
        val entries = value.planner.window(queue, value.media.observe(ids).first())
        val next = entries.getOrNull(1)?.item
        val departure = value.saver.capture(position(), durationMs, exit)?.departure
        val playing = wantPlaying
        val generation =
            if (next == null) {
                value.sessions.endSession(departure, revision, clock.now())
            } else {
                value.sessions.onTransition(departure, next.episodeId, next.origin, revision, clock.now())
            } ?: return
        val committed = value.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
        if (committed.generation != generation || committed.current?.episodeId != next?.episodeId) return
        if (next == null) {
            value.engine.pause()
            projection.apply(committed, emptyList(), listOf(WindowOp.Clear), currentId)
            value.planner.appliedOwnGeneration(generation)
            wantPlaying = false
            loaded(0)
            return
        }
        val row = requireNotNull(entries[1].media)
        val saved = value.positions.observe(next.episodeId).first()
        val startMs =
            StartPositionRule().startPositionMs(
                saved?.positionMs,
                saved?.updatedAt,
                row.measuredDurationMs ?: row.durationMs,
                row.playedAt != null || saved?.playedAt != null,
                clock.now(),
            )
        if (!projectStart(committed, startMs)) return
        history.record(current)
        wantPlaying = playing
        if (playing) value.engine.play()
        publish()
    }

    private suspend fun engineState(state: EngineState) {
        val value = runtime ?: return
        if (!prepared || state != value.engine.state.value) return
        val id = engineCurrentId
        when (state) {
            is EngineState.Ready -> {
                if (state.episodeId != id) return
                if (state.playing) checkSave(value.saver.onPlaying())
            }

            is EngineState.Buffering -> {
                if (state.episodeId != id) return
            }

            is EngineState.Error -> {
                if (state.episodeId != id) return
                wantPlaying = false
            }

            EngineState.Ended -> {
                advance(PlaybackExit.END)
                return
            }

            EngineState.Idle -> {
                Unit
            }
        }
        val advancing = state is EngineState.Ready && state.playing
        if (!advancing && (current.value?.isPlaying == true || state is EngineState.Error)) flushPause()
        publish()
    }

    private suspend fun engineEvent(event: EngineEvent) {
        if (!prepared) return
        when (event) {
            is EngineEvent.TracksKnown -> {
                if (event.episodeId == engineCurrentId) {
                    event.durationMs?.takeIf { it > 0 }?.let {
                        durationMs = it
                        requireNotNull(runtime).positions.setMeasuredDuration(event.episodeId, it)
                    }
                }
            }

            is EngineEvent.ItemTransition -> {
                engineItemTransition(event)
                return
            }

            else -> {
                Unit
            }
        }
        publish()
    }

    /**
     * The engine has already handed the ring to the next window item. Commit the
     * shared departure/arrival through the core stores, then apply the arrival's
     * start position and effective settings; the projected window follows the
     * commit. The engine keeps the outgoing item in its window, so the planner
     * diff removes it as a prefix and the arrival is never re-prepared.
     */
    private suspend fun engineItemTransition(event: EngineEvent.ItemTransition) {
        val value = requireNotNull(runtime)
        val projection = projector ?: return
        val toId = event.toEpisodeId
        val fromId = event.fromEpisodeId ?: return
        if (engineCurrentId == toId) {
            publish()
            return
        }
        val outgoing = projection.entries.firstOrNull { it.item.episodeId == fromId } ?: return
        val arrival = projection.entries.firstOrNull { it.item.episodeId == toId } ?: return
        val row = arrival.media ?: return
        val revision = SessionRevision(fromId, projection.generation)
        val departure = value.saver.capture(event.fromPositionMs, durationMs, PlaybackExit.AUTO)?.departure ?: return
        val generation =
            value.sessions.onTransition(departure, toId, arrival.item.origin, revision, clock.now()) ?: return
        value.planner.appliedOwnGeneration(generation)
        engineCurrentId = toId
        history.record(outgoing.item)
        val group = context?.takeIf { it.type == ContextType.GROUP }?.id
        val settings = requireNotNull(value.settings.cached(row.podcastId, group))
        effective.value = settings
        value.engine.setSpeed(settings.speed.value)
        value.engine.setSkipSilence(settings.skipSilence.value)
        durationMs = row.measuredDurationMs ?: row.durationMs
        since = clock.now()
        val source = if (arrival.localUri != null) PositionSource.DOWNLOAD else PositionSource.STREAM
        value.saver.load(toId, since, source, replay = row.playedAt != null)
        value.saver.startPositionApplied()
        checkSave(value.saver.onPlaying())
        followSettings(row, group)
        val saved = value.positions.observe(toId).first()
        val startMs =
            StartPositionRule().startPositionMs(
                saved?.positionMs,
                saved?.updatedAt,
                durationMs,
                row.playedAt != null || saved?.playedAt != null,
                clock.now(),
            )
        if (startMs > 0) {
            value.engine.seekTo(startMs)
            checkSave(value.saver.onSeek(startMs, durationMs, SeekOrigin.CORE))
        }
        publish(startMs)
        // The committed queue emission re-projects the tail; apply it now so the
        // engine's window and the planner agree even when the emission coalesced.
        val queue = value.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
        if (queue.generation != generation || queue.current?.episodeId != toId) return
        val ids = (listOfNotNull(queue.current) + queue.upNext + queue.contextTail).map { it.episodeId }.toSet()
        val media = value.media.observe(ids).first()
        val plan = value.planner.plan(queue, media, playerWindow()) ?: return
        projection.apply(queue, plan.entries, plan.operations, toId)
        publish()
    }

    private fun position(): Long =
        if (prepared) {
            runtime?.engine?.positionMs()?.coerceAtLeast(0) ?: 0
        } else {
            current.value?.position?.positionMs
                ?: 0
        }

    private fun playerWindow(): PlayerWindow {
        val entries = if (prepared) projector?.entries.orEmpty() else emptyList()
        val index = engineCurrentId?.let { id -> entries.indexOfFirst { it.item.episodeId == id } } ?: -1
        return PlayerWindow(
            entries,
            currentIndex = if (entries.isEmpty()) 0 else index.coerceIn(0, entries.size - 1),
            currentSinceMs = since,
            positionMs = position(),
            isPlaying = current.value?.isPlaying == true,
            playWhenReady = wantPlaying,
        )
    }

    private suspend fun publish(positionMs: Long = position()) {
        if (!prepared) return
        val value = requireNotNull(runtime)
        val projection = projector ?: return
        val row =
            engineCurrentId
                ?.let { id -> projection.entries.firstOrNull { it.item.episodeId == id }?.media }
                ?: projection.entries.firstOrNull()?.media
                ?: return
        val state = value.engine.state.value
        val phase =
            when (state) {
                EngineState.Idle -> PlayerPhase.NOT_LOADED
                is EngineState.Buffering -> PlayerPhase.BUFFERING
                is EngineState.Ready -> PlayerPhase.READY
                EngineState.Ended -> PlayerPhase.ENDED
                is EngineState.Error -> PlayerPhase.ERROR
            }
        val playing =
            (state as? EngineState.Ready)?.let { it.episodeId == row.episodeId && it.playing && wantPlaying } == true
        val snapshot =
            PositionSnapshot(
                row.episodeId,
                positionMs,
                positionMs,
                durationMs,
                effective.value?.speed?.value ?: 1f,
                playing,
                clock.elapsedRealtime(),
            )
        val issue =
            (state as? EngineState.Error)?.error?.let {
                when (it) {
                    EngineError.Network -> PlaybackIssue.NETWORK_LOST
                    EngineError.LocalFileMissing -> PlaybackIssue.LOCAL_FILE_MISSING
                    else -> PlaybackIssue.PLAYER_ERROR
                }
            }
        current.value =
            NowPlaying(
                row.episodeId,
                row.podcastId,
                row.title,
                row.podcastTitle,
                row.artwork,
                row.sourceType,
                row.isVideo,
                phase,
                playing,
                wantPlaying,
                snapshot,
                projection.entries.size > 1,
                value.resolver.streamKind(row.episodeId)
                    ?: if (projection.entries.firstOrNull { it.item.episodeId == row.episodeId }?.localUri !=
                        null
                    ) {
                        StreamKind.LOCAL
                    } else {
                        StreamKind.STREAM
                    },
                issue,
                context,
            )
        ticks.emit(snapshot)
    }

    private suspend fun flushPause() {
        if (prepared) checkSave(requireNotNull(runtime).saver.onPause(position(), durationMs))
    }

    private fun checkSave(result: PositionSaveResult) {
        if (result is PositionSaveResult.Failed) throw result.cause
    }

    override suspend fun setSpeed(
        speed: Float,
        scope: SettingScope,
    ): ScopeWriteResult =
        serial {
            ensureRuntime().settings.setSpeed(speed, scope)
        }

    override suspend fun setSkipSilence(
        enabled: Boolean,
        scope: SettingScope,
    ): ScopeWriteResult =
        serial {
            ensureRuntime().settings.setSkipSilence(enabled, scope)
        }

    override suspend fun playNext(episodeIds: List<Long>): AddResult =
        serial { ensureRuntime().queue.addNext(episodeIds) }

    override suspend fun playLast(episodeIds: List<Long>): AddResult =
        serial { ensureRuntime().queue.addLast(episodeIds) }

    override suspend fun dismiss() {
        val ticket = intent.incrementAndGet()
        serial {
            requireIntent(ticket)
            val value = runtime ?: return@serial
            value.sessions.clearCurrent(
                value.saver.capture(position(), durationMs, PlaybackExit.DISMISS)?.departure,
                clock.now(),
                allowed = { ticket == intent.get() },
            )
            requireIntent(ticket)
            value.engine.pause()
            val queue = value.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
            requireNotNull(projector).apply(queue, emptyList(), listOf(WindowOp.Clear), engineCurrentId)
            value.planner.appliedOwnGeneration(queue.generation)
            wantPlaying = false
            loaded(0)
            history.clear()
        }
    }

    /** Flush before the shell cancels application scope or closes native resources. */
    suspend fun shutdown() {
        intent.incrementAndGet()
        try {
            serial {
                if (closed) return@serial
                closed = true
                val value = runtime ?: return@serial
                value.engine.pause()
                wantPlaying = false
                try {
                    flushPause()
                } finally {
                    withContext(ioDispatcher) { value.engine.close() }
                }
            }
        } finally {
            lifetime.cancelAndJoin()
        }
    }

    override suspend fun streamingCacheBytes(): Long =
        serial {
            val cache = runtime?.cache ?: return@serial 0
            withContext(ioDispatcher) { cache.sizeBytes() }
        }

    override suspend fun clearStreamingCache() =
        serial {
            runtime?.cache?.let { withContext(ioDispatcher) { it.clear() } }
            Unit
        }

    override fun setSleepTimer(mode: SleepTimerMode) = Unit

    override fun extendSleepTimer(minutes: Int) = Unit

    override fun nextChapter() = Unit

    override fun previousChapter() = Unit

    override fun grantMeteredStreaming() = Unit

    private fun command(block: suspend () -> Unit) {
        scope.launch { guarded(block) }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            serial { if (!closed) block() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, failure) { "Desktop playback operation failed" }
            current.value = current.value?.copy(issue = PlaybackIssue.PLAYER_ERROR)
        }
    }

    private suspend fun reportStart(result: PlayResult) {
        if (result != PlayResult.Started) notifications.emit(PlaybackEvent.StartRefused(result))
    }

    private suspend fun <T> serial(block: suspend () -> T): T = withContext(dispatcher) { mutex.withLock { block() } }

    private companion object {
        const val TAG = "DesktopPlayback"
        const val EVENT_CAPACITY = 8
        const val TICK_MS = 1_000L
        const val BYTES_PER_MB = 1_000_000L
        const val DEFAULT_SKIP_BACK_MS = 10_000L
        const val DEFAULT_SKIP_FORWARD_MS = 30_000L
    }
}
