// SPDX-License-Identifier: Unlicense

package ch.lkmc.neutrodyne.feature.discover

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.lkmc.neutrodyne.core.designsystem.components.NdAssistChip
import ch.lkmc.neutrodyne.core.designsystem.components.NdEmptyState
import ch.lkmc.neutrodyne.core.designsystem.components.NdTopAppBar
import ch.lkmc.neutrodyne.core.designsystem.icons.NdIcons
import ch.lkmc.neutrodyne.core.navigation.AddPodcastKey
import ch.lkmc.neutrodyne.core.navigation.LocalAppNavigator
import ch.lkmc.neutrodyne.core.ui.resources.Res
import ch.lkmc.neutrodyne.core.ui.resources.discover_add_url
import ch.lkmc.neutrodyne.core.ui.resources.discover_empty_body
import ch.lkmc.neutrodyne.core.ui.resources.discover_empty_title
import ch.lkmc.neutrodyne.core.ui.resources.nav_discover
import ch.lkmc.neutrodyne.core.ui.root.SettingsGearButton
import org.jetbrains.compose.resources.stringResource

/**
 * The Discover destination (08 Discover): M1a delivers the "Add by URL" assist chip over the empty
 * state; the search bar, charts, "Add YouTube channel" and "Import" chips arrive with M3/M7/M9a
 * (deviation recorded in 08, 2026-10-07).
 */
@Composable
internal fun DiscoverRoute() {
    val navigator = LocalAppNavigator.current

    Column(Modifier.fillMaxSize()) {
        NdTopAppBar(
            title = stringResource(Res.string.nav_discover),
            actions = { SettingsGearButton() },
        )
        NdAssistChip(
            onClick = { navigator.push(AddPodcastKey(null)) },
            label = stringResource(Res.string.discover_add_url),
            leadingIcon = NdIcons.Add,
            modifier = Modifier.padding(horizontal = CHIP_PADDING),
        )
        NdEmptyState(
            icon = NdIcons.Explore,
            title = stringResource(Res.string.discover_empty_title),
            body = stringResource(Res.string.discover_empty_body),
        )
    }
}

private val CHIP_PADDING = 16.dp
