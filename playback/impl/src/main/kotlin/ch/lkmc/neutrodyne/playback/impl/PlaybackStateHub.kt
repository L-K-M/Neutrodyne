// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.domain.PositionStore
import ch.lkmc.neutrodyne.core.domain.QueueRepository
import ch.lkmc.neutrodyne.core.model.CurrentChapter
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
import ch.lkmc.neutrodyne.core.model.SleepTimerState
import ch.lkmc.neutrodyne.playback.api.NowPlaying
import ch.lkmc.neutrodyne.playback.api.PlaybackEvent
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import ch.lkmc.neutrodyne.playback.api.PlayerPhase
import ch.lkmc.neutrodyne.playback.api.PositionSnapshot
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.atomic.AtomicLong

/** Observation never binds a controller. A detached service falls back to durable state. */
@OptIn(ExperimentalCoroutinesApi::class)
@SingleIn(AppScope::class)
@Inject
internal class PlaybackStateHub(
    opener: DatabaseOpener,
    queues: Provider<QueueRepository>,
    media: Provider<PlaybackMediaLookup>,
    positions: Provider<PositionStore>,
    private val gate: MeteredStreamingGate,
    private val clock: Clock,
    @ApplicationScope scope: CoroutineScope,
    @Dispatcher(NeutrodyneDispatchers.IO) io: CoroutineDispatcher,
) : PlaybackStateSource {
    private val active = MutableStateFlow<ServiceBridge?>(null)
    private val playbackIntents = AtomicLong()
    val bridge: ServiceBridge? get() = active.value
    private val emitted = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
    override val events = emitted.asSharedFlow()
    private val sleepTimerState = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    override val sleepTimer = sleepTimerState.asStateFlow()
    private val chapterState = MutableStateFlow<CurrentChapter?>(null)
    override val currentChapter = chapterState.asStateFlow()
    override val pendingMeteredConsent = gate.pendingConsent

    fun updateSleepTimer(state: SleepTimerState) {
        sleepTimerState.value = state
    }

    fun updateCurrentChapter(chapter: CurrentChapter?) {
        chapterState.value = chapter
    }

    /** The (episodeId, [NowPlaying.loadId]) of the latest live-published item — one slot, only
     * ever written from the bridge branch, so a durable fallback for that same item keeps its
     * identity across service attach/detach while a real load boundary clears it. */
    private val lastLiveLoad = MutableStateFlow<Pair<Long, Long>?>(null)

    private val durable =
        flow {
            opener.awaitOpen()
            var mintedId: Long? = null
            var mintedToken = 0L
            emitAll(
                queues()
                    .observeSession()
                    .map { session ->
                        val id = session?.currentEpisodeId
                        if (id == null) {
                            mintedId = null
                        } else if (id != mintedId) {
                            mintedId = id
                            mintedToken =
                                ch.lkmc.neutrodyne.playback.api
                                    .nextNowPlayingLoadId()
                            // A genuine load boundary in the durable view: the remembered live
                            // identity must not outlive it.
                            lastLiveLoad.value = null
                        }
                        session to mintedToken
                    }.flatMapLatest { (session, minted) ->
                        val id = session?.currentEpisodeId
                        if (id ==
                            null
                        ) {
                            flowOf(null)
                        } else {
                            combine(media().observe(setOf(id)), positions().observe(id)) { rows, stored ->
                                rows[id]?.let { row ->
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
                                        PositionSnapshot(
                                            id,
                                            stored?.positionMs ?: 0,
                                            0,
                                            row.measuredDurationMs ?: row.durationMs,
                                            1f,
                                            false,
                                            clock.elapsedRealtime(),
                                        ),
                                        false,
                                        null,
                                        null,
                                        session.context,
                                        lastLiveLoad.value
                                            ?.takeIf { it.first == session.currentEpisodeId }
                                            ?.second
                                            ?: mintedToken,
                                    )
                                }
                            }
                        }
                    },
            )
        }.flowOn(io)

    override val nowPlaying: StateFlow<NowPlaying?> =
        active
            .flatMapLatest { bridge -> bridge?.nowPlaying ?: durable }
            .onEach { now ->
                if (now != null && active.value != null) {
                    lastLiveLoad.value = now.episodeId to now.loadId
                }
            }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    override val effectivePlayback: StateFlow<EffectivePlayback?> =
        active
            .flatMapLatest {
                it?.effectivePlayback ?: flowOf(null)
            }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    override val positionTicks: Flow<PositionSnapshot> =
        nowPlaying.flatMapLatest { item ->
            flow {
                val snapshot = item?.position ?: return@flow
                emit(snapshot)
                while (snapshot.advancing) {
                    delay(TICK_MS)
                    val now = clock.elapsedRealtime()
                    emit(snapshot.copy(positionMs = snapshot.positionAt(now), sampledAtElapsedMs = now))
                }
            }
        }

    // Starts and pauses share one ticket across UI and Media3 surfaces.
    fun newPlaybackIntent(): Long {
        val intent = playbackIntents.incrementAndGet()
        gate.clearPending()
        return intent
    }

    fun isCurrentPlaybackIntent(intent: Long): Boolean = playbackIntents.get() == intent

    fun attach(bridge: ServiceBridge) {
        check(active.value == null)
        active.value = bridge
    }

    fun detach(bridge: ServiceBridge) {
        if (active.value === bridge) active.value = null
    }

    fun event(event: PlaybackEvent) {
        emitted.tryEmit(event)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val TICK_MS = 1_000L
    }
}
