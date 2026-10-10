// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.TopLevelKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The transition contract [NeutrodyneNavHost] hands to `NavDisplay`: tab taps are instant, a
 * forward push covers, a back pop (including the predictive gesture) wipes, overlays keep the
 * platform default. Rendered mid-transition frames are a device concern — this pins the mapping.
 */
class NdSceneTransitionTest {
    private fun entry(
        key: NavKey,
        tab: TopLevelKey,
    ): NavEntry<NavKey> = NavEntry(key, key, mapOf(NdSceneMetadata.KEY_TAB to tab)) {}

    private fun paneScene(
        vararg keys: NavKey,
        tab: TopLevelKey,
    ): Scene<NavKey> =
        object : Scene<NavKey> {
            override val key: Any = Any()
            override val entries: List<NavEntry<NavKey>> = keys.map { entry(it, tab) }
            override val previousEntries: List<NavEntry<NavKey>> = emptyList()
            override val content: @Composable () -> Unit = {}
        }

    private fun overlayScene(tab: TopLevelKey): Scene<NavKey> =
        object : OverlayScene<NavKey> {
            override val key: Any = Any()
            override val entries: List<NavEntry<NavKey>> = listOf(entry(FeedsKey, tab))
            override val overlaidEntries: List<NavEntry<NavKey>> = entries
            override val previousEntries: List<NavEntry<NavKey>> = emptyList()
            override val content: @Composable () -> Unit = {}
        }

    @Test
    fun tabSwitchIsInstant() {
        val feeds = paneScene(FeedsKey, tab = FeedsKey)
        val library = paneScene(LibraryKey, tab = LibraryKey)
        assertEquals(NdSceneTransition.Instant, classifyTransition(feeds, library))
        assertEquals(NdSceneTransition.Instant, classifyTransition(library, feeds))
    }

    @Test
    fun forwardPushWithinATabCovers() {
        val root = paneScene(FeedsKey, tab = FeedsKey)
        val detail = paneScene(FeedsKey, PodcastKey(3L), tab = FeedsKey)
        assertEquals(NdSceneTransition.SlideInCover, classifyTransition(root, detail))
    }

    /**
     * Tapping Feeds while on Library yields the same strict-prefix shape as a real back to
     * Feeds — without the action marker the tap must not wipe.
     */
    @Test
    fun tabTapShapedLikeAPopIsInstant() {
        val library = paneScene(LibraryKey, tab = LibraryKey)
        val feeds = paneScene(FeedsKey, tab = FeedsKey)
        assertEquals(NdSceneTransition.Instant, classifyPopTransition(library, feeds, lastChangeWasPop = false))
    }

    @Test
    fun backPopWipesTheOutgoingScene() {
        val detail = paneScene(FeedsKey, PodcastKey(3L), tab = FeedsKey)
        val root = paneScene(FeedsKey, tab = FeedsKey)
        assertEquals(
            NdSceneTransition.SlideOutWipe,
            classifyPopTransition(detail, root, lastChangeWasPop = true),
        )
    }

    @Test
    fun predictivePopWipesForContentAndDefersToPlatformForOverlays() {
        val detail = paneScene(FeedsKey, PodcastKey(3L), tab = FeedsKey)
        val root = paneScene(FeedsKey, tab = FeedsKey)
        assertEquals(NdSceneTransition.SlideOutWipe, classifyPredictivePopTransition(detail, root))
        assertEquals(
            NdSceneTransition.PlatformDefault,
            classifyPredictivePopTransition(overlayScene(FeedsKey), root),
        )
    }

    @Test
    fun overlaysKeepThePlatformDefault() {
        val feeds = paneScene(FeedsKey, tab = FeedsKey)
        val overlay = overlayScene(FeedsKey)
        assertEquals(NdSceneTransition.PlatformDefault, classifyTransition(feeds, overlay))
        assertEquals(NdSceneTransition.PlatformDefault, classifyTransition(overlay, feeds))
        assertEquals(
            NdSceneTransition.PlatformDefault,
            classifyPopTransition(overlay, feeds, lastChangeWasPop = true),
        )
    }

    /** A scene whose top entry lost its stamp must not be misread as a different tab. */
    @Test
    fun unstampedSceneIsNotATabSwitch() {
        val feeds = paneScene(FeedsKey, tab = FeedsKey)
        val unstamped =
            object : Scene<NavKey> {
                override val key: Any = Any()
                override val entries: List<NavEntry<NavKey>> = listOf(NavEntry(FeedsKey, FeedsKey) {})
                override val previousEntries: List<NavEntry<NavKey>> = emptyList()
                override val content: @Composable () -> Unit = {}
            }
        assertEquals(NdSceneTransition.SlideInCover, classifyTransition(feeds, unstamped))
    }

    /**
     * The marker only `pop()` sets distinguishes the real back action — forward mutations and
     * re-selections must clear it so the next pop-shaped transition classifies as a tap.
     */
    @Test
    fun lastChangeWasPopTracksTheActionOrigin() {
        val state =
            NavigationState(
                stacks =
                    TOP_LEVEL_TABS.associateWith { tab -> NavBackStack<NavKey>(tab) },
                selected = mutableStateOf(LibraryKey),
            )
        state.push(PodcastKey(7L))
        assertEquals(false, state.lastChangeWasPop)

        state.pop()
        assertEquals(true, state.lastChangeWasPop)

        state.push(PodcastKey(8L))
        assertEquals(false, state.lastChangeWasPop)

        state.selectTab(DiscoverKey)
        assertEquals(false, state.lastChangeWasPop)

        // Popping from a non-Feeds root selects Feeds — still a back action.
        state.pop()
        assertEquals(true, state.lastChangeWasPop)
        assertEquals(FeedsKey, state.selectedTab)
    }
}
