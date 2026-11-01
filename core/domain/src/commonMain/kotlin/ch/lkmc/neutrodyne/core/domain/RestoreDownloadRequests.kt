// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.domain

import ch.lkmc.neutrodyne.core.model.DownloadLane
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.download.api.DownloadController
import ch.lkmc.neutrodyne.download.api.RequestResult
import dev.zacsweers.metro.Inject

/** Restore hints are an offer only. A visible user's tap enters the normal manual consent path. */
@Inject
class RestoreDownloadRequests(
    private val downloads: DownloadController,
    private val media: PlaybackMediaLookup,
) {
    suspend fun eligible(ids: List<Long>): List<Long> =
        ids.distinct().filter { id ->
            val row = media.get(id)
            row != null && row.sourceType == SourceType.RSS && row.downloadState != DownloadState.COMPLETED
        }

    suspend fun request(
        ids: List<Long>,
        allowMetered: Boolean? = null,
    ): RequestResult = downloads.request(eligible(ids), DownloadLane.MANUAL, allowMetered)
}
