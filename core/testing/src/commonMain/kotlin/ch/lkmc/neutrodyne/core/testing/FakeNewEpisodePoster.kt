// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.NewEpisodePoster
import ch.lkmc.neutrodyne.core.model.NewEpisodeNotice

class FakeNewEpisodePoster : NewEpisodePoster {
    val posts = mutableListOf<List<NewEpisodeNotice>>()
    val removals = mutableListOf<List<Long>>()
    var onPost: suspend (List<NewEpisodeNotice>) -> Unit = {}
    var onRemove: suspend (List<Long>) -> Unit = {}

    override suspend fun post(notices: List<NewEpisodeNotice>) {
        posts += notices.toList()
        onPost(notices)
    }

    override suspend fun removePodcasts(podcastIds: List<Long>) {
        removals += podcastIds.toList()
        onRemove(podcastIds)
    }
}
