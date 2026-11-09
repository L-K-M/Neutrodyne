// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.sync

import androidx.compose.runtime.Composable
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.domain.BackupRepository
import ch.lkmc.neutrodyne.core.domain.SyncServerAddress
import ch.lkmc.neutrodyne.core.model.BuildInfo
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
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
    fun entries(
        syncController: Provider<SyncController>,
        backups: Provider<BackupRepository>,
        address: Provider<SyncServerAddress>,
        buildInfo: BuildInfo,
    ): EntryProviderInstaller =
        {
            entry<SyncSettingsKey>(metadata = NdSceneMetadata.paneDetail()) {
                SyncSettingsScreen(
                    syncController(),
                    address(),
                    "${buildInfo.repoUrl}#run-the-server",
                )
            }
            entry<SyncSetupKey>(metadata = NdSceneMetadata.paneDetail()) { key ->
                SyncSetupScreen(syncController(), key.serverUrl, backups())
            }
        }
}
