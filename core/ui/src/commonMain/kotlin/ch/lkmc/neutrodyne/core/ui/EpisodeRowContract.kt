// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import androidx.compose.runtime.Immutable
import ch.lkmc.neutrodyne.core.model.DownloadError
import ch.lkmc.neutrodyne.core.model.DownloadState
import ch.lkmc.neutrodyne.core.model.SwipeAction
import ch.lkmc.neutrodyne.core.model.WaitReason

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

/**
 * The overlay a live row needs on top of the paged `EpisodeRow` (08 Live row state; the
 * `EpisodeLiveStateSource` arrives with M4's playback). All fields default to "nothing live".
 */
@Immutable
public data class RowLive(
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val downloadState: DownloadState? = null,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val waitReason: WaitReason? = null,
    val nextAttemptAt: Long? = null,
    val lastError: DownloadError? = null,
    /** In the player as the current item. */
    val isNowPlaying: Boolean = false,
    /** And currently advancing (vs paused). */
    val isPlaying: Boolean = false,
) {
    /** Progress as 0..1, `null` when indeterminate or not downloading. */
    public fun downloadProgress(): Float? =
        downloadedBytes?.let { done ->
            val total = totalBytes ?: return null
            if (total <= 0) null else (done.toFloat() / total).coerceIn(0f, 1f)
        }
}

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
    ) : EpisodeAction

    public data class OpenPodcast(
        override val episodeId: Long,
        val podcastId: Long,
    ) : EpisodeAction

    public data class WatchOnYouTube(
        override val episodeId: Long,
        val videoId: String,
    ) : EpisodeAction

    /** Long-press / toggle in selection mode (M2). */
    public data class Select(
        override val episodeId: Long,
    ) : EpisodeAction

    /** Greyed YouTube rows with the engine (04 "Check again"). */
    public data class CheckAvailability(
        override val episodeId: Long,
    ) : EpisodeAction
}
