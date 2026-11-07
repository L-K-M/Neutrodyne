// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.runtime.Composable
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.navigation.SyncHeldChangesKey
import ch.lkmc.neutrodyne.core.navigation.SyncSettingsKey
import ch.lkmc.neutrodyne.core.navigation.SyncSetupKey
import ch.lkmc.neutrodyne.sync.api.SyncController
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides

/** Contributes `:feature:sync`'s nav entries (08 Sync screens). */
@ContributesTo(AppScope::class)
@BindingContainer
object SyncNavigation {
    @Provides
    @IntoSet
    fun entries(syncController: Provider<SyncController>): EntryProviderInstaller =
        {
            entry<SyncSettingsKey>(metadata = NdSceneMetadata.paneDetail()) {
                SyncSettingsScreen(syncController())
            }
            entry<SyncSetupKey>(metadata = NdSceneMetadata.paneDetail()) { key ->
                SyncSetupScreen(syncController(), key.serverUrl)
            }
            entry<SyncHeldChangesKey>(metadata = NdSceneMetadata.dialog()) { key ->
                SyncHeldChangesRoute(syncController(), key.id)
            }
        }
}
