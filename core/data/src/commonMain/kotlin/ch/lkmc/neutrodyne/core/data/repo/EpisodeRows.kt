// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.data.repo

import ch.lkmc.neutrodyne.core.database.EpisodeRowProjection
import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.EpisodeRow

internal fun EpisodeRowProjection.toEpisodeRow(): EpisodeRow =
    EpisodeRow(
        id = id,
        podcastId = podcastId,
        title = title,
        podcastTitle = podcastTitle,
        sortDate = sortDate,
        pubDate = pubDate,
        durationMs = durationMs,
        isVideo = isVideo,
        isShort = isShort,
        availability = availability,
        episodeType = episodeType,
        episodeDisplay = episodeDisplay,
        sourceType = sourceType,
        externalMediaId = externalMediaId,
        isNew = isNew,
        firstSeenAt = firstSeenAt,
        artwork = ArtworkRef(artworkKey, artworkUrl, artworkVersion),
        artworkAvgArgb = artworkAvgArgb,
        podcastArtwork = ArtworkRef(podcastArtworkKey, podcastArtworkUrl, podcastArtworkVersion),
        podcastArtworkAvgArgb = podcastArtworkAvgArgb,
        playedAt = playedAt,
        startedAt = startedAt,
        isFavorite = isFavorite,
        downloadState = downloadState,
    )
