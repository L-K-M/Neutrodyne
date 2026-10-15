// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.testing

import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [CredentialCommitCoordinator] with the real contract — mutual exclusion, release on
 * cancellation and failure — plus a [commits] counter for tests asserting a path ran under
 * coordination.
 */
class FakeCredentialCommitCoordinator : CredentialCommitCoordinator {
    private val mutex = Mutex()

    /** How many [withCredentialCommit] blocks have entered. */
    var commits = 0
        private set

    override suspend fun <T> withCredentialCommit(block: suspend () -> T): T =
        mutex.withLock {
            commits++
            block()
        }
}
