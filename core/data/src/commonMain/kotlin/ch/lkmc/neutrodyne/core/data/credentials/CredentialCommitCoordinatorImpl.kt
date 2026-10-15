// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.credentials

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.domain.CredentialCommitCoordinator
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The process-wide credential commit mutex of 03 Basic auth and CredentialStore. Only one
 * process ever opens the database (02 Platform constraints), so one `Mutex` suffices.
 *
 * `Mutex.withLock` is not reentrant and releases on cancellation as well as failure — both
 * deliberate, per the contract.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class CredentialCommitCoordinatorImpl : CredentialCommitCoordinator {
    private val mutex = Mutex()

    override suspend fun <T> withCredentialCommit(block: suspend () -> T): T = mutex.withLock { block() }
}
