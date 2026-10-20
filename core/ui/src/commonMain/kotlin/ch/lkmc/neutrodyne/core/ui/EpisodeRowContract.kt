// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.runtime.Immutable
import ch.lkmc.neutrodyne.core.model.SourceType
import ch.lkmc.neutrodyne.core.model.SwipeAction

/** Row layout variants (08 EpisodeRow); Downloads uses its own `DownloadEntryRow`. */
public enum class EpisodeRowStyle {
    FEED,
    PODCAST,
    QUEUE,
}

/**
 * Capability and user gates the row honours (08 EpisodeRow). [inAppPlayback]/[downloads] come from
 * `YouTubeCapabilities` for YouTube rows; [recheck] enables "Check again"; [swipe] is null in Feeds
 * unless `appearance.feeds_row_swipe` and always null on the desktop; [offline] greys the play
 * affordance of episodes without a download.
 */
@Immutable
public data class RowCaps(
    val inAppPlayback: Boolean,
    val downloads: Boolean,
    val recheck: Boolean,
    val swipe: SwipeConfig?,
    val offline: Boolean,
) {
    public companion object {
        /** Plain RSS playback, everything allowed (the M1a default). */
        public val FULL: RowCaps =
            RowCaps(inAppPlayback = true, downloads = true, recheck = false, swipe = null, offline = false)
    }
}

/** Swipe directions (08; [SwipeAction.NONE] disables a side). */
@Immutable
public data class SwipeConfig(
    val startToEnd: SwipeAction,
    val endToStart: SwipeAction,
)

public enum class PlayedActionOrigin { ROW, SWIPE }

/** Everything a row's tap targets dispatch (08 EpisodeRow). */
public sealed interface EpisodeAction {
    public val episodeId: Long

    public data class Open(
        override val episodeId: Long,
    ) : EpisodeAction

    public data class PlayToggle(
        override val episodeId: Long,
    ) : EpisodeAction

    public data class PlayNext(
        override val episodeId: Long,
    ) : EpisodeAction

    public data class PlayLast(
        override val episodeId: Long,
    ) : EpisodeAction

    public data class DownloadToggle(
        override val episodeId: Long,
    ) : EpisodeAction

    public data class SetPlayed(
        override val episodeId: Long,
        val played: Boolean,
        val origin: PlayedActionOrigin = PlayedActionOrigin.ROW,
    ) : EpisodeAction

    public data class OpenPodcast(
        override val episodeId: Long,
        val podcastId: Long,
    ) : EpisodeAction

    public data class WatchOnYouTube(
        override val episodeId: Long,
        val videoId: String,
        val mode: YouTubeWatchMode = YouTubeWatchMode.EXTERNAL_OR_UNPLAYABLE,
    ) : EpisodeAction

    /** Long-press / toggle in selection mode (M2). */
    public data class Select(
        override val episodeId: Long,
    ) : EpisodeAction

    /** Shift-click extends from the selection's last toggled loaded row. */
    public data class ExtendSelection(
        override val episodeId: Long,
    ) : EpisodeAction

    /** Greyed YouTube rows with the engine (04 "Check again"). */
    public data class CheckAvailability(
        override val episodeId: Long,
    ) : EpisodeAction
}

/** RSS keeps native actions when mixed with external channels. */
public fun RowCaps.forSource(source: SourceType): RowCaps =
    if (source == SourceType.RSS) copy(inAppPlayback = true, downloads = true, recheck = false) else this

public enum class YouTubeWatchMode { EXTERNAL_OR_UNPLAYABLE, PLAYABLE_OVERFLOW }
