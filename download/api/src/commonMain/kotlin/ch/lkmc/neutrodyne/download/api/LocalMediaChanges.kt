// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.api

import kotlinx.coroutines.flow.Flow

/** Separate from the synchronous index so loader lookups never wait for storage or readiness.
 * Emit changed IDs after committed completion/index publication, or index removal for loss/delete.
 * Subscribers re-read the index; playback reprojects only non-current window items. */
interface LocalMediaChanges {
    val changes: Flow<Set<Long>>
}

/** Main-safe initial snapshot barrier, separate from the non-blocking lookup. Failures propagate. */
fun interface LocalMediaReadiness {
    suspend fun awaitReady()
}
