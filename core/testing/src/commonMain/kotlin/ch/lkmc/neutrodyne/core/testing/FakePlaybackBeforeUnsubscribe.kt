// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.PlaybackBeforeUnsubscribe

class FakePlaybackBeforeUnsubscribe : PlaybackBeforeUnsubscribe {
    val calls = mutableListOf<List<Long>>()
    var beforePause: suspend (List<Long>) -> Unit = {}

    override suspend fun pause(podcastIds: List<Long>) {
        calls += podcastIds.toList()
        beforePause(podcastIds)
    }
}
