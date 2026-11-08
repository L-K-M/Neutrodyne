// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.discover

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.DirectoryKey
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.YouTubeSearchKey
import ch.lkmc.neutrodyne.feature.discover.add.AddPodcastRoute
import ch.lkmc.neutrodyne.feature.discover.youtube.YouTubeSearchRoute
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides

/**
 * Contributes `:feature:discover`'s nav entries into the `Set<EntryProviderInstaller>`
 * multibinding (01 Feature entry installers). `DiscoverKey` is the list pane of its tab's stack;
 * `AddPodcastKey` is the add-podcast sheet overlay (08 Screen inventory).
 */
@ContributesTo(AppScope::class)
@BindingContainer
public object DiscoverNavigation {
    @Provides
    @IntoSet
    public fun entries(): EntryProviderInstaller =
        {
            entry<DirectoryKey>(metadata = NdSceneMetadata.paneDetail()) { key -> DirectoryRoute(key) }
            entry<DiscoverKey>(metadata = NdSceneMetadata.paneList()) { DiscoverRoute() }
            entry<AddPodcastKey>(metadata = NdSceneMetadata.bottomSheet()) { key ->
                AddPodcastRoute(key)
            }
            entry<YouTubeSearchKey>(metadata = NdSceneMetadata.paneDetail()) { key ->
                YouTubeSearchRoute(key)
            }
        }
}
