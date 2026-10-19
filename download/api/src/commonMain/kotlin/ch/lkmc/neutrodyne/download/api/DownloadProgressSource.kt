// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.download.api

import kotlinx.coroutines.flow.Flow

interface DownloadProgressSource {
    /** Only requested active IDs, at most four emissions per second; never database ticks. */
    fun observe(ids: Set<Long>): Flow<Map<Long, LiveProgress>>
}
