// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.RefreshController
import ch.lkmc.neutrodyne.core.domain.RefreshScope
import ch.lkmc.neutrodyne.core.domain.RefreshStatus
import ch.lkmc.neutrodyne.core.model.FeedSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [RefreshController] (09 `:core:testing` inventory): records requests and lets tests
 * drive [status] (e.g. `running = true` during a pull-to-refresh assertion).
 */
class FakeRefreshController : RefreshController {
    val status =
        MutableStateFlow(RefreshStatus(running = false, scope = null, done = 0, total = 0, lastRunFinishedAt = null))

    val calls = mutableListOf<String>()

    override fun refreshNow(scope: RefreshScope) {
        calls += "refreshNow($scope)"
    }

    override suspend fun reschedulePeriodic() {
        calls += "reschedulePeriodic()"
    }

    override fun observeStatus(): Flow<RefreshStatus> = status

    override fun refreshFeed(source: FeedSource) {
        calls += "refreshFeed($source)"
    }

    override fun loadOlderEpisodes(podcastId: Long) {
        calls += "loadOlderEpisodes($podcastId)"
    }

    override fun requestFirstFetch() {
        calls += "requestFirstFetch()"
    }
}
