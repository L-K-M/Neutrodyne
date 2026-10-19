// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.session.MediaSession
import ch.lkmc.neutrodyne.core.common.Dispatcher
import ch.lkmc.neutrodyne.core.common.NeutrodyneDispatchers
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.core.domain.PositionStore
import ch.lkmc.neutrodyne.core.domain.QueueRepository
import ch.lkmc.neutrodyne.playback.core.refusal
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** A card is a saved-state read, never a playback runtime, resolver pin or prepare. */
@Inject
internal class SavedResumption(
    private val opener: DatabaseOpener,
    private val queues: Provider<QueueRepository>,
    private val media: Provider<PlaybackMediaLookup>,
    private val positions: Provider<PositionStore>,
    private val items: Provider<MediaItemFactory>,
    private val capabilities: YouTubeCapabilitiesSource,
    @Dispatcher(NeutrodyneDispatchers.IO) private val io: CoroutineDispatcher,
) {
    suspend fun load(): MediaSession.MediaItemsWithStartPosition =
        withContext(io) {
            opener.awaitOpen()
            val id =
                queues().observeSession().first()?.currentEpisodeId
                    ?: throw UnsupportedOperationException("No saved current episode")
            val row = media().get(id) ?: throw UnsupportedOperationException("Saved episode was removed")
            if (row.refusal(capabilities.capabilities.value) !=
                null
            ) {
                throw UnsupportedOperationException("Saved episode is not playable")
            }
            val position =
                positions()
                    .observe(id)
                    .first()
                    ?.positionMs
                    ?.coerceAtLeast(0) ?: 0
            MediaSession.MediaItemsWithStartPosition(listOf(items().build(row, position)), 0, position)
        }
}
