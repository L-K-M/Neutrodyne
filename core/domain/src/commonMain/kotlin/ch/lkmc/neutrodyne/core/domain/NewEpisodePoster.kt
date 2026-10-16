// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.NewEpisodeNotice

/** Platform posting and unsubscribe cleanup (03 New-episode notifications). */
interface NewEpisodePoster {
    /** Once per run, with a non-empty list; each channel appears at most once. */
    suspend fun post(notices: List<NewEpisodeNotice>)

    /** Drop these podcasts' lines from currently shown notifications. */
    suspend fun removePodcasts(podcastIds: List<Long>)
}
