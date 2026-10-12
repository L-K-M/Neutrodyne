// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.episode

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.EpisodeKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides

/**
 * Contributes `:feature:episode`'s nav entry (01 Feature entry installers). `EpisodeKey` is the
 * extra pane of wide layouts (08 Screen inventory).
 */
@ContributesTo(AppScope::class)
@BindingContainer
public object EpisodeNavigation {
    @Provides
    @IntoSet
    public fun entries(): EntryProviderInstaller =
        {
            entry<EpisodeKey>(metadata = NdSceneMetadata.paneExtra()) { key -> EpisodeRoute(key) }
        }
}
