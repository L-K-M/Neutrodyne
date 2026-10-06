// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.discover

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.navigation.DiscoverKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides

/**
 * Contributes `:feature:discover`'s nav entries into the `Set<EntryProviderInstaller>`
 * multibinding (01 Feature entry installers). `DiscoverKey` is the list pane of its tab's stack;
 * the `AddPodcastKey` sheet arrives with M1 (08 Screen inventory).
 */
@ContributesTo(AppScope::class)
@BindingContainer
public object DiscoverNavigation {
    @Provides
    @IntoSet
    public fun entries(): EntryProviderInstaller = {
        entry<DiscoverKey>(metadata = NdSceneMetadata.paneList()) { DiscoverRoute() }
    }
}
