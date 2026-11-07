// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.UpNextKey
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedKeysTest {
    @Test
    fun stableTabKindAndItemIdentityNeverCollide() {
        val tabs = listOf(FeedsKey, LibraryKey, UpNextKey, DownloadsKey, DiscoverKey)
        val keys =
            tabs.flatMap { tab ->
                listOf(SharedKeys.cover(tab, 42), SharedKeys.episodeArt(tab, 42), SharedKeys.cover(tab, 43))
            }
        assertEquals(15, keys.toSet().size)
        assertEquals("library:cover:42", SharedKeys.cover(LibraryKey, 42))
        assertEquals("feeds:episode-art:42", SharedKeys.episodeArt(FeedsKey, 42))
        assertEquals("upnext:cover:42", SharedKeys.cover(UpNextKey, 42))
        assertEquals("downloads:episode-art:42", SharedKeys.episodeArt(DownloadsKey, 42))
        assertEquals("discover:cover:42", SharedKeys.cover(DiscoverKey, 42))
    }
}
