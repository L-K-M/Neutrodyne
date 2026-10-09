// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.navigation.AppNavigator
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener

/**
 * The YouTube watch link behind "Watch on YouTube" and external rows (04's canonical
 * `https://www.youtube.com/watch?v={id}`). A stopgap until 04's `YouTubeFeedUrls` lands with M8 —
 * the deviation is recorded in 08.
 */
public object YouTubeLinks {
    public fun watch(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"
}

/**
 * Routes the navigation and platform halves of an [EpisodeAction] (08 EpisodeRow): `Open` and
 * `OpenPodcast` push detail panes, `WatchOnYouTube` goes through the shell's [ExternalUrlOpener].
 * Returns `false` for repository-owned actions (`SetPlayed`, `PlayToggle`, `DownloadToggle`, …),
 * which the screen's ViewModel consumes.
 */
public fun dispatchEpisodeRoute(
    action: EpisodeAction,
    navigator: AppNavigator,
    urls: ExternalUrlOpener,
): Boolean =
    when (action) {
        is EpisodeAction.Open -> {
            navigator.pushDetail(EpisodeKey(action.episodeId))
            true
        }

        is EpisodeAction.OpenPodcast -> {
            navigator.pushDetail(PodcastKey(action.podcastId))
            true
        }

        is EpisodeAction.WatchOnYouTube -> {
            urls.open(YouTubeLinks.watch(action.videoId))
            true
        }

        else -> {
            false
        }
    }
