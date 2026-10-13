// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.navigation.AppNavigator
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult

/**
 * The YouTube watch link behind "Watch on YouTube" and external rows (04's canonical
 * `https://www.youtube.com/watch?v={id}`). A stopgap until 04's `YouTubeFeedUrls` lands with M8 —
 * the deviation is recorded in 08.
 */
public object YouTubeLinks {
    public fun watch(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"
}

/** How routing an [EpisodeAction] ended; see [dispatchEpisodeRoute]. */
public enum class EpisodeRouteResult {
    /** The action was routed — a detail push or a successfully opened external link. */
    HANDLED,

    /**
     * The external open found no handler (08: "No app can open this link" with a "Copy link"
     * action — the caller posts that `UserMessage`; `action_copy_link`/`no_handler`).
     */
    NO_HANDLER,

    /** Repository-owned action (`SetPlayed`, `PlayToggle`, `DownloadToggle`, …) — the ViewModel consumes it. */
    UNHANDLED,
}

/**
 * Routes the navigation and platform halves of an [EpisodeAction] (08 EpisodeRow): `Open` and
 * `OpenPodcast` push detail panes, `WatchOnYouTube` goes through the shell's [ExternalUrlOpener].
 * [EpisodeRouteResult.UNHANDLED] marks the repository-owned actions, [EpisodeRouteResult.NO_HANDLER]
 * a failed external open so the caller can show the no-handler snackbar.
 */
public fun dispatchEpisodeRoute(
    action: EpisodeAction,
    navigator: AppNavigator,
    urls: ExternalUrlOpener,
): EpisodeRouteResult =
    when (action) {
        is EpisodeAction.Open -> {
            navigator.pushDetail(EpisodeKey(action.episodeId))
            EpisodeRouteResult.HANDLED
        }

        is EpisodeAction.OpenPodcast -> {
            navigator.pushDetail(PodcastKey(action.podcastId))
            EpisodeRouteResult.HANDLED
        }

        is EpisodeAction.WatchOnYouTube -> {
            if (urls.open(YouTubeLinks.watch(action.videoId)) == OpenResult.NO_HANDLER) {
                EpisodeRouteResult.NO_HANDLER
            } else {
                EpisodeRouteResult.HANDLED
            }
        }

        else -> {
            EpisodeRouteResult.UNHANDLED
        }
    }
