// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.discover

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.lkmc.neutrodyne.core.designsystem.components.NdEmptyState
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.discover_empty_body
import ch.lkmc.neutrodyne.core.ui.resources.discover_empty_title
import ch.lkmc.neutrodyne.core.ui.resources.nav_discover
import ch.lkmc.neutrodyne.core.ui.root.SettingsGearButton
import org.jetbrains.compose.resources.stringResource

/**
 * The M0a Discover destination (01 M0 checklist step 16): a top bar with the Settings gear and the
 * empty state. M1 delivers directory search and add-by-URL (08 Discover).
 */
@Composable
internal fun DiscoverRoute() {
    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(
            title = stringResource(Res.string.nav_discover),
            actions = { SettingsGearButton() },
        )
        NdEmptyState(
            icon = NdIcons.Explore,
            title = stringResource(Res.string.discover_empty_title),
            body = stringResource(Res.string.discover_empty_body),
        )
    }
}
