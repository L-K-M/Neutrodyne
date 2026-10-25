// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.lifecycle.ProcessLifecycleOwner
import ch.lkmc.neutrodyne.core.common.AppInitializer
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.domain.PlaybackBeforeUnsubscribe
import ch.lkmc.neutrodyne.core.domain.StartGate
import ch.lkmc.neutrodyne.playback.api.PlaybackController
import ch.lkmc.neutrodyne.playback.api.PlaybackMaintenance
import ch.lkmc.neutrodyne.playback.api.PlaybackStateSource
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@ContributesTo(AppScope::class)
@BindingContainer
public object PlaybackBindings {
    @Provides internal fun controller(value: PlaybackControllerImpl): PlaybackController = value

    @Provides internal fun state(value: PlaybackStateHub): PlaybackStateSource = value

    @Provides internal fun maintenance(value: StreamingCache): PlaybackMaintenance = value

    @Provides @IntoSet
    internal fun gate(value: MeteredStreamingGate): StartGate = value

    @Provides internal fun unsubscribe(value: PlaybackUnsubscribeGuard): PlaybackBeforeUnsubscribe = value
}

@ContributesIntoSet(AppScope::class)
@Inject
internal class PlayerConnectionInitializer(
    private val connection: PlayerConnection,
) : AppInitializer {
    override val order: Int = 300

    override suspend fun run() =
        withContext(Dispatchers.Main.immediate) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(connection)
        }
}

@Inject
internal class PlaybackUnsubscribeGuard(
    private val hub: PlaybackStateHub,
) : PlaybackBeforeUnsubscribe {
    override suspend fun pause(podcastIds: List<Long>) =
        withContext(Dispatchers.Main.immediate) {
            hub.bridge?.pauseBeforeUnsubscribe(podcastIds)
            Unit
        }
}
