// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.GroupEditKey
import ch.lkmc.neutrodyne.core.navigation.GroupSettingsKey
import ch.lkmc.neutrodyne.core.navigation.GroupsManageKey
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides

@ContributesTo(AppScope::class)
@BindingContainer
public object GroupsNavigation {
    @Provides
    @IntoSet
    public fun entries(): EntryProviderInstaller =
        {
            entry<GroupsManageKey>(metadata = NdSceneMetadata.paneList()) { GroupsManageRoute() }
            entry<GroupSettingsKey>(metadata = NdSceneMetadata.paneDetail()) { key -> GroupSettingsRoute(key) }
            entry<GroupEditKey>(metadata = NdSceneMetadata.paneDetail()) { key -> GroupEditRoute(key) }
        }
}
