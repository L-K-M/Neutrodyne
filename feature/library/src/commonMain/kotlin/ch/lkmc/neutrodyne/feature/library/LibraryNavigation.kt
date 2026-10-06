// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.library

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.LibraryKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides

/**
 * Contributes `:feature:library`'s nav entries into the `Set<EntryProviderInstaller>` multibinding
 * (01 Feature entry installers). `LibraryKey` is the list pane of its tab's stack (08 Screen
 * inventory).
 */
@ContributesTo(AppScope::class)
@BindingContainer
public object LibraryNavigation {
    @Provides
    @IntoSet
    public fun entries(): EntryProviderInstaller = {
        entry<LibraryKey>(metadata = NdSceneMetadata.paneList()) { LibraryRoute() }
    }
}
