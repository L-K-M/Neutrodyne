// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.feeds

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.FeedsKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides

/**
 * Contributes `:feature:feeds`'s nav entries into the `Set<EntryProviderInstaller>` multibinding
 * (01 Feature entry installers). `FeedsKey` is the list pane of its tab's stack; the group-mosaic
 * detail placeholder arrives with M2 (08 Screen inventory).
 */
@ContributesTo(AppScope::class)
@BindingContainer
public object FeedsNavigation {
    @Provides
    @IntoSet
    public fun entries(): EntryProviderInstaller =
        {
            entry<FeedsKey>(metadata = NdSceneMetadata.paneList()) { FeedsRoute() }
        }
}
