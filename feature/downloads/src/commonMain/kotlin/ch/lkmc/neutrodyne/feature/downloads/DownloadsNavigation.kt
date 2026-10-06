// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.downloads

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.navigation.DownloadsKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides

/**
 * Contributes `:feature:downloads`'s nav entries into the `Set<EntryProviderInstaller>`
 * multibinding (01 Feature entry installers). `DownloadsKey` is the list pane of its tab's stack
 * (08 Screen inventory).
 */
@ContributesTo(AppScope::class)
@BindingContainer
public object DownloadsNavigation {
    @Provides
    @IntoSet
    public fun entries(): EntryProviderInstaller = {
        entry<DownloadsKey>(metadata = NdSceneMetadata.paneList()) { DownloadsRoute() }
    }
}
