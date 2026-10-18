// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.importexport

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import ch.lkmc.neutrodyne.core.common.AppScope
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.navigation.BackupKey
import ch.lkmc.neutrodyne.core.navigation.EntryProviderInstaller
import ch.lkmc.neutrodyne.core.navigation.ImportKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.navigation.NdSceneMetadata
import ch.lkmc.neutrodyne.core.ui.EmptyState
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.back
import ch.lkmc.neutrodyne.core.ui.resources.import_backup_pending
import ch.lkmc.neutrodyne.core.ui.resources.import_backup_title
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import org.jetbrains.compose.resources.stringResource

/** Notification routes must resolve as soon as the work hosts can post them. */
@ContributesTo(AppScope::class)
@BindingContainer
public object ImportExportPendingNavigation {
    // shortcut: D1/D4 replace these unavailable entries when their detail screens land.
    @Provides
    @IntoSet
    public fun entries(): EntryProviderInstaller =
        {
            entry<ImportKey>(metadata = NdSceneMetadata.paneDetail()) { PendingDetails(it) }
            entry<BackupKey>(metadata = NdSceneMetadata.paneDetail()) { PendingDetails(it) }
        }
}

@Composable
private fun PendingDetails(key: NavKey) {
    val navigator = LocalAppNavigator.current
    EmptyState(
        icon = NdIcons.Settings,
        title = stringResource(Res.string.import_backup_title),
        body = stringResource(Res.string.import_backup_pending),
        actionLabel = stringResource(Res.string.back),
        onAction = { navigator.pop(key) },
    )
}
