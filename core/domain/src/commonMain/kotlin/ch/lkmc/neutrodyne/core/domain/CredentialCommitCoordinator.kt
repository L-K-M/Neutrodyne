// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.domain

/**
 * The commit coordinator of 03 Basic auth and CredentialStore (contract owned here with
 * [SecretStore]; impl in `:core:data` commonMain, its own process-wide `Mutex`).
 */
interface CredentialCommitCoordinator {
    /**
     * Runs [block] mutually excluded with every credential delete (the `db-maintenance`
     * sweep, the last-reference delete of `deleteCascade`). Acquire before entering any Room
     * write transaction; never acquire while holding the Room writer (single writer + mutex =
     * deadlock). Not reentrant: a holder must not call it again.
     */
    suspend fun <T> withCredentialCommit(block: suspend () -> T): T
}
