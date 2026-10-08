// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.common.Outcome
import ch.lkmc.neutrodyne.core.domain.SubscribeError
import ch.lkmc.neutrodyne.core.domain.SubscribeUseCase

import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [SubscribeUseCase] (09 `:core:testing` inventory): [nextOutcome] is the canned
 * answer, [subscribed] records `(previewId, groupIds)` pairs. [gate] parks the next call after
 * recording it, for tests that need the operation in flight while other calls happen.
 */
class FakeSubscribeUseCase : SubscribeUseCase {
    var nextOutcome: Outcome<Long, SubscribeError> = Outcome.Success(1L)

    /** Set to suspend the next (and subsequent) invokes until completed; `null` passes through. */
    var gate: CompletableDeferred<Unit>? = null

    val subscribed = mutableListOf<Pair<String, Set<Long>>>()

    override suspend fun invoke(
        previewId: String,
        groupIds: Set<Long>,
    ): Outcome<Long, SubscribeError> {
        subscribed += previewId to groupIds
        gate?.await()
        return nextOutcome
    }
}
