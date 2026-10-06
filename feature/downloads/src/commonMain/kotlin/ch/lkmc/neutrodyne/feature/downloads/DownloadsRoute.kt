// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.lkmc.neutrodyne.core.designsystem.components.NdEmptyState
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.downloads_empty_body
import ch.lkmc.neutrodyne.core.ui.resources.downloads_empty_title
import ch.lkmc.neutrodyne.core.ui.resources.nav_downloads
import ch.lkmc.neutrodyne.core.ui.root.SettingsGearButton
import org.jetbrains.compose.resources.stringResource

/**
 * The M0a Downloads destination (01 M0 checklist step 16): a top bar with the Settings gear and
 * the empty state. M6 delivers the download lists and the failed-count badge (08 Downloads).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadsRoute() {
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(
            title = stringResource(Res.string.nav_downloads),
            actions = { SettingsGearButton() },
        )
        NdEmptyState(
            icon = NdIcons.Download,
            title = stringResource(Res.string.downloads_empty_title),
            body = stringResource(Res.string.downloads_empty_body),
        )
    }
}
