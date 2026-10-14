// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.podcast

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.PodcastKey
import ch.lkmc.neutrodyne.core.navigation.PodcastSettingsKey
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides

/**
 * Contributes `:feature:podcast`'s nav entries (01 Feature entry installers): `PodcastKey` and
 * `PodcastSettingsKey` are detail panes of whichever tab pushed them (08 Screen inventory).
 */
@ContributesTo(AppScope::class)
@BindingContainer
public object PodcastNavigation {
    @Provides
    @IntoSet
    public fun entries(): EntryProviderInstaller =
        {
            entry<PodcastKey>(metadata = NdSceneMetadata.paneDetail()) { key -> PodcastRoute(key) }
            entry<PodcastSettingsKey>(metadata = NdSceneMetadata.paneDetail()) { key ->
                PodcastSettingsRoute(key)
            }
        }
}
