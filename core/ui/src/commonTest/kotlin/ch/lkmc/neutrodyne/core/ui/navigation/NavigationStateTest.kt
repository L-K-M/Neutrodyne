// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.PodcastPreviewKey
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.TopLevelKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The back-order rules of [NavigationState] (08 Back handling order): per-tab stacks, non-Feeds
 * root back returns to Feeds, Feeds root back returns false.
 */
class NavigationStateTest {

    private fun state(initial: TopLevelKey = FeedsKey): NavigationState {
        val stacks = TOP_LEVEL_TABS.associateWith { tab -> NavBackStack<NavKey>().apply { add(tab) } }
        return NavigationState(stacks, mutableStateOf(initial))
    }

    @Test
    fun `push adds to the selected tab's stack only`() {
        val nav = state()
        nav.selectTab(LibraryKey)
        nav.push(PodcastKey(7))
        nav.selectTab(FeedsKey)
        nav.push(SettingsHomeKey)

        assertEquals(listOf(FeedsKey, SettingsHomeKey), nav.stack(FeedsKey).toList())
        assertEquals(listOf(LibraryKey, PodcastKey(7)), nav.stack(LibraryKey).toList())
        assertEquals(listOf(UpNextKey), nav.stack(UpNextKey).toList())
    }

    @Test
    fun `selectTab keeps the previous tab's stack`() {
        val nav = state()
        nav.selectTab(LibraryKey)
        nav.push(PodcastKey(7))
        nav.selectTab(DiscoverKey)
        nav.selectTab(LibraryKey)

        assertEquals(listOf(LibraryKey, PodcastKey(7)), nav.stack(LibraryKey).toList())
    }

    @Test
    fun `pop removes the top of the selected stack`() {
        val nav = state()
        nav.push(PodcastKey(1))
        nav.push(PodcastKey(2))

        assertTrue(nav.pop())
        assertEquals(listOf(FeedsKey, PodcastKey(1)), nav.stack(FeedsKey).toList())
    }

    @Test
    fun `pop at a non-Feeds root returns to Feeds`() {
        val nav = state()
        nav.selectTab(DownloadsKey)

        assertTrue(nav.pop())
        assertEquals(FeedsKey, nav.selectedTab)
        assertEquals(listOf(DownloadsKey), nav.stack(DownloadsKey).toList())
    }

    @Test
    fun `pop at the Feeds root returns false`() {
        val nav = state()

        assertFalse(nav.pop())
        assertEquals(FeedsKey, nav.selectedTab)
        assertEquals(listOf(FeedsKey), nav.stack(FeedsKey).toList())
    }

    @Test
    fun `resetTab pops back to the tab's root`() {
        val nav = state()
        nav.selectTab(LibraryKey)
        nav.push(PodcastKey(1))
        nav.push(PodcastKey(2))

        nav.resetTab(LibraryKey)
        assertEquals(listOf(LibraryKey), nav.stack(LibraryKey).toList())
    }

    @Test
    fun `open replaces a tab's stack and selects it`() {
        val nav = state()
        nav.selectTab(LibraryKey)
        nav.push(PodcastKey(1))

        nav.open(DiscoverKey, listOf(AddPodcastKey(null), PodcastPreviewKey("https://x")))
        assertEquals(DiscoverKey, nav.selectedTab)
        assertEquals(
            listOf(DiscoverKey, AddPodcastKey(null), PodcastPreviewKey("https://x")),
            nav.stack(DiscoverKey).toList(),
        )
    }

    @Test
    fun `pushDetail replaces a same-class top entry on wide panes`() {
        val nav = state()
        nav.panePartitions = 2
        nav.selectTab(LibraryKey)
        nav.pushDetail(PodcastKey(1))
        nav.pushDetail(PodcastKey(2))

        assertEquals(listOf(LibraryKey, PodcastKey(2)), nav.stack(LibraryKey).toList())
    }

    @Test
    fun `pushDetail stacks a different-class top entry`() {
        val nav = state()
        nav.panePartitions = 2
        nav.selectTab(LibraryKey)
        nav.push(PodcastKey(1))
        nav.pushDetail(PodcastPreviewKey("https://x"))

        assertEquals(listOf(LibraryKey, PodcastKey(1), PodcastPreviewKey("https://x")), nav.stack(LibraryKey).toList())
    }

    @Test
    fun `pushDetail stacks on one pane`() {
        val nav = state()
        nav.selectTab(LibraryKey)
        nav.pushDetail(PodcastKey(1))
        nav.pushDetail(PodcastKey(2))

        assertEquals(listOf(LibraryKey, PodcastKey(1), PodcastKey(2)), nav.stack(LibraryKey).toList())
    }

    @Test
    fun `overlay keys push onto the selected tab's stack`() {
        val nav = state()
        nav.selectTab(DownloadsKey)
        nav.push(AddPodcastKey("https://feed"))

        assertEquals(listOf(DownloadsKey, AddPodcastKey("https://feed")), nav.stack(DownloadsKey).toList())
        assertEquals(listOf(FeedsKey), nav.stack(FeedsKey).toList())
    }
}
