// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.playback.impl

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import ch.lkmc.neutrodyne.core.database.DatabaseOpener
import ch.lkmc.neutrodyne.core.domain.PlaySessionStore
import ch.lkmc.neutrodyne.core.domain.PlaybackMediaLookup
import ch.lkmc.neutrodyne.playback.core.refusal
import ch.lkmc.neutrodyne.youtube.api.YouTubeCapabilitiesSource
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider

/** M4 browse surface: current + playable Up next. Context nodes and search arrive in M5. */
@Inject
internal class MediaLibraryTree(
    private val opener: DatabaseOpener,
    private val sessions: Provider<PlaySessionStore>,
    private val media: Provider<PlaybackMediaLookup>,
    private val items: Provider<MediaItemFactory>,
    private val capabilities: YouTubeCapabilitiesSource,
) {
    fun root(): MediaItem = folder(ROOT, "Neutrodyne")

    suspend fun children(
        parentId: String,
        page: Int,
        pageSize: Int,
    ): List<MediaItem> {
        require(page >= 0 && pageSize > 0)
        val children =
            when (parentId) {
                ROOT -> listOf(folder(UP_NEXT, "Up next"))
                UP_NEXT -> upNext()
                else -> throw IllegalArgumentException("Unknown browse parent")
            }
        val start = (page.toLong() * pageSize).coerceAtMost(children.size.toLong()).toInt()
        return children.drop(start).take(pageSize.coerceAtMost(MAX_ITEMS))
    }

    suspend fun item(mediaId: String): MediaItem? =
        when (mediaId) {
            ROOT -> root()
            UP_NEXT -> folder(UP_NEXT, "Up next")
            else -> upNext().firstOrNull { it.mediaId == mediaId }
        }

    private suspend fun upNext(): List<MediaItem> {
        opener.awaitOpen()
        val queue = sessions().virtualQueue(0)
        val ids = (listOfNotNull(queue.current) + queue.upNext).map { it.episodeId }.distinct()
        val result = mutableListOf<MediaItem>()
        for (id in ids) {
            val row = media().get(id) ?: continue
            if (row.refusal(capabilities.capabilities.value) != null) continue
            result +=
                items()
                    .build(row)
                    .buildUpon()
                    .setMediaId("episode:$id@upnext")
                    .build()
            if (result.size == MAX_ITEMS) break
        }
        return result
    }

    private fun folder(
        id: String,
        title: String,
    ): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            ).build()

    companion object {
        const val ROOT = "root"
        const val UP_NEXT = "upnext"
        private const val MAX_ITEMS = 100
    }
}
