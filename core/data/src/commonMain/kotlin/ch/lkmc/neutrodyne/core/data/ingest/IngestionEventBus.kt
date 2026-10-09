// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.ingest

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.domain.IngestionEvents
import ch.lkmc.neutrodyne.core.model.NewEpisodes
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ExposeImplBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The `NewEpisodes` bus of 03 Events (`:core:data` singleton): `tryEmit` only, in-process and best
 * effort — consumers that must not miss work re-derive it from the database.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
// `FeedRefresher` injects the concrete bus to reach `emit`, not the `IngestionEvents` supertype.
@ExposeImplBinding
@Inject
internal class IngestionEventBus : IngestionEvents {
    private val bus =
        MutableSharedFlow<NewEpisodes>(
            replay = 0,
            extraBufferCapacity = 256,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )

    override val newEpisodes: SharedFlow<NewEpisodes> = bus

    /** `NewEpisodes` emission of the ingest commit (03 step 11: after the transaction, never suspends). */
    fun emit(
        podcastId: Long,
        episodeIds: List<Long>,
        initialFetch: Boolean,
    ) {
        bus.tryEmit(NewEpisodes(podcastId, episodeIds, initialFetch))
    }
}
