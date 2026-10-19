// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import ch.lkmc.neutrodyne.core.artwork.ArtworkStore
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.PlaybackMediaInfo
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.download.api.LocalMediaIndex
import dev.zacsweers.metro.Inject
import java.time.Instant
import java.time.ZoneOffset

/** Build on IO: checking pinned artwork may touch storage. Position is a one-shot snapshot. */
@Inject
internal class MediaItemFactory(
    private val artwork: ArtworkStore,
    private val localMedia: LocalMediaIndex,
) {
    fun artworkUri(
        key: String,
        version: Int = 0,
    ): android.net.Uri = artwork.contentUri(key, version).toUri()

    fun build(
        row: PlaybackMediaInfo,
        positionMs: Long? = null,
    ): MediaItem {
        val uri = episodeUri(row.episodeId)
        val local = localMedia.localUriOrNull(row.episodeId) != null
        val art =
            if (row.sourceType == SourceType.RSS &&
                artwork.pinnedFile(row.artwork.key) != null
            ) {
                row.artwork
            } else {
                row.podcastArtwork
            }
        val duration = (row.measuredDurationMs ?: row.durationMs)?.takeIf { it > 0 }
        val extras = Bundle()
        extras.putLong(
            MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS,
            when (row.downloadState) {
                DownloadState.COMPLETED -> MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED
                DownloadState.DOWNLOADING -> MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADING
                else -> MediaConstants.EXTRAS_VALUE_STATUS_NOT_DOWNLOADED
            },
        )
        val status =
            when {
                row.playedAt != null -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
                positionMs != null && positionMs > 0 -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
                else -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
            }
        extras.putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, status)
        if (status == MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED && duration != null) {
            extras.putDouble(
                MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE,
                (positionMs!!.toDouble() / duration).coerceIn(0.0, 1.0),
            )
        }
        val key =
            if (row.sourceType == SourceType.RSS) {
                row.audioSource()?.let { EnclosureFingerprint.cacheKey(row.episodeId, it.url, it.length) }
            } else {
                row.externalMediaId?.let { "yt:$it" }
            }
        return MediaItem
            .Builder()
            .setMediaId("episode:${row.episodeId}")
            .setUri(uri)
            .setCustomCacheKey(key)
            .setRequestMetadata(
                MediaItem.RequestMetadata
                    .Builder()
                    .setMediaUri(uri)
                    .setExtras(Bundle().apply { putBoolean(LOCAL_HINT, local) })
                    .build(),
            ).setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(row.title)
                    .setDisplayTitle(row.title)
                    .setArtist(row.podcastTitle)
                    .setAlbumTitle(row.podcastTitle)
                    .setAlbumArtist(row.podcastAuthor)
                    .setArtworkUri(artwork.contentUri(art.key, art.version).toUri())
                    .setDurationMs(duration)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setExtras(extras)
                    .apply {
                        row.pubDate?.let { Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC) }?.let { date ->
                            setReleaseYear(date.year)
                            setReleaseMonth(date.monthValue)
                            setReleaseDay(date.dayOfMonth)
                        }
                    }.build(),
            ).build()
    }

    companion object {
        const val LOCAL_HINT = "nd.local"
    }
}
