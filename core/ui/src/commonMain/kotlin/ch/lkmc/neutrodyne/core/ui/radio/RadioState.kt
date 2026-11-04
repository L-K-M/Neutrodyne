// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import ch.lkmc.neutrodyne.core.ui.UiText

/** Immutable presentation data; production adapters retain repository IDs, never fixture indices. */
public data class RadioEpisode(
    val id: Long,
    val title: String,
    val podcast: String,
    val durationMs: Long? = null,
    val sortDate: Long = 0,
)

public data class RadioPlaylist(
    val id: Long,
    val name: String,
    val podcastCount: Int,
)

public data class RadioPodcast(
    val id: Long,
    val name: String,
)

public data class RadioState(
    val playlists: List<RadioPlaylist> = emptyList(),
    val playlistId: Long? = null,
    val episodes: List<RadioEpisode> = emptyList(),
    val episodeCount: Int? = null,
    val complete: Boolean = true,
    val loading: Boolean = false,
    val loadError: Boolean = false,
    val selectedId: Long? = null,
    val nowPlayingEpisode: RadioEpisode? = null,
    val elapsedMs: Long = 0,
    val playing: Boolean = false,
    val hasPlayed: Boolean = false,
    val removed: RadioEpisode? = null,
    val podcasts: List<RadioPodcast> = emptyList(),
    val creating: Boolean = false,
    val createError: UiText? = null,
    val createdPlaylistId: Long? = null,
) {
    public val playlist: RadioPlaylist? get() = playlists.find { it.id == playlistId }
    public val selected: RadioEpisode? get() =
        nowPlayingEpisode?.takeIf { it.id == selectedId }
            ?: episodes.find { it.id == selectedId }
}

/** Four tubes: minute pairs, or an hour digit and H followed by a minute pair. */
public fun radioElapsed(positionMs: Long): String {
    val seconds = (positionMs.coerceAtLeast(0) / 1000)
    val minutes = seconds / 60
    if (minutes < 100) return "${minutes.toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
    val capped = minutes.coerceAtMost(9 * 60 + 59)
    return "${capped / 60}H:${(capped % 60).toString().padStart(2, '0')}"
}

public fun radioDiscardThreshold(widthDp: Float): Float = minOf(80f, maxOf(48f, widthDp * 0.28f))

/** Undo never rewinds selection, elapsed time or transport. */
public fun radioSelectionAfterRemoval(
    ids: List<Long>,
    selected: Long?,
    removed: Long,
): Long? {
    if (selected != removed) return selected
    val index = ids.indexOf(removed)
    val remaining = ids.filterNot { it == removed }
    return remaining.getOrNull(index.coerceAtMost(remaining.lastIndex))
}
