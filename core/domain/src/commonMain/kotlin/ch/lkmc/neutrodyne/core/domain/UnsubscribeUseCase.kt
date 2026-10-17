// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.common.Log
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The unsubscribe use case (03 Unsubscribe and other podcast operations, D24): invoked after the
 * UI's confirmation (08 shows the downloaded-episode count). Pauses playback and deletes download
 * files through the optional `PlaybackController`/`DownloadController` dependencies, which arrive
 * with M4 and M6. Notification cleanup runs after the database commit. `PodcastRepository.unsubscribe`
 * flushes `FetchStateBatcher`, cascades, unpins artwork and reschedules.
 *
 * `CredentialCommitCoordinator` (M1b) wraps the cascade once it exists.
 */
@Inject
class UnsubscribeUseCase(
    private val podcasts: PodcastRepository,
    private val poster: NewEpisodePoster,
) {
    /** Unsubscribes [podcastIds]; returns the ids that were actually removed. */
    suspend operator fun invoke(podcastIds: List<Long>): List<Long> {
        val removed = podcasts.unsubscribe(podcastIds)
        if (removed.isEmpty()) return removed

        // The cascade has committed; cancellation must not leave its notification lines behind.
        withContext(NonCancellable) {
            try {
                poster.removePodcasts(removed)
            } catch (error: Exception) {
                Log.w("Unsubscribe", error) { "new-episode notification cleanup failed" }
            }
        }
        return removed
    }
}
