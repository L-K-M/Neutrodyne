// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.Log
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The unsubscribe use case (03 Unsubscribe and other podcast operations, D24): invoked after the
 * UI's confirmation (08 shows the downloaded-episode count). Pauses and flushes active playback before the cascade through `PlaybackBeforeUnsubscribe`.
 * Download file cleanup arrives with M6. Notification cleanup runs after the database commit. `PodcastRepository.unsubscribe`
 * flushes `FetchStateBatcher`, cascades, unpins artwork and reschedules.
 *
 * `CredentialCommitCoordinator` (M1b) wraps the cascade once it exists.
 */
@Inject
class UnsubscribeUseCase(
    private val podcasts: PodcastRepository,
    private val poster: NewEpisodePoster,
    private val playback: PlaybackBeforeUnsubscribe = PlaybackBeforeUnsubscribe.None,
) {
    /** Unsubscribes [podcastIds]; returns the ids that were actually removed. */
    suspend operator fun invoke(podcastIds: List<Long>): List<Long> =
        // The cascade and the cleanup of its notification lines finish together: a caller
        // cancelled once the cascade commits would otherwise never receive the removed ids.
        withContext(NonCancellable) {
            playback.pause(podcastIds)
            val removed = podcasts.unsubscribe(podcastIds)
            if (removed.isNotEmpty()) {
                try {
                    poster.removePodcasts(removed)
                } catch (error: Exception) {
                    Log.w("Unsubscribe", error) { "new-episode notification cleanup failed" }
                }
            }
            removed
        }
}
