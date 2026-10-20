// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.ContextEligibility
import ch.lkmc.neutrodyne.core.domain.PlayContextResolver
import ch.lkmc.neutrodyne.core.model.ContextType
import ch.lkmc.neutrodyne.core.model.FeedFilters
import ch.lkmc.neutrodyne.core.model.FeedOrder
import ch.lkmc.neutrodyne.core.model.FeedSource
import ch.lkmc.neutrodyne.core.model.MediaFilter
import ch.lkmc.neutrodyne.core.model.PlayContextSpec

/** Configured query answers; records inputs without reproducing 05's SQL predicates. */
class FakePlayContextResolver : PlayContextResolver {
    var specResult = PlayContextSpec(ContextType.ALL, null, FeedOrder.NEWEST_FIRST, 0, MediaFilter.ALL, null, false)
    var startResult: Long? = null
    var failure: Throwable? = null
    val specCalls = mutableListOf<Triple<FeedSource, FeedFilters, FeedOrder>>()
    val eligibilityCalls = mutableListOf<ContextEligibility>()
    val startCalls = mutableListOf<PlayContextSpec>()

    override suspend fun spec(
        source: FeedSource,
        filters: FeedFilters,
        order: FeedOrder,
    ): PlayContextSpec {
        specCalls += Triple(source, filters, order)
        failure?.let { throw it }
        return specResult
    }

    override suspend fun startItem(
        spec: PlayContextSpec,
        eligibility: ContextEligibility,
    ): Long? {
        startCalls += spec
        eligibilityCalls += eligibility
        failure?.let { throw it }
        return startResult
    }
}
