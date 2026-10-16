// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.groups

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.navigation.AddToGroupsKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides

@ContributesTo(AppScope::class)
@BindingContainer
internal object GroupsNavigation {
    @Provides
    @IntoSet
    fun entries(): EntryProviderInstaller =
        {
            entry<AddToGroupsKey>(metadata = NdSceneMetadata.bottomSheet()) { AddToGroupsRoute(it) }
        }
}
