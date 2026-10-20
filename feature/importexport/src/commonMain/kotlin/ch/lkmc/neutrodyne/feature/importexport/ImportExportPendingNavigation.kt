// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feature.importexport

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.navigation.BackupKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides

@ContributesTo(AppScope::class)
@BindingContainer
public object ImportExportPendingNavigation {
    @Provides
    @IntoSet
    public fun entries(buildInfo: BuildInfo? = null): EntryProviderInstaller =
        {
            entry<BackupKey>(metadata = NdSceneMetadata.paneDetail()) { BackupRoute(debug = buildInfo?.debug == true) }
        }
}
