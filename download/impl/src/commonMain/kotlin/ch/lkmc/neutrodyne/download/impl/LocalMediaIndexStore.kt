// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.download.api.LocalMediaChanges
import ch.lkmc.neutrodyne.download.api.LocalMediaIndex

/** W5 owns the atomic snapshot and notifications. W4 publishes only after completion commit;
 * W5 removes before deletion/MISSING and replaces after startup/reconcile. Readers never wait. */
internal interface LocalMediaIndexStore :
    LocalMediaIndex,
    LocalMediaChanges {
    suspend fun replace(snapshot: Map<Long, String>)

    suspend fun put(
        episodeId: Long,
        uri: String,
    )

    suspend fun remove(episodeId: Long)
}
