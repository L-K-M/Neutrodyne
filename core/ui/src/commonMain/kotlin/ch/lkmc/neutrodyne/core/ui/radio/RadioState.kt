// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.radio

import ch.lkmc.neutrodyne.core.model.ArtworkRef
import ch.lkmc.neutrodyne.core.model.settings.PlaybackSettingKeys
import ch.lkmc.neutrodyne.playback.api.PlaybackIssue
import ch.lkmc.neutrodyne.playback.api.PlayerPhase

/** Immutable presentation data; production adapters retain repository IDs, never fixture indices. */
public data class RadioEpisode(
    val id: Long,
    val title: String,
    val podcast: String,
    val durationMs: Long? = null,
    val sortDate: Long = 0,
    /** The podcast's cover printed on the slip; null falls back to the podcast's monogram. */
    val podcastArtwork: ArtworkRef? = null,
    val podcastArtworkAvgArgb: Int? = null,
)

public data class RadioPlaylist(
    val id: Long,
    val name: String,
    val podcastCount: Int,
)

public data class RadioState(
    val playlists: List<RadioPlaylist> = emptyList(),
    /**
     * Whether [playlists] reflects the repository yet. Until it does, an empty list means
     * "not read", not "none", so the radio does not flash its first-tuning card on start.
     */
    val playlistsLoaded: Boolean = false,
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
    /**
     * The loaded player's phase, so the transport can say BUFFERING, ENDED or ERROR instead of
     * guessing from [playing]. Null when nothing is loaded or the phase is not tracked.
     */
    val phase: PlayerPhase? = null,
    /** The loaded player's current issue, such as a lost network or a metered block. */
    val issue: PlaybackIssue? = null,
    val removed: RadioEpisode? = null,
    /** The listener's skip intervals (Playback settings), printed on the keys beside the counter. */
    val skipBackMs: Long = PlaybackSettingKeys.SKIP_BACK_MS.default,
    val skipForwardMs: Long = PlaybackSettingKeys.SKIP_FORWARD_MS.default,
    /** The local start runs before a player snapshot is available, including during pre-play sync. */
    val startingEpisodeId: Long? = null,
    val playWhenReadyEpisodeId: Long? = null,
) {
    public val starting: Boolean get() = selectedId != null && startingEpisodeId == selectedId
    public val switchOn: Boolean get() =
        playing || starting ||
            (selectedId != null && playWhenReadyEpisodeId == selectedId)

    public val playlist: RadioPlaylist? get() = playlists.find { it.id == playlistId }

    /** The listener has no playlist yet: a first run, or every group deleted. */
    public val untuned: Boolean get() = playlistsLoaded && playlists.isEmpty()

    /**
     * The selected episode as the programme knows it, so a refresh that renames a title or
     * podcast reaches the player without waiting for the next player emission. The player's
     * snapshot is the fallback for an episode playing outside the tuned programme; its measured
     * duration still wins in [selectedDurationMs].
     */
    public val selected: RadioEpisode? get() =
        episodes.find { it.id == selectedId }
            ?: nowPlayingEpisode?.takeIf { it.id == selectedId }

    /** Prefer the player's measurement while allowing its metadata to arrive after the programme. */
    public val selectedDurationMs: Long?
        get() {
            val id = selectedId ?: return null
            val measured = nowPlayingEpisode?.takeIf { it.id == id }?.durationMs
            return measured?.takeIf { it > 0 } ?: episodes.find { it.id == id }?.durationMs?.takeIf { it > 0 }
        }
}

/** Four tubes: minute pairs, or an hour digit and H followed by a minute pair. */
public fun radioElapsed(positionMs: Long): String {
    val seconds = (positionMs.coerceAtLeast(0) / 1000)
    val minutes = seconds / 60
    if (minutes < 100) return "${minutes.toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
    val capped = minutes.coerceAtMost(9 * 60 + 59)
    return "${capped / 60}H:${(capped % 60).toString().padStart(2, '0')}"
}

/** The printed needle scale retains full minutes beyond the four-tube counter's limit. */
internal fun radioTimeline(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0) / 1000
    return "${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
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
