// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.desktop

import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncActive
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncEvent
import ch.lkmc.neutrodyne.core.domain.PlaybackSyncPort
import ch.lkmc.neutrodyne.core.domain.SessionRevision
import ch.lkmc.neutrodyne.core.model.AddResult
import ch.lkmc.neutrodyne.core.model.ChapterSource
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.CurrentChapter
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.PlayContextInfo
import ch.lkmc.neutrodyne.core.model.PlaySessionInfo
import ch.lkmc.neutrodyne.core.model.PositionSource
import ch.lkmc.neutrodyne.core.model.SettingScope
import ch.lkmc.neutrodyne.core.model.SleepTimerMode
import ch.lkmc.neutrodyne.core.model.SleepTimerState
import ch.lkmc.neutrodyne.core.model.VirtualQueue
import ch.lkmc.neutrodyne.desktop.system.AudioRouteMonitor
import ch.lkmc.neutrodyne.desktop.system.NoOpAudioRouteMonitor
import ch.lkmc.neutrodyne.desktop.system.RouteEvent
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlayOrigin
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
import ch.lkmc.neutrodyne.playback.api.UnplayableReason
import ch.lkmc.neutrodyne.playback.core.ChapterIndex
import ch.lkmc.neutrodyne.playback.core.CurrentAction
import ch.lkmc.neutrodyne.playback.core.PlayStarter
import ch.lkmc.neutrodyne.playback.core.PlaybackExit
import ch.lkmc.neutrodyne.playback.core.PlaybackHistory
import ch.lkmc.neutrodyne.playback.core.PlayerWindow
import ch.lkmc.neutrodyne.playback.core.PositionSaveResult
import ch.lkmc.neutrodyne.playback.core.PreviousAction
import ch.lkmc.neutrodyne.playback.core.QueueWindowPlanner
import ch.lkmc.neutrodyne.playback.core.RecoveryBudget
import ch.lkmc.neutrodyne.playback.core.RemotePlayedGuard
import ch.lkmc.neutrodyne.playback.core.SeekOrigin
import ch.lkmc.neutrodyne.playback.core.SleepTimerCore
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

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
    private val network: NetworkMonitor,
    private val remoteGuard: RemotePlayedGuard,
    private val starterFactory: () -> PlayStarter,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val routeMonitor: AudioRouteMonitor = NoOpAudioRouteMonitor,
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

    /**
     * The in-app volume 0..1 (11 Menus: Volume up/down; Ctrl/Cmd+↑/↓). Stored before the
     * runtime exists and applied in [ensureRuntime], so a launch-time restore reaches the
     * engine it creates.
     */
    private var userVolume: Float = 1.0f
    private val history = PlaybackHistory()
    private val budget = RecoveryBudget(clock)

    /**
     * A network error waiting for a validated connection, or null. The 06
     * foreground window bounds how long an automatic re-prepare may wait.
     */
    private var networkRetry: NetworkRetry? = null

    /** True once an output device failure paused playback; cleared on the next start. */
    private var outputDeviceLost = false

    /**
     * The terminal [EngineState.Ended] was already handled. The 1-s tick
     * reconciles a coalesced Ended, but a second pass must not end the session
     * after an end-of-episode sleep already completed the item.
     */
    private var engineEndedHandled = false

    /**
     * The item the end-of-episode timer completed in place. It stays loaded, so a user
     * seek or Play on it starts a replay occurrence (06's M5 note, Android's
     * `replayCompletedBoundary`); cleared once the item stops being current or is armed.
     */
    private var completedBoundary: Long? = null

    /**
     * An issue the engine's own state no longer carries: a missing local file is
     * re-resolved at once, so the error state is replaced by Buffering before the
     * UI can show it. Held until playback resumes (06 Error recovery).
     */
    private var pendingIssue: PlaybackIssue? = null

    private val sleepTimerCore = SleepTimerCore()
    private var sleepSampledAt = 0L
    private var chapterIndex = ChapterIndex(emptyList())
    private var chapterFollowing: Job? = null
    private var chapterBoundary: Job? = null
    private val writtenChapterSources = mutableSetOf<ChapterSource>()

    /**
     * TracksKnown of a prepared successor can reach the controller before the
     * engine's ItemTransition event, while [engineCurrentId] is still the outgoing
     * item. The event is kept until that episode becomes current, so its chapters
     * and measured duration are applied exactly when the item loads.
     */
    private val pendingTracksKnown = mutableMapOf<Long, EngineEvent.TracksKnown>()
    private val current = MutableStateFlow<NowPlaying?>(null)
    private val ticks = MutableSharedFlow<PositionSnapshot>(replay = 1)
    private val effective = MutableStateFlow<EffectivePlayback?>(null)
    private val notifications = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = EVENT_CAPACITY)
    override val nowPlaying = current.asStateFlow()
    override val positionTicks = ticks.asSharedFlow()
    override val effectivePlayback = effective.asStateFlow()
    override val pendingMeteredConsent = MutableStateFlow<PlayResult.NeedsMeteredConsent?>(null).asStateFlow()
    override val events = notifications.asSharedFlow()

    private val mutableSleepTimer = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    private val mutableCurrentChapter = MutableStateFlow<CurrentChapter?>(null)
    override val sleepTimer = mutableSleepTimer.asStateFlow()
    override val currentChapter = mutableCurrentChapter.asStateFlow()

    private val syncActive = MutableStateFlow(PlaybackSyncActive(false))
    private val boundaries = MutableSharedFlow<PlaybackSyncEvent>(extraBufferCapacity = EVENT_CAPACITY)

    /**
     * 06 Sync interplay's desktop [PlaybackSyncPort]: `active` follows the published current,
     * `Paused`/`Stopped`/`Transition` boundaries emit only after their position save or session
     * transaction committed, and a remote mark-played freezes the loaded item through
     * [RemotePlayedGuard]. The port is a member forwarder because [PlaybackStateSource]'s own
     * `events` property would clash with the port's.
     */
    val syncPort: PlaybackSyncPort =
        object : PlaybackSyncPort {
            override val active: StateFlow<PlaybackSyncActive> = syncActive.asStateFlow()

            override val events: Flow<PlaybackSyncEvent> = boundaries.asSharedFlow()

            override suspend fun onRemoteMarkedPlayed(
                episodeId: Long,
                deviceName: String,
            ) = this@DesktopPlaybackController.onRemoteMarkedPlayed(episodeId, deviceName)

            override suspend fun onRemoteMarkReverted(episodeId: Long) =
                this@DesktopPlaybackController.onRemoteMarkReverted(episodeId)
        }

    private val unfreezeWatches = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    /** 06 Sync interplay: a boundary reaches sync only after its save or transaction committed. */
    private fun pausedBoundary() {
        if (prepared) boundaries.tryEmit(PlaybackSyncEvent.Paused)
    }

    private fun stoppedBoundary() {
        boundaries.tryEmit(PlaybackSyncEvent.Stopped)
    }

    private fun transitionBoundary(episodeId: Long) {
        boundaries.tryEmit(PlaybackSyncEvent.Transition(episodeId))
    }

    suspend fun initialize() {
        serial { ensureRuntime() }
    }

    private suspend fun ensureRuntime(): DesktopPlaybackRuntime {
        check(!closed) { "Desktop playback is shut down" }
        runtime?.let { return it }
        val value = withContext(ioDispatcher) { runtimeFactory() }
        runtime = value
        value.engine.setVolume(userVolume)
        sleepSampledAt = clock.elapsedRealtime()
        projector = DesktopQueueProjector(value.engine, value.resolver)
        // A freeze a revert or an unfreeze cleared drops its watcher too.
        scope.launch {
            remoteGuard.frozen.collect {
                for ((episodeId, job) in unfreezeWatches.entries) {
                    if (!remoteGuard.isFrozen(episodeId) && unfreezeWatches.remove(episodeId, job)) {
                        job.cancel()
                    }
                }
            }
        }
        scope.launch {
            combine(
                value.planner.snapshots(),
                value.queue.observeSession(),
                value.capabilities.capabilities,
            ) { queue, _, _ -> queue }
                .flatMapLatest { queue ->
                    val ids =
                        (listOfNotNull(queue.current) + queue.upNext + queue.contextTail).map { it.episodeId }.toSet()
                    value.media.observe(ids).map { queue to it }
                }.collect { (queue, media) ->
                    guarded {
                        val projection = requireNotNull(projector)
                        val plan =
                            value.planner.plan(queue, media, playerWindow(), frozen = ::frozenForPlanner)
                                ?: return@guarded
                        // Read the session with the queue: the session and queue flows invalidate
                        // independently, so a cached context could be cleared by a stale pairing.
                        context = coherentContext(queue, value.queue.observeSession().first(), context)
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
                        pendingTracksKnown.keys.retainAll { id ->
                            projection.entries.any { it.item.episodeId == id }
                        }
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
        // 06 Error recovery: a network error keeps the item and re-prepares once
        // a validated connection returns within the foreground window.
        scope.launch {
            network.status.collect { status ->
                guarded {
                    if (status.isConnected && status.isValidated) networkRecovered()
                }
            }
        }
        // 11 Audio-route monitoring: a removed device pauses and saves; a default
        // change alone keeps playing on the new default.
        scope.launch {
            routeMonitor.events.collect { event ->
                guarded {
                    when (event) {
                        is RouteEvent.DeviceRemoved -> deviceRemoved()
                        RouteEvent.DefaultChanged -> Unit
                    }
                }
            }
        }
        // The shared core's 500-ms step keeps the 20 fade steps over 10 s.
        scope.launch {
            while (true) {
                delay(SleepTimerCore.STEP_MS)
                guarded {
                    applySleep(sleepTimerCore.tick(sleepElapsed()))
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
            mutableCurrentChapter.value = null
            current.value = null
            syncActive.value = PlaybackSyncActive(false)
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

    override suspend fun playEpisode(episodeId: Long): PlayResult =
        start(StartRequest.Episode(episodeId), PlayOrigin.UI)

    override suspend fun playEpisodeAt(
        episodeId: Long,
        positionMs: Long,
    ): PlayResult = start(StartRequest.Episode(episodeId, positionMs), PlayOrigin.UI)

    override suspend fun playDownloads(startEpisodeId: Long?): PlayResult =
        start(StartRequest.Downloads(startEpisodeId), PlayOrigin.UI)

    override suspend fun playFeed(
        source: FeedSource,
        filters: FeedFilters,
        order: FeedOrder,
        startEpisodeId: Long?,
    ): PlayResult = start(StartRequest.Feed(source, filters, order, startEpisodeId), PlayOrigin.UI)

    override suspend fun play(origin: PlayOrigin): PlayResult = start(StartRequest.Resume, origin)

    private suspend fun start(
        request: StartRequest,
        origin: PlayOrigin,
    ): PlayResult {
        val ticket = intent.incrementAndGet()
        prePlay(origin, ticket)
        return serial { startLocked(request, ticket) }
    }

    /**
     * MS3 pull before play (10): runs outside the serial transition lock so OS commands, pause
     * and a newer intent are never queued behind the wait. Supersession during the pull throws
     * on the recheck; a pull failure is not a playback failure.
     */
    private suspend fun prePlay(
        origin: PlayOrigin,
        ticket: Long,
    ) {
        if (origin != PlayOrigin.UI) return
        try {
            starterFactory().beforeUiStart()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, failure) { "Pre-play pull failed" }
        }
        requireIntent(ticket)
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
                val currentId = engineCurrentId
                if (currentId != null && value.engine.state.value is EngineState.Error) {
                    // A recovered failure (device loss, network, a rejected file)
                    // re-opens the current item at its saved position. This is the
                    // user's explicit retry, so it does not spend the automatic
                    // re-prepare budget.
                    reprepare(currentId)
                } else if (currentId != null && completedBoundary == currentId) {
                    // The end-of-episode timer completed this item in place; a Play
                    // replays it from the start (06's M5 note).
                    replayFromStart(currentId)
                } else {
                    if (outcome.seekPositionMs == null) applyPausedPickup(value, currentId, ticket)
                    wantPlaying = true
                    value.engine.play()
                }
                publish()
                PlayResult.Started
            }

            is StartOutcome.Committed -> {
                val old = projector?.entries?.firstOrNull()?.item
                // Publish the generation before projectStart's suspending reads, so the
                // collector cannot mistake this own write for a foreign session and load it paused.
                value.planner.appliedOwnGeneration(outcome.queue.generation)
                if (!projectStart(outcome.queue, outcome.startPositionMs, ticket)) return PlayResult.ServiceUnavailable
                requireIntent(ticket)
                if (old != null && old.episodeId != outcome.queue.current?.episodeId) history.record(old)
                // The start's session transaction committed; a changed current is a transition.
                outcome.queue.current
                    ?.episodeId
                    ?.takeIf { it != old?.episodeId }
                    ?.let(::transitionBoundary)
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
        // The engine emits TracksKnown during setWindow; the current id must already be
        // visible to the event collector, which can run before loaded().
        engineCurrentId = queue.current?.episodeId
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
        // A new item is current, so a later terminal Ended is a new event.
        engineEndedHandled = false
        completedBoundary = null
        val row = projector?.entries?.firstOrNull()?.media
        following?.cancelAndJoin()
        following = null
        value.saver.clear()
        prepared = row != null
        engineCurrentId = row?.episodeId
        durationMs = row?.let { it.measuredDurationMs ?: it.durationMs }
        since = clock.now()
        if (row == null) {
            pendingTracksKnown.clear()
            followChapters(null)
            applySleep(sleepTimerCore.currentChanged(null))
            value.settings.followCurrent(null, null)
            effective.value = null
            current.value = null
            syncActive.value = PlaybackSyncActive(false)
            remoteGuard.currentChanged(null)
            return
        }
        remoteGuard.currentChanged(row.episodeId)
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
        value.saver.load(
            row.episodeId,
            since,
            source,
            replay = row.playedAt != null,
            allowed = { remoteGuard.allowsSaves(row.episodeId) },
        )
        value.saver.startPositionApplied(startMs)
        followSettings(row, group)
        followChapters(row.episodeId)
        pendingTracksKnown.remove(row.episodeId)?.let { applyTracksKnown(it) }
        applySleep(sleepTimerCore.currentChanged(row.episodeId))
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
        command { userPause() }
    }

    /** An explicit pause restores normal volume; the timer keeps its remaining time. */
    private suspend fun userPause() {
        wantPlaying = false
        runtime?.engine?.pause()
        sampleSleep()
        applySleep(sleepTimerCore.paused())
        flushPause()
        pausedBoundary()
        publish()
    }

    override fun togglePlayPause(origin: PlayOrigin) {
        val ticket = intent.incrementAndGet()
        scope.launch {
            // The UI pull runs outside the transition lock; wantPlaying is confined to this
            // dispatcher, and the serial block re-checks the intent anyway. A toggle that lands
            // as a pause skips the pull entirely.
            if (origin == PlayOrigin.UI && !wantPlaying) {
                try {
                    starterFactory().beforeUiStart()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w(TAG, failure) { "Pre-play pull failed" }
                }
            }
            guarded {
                requireIntent(ticket)
                if (wantPlaying) {
                    userPause()
                } else {
                    reportStart(startLocked(StartRequest.Resume, ticket))
                }
            }
        }
    }

    override fun seekTo(positionMs: Long) = command { seek(positionMs) }

    override fun skipBack() = command { seek((position() - skipBackMs).coerceAtLeast(0)) }

    override fun skipForward() = command { seek(position() + skipForwardMs) }

    /** The current in-app volume 0..1 (the menu's Volume up/down state). */
    fun getUserVolume(): Float = userVolume

    /** Sets the in-app volume 0..1; the engine follows once it exists. */
    fun setUserVolume(linear: Float) {
        val clamped = linear.coerceIn(0f, 1f)
        userVolume = clamped
        runtime?.engine?.setVolume(clamped)
    }

    private suspend fun seek(requestedMs: Long) {
        if (!prepared) return
        val value = requireNotNull(runtime)
        if (completedBoundary != null && completedBoundary == engineCurrentId) armReplay()
        val target = requestedMs.coerceAtLeast(0).let { durationMs?.let(it::coerceAtMost) ?: it }
        value.engine.seekTo(target)
        checkSave(value.saver.onSeek(target, durationMs, SeekOrigin.USER))
        publish(target)
    }

    /**
     * The completed boundary item stays current: the planner must not apply its
     * "current marked played" advance/end rule to it (Android passes the same freeze through
     * `QueueProjector`). A replay trigger clears the marker, so the rule resumes.
     */
    private fun frozenForPlanner(episodeId: Long): Boolean =
        completedBoundary == episodeId || remoteGuard.isFrozen(episodeId)

    /**
     * Arms a replay of the completed boundary item. A fresh `since` lets its saves through the
     * played-after-start guard again, and `replay = true` clears the played mark at the first
     * actual playback (06's M5 note, Android's `replayCompletedBoundary`).
     */
    private fun armReplay() {
        val value = runtime ?: return
        val id = engineCurrentId ?: return
        val projection = projector ?: return
        val source =
            if (projection.entries.firstOrNull()?.localUri != null) {
                PositionSource.DOWNLOAD
            } else {
                PositionSource.STREAM
            }
        since = clock.now()
        value.saver.load(id, since, source, replay = true, allowed = { remoteGuard.allowsSaves(id) })
        value.saver.startPositionApplied(0)
        completedBoundary = null
    }

    /**
     * A Play on the completed boundary item replays it from the start: the mark-played chain reset
     * its position, so the engine re-prepares at 0 and the armed saver clears the played mark at the
     * first actual playback.
     */
    private suspend fun replayFromStart(episodeId: Long) {
        val value = requireNotNull(runtime)
        val projection = projector ?: return
        val index = projection.entries.indexOfFirst { it.item.episodeId == episodeId }
        if (index < 0) return
        armReplay()
        value.engine.setWindow(projection.engineWindow, index, 0)
        wantPlaying = true
        value.engine.play()
        publish()
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
                    // The goBack transaction committed and the previous item is installed.
                    transitionBoundary(action.item.episodeId)
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
        // Re-read the live tail before deciding, including edits not yet collected by the projector.
        // The applied generation is only a lagging mirror; expecting it drops the commit
        // when an emission coalesced or an apply was skipped.
        val queue = value.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
        if (queue.current?.episodeId != current.episodeId) return
        val revision = SessionRevision(current.episodeId, queue.generation)
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
        // Publish the generation before the suspending reads below, so the collector cannot
        // mistake this own write for a foreign session and load it paused.
        value.planner.appliedOwnGeneration(generation)
        val committed = value.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
        if (committed.generation != generation || committed.current?.episodeId != next?.episodeId) return
        if (next == null) {
            value.engine.pause()
            projection.apply(committed, emptyList(), listOf(WindowOp.Clear), currentId)
            wantPlaying = false
            loaded(0)
            // The end-of-session transaction committed (06's Stopped boundary). `active` is
            // already cleared: a release reading the port sees nothing playing any more.
            stoppedBoundary()
            return
        }
        // The transition transaction committed with the departure saved (06's Transition).
        transitionBoundary(next.episodeId)
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
                if (state.playing) {
                    outputDeviceLost = false
                    pendingIssue = null
                    checkSave(value.saver.onPlaying())
                }
            }

            is EngineState.Buffering -> {
                if (state.episodeId != id) return
            }

            is EngineState.Error -> {
                recoverEngineError(state)
                return
            }

            EngineState.Ended -> {
                if (engineEndedHandled) return
                engineEndedHandled = true
                if (sleepTimerCore.state is SleepTimerState.EndOfEpisode) {
                    endOfEpisode()
                } else {
                    advance(PlaybackExit.END)
                }
                return
            }

            EngineState.Idle -> {
                Unit
            }
        }
        val advancing = state is EngineState.Ready && state.playing
        // Buffering stops the countdown but holds the fade factor; an explicit pause
        // restores the volume through userPause.
        applySleep(
            sleepTimerCore.advancing(
                if (advancing) SleepTimerCore.Advancement.PLAYING else SleepTimerCore.Advancement.STOPPED,
            ),
        )
        val wasAdvancing = current.value?.isPlaying == true
        if (!advancing && (wasAdvancing || state is EngineState.Error)) {
            flushPause()
            // Advancing stopped without a user command (an error or a stall's end): the
            // boundary follows the flush's save, like Android's onIsPlayingChanged(false).
            if (wasAdvancing) pausedBoundary()
        }
        publish()
    }

    /**
     * Applies 06's recovery rules and 11's error table to an engine error. A
     * skip-type failure skips the item; a network failure keeps it and waits for
     * a validated connection; a missing local file re-resolves to the stream; a
     * device failure pauses and saves. A successor's prepare failure skips that
     * successor and tries the following item.
     */
    private suspend fun recoverEngineError(state: EngineState.Error) {
        val value = runtime ?: return
        if (!prepared || state != value.engine.state.value) return
        pendingIssue = null
        val failedId = state.episodeId
        if (failedId != engineCurrentId) {
            successorPrepareFailed(failedId, state.error)
            return
        }
        when (state.error) {
            is EngineError.Output -> deviceLost()
            EngineError.Network -> networkLost(failedId)
            EngineError.LocalFileMissing -> localFileMissing(failedId)
            is EngineError.Engine -> pauseWithPlayerError()
            else -> skipUnplayable(failedId, requireNotNull(unplayableReason(state.error)))
        }
    }

    /** The 06 reason for an engine error, or null when the error is not a skip. */
    private fun unplayableReason(error: EngineError): UnplayableReason? =
        when (error) {
            is EngineError.Http -> httpReason(error.status)
            EngineError.AuthRequired -> UnplayableReason.AuthRequired
            EngineError.UnsupportedFormat -> UnplayableReason.UnsupportedFormat
            EngineError.NoMedia -> UnplayableReason.NoMedia
            is EngineError.ContentChanged -> UnplayableReason.Http(HTTP_RANGE_NOT_SATISFIABLE)
            is EngineError.YouTube -> UnplayableReason.YouTubeExtraction
            EngineError.Network, EngineError.LocalFileMissing, is EngineError.Output, is EngineError.Engine -> null
        }

    private fun httpReason(status: Int): UnplayableReason =
        if (status == HTTP_UNAUTHORIZED) UnplayableReason.AuthRequired else UnplayableReason.Http(status)

    /**
     * Skips an unplayable item with 06's rules: a Skipped event, then the next
     * queue item; after five consecutive skips the player pauses with
     * PLAYER_ERROR instead of spinning through the queue.
     */
    private suspend fun skipUnplayable(
        episodeId: Long,
        reason: UnplayableReason,
    ) {
        if (!budget.trySkip() || (projector?.entries?.size ?: 0) <= 1) {
            pauseWithPlayerError()
            return
        }
        notifications.emit(PlaybackEvent.Skipped(episodeId, titleOf(episodeId), reason))
        advance(PlaybackExit.ERROR_SKIP)
    }

    /**
     * A successor's preparation failed at the boundary (11 Transitions point 4).
     * The outgoing item has ended, so it is committed as a natural departure; the
     * failed successor is skipped with 06's rules and the following item is tried.
     */
    private suspend fun successorPrepareFailed(
        failedId: Long,
        error: EngineError,
    ) {
        val value = requireNotNull(runtime)
        val projection = projector ?: return
        val outgoingId = engineCurrentId ?: return
        val outgoing = projection.entries.firstOrNull { it.item.episodeId == outgoingId } ?: return
        // A failure for an item the projection already dropped is stale: no
        // skip to record and no budget to spend for it.
        val failedIndex = projection.entries.indexOfFirst { it.item.episodeId == failedId }
        if (failedIndex < 0) return
        val reason = unplayableReason(error)
        if (reason == null || !budget.trySkip()) {
            // A network or device failure preparing the successor is not a skip.
            pauseWithPlayerError()
            return
        }
        notifications.emit(PlaybackEvent.Skipped(failedId, titleOf(failedId), reason))
        val departure = value.saver.capture(position(), durationMs, PlaybackExit.END)?.departure
        val next = projection.entries.getOrNull(failedIndex + 1)
        // Expect the live session, not the projector's applied generation: it lags
        // when an emission coalesced or an apply was skipped, and the outgoing item
        // already ended either way.
        val live = value.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
        if (live.current?.episodeId != outgoingId) return
        val revision = SessionRevision(outgoingId, live.generation)
        val generation =
            if (next == null) {
                value.sessions.endSession(departure, revision, clock.now())
            } else {
                value.sessions.onTransition(departure, next.item.episodeId, next.item.origin, revision, clock.now())
            } ?: return
        // Publish the generation before the suspending reads below, so the collector cannot
        // mistake this own write for a foreign session and load it paused.
        value.planner.appliedOwnGeneration(generation)
        val committed = value.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
        if (committed.generation != generation || committed.current?.episodeId != next?.item?.episodeId) return
        if (next == null) {
            value.engine.pause()
            projection.apply(committed, emptyList(), listOf(WindowOp.Clear), outgoingId)
            wantPlaying = false
            loaded(0)
            return
        }
        val row = next.media ?: return
        val saved = value.positions.observe(next.item.episodeId).first()
        val startMs =
            StartPositionRule().startPositionMs(
                saved?.positionMs,
                saved?.updatedAt,
                row.measuredDurationMs ?: row.durationMs,
                row.playedAt != null || saved?.playedAt != null,
                clock.now(),
            )
        if (!projectStart(committed, startMs)) return
        history.record(outgoing.item)
        wantPlaying = true
        value.engine.play()
        publish()
    }

    /** 11 Device loss: pause and save; the next play opens the default device. */
    private suspend fun deviceLost() {
        if (outputDeviceLost) return
        outputDeviceLost = true
        wantPlaying = false
        runtime?.engine?.pause()
        flushPause()
        applySleep(sleepTimerCore.paused())
        publish()
    }

    /** MD2's route monitor: the device in use disappeared. */
    private suspend fun deviceRemoved() {
        runtime?.engine?.onDeviceRemoved()
        deviceLost()
    }

    /** 06: a network failure keeps the item and waits for a validated connection. */
    private suspend fun networkLost(episodeId: Long) {
        networkRetry = NetworkRetry(episodeId, clock.elapsedRealtime())
        wantPlaying = false
        runtime?.engine?.pause()
        flushPause()
        applySleep(sleepTimerCore.paused())
        publish()
    }

    private suspend fun networkRecovered() {
        val retry = networkRetry ?: return
        if (engineCurrentId != retry.episodeId ||
            clock.elapsedRealtime() - retry.failedAtMs >= FOREGROUND_WINDOW_MS
        ) {
            networkRetry = null
            return
        }
        networkRetry = null
        if (!budget.tryReprepare(retry.episodeId)) {
            pauseWithPlayerError()
            return
        }
        reprepare(retry.episodeId)
    }

    /** 06: a missing download re-resolves to the stream at the same position. */
    private suspend fun localFileMissing(episodeId: Long) {
        pendingIssue = PlaybackIssue.LOCAL_FILE_MISSING
        if (!budget.tryReprepare(episodeId)) {
            pauseWithPlayerError()
            return
        }
        reprepare(episodeId)
    }

    private suspend fun pauseWithPlayerError() {
        wantPlaying = false
        runtime?.engine?.pause()
        flushPause()
        applySleep(sleepTimerCore.paused())
        publish()
    }

    /**
     * Re-opens the current item at its last position (06's re-prepare). The engine
     * re-resolves the source and, after an output failure, opens a fresh device.
     */
    private suspend fun reprepare(episodeId: Long) {
        val value = requireNotNull(runtime)
        val projection = projector ?: return
        val index = projection.entries.indexOfFirst { it.item.episodeId == episodeId }
        if (index < 0) return
        val position = position().coerceAtLeast(0)
        value.engine.setWindow(projection.engineWindow, index, position)
        wantPlaying = true
        value.engine.play()
        publish()
    }

    private fun titleOf(episodeId: Long): String =
        projector
            ?.entries
            ?.firstOrNull { it.item.episodeId == episodeId }
            ?.media
            ?.title
            .orEmpty()

    private suspend fun engineEvent(event: EngineEvent) {
        if (!prepared) return
        when (event) {
            is EngineEvent.TracksKnown -> {
                if (event.episodeId == engineCurrentId) {
                    applyTracksKnown(event)
                } else if (projector?.entries?.any { it.item.episodeId == event.episodeId } == true) {
                    pendingTracksKnown[event.episodeId] = event
                }
            }

            is EngineEvent.ItemTransition -> {
                engineItemTransition(event)
                return
            }

            EngineEvent.DeviceLost -> {
                deviceLost()
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
        // The expected revision must be the live session's, not the projector's
        // applied generation: Room emissions conflate and a failed apply leaves the
        // projection behind, which silently dropped a transition the engine had
        // already made and desynced the session for good.
        val live = value.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
        if (live.current?.episodeId == toId) {
            // Another path (the collector's advance, a duplicated event) already
            // committed this arrival; only the local view needs to catch up.
            engineCurrentId = toId
            publish()
            return
        }
        if (live.current?.episodeId != fromId) return
        val outgoing = projection.entries.firstOrNull { it.item.episodeId == fromId } ?: return
        val arrival = projection.entries.firstOrNull { it.item.episodeId == toId } ?: return
        val row = arrival.media ?: return
        val revision = SessionRevision(fromId, live.generation)
        // A null capture means the saver was already unloaded; commit anyway or the
        // engine and session permanently desync for the want of one position save.
        val departure =
            value.saver
                .capture(event.fromPositionMs, durationMs, PlaybackExit.AUTO)
                ?.departure
                ?.takeIf { it.episodeId == fromId }
        val generation =
            value.sessions.onTransition(departure, toId, arrival.item.origin, revision, clock.now()) ?: return
        value.planner.appliedOwnGeneration(generation)
        engineCurrentId = toId
        remoteGuard.currentChanged(toId)
        // The transition transaction committed with the departure saved (06's Transition).
        transitionBoundary(toId)
        history.record(outgoing.item)
        followChapters(toId)
        pendingTracksKnown.remove(toId)?.let { applyTracksKnown(it) }
        applySleep(sleepTimerCore.currentChanged(toId))
        val group = context?.takeIf { it.type == ContextType.GROUP }?.id
        val settings = requireNotNull(value.settings.cached(row.podcastId, group))
        effective.value = settings
        value.engine.setSpeed(settings.speed.value)
        value.engine.setSkipSilence(settings.skipSilence.value)
        durationMs = row.measuredDurationMs ?: row.durationMs
        since = clock.now()
        val source = if (arrival.localUri != null) PositionSource.DOWNLOAD else PositionSource.STREAM
        value.saver.load(
            toId,
            since,
            source,
            replay = row.playedAt != null,
            allowed = { remoteGuard.allowsSaves(toId) },
        )
        value.saver.startPositionApplied(0)
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
        val plan =
            value.planner.plan(queue, media, playerWindow(), frozen = ::frozenForPlanner)
                ?: return
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

    // 06's paused-item pickup: a stored row outside this player's last write/apply —
    // a sync pull or a restore — is taken up on resume. The item and playing state are
    // rechecked after the suspend read, and a superseded intent abandons the seek.
    private suspend fun applyPausedPickup(
        value: DesktopPlaybackRuntime,
        episodeId: Long?,
        ticket: Long,
    ) {
        if (episodeId == null) return
        if ((value.engine.state.value as? EngineState.Ready)?.playing == true) return
        if (engineCurrentId != episodeId) return
        val stored = value.positions.observe(episodeId).first() ?: return
        requireIntent(ticket)
        if ((value.engine.state.value as? EngineState.Ready)?.playing == true) return
        if (engineCurrentId != episodeId) return
        val pickup = value.saver.pickupPositionMs(episodeId, stored.positionMs) ?: return
        value.engine.seekTo(pickup)
        checkSave(value.saver.onSeek(pickup, durationMs, SeekOrigin.CORE))
        publish(pickup)
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
            pendingIssue
                ?: (state as? EngineState.Error)?.error?.let { error ->
                    when (error) {
                        // A missing local file is re-resolved at once, so pendingIssue holds the
                        // UI issue until the re-resolve; the raw error must not leak it early.
                        EngineError.LocalFileMissing -> null

                        EngineError.Network -> PlaybackIssue.NETWORK_LOST

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
        syncActive.value = PlaybackSyncActive(playing, row.episodeId)
        evaluateChapter()
    }

    /**
     * Freezes the loaded item through [RemotePlayedGuard]; it keeps playing and its saves stop at
     * the write gate (06 Sync interplay: remote play position wins). Only the sync applier's
     * transaction calls this, at its projection commit gate.
     */
    private suspend fun onRemoteMarkedPlayed(
        episodeId: Long,
        deviceName: String,
    ) {
        remoteGuard.freeze(episodeId)
        watchUnfreeze(episodeId)
    }

    /** 10's pre-commit compensation: a rolled-back apply releases only its own freeze. */
    private suspend fun onRemoteMarkReverted(episodeId: Long) {
        remoteGuard.release(episodeId)
    }

    /**
     * Lifts the freeze once the marked row stops being effectively played (a later played or
     * listening mark overwrote it). The watch arms on the first effective played row so a revert
     * or a reverted replay's rows do not lift a freeze they did not cause.
     */
    private fun watchUnfreeze(episodeId: Long) {
        unfreezeWatches.remove(episodeId)?.cancel()
        unfreezeWatches[episodeId] =
            scope.launch {
                try {
                    var armed = false
                    val positions = runtime?.positions ?: return@launch
                    positions.observeMarks(episodeId).collect { row ->
                        when {
                            row != null && row.playedAt != null && row.startedAt == null -> {
                                armed = true
                            }

                            armed -> {
                                remoteGuard.lift(episodeId)
                                throw CancellationException("unfrozen")
                            }
                        }
                    }
                } catch (expected: CancellationException) {
                    // The lift itself, or a restart through a repeated freeze.
                } catch (failure: Exception) {
                    // A failed read keeps the freeze; stop-being-current still lifts it.
                } finally {
                    // A newer watch may already have replaced this one; keep its entry.
                    if (unfreezeWatches[episodeId] === coroutineContext[Job]) {
                        unfreezeWatches.remove(episodeId)
                    }
                }
            }
    }

    private suspend fun flushPause() {
        if (prepared) checkSave(requireNotNull(runtime).saver.onPause(position(), durationMs))
    }

    private fun sleepElapsed(): Long {
        val now = clock.elapsedRealtime()
        val elapsed = (now - sleepSampledAt).coerceAtLeast(0)
        sleepSampledAt = now
        return elapsed
    }

    private suspend fun sampleSleep() {
        applySleep(sleepTimerCore.tick(sleepElapsed()))
    }

    /**
     * Applies the shared core's commands. The desktop has no EndOfItemPauseArbiter:
     * the end-of-episode pause is driven by the core state at [EngineState.Ended],
     * so [SleepTimerCore.Effect.ArmEndOfItem] only records what that handler reads.
     */
    private suspend fun applySleep(effects: List<SleepTimerCore.Effect>) {
        val value = runtime ?: return
        effects.forEach { effect ->
            when (effect) {
                is SleepTimerCore.Effect.Fade -> {
                    value.engine.setFade(effect.factor)
                }

                is SleepTimerCore.Effect.ArmEndOfItem -> {
                    Unit
                }

                SleepTimerCore.Effect.Pause -> {
                    wantPlaying = false
                    value.engine.pause()
                    flushPause()
                    pausedBoundary()
                    publish()
                }

                SleepTimerCore.Effect.Fired -> {
                    notifications.emit(PlaybackEvent.SleepTimerFired)
                }
            }
        }
        mutableSleepTimer.value = sleepTimerCore.state
    }

    /**
     * The end-of-episode timer completes the item in place: the shared boundary write
     * marks it played, the session stays on it and the timer clears (06 Sleep timer).
     */
    private suspend fun endOfEpisode() {
        val value = requireNotNull(runtime)
        val id = engineCurrentId ?: return
        projector ?: return
        val capture = value.saver.capture(position(), durationMs, PlaybackExit.END)
        if (capture != null && capture.departure.writePosition && capture.departure.markPlayed) {
            val live = value.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
            if (value.sessions.markBoundaryPlayed(id, SessionRevision(id, live.generation), clock.now())) {
                completedBoundary = id
            }
        }
        applySleep(sleepTimerCore.endOfItem())
        publish()
    }

    private suspend fun applyTracksKnown(event: EngineEvent.TracksKnown) {
        if (event.episodeId != engineCurrentId) return
        event.durationMs?.takeIf { it > 0 }?.let {
            durationMs = it
            requireNotNull(runtime).positions.setMeasuredDuration(event.episodeId, it)
        }
        persistEmbeddedChapters(event)
    }

    private suspend fun persistEmbeddedChapters(event: EngineEvent.TracksKnown) {
        val value = runtime ?: return
        event.chapters
            .groupBy { it.source }
            .forEach { (source, rows) ->
                if (rows.isEmpty() || source in writtenChapterSources) return@forEach
                try {
                    value.chapterRepository.replace(event.episodeId, source, rows)
                    writtenChapterSources += source
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w(TAG, failure) { "Could not persist embedded chapters" }
                }
            }
    }

    /**
     * Observes the winning rows of the current episode and asks for Podcasting 2.0 JSON
     * enrichment, as Android's Chapters does. The observation already publishes the
     * winning source, so container rows and JSON rows need no second merge.
     */
    private suspend fun followChapters(episodeId: Long?) {
        chapterFollowing?.cancelAndJoin()
        chapterFollowing = null
        chapterBoundary?.cancel()
        chapterBoundary = null
        chapterIndex = ChapterIndex(emptyList())
        writtenChapterSources.clear()
        mutableCurrentChapter.value = null
        val value = runtime ?: return
        if (episodeId == null) return
        chapterFollowing =
            scope.launch {
                value.chapterRepository.observe(episodeId).collect { rows ->
                    guarded {
                        if (episodeId != engineCurrentId) return@guarded
                        chapterIndex = ChapterIndex(rows)
                        evaluateChapter()
                    }
                }
            }
        // Best-effort enrichment; a failure leaves stored and lower sources in place.
        scope.launch {
            try {
                value.chapterRepository.ensureLoaded(episodeId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, failure) { "Could not load chapters" }
            }
        }
    }

    /**
     * Publishes the containing chapter and schedules the next boundary, as Android's
     * Chapters does; a gap or an explicit end clears the state without waiting for the
     * next start.
     */
    private fun evaluateChapter() {
        chapterBoundary?.cancel()
        chapterBoundary = null
        val id = engineCurrentId
        val index = id?.let { chapterIndex.current(position()) }
        mutableCurrentChapter.value =
            id?.let { episode ->
                index?.let { CurrentChapter(episode, it, chapterIndex.chapters.size, chapterIndex.chapters[it]) }
            }
        if (current.value?.isPlaying != true) return
        val next = chapterIndex.nextBoundary(position()) ?: return
        val speed =
            effective.value
                ?.speed
                ?.value
                ?.takeIf { it > 0f } ?: return
        val waitMs = ceil((next - position()).coerceAtLeast(0).toDouble() / speed).toLong().coerceAtLeast(1)
        chapterBoundary =
            scope.launch {
                delay(waitMs)
                guarded { evaluateChapter() }
            }
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
            val generation =
                value.sessions.clearCurrent(
                    value.saver.capture(position(), durationMs, PlaybackExit.DISMISS)?.departure,
                    clock.now(),
                    allowed = { ticket == intent.get() },
                )
            // Publish the generation before the suspending reads below, so the collector cannot
            // mistake this own write for a foreign session and load it paused.
            value.planner.appliedOwnGeneration(generation)
            requireIntent(ticket)
            value.engine.pause()
            val queue = value.sessions.virtualQueue(QueueWindowPlanner.CONTEXT_SIZE)
            requireNotNull(projector).apply(queue, emptyList(), listOf(WindowOp.Clear), engineCurrentId)
            wantPlaying = false
            loaded(0)
            history.clear()
            // The dismissal transaction committed (06's Stopped boundary), after the save and
            // the `active` clear — a release reading the port sees nothing playing any more.
            stoppedBoundary()
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
                sampleSleep()
                applySleep(sleepTimerCore.set(SleepTimerMode.Off, null))
                try {
                    flushPause()
                    // `active` clears before the quit boundary, so a release reading the port
                    // sees nothing playing; the boundary itself still follows the final save.
                    syncActive.value = PlaybackSyncActive(false)
                    remoteGuard.currentChanged(null)
                    stoppedBoundary()
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

    override fun setSleepTimer(mode: SleepTimerMode) {
        if (mode is SleepTimerMode.Minutes && !SleepTimerMode.isValidMinutes(mode.minutes)) return
        command {
            sampleSleep()
            applySleep(sleepTimerCore.set(mode, engineCurrentId))
        }
    }

    override fun extendSleepTimer(minutes: Int) {
        if (!SleepTimerMode.isValidMinutes(minutes)) return
        command {
            sampleSleep()
            applySleep(sleepTimerCore.extend(minutes))
        }
    }

    override fun nextChapter() = command { chapterSeek(ChapterDirection.NEXT) }

    override fun previousChapter() = command { chapterSeek(ChapterDirection.PREVIOUS) }

    override fun grantMeteredStreaming() = Unit

    private suspend fun chapterSeek(direction: ChapterDirection) {
        if (!prepared) return
        val target =
            when (direction) {
                ChapterDirection.NEXT -> chapterIndex.next(position())
                ChapterDirection.PREVIOUS -> chapterIndex.previous(position())
            } ?: return
        seek(target)
    }

    private enum class ChapterDirection { NEXT, PREVIOUS }

    /** A network failure kept for an automatic re-prepare (06 Error recovery). */
    private data class NetworkRetry(
        val episodeId: Long,
        val failedAtMs: Long,
    )

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
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_RANGE_NOT_SATISFIABLE = 416

        /** 06 Error recovery: the automatic network re-prepare window. */
        const val FOREGROUND_WINDOW_MS = 10 * 60_000L
    }
}

/**
 * The queue and session flows invalidate independently, so a fresh queue emission can meet a
 * stale session emission. Only a session that carries the queue's generation is the context of
 * that queue; any other pairing keeps the last coherent context, or a transition landing in the
 * gap would resolve the arrival's settings with a null group.
 */
internal fun coherentContext(
    queue: VirtualQueue,
    session: PlaySessionInfo?,
    previous: PlayContextInfo?,
): PlayContextInfo? =
    when {
        session != null && session.generation == queue.generation -> session.context
        previous != null -> previous
        else -> null
    }
