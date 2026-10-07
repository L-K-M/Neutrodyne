// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.core.navigation

import ch.lkmc.neutrodyne.core.navigation.RouteInput.Action
import kotlin.test.Test
import kotlin.test.assertEquals

class IntentRouterTest {
    @Test
    fun launcherStartMapsToNone() {
        assertEquals(Route.None, IntentRouter.route(RouteInput(Action.LAUNCH)))
        assertEquals(Route.None, IntentRouter.route(RouteInput(Action.LAUNCH, internal = true)))
    }

    @Test
    fun episodeRoutePushesEpisodeKey() {
        val route = IntentRouter.route(internalView("neutrodyne://open/episode/11"))

        assertEquals(Route.Push(EpisodeKey(episodeId = 11L)), route)
    }

    @Test
    fun malformedEpisodeIdsMapToNone() {
        assertEquals(Route.None, IntentRouter.route(internalView("neutrodyne://open/episode/abc")))
        assertEquals(Route.None, IntentRouter.route(internalView("neutrodyne://open/episode/")))
        assertEquals(Route.None, IntentRouter.route(internalView("neutrodyne://open/episode")))
    }

    @Test
    fun podcastRouteNavigatesToLibraryStack() {
        val route = IntentRouter.route(internalView("neutrodyne://open/podcast/7"))

        assertEquals(Route.Navigate(LibraryKey, listOf(PodcastKey(podcastId = 7L))), route)
    }

    @Test
    fun malformedPodcastIdMapsToNone() {
        assertEquals(Route.None, IntentRouter.route(internalView("neutrodyne://open/podcast/abc")))
    }

    @Test
    fun groupRouteSelectsFeed() {
        val route = IntentRouter.route(internalView("neutrodyne://open/group/abc-uuid"))

        assertEquals(Route.SelectFeed(groupUuid = "abc-uuid"), route)
    }

    @Test
    fun downloadsRouteNavigatesToDownloadsRoot() {
        val route = IntentRouter.route(internalView("neutrodyne://open/downloads"))

        assertEquals(Route.Navigate(DownloadsKey, emptyList()), route)
    }

    @Test
    fun playerRouteExpandsPlayer() {
        assertEquals(Route.ExpandPlayer, IntentRouter.route(internalView("neutrodyne://open/player")))
    }

    @Test
    fun importRouteNavigatesToLibraryStack() {
        val route = IntentRouter.route(internalView("neutrodyne://open/import/session-1"))

        assertEquals(Route.Navigate(LibraryKey, listOf(ImportKey(sessionId = "session-1"))), route)
    }

    @Test
    fun settingsRoutesPushSettingsKey() {
        assertEquals(
            Route.Push(SettingsKey(SettingsPage.ABOUT)),
            IntentRouter.route(internalView("neutrodyne://open/settings/about")),
        )
        assertEquals(
            Route.Push(SettingsKey(SettingsPage.UPDATES)),
            IntentRouter.route(internalView("neutrodyne://open/settings/UPDATES")),
        )
        assertEquals(
            Route.Push(SettingsKey(SettingsPage.YOUTUBE)),
            IntentRouter.route(internalView("neutrodyne://open/settings/Youtube")),
        )
    }

    @Test
    fun unknownSettingsPageMapsToNone() {
        assertEquals(Route.None, IntentRouter.route(internalView("neutrodyne://open/settings/nope")))
    }

    @Test
    fun syncSettingsRoutePushesSyncSettingsKey() {
        assertEquals(
            Route.Push(SyncSettingsKey),
            IntentRouter.route(internalView("neutrodyne://open/settings/sync")),
        )
    }

    @Test
    fun updatesReleaseRoutePushesSettingsKeyWithOpenRelease() {
        val route = IntentRouter.route(internalView("neutrodyne://open/settings/updates/release"))

        assertEquals(Route.Push(SettingsKey(SettingsPage.UPDATES, openRelease = true)), route)
    }

    @Test
    fun installHelpRoutePushesInstallHelpKey() {
        assertEquals(
            Route.Push(InstallHelpKey()),
            IntentRouter.route(internalView("neutrodyne://open/help/install")),
        )
        assertEquals(Route.None, IntentRouter.route(internalView("neutrodyne://open/help/other")))
    }

    @Test
    fun diagnosticsRoutePushesDiagnosticsKey() {
        assertEquals(
            Route.Push(DiagnosticsKey),
            IntentRouter.route(internalView("neutrodyne://open/diagnostics")),
        )
    }

    @Test
    fun externalInputsNeverReachInternalRoutes() {
        val external = RouteInput(Action.VIEW, uri = "neutrodyne://open/episode/11", internal = false)

        assertEquals(Route.None, IntentRouter.route(external))
    }

    @Test
    fun externalDeepLinksMapToNoneInM0a() {
        assertEquals(Route.None, IntentRouter.route(RouteInput(Action.VIEW, uri = "feed:https://example.com/x")))
        assertEquals(Route.None, IntentRouter.route(RouteInput(Action.SEND, text = "https://example.com/feed.xml")))
        assertEquals(Route.None, IntentRouter.route(RouteInput(Action.OPEN_FILE, uri = "file:///tmp/list.opml")))
        assertEquals(
            Route.None,
            IntentRouter.route(RouteInput(Action.VIEW, uri = "neutrodyne://subscribe?url=https://example.com/x")),
        )
    }

    @Test
    fun schemeMatchingIgnoresCaseAndQueries() {
        assertEquals(Route.ExpandPlayer, IntentRouter.route(internalView("NEUTRODYNE://open/player")))
        assertEquals(
            Route.ExpandPlayer,
            IntentRouter.route(internalView("neutrodyne://open/player?source=notification")),
        )
        assertEquals(Route.ExpandPlayer, IntentRouter.route(internalView("neutrodyne://open/player/")))
    }

    @Test
    fun unknownRoutesMapToNone() {
        assertEquals(Route.None, IntentRouter.route(internalView("neutrodyne://open/nope")))
        assertEquals(Route.None, IntentRouter.route(internalView("neutrodyne://other/player")))
        assertEquals(Route.None, IntentRouter.route(internalView("https://example.com/open/player")))
        assertEquals(Route.None, IntentRouter.route(RouteInput(Action.VIEW, uri = null, internal = true)))
        assertEquals(Route.None, IntentRouter.route(internalView("not a uri")))
    }

    private fun internalView(uri: String): RouteInput = RouteInput(Action.VIEW, uri = uri, internal = true)
}
