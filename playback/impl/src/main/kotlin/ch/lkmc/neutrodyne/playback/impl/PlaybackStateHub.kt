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
import ch.lkmc.neutrodyne.core.model.EffectivePlayback
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/** Observation never binds a controller. A detached service falls back to durable state. */
@OptIn(ExperimentalCoroutinesApi::class)
@SingleIn(AppScope::class)
@Inject
internal class PlaybackStateHub(
    opener: DatabaseOpener,
    queues: Provider<QueueRepository>,
    media: Provider<PlaybackMediaLookup>,
    positions: Provider<PositionStore>,
    gate: MeteredStreamingGate,
    private val clock: Clock,
    @ApplicationScope scope: CoroutineScope,
    @Dispatcher(NeutrodyneDispatchers.IO) io: CoroutineDispatcher,
) : PlaybackStateSource {
    private val active = MutableStateFlow<ServiceBridge?>(null)
    val bridge: ServiceBridge? get() = active.value
    private val emitted = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
    override val events = emitted.asSharedFlow()
    override val pendingMeteredConsent = gate.pendingConsent

    private val durable =
        flow {
            opener.awaitOpen()
            emitAll(
                queues().observeSession().flatMapLatest { session ->
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
                                )
                            }
                        }
                    }
                },
            )
        }.flowOn(io)

    override val nowPlaying: StateFlow<NowPlaying?> =
        active
            .flatMapLatest { bridge ->
                bridge?.nowPlaying ?: durable
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
