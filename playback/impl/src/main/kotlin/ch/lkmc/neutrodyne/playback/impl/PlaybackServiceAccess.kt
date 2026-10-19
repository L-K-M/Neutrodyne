// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.common.ApplicationScope
import ch.lkmc.neutrodyne.core.common.Clock
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NetworkMonitor
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.domain.ChapterRepository
import ch.lkmc.neutrodyne.core.domain.PlaySessionStore
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.domain.PositionStore
import ch.lkmc.neutrodyne.core.domain.QueueRepository
import ch.lkmc.neutrodyne.download.api.LocalMediaIndex
import ch.lkmc.neutrodyne.playback.core.EffectivePlaybackSettings
import ch.lkmc.neutrodyne.playback.core.PlayStarter
import ch.lkmc.neutrodyne.playback.core.PlaybackHistory
import ch.lkmc.neutrodyne.playback.core.PositionSaver
import ch.lkmc.neutrodyne.playback.core.QueueWindowPlanner
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/** Injecting a framework service must not walk a database-backed constructor on main. */
@Inject
public class PlaybackServiceAccess internal constructor(
    internal val playerFactory: Provider<PlayerFactory>,
    internal val resolver: EpisodeResolver,
    internal val index: EpisodeSourceIndex,
    internal val prefs: PlaybackPrefs,
    internal val hub: PlaybackStateHub,
    internal val gate: MeteredStreamingGate,
    internal val tree: MediaLibraryTree,
    internal val network: NetworkMonitor,
    internal val clock: Clock,
    internal val local: LocalMediaIndex,
    internal val capabilities: YouTubeCapabilitiesSource,
    internal val history: PlaybackHistory,
    @ApplicationScope internal val applicationScope: CoroutineScope,
    @Dispatcher(NeutrodyneDispatchers.IO) internal val io: CoroutineDispatcher,
    private val opener: DatabaseOpener,
    private val starter: Provider<PlayStarter>,
    private val planner: Provider<QueueWindowPlanner>,
    private val saver: Provider<PositionSaver>,
    private val effective: Provider<EffectivePlaybackSettings>,
    private val sessions: Provider<PlaySessionStore>,
    private val queues: Provider<QueueRepository>,
    private val positions: Provider<PositionStore>,
    private val lookup: Provider<PlaybackMediaLookup>,
    private val items: Provider<MediaItemFactory>,
    internal val chapters: Provider<ChapterRepository>,
) {
    internal suspend fun open(): PlaybackPorts =
        withContext(io) {
            opener.awaitOpen()
            prefs.start(applicationScope)
            prefs.awaitReady()
            PlaybackPorts(
                starter(),
                planner(),
                saver(),
                effective(),
                sessions(),
                queues(),
                positions(),
                lookup(),
                items(),
            )
        }
}

internal data class PlaybackPorts(
    val starter: PlayStarter,
    val planner: QueueWindowPlanner,
    val saver: PositionSaver,
    val effective: EffectivePlaybackSettings,
    val sessions: PlaySessionStore,
    val queues: QueueRepository,
    val positions: PositionStore,
    val lookup: PlaybackMediaLookup,
    val items: MediaItemFactory,
)

@ContributesTo(AppScope::class)
public interface PlaybackServiceInjector {
    public fun inject(service: NeutrodynePlaybackService)
}
