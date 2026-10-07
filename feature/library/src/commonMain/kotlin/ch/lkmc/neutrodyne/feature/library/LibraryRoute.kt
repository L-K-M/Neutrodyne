// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.lkmc.neutrodyne.core.designsystem.components.NdEmptyState
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.library_empty_body
import ch.lkmc.neutrodyne.core.ui.resources.library_empty_title
import ch.lkmc.neutrodyne.core.ui.resources.nav_library
import ch.lkmc.neutrodyne.core.ui.root.SettingsGearButton
import org.jetbrains.compose.resources.stringResource

/**
 * The M0a Library destination (01 M0 checklist step 16): a top bar with the Settings gear and the
 * empty state. M1 delivers the subscription cover grid (08 Library).
 */
@Composable
internal fun LibraryRoute() {
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(
            title = stringResource(Res.string.nav_library),
            actions = { SettingsGearButton() },
        )
        NdEmptyState(
            icon = NdIcons.GridView,
            title = stringResource(Res.string.library_empty_title),
            body = stringResource(Res.string.library_empty_body),
        )
    }
}
