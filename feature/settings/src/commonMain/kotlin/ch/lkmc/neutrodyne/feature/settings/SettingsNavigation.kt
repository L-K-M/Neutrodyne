// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.settings

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.LicencesKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.SettingsHomeKey
import ch.lkmc.neutrodyne.core.navigation.SettingsKey
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides

/**
 * Contributes `:feature:settings`'s nav entries into the `Set<EntryProviderInstaller>`
 * multibinding (01 Feature entry installers).
 *
 * The shells' graph must bind a `BuildInfo` for the About page and may bind a [LicencesSource]
 * (nullable: when unbound the Licences screen shows its empty state). `SettingsHomeKey` is a list
 * pane whose desktop detail placeholder is the Appearance page (08 Settings screens);
 * `SettingsKey` and `LicencesKey` are detail panes (08 Screen inventory).
 */
@ContributesTo(AppScope::class)
@BindingContainer
public object SettingsNavigation {
    @Provides
    @IntoSet
    public fun entries(buildInfo: BuildInfo, licencesSource: LicencesSource?): EntryProviderInstaller = {
        entry<SettingsHomeKey>(
            metadata = NdSceneMetadata.paneList(detailPlaceholder = { AppearancePage() }),
        ) {
            SettingsHomeRoute()
        }
        entry<SettingsKey>(metadata = NdSceneMetadata.paneDetail()) { key ->
            SettingsRoute(key, buildInfo)
        }
        entry<LicencesKey>(metadata = NdSceneMetadata.paneDetail()) {
            LicencesRoute(licencesSource)
        }
    }
}
