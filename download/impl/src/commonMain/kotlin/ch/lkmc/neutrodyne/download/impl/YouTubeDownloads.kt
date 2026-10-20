// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.download.impl

import ch.lkmc.neutrodyne.core.database.DownloadEntity
import ch.lkmc.neutrodyne.core.database.DownloadSourceRow
import ch.lkmc.neutrodyne.core.model.SourceKind
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.youtube.api.YouTubeIds

internal fun DownloadSourceRow.youtubeIdentity(): YouTubeDownloadIdentity? =
    if (sourceType == SourceType.YOUTUBE_CHANNEL && YouTubeIds.CHANNEL.matches(youtubeChannelId.orEmpty()) &&
        YouTubeIds.VIDEO.matches(externalMediaId.orEmpty())
    ) {
        YouTubeDownloadIdentity(podcastId, checkNotNull(externalMediaId))
    } else {
        null
    }

internal fun DownloadSourceRow.matches(row: DownloadEntity): Boolean =
    when (row.sourceKind) {
        SourceKind.RSS_ENCLOSURE -> sourceType == SourceType.RSS && youtubeChannelId == null
        SourceKind.YOUTUBE -> youtubeIdentity()?.videoId == row.sourceRef
    }
