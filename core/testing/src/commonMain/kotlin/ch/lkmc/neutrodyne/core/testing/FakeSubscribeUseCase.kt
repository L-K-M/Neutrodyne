// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.SubscribeError
import ch.lkmc.neutrodyne.core.domain.SubscribeUseCase

/**
 * In-memory [SubscribeUseCase] (09 `:core:testing` inventory): [nextOutcome] is the canned
 * answer, [subscribed] records `(previewId, groupIds)` pairs.
 */
class FakeSubscribeUseCase : SubscribeUseCase {
    var nextOutcome: Outcome<Long, SubscribeError> = Outcome.Success(1L)

    val subscribed = mutableListOf<Pair<String, Set<Long>>>()

    override suspend fun invoke(
        previewId: String,
        groupIds: Set<Long>,
    ): Outcome<Long, SubscribeError> {
        subscribed += previewId to groupIds
        return nextOutcome
    }
}
