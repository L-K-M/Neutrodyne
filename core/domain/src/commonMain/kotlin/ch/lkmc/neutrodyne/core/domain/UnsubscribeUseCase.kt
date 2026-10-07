// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import dev.zacsweers.metro.Inject

/**
 * The unsubscribe use case (03 Unsubscribe and other podcast operations, D24): invoked after the
 * UI's confirmation (08 shows the downloaded-episode count). Pauses playback and deletes download
 * files through the optional `PlaybackController`/`DownloadController` dependencies, which arrive
 * with M4 and M6 — until then only the database part runs. `PodcastRepository.unsubscribe`
 * flushes `FetchStateBatcher`, cascades, unpins artwork and reschedules.
 *
 * `CredentialCommitCoordinator` (M1b) wraps the cascade once it exists.
 */
@Inject
class UnsubscribeUseCase(
    private val podcasts: PodcastRepository,
) {
    /** Unsubscribes [podcastIds]; returns the ids that were actually removed. */
    suspend operator fun invoke(podcastIds: List<Long>): List<Long> =
        podcasts.unsubscribe(podcastIds)
}
