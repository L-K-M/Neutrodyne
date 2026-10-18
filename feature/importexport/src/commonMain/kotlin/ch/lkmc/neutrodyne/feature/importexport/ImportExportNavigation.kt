// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.ExportKey
import ch.lkmc.neutrodyne.core.navigation.ImportFileKey
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides

@ContributesTo(AppScope::class)
@BindingContainer
public object ImportExportNavigation {
    @Provides
    @IntoSet
    public fun entries(): EntryProviderInstaller =
        {
            entry<ExportKey>(metadata = NdSceneMetadata.dialog()) { ExportRoute(it) }
            entry<ImportFileKey>(metadata = NdSceneMetadata.dialog()) { ImportFileRoute() }
            entry<ImportKey>(metadata = NdSceneMetadata.paneDetail()) { ImportRoute(it) }
        }
}
