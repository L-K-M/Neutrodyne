// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui

import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.testing.navigation.RecordingAppNavigator
import ch.lkmc.neutrodyne.core.ui.platform.ExternalUrlOpener
import ch.lkmc.neutrodyne.core.ui.platform.OpenResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `dispatchEpisodeRoute`'s result contract (08: a `NO_HANDLER` open must reach the caller so it
 * can show "No app can open this link" + "Copy link" — a dropped `OpenResult` makes that
 * feedback unreachable).
 */
class EpisodeActionsTest {
    private val navigator = RecordingAppNavigator()

    @Test
    fun noHandlerSurfacesToTheCaller() {
        val result =
            dispatchEpisodeRoute(
                EpisodeAction.WatchOnYouTube(1, "abc"),
                navigator,
                ExternalUrlOpener { OpenResult.NO_HANDLER },
            )
        assertEquals(EpisodeRouteResult.NO_HANDLER, result)
    }

    @Test
    fun openedExternalLinkIsHandled() {
        val result =
            dispatchEpisodeRoute(
                EpisodeAction.WatchOnYouTube(1, "abc"),
                navigator,
                ExternalUrlOpener { OpenResult.OPENED },
            )
        assertEquals(EpisodeRouteResult.HANDLED, result)
    }

    @Test
    fun repositoryActionsStayUnhandled() {
        val result =
            dispatchEpisodeRoute(
                EpisodeAction.PlayToggle(1),
                navigator,
                ExternalUrlOpener { OpenResult.OPENED },
            )
        assertEquals(EpisodeRouteResult.UNHANDLED, result)
    }

    @Test
    fun openPushesTheEpisodeDetailPane() {
        val result =
            dispatchEpisodeRoute(
                EpisodeAction.Open(7),
                navigator,
                ExternalUrlOpener { OpenResult.OPENED },
            )
        assertEquals(EpisodeRouteResult.HANDLED, result)
        assertEquals(
            listOf<RecordingAppNavigator.Call>(
                RecordingAppNavigator.Call.PushDetail(EpisodeKey(7)),
            ),
            navigator.calls,
        )
    }

    @Test
    fun openPodcastPushesThePodcastDetailPane() {
        dispatchEpisodeRoute(
            EpisodeAction.OpenPodcast(episodeId = 1, podcastId = 9),
            navigator,
            ExternalUrlOpener { OpenResult.OPENED },
        )
        assertEquals(
            listOf<RecordingAppNavigator.Call>(
                RecordingAppNavigator.Call.PushDetail(PodcastKey(9)),
            ),
            navigator.calls,
        )
    }
}
