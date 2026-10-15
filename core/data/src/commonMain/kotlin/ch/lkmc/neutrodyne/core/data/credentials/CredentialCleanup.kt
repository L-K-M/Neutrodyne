// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.credentials

import ch.lkmc.neutrodyne.core.common.Log
import ch.lkmc.neutrodyne.core.common.suspendRunCatching
import ch.lkmc.neutrodyne.core.database.CredentialDao
import ch.lkmc.neutrodyne.core.domain.SecretStore

/**
 * The failure cleanup of 03's commit coordination (Subscribe transaction step 2,
 * `setCredentials`): a `put` whose referencing write never landed is removed again — unless
 * another podcast references the row, which a shared origin's `put`-replace can leave linked
 * ("one credential per origin" — the replaced password stays). Callers run this inside
 * `withCredentialCommit`; a failed removal is logged, not thrown — it must never mask the
 * commit failure it cleans up after.
 */
internal suspend fun SecretStore.removeUnreferenced(
    dao: CredentialDao,
    origin: String,
    rowId: Long?,
) {
    // The reference check runs inside the guard too: when the database is why the commit
    // failed, it throws as well. Unknown or present references keep the credential; the
    // maintenance sweep removes true orphans.
    suspendRunCatching {
        if (rowId != null && dao.referenceCount(rowId) == 0) remove(origin)
    }.onFailure { e -> Log.w(TAG, e) { "credential rollback for a failed commit failed" } }
}

private const val TAG = "CredentialCommit"
