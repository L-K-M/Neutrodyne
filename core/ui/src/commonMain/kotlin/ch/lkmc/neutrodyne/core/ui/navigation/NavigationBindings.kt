// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.core.ui.navigation

import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds

/**
 * Declares the `Set<EntryProviderInstaller>` multibinding (01 Feature entry installers): features
 * contribute one installer each through `@Provides @IntoSet` in their own contributed binding
 * containers; the shells hand the aggregated set to `NeutrodyneRoot`. `allowEmpty` keeps both
 * graphs compiling while no feature contributes.
 */
@ContributesTo(AppScope::class)
@BindingContainer
public interface NavigationBindings {
    @Multibinds(allowEmpty = true)
    public fun entryProviderInstallers(): Set<EntryProviderInstaller>
}
