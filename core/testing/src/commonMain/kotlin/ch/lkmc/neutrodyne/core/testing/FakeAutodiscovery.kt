// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.feeds.discovery.Autodiscovery
import ch.lkmc.neutrodyne.feeds.discovery.DiscoveredFeed
import okio.Source

/** Does not read/close the caller's source or perform any I/O. W3 tests supply extraction results. */
class FakeAutodiscovery : Autodiscovery {
    var answer: List<DiscoveredFeed> = emptyList()
    val pages = mutableListOf<Pair<String?, String>>()

    override fun candidates(
        html: Source,
        charset: String?,
        pageUrl: String,
    ): List<DiscoveredFeed> {
        pages += charset to pageUrl
        return answer.toList()
    }
}
